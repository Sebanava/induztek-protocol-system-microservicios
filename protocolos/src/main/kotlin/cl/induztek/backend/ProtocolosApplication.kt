package cl.induztek.backend

import cl.induztek.protocol.domain.*
import cl.induztek.protocol.model.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.LocalDateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException

@SpringBootApplication class ProtocolosApplication

fun main(args: Array<String>) {
    runApplication<ProtocolosApplication>(*args)
}

data class Cambio(
    val tipo: String,
    val id: String,
    val version: Long,
    val operacionId: String,
    val datos: JsonNode,
)

data class RegistroRemoto(val tipo: String, val id: String, val version: Long, val datos: JsonNode)

@Service
class IdentidadRemota(@Value("\${induztek.identidad-url}") url: String) {
    private val http = RestClient.builder().baseUrl(url).build()

    fun usuarios(token: String): List<Identidad> =
        http
            .get()
            .uri("/api/usuarios")
            .header("Authorization", token)
            .retrieve()
            .body(
                object : org.springframework.core.ParameterizedTypeReference<List<Identidad>>() {}
            ) ?: emptyList()

    fun autenticar(token: String?): Identidad {
        if (token == null || !token.startsWith("Bearer "))
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return try {
            http
                .get()
                .uri("/api/sesion")
                .header("Authorization", token)
                .retrieve()
                .body(Identidad::class.java)!!
        } catch (e: org.springframework.web.client.RestClientResponseException) {
            if (e.statusCode.value() in listOf(401, 403))
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión inválida")
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Servicio de identidad temporalmente no disponible",
            )
        } catch (_: Exception) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Servicio de identidad temporalmente no disponible",
            )
        }
    }
}

@Service
class RegistroService(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    init {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS registro (tipo VARCHAR(30), id VARCHAR(120), version BIGINT NOT NULL, datos LONGTEXT NOT NULL, instalacion VARCHAR(120), tag VARCHAR(120), visita VARCHAR(120), equipo VARCHAR(120), numero VARCHAR(160), PRIMARY KEY(tipo,id), UNIQUE(tipo,instalacion,tag), UNIQUE(tipo,visita,equipo), UNIQUE(tipo,numero))"
        )
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS operacion (id VARCHAR(180) PRIMARY KEY, actor VARCHAR(100) NOT NULL, tipo VARCHAR(30), registro_id VARCHAR(120), version BIGINT NOT NULL, datos LONGTEXT NOT NULL)"
        )
        if (jdbc.queryForObject("SELECT COUNT(*) FROM registro", Int::class.java) == 0) {
            mapper.readTree(javaClass.getResourceAsStream("/registros-demo.json")).forEach { r ->
                persistir(RegistroRemoto(r["tipo"].asText(), r["id"].asText(), 1, r["datos"]))
            }
        }
    }

    private fun texto(n: JsonNode, k: String) = n.path(k).asText("")

    private fun error(m: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, m)

    private fun permiso(ok: Boolean, m: String = "Operación no autorizada") {
        if (!ok) throw ResponseStatusException(HttpStatus.FORBIDDEN, m)
    }

    private fun todos(): List<RegistroRemoto> =
        jdbc.query("SELECT * FROM registro") { rs, _ ->
            RegistroRemoto(
                rs.getString("tipo"),
                rs.getString("id"),
                rs.getLong("version"),
                mapper.readTree(rs.getString("datos")),
            )
        }

    private fun clave(tipo: String, id: String) = "$tipo:$id"

    private fun entidad(r: RegistroRemoto) = r.datos.path("registro")

    private fun persistir(r: RegistroRemoto) {
        val d = entidad(r)
        jdbc.update(
            "INSERT INTO registro (tipo,id,version,datos,instalacion,tag,visita,equipo,numero) VALUES (?,?,?,?,?,?,?,?,?)",
            r.tipo,
            r.id,
            r.version,
            mapper.writeValueAsString(r.datos),
            if (r.tipo == "equipo") texto(d, "instalacionId") else null,
            if (r.tipo == "equipo") texto(d, "tag").trim().uppercase() else null,
            if (r.tipo == "protocolo") texto(d, "visitaId") else null,
            if (r.tipo == "protocolo") texto(d, "equipoId") else null,
            if (r.tipo == "protocolo") texto(d, "numero") else null,
        )
    }

    private fun persistirConVersion(r: RegistroRemoto, esperada: Long) {
        val d = entidad(r)
        val valores =
            arrayOf<Any?>(
                r.version,
                mapper.writeValueAsString(r.datos),
                if (r.tipo == "equipo") texto(d, "instalacionId") else null,
                if (r.tipo == "equipo") texto(d, "tag").trim().uppercase() else null,
                if (r.tipo == "protocolo") texto(d, "visitaId") else null,
                if (r.tipo == "protocolo") texto(d, "equipoId") else null,
                if (r.tipo == "protocolo") texto(d, "numero") else null,
            )
        if (esperada == 0L)
            jdbc.update(
                "INSERT INTO registro (version,datos,instalacion,tag,visita,equipo,numero,tipo,id) VALUES (?,?,?,?,?,?,?,?,?)",
                *valores,
                r.tipo,
                r.id,
            )
        else if (
            jdbc.update(
                "UPDATE registro SET version=?,datos=?,instalacion=?,tag=?,visita=?,equipo=?,numero=? WHERE tipo=? AND id=? AND version=?",
                *valores,
                r.tipo,
                r.id,
                esperada,
            ) != 1
        )
            error("El registro cambió durante el envío; reintenta sin borrar tus datos")
    }

    fun listar(actor: Identidad): List<RegistroRemoto> {
        val registros = todos()
        if (actor.perfil() != Perfil.CLIENTE) return registros
        val instalaciones =
            registros
                .filter { it.tipo == "instalacion" && texto(entidad(it), "clienteId") == actor.id }
                .map { it.id }
                .toSet()
        val equipos =
            registros
                .filter {
                    it.tipo == "equipo" && texto(entidad(it), "instalacionId") in instalaciones
                }
                .map { it.id }
                .toSet()
        val protocolos =
            registros.filter {
                it.tipo == "protocolo" &&
                    texto(entidad(it), "equipoId") in equipos &&
                    texto(entidad(it), "estado") !in listOf("BORRADOR", "ANULADO")
            }
        val instrumentos =
            protocolos
                .flatMap { it.datos.path("pruebas").map { p -> texto(p, "instrumentoId") } }
                .toSet()
        return registros.filter {
            when (it.tipo) {
                "instalacion" -> it.id in instalaciones
                "equipo" -> it.id in equipos
                "visita" -> texto(entidad(it), "instalacionId") in instalaciones
                "protocolo" -> it in protocolos
                "instrumento" -> it.id in instrumentos
                else -> false
            }
        }
    }

    @Transactional
    fun sincronizar(actor: Identidad, cambios: List<Cambio>): List<RegistroRemoto> {
        permiso(actor.perfil().registra)
        if (cambios.size > 2000) error("Lote demasiado grande")
        if (cambios.map { clave(it.tipo, it.id) }.distinct().size != cambios.size)
            error("Registros repetidos dentro del lote")
        val anteriores = todos().associateBy { clave(it.tipo, it.id) }
        val vista = anteriores.toMutableMap()
        cambios.forEach { c ->
            if (
                c.tipo !in listOf("instalacion", "equipo", "instrumento", "visita", "protocolo") ||
                    c.id.isBlank() ||
                    c.id != texto(c.datos.path("registro"), "id")
            )
                error("Identificador o tipo inválido")
            vista[clave(c.tipo, c.id)] = RegistroRemoto(c.tipo, c.id, c.version + 1, c.datos)
        }
        validarIdsHijos(vista)
        val resultado = mutableListOf<RegistroRemoto>()
        val nuevos = mutableSetOf<String>()
        cambios.forEach { c ->
            val previa =
                jdbc
                    .query(
                        "SELECT * FROM operacion WHERE id=?",
                        { rs, _ ->
                            Triple(
                                rs.getString("actor"),
                                mapper.readTree(rs.getString("datos")),
                                rs.getLong("version"),
                            )
                        },
                        c.operacionId,
                    )
                    .firstOrNull()
            if (previa != null) {
                permiso(previa.first == actor.id)
                if (previa.second != c.datos) error("La operación ya tiene otro contenido")
                resultado += RegistroRemoto(c.tipo, c.id, previa.third, c.datos)
            } else {
                val anterior = anteriores[clave(c.tipo, c.id)]
                if (anterior?.datos == c.datos) {
                    resultado += anterior
                    return@forEach
                }
                if ((anterior?.version ?: 0) != c.version)
                    error(
                        "Conflicto de versión en ${c.tipo} ${c.id}; se conservan los datos del dispositivo"
                    )
                validar(actor, c, anterior, vista)
                nuevos += clave(c.tipo, c.id)
                resultado += RegistroRemoto(c.tipo, c.id, c.version + 1, c.datos)
            }
        }
        resultado.forEach { r ->
            val c = cambios.first { it.tipo == r.tipo && it.id == r.id }
            if (clave(r.tipo, r.id) in nuevos) persistirConVersion(r, c.version)
            if (
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM operacion WHERE id=?",
                    Int::class.java,
                    c.operacionId,
                ) == 0
            )
                jdbc.update(
                    "INSERT INTO operacion VALUES (?,?,?,?,?,?)",
                    c.operacionId,
                    actor.id,
                    r.tipo,
                    r.id,
                    r.version,
                    mapper.writeValueAsString(r.datos),
                )
        }
        return resultado
    }

    private fun validarIdsHijos(vista: Map<String, RegistroRemoto>) {
        val ids = mutableSetOf<String>()
        val campos =
            mapOf(
                "instrumento" to listOf("calibraciones"),
                "visita" to listOf("participantes", "recepciones"),
                "protocolo" to listOf("pruebas", "mediciones", "historial"),
            )
        vista.values.forEach { r ->
            campos[r.tipo].orEmpty().forEach { campo ->
                r.datos.path(campo).forEach { hijo ->
                    val id = texto(hijo, "id")
                    if (id.isBlank() || !ids.add("$campo:$id"))
                        error("Identificador de $campo duplicado")
                }
            }
        }
    }

    private fun validar(
        actor: Identidad,
        c: Cambio,
        anterior: RegistroRemoto?,
        vista: Map<String, RegistroRemoto>,
    ) {
        val d = c.datos.path("registro")
        fun referencia(tipo: String, id: String) =
            vista[clave(tipo, id)] ?: error("Falta referencia $tipo $id")
        fun requerir(vararg campos: String) {
            campos.forEach { if (texto(d, it).isBlank()) error("Falta $it") }
        }
        when (c.tipo) {
            "instalacion" -> {
                requerir("clienteId", "nombre", "ubicacion", "comuna")
                if (
                    anterior != null &&
                        texto(entidad(anterior), "clienteId") != texto(d, "clienteId")
                )
                    error("No se puede cambiar la empresa de una instalación")
            }
            "equipo" -> {
                requerir("instalacionId", "tag", "tipoId", "marca", "modelo", "serie")
                referencia("instalacion", texto(d, "instalacionId"))
                if (texto(d, "tipoId") !in Catalogo.equipos) error("Tipo de equipo inválido")
                if (
                    texto(d, "tipoId") in listOf("CELDA", "INTERRUPTOR") &&
                        d.path("maxContactos").asDouble() <= 0
                )
                    error("Máximo de placa obligatorio")
                if (
                    anterior != null &&
                        listOf("instalacionId", "tipoId", "tag").any {
                            d.path(it) != entidad(anterior).path(it)
                        }
                )
                    error("La identidad del equipo no puede cambiar al sincronizar")
            }
            "instrumento" -> {
                permiso(actor.perfil() == Perfil.JEFE_TECNICO)
                requerir("nombre", "serie", "tipoPruebaId")
                if (texto(d, "tipoPruebaId") !in Catalogo.pruebas)
                    error("Prueba de instrumento inválida")
                val antiguas = anterior?.datos?.path("calibraciones")?.map { it } ?: emptyList()
                val nuevas = c.datos.path("calibraciones").map { it }
                if (!nuevas.containsAll(antiguas))
                    error("No se puede borrar el historial de calibraciones")
                nuevas.forEach {
                    if (texto(it, "desde") > texto(it, "hasta")) error("Calibración inválida")
                }
            }
            "visita" -> {
                if (
                    vista.values.any {
                        it.tipo == "visita" &&
                            it.id != c.id &&
                            texto(entidad(it), "ot").equals(texto(d, "ot"), true)
                    }
                )
                    error("OT duplicada")
                if (runCatching { java.time.LocalDate.parse(texto(d, "fecha")) }.isFailure)
                    error("Fecha de visita inválida")
                requerir("instalacionId", "ingenieroId", "ot", "fecha")
                referencia("instalacion", texto(d, "instalacionId"))
                permiso(actor.perfil().cierraVisita)
                val participantes = c.datos.path("participantes").map { texto(it, "usuarioId") }
                permiso(actor.id in participantes, "Solo un participante modifica la visita")
                if (
                    anterior != null &&
                        !entidad(anterior).path("cierre").isNull &&
                        entidad(anterior).has("cierre") &&
                        anterior.datos != c.datos
                )
                    error("El acta emitida no se puede modificar")
                if (d.hasNonNull("cierre")) {
                    val p =
                        vista.values.filter {
                            it.tipo == "protocolo" && texto(entidad(it), "visitaId") == c.id
                        }
                    if (
                        p.isEmpty() ||
                            p.any {
                                texto(entidad(it), "estado") !in
                                    listOf("REGISTRADO_TERRENO", "FORMALIZADO", "ANULADO")
                            }
                    )
                        error("Hay protocolos pendientes para cerrar la visita")
                    val r =
                        c.datos.path("recepciones").singleOrNull()
                            ?: error("Recepción única obligatoria")
                    if (
                        !Validacion.rut(texto(r, "rut")) ||
                            texto(r, "nombre").isBlank() ||
                            texto(r, "cargo").isBlank() ||
                            !texto(r, "documento").contains("Pendiente de formalización")
                    )
                        error("Recepción o acta incompleta")
                }
            }
            "protocolo" -> validarProtocolo(actor, c, anterior, vista)
        }
    }

    private fun validarProtocolo(
        actor: Identidad,
        c: Cambio,
        anterior: RegistroRemoto?,
        vista: Map<String, RegistroRemoto>,
    ) {
        val p = c.datos.path("registro")
        fun registro(tipo: String, id: String) = vista[clave(tipo, id)] ?: error("Falta $tipo $id")
        val equipo = entidad(registro("equipo", texto(p, "equipoId")))
        val visita = entidad(registro("visita", texto(p, "visitaId")))
        if (texto(equipo, "instalacionId") != texto(visita, "instalacionId"))
            error("Equipo ajeno a la visita")
        if (texto(p, "numero").isBlank()) error("Número de protocolo obligatorio")
        val fecha =
            runCatching { LocalDateTime.parse(texto(p, "fechaHora")) }
                .getOrElse { error("Fecha de prueba inválida") }
        if (fecha.toLocalDate().toString() != texto(visita, "fecha"))
            error("La prueba debe corresponder a la fecha de visita")
        if (p.hasNonNull("reemplazaId")) {
            val original = entidad(registro("protocolo", texto(p, "reemplazaId")))
            if (
                texto(original, "equipoId") != texto(p, "equipoId") ||
                    texto(original, "estado") != "FORMALIZADO"
            )
                error("Solo se reemplaza un formalizado del mismo equipo")
            if (
                vista.values.any {
                    it.tipo == "protocolo" &&
                        it.id != c.id &&
                        texto(entidad(it), "reemplazaId") == texto(p, "reemplazaId") &&
                        texto(entidad(it), "estado") != "ANULADO"
                }
            )
                error("El original ya tiene un reemplazo")
        }
        val estado =
            runCatching { Estado.valueOf(texto(p, "estado")) }
                .getOrElse { error("Estado inválido") }
        val previo = anterior?.let { Estado.valueOf(texto(entidad(it), "estado")) }
        if (previo in listOf(Estado.FORMALIZADO, Estado.ANULADO)) error("Protocolo inmutable")
        val propietario = texto(p, "creadorId") == actor.id
        if (anterior == null) {
            permiso(propietario)
            if (
                estado !in listOf(Estado.BORRADOR, Estado.REGISTRADO_TERRENO) &&
                    !(actor.perfil().revisa &&
                        estado in listOf(Estado.FORMALIZADO, Estado.OBSERVADO, Estado.ANULADO))
            )
                error("Estado inicial no permitido")
        } else {
            if (
                listOf("numero", "equipoId", "visitaId", "creadorId", "fechaHora", "reemplazaId")
                    .any { p.path(it) != entidad(anterior).path(it) }
            )
                error("No se puede cambiar la identidad del protocolo")
            if (previo in listOf(Estado.BORRADOR, Estado.OBSERVADO)) permiso(propietario)
            if (estado != previo) permiso(Permisos.transicion(actor.perfil(), previo!!, estado))
            if (previo == Estado.REGISTRADO_TERRENO) {
                permiso(actor.perfil().revisa)
                if (
                    listOf("temperatura", "humedad", "mantenimiento").any {
                        p.path(it) != entidad(anterior).path(it)
                    } || c.datos.path("mediciones") != anterior.datos.path("mediciones")
                )
                    error("El revisor no puede alterar mediciones")
                val previas = anterior.datos.path("pruebas").associateBy { texto(it, "id") }
                if (c.datos.path("pruebas").size() != previas.size)
                    error("El revisor no elimina pruebas")
                c.datos.path("pruebas").forEach { nueva ->
                    val vieja = previas[texto(nueva, "id")] ?: error("El revisor no agrega pruebas")
                    val a =
                        vieja.deepCopy<ObjectNode>().apply {
                            remove(listOf("resultado", "comentario"))
                        }
                    val b =
                        nueva.deepCopy<ObjectNode>().apply {
                            remove(listOf("resultado", "comentario"))
                        }
                    if (a != b) error("El revisor solo indica criterio manual")
                }
            }
        }
        if (estado in listOf(Estado.OBSERVADO, Estado.ANULADO) && texto(p, "motivo").isBlank())
            error("Motivo obligatorio")
        val historia = c.datos.path("historial").map { it }
        val viejaHistoria = anterior?.datos?.path("historial")?.map { it } ?: emptyList()
        if (!historia.containsAll(viejaHistoria))
            error("No se puede modificar ni borrar trazabilidad previa")
        val nuevaHistoria = historia.filter { it !in viejaHistoria }
        if (
            nuevaHistoria.isEmpty() ||
                nuevaHistoria.any {
                    texto(it, "usuarioId") != actor.id ||
                        texto(it, "protocoloId") != c.id ||
                        texto(it, "fechaHora").isBlank()
                }
        )
            error("Trazabilidad del autor obligatoria")
        if (nuevaHistoria.none { texto(it, "nuevo") == estado.name })
            error("Falta la transición de estado")
        val pruebas = c.datos.path("pruebas").map { it }
        fun vigentes(agregado: JsonNode): List<JsonNode> =
            agregado
                .path("pruebas")
                .groupBy { texto(it, "tipoId") to texto(it, "elemento") }
                .values
                .map { grupo ->
                    val realizadas = grupo.filter { it.path("realizada").asBoolean() }
                    (realizadas.ifEmpty { grupo }).maxBy {
                        runCatching { Momento.valueOf(texto(it, "momento")).ordinal }
                            .getOrElse { error("Momento de medición inválido") }
                    }
                }
        val vigentesIds = vigentes(c.datos).map { texto(it, "id") }.toSet()
        val mediciones = c.datos.path("mediciones").groupBy { texto(it, "pruebaId") }
        if (mediciones.keys.any { id -> pruebas.none { texto(it, "id") == id } })
            error("Medición de una prueba ajena")
        if (historia.any { texto(it, "protocoloId") != c.id }) error("Historial de otro protocolo")
        if (
            pruebas
                .map { Triple(texto(it, "tipoId"), texto(it, "elemento"), texto(it, "momento")) }
                .distinct()
                .size != pruebas.size
        )
            error("Prueba duplicada")
        pruebas.forEach { pr ->
            if (
                pr.hasNonNull("resultado") &&
                    texto(pr, "resultado") !in Resultado.entries.map { it.name }
            )
                error("Resultado inválido")
            if (runCatching { Momento.valueOf(texto(pr, "momento")) }.isFailure)
                error("Momento inválido")
            val tipo = texto(pr, "tipoId")
            if (
                tipo !in Catalogo.tipos(texto(equipo, "tipoId")) ||
                    texto(pr, "elemento") !in Catalogo.elementos(texto(equipo, "tipoId"), tipo)
            )
                error("Prueba incompatible")
            if (texto(pr, "protocoloId") != c.id) error("Prueba de otro protocolo")
            if (!pr.path("realizada").asBoolean()) {
                if (texto(pr, "motivo").isBlank()) error("Motivo de no realización obligatorio")
            } else {
                val instrumento = registro("instrumento", texto(pr, "instrumentoId"))
                if (texto(entidad(instrumento), "tipoPruebaId") != tipo)
                    error("Instrumento incompatible")
                val fecha = texto(p, "fechaHora").take(10)
                if (
                    instrumento.datos.path("calibraciones").none {
                        texto(it, "desde") <= fecha && texto(it, "hasta") >= fecha
                    }
                )
                    error("Calibración vencida")
                val valores =
                    mediciones[texto(pr, "id")].orEmpty().associate {
                        texto(it, "parametroId").substringAfter(':') to
                            it.path("valor").asDouble(Double.NaN)
                    }
                val filas = mediciones[texto(pr, "id")].orEmpty()
                if (
                    filas.size != valores.size ||
                        filas.any {
                            texto(it, "parametroId") !=
                                "$tipo:${texto(it,"parametroId").substringAfter(':')}"
                        }
                )
                    error("Parámetro duplicado o incompatible")
                val esperados =
                    Catalogo.parametros(texto(equipo, "tipoId"), tipo).filter {
                        tipo != "OPERACION_RELE" ||
                            pr.path("opera").asBoolean(true) ||
                            it !in listOf("CORRIENTE_OPERACION", "TIEMPO_MEDIDO")
                    }
                if (
                    valores.keys != esperados.toSet() ||
                        valores.any { (parametro, valor) ->
                            !valor.isFinite() || valor < 0 ||
                                (valor == 0.0 && parametro !in listOf(
                                    "POSICION_TAP", "RESISTENCIA", "RESISTENCIA_CONTACTO", "TIEMPO_MEDIDO",
                                )) ||
                                (parametro == "POSICION_TAP" && valor % 1.0 != 0.0)
                        }
                )
                    error("Mediciones incompletas o inválidas")
                val calculado =
                    runCatching {
                            ReglasPrueba.resultado(
                                texto(equipo, "tipoId"),
                                tipo,
                                valores,
                                equipo.path("maxContactos").takeUnless { it.isNull }?.asDouble(),
                                pr.path("opera").asBoolean(true),
                            )
                        }
                        .getOrElse { error("Valores fuera de rango") }
                if (calculado != null && texto(pr, "resultado") != calculado.name)
                    error("El resultado no coincide con las mediciones")
                if (
                    calculado == null &&
                        pr.hasNonNull("resultado") &&
                        !actor.perfil().revisa &&
                        anterior?.datos?.path("pruebas")?.none { it == pr } != false
                )
                    permiso(false, "Solo el ingeniero indica resultado manual")
                if (
                    estado == Estado.FORMALIZADO &&
                        texto(pr, "id") in vigentesIds &&
                        !pr.hasNonNull("resultado")
                )
                    error("Criterio manual pendiente")
            }
        }
        if (estado !in listOf(Estado.BORRADOR, Estado.ANULADO)) {
            if (
                !p.hasNonNull("temperatura") ||
                    !p.path("temperatura").asDouble(Double.NaN).isFinite() ||
                    !p.hasNonNull("humedad") ||
                    p.path("humedad").asDouble(-1.0) !in 0.0..100.0
            )
                error("Ambiente incompleto")
            if (pruebas.none { it.path("realizada").asBoolean() })
                error("Se requiere una prueba realizada")
            Catalogo.tipos(texto(equipo, "tipoId")).forEach { tipo ->
                Catalogo.elementos(texto(equipo, "tipoId"), tipo).forEach { el ->
                    if (pruebas.none { texto(it, "tipoId") == tipo && texto(it, "elemento") == el })
                        error("Falta prueba requerida")
                }
            }
        }
        if (estado == Estado.FORMALIZADO) {
            permiso(actor.perfil().revisa)
            if (!visita.hasNonNull("cierre")) error("La visita debe cerrarse antes de formalizar")

            val anteriores =
                vista.values
                    .filter {
                        it.tipo == "protocolo" &&
                            it.id != c.id &&
                            texto(entidad(it), "equipoId") == texto(p, "equipoId") &&
                            texto(entidad(it), "fechaHora") < texto(p, "fechaHora")
                    }
                    .sortedByDescending { texto(entidad(it), "fechaHora") }
            fun valores(agregado: JsonNode, pr: JsonNode): Map<String, Double> =
                agregado
                    .path("mediciones")
                    .filter { texto(it, "pruebaId") == texto(pr, "id") }
                    .associate { texto(it, "parametroId") to it.path("valor").asDouble() }
            fun contexto(valores: Map<String, Double>) =
                valores.filterKeys {
                    it.substringAfter(':') in
                        listOf(
                            "TENSION_PRUEBA",
                            "POSICION_TAP",
                            "CORRIENTE_AJUSTE",
                            "TIEMPO_ESPERADO",
                        )
                }
            val significativa =
                vigentes(c.datos)
                    .filter { it.path("realizada").asBoolean() }
                    .any { pr ->
                        val actuales = valores(c.datos, pr)
                        val previa =
                            anteriores
                                .filter {
                                    texto(entidad(it), "estado") !in listOf("BORRADOR", "ANULADO")
                                }
                                .firstNotNullOfOrNull { a ->
                                    vigentes(a.datos)
                                        .firstOrNull {
                                            texto(it, "tipoId") == texto(pr, "tipoId") &&
                                                texto(it, "elemento") == texto(pr, "elemento") &&
                                                it.path("realizada").asBoolean() &&
                                                contexto(valores(a.datos, it)) == contexto(actuales)
                                        }
                                        ?.let { a to it }
                                } ?: return@any false
                        val anterioresValores = valores(previa.first.datos, previa.second)
                        actuales
                            .filterKeys {
                                it.substringAfter(':') !in listOf("TENSION_PRUEBA", "POSICION_TAP")
                            }
                            .any { (k, v) ->
                                anterioresValores[k]?.let {
                                    ReglasPrueba.significativa(ReglasPrueba.variacion(v, it))
                                } == true
                            }
                    }
            if (significativa && texto(p, "motivo").isBlank())
                error("La variación significativa exige comentario del ingeniero")
        }
    }
}

@RestController
@RequestMapping("/api")
class ProtocolosController(
    private val registros: RegistroService,
    private val identidad: IdentidadRemota,
) {
    @GetMapping("/salud") fun salud() = mapOf("estado" to "ok", "servicio" to "protocolos")

    @GetMapping("/registros")
    fun listar(@RequestHeader("Authorization", required = false) token: String?) =
        registros.listar(identidad.autenticar(token))

    @PostMapping("/sincronizacion")
    fun sincronizar(
        @RequestHeader("Authorization", required = false) token: String?,
        @RequestBody cambios: List<Cambio>,
    ): List<RegistroRemoto> {
        val actor = identidad.autenticar(token)
        val usuarios = identidad.usuarios(token!!).associateBy { it.id }
        cambios.forEach { c ->
            val d = c.datos.path("registro")
            if (
                c.tipo == "instalacion" &&
                    usuarios[d.path("clienteId").asText()]?.perfil() != Perfil.CLIENTE
            )
                throw ResponseStatusException(HttpStatus.CONFLICT, "Empresa cliente no disponible")
            if (
                c.tipo == "visita" &&
                    usuarios[d.path("ingenieroId").asText()]?.let {
                        it.activo && it.perfil() == Perfil.INGENIERO
                    } != true
            )
                throw ResponseStatusException(HttpStatus.CONFLICT, "Ingeniero a cargo inválido")
            if (
                c.tipo == "protocolo" &&
                    usuarios[d.path("creadorId").asText()]?.perfil()?.registra != true
            )
                throw ResponseStatusException(HttpStatus.CONFLICT, "Autor del protocolo inválido")
        }
        return registros.sincronizar(actor, cambios)
    }
}

@RestControllerAdvice
class ErroresApi {
    @ExceptionHandler(ResponseStatusException::class)
    fun regla(e: ResponseStatusException) =
        org.springframework.http.ResponseEntity.status(e.statusCode)
            .body(mapOf("mensaje" to (e.reason ?: "Operación no autorizada")))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun duplicado() =
        org.springframework.http.ResponseEntity.status(409)
            .body(
                mapOf(
                    "mensaje" to
                        "TAG, número o equipo/visita duplicado; no se descartó información del dispositivo"
                )
            )
}
