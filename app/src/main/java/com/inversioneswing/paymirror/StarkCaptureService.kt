package com.inversioneswing.paymirror

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.firebase.database.FirebaseDatabase
import java.util.regex.Pattern

class StarkCaptureService : NotificationListenerService() {

    private val db = FirebaseDatabase.getInstance().getReference("pagos_ventas")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == "com.bcp.innovabcp" || pkg == "com.viabcp.bcp") {
            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val fullContent = "$text $bigText"

            processPayment(fullContent, pkg)
        }
    }

    private fun processPayment(content: String, pkg: String) {
        // Regex para capturar montos (S/ 10.00, S/10, S/. 10.00)
        val regex = Pattern.compile("(S/|S/\\.|S/\\s)(\\d+\\.\\d{2}|\\d+)")
        val matcher = regex.matcher(content)

        if (matcher.find()) {
            val monto = matcher.group(2)
            val nombre = content.replace(matcher.group(0)!!, "")
                                .replace("¡Yapeaste!", "")
                                .replace("te envió", "")
                                .replace("notificación", "").trim()

            val paymentId = System.currentTimeMillis().toString()
            val paymentData = mapOf(
                "id" to paymentId,
                "nombre" to nombre,
                "monto" to monto,
                "timestamp" to paymentId,
                "banco" to if(pkg.contains("innova")) "YAPE" else "BCP"
            )

            // Sincronización Espejo vía Firebase
            db.child(paymentId).setValue(paymentData)
        }
    }
}
