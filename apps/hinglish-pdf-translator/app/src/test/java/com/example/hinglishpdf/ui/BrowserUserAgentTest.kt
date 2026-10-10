package com.example.hinglishpdf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserUserAgentTest {

    @Test
    fun `the embedded-webview marks are removed, the rest is kept`() {
        val webView = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.100 Mobile Safari/537.36"
        val ua = browserUserAgent(webView)
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/129.0.6668.100 Mobile Safari/537.36",
            ua,
        )
        assertFalse(ua.contains("; wv"))
        assertEquals(ua, browserUserAgent(ua)) // already clean: unchanged
    }
}
