package site.whpp.schedule

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()

    private val updateJsonUrl =
        "https://raw.githubusercontent.com/hustlerwhpp/ota-schedule/main/update.json"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applySystemBarsTheme()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("file://")) return false
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }
        }

        webView.loadUrl("file:///android_asset/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        checkOtaUpdate()
    }

    private fun applySystemBarsTheme() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = Color.parseColor(if (isNight) "#0b1120" else "#f8fafc")
        window.navigationBarColor = Color.parseColor(if (isNight) "#0b1120" else "#f8fafc")
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight
    }

    private fun checkOtaUpdate() {
        executor.execute {
            try {
                val conn = (URL(updateJsonUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@execute

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val remoteCode = json.optInt("versionCode", 0)
                val remoteName = json.optString("versionName", "")
                val apkUrl = json.optString("apkUrl", "")
                val notes = json.optString("releaseNotes", "")

                if (remoteCode > BuildConfig.VERSION_CODE && apkUrl.isNotBlank()) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            showMandatoryUpdateDialog(remoteName, notes, apkUrl)
                        }
                    }
                }
            } catch (_: Exception) {
                // Офлайн или ошибка сети — продолжаем работу с кэшем расписания
            }
        }
    }

    private fun showMandatoryUpdateDialog(
        versionName: String,
        releaseNotes: String,
        apkUrl: String
    ) {
        val message = buildString {
            append("Доступна обязательная версия $versionName.\n")
            if (releaseNotes.isNotBlank()) {
                append("\n").append(releaseNotes)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Обновление приложения")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Обновить", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                btn.isEnabled = false
                btn.text = "Загрузка..."
                downloadAndInstallApk(apkUrl, dialog)
            }
        }
        dialog.show()
    }

    private fun downloadAndInstallApk(apkUrl: String, dialog: AlertDialog) {
        executor.execute {
            try {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                val outFile = File(dir, "Schedule-update.apk")
                if (outFile.exists()) outFile.delete()

                var currentUrl = apkUrl
                var conn: HttpURLConnection
                var redirects = 0
                while (true) {
                    conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = false
                    }
                    val code = conn.responseCode
                    if (code in 300..399 && redirects < 5) {
                        val loc = conn.getHeaderField("Location") ?: break
                        currentUrl = loc
                        redirects++
                        conn.disconnect()
                    } else {
                        break
                    }
                }

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }

                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        installApk(outFile, dialog)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this,
                            "Не удалось скачать обновление: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        btn.isEnabled = true
                        btn.text = "Повторить"
                    }
                }
            }
        }
    }

    private fun installApk(apkFile: File, dialog: AlertDialog) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                this,
                "Разрешите установку из этого источника и нажмите «Установить»",
                Toast.LENGTH_LONG
            ).show()
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(permIntent)
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = true
            btn.text = "Установить"
            btn.setOnClickListener { installApk(apkFile, dialog) }
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
