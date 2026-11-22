package cl.induztek.protocol.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Claves {
    private const val ITERACIONES = 120_000

    fun crear(clave: String): String {
        val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "$ITERACIONES:${Base64.getEncoder().encodeToString(sal)}:${Base64.getEncoder().encodeToString(derivar(clave, sal, ITERACIONES))}"
    }

    fun verificar(clave: String, guardada: String): Boolean =
        try {
            val partes = guardada.split(':')
            MessageDigest.isEqual(
                Base64.getDecoder().decode(partes[2]),
                derivar(clave, Base64.getDecoder().decode(partes[1]), partes[0].toInt()),
            )
        } catch (_: Exception) {
            false
        }

    private fun derivar(clave: String, sal: ByteArray, iteraciones: Int): ByteArray {
        val spec = PBEKeySpec(clave.toCharArray(), sal, iteraciones, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
