package app.tsukaima.data.api

import okhttp3.HttpUrl

/**
 * Where cleartext `http://` is allowed at all: hosts that are local or private, i.e. loopback (`127.0.0.0/8`,
 * `localhost`, `::1`), RFC 1918 (`10/8`, `172.16/12`, `192.168/16`), CGNAT/Tailscale (`100.64.0.0/10`), MagicDNS
 * names (`*.ts.net`) and single-label hostnames (`hermes`). https is always allowed.
 *
 * [CleartextGuard] refuses every other plain-http request (and [app.tsukaima.data.gateway.GatewayClient]
 * the socket), so neither the bearer token nor the password replayed by an automatic re-login goes out unencrypted
 * to a public host. Android's network security config cannot express this (it allowlists domains, not CIDRs), which
 * is why the check lives in code.
 */
object TrustedHost {
    fun allowsCleartext(url: HttpUrl): Boolean {
        if (url.isHttps) return true
        val host = url.host.lowercase()
        if (host == "localhost" || host == "::1") return true
        if (':' in host) return false // other IPv6 literals: not in the trusted list
        ipv4(host)?.let { (a, b) ->
            return a == 127 ||
                a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127)
        }
        if (host.endsWith(".ts.net")) return true
        return '.' !in host
    }

    /** First two octets of a dotted-quad IPv4 literal, or null if [host] is not one. */
    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && it in 0..255 } ?: return null }
        return octets[0] to octets[1]
    }
}
