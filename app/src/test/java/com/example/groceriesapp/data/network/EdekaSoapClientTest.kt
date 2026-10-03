package com.example.groceriesapp.data.network

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EdekaSoapClientTest {
    private val client = EdekaSoapClient()

    @Test
    fun receiptListParsesMoneyCurrencyAndShop() {
        val entries = client.parseReceiptList(
            soap(
                """
                <getAllReceiptsByAppResponse>
                  <receipts><receipt>
                    <id>42</id><dateAndTime>2026-08-27T18:24:00Z</dateAndTime>
                    <sum>12.34</sum><currency><code>EUR</code><symbol>€</symbol></currency>
                    <retailerName>EDEKA</retailerName><shopName>EDEKA Testmarkt</shopName>
                    <shopStreet>Musterweg 1</shopStreet><shopZipCode>10115</shopZipCode><shopCity>Berlin</shopCity>
                  </receipt></receipts>
                </getAllReceiptsByAppResponse>
                """,
            ),
        )

        assertEquals(1, entries.size)
        assertEquals(42, entries.single().id)
        assertEquals(1234, entries.single().totalCents)
        assertEquals("EUR", entries.single().currency)
        assertEquals("EDEKA Testmarkt", entries.single().merchantName)
    }

    @Test
    fun serviceDiscoveryParsesRequiredHttpsEndpoints() {
        val endpoints = client.parseEndpoints(
            """
            <UpdateResult><mobileApplicationWebServices>
              <mobileApplicationWebService id="SSO_SERVICE"><wsEndpoint>https://example.test/sso</wsEndpoint></mobileApplicationWebService>
              <mobileApplicationWebService id="RECEIPT_SERVICE"><wsEndpoint>https://example.test/receipts</wsEndpoint></mobileApplicationWebService>
            </mobileApplicationWebServices></UpdateResult>
            """.trimIndent().toByteArray(),
        )

        assertEquals("https://example.test/sso", endpoints["SSO_SERVICE"])
        assertEquals("https://example.test/receipts", endpoints["RECEIPT_SERVICE"])
    }

    @Test
    fun registrationFieldsStayInServerRequiredOrder() {
        val xml = client.applicationRegistrationXml("installation", "registrationRequest")
        val fields = listOf(
            "mobileApplication",
            "brand",
            "applicationVersion",
            "platform",
            "platformVersion",
            "imei",
            "serialNumber",
            "manufacturer",
            "model",
            "countryAlpha2",
            "languageAlpha2",
        )

        val positions = fields.map { xml.indexOf("<$it>") }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun receiptRequestUsesValuephoneHeadersAndSoapOperation() {
        var captured: okhttp3.Request? = null
        val fakeHttp = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(soap("<getAllReceiptsByAppResponse><receipts/></getAllReceiptsByAppResponse>").toResponseBody())
                .build()
        }.build()
        EdekaSoapClient(fakeHttp).getReceipts(
            EdekaSoapClient.Session("https://example.test/receipts", "Basic redacted"),
            limit = 50,
            offset = 0,
        )

        val request = requireNotNull(captured)
        assertEquals("getAllReceiptsByApp", request.header("SOAPAction"))
        assertEquals("OVERALLKBP", request.header("X-Application-Type"))
        assertEquals("Basic redacted", request.header("Authorization"))
        assertTrue(request.header("User-Agent").orEmpty().contains("EDEKA; 5.11.0"))
        val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
        assertTrue(body.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(body.contains("<op:getAllReceiptsByApp"))
        assertTrue(body.contains("<limit>50</limit><offset>0</offset>"))
    }

    @Test
    fun formattedReceiptParsesTypedLinesAndCanceledFlag() {
        val detail = client.parseFormattedReceipt(
            soap(
                """
                <getFormattedReceiptResponse><formattedReceipt>
                  <id>42</id><sum>1.29</sum><canceled>true</canceled>
                  <currency><symbol>€</symbol></currency>
                  <lineItems>
                    <item><lineType>NORMAL</lineType><leftSide>Bio Milch</leftSide><rightSide>1,49 €</rightSide></item>
                    <item><lineType>DISCOUNT</lineType><leftSide>Rabatt</leftSide><rightSide>-0,20 €</rightSide></item>
                  </lineItems>
                </formattedReceipt></getFormattedReceiptResponse>
                """,
            ),
            receiptId = 42,
        )

        assertTrue(detail.canceled)
        assertEquals(129, detail.totalCents)
        assertEquals(EdekaReceiptLineType.NORMAL, detail.lines.first().type)
        assertEquals(EdekaReceiptLineType.DISCOUNT, detail.lines.last().type)
    }

    /**
     * `SEPARATOR` items arrive with neither `leftSide` nor `rightSide`, exactly as the live backend
     * sends them, and have to survive parsing: they are the only marker of where one rung-up
     * position ends, which the item mapping groups multiplier and coupon lines by.
     */
    @Test
    fun textlessSeparatorLinesSurviveParsing() {
        val detail = client.parseFormattedReceipt(
            soap(
                """
                <getFormattedReceiptResponse><formattedReceipt>
                  <id>756442500</id><sum>0.58</sum><canceled>false</canceled>
                  <currency><symbol>€</symbol></currency>
                  <lineItems>
                    <item><lineType>NORMAL</lineType><leftSide>2.000 x 0,29€</leftSide></item>
                    <item><lineType>NORMAL</lineType><leftSide>Harry Dopp.Bröt.</leftSide><rightSide>0,58€</rightSide></item>
                    <item><lineType>SEPARATOR</lineType></item>
                  </lineItems>
                </formattedReceipt></getFormattedReceiptResponse>
                """,
            ),
            receiptId = 756442500,
        )

        assertEquals(
            listOf(
                EdekaReceiptLineType.NORMAL,
                EdekaReceiptLineType.NORMAL,
                EdekaReceiptLineType.SEPARATOR,
            ),
            detail.lines.map(EdekaFormattedReceiptLine::type),
        )
        assertEquals("2.000 x 0,29€", detail.lines.first().left)
    }

    @Test
    fun soapFaultIsSurfaced() {
        val error = assertThrows(java.io.IOException::class.java) {
            client.parseReceiptList(
                soap("<soap:Fault><faultstring>Receipt service unavailable</faultstring></soap:Fault>"),
            )
        }

        assertTrue(error.message.orEmpty().contains("unavailable"))
    }

    @Test
    fun doctypesAreRejected() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY y SYSTEM="file:///etc/passwd">]><x>&y;</x>"""

        assertThrows(Exception::class.java) { client.parseXml(xml.toByteArray()) }
    }

    private fun soap(body: String): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Body>$body</soap:Body>
        </soap:Envelope>
    """.trimIndent().toByteArray()
}
