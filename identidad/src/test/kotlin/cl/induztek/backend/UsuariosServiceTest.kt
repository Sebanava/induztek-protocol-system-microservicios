package cl.induztek.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import cl.induztek.protocol.domain.Claves
import java.util.UUID
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.server.ResponseStatusException

@SpringBootTest(properties = ["spring.datasource.url=jdbc:h2:mem:identidad-test;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "eureka.client.enabled=false", "induztek.recuperacion.remitente=cuentas@induztek.ejemplo"])
@org.springframework.context.annotation.Import(BuzonConfiguracion::class)
class UsuariosServiceTest {
    @Autowired lateinit var service: UsuariosService
    @Autowired lateinit var buzon: BuzonPrueba
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun restaurar() {
        buzon.mensajes.clear()
        jdbc.update("DELETE FROM recuperacion_clave")
        jdbc.update("DELETE FROM sesion")
        jdbc.update("DELETE FROM usuario")
        val hash = Claves.crear("Induztek2026!")
        mapper.readTree(javaClass.getResourceAsStream("/usuarios-demo.json")).forEach {
            val u = mapper.treeToValue(it, Identidad::class.java)
            jdbc.update(
                "INSERT INTO usuario (id,correo,rut,datos,clave_hash,version) VALUES (?,?,?,?,?,?)",
                u.id, u.correo, u.rut.replace(".", "").replace("-", "").uppercase(),
                mapper.writeValueAsString(u.copy(version = 1)), hash, 1,
            )
        }
    }

    private fun jefe(): Identidad {
        val sesion = service.ingresar(Credenciales("respinoza@induztek.ejemplo", "Induztek2026!"))
        return service.autenticar("Bearer ${sesion.token}")
    }

    private fun json(u: Identidad, clave: String? = null): ObjectNode =
        mapper.valueToTree<ObjectNode>(u).also { if (clave != null) it.put("claveHash", clave) }

    @Test
    fun loginGeneraTokenYNoExponeHash() {
        val sesion = service.ingresar(Credenciales("mgodoy@induztek.ejemplo", "Induztek2026!"))
        assertEquals("mgodoy", service.autenticar("Bearer ${sesion.token}").id)
        assertFalse(mapper.writeValueAsString(sesion).contains("claveHash"))
        assertEquals(1, sesion.usuario.version)
    }

    @Test
    fun rechazaClaveIncorrectaYTokenInventado() {
        assertThrows(ResponseStatusException::class.java) {
            service.ingresar(Credenciales("mgodoy@induztek.ejemplo", "incorrecta"))
        }
        assertThrows(ResponseStatusException::class.java) { service.autenticar("Bearer falso") }
    }

    @Test
    fun clienteNoObtieneOtrasEmpresasNiPuedeOtorgarCuentas() {
        val sesion = service.ingresar(Credenciales("mfuentes@envpacifico.ejemplo", "Induztek2026!"))
        val actor = service.autenticar("Bearer ${sesion.token}")
        assertFalse(service.listar(actor).any { it.rolId == "CLIENTE" && it.id != "pacifico" })
        assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(actor, emptyList())
        }
    }

    @Test
    fun tecnicoNoActivaCuentaCliente() {
        val sesion = service.ingresar(Credenciales("mgodoy@induztek.ejemplo", "Induztek2026!"))
        val actor = service.autenticar("Bearer ${sesion.token}")
        val nuevo =
            mapper
                .createObjectNode()
                .put("id", "demo-nueva")
                .put("rolId", "CLIENTE")
                .put("nombre", "Empresa ficticia")
                .put("rut", "14.226.791-8")
                .put("correo", "nueva@demo.ejemplo")
                .put("activo", true)
        assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(actor, listOf(nuevo))
        }
    }

    @Test
    fun tecnicoReintentaAltaInactivaSinCambiarClaveEstadoNiVersion() {
        val sesion = service.ingresar(Credenciales("mgodoy@induztek.ejemplo", "Induztek2026!"))
        val tecnico = service.autenticar("Bearer ${sesion.token}")
        val cliente = Identidad(
            "cliente-nuevo", "CLIENTE", "Empresa ficticia", "14.226.791-8",
            "cliente-nuevo@demo.ejemplo", activo = false,
        )
        val hashInicial = Claves.crear("ClienteInicial2026!")
        val enviada = json(cliente, hashInicial)
        val creada = service.sincronizar(tecnico, listOf(enviada)).single()


        assertEquals(creada, service.sincronizar(tecnico, listOf(enviada)).single())
        assertEquals(creada, service.sincronizar(tecnico, listOf(json(cliente))).single())
        assertEquals(1L, creada.version)
        assertFalse(creada.activo)

        val hashDistinto = assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(tecnico, listOf(json(cliente, Claves.crear("OtraClave2026!"))))
        }
        assertEquals(HttpStatus.FORBIDDEN, hashDistinto.statusCode)
        val activacion = assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(tecnico, listOf(json(creada.copy(activo = true))))
        }
        assertEquals(HttpStatus.FORBIDDEN, activacion.statusCode)
        val edicion = assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(tecnico, listOf(json(creada.copy(nombre = "Cambio no autorizado"))))
        }
        assertEquals(HttpStatus.FORBIDDEN, edicion.statusCode)
        assertEquals(creada, service.listar(tecnico).first { it.id == creada.id })
        assertEquals(hashInicial, jdbc.queryForObject("SELECT clave_hash FROM usuario WHERE id=?", String::class.java, creada.id))
    }

    @Test
    fun rutExistenteConOtroFormatoYEspaciosNoCreaOtraCuenta() {
        val actor = jefe()
        val anterior = service.listar(actor).first { it.id == "mgodoy" }
        val cantidad = requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM usuario", Int::class.java))
        listOf("  17.284.519-3  ", "\t17284519-3\t", " 172845193 ").forEachIndexed { indice, rut ->
            val nuevo = Identidad(
                "rut-duplicado-$indice", "TECNICO", "Personal ficticio", rut,
                "duplicado-$indice@demo.ejemplo",
            )
            val errorPersonal = assertThrows(ResponseStatusException::class.java) {
                service.personal(actor, listOf(json(nuevo, Claves.crear("Personal2026!"))))
            }
            assertEquals(HttpStatus.CONFLICT, errorPersonal.statusCode)
            val errorCliente = assertThrows(ResponseStatusException::class.java) {
                service.sincronizar(actor, listOf(json(nuevo.copy(rolId = "CLIENTE", activo = false))))
            }
            assertEquals(HttpStatus.CONFLICT, errorCliente.statusCode)
        }
        assertEquals(cantidad, requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM usuario", Int::class.java)))
        assertEquals(anterior, service.listar(actor).first { it.id == anterior.id })
    }

    @Test
    fun actualizarRutConEspaciosNoPermiteDuplicarOtraPersona() {
        val actor = jefe()
        val anterior = service.listar(actor).first { it.id == "jtapia" }
        val error = assertThrows(ResponseStatusException::class.java) {
            service.personal(actor, listOf(json(anterior.copy(rut = "  17.284.519-3  "))))
        }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertEquals(anterior, service.listar(actor).first { it.id == anterior.id })
    }

    @Test
    fun columnaLegadaConEspaciosNoPermiteDuplicadoNiSeReescribe() {
        val actor = jefe()
        val antes = service.listar(actor)
        val cantidad = requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM usuario", Int::class.java))
        val rutLegado = "  172845193  "
        jdbc.update("UPDATE usuario SET rut=? WHERE id='mgodoy'", rutLegado)
        val nuevo = Identidad(
            "rut-duplicado-legado", "TECNICO", "Personal ficticio", "17.284.519-3",
            "legado-duplicado@demo.ejemplo",
        )
        val alta = assertThrows(ResponseStatusException::class.java) {
            service.personal(actor, listOf(json(nuevo, Claves.crear("Personal2026!"))))
        }
        assertEquals(HttpStatus.CONFLICT, alta.statusCode)
        val otra = antes.first { it.id == "jtapia" }
        val edicion = assertThrows(ResponseStatusException::class.java) {
            service.personal(actor, listOf(json(otra.copy(rut = "172845193"))))
        }
        assertEquals(HttpStatus.CONFLICT, edicion.statusCode)
        assertEquals(antes.associateBy { it.id }, service.listar(actor).associateBy { it.id })
        assertEquals(cantidad, requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM usuario", Int::class.java)))
        assertEquals(rutLegado, jdbc.queryForObject("SELECT rut FROM usuario WHERE id='mgodoy'", String::class.java))
    }

    @Test
    fun rutNuevoSeGuardaSinBordesYConColumnaCanonica() {
        val actor = jefe()
        val personal = Identidad(
            "rut-nuevo-personal", "TECNICO", "Personal ficticio", "  14.226.791-8  ",
            "rut-personal@demo.ejemplo",
        )
        val guardada = service.personal(actor, listOf(json(personal, Claves.crear("Personal2026!")))).single()
        assertEquals("14.226.791-8", guardada.rut)
        assertEquals("142267918", jdbc.queryForObject("SELECT rut FROM usuario WHERE id=?", String::class.java, guardada.id))

        val empresa = personal.copy(
            id = "rut-nuevo-cliente", rolId = "CLIENTE", activo = false,
            rut = "\t12.345.678-5 \n", correo = "rut-cliente@demo.ejemplo",
        )
        val cliente = service.sincronizar(actor, listOf(json(empresa))).single()
        assertEquals("12.345.678-5", cliente.rut)
        assertEquals("123456785", jdbc.queryForObject("SELECT rut FROM usuario WHERE id=?", String::class.java, cliente.id))
    }

    @Test
    fun jefeDesactivaCuentaYElTokenAnteriorDejaDeServir() {
        val previa = service.ingresar(Credenciales("jtapia@induztek.ejemplo", "Induztek2026!"))
        val jefe = service.ingresar(Credenciales("respinoza@induztek.ejemplo", "Induztek2026!"))
        val actor = service.autenticar("Bearer ${jefe.token}")
        val u = service.listar(actor).first { it.id == "jtapia" }.copy(activo = false)
        service.personal(actor, listOf(mapper.valueToTree(u)))
        assertThrows(ResponseStatusException::class.java) {
            service.autenticar("Bearer ${previa.token}")
        }
        assertThrows(ResponseStatusException::class.java) {
            service.ingresar(Credenciales("jtapia@induztek.ejemplo", "Induztek2026!"))
        }
        val vigente = service.listar(actor).first { it.id == u.id }
        service.personal(actor, listOf(mapper.valueToTree(vigente.copy(activo = true))))
    }

    @Test
    fun cuentaPersonalObsoletaNoReactivaNiRevierteLaClave() {
        val actor = jefe()
        val vieja = service.listar(actor).first { it.id == "crondon" }
        val nuevaClave = "ContraseñaNueva2026!"
        val guardada = service.personal(actor, listOf(json(vieja.copy(activo = false), Claves.crear(nuevaClave)))).single()
        assertEquals(vieja.version + 1, guardada.version)

        val error = assertThrows(ResponseStatusException::class.java) {
            service.personal(actor, listOf(json(vieja, Claves.crear("Induztek2026!"))))
        }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertFalse(service.listar(actor).first { it.id == vieja.id }.activo)


        service.personal(actor, listOf(json(guardada.copy(activo = true))))
        assertEquals(vieja.id, service.ingresar(Credenciales(vieja.correo, nuevaClave)).usuario.id)
        assertThrows(ResponseStatusException::class.java) {
            service.ingresar(Credenciales(vieja.correo, "Induztek2026!"))
        }
    }

    @Test
    fun cuentaClienteObsoletaNoRevierteEstadoNiClave() {
        val actor = jefe()
        val vieja = service.listar(actor).first { it.id == "pacifico" }
        val hashNuevo = Claves.crear("ClienteNueva2026!")
        val guardada = service.sincronizar(actor, listOf(json(vieja.copy(activo = false), hashNuevo))).single()
        val error = assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(actor, listOf(json(vieja, Claves.crear("Induztek2026!"))))
        }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertFalse(service.listar(actor).first { it.id == vieja.id }.activo)
        service.sincronizar(actor, listOf(json(guardada.copy(activo = true))))
        assertEquals(vieja.id, service.ingresar(Credenciales(vieja.correo, "ClienteNueva2026!")).usuario.id)
    }

    @Test
    fun perfilImportadoSinClaveNoReemplazaLaContrasena() {
        val actor = jefe()
        val u = Identidad("nuevo-personal", "TECNICO", "Personal ficticio", "14.226.791-8", "nuevo@demo.ejemplo")
        val creada = service.personal(actor, listOf(json(u, Claves.crear("CuentaNueva2026!")))).single()
        val editada = service.personal(actor, listOf(json(creada.copy(nombre = "Nombre actualizado")))).single()
        assertEquals(2, editada.version)
        assertEquals(u.id, service.ingresar(Credenciales(u.correo, "CuentaNueva2026!")).usuario.id)
        assertFalse(mapper.writeValueAsString(service.listar(actor)).contains("claveHash"))
    }

    @Test
    fun reintentarOperacionExactaNoIncrementaVersionNiRevierteOtraPosterior() {
        val actor = jefe()
        val vieja = service.listar(actor).first { it.id == "dvalenzuela" }
        val cambio = json(vieja.copy(nombre = "Nombre actualizado"), Claves.crear("CambioClave2026!"))
        val primera = service.personal(actor, listOf(cambio)).single()
        val repetida = service.personal(actor, listOf(cambio)).single()
        assertEquals(primera, repetida)
        val posterior = service.personal(actor, listOf(json(primera.copy(activo = false)))).single()
        val error = assertThrows(ResponseStatusException::class.java) {
            service.personal(actor, listOf(cambio))
        }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertEquals(posterior, service.listar(actor).first { it.id == vieja.id })
    }

    @Test
    fun loteConConflictoNoDejaActualizacionesParciales() {
        val actor = jefe()
        val a = service.listar(actor).first { it.id == "crondon" }
        val b = service.listar(actor).first { it.id == "dvalenzuela" }
        service.personal(actor, listOf(json(b.copy(nombre = "Cambio remoto"))))
        assertThrows(ResponseStatusException::class.java) {
            service.personal(actor, listOf(json(a.copy(nombre = "No debe persistir")), json(b.copy(activo = false))))
        }
        assertEquals(a, service.listar(actor).first { it.id == a.id })
    }

    @Test
    fun migraTablaAnteriorSinPerderCuentaClaveNiSesion() {
        val antigua = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:migracion-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", ""))
        try {
            antigua.execute("CREATE TABLE usuario (id VARCHAR(100) PRIMARY KEY, correo VARCHAR(200) UNIQUE NOT NULL, rut VARCHAR(20) UNIQUE NOT NULL, datos VARCHAR(20000) NOT NULL, clave_hash VARCHAR(400) NOT NULL)")
            val u = Identidad("existente", "TECNICO", "Existente", "14.226.791-8", "existente@demo.ejemplo")

            val perfilViejo = json(u).also { it.remove("version") }
            antigua.update("INSERT INTO usuario VALUES (?,?,?,?,?)", u.id, u.correo, u.rut, mapper.writeValueAsString(perfilViejo), Claves.crear("Existente2026!"))
            antigua.execute("CREATE TABLE sesion (token_hash VARCHAR(64) PRIMARY KEY, usuario_id VARCHAR(100) NOT NULL REFERENCES usuario(id), vence BIGINT NOT NULL)")
            val tokenAnterior = "token-ficticio-anterior"
            val hashToken = java.security.MessageDigest.getInstance("SHA-256")
                .digest(tokenAnterior.toByteArray()).joinToString("") { "%02x".format(it) }
            antigua.update("INSERT INTO sesion VALUES (?,?,?)", hashToken, u.id, java.time.Instant.now().plusSeconds(600).epochSecond)
            val migrado = UsuariosService(antigua, mapper)
            assertEquals(u.id, migrado.autenticar("Bearer $tokenAnterior").id)
            val sesion = migrado.ingresar(Credenciales(u.correo, "Existente2026!"))
            assertEquals(u.id, sesion.usuario.id)
            assertEquals(1, sesion.usuario.version)
            assertEquals(u.id, migrado.autenticar("Bearer ${sesion.token}").id)
            assertEquals(1, requireNotNull(antigua.queryForObject("SELECT COUNT(*) FROM usuario", Int::class.java)))
        } finally {
            antigua.execute("SHUTDOWN")
        }
    }

    @Test
    fun servidorRechazaCorreoYTelefonoInvalidosAntesDeGuardar() {
        val original = service.listar(jefe()).first { it.perfil().name == "CLIENTE" }
        val correoInvalido = json(original.copy(correo = "foo@-empresa.cl"))
        val telefonoInvalido = json(original.copy(telefono = "91234567890"))
        for ((perfil, campo) in listOf(correoInvalido to "Correo", telefonoInvalido to "9 dígitos")) {
            val error = assertThrows(ResponseStatusException::class.java) {
                service.sincronizar(jefe(), listOf(perfil))
            }
            assertEquals(400, error.statusCode.value())
            assertTrue(error.reason.orEmpty().contains(campo))
        }
        val datos = json(original.copy(telefono = "+56 (9) 1234-5678"))
        assertEquals("+56912345678", service.sincronizar(jefe(), listOf(datos)).single().telefono)
    }

    @Test
    fun clienteCambiaSuClaveYRevocaSusSesionesSinAfectarOtrasCuentas() {
        val correo = "mfuentes@envpacifico.ejemplo"
        val actual = "Induztek2026!"
        val nueva = "ClientePrueba2026!"
        val sesion = service.ingresar(Credenciales(correo, actual))
        val anterior = service.ingresar(Credenciales(correo, actual))
        val tecnico = service.ingresar(Credenciales("mgodoy@induztek.ejemplo", actual))
        val token = "Bearer ${sesion.token}"
        assertEquals(401, assertThrows(ResponseStatusException::class.java) {
            service.cambiarClave(null, actual, nueva)
        }.statusCode.value())
        assertEquals(400, assertThrows(ResponseStatusException::class.java) {
            service.cambiarClave(token, actual, "corta")
        }.statusCode.value())
        assertEquals(403, assertThrows(ResponseStatusException::class.java) {
            service.cambiarClave(token, "incorrecta", nueva)
        }.statusCode.value())
        val resultado = IdentidadController(service).cambiarClave(token, CambioClave(actual, nueva))
        assertEquals(sesion.usuario.id, resultado.usuario.id)
        assertEquals(sesion.usuario.version + 1, resultado.usuario.version)
        assertEquals(resultado.usuario, service.autenticar("Bearer ${resultado.token}"))
        assertThrows(ResponseStatusException::class.java) { service.autenticar(token) }
        assertThrows(ResponseStatusException::class.java) { service.autenticar("Bearer ${anterior.token}") }
        assertThrows(ResponseStatusException::class.java) { service.ingresar(Credenciales(correo, actual)) }
        assertEquals(resultado.usuario, service.ingresar(Credenciales(correo, nueva)).usuario)
        assertEquals(tecnico.usuario, service.autenticar("Bearer ${tecnico.token}"))
    }

    @Test
    fun clienteRecuperaSinClaveActualConCodigoQueCaducaYEsDeUnSoloUso() {
        val correo = "mfuentes@envpacifico.ejemplo"
        val actual = "Induztek2026!"
        val nueva = "ClienteRestablecido2026!"
        val previa = service.ingresar(Credenciales(correo, actual))
        val controlador = IdentidadController(service)
        controlador.solicitar(SolicitudRecuperacion("inexistente@induztek.ejemplo"))
        assertTrue(buzon.mensajes.isEmpty())
        controlador.solicitar(SolicitudRecuperacion(correo))
        controlador.solicitar(SolicitudRecuperacion(correo))
        assertEquals(1, buzon.mensajes.size)
        val correoEnviado = buzon.mensajes.single()
        assertEquals(correo, correoEnviado.to!!.single())
        assertEquals("cuentas@induztek.ejemplo", correoEnviado.from)
        fun codigo() = buzon.mensajes.last().text!!.lineSequence().first { it.length == 32 }
        val primero = codigo()
        val hash = jdbc.queryForObject("SELECT codigo_hash FROM recuperacion_clave WHERE usuario_id=?", String::class.java, previa.usuario.id)
        assertNotEquals(primero, hash)
        jdbc.update("UPDATE recuperacion_clave SET vence=0, solicitado=0 WHERE usuario_id=?", previa.usuario.id)
        assertEquals(400, assertThrows(ResponseStatusException::class.java) {
            controlador.recuperar(ConfirmacionRecuperacion(correo, primero, nueva))
        }.statusCode.value())
        controlador.solicitar(SolicitudRecuperacion(correo))
        assertEquals(2, buzon.mensajes.size)
        val valido = codigo()
        assertNotEquals(primero, valido)
        assertThrows(ResponseStatusException::class.java) {
            controlador.recuperar(ConfirmacionRecuperacion(correo, "x".repeat(32), nueva))
        }
        controlador.recuperar(ConfirmacionRecuperacion(correo, valido, nueva))
        assertThrows(ResponseStatusException::class.java) {
            controlador.recuperar(ConfirmacionRecuperacion(correo, valido, "SegundoIntento2026!"))
        }
        assertThrows(ResponseStatusException::class.java) { service.ingresar(Credenciales(correo, actual)) }
        assertThrows(ResponseStatusException::class.java) { service.autenticar("Bearer ${previa.token}") }
        assertEquals(previa.usuario.id, service.ingresar(Credenciales(correo, nueva)).usuario.id)
    }
}

@org.springframework.boot.test.context.TestConfiguration
class BuzonConfiguracion {
    @org.springframework.context.annotation.Bean
    fun buzon() = BuzonPrueba()
}

class BuzonPrueba : org.springframework.mail.javamail.JavaMailSenderImpl() {
    val mensajes = mutableListOf<org.springframework.mail.SimpleMailMessage>()

    override fun send(simpleMessage: org.springframework.mail.SimpleMailMessage) {
        mensajes += org.springframework.mail.SimpleMailMessage(simpleMessage)
    }
}