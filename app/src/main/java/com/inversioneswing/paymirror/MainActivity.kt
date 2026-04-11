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
import com.google.firebase.database.*
import java.util.*

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private val db = FirebaseDatabase.getInstance().getReference("pagos_ventas")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        setContent {
            val paymentList = remember { mutableStateListOf<Map<String, String>>() }

            // Escucha Espejo en Tiempo Real
            LaunchedEffect(Unit) {
                db.limitToLast(50).addChildEventListener(object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        val rawData = snapshot.value as? Map<*, *>
                        val data = rawData?.map { it.key.toString() to it.value.toString() }?.toMap() ?: return
                        
                        paymentList.add(0, data) // Añadir al principio para ver los nuevos arriba
                        
                        // Solo hablar si es un pago reciente (evitar hablar historial al abrir)
                        val timestamp = data["timestamp"]?.toLong() ?: 0
                        if (System.currentTimeMillis() - timestamp < 10000) {
                            speakPayment(data["nombre"] ?: "Desconocido", data["monto"] ?: "0.00")
                        }
                    }
                    override fun onChildChanged(s: DataSnapshot, p: String?) {}
                    override fun onChildRemoved(s: DataSnapshot) {}
                    override fun onChildMoved(s: DataSnapshot, p: String?) {}
                    override fun onCancelled(e: DatabaseError) {}
                })
            }

            PayMirrorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0D0D)) {
                    ChatScreen(paymentList)
                }
            }
        }
    }

    private fun speakPayment(nombre: String, monto: String) {
        if (isTtsReady) {
            val text = "Pago recibido de $nombre por $monto soles"
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            isTtsReady = true
        }
    }
}

@Composable
fun ChatScreen(payments: List<Map<String, String>>) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "WING PAY-MIRROR CORE",
            color = Color(0xFF00F3FF),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(payments) { payment ->
                PaymentBubble(payment)
            }
        }
    }
}

@Composable
fun PaymentBubble(payment: Map<String, String>) {
    val banco = payment["banco"] ?: "YAPE"
    val colorBanco = if (banco == "YAPE") Color(0xFF25D366) else Color(0xFF00F3FF)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .border(1.dp, colorBanco.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(payment["nombre"] ?: "Desconocido", color = colorBanco, fontWeight = FontWeight.Bold)
            Text(banco, color = colorBanco.copy(alpha = 0.5f), fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("S/ ${payment["monto"]}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun PayMirrorTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
