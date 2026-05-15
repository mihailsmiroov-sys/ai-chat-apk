package com.aichat.app

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.*
import android.widget.LinearLayout
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST = 1001
    private val CAMERA_REQUEST = 1002
    private val PERMISSIONS_REQUEST = 1003
    private val httpClient = OkHttpClient()
    private val prefs by lazy { getSharedPreferences("ai_chat_secure", MODE_PRIVATE) }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("ru", "RU")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { webView.evaluateJavascript("window._ttsStarted && window._ttsStarted()", null) }
                }
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { webView.evaluateJavascript("window._ttsDone && window._ttsDone()", null) }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread { webView.evaluateJavascript("window._ttsDone && window._ttsDone()", null) }
                }
            })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        val layout = LinearLayout(this)
        layout.setBackgroundColor(Color.parseColor("#1e1f22"))
        webView = WebView(this)
        layout.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(layout)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun speak(text: String) { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts") }
            @android.webkit.JavascriptInterface
            fun stop() { tts.stop() }
            @android.webkit.JavascriptInterface
            fun isSpeaking(): Boolean = tts.isSpeaking
        }, "AndroidTTS")

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun share(text: String) {
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(intent, "Поделиться"))
                }
            }
        }, "AndroidShare")

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun openCamera() {
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
                    } else {
                        launchCamera()
                    }
                }
            }

            @android.webkit.JavascriptInterface
            fun requestInitialPermissions() {
                runOnUiThread { requestNeededPermissions() }
            }

            @android.webkit.JavascriptInterface
            fun openAppSettings() {
                runOnUiThread {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        }, "AndroidApp")

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun getApiKey(): String = prefs.getString("openrouter_api_key", "") ?: ""

            @android.webkit.JavascriptInterface
            fun setApiKey(apiKey: String) {
                prefs.edit().putString("openrouter_api_key", apiKey.trim()).apply()
            }

            @android.webkit.JavascriptInterface
            fun hasApiKey(): Boolean = !getApiKey().isBlank()
        }, "AndroidKeyStore")

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun sendRequest(url: String, body: String, callbackId: String) {
                try {
                if (url != OPENROUTER_CHAT_URL) {
                    sendProxyResult(callbackId, null, "Запросы разрешены только к OpenRouter")
                    return
                }

                val apiKey = (prefs.getString("openrouter_api_key", "") ?: "")
                    .trim()
                    .replace(Regex("[\\r\\n\\t]"), "")
                if (apiKey.isBlank()) {
                    sendProxyResult(callbackId, null, "Укажите API ключ OpenRouter")
                    return
                }

                val requestBody = body.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(OPENROUTER_CHAT_URL)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://mihailsmiroov-sys.github.io/ai-chat-apk/")
                    .addHeader("X-Title", "AI Chat App")
                    .build()

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        sendProxyResult(callbackId, null, e.message ?: "Network error")
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: ""
                        if (!response.isSuccessful) {
                            sendProxyResult(callbackId, null, responseBody.ifBlank { "HTTP ${response.code}" })
                        } else {
                            sendProxyResult(callbackId, responseBody, null)
                        }
                    }
                })
                } catch (e: Exception) {
                    sendProxyResult(callbackId, null, e.message ?: e.javaClass.simpleName)
                }
            }
        }, "AndroidProxy")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(Intent.createChooser(intent, "Выбрать фото"), FILE_CHOOSER_REQUEST)
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {}
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQUEST) {
            val bitmap = data?.extras?.get("data") as? Bitmap
            if (resultCode == Activity.RESULT_OK && bitmap != null) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                val dataUrl = "data:image/jpeg;base64,$base64"
                runOnUiThread {
                    webView.evaluateJavascript("window._androidCameraResult(${JSONObject.quote(dataUrl)})", null)
                }
            } else {
                runOnUiThread { webView.evaluateJavascript("window._androidCameraCancelled && window._androidCameraCancelled()", null) }
            }
            return
        }

        if (requestCode == FILE_CHOOSER_REQUEST) {
            val uris = if (resultCode == Activity.RESULT_OK) {
                when {
                    data?.clipData != null -> Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else null
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchCamera()
            else webView.evaluateJavascript("window._androidPermissionResult && window._androidPermissionResult('camera', false)", null)
            return
        }
        if (requestCode == PERMISSIONS_REQUEST) {
            webView.evaluateJavascript("window._androidPermissionResult && window._androidPermissionResult('initial', true)", null)
        }
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, CAMERA_REQUEST)
        } else {
            webView.evaluateJavascript("window._androidCameraError && window._androidCameraError('Камера недоступна')", null)
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), PERMISSIONS_REQUEST)
        else webView.evaluateJavascript("window._androidPermissionResult && window._androidPermissionResult('initial', true)", null)
    }

    private fun sendProxyResult(callbackId: String, response: String?, error: String?) {
        val payload = JSONObject().apply {
            put("id", callbackId)
            put("response", response)
            put("error", error)
        }
        runOnUiThread {
            webView.evaluateJavascript("window._proxyCallback(${payload})", null)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"
    }
}
