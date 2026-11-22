package cl.induztek.bitacora

import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.server.ResponseStatusException

@SpringBootApplication
class BitacoraApplication

fun main(args: Array<String>) {
    runApplication<BitacoraApplication>(*args)
}

data class Actor(val id: String)
data class Evento(val id: UUID)
data class Entrada(val id: UUID, val fechaEpoch: Long)

@Service
class Identidad(@Value("\${induztek.identidad-url}") url: String, builder: RestClient.Builder) {
    private val cliente = builder.baseUrl(url).build()

    fun autenticar(token: String?): String {
        if (token.isNullOrBlank()) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        try {
            return cliente.get().uri("/api/sesion").header("Authorization", token)
                .retrieve().body(Actor::class.java)?.id
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        } catch (e: RestClientResponseException) {
            val estado = if (e.statusCode.value() == 401) HttpStatus.UNAUTHORIZED else HttpStatus.SERVICE_UNAVAILABLE
            throw ResponseStatusException(estado)
        } catch (e: RestClientException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        }
    }
}

@Service
class Bitacora(private val jdbc: JdbcTemplate) {
    init {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS evento_sync (
                actor VARCHAR(100) NOT NULL,
                id CHAR(36) NOT NULL,
                fecha BIGINT NOT NULL,
                PRIMARY KEY (actor, id),
                INDEX evento_actor_fecha (actor, fecha)
            )
        """.trimIndent())
    }

    fun registrar(actor: String, evento: Evento): Entrada {
        try {
            jdbc.update(
                "INSERT INTO evento_sync (actor, id, fecha) VALUES (?, ?, ?)",
                actor, evento.id.toString(), System.currentTimeMillis()
            )
        } catch (_: org.springframework.dao.DuplicateKeyException) {
            return obtener(actor, evento)
        }
        return obtener(actor, evento)
    }

    private fun obtener(actor: String, evento: Evento): Entrada = requireNotNull(
        jdbc.queryForObject("SELECT fecha FROM evento_sync WHERE actor=? AND id=?", Long::class.java, actor, evento.id.toString())
    ).let { Entrada(evento.id, it) }

    fun listar(actor: String): List<Entrada> =
        jdbc.query("SELECT id, fecha FROM evento_sync WHERE actor=? ORDER BY fecha DESC, id LIMIT 50",
            { rs, _ -> Entrada(UUID.fromString(rs.getString("id")), rs.getLong("fecha")) }, actor)
}

@RestController
@RequestMapping("/api/bitacora")
class BitacoraController(private val identidad: Identidad, private val bitacora: Bitacora) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun registrar(@RequestHeader("Authorization", required = false) token: String?, @RequestBody evento: Evento) =
        bitacora.registrar(identidad.autenticar(token), evento)

    @GetMapping
    fun listar(@RequestHeader("Authorization", required = false) token: String?) =
        bitacora.listar(identidad.autenticar(token))
}
