package com.aichat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Foreground-сервис, который держит WatchHttpServer живым.
 * Запускается из MainActivity при старте приложения.
 *
 * Сервис слушает локальный порт 8765 для запросов от Zepp OS side-service.
 */
class WatchService : Service() {

    private val TAG = "WatchService"
    private var httpServer: WatchHttpServer? = null
    private var tts: TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        initTTS()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WatchService started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        httpServer?.stop()
        tts?.shutdown()
        super.onDestroy()
        Log.d(TAG, "WatchService stopped")
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                startHttpServer()
                Log.d(TAG, "TTS ready, HTTP server started")
            } else {
                Log.e(TAG, "TTS init failed: $status")
                // Запускаем сервер даже без TTS
                startHttpServer()
            }
        }
    }

    private fun startHttpServer() {
        try {
            httpServer = WatchHttpServer(applicationContext, tts!!)
            httpServer!!.start()
            Log.d(TAG, "HTTP server started on port 8765")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "watch_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "AI Chat Watch",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Сервис для связи с умными часами Amazfit"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return Notification.Builder(this, channelId)
            .setContentTitle("AI Chat")
            .setContentText("Ожидание запросов с часов...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1001
    }
}
