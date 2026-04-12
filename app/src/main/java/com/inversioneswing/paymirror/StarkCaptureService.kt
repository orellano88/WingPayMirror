package com.inversioneswing.paymirror

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StarkCaptureService : NotificationListenerService() {

    private val dbUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/pagos.json"

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == "com.bcp.innovabcp" || pkg == "com.viabcp.bcp") {
            val extras = sbn.notification.extras
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            
            processPayment("$text $bigText", pkg)
        }
    }

    private fun processPayment(content: String, pkg: String) {
        val regex = Pattern.compile("(S/|S/\\.|S/\\s)(\\d+\\.\\d{2}|\\d+)")
        val matcher = regex.matcher(content)

        if (matcher.find()) {
            val monto = matcher.group(2)
            val nombre = content.replace(matcher.group(0)!!, "")
                                .replace("¡Yapeaste!", "")
                                .replace("te envió", "").trim()

            val jsonBody = JSONObject().apply {
                put("nombre", nombre)
                put("monto", monto)
                put("timestamp", System.currentTimeMillis())
                put("banco", if(pkg.contains("innova")) "YAPE" else "BCP")
            }

            // Enviar a la nube vía REST Nativo (Protocolo Stark Independiente)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val connection = URL(dbUrl).openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    OutputStreamWriter(connection.outputStream).use { 
                        it.write(jsonBody.toString())
                        it.flush()
                    }

                    val responseCode = connection.responseCode
                    // Opcional: Log responseCode para debug
                    connection.disconnect()
                } catch (e: Exception) {
                    // Re-intento silencioso en caso de falla de red
                }
            }
        }
    }
}
