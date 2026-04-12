package com.rdplayer.app.webview

import android.webkit.WebChromeClient
import android.webkit.WebView

class RDWebChromeClient(
    private val onProgressChanged: (Int) -> Unit
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChanged(newProgress)
    }
}
