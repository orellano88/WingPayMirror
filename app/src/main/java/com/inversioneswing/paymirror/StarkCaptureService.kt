package com.inversioneswing.paymirror

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StarkCaptureService : NotificationListenerService() {

    private val client = OkHttpClient()
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

            val data = mapOf(
                "nombre" to nombre,
                "monto" to monto,
                "timestamp" to System.currentTimeMillis(),
                "banco" to if(pkg.contains("innova")) "YAPE" else "BCP"
            )

            // Enviar a la nube vía REST (Protocolo Stark Independiente)
            CoroutineScope(Dispatchers.IO).launch {
                val body = Gson().toJson(data).toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(dbUrl).post(body).build()
                client.newCall(request).execute()
            }
        }
    }
}
