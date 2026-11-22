package cl.induztek.protocol.model

enum class Perfil(val titulo: String) {
    TECNICO("Técnico de terreno"),
    INGENIERO("Ingeniero de pruebas"),
    JEFE_TECNICO("Jefe técnico"),
    CLIENTE("Usuario cliente");

    val registra
        get() = this != CLIENTE

    val revisa
        get() = this == INGENIERO || this == JEFE_TECNICO

    val cierraVisita
        get() = this == TECNICO || this == INGENIERO
}

enum class Estado(val titulo: String) {
    BORRADOR("Borrador"),
    REGISTRADO_TERRENO("Registrado en terreno"),
    OBSERVADO("Observado"),
    FORMALIZADO("Formalizado"),
    ANULADO("Anulado");

    fun visible(perfil: Perfil) = perfil != Perfil.CLIENTE || this !in listOf(BORRADOR, ANULADO)

    fun etiqueta(perfil: Perfil) =
        if (perfil == Perfil.CLIENTE && this != FORMALIZADO) "Pendiente de formalización"
        else titulo
}

enum class Resultado(val titulo: String) {
    APROBADO("Aprobado"),
    OBSERVADO("Observado"),
    RECHAZADO("Rechazado"),
}

enum class Momento {
    UNICA,
    PREVIA,
    POSTERIOR,
}

enum class TipoVisita(val titulo: String) {
    SOLO_PRUEBAS("Solo pruebas"),
    PRUEBAS_Y_MANT("Pruebas y mantenimiento"),
}

object Catalogo {
    val equipos =
        linkedMapOf(
            "TRANSFORMADOR" to "Transformador",
            "INTERRUPTOR" to "Interruptor",
            "CELDA" to "Celda MT",
            "CABLE" to "Cable de poder",
            "RELE" to "Relé de protección",
        )
    val pruebas =
        linkedMapOf(
            "AISLACION" to "Resistencia de aislación",
            "TTR" to "Relación de transformación",
            "DEVANADOS" to "Resistencia de devanados",
            "CONTACTOS" to "Resistencia de contactos",
            "OPERACION_RELE" to "Operación de relé",
        )

    fun tipos(tipo: String): List<String> =
        when (tipo) {
            "TRANSFORMADOR" -> listOf("AISLACION", "TTR", "DEVANADOS")
            "INTERRUPTOR",
            "CELDA" -> listOf("AISLACION", "CONTACTOS")
            "CABLE" -> listOf("AISLACION")
            "RELE" -> listOf("OPERACION_RELE")
            else -> emptyList()
        }

    fun elementos(equipo: String, prueba: String): List<String> =
        when {
            prueba == "AISLACION" && equipo == "TRANSFORMADOR" -> listOf("AT-BT", "AT-T", "BT-T")
            prueba == "AISLACION" && equipo == "CABLE" -> listOf("Fase U", "Fase V", "Fase W")
            prueba == "TTR" -> listOf("H1-X1", "H2-X2", "H3-X3")
            prueba == "DEVANADOS" -> listOf("AT-U", "AT-V", "AT-W", "BT-U", "BT-V", "BT-W")
            prueba == "CONTACTOS" -> listOf("Polo 1", "Polo 2", "Polo 3")
            else -> listOf("General")
        }

    fun parametros(equipo: String, prueba: String): List<String> =
        when (prueba) {
            "AISLACION" ->
                listOf("TENSION_PRUEBA", "LECTURA_1MIN") +
                    if (equipo in listOf("TRANSFORMADOR", "CABLE")) listOf("LECTURA_10MIN")
                    else emptyList()
            "TTR" -> listOf("POSICION_TAP", "RELACION_TEORICA", "RELACION_MEDIDA")
            "DEVANADOS" -> listOf("RESISTENCIA")
            "CONTACTOS" -> listOf("RESISTENCIA_CONTACTO")
            "OPERACION_RELE" ->
                listOf(
                    "CORRIENTE_AJUSTE",
                    "CORRIENTE_OPERACION",
                    "TIEMPO_ESPERADO",
                    "TIEMPO_MEDIDO",
                )
            else -> emptyList()
        }

    val unidades =
        mapOf(
            "TENSION_PRUEBA" to "kV",
            "LECTURA_1MIN" to "MΩ",
            "LECTURA_10MIN" to "MΩ",
            "POSICION_TAP" to "—",
            "RELACION_TEORICA" to "—",
            "RELACION_MEDIDA" to "—",
            "RESISTENCIA" to "mΩ",
            "RESISTENCIA_CONTACTO" to "µΩ",
            "CORRIENTE_AJUSTE" to "A",
            "CORRIENTE_OPERACION" to "A",
            "TIEMPO_ESPERADO" to "ms",
            "TIEMPO_MEDIDO" to "ms",
        )
    val etiquetas =
        mapOf(
            "TENSION_PRUEBA" to "Tensión de prueba",
            "LECTURA_1MIN" to "Lectura a 1 minuto",
            "LECTURA_10MIN" to "Lectura a 10 minutos",
            "POSICION_TAP" to "Posición del cambiador (tap)",
            "RELACION_TEORICA" to "Relación teórica",
            "RELACION_MEDIDA" to "Relación medida",
            "RESISTENCIA" to "Resistencia de devanado",
            "RESISTENCIA_CONTACTO" to "Resistencia de contacto",
            "CORRIENTE_AJUSTE" to "Corriente de ajuste",
            "CORRIENTE_OPERACION" to "Corriente de operación",
            "TIEMPO_ESPERADO" to "Tiempo esperado",
            "TIEMPO_MEDIDO" to "Tiempo medido",
        )
}
