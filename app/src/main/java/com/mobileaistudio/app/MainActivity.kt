package com.mobileaistudio.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("wake", false) == true) showOverLock(true)
        web = WebView(this)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        web.setBackgroundColor(if (night) 0xFF262624.toInt() else 0xFFFAF9F5.toInt())
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowContentAccess = true

        bridge = Bridge(this, web)
        web.addJavascriptInterface(bridge, "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // The app page stays local. Web links open in the phone's browser.
                if (request.url.scheme == "file") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        web.loadUrl("file:///android_asset/index.html")
        bridge.onShare(intent)
        bridge.wakeResume()
    }

    fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
        }
    }

    /** Asks Android to let this app run in the background without being stopped (needed for the wake word). */
    fun askBattery() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { openAppInfo() }
        }
    }

    /** Opens this app's page in Android settings (where "Allow restricted settings" lives, in the top-right menu). */
    fun openAppInfo() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
        }
    }

    /** The phone's own "speak now" window. Returns false if there is none. */
    fun speechDialog(): Boolean {
        return try {
            val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            startActivityForResult(i, REQ_VOICE)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hasMic(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun askMic() {
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 8)
    }

    fun hasLocation(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun askLocation() {
        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 10)
    }

    fun hasFilesAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** Opens the Android screen where the person allows access to all files (or asks, on older phones). */
    fun requestFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                }
            }
        } else {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 7
            )
        }
    }

    /** Opened by the wake word on a locked or sleeping phone: light the screen and show over the lock screen, for this visit only. */
    private fun showOverLock(on: Boolean) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(on)
                setTurnScreenOn(on)
            } else {
                val f = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                if (on) window.addFlags(f) else window.clearFlags(f)
            }
        } catch (e: Throwable) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("wake", false)) showOverLock(true)
        setIntent(intent)
        if (::bridge.isInitialized) bridge.onShare(intent)
    }

    override fun onPause() {
        WakeCenter.visible = false
        showOverLock(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        WakeCenter.visible = true
        if (intent?.getBooleanExtra("wake", false) == true) {
            intent.removeExtra("wake")
            if (::bridge.isInitialized) bridge.onWake()
        }
        if (::web.isInitialized) web.evaluateJavascript("window.__onResume && window.__onResume()", null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) bridge.onMicResult(grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
        if (requestCode == 7 || requestCode == 10) web.evaluateJavascript("window.__onResume && window.__onResume()", null)
    }

    private var cameraFile: java.io.File? = null

    fun pickDocs() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(i, REQ_DOC)
        } catch (e: Exception) {
        }
    }

    /** mode is "camera" (take a photo with the phone's camera app) or "gallery" (pick a picture). */
    fun pickImage(mode: String) {
        try {
            if (mode == "camera") {
                val dir = java.io.File(cacheDir, "camera")
                dir.mkdirs()
                val f = java.io.File(dir, "photo_" + System.currentTimeMillis() + ".jpg")
                cameraFile = f
                val uri = Uri.parse("content://com.mobileaistudio.app.photos/" + f.name)
                val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = android.content.ClipData.newRawUri("", uri)
                startActivityForResult(i, REQ_CAMERA)
            } else {
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "image/*"
                startActivityForResult(Intent.createChooser(i, "Choose a picture"), REQ_IMAGE)
            }
        } catch (e: Exception) {
            bridge.onImageFailed(if (mode == "camera") "No camera app was found on this phone." else "Could not open the picture picker.")
        }
    }

    fun pickModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            startActivityForResult(i, REQ_MODEL)
        } catch (e: Exception) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                val result = if (resultCode == RESULT_OK) {
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                } else {
                    null
                }
                fileCallback?.onReceiveValue(result)
                fileCallback = null
            }
            REQ_MODEL -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) bridge.onModelPicked(uri)
            }
            REQ_DOC -> {
                val list = ArrayList<Uri>()
                if (resultCode == RESULT_OK && data != null) {
                    val cd = data.clipData
                    if (cd != null) for (i in 0 until cd.itemCount) list.add(cd.getItemAt(i).uri)
                    else data.data?.let { list.add(it) }
                }
                if (list.isNotEmpty()) bridge.onDocsPicked(list)
            }
            REQ_VOICE -> {
                val t = if (resultCode == RESULT_OK) data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
                bridge.onVoiceDialog(t)
            }
            REQ_IMAGE -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) bridge.onImagePicked(uri, false) else bridge.onImageCancelled()
            }
            REQ_CAMERA -> {
                val f = cameraFile
                cameraFile = null
                if (resultCode == RESULT_OK && f != null && f.exists() && f.length() > 0) {
                    bridge.onImagePicked(Uri.fromFile(f), true)
                } else {
                    f?.delete()
                    bridge.onImageCancelled()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Let the page close its own panels first.
        web.evaluateJavascript("(window.__back ? window.__back() : false)") { result ->
            if (result != "true") finish()
        }
    }

    override fun onDestroy() {
        bridge.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_FILE = 1
        private const val REQ_MODEL = 2
        private const val REQ_IMAGE = 3
        private const val REQ_DOC = 5
        private const val REQ_VOICE = 9
        private const val REQ_CAMERA = 4
    }
}
