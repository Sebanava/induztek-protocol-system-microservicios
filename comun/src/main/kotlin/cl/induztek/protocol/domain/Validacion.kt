package cl.induztek.protocol.domain

import java.time.LocalDate

class ErrorFormulario(val campos: Map<String, String>) :
    IllegalArgumentException(campos.values.firstOrNull() ?: "Revisa los datos")

object Validacion {
    fun requeridos(datos: Map<String, String>, vararg claves: String) {
        val errores =
            claves.filter { datos[it].isNullOrBlank() }.associateWith { "Campo obligatorio" }
        if (errores.isNotEmpty()) throw ErrorFormulario(errores)
    }

    fun numero(datos: Map<String, String>, clave: String, positivo: Boolean = true): Double {
        val valor = datos[clave]?.trim()?.replace(',', '.')?.toDoubleOrNull()
        if (valor == null || !valor.isFinite() || valor < 0 || (positivo && valor == 0.0))
            throw ErrorFormulario(
                mapOf(
                    clave to
                        if (positivo) "Ingresa un número mayor que cero"
                        else "Ingresa un número no negativo"
                )
            )
        return valor
    }

    fun decimalOpcional(datos: Map<String, String>, clave: String): Double? =
        if (datos[clave].isNullOrBlank()) null else numero(datos, clave)

    fun fecha(datos: Map<String, String>, clave: String): LocalDate =
        try {
            LocalDate.parse(datos[clave])
        } catch (_: Exception) {
            throw ErrorFormulario(mapOf(clave to "Usa fecha válida AAAA-MM-DD"))
        }

    fun normalizarRut(valor: String): String =
        valor.trim().replace(".", "").replace("-", "").uppercase()

    fun rut(valor: String): Boolean {
        val texto = normalizarRut(valor)
        if (!texto.matches(Regex("[0-9]{7,8}[0-9K]"))) return false
        val cuerpo = texto.dropLast(1)
        var suma = 0
        cuerpo.reversed().forEachIndexed { indice, digito ->
            suma += digito.digitToInt() * (2 + indice % 6)
        }
        val dv =
            when (val resto = 11 - suma % 11) {
                11 -> "0"
                10 -> "K"
                else -> resto.toString()
            }
        return dv == texto.takeLast(1)
    }

    fun validarRut(datos: Map<String, String>, clave: String = "rut") {
        if (!rut(datos[clave].orEmpty()))
            throw ErrorFormulario(mapOf(clave to "RUT con dígito verificador inválido"))
    }

    fun correo(datos: Map<String, String>) {
        if (!datos["correo"].orEmpty().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
            throw ErrorFormulario(mapOf("correo" to "Ingresa un correo válido"))
    }
}
