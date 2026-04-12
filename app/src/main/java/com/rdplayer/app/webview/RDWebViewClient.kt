package com.rdplayer.app.webview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.rdplayer.app.R
import com.rdplayer.app.util.ServerUrlUtils

class RDWebViewClient(
    private val serverUrl: String,
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
    private val onError: (String) -> Unit,
    private val onMediaUrl: (String) -> Unit
) : WebViewClient() {

    companion object {
        private val MEDIA_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm",
            ".m3u8", ".ts", ".mpd",
            ".mp3", ".flac", ".aac", ".ogg", ".wav", ".m4a"
        )
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)

        // Inject D-Pad Focus Styles for Android TV Support
        view?.evaluateJavascript("""
            (function() {
                if (!document.getElementById('tv-focus-style')) {
                    var style = document.createElement('style');
                    style.id = 'tv-focus-style';
                    style.innerHTML = '*:focus { outline: 4px solid #FF9800 !important; outline-offset: 2px !important; border-radius: 4px !important; background-color: rgba(255,152,0,0.1) !important; }';
                    document.head.appendChild(style);
                }
            })();
        """.trimIndent(), null)

        onPageFinished()
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        val context = view?.context ?: return false
        val path = request.url.path?.lowercase() ?: ""

        // 1. Check if it's a direct media file URL → open in external player
        if (MEDIA_EXTENSIONS.any { path.endsWith(it) }) {
            onMediaUrl(url)
            return true
        }

        // 2. Internal navigation → stay in WebView
        if (isInternalUrl(url)) {
            return false
        }

        // 4. External link → open in browser
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
        return true
    }

    private fun isInternalUrl(url: String): Boolean {
        return ServerUrlUtils.isInternalUrl(serverUrl, url)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (request?.isForMainFrame == true) {
            val context = view?.context ?: return
            onError(describeWebViewError(context, error?.errorCode, error?.description))
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        if (request?.isForMainFrame == true) {
            val context = view?.context ?: return
            describeHttpStatusError(context, errorResponse?.statusCode ?: 0)?.let(onError)
        }
    }
}

internal fun describeWebViewError(
    context: Context,
    errorCode: Int?,
    description: CharSequence?
): String {
    return when (errorCode) {
        WebViewClient.ERROR_HOST_LOOKUP -> context.getString(R.string.error_network_dns)
        WebViewClient.ERROR_CONNECT -> context.getString(R.string.error_network_connect)
        WebViewClient.ERROR_TIMEOUT -> context.getString(R.string.error_network_timeout)
        WebViewClient.ERROR_BAD_URL -> context.getString(R.string.error_network_bad_url)
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> context.getString(R.string.error_network_ssl)
        WebViewClient.ERROR_AUTHENTICATION -> context.getString(R.string.error_network_auth)
        WebViewClient.ERROR_PROXY_AUTHENTICATION -> context.getString(R.string.error_network_proxy_auth)
        WebViewClient.ERROR_REDIRECT_LOOP -> context.getString(R.string.error_network_redirect_loop)
        WebViewClient.ERROR_TOO_MANY_REQUESTS -> context.getString(R.string.error_network_too_many_requests)
        else -> context.getString(
            R.string.error_network_generic,
            description?.toString()?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.error_unknown_reason)
        )
    }
}

internal fun describeHttpStatusError(context: Context, statusCode: Int): String? {
    return when (statusCode) {
        401 -> context.getString(R.string.error_http_401)
        403 -> context.getString(R.string.error_http_403)
        404 -> context.getString(R.string.error_http_404)
        429 -> context.getString(R.string.error_http_429)
        in 500..599 -> context.getString(R.string.error_http_5xx, statusCode)
        in 400..499 -> context.getString(R.string.error_http_generic, statusCode)
        else -> null
    }
}
