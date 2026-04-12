package com.rdplayer.app.util

import java.net.URL

object ServerUrlUtils {

    fun normalizeServerUrl(input: String): String = input.trim().trimEnd('/')

    fun isValidServerUrl(input: String): Boolean {
        val normalized = normalizeServerUrl(input)
        if (normalized.isBlank()) return false

        return try {
            val url = URL(normalized)
            url.protocol in listOf("http", "https") && url.host.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun buildTargetUrl(serverUrl: String, path: String? = null): String {
        val baseUrl = normalizeServerUrl(serverUrl)
        if (path.isNullOrBlank()) return baseUrl

        return if (path.startsWith("/")) {
            baseUrl + path
        } else {
            "$baseUrl/$path"
        }
    }

    fun isInternalUrl(serverUrl: String, candidateUrl: String): Boolean {
        return try {
            val server = URL(normalizeServerUrl(serverUrl))
            val candidate = URL(candidateUrl)

            val sameHost = server.host.equals(candidate.host, ignoreCase = true)
            val samePort = effectivePort(server) == effectivePort(candidate)
            val schemeAllowed = if (server.protocol.equals("https", ignoreCase = true)) {
                candidate.protocol.equals("https", ignoreCase = true)
            } else {
                candidate.protocol.equals("http", ignoreCase = true) ||
                    candidate.protocol.equals("https", ignoreCase = true)
            }

            sameHost && samePort && schemeAllowed
        } catch (_: Exception) {
            false
        }
    }

    private fun effectivePort(url: URL): Int = if (url.port != -1) url.port else url.defaultPort
}
