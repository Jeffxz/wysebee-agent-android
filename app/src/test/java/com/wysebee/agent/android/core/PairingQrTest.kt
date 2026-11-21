package com.wysebee.agent.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrTest {
    private val token = "aB3_-".repeat(8) + "xyz"

    @Test fun readsWhatWysebeeShows() {
        assertEquals(PairingQr(token, "https://dev.example.com"), PairingQr.parse(" wysebee-agent:pair?offer=$token&from=https%3A%2F%2Fdev.example.com\n"))
        assertEquals(PairingQr(token, null), PairingQr.parse("wysebee-agent:pair?offer=$token"))
        assertEquals(PairingQr(token, null), PairingQr.parse("wysebee-agent:pair?from=&offer=$token&other=1&=x"))
    }

    @Test fun refusesAnythingElse() {
        for (bad in listOf(null, "", "hello", "https://app.wysebee.com/?offer=$token", "wysebee-agent:other?offer=$token", "wysebee-agent:pair?offer=${token}x", "wysebee-agent:pair?offer=${token.dropLast(1)}!", "wysebee-agent:pair?offer=%E0%A4%A")) {
            assertNull(bad, PairingQr.parse(bad))
        }
    }

    @Test fun hosts() {
        assertEquals("tunnel.example.com", PairingQr.host("https://Tunnel.Example.com/"))
        assertNull(PairingQr.host(null))
        assertNull(PairingQr.host("not a url"))
    }
}
