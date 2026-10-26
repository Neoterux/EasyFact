/*
 * Copyright (C) 2026 the EasyFact contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package easyfact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Committed failing fixtures for the release-dex host gate. The byte arrays are synthetic and use
 * `example.com` names only, so these tests are the permanent proof that the gate can be seen red
 * independent of any real build.
 */
class UrlHostScanTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun scan(text: String) = UrlHostScan.violations(
        UrlHostScan.hosts(bytes(text)),
        UrlHostScan.ALLOWED_HOSTS,
    )

    @Test
    fun contentWithOnlyAnAllowlistedHostPasses() {
        assertEquals(emptyList<String>(), scan("see https://developer.android.com/reference/foo for details"))
    }

    @Test
    fun analyticsEndpointIsReportedByHost() {
        assertEquals(
            listOf("UNEXPECTED HOST telemetry.example.com"),
            scan("https://telemetry.example.com/v1/collect"),
        )
    }

    @Test
    fun hostEmbeddedAmongBinaryNoiseIsStillFound() {
        val noise = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x03, 0x1B, 0x80.toByte())
        val content = noise + bytes("https://metrics.example.com/ping") + noise + bytes("x") + noise
        assertEquals(setOf("metrics.example.com"), UrlHostScan.hosts(content))
    }

    @Test
    fun httpAndHttpsFormsOfTheSameHostReduceToOneHost() {
        val hosts = UrlHostScan.hosts(bytes("http://Collector.Example.com/a\u0000https://collector.example.com:8443/b"))
        assertEquals(setOf("collector.example.com"), hosts)
    }

    @Test
    fun userinfoPortQueryAndFragmentAreStrippedFromTheHost() {
        assertEquals(setOf("host.example.com"), UrlHostScan.hosts(bytes("https://user:pw@host.example.com:99?x=1")))
        assertEquals(setOf("host.example.com"), UrlHostScan.hosts(bytes("https://host.example.com#frag")))
    }

    @Test
    fun bareSchemePrefixConstantContributesNoHost() {
        assertEquals(emptySet<String>(), UrlHostScan.hosts(bytes("https://\u0000http://")))
    }

    @Test
    fun placeholderHostIsReturnedRatherThanDropped() {
        val result = scan("https://%s/path")
        assertEquals(listOf("UNEXPECTED HOST %s"), result)
    }

    @Test
    fun severalHostsInOneBlobAreAllReported() {
        val result = scan("https://a.example.com/x https://developer.android.com/y\u0000http://b.example.com")
        assertEquals(listOf("UNEXPECTED HOST a.example.com", "UNEXPECTED HOST b.example.com"), result)
    }

    @Test
    fun allowlistComparisonIgnoresCase() {
        assertEquals(emptyList<String>(), UrlHostScan.violations(listOf("Developer.Android.COM"), listOf("developer.android.com")))
        assertTrue(UrlHostScan.violations(listOf("other.example.com"), listOf("developer.android.com")).isNotEmpty())
    }
}
