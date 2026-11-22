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
        if (cuerpo.all { it == '0' }) return false
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

    private val zona = java.time.ZoneId.of("America/Santiago")
    fun ahora(): java.time.LocalDateTime = java.time.LocalDateTime.now(zona)
    fun hoy(): LocalDate = ahora().toLocalDate()

    private val parteLocal = Regex("[A-Za-z0-9!#%&'*+/=?^_`{|}~.-]+")
    private val etiqueta = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    private val ultimo = Regex("[A-Za-z]{2,63}|[Xx][Nn]--[A-Za-z0-9-]{1,59}")

    fun correoValido(valor: String): Boolean {
        val correo = valor.trim()
        if (correo.length > 200 || correo.count { it == '@' } != 1) return false
        val local = correo.substringBefore('@')
        val dominio = runCatching { java.net.IDN.toASCII(correo.substringAfter('@')) }.getOrNull()
            ?: return false
        val etiquetas = dominio.split('.')
        return local.length <= 64 && parteLocal.matches(local) &&
            !local.startsWith('.') && !local.endsWith('.') && ".." !in local &&
            etiquetas.size >= 2 && etiquetas.all(etiqueta::matches) && ultimo.matches(etiquetas.last())
    }

    fun correo(datos: Map<String, String>) {
        if (!correoValido(datos["correo"].orEmpty()))
            throw ErrorFormulario(mapOf("correo" to "Ingresa un correo válido, por ejemplo nombre@empresa.cl"))
    }

    private val formatoTelefono = Regex("[+]?[0-9 ()-]+")
    private val numeroNacional = Regex("[2-9][0-9]{8}")

    fun telefonoChileno(valor: String): String {
        val texto = valor.trim()
        if (texto.isEmpty()) return ""
        val compacto = texto.filter { it !in " ()-" }
        val nacional = when {
            compacto.startsWith("+56") -> compacto.drop(3)
            compacto.startsWith("0056") -> compacto.drop(4)
            else -> compacto
        }
        if (!formatoTelefono.matches(texto) || !numeroNacional.matches(nacional))
            throw ErrorFormulario(mapOf("telefono" to "Ingresa 9 dígitos de Chile, con +56 opcional"))
        return "+56$nacional"
    }

    fun fabricacionValida(anio: Int, fecha: LocalDate = hoy()): Boolean = anio in 1800..fecha.year
}