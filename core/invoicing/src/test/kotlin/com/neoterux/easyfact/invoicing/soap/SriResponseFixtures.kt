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
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.time.LocalDate

/**
 * Synthetic SRI responses (D-45). Every one is MODELLED, not captured: none was recorded from the
 * SRI, no document was submitted to obtain it, and every value is a placeholder.
 *
 * Element names and nesting follow the technical sheet (Ficha) v2.34, pages 17-19: a `soap:`
 * envelope, an `ns2:` response element in the SRI's namespace, unqualified children. The Ficha's
 * samples pad `claveAccesoConsultada`, `numeroAutorizacion` and the message text with line breaks,
 * and these do too. Keys come from [AccessKey.of] over [SyntheticIdentifiers.RUC] at runtime and
 * line breaks are explicit, so no identifier-shaped literal appears in this file.
 */
object SriResponseFixtures {

    private const val RECEPTION_NAMESPACE = "http://ec.gob.sri.ws.recepcion"
    private const val AUTHORIZATION_NAMESPACE = "http://ec.gob.sri.ws.autorizacion"
    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"

    /** A date as the SRI writes it, ISO with an offset (Ficha p.18). */
    const val SAMPLE_DATE: String = "2026-10-27T10:15:30.997-05:00"

    /** The synthetic 49-digit key every fixture refers to. */
    val accessKey: String = keyFor(2L)

    /** A different synthetic key, for fixtures that need two. */
    val otherAccessKey: String = keyFor(3L)

    /**
     * The comprobante every fixture embeds: a short synthetic factura with an XML declaration, a
     * comment holding a literal `<autorizacion>` and no signature block.
     */
    val comprobanteText: String =
        XML_DECLARATION + "<factura id=\"comprobante\" version=\"1.1.0\">" +
            "<!-- a literal <autorizacion> element name inside a comment -->" +
            "<infoTributaria><razonSocial>Test Issuer S.A.</razonSocial><claveAcceso>" + accessKey +
            "</claveAcceso></infoTributaria></factura>"

    /** [comprobanteText] as a CDATA section, with its delimiters, exactly as an `autorizacion` carries it. */
    val comprobanteCdata: String = "<![CDATA[$comprobanteText]]>"

    /** [comprobanteText] as escaped character data. */
    val comprobanteEscaped: String = escape(comprobanteText)

    private fun keyFor(sequential: Long): String =
        when (
            val outcome = AccessKey.of(
                issueDate = LocalDate.of(2026, 10, 27),
                documentTypeCode = "01",
                issuerRuc = SyntheticIdentifiers.RUC,
                environmentCode = "1",
                establishment = "001",
                emissionPoint = "002",
                sequential = sequential,
                numericCode = "12345678",
                emissionTypeCode = "1",
            )
        ) {
            is Outcome.Ok -> outcome.value.digits
            is Outcome.Err -> throw AssertionError("the fixture key must build: ${outcome.error}")
        }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun envelope(namespace: String, operation: String, body: String): String =
        XML_DECLARATION +
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" +
            "<ns2:$operation xmlns:ns2=\"$namespace\">$body</ns2:$operation></soap:Body></soap:Envelope>"

    private fun message(identifier: String, text: String, type: String, additional: String? = null): String =
        "<mensaje><identificador>$identifier</identificador><mensaje>$text</mensaje>" +
            (if (additional != null) "<informacionAdicional>$additional</informacionAdicional>" else "") +
            "<tipo>$type</tipo></mensaje>"

    private fun receptionBody(wrapper: String, inner: String): String =
        envelope(RECEPTION_NAMESPACE, "validarComprobanteResponse", "<$wrapper>$inner</$wrapper>")

    private fun authorizationBody(
        count: String? = "1",
        entries: String?,
        queried: String = "\n$accessKey\n",
        wrapperAttributes: String = "",
    ): ByteArray = bytes(
        envelope(
            AUTHORIZATION_NAMESPACE,
            "autorizacionComprobanteResponse",
            "<RespuestaAutorizacionComprobante$wrapperAttributes>" +
                "<claveAccesoConsultada>$queried</claveAccesoConsultada>" +
                (if (count != null) "<numeroComprobantes>$count</numeroComprobantes>" else "") +
                (if (entries != null) "<autorizaciones>$entries</autorizaciones>" else "") +
                "</RespuestaAutorizacionComprobante>",
        ),
    )

    private fun entry(
        state: String,
        date: String? = SAMPLE_DATE,
        number: String? = "\n$accessKey\n",
        comprobante: String? = comprobanteCdata,
        messages: String = "",
    ): String =
        "<autorizacion><estado>$state</estado>" +
            (if (number != null) "<numeroAutorizacion>$number</numeroAutorizacion>" else "") +
            (if (date != null) "<fechaAutorizacion>$date</fechaAutorizacion>" else "") +
            "<ambiente>PRUEBAS</ambiente>" +
            (if (comprobante != null) "<comprobante>$comprobante</comprobante>" else "") +
            "<mensajes>$messages</mensajes></autorizacion>"

    private val warning60: String =
        message("60", "\nESTE PROCESO FUE REALIZADO EN EL\nAMBIENTE DE PRUEBAS\n", "ADVERTENCIA")

    // --- Reception ------------------------------------------------------------------------

    /** Ficha v2.34 p.17, "Recepcion exitosa": `RECIBIDA` with an empty `comprobantes`. */
    fun receptionReceived(): ByteArray =
        bytes(receptionBody("RespuestaRecepcionComprobante", "<estado>RECIBIDA</estado><comprobantes/>"))

    /**
     * The same answer with the wrapper named after the Java return type (`RespuestaSolicitud`)
     * instead of the XML element the Ficha shows (p.17). The parser keys on neither name.
     */
    fun receptionReceivedRespuestaSolicitud(): ByteArray =
        bytes(receptionBody("RespuestaSolicitud", "<estado>RECIBIDA</estado><comprobantes/>"))

    /** Quirk reported by third-party developers (unverified): `estado` alone, no `comprobantes` element. */
    fun receptionBareReceived(): ByteArray =
        bytes(receptionBody("RespuestaRecepcionComprobante", "<estado>RECIBIDA</estado>"))

    /**
     * Ficha v2.34 p.17-18, "Recepcion fallida": `DEVUELTA`, one `comprobante` without a
     * `claveAcceso`, message 35 of type ERROR with a synthetic `informacionAdicional`.
     */
    fun receptionReturned(): ByteArray = bytes(
        receptionBody(
            "RespuestaRecepcionComprobante",
            "<estado>DEVUELTA</estado><comprobantes><comprobante><mensajes>" +
                message("35", "DOCUMENTO INVÁLIDO", "ERROR", "synthetic detail: a field fails its pattern") +
                "</mensajes></comprobante></comprobantes>",
        ),
    )

    /** The structure-only shape of Phase 2 (`claveAcceso` present), with one message. */
    fun receptionReturnedWithKey(): ByteArray = bytes(
        receptionBody(
            "RespuestaRecepcionComprobante",
            "<estado>DEVUELTA</estado><comprobantes><comprobante><claveAcceso>$accessKey</claveAcceso><mensajes>" +
                message("43", "CLAVE ACCESO REGISTRADA", "ERROR") +
                "</mensajes></comprobante></comprobantes>",
        ),
    )

    /** Quirk reported by third-party developers (unverified): `DEVUELTA` with an empty `comprobantes` and no message. */
    fun receptionReturnedEmpty(): ByteArray =
        bytes(receptionBody("RespuestaRecepcionComprobante", "<estado>DEVUELTA</estado><comprobantes></comprobantes>"))

    /** A reception answer whose `estado` is not one the SRI documents. */
    fun receptionUnknownState(): ByteArray =
        bytes(receptionBody("RespuestaRecepcionComprobante", "<estado> en  cola </estado><comprobantes/>"))

    // --- Authorization --------------------------------------------------------------------

    /**
     * Ficha v2.34 p.18-19, "Comprobante Autorizado": padded `claveAccesoConsultada` and
     * `numeroAutorizacion`, `ambiente` PRUEBAS, a CDATA comprobante with an XML declaration, a
     * comment and a literal `<autorizacion>`, and warning 60 (ADVERTENCIA) whose text carries
     * line breaks. No `informacionAdicional`.
     */
    fun authorizationAuthorized(): ByteArray =
        authorizationBody(entries = entry("AUTORIZADO", messages = warning60))

    /**
     * Ficha v2.34 p.19, "Comprobante No Autorizado": `RECHAZADO`, no `numeroAutorizacion`, and
     * message 46 with padded text.
     */
    fun authorizationRejected(): ByteArray = authorizationBody(
        entries = entry("RECHAZADO", number = null, messages = message("46", " RUC no existe ", "ERROR")),
    )

    /** The same authorization with the comprobante escaped instead of in CDATA. */
    fun authorizationEscaped(): ByteArray =
        authorizationBody(entries = entry("AUTORIZADO", comprobante = comprobanteEscaped, messages = warning60))

    /**
     * Quirk reported by third-party developers (unverified): three entries for one key,
     * `NO AUTORIZADO`, `AUTORIZADO`, `RECHAZADO`, each with its own `numeroAutorizacion` text.
     */
    fun authorizationRepeated(): ByteArray = authorizationBody(
        count = "3",
        entries = entry("NO AUTORIZADO", number = "first-entry") +
            entry("AUTORIZADO", number = "second-entry") +
            entry("RECHAZADO", number = "third-entry"),
    )

    /**
     * Quirk (unverified): no entry is authorized and the dates are out of order. The latest is
     * entry 1, which is neither first nor last.
     */
    fun authorizationNoneAuthorizedOutOfOrder(): ByteArray = authorizationBody(
        count = "3",
        entries = entry("RECHAZADO", date = "2026-10-27T10:00:00.000-05:00") +
            entry("NO AUTORIZADO", date = "2026-10-27T14:00:00.000-05:00") +
            entry("EN PROCESO", date = "2026-10-27T08:00:00.000-05:00"),
    )

    /** The same entries with one unparseable date, so the last entry in SRI order decides. */
    fun authorizationNoneAuthorizedOneBadDate(): ByteArray = authorizationBody(
        count = "3",
        entries = entry("RECHAZADO", date = "not a date") +
            entry("NO AUTORIZADO", date = "2026-10-27T14:00:00.000-05:00") +
            entry("EN PROCESO", date = "2026-10-27T08:00:00.000-05:00"),
    )

    /** Two entries with the same instant written in different offsets; the later in SRI order wins. */
    fun authorizationNoneAuthorizedTiedDates(): ByteArray = authorizationBody(
        count = "2",
        entries = entry("RECHAZADO", date = "2026-10-27T10:00:00.000-05:00") +
            entry("EN PROCESO", date = "2026-10-27T15:00:00.000Z"),
    )

    /** Quirk (unverified): `numeroComprobantes` 0 and an empty `autorizaciones`. */
    fun authorizationEmpty(): ByteArray = authorizationBody(count = "0", entries = "")

    /** Quirk (unverified): `numeroComprobantes` 0 and no `autorizaciones` element at all. */
    fun authorizationAbsent(): ByteArray = authorizationBody(count = "0", entries = null)

    /** An `AUTORIZADO` entry with no `numeroAutorizacion`, as a truncated answer might be. */
    fun authorizationAuthorizedWithoutNumber(): ByteArray =
        authorizationBody(entries = entry("AUTORIZADO", number = null))

    /** An `AUTORIZADO` entry with no `comprobante` element. */
    fun authorizationAuthorizedWithoutComprobante(): ByteArray =
        authorizationBody(entries = entry("AUTORIZADO", comprobante = null))

    /** An `AUTORIZADO` entry whose `comprobante` element is the self-closing `<comprobante/>`. */
    fun authorizationAuthorizedWithSelfClosingComprobante(): ByteArray = authorizationBody(
        entries = entry("AUTORIZADO", comprobante = "").replace("<comprobante></comprobante>", "<comprobante/>"),
    )

    /** An `AUTORIZADO` entry whose `comprobante` element holds exactly [content], which may be empty. */
    fun authorizationAuthorizedWithComprobanteContent(content: String): ByteArray =
        authorizationBody(entries = entry("AUTORIZADO", comprobante = content))

    /** An incomplete `AUTORIZADO` entry followed by a complete one. */
    fun authorizationIncompleteThenComplete(): ByteArray = authorizationBody(
        count = "2",
        entries = entry("AUTORIZADO", number = null) + entry("AUTORIZADO"),
    )

    /** One entry whose `estado` is [state] as written, for the unknown and lenient-spelling cases. */
    fun authorizationWithState(state: String): ByteArray =
        authorizationBody(entries = entry(state, number = null))

    /** One entry whose `fechaAutorizacion` is [date] as written; `dd/MM/yyyy HH:mm:ss` is one known form. */
    fun authorizationWithDate(date: String): ByteArray =
        authorizationBody(entries = entry("AUTORIZADO", date = date))

    /** A CDATA comprobante with CR LF inside it, which XML line-end normalisation would rewrite. */
    fun authorizationCrLf(): ByteArray = authorizationBody(
        entries = entry(
            "AUTORIZADO",
            comprobante = "<![CDATA[$comprobanteText\r\n<!-- second line -->\r\n]]>",
        ),
    )

    /** An escaped comprobante of about 1 MiB, far above the Ficha's 320 KiB. */
    fun authorizationLarge(): ByteArray {
        val line = "<detalle><descripcion>synthetic line of filler text</descripcion></detalle>"
        val target = 1024 * 1024
        val lines = (target - escape(comprobanteText).length) / escape(line).length + 1
        return authorizationBody(entries = entry("AUTORIZADO", comprobante = escape(comprobanteText + line.repeat(lines))))
    }

    /** `numeroComprobantes` says 2 and one entry is present. */
    fun authorizationCountMismatch(): ByteArray =
        authorizationBody(count = "2", entries = entry("AUTORIZADO"))

    /** A default namespace declared on the response wrapper, which the `autorizacion` slice would inherit. */
    fun authorizationInheritingNamespace(): ByteArray =
        authorizationBody(
            entries = entry("AUTORIZADO"),
            wrapperAttributes = " xmlns=\"http://example.com/synthetic\"",
        )

    /** An `autorizacion` outside `autorizaciones`, which the structural read does not count. */
    fun authorizationStrayEntry(): ByteArray = authorizationBody(
        count = "0",
        entries = "",
    ).let { String(it, Charsets.UTF_8) }
        .replace("</RespuestaAutorizacionComprobante>", entry("AUTORIZADO") + "</RespuestaAutorizacionComprobante>")
        .let { bytes(it) }

    // --- Transport-class answers ------------------------------------------------------------

    /** A SOAP 1.1 fault, `faultcode` and `faultstring` unqualified. */
    fun soapFault(): ByteArray = bytes(
        XML_DECLARATION +
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>" +
            "<faultcode>soap:Server</faultcode><faultstring> synthetic server failure </faultstring>" +
            "</soap:Fault></soap:Body></soap:Envelope>",
    )

    /** A SOAP 1.2 fault, `Code/Value` and `Reason/Text`. */
    fun soap12Fault(): ByteArray = bytes(
        XML_DECLARATION +
            "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\"><env:Body><env:Fault>" +
            "<env:Code><env:Value>env:Receiver</env:Value></env:Code>" +
            "<env:Reason><env:Text xml:lang=\"en\">synthetic receiver failure</env:Text></env:Reason>" +
            "</env:Fault></env:Body></env:Envelope>",
    )

    /** Well-formed SOAP whose body is neither of the two expected responses. */
    fun unexpectedBody(): ByteArray =
        bytes(envelope("http://example.com/synthetic", "somethingElseResponse", "<result>synthetic</result>"))

    /** Well-formed XML that is not a SOAP envelope at all. */
    fun notSoap(): ByteArray = bytes("$XML_DECLARATION<page><title>synthetic</title></page>")

    /** A response cut off in the middle of an element. */
    fun notWellFormed(): ByteArray =
        bytes(String(receptionReceived(), Charsets.UTF_8).dropLast(40))

    /** A DOCTYPE with nested entities, the classic expansion payload (small: the guard refuses it unread). */
    fun billionLaughs(): ByteArray = bytes(
        XML_DECLARATION +
            "<!DOCTYPE bomb [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">" +
            "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">]>" +
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>&c;</soap:Body></soap:Envelope>",
    )

    /** A DOCTYPE declaring an external entity that points at a synthetic path; nothing is ever fetched. */
    fun externalEntity(): ByteArray = bytes(
        XML_DECLARATION +
            "<!DOCTYPE probe [<!ENTITY external SYSTEM \"file:///nonexistent/synthetic\">]>" +
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>&external;</soap:Body></soap:Envelope>",
    )

    /** A body of [depth] nested elements, beyond what any response needs. */
    fun nestedTooDeep(depth: Int): ByteArray = bytes(
        XML_DECLARATION +
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" +
            "<a>".repeat(depth) + "</a>".repeat(depth) + "</soap:Body></soap:Envelope>",
    )

    /** A body of [count] sibling elements, beyond what any response needs. */
    fun tooManyElements(count: Int): ByteArray = bytes(
        XML_DECLARATION +
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" +
            "<a/>".repeat(count) + "</soap:Body></soap:Envelope>",
    )
}
