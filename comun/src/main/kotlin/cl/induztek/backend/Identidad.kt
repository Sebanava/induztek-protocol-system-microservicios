package cl.induztek.backend

import cl.induztek.protocol.model.Perfil

data class Identidad(
    val id: String,
    val rolId: String,
    val nombre: String,
    val rut: String,
    val correo: String,
    val activo: Boolean = true,
    val contacto: String = "",
    val cargo: String = "",
    val giro: String = "",
    val telefono: String = "",
    val version: Long = 0,
) {
    fun perfil() = Perfil.valueOf(rolId)
}

data class Credenciales(val correo: String, val clave: String)

data class SesionRemota(val token: String, val usuario: Identidad)
