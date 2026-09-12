package com.dreamsparkgroups.qdate

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.provider.OpenableColumns
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var adView: AdView
    private lateinit var splashOverlay: View
    private lateinit var prefs: SharedPreferences

    private val appHost = Uri.parse(BuildConfig.APP_URL).host.orEmpty()

    // Hosts that must complete inside the WebView so the app session cookie persists.
    // Google blocks sign-in in embedded WebViews unless the UA is clean (no "wv" token),
    // which is enforced via BuildConfig.APP_USER_AGENT. Everything else opens in the
    // system browser.
    private val inAppHosts = setOf(
        appHost,
        "accounts.google.com",
        "accounts.youtube.com",
        "oauth2.googleapis.com",
        "openidconnect.googleapis.com",
        "www.googleapis.com",
        "google.com",
        "www.google.com",
        "gstatic.com",
        "ssl.gstatic.com"
    )

    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var cameraVideoUri: Uri? = null

    // ---- Ads state ----
    private var interstitialAd: InterstitialAd? = null
    private var profileBrowseCount = 0
    private var lastInterstitialAt = 0L
    private var navHeightDp = 0
    private var navVisible = false
    private var bannerAdLoaded = false
    private var bannerHeightDp = 0

    private val bridgeJs: String by lazy {
        // Never let a missing/renamed asset file crash the app at page load.
        try {
            assets.open("qdate_bridge.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denied is fine — pushes are just silent */ }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = uploadCallback
            uploadCallback = null
            if (callback == null) return@registerForActivityResult

            val picked = mutableListOf<Uri>()
            val data = result.data
            // Camera apps commonly return no `data` Uri when EXTRA_OUTPUT is
            // used. Only add a camera output when it contains actual bytes.
            // The temporary files are created before the chooser opens, so a
            // plain "exists" check would incorrectly attach empty image/video
            // files to every gallery selection.
            val cameraOutput = if (result.resultCode == RESULT_OK) {
                cameraImageUri?.takeIf { uriHasContent(it) }
                    ?: cameraVideoUri?.takeIf { uriHasContent(it) }
            } else {
                null
            }
            if (cameraOutput != null) {
                // Prefer the full-resolution EXTRA_OUTPUT over a camera
                // thumbnail that some camera apps also return in `data`.
                picked.add(cameraOutput)
            } else if (result.resultCode == RESULT_OK && data != null) {
                val clipData = data.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { picked.add(it) }
                    }
                } else {
                    data.data?.let { picked.add(it) }
                }
            }

            cameraImageUri = null
            cameraVideoUri = null
            callback.onReceiveValue(
                picked.distinct().takeIf { it.isNotEmpty() }?.toTypedArray()
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        adView = findViewById(R.id.adView)
        splashOverlay = findViewById(R.id.splashOverlay)
        prefs = getSharedPreferences("qdate", MODE_PRIVATE)

        // Android 15+ enforces edge-to-edge for targetSdk 35: without this the
        // WebView (chat composer, bottom nav, banner) draws underneath the
        // status and navigation bars. On older versions the insets are 0 and
        // this is a no-op.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        configureWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        requestNotificationPermissionIfNeeded()
        initAds()
        initFcm()
        // A notification tap or a Google OAuth handoff may provide a deep-link.
        // Load it as the initial page instead of loading APP_URL afterwards and
        // overwriting it.
        webView.loadUrl(
            resumeUrlFromIntent(intent) ?: pushUrlFromIntent(intent) ?: BuildConfig.APP_URL
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleResumeIntent(intent)
        handlePushIntent(intent)
    }

    /** A push notification tap arrives with the target page in the `push_url` extra. */
    private fun pushUrlFromIntent(intent: Intent?): String? {
        val url = intent?.getStringExtra("push_url") ?: return null
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return url.takeIf {
            parsed.scheme.equals(Uri.parse(BuildConfig.APP_URL).scheme, true) &&
                parsed.host.equals(appHost, true) &&
                it.startsWith(BuildConfig.APP_URL)
        }
    }

    private fun handlePushIntent(intent: Intent?) {
        pushUrlFromIntent(intent)?.let { webView.loadUrl(it) }
    }

    /* ─────────────── Google OAuth → app resume ─────────────── */

    /** The "Continue with Google" flow runs in the system browser (so it can reuse
     *  the browser's existing Google account). When the browser finishes, the
     *  callback page sends us a qdate://resume?t=<signed token> deep link. That
     *  token re-creates the login session in the WebView's own cookie jar (which
     *  is separate from the browser's) at /auth/app-resume. */
    private fun resumeUrlFromIntent(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "qdate" || uri.host != "resume") return null
        val t = uri.getQueryParameter("t") ?: return null
        if (t.isEmpty()) return null
        return BuildConfig.APP_URL.trimEnd('/') + "/auth/app-resume?t=" + Uri.encode(t)
    }

    private fun handleResumeIntent(intent: Intent?) {
        resumeUrlFromIntent(intent)?.let { webView.loadUrl(it) }
    }

    /* ─────────────────────────── Ads ─────────────────────────── */

    private fun initAds() {
        try {
            MobileAds.initialize(this) { }
            setupBanner()
            loadInterstitial()
        } catch (e: Exception) {
            // Ads are optional — a broken SDK state must never crash the app.
            adView.visibility = View.GONE
        }
    }

    // Ad unit IDs come from res/values/admob.xml: Google test units in debug
    // builds, the real units in release. (ADS_TEST_MODE in build.gradle is no
    // longer used for switching.)
    private fun interstitialUnitId(): String = getString(R.string.interstitial_ad_unit_id)

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupBanner() {
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        adView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, widthDp))
        // The unit ID comes from XML (@string/banner_ad_unit_id — test ID in
        // debug builds, real ID in release). Never set it from code too: the
        // SDK allows it to be set only once and throws otherwise.
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                bannerAdLoaded = true
                applyBannerLayout()
            }
        }
        adView.loadAd(AdRequest.Builder().build())
    }

    /** Places the banner directly above the web app's bottom nav (banner height
     *  comes from the ad view itself; bottom margin from the JS-measured nav). */
    private fun applyBannerLayout() {
        val lp = adView.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        lp.bottomMargin = (navHeightDp * resources.displayMetrics.density).toInt()
        adView.layoutParams = lp
        // Only show once an ad has actually loaded, so no blank strip appears
        // while the first ad is still fetching.
        adView.visibility = if (navVisible && bannerAdLoaded) View.VISIBLE else View.GONE

        // Tell the page how tall the banner is so its spacer matches exactly.
        adView.post {
            val hDp = (adView.height / resources.displayMetrics.density).toInt()
            if (hDp > 0) {
                bannerHeightDp = hDp
                reportBannerHeightToPage()
            }
        }
    }

    /** Pushes the current banner height into the page so its spacer matches.
     *  Called after an ad loads and on every page navigation. */
    private fun reportBannerHeightToPage() {
        if (bannerHeightDp > 0 && isAppPage()) {
            webView.evaluateJavascript(
                "window.__qdateSetBannerHeight && window.__qdateSetBannerHeight($bannerHeightDp);",
                null
            )
        }
    }

    private fun loadInterstitial() {
        InterstitialAd.load(this, interstitialUnitId(), AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                }
            })
    }

    /** Called by the JS bridge for every profile browse (swipe + /user/{id} view). */
    private fun onProfileBrowsedInternal() {
        profileBrowseCount++
        val now = SystemClock.elapsedRealtime()
        if (profileBrowseCount < INTERSTITIAL_EVERY_N_BROWSES) return
        if (now - lastInterstitialAt < INTERSTITIAL_COOLDOWN_MS) return

        val ad = interstitialAd
        if (ad != null) {
            profileBrowseCount = 0
            lastInterstitialAt = now
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadInterstitial()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    loadInterstitial()
                }
            }
            ad.show(this)
        } else {
            // Threshold reached but no ad ready — refill and try on the next browse.
            loadInterstitial()
        }
    }

    /* ─────────────────────────── FCM ─────────────────────────── */

    private fun initFcm() {
        try {
            // Without google-services.json there are no Firebase apps — push simply
            // stays off until the file is dropped into app/.
            if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) return
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    prefs.edit().putString("fcm_token", token).apply()
                    // The token usually arrives AFTER the first page finished
                    // loading — hand it to the page right away so it registers
                    // with the backend immediately instead of on the next
                    // navigation.
                    runOnUiThread {
                        if (isAppPage()) {
                            webView.evaluateJavascript(
                                "window.__qdateSetFcmToken && window.__qdateSetFcmToken(${JSONObject.quote(token)});",
                                null
                            )
                        }
                    }
                }
                .addOnFailureListener { /* token fetch failed — retry on next launch */ }
        } catch (e: Exception) {
            // Firebase not configured — ignore.
        }
    }

    private fun fcmToken(): String? = prefs.getString("fcm_token", null)

    /* ───────────────────── JS bridge + injection ───────────────────── */

    /** Hides the full-screen splash overlay once the first real page is ready
     *  (or has definitively failed) — the WebView underneath takes over. */
    private fun dismissSplashOverlay() {
        if (splashOverlay.visibility == View.VISIBLE) {
            splashOverlay.visibility = View.GONE
        }
    }

    private fun isAppPage(): Boolean = try {
        webView.url?.let { Uri.parse(it).host.orEmpty().equals(appHost, true) } == true
    } catch (e: Exception) {
        false
    }

    private fun injectBridge() {
        if (!isAppPage()) return
        val tokenJs = fcmToken()?.let { "window.__qdateFcmToken = ${JSONObject.quote(it)};" } ?: ""
        webView.evaluateJavascript(tokenJs + bridgeJs, null)
        // The page context was just recreated — re-apply the banner height so
        // the spacer appears immediately instead of waiting for the next ad.
        reportBannerHeightToPage()
    }

    /** Exposed to JS. Every method re-checks the current page host: the interface
     *  object is visible to every loaded page (including Google OAuth screens),
     *  so only app-host pages are honoured. */
    private inner class NativeBridge {
        @JavascriptInterface
        fun setNavHeight(pxDp: Int) {
            runOnUiThread {
                if (pxDp > 0 && pxDp < 400 && isAppPage()) {
                    navHeightDp = pxDp
                    applyBannerLayout()
                }
            }
        }

        @JavascriptInterface
        fun setNavVisible(visible: Boolean) {
            runOnUiThread {
                if (isAppPage()) {
                    navVisible = visible
                    applyBannerLayout()
                }
            }
        }

        @JavascriptInterface
        fun onProfileBrowsed() {
            runOnUiThread {
                if (isAppPage()) onProfileBrowsedInternal()
            }
        }
    }

    /* ─────────────────────────── WebView ─────────────────────────── */

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = BuildConfig.APP_USER_AGENT
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(NativeBridge(), "QDateNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme.orEmpty().lowercase(Locale.ROOT)
                val host = url.host.orEmpty().lowercase(Locale.ROOT)

                return when {
                    // "Continue with Google" must complete in the system browser so
                    // Google offers the browser's already-signed-in account instead of
                    // forcing a fresh login inside the cookie-less WebView. app=1 tells
                    // the web app this flow belongs to the app, so the callback hands
                    // the session back via qdate://resume.
                    googleStartFromUrl(url) -> true
                    scheme == "mailto" || scheme == "tel" || scheme == "sms" || scheme == "intent" -> {
                        openExternal(url)
                        true
                    }
                    host in inAppHosts -> false // stay in the WebView
                    else -> {
                        openExternal(url)
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.progress = 10
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.progress = 100
                progressBar.visibility = View.GONE
                dismissSplashOverlay()
                injectBridge()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    // Don't leave the splash up behind a failed load.
                    dismissSplashOverlay()
                    Toast.makeText(
                        this@MainActivity,
                        "Network error. Check your connection.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                handler.cancel()
                Toast.makeText(this@MainActivity, "Insecure connection blocked.", Toast.LENGTH_LONG).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                // target=_blank / window.open. Returning the ATTACHED main
                // WebView in the transport is unsupported and crashes the
                // renderer (tapping a chat image used to kill the app), so
                // hand Chromium a throwaway WebView whose only job is to tell
                // us the URL — we then load it in the main WebView ourselves.
                val popup = WebView(this@MainActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url ?: return true
                        val asString = url.toString()
                        popup.destroy()
                        if (url.host.orEmpty().equals(appHost, true)) {
                            webView.loadUrl(asString)
                        } else {
                            openExternal(url)
                        }
                        return true
                    }
                }
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                uploadCallback?.onReceiveValue(null)
                uploadCallback = filePathCallback

                val acceptedMimeTypes: List<String> = fileChooserParams?.acceptTypes
                    ?.map { it.trim().lowercase(Locale.ROOT) }
                    ?.filter { it.isNotBlank() }
                    ?.ifEmpty { listOf("*/*") }
                    ?: listOf("*/*")
                val chooserMime = when {
                    acceptedMimeTypes.any { it.startsWith("image/") } &&
                        !acceptedMimeTypes.any { it.startsWith("video/") } -> "image/*"
                    acceptedMimeTypes.any { it.startsWith("video/") } &&
                        !acceptedMimeTypes.any { it.startsWith("image/") } -> "video/*"
                    else -> "*/*"
                }

                val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = chooserMime
                    if (acceptedMimeTypes.size > 1 && chooserMime == "*/*") {
                        putExtra(Intent.EXTRA_MIME_TYPES, acceptedMimeTypes.toTypedArray())
                    }
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val initialIntents = mutableListOf<Intent>()

                if (chooserMime == "image/*" || chooserMime == "*/*") {
                    createCameraFileUri(".jpg", "IMG_")?.let { uri ->
                        cameraImageUri = uri
                        initialIntents.add(cameraIntent(MediaStore.ACTION_IMAGE_CAPTURE, uri))
                    }
                }
                if (chooserMime == "video/*" || chooserMime == "*/*") {
                    createCameraFileUri(".mp4", "VID_")?.let { uri ->
                        cameraVideoUri = uri
                        initialIntents.add(cameraIntent(MediaStore.ACTION_VIDEO_CAPTURE, uri))
                    }
                }

                val chooser = Intent.createChooser(galleryIntent, "Choose a file").apply {
                    if (initialIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                return try {
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (e: Exception) {
                    uploadCallback = null
                    cameraImageUri = null
                    cameraVideoUri = null
                    Toast.makeText(this@MainActivity, "No file picker is available.", Toast.LENGTH_SHORT).show()
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val origin = request?.origin ?: return
                if (origin.host == appHost) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                val fileName = url.substringAfterLast('/').ifEmpty { "download" }
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(fileName)
                    setDescription("QDate download")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this@MainActivity, "Downloading…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                openExternal(Uri.parse(url))
            }
        }
    }

    /* ─────────────────────────── Helpers ─────────────────────────── */

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun createCameraFileUri(extension: String, prefix: String): Uri? {
        return try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile(prefix, extension, dir)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: IOException) {
            null
        }
    }

    private fun cameraIntent(action: String, output: Uri): Intent =
        Intent(action).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, output)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("QDate camera output", output)
        }

    private fun uriHasContent(uri: Uri): Boolean = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return@use cursor.getLong(index) > 0
            }
            false
        } ?: false
    } catch (e: Exception) {
        false
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can handle this link.", Toast.LENGTH_SHORT).show()
        }
    }

    /** True when the navigation targets the web app's Google OAuth start page —
     *  such flows are handed to the system browser with app=1 appended. */
    private fun googleStartFromUrl(url: Uri): Boolean {
        if (!url.host.equals(appHost, true)) return false
        if (!url.path.orEmpty().contains("/auth/google/start", ignoreCase = true)) return false
        val s = url.toString()
        val sep = if (s.contains('?')) "&" else "?"
        openExternal(Uri.parse(s + sep + "app=1"))
        return true
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        webView.onResume()
        // FCM can rotate a token while the app is backgrounded. Fetching the
        // current token on resume makes sure the web session re-binds it
        // without requiring a full page reload.
        if (::webView.isInitialized) initFcm()
    }

    override fun onPause() {
        isForeground = false
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** True while the app is in the foreground — the messaging service uses this
         *  to suppress notifications the in-app polling already covers. */
        @Volatile
        var isForeground = false
            private set

        private const val INTERSTITIAL_EVERY_N_BROWSES = 10
        private const val INTERSTITIAL_COOLDOWN_MS = 60_000L
    }
}
