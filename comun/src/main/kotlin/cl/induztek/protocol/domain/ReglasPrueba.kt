package cl.induztek.protocol.domain

import cl.induztek.protocol.model.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

object ReglasPrueba {
    fun resultado(
        tipoEquipo: String,
        tipo: String,
        valores: Map<String, Double>,
        maxContactos: Double?,
        opera: Boolean = true,
    ): Resultado? {
        fun v(codigo: String) = requireNotNull(valores[codigo]) { "Falta $codigo" }

        fun bandas(
            numerador: BigDecimal,
            denominador: BigDecimal,
            aprobado: String,
            observado: String,
        ): Resultado {
            val porcentajeEscalado = numerador.multiply(BigDecimal("100"))
            return when {
                porcentajeEscalado <= denominador.multiply(BigDecimal(aprobado)) ->
                    Resultado.APROBADO
                porcentajeEscalado <= denominador.multiply(BigDecimal(observado)) ->
                    Resultado.OBSERVADO
                else -> Resultado.RECHAZADO
            }
        }
        fun decimal(codigo: String) = BigDecimal.valueOf(v(codigo))
        return when (tipo) {
            "AISLACION" ->
                if (tipoEquipo !in listOf("TRANSFORMADOR", "CABLE")) null
                else {
                    require(v("LECTURA_1MIN") > 0)
                    val ip = v("LECTURA_10MIN") / v("LECTURA_1MIN")
                    when {
                        v("LECTURA_1MIN") < 100 || ip < 1 -> Resultado.RECHAZADO
                        ip < 2 -> Resultado.OBSERVADO
                        else -> Resultado.APROBADO
                    }
                }
            "TTR" -> {
                require(v("RELACION_TEORICA") > 0)
                bandas(
                    decimal("RELACION_MEDIDA").subtract(decimal("RELACION_TEORICA")).abs(),
                    decimal("RELACION_TEORICA"),
                    "0.5",
                    "1.0",
                )
            }
            "CONTACTOS" -> {
                require(maxContactos != null && maxContactos > 0)
                bandas(
                    decimal("RESISTENCIA_CONTACTO"),
                    BigDecimal.valueOf(maxContactos),
                    "100",
                    "120",
                )
            }
            "OPERACION_RELE" ->
                if (!opera) Resultado.RECHAZADO
                else {
                    require(v("TIEMPO_ESPERADO") > 0)
                    bandas(
                        decimal("TIEMPO_MEDIDO").subtract(decimal("TIEMPO_ESPERADO")).abs(),
                        decimal("TIEMPO_ESPERADO"),
                        "5.0",
                        "10.0",
                    )
                }
            "DEVANADOS" -> null
            else -> error("Tipo de prueba no permitido")
        }
    }

    fun global(resultados: List<Resultado?>): Resultado? =
        if (resultados.isEmpty() || resultados.any { it == null }) null
        else resultados.filterNotNull().maxBy { it.ordinal }

    fun variacion(actual: Double, anterior: Double): Double? =
        if (anterior == 0.0) null
        else
            BigDecimal.valueOf(actual)
                .subtract(BigDecimal.valueOf(anterior))
                .multiply(BigDecimal("100"))
                .divide(BigDecimal.valueOf(anterior).abs(), MathContext.DECIMAL128)
                .toDouble()

    fun significativa(variacion: Double?) = variacion != null && abs(variacion) > 25.0

    fun atrasado(fecha: LocalDate, hoy: LocalDate): Boolean {
        var limite = fecha
        var dias = 0
        while (dias < 2) {
            limite = limite.plusDays(1)
            if (limite.dayOfWeek !in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) dias++
        }
        return hoy.isAfter(limite)
    }
}
