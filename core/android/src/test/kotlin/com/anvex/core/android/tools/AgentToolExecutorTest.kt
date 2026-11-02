package com.anvex.core.android.tools

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AgentToolExecutorTest {
    private fun executor(vararg ip: String) = AgentToolExecutor(resolver = { ip.map { InetAddress.getByName(it) } })

    @Test fun rejectsPrivateAndLoopbackTargets() {
        for (ip in listOf("127.0.0.1", "10.0.0.5", "192.168.1.9", "169.254.1.1", "100.64.0.1", "fd00::1")) {
            val failed = runCatching { executor(ip).checkPublicHttps("https://example.com/".toHttpUrl()) }.isFailure
            assertTrue("$ip should be blocked", failed)
        }
    }

    @Test fun connectTimeDnsRefusesPrivateAnswers() {
        // The same predicate backs the OkHttp Dns hook, so a rebinding host cannot pass the
        // pre-check with a public IP and then connect to a private one.
        assertFalse(isPublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(isPublicAddress(InetAddress.getByName("::1")))
        assertFalse(isPublicAddress(InetAddress.getByName("192.168.0.10")))
        assertFalse(isPublicAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(isPublicAddress(InetAddress.getByName("93.184.216.34")))
    }

    @Test fun fakeIpVpnPoolAndTranslatedAddressesAreJudgedByTheirTarget() {
        // 198.18.0.0/15 is the Fake-IP pool of sing-box/Clash-style VPNs: behind one, every
        // public hostname resolves there. It must not be refused (v4.14.5 regression test).
        assertTrue(isPublicAddress(InetAddress.getByName("198.18.0.7")))
        // NAT64 / 6to4 are judged by the IPv4 they embed, not blanket-blocked.
        assertTrue(isPublicAddress(InetAddress.getByName("64:ff9b::5db8:d822"))) // 93.184.216.34
        assertFalse(isPublicAddress(InetAddress.getByName("64:ff9b::c0a8:101"))) // 192.168.1.1
        assertTrue(isPublicAddress(InetAddress.getByName("2002:5db8:d822::"))) // 93.184.216.34
        assertFalse(isPublicAddress(InetAddress.getByName("2002:c0a8:101::"))) // 192.168.1.1
    }

    @Test fun mixedResolutionPassesWhenAnyAnswerIsPublic() {
        // Split-DNS/VPN resolvers may add a non-public record next to the public one; the
        // connect-time filter drops it, so the pre-check only requires one public answer.
        executor("192.168.1.1", "93.184.216.34").checkPublicHttps("https://example.com/".toHttpUrl())
        val failed = runCatching {
            executor("10.0.0.5", "192.168.1.1").checkPublicHttps("https://example.com/".toHttpUrl())
        }.isFailure
        assertTrue("all-private resolution should still be blocked", failed)
    }

    @Test fun allowsPublicHttpsAndRejectsHttp() {
        executor("93.184.216.34").checkPublicHttps("https://example.com/".toHttpUrl())
        assertTrue(runCatching { executor("93.184.216.34").checkPublicHttps("http://example.com/".toHttpUrl()) }.isFailure)
    }

    @Test fun htmlToTextDropsScriptsAndTags() {
        val text = executor("93.184.216.34").htmlToText("<html><head><title>x</title></head><body><script>evil()</script><p>Hello &amp; <b>world</b></p></body></html>")
        assertEquals("Hello & world", text)
        assertFalse(text.contains("evil"))
    }
}
