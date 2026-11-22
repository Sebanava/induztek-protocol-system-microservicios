package cl.induztek.bitacora

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.server.ResponseStatusException

class BitacoraTest {
    private val db = DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
    private val servicio = Bitacora(JdbcTemplate(db))

    @Test
    fun registraUnaVezYSeparaCuentas() {
        val evento = Evento(UUID.randomUUID())
        val entrada = servicio.registrar("tecnico", evento)
        assertEquals(entrada, servicio.registrar("tecnico", evento))
        assertEquals(listOf(entrada), servicio.listar("tecnico"))
        assertTrue(servicio.listar("cliente").isEmpty())
        servicio.registrar("cliente", evento)
        assertEquals(1, servicio.listar("cliente").size)
    }

    @Test
    fun controladorUsaLaIdentidadAutenticada() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val token = "Bearer prueba"
        val url = "http://localhost:8081"
        server.expect(org.springframework.test.web.client.ExpectedCount.twice(), requestTo("$url/api/sesion"))
            .andExpect(header("Authorization", token))
            .andRespond(withSuccess("""{"id":"tecnico"}""", MediaType.APPLICATION_JSON))
        val identidad = Identidad(url, builder)
        val api = BitacoraController(identidad, servicio)
        val evento = Evento(UUID.randomUUID())
        assertEquals(evento.id, api.registrar(token, evento).id)
        assertEquals(evento.id, api.listar(token).single().id)
        server.verify()
    }

    @Test
    fun distingueSesionInvalidaDeIdentidadNoDisponible() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val url = "http://localhost:8081"
        server.expect(requestTo("$url/api/sesion")).andRespond(withUnauthorizedRequest())
        server.expect(requestTo("$url/api/sesion")).andRespond(withServerError())
        val identidad = Identidad(url, builder)
        assertEquals(401, assertThrows(ResponseStatusException::class.java) { identidad.autenticar("Bearer x") }.statusCode.value())
        assertEquals(503, assertThrows(ResponseStatusException::class.java) { identidad.autenticar("Bearer y") }.statusCode.value())
        server.verify()
    }

    @Test
    fun rechazaSolicitudSinCredencial() {
        val error = assertThrows(ResponseStatusException::class.java) {
            Identidad("http://localhost:8081", RestClient.builder()).autenticar(null)
        }
        assertEquals(401, error.statusCode.value())
    }
}
