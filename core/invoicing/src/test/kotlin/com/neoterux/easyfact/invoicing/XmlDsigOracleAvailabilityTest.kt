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

package com.neoterux.easyfact.invoicing

import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.stream.XMLInputFactory
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Proves the independent verification oracle is reachable from test source.
 *
 * Phase 2 signs against JSR-105 (`javax.xml.crypto`) and reads results back with StAX
 * (`javax.xml.stream`). Both exist in the JDK and are absent from Android, so they are legal in
 * this module's test source and forbidden in its main source (AGENTS.md section 4).
 *
 * This test is also the proof that `verifyPortableBoundary` and Animal Sniffer scan main output
 * only: `./gradlew check` stays green with these imports present.
 */
class XmlDsigOracleAvailabilityTest {

    @Test
    fun jsr105DomFactoryIsAvailable() {
        assertNotNull(XMLSignatureFactory.getInstance("DOM"))
    }

    @Test
    fun staxInputFactoryInstantiates() {
        assertNotNull(XMLInputFactory.newInstance())
    }
}
