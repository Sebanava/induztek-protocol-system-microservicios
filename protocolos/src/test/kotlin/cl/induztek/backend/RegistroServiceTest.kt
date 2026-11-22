package cl.induztek.backend

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.server.ResponseStatusException

@SpringBootTest(
    properties = ["spring.datasource.url=jdbc:h2:mem:protocolos-test;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "eureka.client.enabled=false"]
)
class RegistroServiceTest {
    @Autowired lateinit var service: RegistroService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper
    private val tecnico =
        Identidad("mgodoy", "TECNICO", "Técnico", "17.284.519-3", "mgodoy@induztek.ejemplo")
    private val ingeniera =
        Identidad(
            "acontreras",
            "INGENIERO",
            "Ingeniera",
            "15.906.334-8",
            "acontreras@induztek.ejemplo",
        )
    private val cliente =
        Identidad("pacifico", "CLIENTE", "Cliente", "76.482.915-8", "mfuentes@envpacifico.ejemplo")

    @BeforeEach
    fun restaurar() {
        jdbc.update("DELETE FROM operacion")
        jdbc.update("DELETE FROM registro")
        mapper.readTree(javaClass.getResourceAsStream("/registros-demo.json")).forEach { r ->
            val tipo = r["tipo"].asText()
            val d = r["datos"]["registro"]
            jdbc.update(
                "INSERT INTO registro VALUES (?,?,?,?,?,?,?,?,?)",
                tipo,
                r["id"].asText(),
                1,
                mapper.writeValueAsString(r["datos"]),
                if (tipo == "equipo") d["instalacionId"].asText() else null,
                if (tipo == "equipo") d["tag"].asText().uppercase() else null,
                if (tipo == "protocolo") d["visitaId"].asText() else null,
                if (tipo == "protocolo") d["equipoId"].asText() else null,
                if (tipo == "protocolo") d["numero"].asText() else null,
            )
        }
    }

    private fun obtener(tipo: String, id: String) =
        service.listar(tecnico).first { it.tipo == tipo && it.id == id }

    private fun cambio(
        r: RegistroRemoto,
        datos: JsonNode = r.datos,
        version: Long = r.version,
        operacion: String = UUID.randomUUID().toString(),
    ) = Cambio(r.tipo, r.id, version, operacion, datos)

    private fun editar(
        r: RegistroRemoto,
        estado: String,
        actor: Identidad,
        motivo: String = "Revisado",
    ): JsonNode {
        val d = r.datos.deepCopy<ObjectNode>()
        (d["registro"] as ObjectNode).put("estado", estado).put("motivo", motivo)
        d.withArray("historial")
            .add(
                mapper
                    .createObjectNode()
                    .put("id", UUID.randomUUID().toString())
                    .put("protocoloId", r.id)
                    .put("usuarioId", actor.id)
                    .put("anterior", r.datos["registro"]["estado"].asText())
                    .put("nuevo", estado)
                    .put("fechaHora", "2026-10-24T10:00:00")
                    .put("motivo", motivo)
            )
        return d
    }


    private fun borrador(id: String = "p-2026"): RegistroRemoto {
        val original = obtener("protocolo", id)
        val datos = original.datos.deepCopy<ObjectNode>()
        (datos["registro"] as ObjectNode).put("estado", "BORRADOR")
        jdbc.update("UPDATE registro SET datos=? WHERE tipo='protocolo' AND id=?", mapper.writeValueAsString(datos), id)
        return original.copy(datos = datos)
    }

    private fun realizar(
        datos: ObjectNode,
        tipo: String,
        elemento: String,
        instrumento: String,
        resultado: String?,
        valores: Map<String, Double>,
    ) {
        val prueba = datos["pruebas"].first {
            it["tipoId"].asText() == tipo && it["elemento"].asText() == elemento
        } as ObjectNode
        prueba.put("realizada", true).put("motivo", "").put("instrumentoId", instrumento)
        if (resultado == null) prueba.putNull("resultado") else prueba.put("resultado", resultado)
        val mediciones = datos.withArray("mediciones")
        valores.forEach { (codigo, valor) ->
            val parametro = "$tipo:$codigo"
            val anterior = mediciones.firstOrNull {
                it["pruebaId"].asText() == prueba["id"].asText() && it["parametroId"].asText() == parametro
            } as ObjectNode?
            if (anterior != null) anterior.put("valor", valor)
            else mediciones.add(
                mapper.createObjectNode()
                    .put("id", UUID.randomUUID().toString())
                    .put("pruebaId", prueba["id"].asText())
                    .put("parametroId", parametro)
                    .put("valor", valor)
            )
        }
    }

    @Test
    fun tensionCeroSeRechazaSinGuardarNiCambiarLaVersion() {
        val r = borrador()
        val datos = editar(r, "BORRADOR", tecnico) as ObjectNode
        val tension = datos["mediciones"].first { it["parametroId"].asText() == "AISLACION:TENSION_PRUEBA" } as ObjectNode
        tension.put("valor", 0.0)
        val error = assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(tecnico, listOf(cambio(r, datos)))
        }
        assertEquals(409, error.statusCode.value())
        val conservado = obtener("protocolo", r.id)
        assertEquals(r.version, conservado.version)
        assertEquals(r.datos, conservado.datos)
    }

    @Test
    fun tapFraccionarioSeRechazaAunqueLaRelacionCalculadaSeaCorrecta() {
        val r = borrador()
        val datos = editar(r, "BORRADOR", tecnico) as ObjectNode
        realizar(
            datos, "TTR", "H1-X1", "ttr", "APROBADO",
            mapOf("POSICION_TAP" to 1.5, "RELACION_TEORICA" to 30.0, "RELACION_MEDIDA" to 30.0),
        )
        val error = assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(tecnico, listOf(cambio(r, datos)))
        }
        assertEquals(409, error.statusCode.value())
        assertEquals(r.datos, obtener("protocolo", r.id).datos)
    }

    @Test
    fun cerosPermitidosYBordeTtrValidoSeConservanSinInventarMaximos() {
        data class Caso(
            val protocolo: String, val tipo: String, val elemento: String,
            val instrumento: String, val resultado: String?, val valores: Map<String, Double>,
        )
        val casos = listOf(
            Caso("p-2026", "TTR", "H1-X1", "ttr", "APROBADO",
                mapOf("POSICION_TAP" to 0.0, "RELACION_TEORICA" to 30.0, "RELACION_MEDIDA" to 30.15)),
            Caso("p-2026", "DEVANADOS", "AT-U", "dev-demo", null, mapOf("RESISTENCIA" to 0.0)),
            Caso("p-cel", "CONTACTOS", "Polo 1", "contacto-demo", "APROBADO", mapOf("RESISTENCIA_CONTACTO" to 0.0)),
            Caso("p-rel", "OPERACION_RELE", "General", "inyeccion", "RECHAZADO", mapOf("TIEMPO_MEDIDO" to 0.0)),
        )
        casos.forEach { caso ->
            val r = borrador(caso.protocolo)
            val creador = tecnico.copy(id = r.datos["registro"]["creadorId"].asText())
            val datos = editar(r, "BORRADOR", creador) as ObjectNode
            realizar(datos, caso.tipo, caso.elemento, caso.instrumento, caso.resultado, caso.valores)
            val guardado = service.sincronizar(creador, listOf(cambio(r, datos))).single()
            assertEquals(r.version + 1, guardado.version)
            val prueba = guardado.datos["pruebas"].first {
                it["tipoId"].asText() == caso.tipo && it["elemento"].asText() == caso.elemento
            }
            caso.valores.forEach { (codigo, valor) ->
                val medida = guardado.datos["mediciones"].single {
                    it["pruebaId"].asText() == prueba["id"].asText() &&
                        it["parametroId"].asText() == "${caso.tipo}:$codigo"
                }
                assertEquals(valor, medida["valor"].asDouble(), 0.0)
            }
        }
    }

    @Test
    fun clienteSoloConsultaSuEmpresaYNoEscribe() {
        val visibles = service.listar(cliente)
        assertTrue(visibles.any { it.id == "tr01" })
        assertFalse(visibles.any { it.id == "cel" || it.id == "p-cel" || it.id == "edificio" })
        assertEquals(
            403,
            assertThrows(ResponseStatusException::class.java) {
                    service.sincronizar(cliente, listOf(cambio(obtener("equipo", "tr01"))))
                }
                .statusCode
                .value(),
        )
    }

    @Test
    fun clienteNoVeBorradoresNiAnulados() {
        val r = obtener("protocolo", "p-2026")
        listOf("BORRADOR", "ANULADO").forEach { estado ->
            val d = r.datos.deepCopy<ObjectNode>()
            (d["registro"] as ObjectNode).put("estado", estado)
            jdbc.update(
                "UPDATE registro SET datos=? WHERE tipo='protocolo' AND id='p-2026'",
                mapper.writeValueAsString(d),
            )
            assertFalse(service.listar(cliente).any { it.id == r.id })
        }
    }

    @Test
    fun tecnicoNoFormaliza() {
        val r = obtener("protocolo", "p-2026")
        assertEquals(
            403,
            assertThrows(ResponseStatusException::class.java) {
                    service.sincronizar(
                        tecnico,
                        listOf(cambio(r, editar(r, "FORMALIZADO", tecnico))),
                    )
                }
                .statusCode
                .value(),
        )
    }

    @Test
    fun ingenieraFormalizaYOriginalNoSePuedeModificar() {
        val r = obtener("protocolo", "p-2026")
        val actualizado =
            service
                .sincronizar(ingeniera, listOf(cambio(r, editar(r, "FORMALIZADO", ingeniera))))
                .single()
        assertEquals(2, actualizado.version)
        assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(
                ingeniera,
                listOf(cambio(actualizado, editar(actualizado, "ANULADO", ingeniera))),
            )
        }
    }

    @Test
    fun variacionSinComentarioSeRechaza() {
        val r = obtener("protocolo", "p-2026")
        assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(
                ingeniera,
                listOf(cambio(r, editar(r, "FORMALIZADO", ingeniera, ""))),
            )
        }
    }

    @Test
    fun reintentoEsIdempotenteYNoRevierteUnaVersionPosterior() {
        val r = obtener("protocolo", "p-2026")
        val c = cambio(r, editar(r, "OBSERVADO", ingeniera))
        val primera = service.sincronizar(ingeniera, listOf(c)).single()
        val corregida =
            service
                .sincronizar(
                    tecnico,
                    listOf(
                        cambio(primera, editar(primera, "REGISTRADO_TERRENO", tecnico, "Corregido"))
                    ),
                )
                .single()
        assertEquals(3, corregida.version)
        assertEquals(2, service.sincronizar(ingeniera, listOf(c)).single().version)
        assertEquals(3, obtener("protocolo", r.id).version)
        assertEquals(
            "REGISTRADO_TERRENO",
            obtener("protocolo", r.id).datos["registro"]["estado"].asText(),
        )
    }

    @Test
    fun versionAntiguaNoPisaDatos() {
        val r = obtener("equipo", "tr01")
        val d = r.datos.deepCopy<ObjectNode>()
        (d["registro"] as ObjectNode).put("observaciones", "Primera revisión")
        service.sincronizar(tecnico, listOf(cambio(r, d)))
        (d["registro"] as ObjectNode).put("observaciones", "Segunda copia obsoleta")
        assertEquals(
            409,
            assertThrows(ResponseStatusException::class.java) {
                    service.sincronizar(tecnico, listOf(cambio(r, d)))
                }
                .statusCode
                .value(),
        )
        assertEquals(
            "Primera revisión",
            obtener("equipo", r.id).datos["registro"]["observaciones"].asText(),
        )
    }

    @Test
    fun tagDuplicadoNoBorraNingunaCopia() {
        val r = obtener("equipo", "tr01")
        val d = r.datos.deepCopy<ObjectNode>()
        (d["registro"] as ObjectNode).put("id", "equipo-duplicado")
        assertThrows(Exception::class.java) {
            service.sincronizar(
                tecnico,
                listOf(Cambio("equipo", "equipo-duplicado", 0, UUID.randomUUID().toString(), d)),
            )
        }
        assertEquals(
            1,
            service.listar(tecnico).count {
                it.tipo == "equipo" &&
                    it.datos["registro"]["instalacionId"].asText() == "lampa" &&
                    it.datos["registro"]["tag"].asText() == "TR-01"
            },
        )
    }

    @Test
    fun loteConErrorSeRevierteCompleto() {
        val e = obtener("equipo", "tr01")
        val d = e.datos.deepCopy<ObjectNode>()
        (d["registro"] as ObjectNode).put("observaciones", "No debe persistirse")
        val p = obtener("protocolo", "p-2026")
        assertThrows(Exception::class.java) {
            service.sincronizar(
                tecnico,
                listOf(cambio(e, d), cambio(p, editar(p, "FORMALIZADO", tecnico))),
            )
        }
        assertEquals(1, obtener("equipo", e.id).version)
    }

    @Test
    fun instrumentoSoloJefatura() {
        val r = obtener("instrumento", "meg5")
        val d = r.datos.deepCopy<ObjectNode>()
        (d["registro"] as ObjectNode).put("nombre", "Otro nombre")
        assertEquals(
            403,
            assertThrows(ResponseStatusException::class.java) {
                    service.sincronizar(tecnico, listOf(cambio(r, d)))
                }
                .statusCode
                .value(),
        )
    }

    @Test
    fun servicioDeIdentidadCaidoEsReintentable() {
        val cliente = IdentidadRemota("http://127.0.0.1:1")
        assertEquals(
            503,
            assertThrows(ResponseStatusException::class.java) {
                    cliente.autenticar("Bearer prueba-sintetica")
                }
                .statusCode
                .value(),
        )
    }


    private fun validarCambio(r: RegistroRemoto, actor: Identidad, motivo: String, ajustar: ObjectNode.() -> Unit) {
        val datos = r.datos.deepCopy<ObjectNode>().apply(ajustar)
        val error = assertThrows(ResponseStatusException::class.java) {
            service.sincronizar(actor, listOf(cambio(r, datos)))
        }
        assertEquals(motivo, error.reason)
    }

    @Test
    fun administracionDeInstrumentosConservaCalibraciones() {
        val jefe = Identidad("respinoza", "JEFE_TECNICO", "Jefe técnico", "", "")
        val instrumento = obtener("instrumento", "meg5")
        validarCambio(instrumento, jefe, "Prueba de instrumento inválida") {
            (get("registro") as ObjectNode).put("tipoPruebaId", "invalido")
        }
        validarCambio(instrumento, jefe, "No se puede borrar el historial de calibraciones") {
            withArray("calibraciones").removeAll()
        }
        validarCambio(instrumento, jefe, "Calibración inválida") {
            withArray("calibraciones").addObject()
                .put("id", "cal-invalida").put("desde", "2030-12-31").put("hasta", "2029-01-01")
        }
        val cambioValido = instrumento.datos.deepCopy<ObjectNode>()
        (cambioValido["registro"] as ObjectNode).put("nombre", "Megóhmetro de patio")
        val guardado = service.sincronizar(jefe, listOf(cambio(instrumento, cambioValido))).single()
        assertEquals(instrumento.version + 1, guardado.version)
        assertEquals(instrumento.datos["calibraciones"], guardado.datos["calibraciones"])
    }

    @Test
    fun instalacionesYEquiposRechazanCambiosDeIdentidad() {
        val instalacion = obtener("instalacion", "lampa")
        validarCambio(instalacion, tecnico, "No se puede cambiar la empresa de una instalación") {
            (get("registro") as ObjectNode).put("clienteId", "clinica")
        }
        val celda = obtener("equipo", "cel")
        validarCambio(celda, tecnico, "Máximo de placa obligatorio") {
            (get("registro") as ObjectNode).put("maxContactos", 0)
        }
        validarCambio(celda, tecnico, "La identidad del equipo no puede cambiar al sincronizar") {
            (get("registro") as ObjectNode).put("tag", "CEL-IDENTIDAD-NUEVA")
        }
        val repetido = cambio(instalacion)
        assertEquals("Registros repetidos dentro del lote",
            assertThrows(ResponseStatusException::class.java) {
                service.sincronizar(tecnico, listOf(repetido, repetido.copy(operacionId = "otra")))
            }.reason)
        assertEquals(instalacion, service.sincronizar(tecnico, listOf(repetido)).single())
    }

    @Test
    fun visitasExigenFechaParticipanteYProtocolosAntesDelCierre() {
        val visita = obtener("visita", "vis-2026")
        val datos = visita.datos.deepCopy<ObjectNode>()
        (datos["registro"] as ObjectNode).put("id", "vis-nueva").putNull("cierre")
        datos.putArray("participantes").addObject().put("id", "part-nuevo").put("usuarioId", tecnico.id)
        datos.putArray("recepciones")
        val nueva = RegistroRemoto("visita", "vis-nueva", 0, datos)
        validarCambio(nueva, tecnico, "OT duplicada") {}
        (datos["registro"] as ObjectNode).put("ot", "OT-ES3-001")
        validarCambio(nueva, tecnico, "Fecha de visita inválida") {
            (get("registro") as ObjectNode).put("fecha", "2026-02-31")
        }
        validarCambio(nueva, tecnico, "Solo un participante modifica la visita") {
            withArray("participantes").removeAll()
        }
        validarCambio(nueva, tecnico, "Hay protocolos pendientes para cerrar la visita") {
            (get("registro") as ObjectNode).put("cierre", "2026-09-26T12:30:00")
        }
        assertEquals(1L, service.sincronizar(tecnico, listOf(cambio(nueva))).single().version)
        validarCambio(visita, tecnico, "El acta emitida no se puede modificar") {
            (get("registro") as ObjectNode).put("ot", "OT-DISTINTA")
        }
    }

    @Test
    fun cierreRequiereRecepcionYRespetaDocumentoDeTerreno() {
        val cerrada = obtener("visita", "vis-2026")
        val datos = cerrada.datos.deepCopy<ObjectNode>()
        (datos["registro"] as ObjectNode).putNull("cierre")
        jdbc.update("UPDATE registro SET datos=? WHERE tipo='visita' AND id=?", mapper.writeValueAsString(datos), cerrada.id)
        val abierta = obtener("visita", cerrada.id)
        validarCambio(abierta, tecnico, "Recepción única obligatoria") {
            (get("registro") as ObjectNode).put("cierre", "2026-09-26T13:42:00")
            withArray("recepciones").removeAll()
        }
        validarCambio(abierta, tecnico, "Recepción o acta incompleta") {
            (get("registro") as ObjectNode).put("cierre", "2026-09-26T13:42:00")
            (get("recepciones").first() as ObjectNode).put("rut", "inválido")
        }
        val guardada = service.sincronizar(tecnico, listOf(cambio(abierta, cerrada.datos))).single()
        assertEquals(cerrada.datos["recepciones"], guardada.datos["recepciones"])
    }

    @Test
    fun protocoloRechazaPruebasYMedicionesIncompatibles() {
        val r = borrador()
        fun editarPrueba(motivo: String, ajustar: ObjectNode.() -> Unit) {
            val error = assertThrows(ResponseStatusException::class.java) {
                val datos = (editar(r, "BORRADOR", tecnico) as ObjectNode).apply(ajustar)
                service.sincronizar(tecnico, listOf(cambio(r, datos)))
            }
            assertEquals(motivo, error.reason)
        }
        editarPrueba("Medición de una prueba ajena") {
            withArray("mediciones").addObject().put("id", "ajena").put("pruebaId", "prueba-no-existe")
        }
        editarPrueba("Resultado inválido") {
            (get("pruebas").first() as ObjectNode).put("resultado", "DESCONOCIDO")
        }
        editarPrueba("La prueba debe corresponder a la fecha de visita") {
            (get("registro") as ObjectNode).put("fechaHora", "2025-01-01T08:00:00")
        }
    }

    @Test
    fun apiValidaSesionYReferenciasAntesDePersistir() {
        val servidor = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        servidor.createContext("/api/") { intercambio ->
            intercambio.use {
                val token = it.requestHeaders.getFirst("Authorization")
                val estado = when (token) {
                    "Bearer valido" -> 200
                    "Bearer sin-permiso" -> 403
                    else -> 500
                }
                if (estado == 200) {
                    val respuesta = if (it.requestURI.path == "/api/usuarios") listOf(tecnico, ingeniera, cliente) else tecnico
                    val bytes = mapper.writeValueAsBytes(respuesta)
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(200, bytes.size.toLong())
                    it.responseBody.write(bytes)
                } else {
                    it.sendResponseHeaders(estado, -1)
                }
            }
        }
        servidor.start()
        try {
            val identidad = IdentidadRemota("http://127.0.0.1:${servidor.address.port}")
            val controlador = ProtocolosController(service, identidad)
            val token = "Bearer valido"
            assertEquals("ok", controlador.salud()["estado"])
            assertEquals(service.listar(tecnico), controlador.listar(token))
            for ((credencial, estado) in listOf(null to 401, "Bearer sin-permiso" to 401, "Bearer error" to 503)) {
                assertEquals(estado, assertThrows(ResponseStatusException::class.java) {
                    controlador.listar(credencial)
                }.statusCode.value())
            }
            for ((tipo, id, campo) in listOf(
                Triple("instalacion", "lampa", "clienteId"),
                Triple("visita", "vis-2026", "ingenieroId"),
                Triple("protocolo", "p-2026", "creadorId")
            )) {
                val r = obtener(tipo, id)
                val datos = r.datos.deepCopy<ObjectNode>()
                (datos["registro"] as ObjectNode).put(campo, "sin-referencia")
                val error = assertThrows(ResponseStatusException::class.java) {
                    controlador.sincronizar(token, listOf(cambio(r, datos)))
                }
                assertEquals(409, ErroresApi().regla(error).statusCode.value())
            }
            assertTrue(controlador.sincronizar(token, emptyList()).isEmpty())
            assertEquals(409, ErroresApi().duplicado().statusCode.value())
        } finally {
            servidor.stop(0)
        }
    }

}
