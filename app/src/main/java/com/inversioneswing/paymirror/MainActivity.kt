package com.inversioneswing.paymirror

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PaymentAdapter
    private val paymentList = mutableListOf<Map<String, String>>()
    private var lastId = ""
    private val dbUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/pagos.json?orderBy=\"timestamp\"&limitToLast=20"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PaymentAdapter(paymentList)
        recyclerView.adapter = adapter

        val indicator = findViewById<View>(R.id.heartbeat_indicator)

        // Bucle de Sincronización Stark
        CoroutineScope(Dispatchers.IO).launch {
            while(true) {
                Handler(Looper.getMainLooper()).post { indicator.visibility = View.VISIBLE }
                fetchPayments()
                Handler(Looper.getMainLooper()).post { indicator.visibility = View.INVISIBLE }
                delay(5000)
            }
        }
    }

    private fun fetchPayments() {
        try {
            val connection = URL(dbUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            val json = try {
                BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            } finally {
                connection.disconnect()
            }

            if (json.isNotEmpty() && json != "null") {
                val root = JSONObject(json)
                val keys = root.keys()
                val allPayments = mutableListOf<Map<String, String>>()

                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = root.getJSONObject(key)
                    val map = mutableMapOf<String, String>()
                    val innerKeys = obj.keys()
                    while (innerKeys.hasNext()) {
                        val k = innerKeys.next()
                        map[k] = obj.optString(k, "")
                    }
                    allPayments.add(map)
                }

                val sorted = allPayments.sortedByDescending { it["timestamp"] }

                if (sorted.isNotEmpty() && sorted[0]["timestamp"] != lastId) {
                    lastId = sorted[0]["timestamp"] ?: ""
                    val nombre = sorted[0]["nombre"] ?: "Externo"
                    val monto = sorted[0]["monto"] ?: "0"
                    
                    Handler(Looper.getMainLooper()).post {
                        paymentList.clear()
                        paymentList.addAll(sorted)
                        adapter.notifyDataSetChanged()
                        speakPayment(nombre, monto)
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun speakPayment(nombre: String, monto: String) {
        if (isTtsReady) tts.speak("Nuevo pago de $nombre por $monto soles", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            isTtsReady = true
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}

class PaymentAdapter(private val list: List<Map<String, String>>) : RecyclerView.Adapter<PaymentAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre: TextView = view.findViewById(R.id.tvNombre)
        val tvMonto: TextView = view.findViewById(R.id.tvMonto)
        val tvBanco: TextView = view.findViewById(R.id.tvBanco)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_payment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = list[position]
        holder.tvNombre.text = p["nombre"] ?: "Desconocido"
        holder.tvMonto.text = "S/ ${p["monto"]}"
        holder.tvBanco.text = p["banco"] ?: "YAPE"
        
        val color = if (holder.tvBanco.text == "YAPE") 0xFF25D366.toInt() else 0xFF00F3FF.toInt()
        holder.tvNombre.setTextColor(color)
    }

    override fun getItemCount() = list.size
}
