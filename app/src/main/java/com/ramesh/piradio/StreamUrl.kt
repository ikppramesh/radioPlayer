package com.ramesh.piradio

import java.net.URI

object StreamUrl {

    fun isValid(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
    }

    /** True for plain-HTTP URLs that point outside the local network. */
    fun isInsecureRemote(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (uri.scheme?.lowercase() != "http") return false
        val host = uri.host?.lowercase() ?: return false
        return !isLocalHost(host)
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val parts = host.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (e: java.net.URISyntaxException) {
        null
    }
}
