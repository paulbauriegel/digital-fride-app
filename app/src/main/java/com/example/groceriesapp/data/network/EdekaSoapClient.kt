package com.example.groceriesapp.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.SAXException

class EdekaUnauthorizedException(message: String) : IOException(message)

class EdekaSoapClient(
    private val client: OkHttpClient = defaultClient(),
) {
    data class Session(
        val receiptEndpoint: String,
        val authorization: String,
    )

    companion object {
        private const val BRAND = "EDEKA"
        private const val APPLICATION_TYPE = "OVERALLKBP"
        private const val PLATFORM = "ANDROID"
        private const val PLATFORM_VERSION = "34"
        private const val APP_VERSION = "5.11.0"
        private const val SDK_VERSION = "6.93.0"
        private const val SERVICE_UPDATER =
            "https://www.myvaluephone.com/vpserver/web/servlet/serviceUpdater"
        private const val SSO_SERVICE = "SSO_SERVICE"
        private const val RECEIPT_SERVICE = "RECEIPT_SERVICE"
        private const val DEVICE_REGISTRATION_SERVICE = "MOBILE_DEVICE_REGISTRATION_SERVICE"
        private const val SOAP_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val SSO_NAMESPACE = "http://sso.mobile.vpserverapp.valuephone.com/"
        private const val RECEIPT_NAMESPACE = "http://receipt.mobile.server.valuephone.com/"
        private const val REGISTRATION_NAMESPACE =
            "http://registration.open.mobile.server.valuephone.com/"
        private val XML_MEDIA_TYPE = "text/xml; charset=UTF-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun establishSession(accessToken: String, installationId: String): Session {
        val endpoints = discoverEndpoints()
        val ssoEndpoint = endpoints[SSO_SERVICE]
            ?: throw IOException("EDEKA service discovery omitted the SSO service")
        val receiptEndpoint = endpoints[RECEIPT_SERVICE]
            ?: throw IOException("EDEKA service discovery omitted the receipt service")
        val bootstrap = endpoints[DEVICE_REGISTRATION_SERVICE]?.let { endpoint ->
            runCatching { registerAnonymous(endpoint, installationId) }
                .getOrNull()
                ?.let { basicAuth(it.first, it.second) }
        }
        val credentials = ssoLogin(ssoEndpoint, accessToken, installationId, bootstrap)
        return Session(receiptEndpoint, basicAuth(credentials.first, credentials.second))
    }

    internal fun discoverEndpoints(): Map<String, String> {
        val url = SERVICE_UPDATER.toHttpUrl().newBuilder()
            .addQueryParameter("aid", APPLICATION_TYPE)
            .addQueryParameter("v", APP_VERSION)
            .addQueryParameter("p", PLATFORM)
            .addQueryParameter("pv", PLATFORM_VERSION)
            .addQueryParameter("b", BRAND)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent()).get().build()
        val xml = execute(request)
        return parseEndpoints(xml)
    }

    internal fun parseEndpoints(xml: ByteArray): Map<String, String> {
        val document = parseXml(xml)
        val result = linkedMapOf<String, String>()
        document.documentElement.walkElements().forEach { element ->
            if (element.localTag !in setOf("mobileApplicationWebService", "systemServlet")) {
                return@forEach
            }
            val id = element.getAttribute("id").takeIf(String::isNotBlank)
                ?: element.childText("id")
            val endpoint = element.childText("wsEndpoint") ?: element.childText("url")
            if (id != null && endpoint != null) {
                val parsed = endpoint.toHttpUrl()
                if (!parsed.isHttps) throw IOException("EDEKA returned a non-HTTPS service endpoint")
                result[id] = endpoint
            }
        }
        if (result.isEmpty()) throw IOException("EDEKA service discovery returned no endpoints")
        return result
    }

    private fun registerAnonymous(endpoint: String, installationId: String): Pair<String, String> {
        val inner = """
            <op:registerMobileApplicationForAnonymousUser xmlns:op="$REGISTRATION_NAMESPACE">
              ${applicationRegistrationXml(installationId, "registrationRequest")}
            </op:registerMobileApplicationForAnonymousUser>
        """.trimIndent()
        return parseCredentials(postSoap(endpoint, "registerMobileApplicationForAnonymousUser", inner, null))
    }

    private fun ssoLogin(
        endpoint: String,
        accessToken: String,
        installationId: String,
        bootstrapAuth: String?,
    ): Pair<String, String> {
        val inner = """
            <op:login xmlns:op="$SSO_NAMESPACE">
              <mobileSSOContext><brandId>$BRAND</brandId><accessToken>${escape(accessToken)}</accessToken></mobileSSOContext>
              ${deviceRegistrationXml(installationId)}
              ${applicationRegistrationXml(installationId, "mobileApplicationRegistrationRequest")}
            </op:login>
        """.trimIndent()
        return parseCredentials(postSoap(endpoint, "login", inner, bootstrapAuth))
    }

    fun getReceipts(
        session: Session,
        limit: Int,
        offset: Int,
    ): List<EdekaReceiptEntry> {
        val inner = """
            <op:getAllReceiptsByApp xmlns:op="$RECEIPT_NAMESPACE">
              ${mobileContextXml()}
              <limit>$limit</limit><offset>$offset</offset>
            </op:getAllReceiptsByApp>
        """.trimIndent()
        return parseReceiptList(postSoap(
            session.receiptEndpoint,
            "getAllReceiptsByApp",
            inner,
            session.authorization,
        ))
    }

    internal fun parseReceiptList(xml: ByteArray): List<EdekaReceiptEntry> {
        val document = parseSoap(xml)
        return document.documentElement.walkElements()
            .filter { it.localTag == "receipt" }
            .mapNotNull(::parseReceiptEntry)
            .toList()
    }

    fun getFormattedReceipt(session: Session, receiptId: Int): EdekaFormattedReceipt {
        val inner = """
            <op:getFormattedReceipt xmlns:op="$RECEIPT_NAMESPACE">
              ${mobileContextXml()}<receiptId>$receiptId</receiptId>
            </op:getFormattedReceipt>
        """.trimIndent()
        return parseFormattedReceipt(postSoap(
            session.receiptEndpoint,
            "getFormattedReceipt",
            inner,
            session.authorization,
        ), receiptId)
    }

    internal fun parseFormattedReceipt(xml: ByteArray, receiptId: Int): EdekaFormattedReceipt {
        val document = parseSoap(xml)
        val formatted = document.documentElement.walkElements()
            .firstOrNull { it.localTag == "formattedReceipt" || it.localTag == "return" }
            ?: throw IOException("EDEKA receipt detail response omitted formattedReceipt")
        val lineItems = formatted.directChildren().firstOrNull { it.localTag == "lineItems" }
        val lines = lineItems?.directChildren().orEmpty().mapNotNull { item ->
            val left = item.childText("leftSide").orEmpty().trim()
            val right = item.childText("rightSide").orEmpty().trim()
            val typeText = item.childText("lineType").orEmpty()
            val type = runCatching { EdekaReceiptLineType.valueOf(typeText.uppercase()) }
                .getOrDefault(EdekaReceiptLineType.UNKNOWN)
            // SEPARATOR items legitimately carry no text at all — they are the boundary between
            // two rung-up positions, which is what [toReceiptItems] groups multiplier, product and
            // coupon lines by. Dropping them as "empty" would flatten the receipt into loose lines.
            if (left.isEmpty() && right.isEmpty() && type != EdekaReceiptLineType.SEPARATOR) {
                return@mapNotNull null
            }
            EdekaFormattedReceiptLine(type = type, left = left, right = right)
        }
        return EdekaFormattedReceipt(
            id = formatted.childText("id")?.toIntOrNull() ?: receiptId,
            canceled = formatted.childText("canceled")?.toBooleanStrictOrNull() ?: false,
            totalCents = moneyToCents(formatted.childText("sum")),
            currency = parseCurrency(formatted),
            retailerName = formatted.childText("retailerName"),
            shopName = formatted.childText("shopName"),
            lines = lines,
        )
    }

    private fun parseReceiptEntry(element: Element): EdekaReceiptEntry? {
        val id = element.childText("id")?.toIntOrNull() ?: return null
        return EdekaReceiptEntry(
            id = id,
            dateAndTime = element.childText("dateAndTime").orEmpty(),
            totalCents = moneyToCents(element.childText("sum")),
            currency = parseCurrency(element),
            retailerName = element.childText("retailerName"),
            shopName = element.childText("shopName"),
            shopStreet = element.childText("shopStreet"),
            shopZipCode = element.childText("shopZipCode"),
            shopCity = element.childText("shopCity"),
        )
    }

    private fun parseCurrency(element: Element): String {
        val currency = element.directChildren().firstOrNull { it.localTag == "currency" }
        return currency?.childText("code")
            ?: currency?.childText("isoCode")
            ?: currency?.childText("symbol")?.let { if (it == "€") "EUR" else it }
            ?: "EUR"
    }

    private fun parseCredentials(xml: ByteArray): Pair<String, String> {
        val document = parseSoap(xml)
        val username = document.documentElement.walkElements()
            .firstOrNull { it.localTag == "username" || it.localTag == "userHash" }
            ?.textContent?.trim()
        val password = document.documentElement.walkElements()
            .firstOrNull { it.localTag == "password" || it.localTag == "passHash" }
            ?.textContent?.trim()
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            throw IOException("EDEKA registration response omitted session credentials")
        }
        return username to password
    }

    private fun postSoap(
        endpoint: String,
        action: String,
        inner: String,
        authorization: String?,
    ): ByteArray {
        val url = endpoint.toHttpUrl()
        if (!url.isHttps) throw IOException("Refusing to send EDEKA credentials over non-HTTPS")
        // Do not trim an interpolated multiline string here. Indentation inside `inner` changes
        // trimIndent's common prefix and can leave spaces before the XML declaration, which the
        // valuephone XMLStreamReader rejects.
        val envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<soap:Envelope xmlns:soap=\"$SOAP_NAMESPACE\"><soap:Body>" +
            inner +
            "</soap:Body></soap:Envelope>"
        val request = Request.Builder()
            .url(url)
            .header("SOAPAction", action)
            .header("User-Agent", userAgent())
            .header("X-VPVersion", "App version : $APP_VERSION SDK version : $SDK_VERSION")
            .header("X-Application-Type", APPLICATION_TYPE)
            .apply { authorization?.let { header("Authorization", it) } }
            .post(envelope.toRequestBody(XML_MEDIA_TYPE))
            .build()
        return try {
            execute(request)
        } catch (error: EdekaUnauthorizedException) {
            throw error
        } catch (error: IOException) {
            throw IOException("EDEKA $action failed: ${error.message.orEmpty()}", error)
        }
    }

    private fun execute(request: Request): ByteArray = client.newCall(request).execute().use { response ->
        val body = response.body?.bytes() ?: throw IOException("Empty response from EDEKA")
        if (response.code == 401 || response.code == 403) {
            throw EdekaUnauthorizedException("EDEKA session expired")
        }
        if (!response.isSuccessful) {
            val fault = soapFaultMessage(body)
            val detail = fault?.let { ": $it" }.orEmpty()
            throw IOException("request failed (HTTP ${response.code})$detail")
        }
        body
    }

    private fun soapFaultMessage(xml: ByteArray): String? = runCatching {
        val document = parseXml(xml)
        document.documentElement.walkElements()
            .firstOrNull { it.localTag == "faultstring" }
            ?.textContent
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(200)
    }.getOrNull()

    private fun parseSoap(xml: ByteArray) = parseXml(xml).also { document ->
        val fault = document.documentElement.walkElements().firstOrNull { it.localTag == "Fault" }
        if (fault != null) {
            val message = fault.childText("faultstring") ?: "EDEKA SOAP fault"
            if (message.contains("auth", ignoreCase = true) ||
                message.contains("credential", ignoreCase = true) ||
                message.contains("session", ignoreCase = true)
            ) {
                throw EdekaUnauthorizedException(message.take(200))
            }
            throw IOException(message.take(200))
        }
    }

    internal fun parseXml(xml: ByteArray) = run {
        // Android's bundled XML parser does not implement every JAXP/Xerces feature and throws
        // while configuring `disallow-doctype-decl`. Reject declarations before parsing so the
        // protection does not depend on optional platform features.
        val declarationProbe = String(xml, StandardCharsets.UTF_8)
            .replace("\u0000", "")
            .uppercase(Locale.ROOT)
        if ("<!DOCTYPE" in declarationProbe || "<!ENTITY" in declarationProbe) {
            throw IOException("EDEKA XML declarations are not allowed")
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External XML entities are not allowed") }
        }.parse(ByteArrayInputStream(xml))
    }

    internal fun applicationRegistrationXml(serial: String, elementName: String) = """
        <$elementName><mobileApplication>$APPLICATION_TYPE</mobileApplication><brand>$BRAND</brand><applicationVersion>$APP_VERSION</applicationVersion><platform>$PLATFORM</platform><platformVersion>$PLATFORM_VERSION</platformVersion><imei>${escape(serial)}</imei><serialNumber>${escape(serial)}</serialNumber><manufacturer>GroceriesApp</manufacturer><model>Android</model><countryAlpha2>DE</countryAlpha2><languageAlpha2>de</languageAlpha2></$elementName>
    """.trimIndent()

    private fun deviceRegistrationXml(serial: String) = """
        <mobileDeviceRegistrationRequest><brand>$BRAND</brand><countryAlpha2>DE</countryAlpha2><phoneName>GroceriesApp</phoneName><serialNumber>${escape(serial)}</serialNumber></mobileDeviceRegistrationRequest>
    """.trimIndent()

    private fun mobileContextXml() =
        "<mobileContext><countryAlpha2>DE</countryAlpha2><languageAlpha2>de</languageAlpha2></mobileContext>"

    private fun userAgent() = "kSOAP/2.0; $PLATFORM_VERSION; $BRAND; $APP_VERSION"

    private fun basicAuth(username: String, password: String): String {
        val encoded = Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
        )
        return "Basic $encoded"
    }

    private fun moneyToCents(value: String?): Int? = value?.trim()?.takeIf(String::isNotEmpty)?.let {
        runCatching {
            BigDecimal(it.replace(',', '.')).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).intValueExact()
        }.getOrNull()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

private val Element.localTag: String
    get() = localName ?: tagName.substringAfter(':')

private fun Element.directChildren(): List<Element> = buildList {
    for (index in 0 until childNodes.length) {
        val child = childNodes.item(index)
        if (child.nodeType == Node.ELEMENT_NODE) add(child as Element)
    }
}

private fun Element.childText(name: String): String? = directChildren()
    .firstOrNull { it.localTag == name }
    ?.textContent
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun Element.walkElements(): Sequence<Element> = sequence {
    yield(this@walkElements)
    directChildren().forEach { child -> yieldAll(child.walkElements()) }
}
