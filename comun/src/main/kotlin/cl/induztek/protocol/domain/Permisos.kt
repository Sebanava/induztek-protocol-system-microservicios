package cl.induztek.protocol.domain

import cl.induztek.protocol.model.*

object Permisos {
    fun editar(perfil: Perfil, usuarioId: String, creadorId: String, estado: Estado) =
        perfil.registra &&
            usuarioId == creadorId &&
            estado in listOf(Estado.BORRADOR, Estado.OBSERVADO)

    fun transicion(perfil: Perfil, actual: Estado, siguiente: Estado): Boolean {
        if (actual == Estado.FORMALIZADO || actual == Estado.ANULADO || perfil == Perfil.CLIENTE)
            return false
        return when (siguiente) {
            Estado.REGISTRADO_TERRENO ->
                perfil.registra && actual in listOf(Estado.BORRADOR, Estado.OBSERVADO)
            Estado.FORMALIZADO,
            Estado.OBSERVADO -> perfil.revisa && actual == Estado.REGISTRADO_TERRENO
            Estado.ANULADO -> perfil.revisa
            Estado.BORRADOR -> false
        }
    }
}
