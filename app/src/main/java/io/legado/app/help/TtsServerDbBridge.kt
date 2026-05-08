package io.legado.app.help

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log

object TtsServerDbBridge {

    private const val TAG = "TtsServerDbBridge"

    private const val TTS_PACKAGE = "com.github.jing332.tts_server_android.jtts"
    private const val BRIDGE_AUTHORITY = "com.github.jing332.tts_server_android.jtts.legado.bridge"
    private const val ACTION_START = "com.github.jing332.tts_server_android.jtts.action.LEGADO_BRIDGE_START"

    private val BRIDGE_URI: Uri = Uri.parse("content://$BRIDGE_AUTHORITY")

    fun ensureRunning(context: Context) {
        val app = context.applicationContext

        if (startByProvider(app)) {
            Log.i(TAG, "TTS Server DB started by provider")
            return
        }

        startByService(app)
    }

    private fun startByProvider(context: Context): Boolean {
        return try {
            val result: Bundle? = context.contentResolver.call(
                BRIDGE_URI,
                "start",
                null,
                Bundle()
            )
            result?.getBoolean("ok", false) == true
        } catch (e: Throwable) {
            Log.w(TAG, "startByProvider failed: ${e.message}")
            false
        }
    }

    private fun startByService(context: Context): Boolean {
        return try {
            val intent = Intent(ACTION_START).apply {
                setPackage(TTS_PACKAGE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.i(TAG, "TTS Server DB started by service")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "startByService failed", e)
            false
        }
    }
}
