package com.inversioneswing.paymirror

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: HiveAdapter
    private val contentList = mutableListOf<Map<String, String>>()
    private var lastId = ""
    private var isAlertActive = false
    private var mediaPlayer: MediaPlayer? = null
    
    private var neuralId = ""
    private val prefs by lazy { getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE) }

    private val dbBaseUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/hives"
    private val alertBaseUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/alerts"
    
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            validateAndSaveLink(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadNeuralLink()
        setupUI()
        startNeuralSync()
    }

    private fun loadNeuralLink() {
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        neuralId = prefs.getString("NEURAL_ID", myId) ?: myId
    }

    private fun validateAndSaveLink(id: String) {
        val trimmed = id.trim()
        if (trimmed.length >= 12) {
            neuralId = trimmed
            prefs.edit().putString("NEURAL_ID", trimmed).apply()
            vibrate(200)
            Toast.makeText(this, "Enlace Neuronal Sincronizado", Toast.LENGTH_SHORT).show()
            updateUIState()
            restartSync()
        } else {
            Toast.makeText(this, "ID de Enlace Inválido", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupUI() {
        tts = TextToSpeech(this, this)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = HiveAdapter(contentList)
        recyclerView.adapter = adapter

        updateUIState()

        findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            val et = findViewById<EditText>(R.id.etMessage)
            if (et.text.isNotEmpty()) {
                sendToHive("MESSAGE", et.text.toString())
                et.text.clear()
            }
        }

        findViewById<ImageButton>(R.id.btnPanic).setOnClickListener { triggerGlobalAlert() }
        findViewById<ImageButton>(R.id.btnQR).setOnClickListener { showQRMenu() }
    }

    private fun updateUIState() {
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val badge = findViewById<TextView>(R.id.tvRoleBadge)
        if (neuralId == myId) {
            badge.text = "MODO MAESTRO [Activo]"
            badge.backgroundTintList = ColorStateList.valueOf(0x33000000)
        } else {
            badge.text = "MODO ESPEJO [Link: ${neuralId.take(6)}]"
            badge.backgroundTintList = ColorStateList.valueOf(0x3300F3FF.toInt())
        }
    }

    private fun showQRMenu() {
        val options = arrayOf("Generar Mi QR (Maestro)", "Escanear QR (Espejo)", "Restablecer Conexión")
        AlertDialog.Builder(this)
            .setTitle("ENLACE NEURONAL")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Generar Mi QR (Maestro)" -> showMyQR()
                    "Escanear QR (Espejo)" -> qrScanner.launch(ScanOptions().setPrompt("Apunta al QR Maestro"))
                    "Restablecer Conexión" -> {
                        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                        validateAndSaveLink(myId)
                    }
                }
            }.show()
    }

    private fun showMyQR() {
        try {
            val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val matrix = MultiFormatWriter().encode(myId, BarcodeFormat.QR_CODE, 500, 500)
            val bitmap = BarcodeEncoder().createBitmap(matrix)
            val iv = ImageView(this).apply { setImageBitmap(bitmap); setPadding(50, 50, 50, 50) }
            AlertDialog.Builder(this).setTitle("Código de Enlace").setView(iv).setPositiveButton("Listo", null).show()
        } catch (e: Exception) {}
    }

    private fun restartSync() {
        contentList.clear()
        adapter.notifyDataSetChanged()
        lastId = ""
    }

    private fun startNeuralSync() {
        activityScope.launch(Dispatchers.IO) {
            var backoff = 3000L
            while(isActive) {
                try {
                    val syncSuccess = syncData()
                    val alertSuccess = syncAlert()
                    backoff = if (syncSuccess && alertSuccess) 3000L else (backoff * 2).coerceAtMost(20000L)
                } catch (e: Exception) {
                    backoff = (backoff * 2).coerceAtMost(20000L)
                }
                delay(backoff)
            }
        }
    }

    private suspend fun syncData(): Boolean {
        val json = httpGet("$dbBaseUrl/$neuralId.json?limitToLast=30")
        if (TextUtils.isEmpty(json) || json == "null") return true
        
        val root = JSONObject(json)
        val newList = mutableListOf<Map<String, String>>()
        root.keys().forEach { key ->
            val obj = root.optJSONObject(key) ?: return@forEach
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { map[it] = obj.optString(it, "") }
            newList.add(map)
        }
        
        val sorted = newList.sortedBy { it["timestamp"]?.toLongOrNull() ?: 0L }
        
        withContext(Dispatchers.Main) {
            if (sorted.isNotEmpty() && sorted.last()["timestamp"] != lastId) {
                val isFirst = lastId == ""
                lastId = sorted.last()["timestamp"] ?: ""
                contentList.clear(); contentList.addAll(sorted)
                adapter.notifyDataSetChanged()
                recyclerView.smoothScrollToPosition(contentList.size - 1)
                if (!isFirst && sorted.last()["type"] == "PAYMENT") {
                    speakPayment(sorted.last()["nombre"] ?: "Externo", sorted.last()["monto"] ?: "0")
                }
            }
        }
        return true
    }

    private suspend fun syncAlert(): Boolean {
        val json = httpGet("$alertBaseUrl/$neuralId.json")
        withContext(Dispatchers.Main) {
            val isActive = json.contains("ACTIVE")
            val overlay = findViewById<View>(R.id.alert_overlay)
            if (isActive && !isAlertActive) {
                startPanicAlarm()
                overlay.visibility = View.VISIBLE
            } else if (!isActive && isAlertActive) {
                stopPanicAlarm()
                overlay.visibility = View.GONE
            }
        }
        return true
    }

    private fun triggerGlobalAlert() {
        val next = if (isAlertActive) "IDLE" else "ACTIVE"
        activityScope.launch(Dispatchers.IO) {
            httpPut("$alertBaseUrl/$neuralId.json", JSONObject().put("status", next).toString())
        }
    }

    private fun sendToHive(type: String, content: String) {
        val body = JSONObject().apply {
            put("type", type); put("content", content); put("user", Build.MODEL)
            put("timestamp", System.currentTimeMillis())
        }
        activityScope.launch(Dispatchers.IO) { httpPost("$dbBaseUrl/$neuralId.json", body.toString()) }
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try { conn.connectTimeout = 8000; conn.readTimeout = 8000
            if (conn.responseCode == 200) BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() } else ""
        } catch (e: Exception) { "" } finally { conn.disconnect() }
    }

    private fun httpPost(urlStr: String, data: String) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try { conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream).use { it.write(data); it.flush() }; conn.responseCode
        } catch (e: Exception) {} finally { conn.disconnect() }
    }

    private fun httpPut(urlStr: String, data: String) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try { conn.requestMethod = "PUT"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream).use { it.write(data); it.flush() }; conn.responseCode
        } catch (e: Exception) {} finally { conn.disconnect() }
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else v.vibrate(ms)
    }

    private fun startPanicAlarm() {
        isAlertActive = true
        mediaPlayer = MediaPlayer.create(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        mediaPlayer?.isLooping = true; mediaPlayer?.start()
        vibrate(1000)
    }

    private fun stopPanicAlarm() {
        isAlertActive = false; mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
    }

    private fun speakPayment(n: String, m: String) { if (isTtsReady) tts.speak("Pago de $n por $m soles", TextToSpeech.QUEUE_FLUSH, null, null) }
    override fun onInit(s: Int) { if (s == TextToSpeech.SUCCESS) { tts.language = Locale("es", "ES"); isTtsReady = true } }
    override fun onDestroy() { activityJob.cancel(); stopPanicAlarm(); super.onDestroy() }
}

class HiveAdapter(private val list: List<Map<String, String>>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemViewType(pos: Int) = if (list[pos]["type"] == "PAYMENT") 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) PaymentViewHolder(inf.inflate(R.layout.item_payment, parent, false))
        else MessageViewHolder(inf.inflate(R.layout.item_message, parent, false))
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val d = list[pos]; val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(d["timestamp"]?.toLongOrNull() ?: 0L))
        if (holder is PaymentViewHolder) {
            holder.tvNombre.text = d["nombre"]; holder.tvMonto.text = "S/ ${d["monto"]}"; holder.tvBanco.text = "${d["banco"]} • $time"
        } else if (holder is MessageViewHolder) {
            holder.tvUser.text = d["user"]; holder.tvMessage.text = d["content"]; holder.tvTime.text = time
        }
    }
    override fun getItemCount() = list.size
    class PaymentViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvNombre: TextView = v.findViewById(R.id.tvNombre); val tvMonto: TextView = v.findViewById(R.id.tvMonto); val tvBanco: TextView = v.findViewById(R.id.tvBanco)
    }
    class MessageViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvUser: TextView = v.findViewById(R.id.tvUser); val tvMessage: TextView = v.findViewById(R.id.tvMessage); val tvTime: TextView = v.findViewById(R.id.tvTime)
    }
}
