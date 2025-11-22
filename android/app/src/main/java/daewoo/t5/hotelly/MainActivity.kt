package daewoo.t5.hotelly

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log // Log import 확인!
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.DataBindingUtil
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.messaging.FirebaseMessaging
import daewoo.t5.hotelly.databinding.ActivityMainBinding
import daewoo.t5.hotelly.utils.Constant
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 1000
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 2000
    private var lastScrollTop: Int = 0
    inner class WebAppInterface {
        /**
         * 이 함수가 호출되면, 웹뷰는 이 코드가
         * 안드로이드 앱 내부에서 실행 중임을 알 수 있음.
         */
        @JavascriptInterface // ❗ 이 어노테이션이 필수!
        fun isAndroidApp(): Boolean {
            return true
        }
        @JavascriptInterface
        fun updateScrollPosition(scrollTop: Int) {
            lastScrollTop = scrollTop
        }
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), NOTIFICATION_PERMISSION_REQUEST_CODE)
            } else {
//                // 이미 허용됨 → 알림 테스트 가능
//                showNotificationExample()
            }
        }
    }
    // 토큰 저장할 변수
    private var fcmToken: String? = null
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 허용됨
                showNotificationExample()
            } else {
                // 거부됨 → 안내
                AlertDialog.Builder(this)
                    .setTitle("알림 권한 필요")
                    .setMessage("앱 알림을 받으려면 권한이 필요합니다. 설정에서 허용해 주세요.")
                    .setPositiveButton("설정으로 이동") { _, _ ->
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        startActivity(intent)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
    }

    // 알림 테스트용 함수 (원하면 FCM 알림 연동 가능)
    private fun showNotificationExample() {
        Toast.makeText(this, "알림 권한 허용됨!", Toast.LENGTH_SHORT).show()
    }
    // 토큰을 웹뷰로 쏘는 공통 함수
    private fun sendTokenToWebView(token: String) {
        Log.d("WebViewBridge", "Sending token to WebView: $token")
        runOnUiThread {
            val script = "window.setFCMToken('$token')"
            binding.webView.evaluateJavascript(script, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 3000)
        }
        requestNotificationPermission()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // SwipeRefreshLayout 설정
        val swipe = binding.root.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        swipe.setOnRefreshListener {
            binding.webView.reload()
        }

        // WebView 기본 설정
        binding.webView.apply {
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                loadsImagesAutomatically = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }

            // WebViewClient 설정
            webViewClient = object : WebViewClient() {

                // 페이지 로딩이 완료됐을 때 호출됨
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("WebView", "Page finished loading: $url")

                    // 로딩 끝나면 SwipeRefreshLayout의 로딩 아이콘 숨김
                    swipe.isRefreshing = false

                    // 페이지 로딩이 끝났는데, 만약 fcmToken이 이미 와있다면?
                    // 지금 바로 웹뷰로 쏴준다! (토큰이 로딩보다 빨리 온 경우)
                    fcmToken?.let { token ->
                        sendTokenToWebView(token)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    Log.e("WebView", "Error: ${error?.description}, URL: ${request?.url}")
                    super.onReceivedError(view, request, error)
                    swipe.isRefreshing = false
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    Log.e("WebView", "HTTP Error: ${errorResponse?.statusCode}, URL: ${request?.url}")
                    super.onReceivedHttpError(view, request, errorResponse)
                    swipe.isRefreshing = false
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    Log.d("WebView", "Loading URL: $url")
                    return handleUrl(url)
                }
            }

            // WebChromeClient 설정
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d("WebView Console", "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                    }
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {

                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback

                    // 카메라 인텐트
                    val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    var cameraUri: Uri? = null

                    // 촬영 이미지 임시 저장경로 만들기
                    val photoFile = kotlin.runCatching {
                        File.createTempFile("camera_", ".jpg", cacheDir)
                    }.getOrNull()

                    photoFile?.also {
                        cameraUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.provider",
                            it
                        )
                        cameraIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri)
                    }

                    // 갤러리 인텐트
                    val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }

                    // 선택 UI
                    val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, galleryIntent)
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }

                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                    return true
                }
            }

            handleIntent(intent)
        }
        swipe.setOnChildScrollUpCallback { _, _ ->
            // true = 아직 스크롤이 위가 아님 → SwipeRefresh 비활성화
            // false = 맨 위 → SwipeRefresh 가능
            return@setOnChildScrollUpCallback lastScrollTop > 0
        }
        // 알림 권한 요청 (Android 13 이상)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }

        // FCM 토큰 가져오기
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }

            // 새 토큰 가져오기 성공
            val token = task.result
            Toast.makeText(this, token, Toast.LENGTH_SHORT).show()
            Log.d("FCM", "New Token: $token")

            // 토큰을 받았을 때의 동작
            this.fcmToken = token
            sendTokenToWebView(token)
        }
        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                // 기존의 back 동작 수행
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }
    private fun handleUrl(url: String): Boolean {
        // 네트워크 URL 이면 WebView가 그대로 로딩하게 false
        if (URLUtil.isNetworkUrl(url) || URLUtil.isJavaScriptUrl(url)) {
            return false
        }

        // 딥링크 / 스킴 처리
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return false
        }

        return when (uri.scheme) {
            "intent" -> {
                startSchemeIntent(url)
            }

            else -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun startSchemeIntent(url: String): Boolean {
        val schemeIntent: Intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return false
        }

        try {
            startActivity(schemeIntent)
            return true
        } catch (e: ActivityNotFoundException) {
            val packageName = schemeIntent.`package`
            if (!packageName.isNullOrBlank()) {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$packageName")
                    )
                )
                return true
            }
        }
        return false
    }

    private fun handleIntent(intent: Intent?) {
        // 기본 경로는 "/" 또는 "" (승호의 웹 라우터 설정에 맞게!)
        var path = "/"

        // FCM 서비스가 넣어준 click_action 값을 꺼내보자!
        intent?.getStringExtra(Constant.CLICK_ACTION_KEY)?.let {
            Log.d("MainActivity", "Received click_action path: $it")
            path = it // 값이 있으면 그걸로 교체!
        }

        // 베이스 URL이랑 합체!
        val finalUrl = Constant.BASE_URL + path
        Log.d("MainActivity", "Loading final URL: $finalUrl")

        // 최종 URL 로드!
        binding.webView.loadUrl(finalUrl)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // 갤러리(파일 선택) 결과 처리
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val result = if (resultCode == Activity.RESULT_OK && data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            filePathCallback?.onReceiveValue(result)
            filePathCallback = null
        }
    }
}