package dev.hazydreams.hermesceleste.network

import java.net.URI

object DashboardUrlPolicy {
    fun normalize(raw: String): String {
        val entered = raw.trim().trimEnd('/')
        require(entered.isNotEmpty()) { "Enter your Hermes dashboard address." }
        val candidate = if (SCHEME_PREFIX.containsMatchIn(entered)) entered else "http://$entered"

        val uri = runCatching { URI(candidate) }
            .getOrElse { throw IllegalArgumentException("That dashboard address is not valid.") }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()

        require(scheme == "https" || scheme == "http") { "Use an http:// or https:// dashboard address." }
        require(!host.isNullOrBlank()) { "The dashboard address needs a host." }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Use only the dashboard's base address."
        }
        require(scheme == "https" || isPrivateHttpHost(host)) {
            "Plain HTTP is allowed only for local, private-network, or Tailscale dashboards."
        }

        val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
        val port = if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
        val renderedHost = if (host.contains(':')) "[$host]" else host
        val path = uri.normalize().path.orEmpty().trimEnd('/').let { if (it == "/") "" else it }
        return "$scheme://$renderedHost$port$path"
    }

    internal fun isPrivateHttpHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".ts.net")) {
            return true
        }
        if (!host.contains('.')) return true
        if (host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")) return true

        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            octets[0] == 127 ||
            (octets[0] == 100 && octets[1] in 64..127) ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
