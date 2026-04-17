package com.aichat.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Локальный HTTP-сервер на порту 8765.
 * Zepp OS side-service отправляет сюда запросы.
 *
 * Эндпоинты:
 *   GET  /ping      → { ok: true }
 *   POST /stt       { audioBase64 | usePhone }  → { text: "..." }
 *   POST /tts       { text: "..." }             → { ok: true }  (синхронный — ждёт конца речи)
 *   POST /stop-tts  {}                          → { ok: true }
 */
class WatchHttpServer(
    private val context: Context,
    private val tts: TextToSpeech
) : NanoHTTPD(8765) {

    private val TAG = "WatchHttpServer"
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/ping"                                   -> ok(JSONObject().put("ok", true))
                session.uri == "/stt"      && session.method == Method.POST -> handleSTT(session)
                session.uri == "/tts"      && session.method == Method.POST -> handleTTS(session)
                session.uri == "/stop-tts" && session.method == Method.POST -> handleStopTTS()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_JSON, """{"error":"Not found"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error: ${e.message}")
            error(e.message ?: "Server error")
        }
    }

    // ─── STT ─────────────────────────────────────────────────────────────

    private fun handleSTT(session: IHTTPSession): Response {
        val json = JSONObject(readBody(session))
        return if (json.optBoolean("usePhone", false)) {
            val text = runPhoneSTT()
            if (text != null) ok(JSONObject().put("text", text)) else error("STT failed")
        } else {
            val audioBase64 = json.optString("audioBase64", "")
            if (audioBase64.isEmpty()) return error("No audio data")
            val text = runAudioSTT(audioBase64)
            if (text != null) ok(JSONObject().put("text", text)) else error("Audio STT failed")
        }
    }

    private fun runPhoneSTT(): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        val latch = CountDownLatch(1)
        var result: String? = null

        mainHandler.post {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    result = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    recognizer.destroy()
                    latch.countDown()
                }
                override fun onError(code: Int) {
                    Log.e(TAG, "STT error: $code")
                    recognizer.destroy()
                    latch.countDown()
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }
            recognizer.startListening(intent)
        }

        latch.await(20, TimeUnit.SECONDS)
        return result
    }

    private fun runAudioSTT(base64Audio: String): String? {
        return try {
            val pcmBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val wavFile = File(context.cacheDir, "watch_audio.wav")
            writeWavFile(wavFile, pcmBytes, 16000, 1, 16)
            // TODO: отправить WAV в Whisper или Google Cloud STT
            // Fallback — используем микрофон телефона
            runPhoneSTT()
        } catch (e: Exception) {
            Log.e(TAG, "Audio STT error: ${e.message}")
            null
        }
    }

    // ─── TTS (синхронный — ждёт конца речи) ──────────────────────────────

    private fun handleTTS(session: IHTTPSession): Response {
        val json = JSONObject(readBody(session))
        val text = json.optString("text", "")
        if (text.isEmpty()) return error("No text")

        val latch = CountDownLatch(1)

        mainHandler.post {
            tts.language = Locale("ru", "RU")

            // Слушатель завершения произношения
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { latch.countDown() }
                override fun onError(utteranceId: String?) { latch.countDown() }
            })

            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "WATCH_TTS")
        }

        // Ждём максимум 60 секунд (длинный ответ)
        val finished = latch.await(60, TimeUnit.SECONDS)
        Log.d(TAG, "TTS done (finished=$finished)")

        return ok(JSONObject().put("ok", true))
    }

    private fun handleStopTTS(): Response {
        mainHandler.post { tts.stop() }
        return ok(JSONObject().put("ok", true))
    }

    // ─── Вспомогательные ─────────────────────────────────────────────────

    private fun writeWavFile(file: File, pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int) {
        val data = pcm.size
        FileOutputStream(file).use { out ->
            fun i32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.write("RIFF".toByteArray()); i32(data + 36)
            out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray()); i32(16)
            i16(1); i16(channels); i32(sampleRate)
            i32(sampleRate * channels * bits / 8); i16(channels * bits / 8); i16(bits)
            out.write("data".toByteArray()); i32(data); out.write(pcm)
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private fun ok(json: JSONObject) =
        newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())

    private fun error(msg: String) =
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
            JSONObject().put("error", msg).toString())

    companion object { const val MIME_JSON = "application/json" }
}
