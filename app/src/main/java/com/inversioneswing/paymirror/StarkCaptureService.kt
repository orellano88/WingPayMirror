package com.inversioneswing.paymirror

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.regex.Pattern

import java.util.concurrent.Executors

class StarkCaptureService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private val CHANNEL_ID = "WING_OMEGA_CHANNEL"
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private val pendingMessages = mutableListOf<String>()
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WingPay:WakeLock")
        // Bloqueo de CPU infinito para que no muera con la pantalla apagada
        if (!wakeLock.isHeld) { wakeLock.acquire() }
    }

    private fun awakeAndSpeak(text: String) {
        // No necesitamos re-adquirir aquí si ya lo tenemos en onCreate, 
        // pero por seguridad aseguramos que el sistema esté despierto
        if (!wakeLock.isHeld) { wakeLock.acquire(15 * 1000L) }
        
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        
        speak(text)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createPersistentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
        intent?.getStringExtra("TEST_VOICE")?.let { awakeAndSpeak(it) }
        if (!::tts.isInitialized) { tts = TextToSpeech(this, this) }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_MIN (1) o IMPORTANCE_NONE (0): Cero intrusión.
            val channel = NotificationChannel(CHANNEL_ID, "WING Omega Sentinel", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Sincronización Stark Silenciosa"
                enableVibration(false)
                setShowBadge(false) // No muestra el punto en el icono
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET // Escondido en pantalla de bloqueo
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createPersistentNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("WingPay Activo")
        .setContentText("Sentinel operando en segundo plano")
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setPriority(NotificationCompat.PRIORITY_MIN) // Prioridad mínima para que no salte
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setSilent(true) // Forzar silencio total
        .setOngoing(true)
        .build()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.lowercase()
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val fullContent = "$title $text"

        if (pkg.contains("telegram")) {
            if (fullContent.uppercase().contains("[TEST_PAGO]")) {
                val fakeContent = fullContent.replace("[TEST_PAGO]", "", true).trim()
                processSmartContent(fakeContent, "yape_test")
                return
            }
            if (fullContent.uppercase().contains("[ALERTA_SOS]")) {
                activarAlertaCritica()
                return
            }
        }

        if (pkg.contains("yape") || pkg.contains("bcp") || pkg.contains("plin") || pkg.contains("interbank")) {
            if (!processSmartContent(fullContent, pkg)) {
                // Si detectamos el banco pero el regex falló, mandamos un log de DEBUG al espejo
                networkExecutor.execute {
                    sendDebugToMirror("FALLO_REGEX", "Banco: $pkg | Contenido: $fullContent")
                }
            }
        }
    }

    private fun sendDebugToMirror(type: String, log: String) {
        try {
            val topic = "wingpay_stark_8502345704"
            val url = URL("https://ntfy.sh/$topic")
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Title", "DEBUG $type")
                val json = JSONObject().apply {
                    put("type", type); put("log", log); put("time", System.currentTimeMillis())
                }
                OutputStreamWriter(outputStream).use { it.write(json.toString()) }
                responseCode; disconnect()
            }
        } catch (e: Exception) {}
    }

    private fun processSmartContent(content: String, pkg: String): Boolean {
        // Regex Mejorado: Detecta S/, S ., S, S /., sin importar los espacios, e incluso sin decimales
        val regex = Pattern.compile("(?i)(S\\s*/?\\s*\\.?)\\s*([\\d,]+\\.\\d{2}|[\\d,]+)")
        val matcher = regex.matcher(content)
        
        if (matcher.find()) {
            val montoRaw = matcher.group(2)?.replace(",", "") ?: "0.00"
            val montoFull = matcher.group(0)!!
            
            var nombreRaw = content
                .replace(montoFull, "", true)
                .replace("¡Yapeaste!", "", true)
                .replace("te envió", "", true)
                .replace("te ha yapeado", "", true)
                .replace("te pagó", "", true)
                .replace("Pago recibido", "", true)
                .replace("Transferencia exitosa", "", true)
                .replace("Confirmación de pago", "", true)
                .replace(Regex("[^a-zA-Z\\sñÑáéíóúÁÉÍÓÚ]"), "") // Corregido: Ahora admite tildes y eñes
                .trim()

            val palabras = nombreRaw.split(" ").filter { it.length > 1 }
            nombreRaw = if (palabras.size > 3) palabras.take(3).joinToString(" ") else palabras.joinToString(" ")

            if (nombreRaw.isEmpty()) nombreRaw = "un cliente"
            val nombreLimpio = formatTitleCase(nombreRaw)
            
            val banco = when {
                pkg.contains("yape") -> "YAPE"
                pkg.contains("bcp") -> "BCP"
                pkg.contains("plin") -> "PLIN"
                pkg.contains("interbank") -> "INTERBANK"
                else -> "su cuenta"
            }

            val partesMonto = montoRaw.split(".")
            val soles = partesMonto[0]
            val centimos = if (partesMonto.size > 1) partesMonto[1] else "00"
            
            val mensajeFinal = "¡Aviso de Pago! $banco. $nombreLimpio te envió $soles soles con $centimos céntimos."
            awakeAndSpeak(mensajeFinal)
            
            // CONSENSO STARK-QWEN: Uso de Executor para evitar fugas de memoria en EMUI
            networkExecutor.execute {
                sendToMirror(banco, nombreLimpio, montoRaw)
            }
            return true
        }
        return false
    }

    private fun formatTitleCase(str: String): String {
        if (str.isEmpty()) return str
        return str.lowercase().split(" ").filter { it.isNotEmpty() }.joinToString(" ") { 
            it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() } 
        }
    }

    private fun sendToMirror(banco: String, nombre: String, monto: String) {
        try {
            val topic = "wingpay_stark_8502345704"
            val url = URL("https://ntfy.sh/$topic")
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Title", "PAGO $banco")
                val json = JSONObject().apply {
                    put("bank", banco); put("name", nombre); put("amt", monto); put("time", System.currentTimeMillis())
                }
                OutputStreamWriter(outputStream).use { it.write(json.toString()) }
                responseCode; disconnect()
            }
        } catch (e: Exception) {}
    }

    private fun activarAlertaCritica() {
        awakeAndSpeak("Alerta de pánico activada en la red Stark.")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(5000)
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            tts.speak(text.lowercase(), TextToSpeech.QUEUE_FLUSH, params, "STARK_ID")
        } else { pendingMessages.add(text) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "MX")
            isTtsReady = true
            while (pendingMessages.isNotEmpty()) { speak(pendingMessages.removeAt(0)) }
        }
    }

    private fun sendToTelegram(message: String) {
        val token = "8629465941:AAH-5rwmNDTP_91UKZIRrJO_oZ24p1IcIQE"
        val chatId = "8502345704"
        try {
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(outputStream).use { it.write(JSONObject().apply { put("chat_id", chatId); put("text", message); put("parse_mode", "Markdown") }.toString()) }
                responseCode; disconnect()
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        serviceJob.cancel()
        if (::tts.isInitialized) { tts.shutdown() }
        super.onDestroy()
    }
}
