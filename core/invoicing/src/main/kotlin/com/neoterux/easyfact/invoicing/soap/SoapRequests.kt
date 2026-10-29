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

package com.neoterux.easyfact.invoicing.soap

import com.neoterux.easyfact.invoicing.accesskey.AccessKey
import com.neoterux.easyfact.invoicing.signing.SignedDocument
import java.util.Base64

/**
 * The two SOAP requests the engine sends to the SRI, as UTF-8 bytes ready for a transport.
 *
 * The signatures are the control (D-42). [validarComprobante] takes only a [SignedDocument], so
 * the payload can only be the exact octets a signer produced or that were re-admitted with their
 * recorded digest; a free string or a re-parsed document cannot reach the wire. [autorizacionComprobante]
 * takes only an [AccessKey], so a malformed key cannot be asked about. Nothing here serializes an
 * XML tree: the envelopes are fixed text around one value each, which is why the bytes signed are
 * the bytes transmitted.
 *
 * Operation and parameter names come from the SRI's technical sheet (Ficha v2.34, pages 17-18):
 * `validarComprobante(xml: byte[])` on the reception service and
 * `autorizacionComprobante(claveAccesoComprobante: String)` on the authorization service.
 *
 * [ASSUMED] The wrapper shape is the probe author's reading of the service descriptions, not an
 * observed exchange: the operation element is qualified with the service namespace and the
 * parameter element is unqualified, and no `soapenv:Header` is emitted although the sheet's own
 * consulta sample (page 22) carries an empty one. The owner-run test-environment session settles
 * both points; the owner decided on 2026-10-28 to keep the probe's wrapper until then.
 *
 * HTTP transport, timeouts and retries belong to Phase 6; nothing here opens a connection. The
 * namespace URLs below are identifiers inside the XML, not hosts this app contacts. When `:app`
 * starts to reference this module, `UrlHostScan.ALLOWED_HOSTS` must be reviewed (research
 * Pitfall 12), because the scan reads literals out of release dex.
 */
object SoapRequests {

    private const val ENVELOPE_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val RECEPTION_NAMESPACE = "http://ec.gob.sri.ws.recepcion"
    private const val AUTHORIZATION_NAMESPACE = "http://ec.gob.sri.ws.autorizacion"

    /**
     * The reception request for [document]. The payload is the base64 of the signed octets and
     * nothing else; the basic encoder is used because it never inserts a line break, however long
     * the payload is, and a line break inside the element would be part of the transmitted value.
     */
    fun validarComprobante(document: SignedDocument): ByteArray {
        val payload = Base64.getEncoder().encodeToString(document.octets())
        return (
            "<soapenv:Envelope xmlns:soapenv=\"$ENVELOPE_NAMESPACE\" xmlns:ec=\"$RECEPTION_NAMESPACE\">" +
                "<soapenv:Body><ec:validarComprobante><xml>$payload</xml></ec:validarComprobante></soapenv:Body>" +
                "</soapenv:Envelope>"
            ).toByteArray(Charsets.UTF_8)
    }

    /** The authorization query for [accessKey]. The digits are all-numeric, so no escaping is needed. */
    fun autorizacionComprobante(accessKey: AccessKey): ByteArray =
        (
            "<soapenv:Envelope xmlns:soapenv=\"$ENVELOPE_NAMESPACE\" xmlns:ec=\"$AUTHORIZATION_NAMESPACE\">" +
                "<soapenv:Body><ec:autorizacionComprobante><claveAccesoComprobante>${accessKey.digits}" +
                "</claveAccesoComprobante></ec:autorizacionComprobante></soapenv:Body></soapenv:Envelope>"
            ).toByteArray(Charsets.UTF_8)
}
