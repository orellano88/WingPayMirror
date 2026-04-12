package com.inversioneswing.paymirror

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.provider.MediaStore
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
    private var lastUpdateId = ""
    private var isAlertActive = false
    private var mediaPlayer: MediaPlayer? = null
    
    private var neuralId = ""
    private val prefs by lazy { getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE) }

    private val dbBaseUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/hives"
    private val alertBaseUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/alerts"
    
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (resultCode == RESULT_OK) sendToHive("IMAGE", "[FOTO CAPTURADA]")
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

    private fun setupUI() {
        tts = TextToSpeech(this, this)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = HiveAdapter(contentList)
        recyclerView.adapter = adapter

        updateUIState()

        val et = findViewById<EditText>(R.id.etMessage)
        findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            if (et.text.isNotEmpty()) {
                sendToHive("MESSAGE", et.text.toString())
                et.text.clear()
            }
        }

        findViewById<ImageButton>(R.id.btnPanic).setOnClickListener { triggerGlobalAlert() }
        findViewById<ImageButton>(R.id.btnQR).setOnClickListener { showQRMenu() }
        
        findViewById<ImageButton>(R.id.btnAttach).setOnClickListener {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            cameraLauncher.launch(intent)
        }

        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener {
            sendToHive("AUDIO", "[NOTA DE VOZ]")
            vibrate(100)
            Toast.makeText(this, "Micrófono Stark Activado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUIState() {
        val badge = findViewById<TextView>(R.id.tvRoleBadge)
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        if (neuralId == myId) {
            badge.text = "MODO MAESTRO"
            badge.backgroundTintList = ColorStateList.valueOf(0x33000000)
        } else {
            badge.text = "MODO ESPEJO [Link: ${neuralId.take(5)}]"
            badge.backgroundTintList = ColorStateList.valueOf(0x3300F3FF.toInt())
        }
    }

    private fun startNeuralSync() {
        activityScope.launch(Dispatchers.IO) {
            while(isActive) {
                try {
                    syncData()
                    syncAlert()
                } catch (e: Exception) {}
                delay(3000)
            }
        }
    }

    private suspend fun syncData() {
        val json = httpGet("$dbBaseUrl/$neuralId.json?limitToLast=25")
        if (TextUtils.isEmpty(json) || json == "null") return
        
        val root = JSONObject(json)
        val newList = mutableListOf<Map<String, String>>()
        root.keys().forEach { key ->
            val obj = root.optJSONObject(key) ?: return@forEach
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { map[it] = obj.optString(it, "") }
            newList.add(map)
        }
        
        val sorted = newList.sortedBy { it["timestamp"]?.toLongOrNull() ?: 0L }
        val currentTopId = if (sorted.isNotEmpty()) sorted.last()["timestamp"] + sorted.last()["content"] else ""

        if (currentTopId != lastUpdateId) {
            val isFirst = lastUpdateId == ""
            lastUpdateId = currentTopId
            withContext(Dispatchers.Main) {
                contentList.clear(); contentList.addAll(sorted)
                adapter.notifyDataSetChanged()
                recyclerView.scrollToPosition(contentList.size - 1)
                if (!isFirst && sorted.last()["type"] == "PAYMENT") {
                    speakPayment(sorted.last()["nombre"] ?: "Externo", sorted.last()["monto"] ?: "0")
                }
            }
        }
    }

    private suspend fun syncAlert() {
        val json = httpGet("$alertBaseUrl/$neuralId.json")
        val active = json.contains("ACTIVE")
        withContext(Dispatchers.Main) {
            val overlay = findViewById<View>(R.id.alert_overlay)
            if (active && !isAlertActive) { startPanicAlarm(); overlay.visibility = View.VISIBLE }
            else if (!active && isAlertActive) { stopPanicAlarm(); overlay.visibility = View.GONE }
        }
    }

    private fun triggerGlobalAlert() {
        val next = if (isAlertActive) "IDLE" else "ACTIVE"
        activityScope.launch(Dispatchers.IO) { httpPut("$alertBaseUrl/$neuralId.json", JSONObject().put("status", next).toString()) }
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
        return try { conn.connectTimeout = 5000; BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() } } 
        catch (e: Exception) { "" } finally { conn.disconnect() }
    }

    private fun httpPost(urlStr: String, data: String) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try { conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream).use { it.write(data); it.flush() }; conn.responseCode
        } finally { conn.disconnect() }
    }

    private fun httpPut(urlStr: String, data: String) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try { conn.requestMethod = "PUT"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream).use { it.write(data); it.flush() }; conn.responseCode
        } finally { conn.disconnect() }
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

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else v.vibrate(ms)
    }

    private fun showQRMenu() {
        val opt = arrayOf("Mi QR Maestro", "Escanear Espejo", "Reiniciar")
        AlertDialog.Builder(this).setItems(opt) { _, i ->
            when(i) {
                0 -> showMyQR()
                1 -> qrScanner.launch(ScanOptions().setPrompt("Escanea el QR Maestro"))
                2 -> { prefs.edit().clear().apply(); loadNeuralLink(); updateUIState(); restartSync() }
            }
        }.show()
    }

    private fun showMyQR() {
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val matrix = MultiFormatWriter().encode(myId, BarcodeFormat.QR_CODE, 500, 500)
        val iv = ImageView(this).apply { setImageBitmap(BarcodeEncoder().createBitmap(matrix)); setPadding(40,40,40,40) }
        AlertDialog.Builder(this).setTitle("ID Maestro").setView(iv).show()
    }

    private fun restartSync() { contentList.clear(); adapter.notifyDataSetChanged(); lastUpdateId = "" }
    private val qrScanner = registerForActivityResult(ScanContract()) { if(it.contents != null) { prefs.edit().putString("NEURAL_ID", it.contents).apply(); loadNeuralLink(); updateUIState(); restartSync() } }
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
