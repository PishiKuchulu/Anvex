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
