package com.wysebee.agent.android.core

import java.net.URI
import java.net.URLDecoder

/**
 * A pairing QR code Wysebee shows (the link protocol, "Pairing by a QR code"):
 * `wysebee-agent:pair?offer=<token>&from=<where it was shown>`. The token goes only to this
 * build's own cloud; `from` is only for saying where a QR code came from when that cloud does not
 * know it.
 */
data class PairingQr(val offer: String, val from: String?) {
    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

        /** The QR code's offer, or null when the text is not a Wysebee pairing QR code. */
        fun parse(text: String?): PairingQr? {
            val raw = text?.trim() ?: return null
            if (!raw.startsWith("wysebee-agent:pair?")) return null
            val query = raw.substringAfter('?')
            val fields = query.split('&').mapNotNull { part ->
                val key = part.substringBefore('=', "")
                if (key.isEmpty()) null else key to runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrNull()
            }.toMap()
            val offer = fields["offer"]?.takeIf { TOKEN.matches(it) } ?: return null
            return PairingQr(offer, fields["from"]?.takeIf { it.isNotBlank() })
        }

        /** The host of an address, for saying it; null when it has none. */
        fun host(url: String?): String? = runCatching { URI(url!!).host?.lowercase() }.getOrNull()
    }
}
