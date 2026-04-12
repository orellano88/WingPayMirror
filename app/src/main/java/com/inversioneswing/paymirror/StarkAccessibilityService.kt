package com.inversioneswing.paymirror

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.widget.Toast

class StarkAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Este es el "Ojo Fantasma" que lee la pantalla
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: ""
            
            // Si el usuario abre Yape o BCP, JARVIS se pone en alerta máxima
            if (packageName.contains("yape") || packageName.contains("bcp")) {
                Log.d("STARK_SHIELD", "Usuario en pasarela de pago: $packageName")
            }
        }
    }

    override fun onInterrupt() {
        Log.e("STARK_SHIELD", "El servicio de accesibilidad ha sido interrumpido.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("STARK_SHIELD", "Inmortalidad Activada: Servicio de Accesibilidad Conectado.")
        Toast.makeText(this, "Protocolo Inmortal v39.0: ONLINE", Toast.LENGTH_SHORT).show()
    }
}
