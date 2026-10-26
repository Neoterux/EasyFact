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

/**
 * Pure URL-host extraction and allowlist check over release dex content. No Gradle types, so it
 * runs in a JVM unit test.
 *
 * The dex is treated as bytes and scanned for printable runs that begin with an `http` or `https`
 * scheme; the dex format is not parsed. That is enough to catch a library that embeds an endpoint
 * as a string constant, which is what the measured lab run found: with OkHttp, WorkManager,
 * DataStore, core-ktx and Compose on the classpath, exactly one host appeared. It is a call-site
 * proxy, not a network monitor, and [RESIDUAL_GAP] says what it cannot see.
 */
object UrlHostScan {

    /**
     * Hosts that may appear as a URL in the release dex. Every entry is a reviewed line.
     *
     * `developer.android.com` is the single host that scan actually produced for an AndroidX-only
     * build (documentation links inside library strings). It is not an egress point.
     *
     * Hosts that join this list, each when its feature lands and not before, so that an allowlist
     * never permits what does not exist yet:
     * - the SRI test and production hosts, and the taxpayer-lookup host, when the SRI client lands;
     * - `api.github.com` when the in-app updater lands;
     * - the XML namespace hosts that the signer's own output emits, when the signer lands.
     */
    val ALLOWED_HOSTS: List<String> = listOf(
        "developer.android.com",
    )

    /**
     * The honest limits of the three telemetry layers (dependency policy, merged-manifest policy
     * and this scan). It is printed with every result of the release-dex gate and repeated in the
     * phase summary, and must not be softened.
     */
    const val RESIDUAL_GAP: String =
        "RESIDUAL GAP: a dependency denylist is a blocklist, not a proof of absence. The three layers " +
            "(dependency policy, merged-manifest policy, release-dex host scan) narrow the gap and do not " +
            "close it. None of them can see a URL assembled at runtime from parts, a host reached through " +
            "reflection, a WebView, a native library, or an allowlisted library that itself phones home. " +
            "The final assurance is runtime: capture the app's traffic on a real device during a complete " +
            "issue flow and confirm only allowlisted hosts appear. That manual check is owed before the " +
            "Phase 8 production gate, and nothing in Phase 1 substitutes for it."

    private val SCHEME_HTTP = "http".toByteArray(Charsets.US_ASCII)
    private const val MAX_URL_LENGTH = 2048

    /**
     * Every distinct host named by an `http://` or `https://` string in [content], lower-cased,
     * without userinfo, port or trailing dot. A URL with no host (a bare `https://` prefix constant)
     * contributes nothing. A string that is not a valid host name, such as a `%s` format
     * placeholder, is returned as written so that it fails loudly instead of being dropped.
     */
    fun hosts(content: ByteArray): Set<String> {
        val result = sortedSetOf<String>()
        var i = 0
        val last = content.size - SCHEME_HTTP.size
        while (i <= last) {
            if (!matchesHttp(content, i)) {
                i++
                continue
            }
            var cursor = i + SCHEME_HTTP.size
            if (cursor < content.size && lower(content[cursor]) == 's'.code) cursor++
            if (!startsWithSlashes(content, cursor)) {
                i++
                continue
            }
            cursor += 3
            val start = cursor
            while (cursor < content.size && cursor - start < MAX_URL_LENGTH && isUrlByte(content[cursor])) cursor++
            hostOf(String(content, start, cursor - start, Charsets.US_ASCII))?.let { result += it }
            i = maxOf(cursor, i + 1)
        }
        return result
    }

    /** `UNEXPECTED HOST <host>` for every host in [hosts] that is not in [allowed], sorted. */
    fun violations(hosts: Collection<String>, allowed: Collection<String>): List<String> {
        val allowedSet = allowed.map { it.lowercase() }.toSet()
        return hosts.map { it.lowercase() }.distinct().filter { it !in allowedSet }.sorted()
            .map { "UNEXPECTED HOST $it" }
    }

    private fun matchesHttp(content: ByteArray, at: Int): Boolean {
        for (k in SCHEME_HTTP.indices) {
            if (lower(content[at + k]) != SCHEME_HTTP[k].toInt()) return false
        }
        return true
    }

    private fun startsWithSlashes(content: ByteArray, at: Int): Boolean =
        at + 2 < content.size &&
            content[at] == ':'.code.toByte() && content[at + 1] == '/'.code.toByte() && content[at + 2] == '/'.code.toByte()

    private fun lower(b: Byte): Int {
        val c = b.toInt() and 0xFF
        return if (c in 'A'.code..'Z'.code) c + 32 else c
    }

    /** Printable ASCII that can sit inside a URL; whitespace, quotes, brackets and control bytes end it. */
    private fun isUrlByte(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        if (c <= 0x20 || c >= 0x7F) return false
        return c != '"'.code && c != '\''.code && c != '<'.code && c != '>'.code && c != '\\'.code && c != '`'.code
    }

    private fun hostOf(rest: String): String? {
        var authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        authority = authority.substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBefore(':')
        }
        val cleaned = host.trimEnd('.').lowercase()
        return cleaned.ifEmpty { null }
    }
}
