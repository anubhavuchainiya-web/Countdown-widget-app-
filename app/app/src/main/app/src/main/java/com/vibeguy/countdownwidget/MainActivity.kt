package com.vibeguy.countdownwidget

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val base64Image = saveAndEncodeImage(uri)
            webView.evaluateJavascript(
                "window.onNativeImagePicked && window.onNativeImagePicked(${jsonQuote(base64Image ?: "")});",
                null
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = WebViewClient()
            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    private fun saveAndEncodeImage(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val original = BitmapFactory.decodeStream(input) ?: return null
                val maxSide = 900
                val ratio = minOf(
                    maxSide.toFloat() / original.width.coerceAtLeast(1),
                    maxSide.toFloat() / original.height.coerceAtLeast(1),
                    1f
                )
                val targetW = (original.width * ratio).toInt().coerceAtLeast(1)
                val targetH = (original.height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(original, targetW, targetH, true)

                val outFile = File(filesDir, "widget_bg.jpg")
                FileOutputStream(outFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, fos)
                }

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonQuote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }

    inner class AndroidBridge(private val context: Context) {

        @JavascriptInterface
        fun saveConfig(configJson: String) {
            val prefs = context.getSharedPreferences(CountdownWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(CountdownWidgetProvider.KEY_CONFIG_JSON, configJson).apply()
            CountdownWidgetProvider.updateAllWidgets(context)
        }

        @JavascriptInterface
        fun loadConfig(): String {
            val prefs = context.getSharedPreferences(CountdownWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(CountdownWidgetProvider.KEY_CONFIG_JSON, "") ?: ""
        }

        @JavascriptInterface
        fun pickWallpaper() {
            runOnUiThread {
                pickImageLauncher.launch("image/*")
            }
        }

        @JavascriptInterface
        fun clearWallpaper() {
            val file = File(context.filesDir, "widget_bg.jpg")
            if (file.exists()) {
                file.delete()
            }
            CountdownWidgetProvider.updateAllWidgets(context)
        }

        @JavascriptInterface
        fun finishWidgetSetup() {
            runOnUiThread {
                CountdownWidgetProvider.updateAllWidgets(context)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    setResult(Activity.RESULT_OK, resultValue)
                }
                finish()
            }
        }
    }
}
