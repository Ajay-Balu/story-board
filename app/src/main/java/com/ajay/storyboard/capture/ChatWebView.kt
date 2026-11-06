package com.ajay.storyboard.capture

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Builds a WebView for an AI chat site with the capture script installed before the
 * site's own scripts run.
 *
 * Notes on logins:
 *  - The user agent is left exactly as Android sets it. Changing it makes the UA disagree with the
 *    browser's client-hint headers, which bot checks (captcha / Cloudflare) treat as suspicious and
 *    then stall on a blurred or blank screen.
 *  - Popup windows (window.open / target=_blank), used by some login and captcha flows, open in a
 *    full-screen dialog WebView that shares the same cookies.
 */
object ChatWebView {

    init {
        // Lets you inspect these pages from a PC with chrome://inspect (personal app, always on).
        WebView.setWebContentsDebuggingEnabled(true)
    }

    /**
     * Loads [url] only once the WebView has its real on-screen size. Loading earlier lets the page
     * lay itself out in a 0-height window, which some sites (DeepSeek) never recover from.
     */
    fun loadWhenSized(web: WebView, url: String) {
        if (web.width > 0 && web.height > 0) {
            web.loadUrl(url)
            return
        }
        web.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(v: android.view.View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, orr: Int, ob: Int) {
                if (r - l > 0 && b - t > 0) {
                    v.removeOnLayoutChangeListener(this)
                    WebLog.add("page", "view sized ${r - l}x${b - t}, loading $url")
                    v.post { (v as WebView).loadUrl(url) }
                }
            }
        })
    }

    fun loadScript(context: Context, asset: String): String =
        context.assets.open(asset).bufferedReader().use { it.readText() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView) {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        // In dark mode, sites without their own dark theme are darkened; sites with one use it.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        }
        // Don't send the "X-Requested-With: <app id>" header that marks requests as coming from an app WebView.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }
    }

    fun create(
        context: Context,
        allowedOrigin: String,
        captureScript: String?,
        bridge: CaptureBridge?,
        onUrlChanged: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): WebView {
        val web = WebView(context)
        // MATCH_PARENT matters: with Compose's default WRAP_CONTENT, WebView reports a 0-height viewport,
        // so CSS 100vh/100dvh become 0 and sites like DeepSeek render a blank page.
        web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        configure(web)
        WebLog.add("init", "WebView ${WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "?"} · UA ${web.settings.userAgentString}")

        if (bridge != null) web.addJavascriptInterface(bridge, CaptureBridge.JS_NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, DIAG_SCRIPT, setOf("*"))
        }

        val documentStartSupported = captureScript != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(web, captureScript!!, setOf(allowedOrigin))
        }
        WebLog.add("init", "capture=${captureScript != null} documentStart=$documentStartSupported origin=$allowedOrigin")

        web.webViewClient = object : LoggingClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onUrlChanged(url)
                if (!documentStartSupported && captureScript != null && url.startsWith(allowedOrigin)) {
                    view.evaluateJavascript(captureScript, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                onUrlChanged(url)
                if (!documentStartSupported && captureScript != null && url.startsWith(allowedOrigin)) {
                    view.evaluateJavascript(captureScript, null)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Single-page apps change URL without loading a page; this catches /a/chat/s/<id>.
                onUrlChanged(url)
            }
        }
        web.webChromeClient = PopupChromeClient(context, onProgress)
        return web
    }

    /** Logs navigation, errors and renderer crashes. */
    open class LoggingClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            WebLog.add("page", "start $url")
        }

        override fun onPageFinished(view: WebView, url: String) {
            WebLog.add("page", "finished $url")
            CookieManager.getInstance().flush()
            listOf(1500L, 5000L).forEach { delay ->
                view.postDelayed({ snapshot(view, "after ${delay / 1000.0}s") }, delay)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme.orEmpty()
            if (scheme != "http" && scheme != "https") {
                // intent://, market:// ("Open in app" buttons) — ignore instead of showing an error page.
                WebLog.add("nav", "blocked non-web link ${request.url}")
                return true
            }
            return false
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            WebLog.add("error", "${if (request.isForMainFrame) "MAIN " else ""}${error.errorCode} ${error.description} ${request.url}")
            // Some sites only exist at www.<domain>: retry there when the bare domain doesn't resolve.
            val host = request.url.host.orEmpty()
            if (request.isForMainFrame && error.errorCode == ERROR_HOST_LOOKUP && host.isNotEmpty() &&
                !host.startsWith("www.") && host.count { it == '.' } == 1
            ) {
                val retry = request.url.buildUpon().authority("www.$host").build().toString()
                WebLog.add("nav", "retrying at $retry")
                view.post { view.loadUrl(retry) }
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (response.statusCode >= 400) {
                WebLog.add("http", "${if (request.isForMainFrame) "MAIN " else ""}${response.statusCode} ${request.method} ${request.url}")
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            WebLog.add("ssl", "${error.primaryError} ${error.url}")
            handler.cancel()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            WebLog.add("crash", "page renderer stopped (crash=${detail.didCrash()}); reloading")
            view.post { runCatching { view.reload() } }
            return true
        }
    }

    /** Logs what is really on screen: view size, viewport, visible text and element count. */
    fun snapshot(view: WebView, label: String) {
        val js = """(function(){try{
            var b=document.body, r=b?b.getBoundingClientRect():null;
            var root=document.querySelector('#root,#app,#__next,body>div');
            var rr=root?root.getBoundingClientRect():null;
            return JSON.stringify({
              ready:document.readyState, vis:document.visibilityState,
              inner:innerWidth+'x'+innerHeight, dpr:devicePixelRatio,
              body:r?Math.round(r.width)+'x'+Math.round(r.height):null,
              root:rr?(root.id||root.tagName)+' '+Math.round(rr.width)+'x'+Math.round(rr.height):null,
              els:document.getElementsByTagName('*').length,
              text:(b&&b.innerText||'').trim().slice(0,160),
              inputs:document.querySelectorAll('input').length,
              bg:b?getComputedStyle(b).backgroundColor:null,
              scheme:matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'
            });}catch(e){return 'snapshot failed '+e}})()"""
        WebLog.add("view", "$label size=${view.width}x${view.height} shown=${view.isShown} layer=${view.layerType}")
        view.evaluateJavascript(js) { WebLog.add("dom", "$label $it") }
    }

    /**
     * Runs on every page before the site's scripts:
     *  - layout fix: some sites (DeepSeek, Grok) size their app from the screen height, which comes out
     *    as 0 inside an app WebView, so the whole page is drawn in a 0-px box (white screen). When the
     *    page's main container has no height, it is forced to the full window height.
     *  - diagnostics: uncaught errors and API call results go to the console (and so into WebLog).
     */
    private val DIAG_SCRIPT = """
        (function(){
          if (window.__sbDiag) return; window.__sbDiag = true;

          var fixed = false;
          function fixHeight(){
            var b = document.body; if (!b || fixed) return;
            var root = document.querySelector('#root,#app,#__next,body>div');
            var bh = b.getBoundingClientRect().height;
            var rh = root ? root.getBoundingClientRect().height : bh;
            if (bh < 2 && rh < 2 && b.children.length) {
              var cs = getComputedStyle(b), rcs = root ? getComputedStyle(root) : null;
              console.warn('[fix] page has 0 height (html=' + getComputedStyle(document.documentElement).height +
                ' body=' + cs.height + '/' + cs.position + ' root=' + (rcs ? rcs.height + '/' + rcs.position : '-') +
                ' vv=' + (window.visualViewport ? visualViewport.height : 'none') + '), forcing full height');
              var st = document.createElement('style');
              st.id = 'storyboard-height-fix';
              st.textContent = 'html,body{height:100% !important;min-height:' + innerHeight + 'px !important;}' +
                'body>div:first-of-type,#root,#app,#__next{height:100% !important;min-height:' + innerHeight + 'px !important;}';
              (document.head || document.documentElement).appendChild(st);
              fixed = true;
              window.dispatchEvent(new Event('resize'));
            }
          }
          var n = 0, t = setInterval(function(){ fixHeight(); if (++n > 40) clearInterval(t); }, 400);
          window.addEventListener('resize', function(){
            var st = document.getElementById('storyboard-height-fix');
            if (st) st.textContent = st.textContent.replace(/min-height:\d+px/g, 'min-height:' + innerHeight + 'px');
          });

          window.addEventListener('error', function(e){
            console.error('[diag] uncaught: ' + (e.message || (e.target && (e.target.src || e.target.href)) || e) + ' @' + (e.filename||'') + ':' + (e.lineno||''));
          }, true);
          window.addEventListener('unhandledrejection', function(e){
            var r = e.reason; console.error('[diag] unhandled promise: ' + (r && (r.stack || r.message) || r));
          });
          var f = window.fetch;
          if (f) window.fetch = function(input, init){
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            return f.apply(this, arguments).then(function(res){
              if (/\/api\//.test(url)) console.log('[diag] fetch ' + res.status + ' ' + url.split('?')[0]);
              return res;
            }, function(err){ console.error('[diag] fetch failed ' + url.split('?')[0] + ' ' + err); throw err; });
          };
        })();
    """.trimIndent()

    /** Progress, console logging and popup windows. */
    class PopupChromeClient(
        private val context: Context,
        private val onProgress: (Int) -> Unit,
    ) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            WebLog.add("console", "${message.messageLevel()} ${message.message()} (${message.sourceId().substringAfterLast('/')}:${message.lineNumber()})")
            return true
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            WebLog.add("popup", "opening popup window")
            val popup = WebView(context)
            configure(popup)
            val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar)
            popup.webViewClient = LoggingClient()
            popup.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) {
                    WebLog.add("popup", "closed by page")
                    dialog.dismiss()
                }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    WebLog.add("popup-console", message.message())
                    return true
                }
            }
            dialog.setContentView(popup, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dialog.setOnDismissListener { popup.destroy() }
            dialog.show()
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }
}
