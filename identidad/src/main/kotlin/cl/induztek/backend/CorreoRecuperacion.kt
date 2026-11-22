package cl.induztek.backend

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

interface CorreoRecuperacion {
    fun disponible(): Boolean
    fun enviar(destino: String, codigo: String)
}

@Service
class CorreoRecuperacionSmtp(
    private val remitente: ObjectProvider<JavaMailSender>,
    @Value("\${induztek.recuperacion.remitente:}") private val desde: String,
) : CorreoRecuperacion {
    override fun disponible() = desde.isNotBlank() && remitente.ifAvailable != null

    override fun enviar(destino: String, codigo: String) {
        remitente.getObject().send(SimpleMailMessage().apply {
            from = desde
            setTo(destino)
            subject = "Recuperación de acceso a Induztek"
            text = "Tu código de recuperación de Induztek es:\n\n$codigo\n\n" +
                "Caduca en 15 minutos y solo se puede usar una vez. Si no lo solicitaste, ignora este mensaje."
        })
    }
}