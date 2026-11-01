package com.example

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                NanoChatScreen()
            }
        }
    }
}

class AndroidAppInterface(
    private val context: Context,
    private val getWebView: () -> WebView?,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @JavascriptInterface
    fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun saveBlobBase64(base64Data: String, filename: String, mimeType: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val success = saveFileToDownloads(context, filename, mimeType, bytes)
            mainHandler.post {
                if (success) {
                    Toast.makeText(context, "已保存至下载目录: $filename", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "保存文件失败", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("NanoChat", "saveBlobBase64 error", e)
            mainHandler.post {
                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun httpRequest(reqId: String, method: String, url: String, headersJson: String, bodyBase64: String) {
        scope.launch {
            try {
                val reqBuilder = Request.Builder().url(url)
                reqBuilder.header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

                var hasContentType = false
                if (headersJson.isNotBlank() && headersJson != "{}") {
                    try {
                        val obj = JSONObject(headersJson)
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = obj.optString(k, "")
                            if (k.equals("Content-Type", ignoreCase = true)) {
                                hasContentType = true
                            }
                            reqBuilder.header(k, v)
                        }
                    } catch (e: Exception) {
                        Log.e("NanoChat", "Parse headers error", e)
                    }
                }

                val bodyBytes = if (bodyBase64.isNotBlank()) {
                    Base64.decode(bodyBase64, Base64.DEFAULT)
                } else null

                val isStream = (url.contains("/chat/completions") || url.contains("/api/llm")) &&
                        bodyBytes != null && String(bodyBytes, Charsets.UTF_8).contains("\"stream\":true")

                val requestBody = when {
                    method.equals("POST", ignoreCase = true) ||
                    method.equals("PUT", ignoreCase = true) ||
                    method.equals("PATCH", ignoreCase = true) -> {
                        val contentType = if (hasContentType) null else "application/json; charset=utf-8".toMediaTypeOrNull()
                        (bodyBytes ?: ByteArray(0)).toRequestBody(contentType)
                    }
                    else -> null
                }

                reqBuilder.method(method.uppercase(), requestBody)
                val response = client.newCall(reqBuilder.build()).execute()

                if (isStream && response.isSuccessful) {
                    val source = response.body?.source()
                    if (source != null) {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val chunkEscaped = JSONObject.quote(line + "\n")
                            mainHandler.post {
                                getWebView()?.evaluateJavascript(
                                    "window.__nativeCallbacks?.['$reqId']?.onStream?.($chunkEscaped);",
                                    null
                                )
                            }
                        }
                    }
                    mainHandler.post {
                        getWebView()?.evaluateJavascript(
                            "window.__nativeCallbacks?.['$reqId']?.onDone?.();",
                            null
                        )
                    }
                } else {
                    val code = response.code
                    val message = JSONObject.quote(response.message)
                    val respHeadersObj = JSONObject()
                    for (name in response.headers.names()) {
                        respHeadersObj.put(name.lowercase(), response.header(name) ?: "")
                    }
                    val respHeadersStr = JSONObject.quote(respHeadersObj.toString())

                    val respBytes = response.body?.bytes() ?: ByteArray(0)
                    val respBase64 = JSONObject.quote(Base64.encodeToString(respBytes, Base64.NO_WRAP))

                    mainHandler.post {
                        getWebView()?.evaluateJavascript(
                            "window.__nativeCallbacks?.['$reqId']?.onResponse?.($code, $message, $respHeadersStr, $respBase64);",
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("NanoChat", "Native HTTP error: ${e.message}", e)
                val errQuote = JSONObject.quote(e.message ?: "网络请求失败")
                mainHandler.post {
                    getWebView()?.evaluateJavascript(
                        "window.__nativeCallbacks?.['$reqId']?.onError?.($errQuote);",
                        null
                    )
                }
            }
        }
    }
}

fun saveFileToDownloads(context: Context, filename: String, mimeType: String, bytes: ByteArray): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, if (mimeType.isNotBlank()) mimeType else "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(bytes)
                }
                true
            } else {
                false
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { fos ->
                fos.write(bytes)
            }
            true
        }
    } catch (e: Exception) {
        Log.e("NanoChat", "Error saving file", e)
        false
    }
}

@Composable
fun NanoChatScreen() {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = when {
                data?.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data?.data != null -> {
                    arrayOf(data.data!!)
                }
                else -> null
            }
            filePathCallback?.onReceiveValue(uris)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    BackHandler(enabled = webViewInstance?.canGoBack() == true) {
        webViewInstance?.goBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF3EEE6))
            .systemBarsPadding()
            .testTag("nanochat_main_container")
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    setBackgroundColor(android.graphics.Color.TRANSPARENT)

                    val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                        .build()

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }

                    addJavascriptInterface(AndroidAppInterface(ctx, { webViewInstance }), "AndroidApp")

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url ?: return null
                            return assetLoader.shouldInterceptRequest(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d("NanoChat", "Loaded: $url")
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?
                        ): Boolean {
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = callback

                            val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            try {
                                filePickerLauncher.launch(intent)
                            } catch (e: Exception) {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = null
                                return false
                            }
                            return true
                        }
                    }

                    loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
                    webViewInstance = this
                }
            },
            update = {
                webViewInstance = it
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }
}
