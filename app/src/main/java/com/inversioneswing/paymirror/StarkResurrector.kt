package com.inversioneswing.paymirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class StarkResurrector : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Ingeniería Inversa MacroDroid: Reiniciar el núcleo al detectar BOOT o cambio de RED
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val serviceIntent = Intent(context, StarkCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_10) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
