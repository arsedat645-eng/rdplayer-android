package com.rdplayer.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlUtilsTest {

    @Test
    fun `normalize trims whitespace and trailing slash`() {
        assertEquals(
            "http://100.64.0.10:7070",
            ServerUrlUtils.normalizeServerUrl("  http://100.64.0.10:7070/  ")
        )
    }

    @Test
    fun `buildTargetUrl appends relative path`() {
        assertEquals(
            "http://100.64.0.10:7070/add?q=test",
            ServerUrlUtils.buildTargetUrl("http://100.64.0.10:7070/", "/add?q=test")
        )
    }

    @Test
    fun `validates only http and https URLs`() {
        assertTrue(ServerUrlUtils.isValidServerUrl("https://tailnet-name.ts.net"))
        assertFalse(ServerUrlUtils.isValidServerUrl("ftp://tailnet-name.ts.net"))
        assertFalse(ServerUrlUtils.isValidServerUrl("not a url"))
    }

    @Test
    fun `https server does not treat http downgrade as internal`() {
        assertFalse(
            ServerUrlUtils.isInternalUrl(
                serverUrl = "https://tailnet-name.ts.net",
                candidateUrl = "http://tailnet-name.ts.net"
            )
        )
    }

    @Test
    fun `http server keeps same host and port internal`() {
        assertTrue(
            ServerUrlUtils.isInternalUrl(
                serverUrl = "http://100.64.0.10:7070",
                candidateUrl = "http://100.64.0.10:7070/discover"
            )
        )
    }
}
