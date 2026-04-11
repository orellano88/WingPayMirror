package com.inversioneswing.paymirror

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import java.util.*

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private val client = OkHttpClient()
    private val dbUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/pagos.json?orderBy=\"timestamp\"&limitToLast=10"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        setContent {
            val paymentList = remember { mutableStateListOf<Map<String, String>>() }
            var lastId by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                while(true) {
                    try {
                        val request = Request.Builder().url(dbUrl).build()
                        val response = client.newCall(request).execute()
                        val json = response.body?.string() ?: ""
                        val type = object : TypeToken<Map<String, Map<String, String>>>() {}.type
                        val data: Map<String, Map<String, String>> = Gson().fromJson(json, type) ?: emptyMap()
                        val sortedPayments = data.values.sortedByDescending { it["timestamp"] }
                        
                        if (sortedPayments.isNotEmpty() && sortedPayments[0]["timestamp"] != lastId) {
                            val newPayment = sortedPayments[0]
                            paymentList.clear()
                            paymentList.addAll(sortedPayments)
                            lastId = newPayment["timestamp"] ?: ""
                            speakPayment(newPayment["nombre"] ?: "Externo", newPayment["monto"] ?: "0")
                        }
                    } catch (e: Exception) { }
                    delay(3000)
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00FF41), background = Color(0xFF0D0D0D))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0D0D)) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("WING PAY-MIRROR // HUD", color = Color(0xFF00F3FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(paymentList) { payment ->
                                val color = if (payment["banco"] == "YAPE") Color(0xFF25D366) else Color(0xFF00F3FF)
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)).border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(payment["nombre"] ?: "Desconocido", color = color, fontWeight = FontWeight.Bold)
                                        Text(payment["banco"] ?: "", color = color.copy(alpha = 0.5f), fontSize = 10.sp)
                                    }
                                    Text("S/ ${payment["monto"]}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }
        }
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
}
