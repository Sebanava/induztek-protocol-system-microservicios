package cl.induztek.backend

import cl.induztek.protocol.domain.Claves
import cl.induztek.protocol.domain.Validacion
import cl.induztek.protocol.model.Perfil
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@SpringBootApplication class IdentidadApplication

fun main(args: Array<String>) {
    runApplication<IdentidadApplication>(*args)
}

@Service
class UsuariosService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val correoRecuperacion: CorreoRecuperacion? = null,
) {
    init {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS usuario (id VARCHAR(100) PRIMARY KEY, correo VARCHAR(200) UNIQUE NOT NULL, rut VARCHAR(20) UNIQUE NOT NULL, datos TEXT NOT NULL, clave_hash VARCHAR(400) NOT NULL)"
        )

        val tieneVersion = jdbc.query("SELECT * FROM usuario WHERE 1=0", org.springframework.jdbc.core.ResultSetExtractor { rs ->
            (1..rs.metaData.columnCount).any { rs.metaData.getColumnName(it).equals("version", true) }
        }) == true
        if (!tieneVersion) jdbc.execute("ALTER TABLE usuario ADD COLUMN version BIGINT NOT NULL DEFAULT 1")
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS sesion (token_hash VARCHAR(64) PRIMARY KEY, usuario_id VARCHAR(100) NOT NULL, vence BIGINT NOT NULL, FOREIGN KEY (usuario_id) REFERENCES usuario(id))"
        )
        val semilla = mapper.readTree(javaClass.getResourceAsStream("/usuarios-demo.json"))
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS recuperacion_clave (usuario_id VARCHAR(100) PRIMARY KEY, codigo_hash VARCHAR(64) NOT NULL, solicitado BIGINT NOT NULL, vence BIGINT NOT NULL, FOREIGN KEY (usuario_id) REFERENCES usuario(id))"
        )
        if (jdbc.queryForObject("SELECT COUNT(*) FROM usuario", Int::class.java) == 0) {
            val clave = Claves.crear("Induztek2026!")
            semilla.forEach { insertar(mapper.treeToValue(it, Identidad::class.java), clave) }
        }
    }

    private fun insertar(u: Identidad, hash: String): Identidad {
        val nueva = u.copy(version = 1)
        jdbc.update(
            "INSERT INTO usuario (id,correo,rut,datos,clave_hash,version) VALUES (?,?,?,?,?,?)",
            u.id,
            u.correo.lowercase(),
            Validacion.normalizarRut(u.rut),
            mapper.writeValueAsString(nueva),
            hash,
            nueva.version,
        )
        return nueva
    }

    private fun perfil(fila: Map<String, Any>): Identidad =
        mapper.readValue(fila.getValue("DATOS").toString(), Identidad::class.java)
            .copy(version = (fila.getValue("VERSION") as Number).toLong())

    private fun buscar(id: String) =
        jdbc.queryForList("SELECT datos,clave_hash,version FROM usuario WHERE id=?", id)
            .firstOrNull()

    private fun iguales(a: Identidad, b: Identidad) = a.copy(version = 0) == b.copy(version = 0)

    private fun conflicto(): Nothing =
        throw ResponseStatusException(
            HttpStatus.CONFLICT,
            "La cuenta cambió en otro dispositivo. Descarga la versión actual antes de editarla.",
        )

    private fun guardar(recibida: Identidad, clave: String?, anterior: Map<String, Any>?): Identidad {
        val actual = anterior?.let(::perfil)
        val correo = recibida.correo.trim()
        if (correo != actual?.correo && !Validacion.correoValido(correo))
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Correo inválido")
        val telefono = if (recibida.telefono == actual?.telefono) recibida.telefono else try {
            Validacion.telefonoChileno(recibida.telefono)
        } catch (e: cl.induztek.protocol.domain.ErrorFormulario) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        val u = recibida.copy(correo = correo, telefono = telefono)
        try {

            val rut = Validacion.normalizarRut(u.rut)
            if (jdbc.queryForList("SELECT id,rut FROM usuario WHERE id<>?", u.id).any {
                    Validacion.normalizarRut(it.getValue("RUT").toString()) == rut
                })
                throw ResponseStatusException(HttpStatus.CONFLICT, "Ya existe ese RUT")
            if (anterior == null) {
                if (u.version != 0L) conflicto()
                return insertar(u, requireNotNull(clave))
            }
            val actual = perfil(anterior)
            val hashActual = anterior.getValue("CLAVE_HASH").toString()

            if (iguales(u, actual) && (clave == null || clave == hashActual)) return actual
            if (u.version != actual.version) conflicto()
            val siguiente = u.copy(version = actual.version + 1)
            val modificadas = jdbc.update(
                "UPDATE usuario SET correo=?,rut=?,datos=?,clave_hash=?,version=? WHERE id=? AND version=?",
                u.correo.lowercase(),
                Validacion.normalizarRut(u.rut),
                mapper.writeValueAsString(siguiente),
                clave ?: hashActual,
                siguiente.version,
                u.id,
                actual.version,
            )
            if (modificadas != 1) {
                val vigente = buscar(u.id) ?: conflicto()
                val ultimo = perfil(vigente)
                if (iguales(u, ultimo) && (clave == null || clave == vigente["CLAVE_HASH"]))
                    return ultimo
                conflicto()
            }
            return siguiente
        } catch (_: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ya existe ese ID, correo o RUT")
        }
    }

    private fun hash(token: String) =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    @Transactional
    fun ingresar(c: Credenciales): SesionRemota {
        val filas =
            jdbc.queryForList(
                "SELECT datos,clave_hash,version FROM usuario WHERE correo = ? FOR UPDATE",
                c.correo.trim().lowercase(),
            )
        val fila =
            filas.firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Acceso inválido")
        val u = perfil(fila)
        if (!u.activo || !Claves.verificar(c.clave, fila["CLAVE_HASH"].toString()))
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Acceso inválido")
        return crearSesion(u)
    }

    @Transactional
    fun cambiarClave(autorizacion: String?, actual: String, nueva: String): SesionRemota {
        val actor = autenticar(autorizacion)
        try {
            Validacion.validarCambioClave(actual, nueva)
        } catch (e: cl.induztek.protocol.domain.ErrorFormulario) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        val fila = jdbc.queryForList(
            "SELECT datos,clave_hash,version FROM usuario WHERE id=? FOR UPDATE", actor.id
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val vigente = jdbc.queryForList(
            "SELECT token_hash FROM sesion WHERE token_hash=? AND usuario_id=? AND vence>? FOR UPDATE",
            hash(requireNotNull(autorizacion).substring(7)), actor.id, Instant.now().epochSecond,
        ).isNotEmpty()
        if (!vigente || !perfil(fila).activo) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (!Claves.verificar(actual, fila.getValue("CLAVE_HASH").toString()))
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Contraseña actual incorrecta")
        val perfilActual = perfil(fila)
        val actualizado = perfilActual.copy(version = perfilActual.version + 1)
        jdbc.update("UPDATE usuario SET clave_hash=?,version=? WHERE id=?",
            Claves.crear(nueva), actualizado.version, actualizado.id)
        invalidarAccesos(actualizado.id)
        return crearSesion(actualizado)
    }

    private fun invalidarAccesos(id: String) {
        jdbc.update("DELETE FROM sesion WHERE usuario_id=?", id)
        jdbc.update("DELETE FROM recuperacion_clave WHERE usuario_id=?", id)
    }

    @Transactional
    fun solicitarRecuperacion(correo: String) {
        val canal = correoRecuperacion?.takeIf { it.disponible() }
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recuperación por correo no disponible")
        if (!Validacion.correoValido(correo)) return
        val perfil = cuentaParaRecuperacion(correo) ?: return
        val ahora = Instant.now().epochSecond
        val previo = jdbc.queryForList(
            "SELECT solicitado FROM recuperacion_clave WHERE usuario_id=? FOR UPDATE", perfil.id,
        ).singleOrNull()?.get("SOLICITADO") as? Number
        if (previo != null && ahora - previo.toLong() < 60) return
        val codigo = nuevoToken(24)
        jdbc.update("DELETE FROM recuperacion_clave WHERE usuario_id=?", perfil.id)
        jdbc.update("INSERT INTO recuperacion_clave VALUES (?,?,?,?)", perfil.id, hash(codigo), ahora, ahora + 900)
        try {
            canal.enviar(perfil.correo, codigo)
        } catch (_: org.springframework.mail.MailException) {
            jdbc.update("DELETE FROM recuperacion_clave WHERE usuario_id=?", perfil.id)
        }
    }

    @Transactional
    fun recuperarClave(correo: String, codigo: String, nueva: String) {
        try {
            Validacion.validarClaveNueva(nueva)
        } catch (e: cl.induztek.protocol.domain.ErrorFormulario) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        fun codigoInvalido(): Nothing =
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Código inválido o vencido")
        if (!codigo.trim().matches(Regex("[A-Za-z0-9_-]{32}"))) codigoInvalido()
        val perfil = cuentaParaRecuperacion(correo) ?: codigoInvalido()
        val vigente = jdbc.queryForList(
            "SELECT codigo_hash FROM recuperacion_clave WHERE usuario_id=? AND vence>? FOR UPDATE",
            perfil.id, Instant.now().epochSecond,
        ).singleOrNull()?.get("CODIGO_HASH")?.toString() ?: codigoInvalido()
        if (!MessageDigest.isEqual(vigente.toByteArray(), hash(codigo.trim()).toByteArray())) codigoInvalido()
        jdbc.update("UPDATE usuario SET clave_hash=?,version=? WHERE id=?",
            Claves.crear(nueva), perfil.version + 1, perfil.id)
        invalidarAccesos(perfil.id)
    }

    private fun cuentaParaRecuperacion(correo: String): Identidad? =
        jdbc.queryForList("SELECT datos,version FROM usuario WHERE correo=? FOR UPDATE", correo.trim().lowercase())
            .singleOrNull()?.let(::perfil)?.takeIf { it.activo }

    private fun nuevoToken(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun crearSesion(u: Identidad): SesionRemota {
        val token = nuevoToken(32)
        jdbc.update(
            "INSERT INTO sesion VALUES (?,?,?)",
            hash(token),
            u.id,
            Instant.now().plusSeconds(604800).epochSecond,
        )
        return SesionRemota(token, u)
    }

    fun autenticar(authorization: String?): Identidad {
        val token =
            authorization?.takeIf { it.startsWith("Bearer ") }?.substring(7)
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val filas =
            jdbc.queryForList(
                "SELECT u.datos,u.version FROM usuario u JOIN sesion s ON s.usuario_id=u.id WHERE s.token_hash=? AND s.vence>?",
                hash(token),
                Instant.now().epochSecond,
            )
        val u =
            filas.firstOrNull()?.let {
                perfil(it)
            }
        return u?.takeIf { it.activo } ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
    }

    fun listar(actor: Identidad): List<Identidad> =
        jdbc
            .queryForList("SELECT datos,version FROM usuario")
            .map(::perfil)
            .filter {
                actor.perfil() != Perfil.CLIENTE ||
                    it.id == actor.id ||
                    it.perfil() != Perfil.CLIENTE
            }

    @Transactional
    fun personal(actor: Identidad, registros: List<JsonNode>): List<Identidad> {
        if (actor.perfil() != Perfil.JEFE_TECNICO)
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return registros.map { r ->
            val recibida = mapper.treeToValue(r, Identidad::class.java)
            val u = recibida.copy(rut = recibida.rut.trim())
            if (u.perfil() == Perfil.CLIENTE || !Validacion.rut(u.rut))
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Perfil o RUT inválido")
            if (u.id == actor.id && (!u.activo || u.perfil() != Perfil.JEFE_TECNICO))
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "No puedes quitar tu propio acceso de jefatura",
                )
            val anterior = buscar(u.id)
            if (
                anterior != null &&
                    perfil(anterior).perfil() == Perfil.CLIENTE
            )
                throw ResponseStatusException(HttpStatus.FORBIDDEN)
            val hash = r.path("claveHash").asText().takeIf { it.isNotBlank() }
            if (anterior == null && hash == null)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Contraseña requerida")
            guardar(u, hash, anterior)
        }
    }

    @Transactional
    fun sincronizar(actor: Identidad, registros: List<JsonNode>): List<Identidad> {
        if (actor.perfil() == Perfil.CLIENTE) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return registros.map { r ->
            val recibida = mapper.treeToValue(r, Identidad::class.java)
            val u = recibida.copy(rut = recibida.rut.trim())
            val anterior = buscar(u.id)
            if (u.perfil() != Perfil.CLIENTE)
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "No se puede reasignar un perfil desde sincronización",
                )
            if (!Validacion.rut(u.rut))
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "RUT inválido")
            if (anterior != null && perfil(anterior).perfil() != Perfil.CLIENTE)
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "No se puede reasignar un perfil")
            if (anterior != null && actor.perfil() != Perfil.JEFE_TECNICO) {
                val claveEnviada = r.path("claveHash").asText().takeIf { it.isNotBlank() }

                if (!iguales(perfil(anterior), u) ||
                    claveEnviada != null && claveEnviada != anterior["CLAVE_HASH"].toString())
                    throw ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Solo jefatura modifica cuentas",
                    )
                perfil(anterior)
            } else {
                if (actor.perfil() != Perfil.JEFE_TECNICO && u.activo)
                    throw ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "La cuenta nueva debe estar inactiva",
                    )
                val clave = r.path("claveHash").asText().takeIf { it.isNotBlank() }
                    ?: if (anterior == null) Claves.crear(java.util.UUID.randomUUID().toString()) else null
                guardar(u, clave, anterior)
            }
        }
    }
}

data class CambioClave(val actual: String, val nueva: String)
data class SolicitudRecuperacion(val correo: String)
data class ConfirmacionRecuperacion(val correo: String, val codigo: String, val nueva: String)

@RestController
@RequestMapping("/api")
class IdentidadController(private val usuarios: UsuariosService) {
    @GetMapping("/salud") fun salud() = mapOf("estado" to "ok", "servicio" to "identidad")

    @PostMapping("/sesiones") fun login(@RequestBody c: Credenciales) = usuarios.ingresar(c)

    @GetMapping("/sesion")
    fun sesion(@RequestHeader("Authorization", required = false) token: String?) =
        usuarios.autenticar(token)

    @PostMapping("/recuperacion/solicitar")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun solicitar(@RequestBody solicitud: SolicitudRecuperacion) =
        usuarios.solicitarRecuperacion(solicitud.correo)

    @PostMapping("/recuperacion/confirmar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun recuperar(@RequestBody solicitud: ConfirmacionRecuperacion) =
        usuarios.recuperarClave(solicitud.correo, solicitud.codigo, solicitud.nueva)

    @PostMapping("/mi-contrasena")
    fun cambiarClave(
        @RequestHeader("Authorization", required = false) token: String?,
        @RequestBody cambio: CambioClave,
    ) = usuarios.cambiarClave(token, cambio.actual, cambio.nueva)

    @GetMapping("/usuarios")
    fun listar(@RequestHeader("Authorization", required = false) token: String?) =
        usuarios.listar(usuarios.autenticar(token))

    @PostMapping("/personal")
    fun personal(
        @RequestHeader("Authorization", required = false) token: String?,
        @RequestBody registros: List<JsonNode>,
    ): List<Identidad> = usuarios.personal(usuarios.autenticar(token), registros)

    @PostMapping("/clientes")
    fun guardar(
        @RequestHeader("Authorization", required = false) token: String?,
        @RequestBody registros: List<JsonNode>,
    ): List<Identidad> = usuarios.sincronizar(usuarios.autenticar(token), registros)
}
