package com.inversioneswing.paymirror

import android.content.*
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private val dbUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/hive.json?limitToLast=50"
    private val alertUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/alert.json"
    
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupUI()
        startSyncLoops()
    }

    private fun setupUI() {
        tts = TextToSpeech(this, this)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = HiveAdapter(contentList)
        recyclerView.adapter = adapter

        val etMessage = findViewById<EditText>(R.id.etMessage)
        findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            val msg = etMessage.text.toString()
            if (msg.isNotEmpty()) {
                sendToHive("MESSAGE", msg)
                etMessage.text.clear()
            }
        }

        findViewById<ImageButton>(R.id.btnPanic).setOnClickListener {
            triggerGlobalAlert()
        }
    }

    private fun startSyncLoops() {
        activityScope.launch {
            while(isActive) {
                fetchHiveContent()
                checkGlobalAlert()
                delay(4000)
            }
        }
    }

    private fun fetchHiveContent() {
        activityScope.launch(Dispatchers.IO) {
            try {
                val json = httpGet(dbUrl)
                if (!TextUtils.isEmpty(json) && json != "null") {
                    val root = JSONObject(json)
                    val keys = root.keys()
                    val newList = mutableListOf<Map<String, String>>()

                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = root.optJSONObject(key) ?: continue
                        val map = mutableMapOf<String, String>()
                        obj.keys().forEach { map[it] = obj.optString(it, "") }
                        newList.add(map)
                    }

                    val sorted = newList.sortedBy { it["timestamp"] }

                    withContext(Dispatchers.Main) {
                        if (sorted.isNotEmpty() && sorted.last()["timestamp"] != lastId) {
                            val newEntry = sorted.last()
                            lastId = newEntry["timestamp"] ?: ""
                            
                            contentList.clear()
                            contentList.addAll(sorted)
                            adapter.notifyDataSetChanged()
                            recyclerView.scrollToPosition(contentList.size - 1)

                            if (newEntry["type"] == "PAYMENT") {
                                speakPayment(newEntry["nombre"] ?: "Externo", newEntry["monto"] ?: "0")
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun checkGlobalAlert() {
        activityScope.launch(Dispatchers.IO) {
            try {
                val json = httpGet(alertUrl)
                if (json.contains("ACTIVE") && !isAlertActive) {
                    withContext(Dispatchers.Main) { startPanicAlarm() }
                } else if (json.contains("IDLE") && isAlertActive) {
                    withContext(Dispatchers.Main) { stopPanicAlarm() }
                }
            } catch (e: Exception) {}
        }
    }

    private fun triggerGlobalAlert() {
        val status = if (isAlertActive) "IDLE" else "ACTIVE"
        activityScope.launch(Dispatchers.IO) {
            httpPost(alertUrl, JSONObject().put("status", status).toString())
        }
    }

    private fun startPanicAlarm() {
        isAlertActive = true
        val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        mediaPlayer = MediaPlayer.create(this, notification)
        mediaPlayer?.isLooping = true
        mediaPlayer?.start()
        
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), 0))
        }
        
        Toast.makeText(this, "¡ALERTA GLOBAL ACTIVADA!", Toast.LENGTH_LONG).show()
    }

    private fun stopPanicAlarm() {
        isAlertActive = false
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
    }

    private fun sendToHive(type: String, content: String) {
        val body = JSONObject().apply {
            put("type", type)
            put("content", content)
            put("user", Build.MODEL)
            put("timestamp", System.currentTimeMillis())
        }
        activityScope.launch(Dispatchers.IO) { httpPost(dbUrl.split("?")[0], body.toString()) }
    }

    private fun httpGet(urlStr: String): String {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun httpPost(urlStr: String, data: String) {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream).use { it.write(data); it.flush() }
            connection.responseCode
        } finally { connection.disconnect() }
    }

    private fun speakPayment(nombre: String, monto: String) {
        if (isTtsReady) tts.speak("Pago de $nombre por $monto soles", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            isTtsReady = true
        }
    }

    override fun onDestroy() {
        activityJob.cancel()
        stopPanicAlarm()
        super.onDestroy()
    }
}

class HiveAdapter(private val list: List<Map<String, String>>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    override fun getItemViewType(position: Int): Int {
        return if (list[position]["type"] == "PAYMENT") 0 else 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            PaymentViewHolder(inflater.inflate(R.layout.item_payment, parent, false))
        } else {
            MessageViewHolder(inflater.inflate(R.layout.item_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val data = list[position]
        val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(data["timestamp"]?.toLong() ?: 0L))
        
        if (holder is PaymentViewHolder) {
            holder.tvNombre.text = data["nombre"] ?: "Externo"
            holder.tvMonto.text = "S/ ${data["monto"]}"
            holder.tvBanco.text = "${data["banco"]} • $time"
        } else if (holder is MessageViewHolder) {
            holder.tvUser.text = data["user"] ?: "Anónimo"
            holder.tvMessage.text = data["content"] ?: ""
            holder.tvTime.text = time
        }
    }

    override fun getItemCount() = list.size

    class PaymentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre: TextView = view.findViewById(R.id.tvNombre)
        val tvMonto: TextView = view.findViewById(R.id.tvMonto)
        val tvBanco: TextView = view.findViewById(R.id.tvBanco)
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvUser: TextView = view.findViewById(R.id.tvUser)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }
}
