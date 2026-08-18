package com.example.groceriesapp.data.network

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenFoodFactsProductMapperTest {

    @Test
    fun `reads the German name and the structured quantity`() {
        val json = """
            {
              "status": 1,
              "code": "4008400202020",
              "product": {
                "code": "4008400202020",
                "product_name_de": "Haferdrink Natur",
                "product_name": "Oat Drink",
                "quantity": "1 L",
                "product_quantity": "1000",
                "product_quantity_unit": "ml"
              }
            }
        """.trimIndent()

        val product = OpenFoodFactsProductMapper.map(json, "4008400202020")

        assertEquals("4008400202020", product.barcode)
        assertEquals("Haferdrink Natur", product.name)
        // The structured pair wins over the free-text "1 L".
        assertEquals(1000.0, product.quantity!!, 0.001)
        assertEquals("ml", product.unit)
    }

    @Test
    fun `falls back through the name fields to the brand`() {
        assertEquals("Oat Drink", nameOf(""""product_name": "Oat Drink""""))
        assertEquals("Hafer", nameOf(""""generic_name_de": "Hafer""""))
        assertEquals("Haferbasis", nameOf(""""generic_name": "Haferbasis""""))
        assertEquals("Alnatura", nameOf(""""brands": "Alnatura, Alnatura Bio""""))
    }

    @Test
    fun `explicit JSON nulls do not become the string null`() {
        // Android's optString hands back "null" here, so this is the case that would ship a row
        // literally called "null" while every other assertion still passed.
        val json = """
            {
              "status": 1,
              "product": {
                "product_name_de": null,
                "product_name": null,
                "generic_name_de": null,
                "generic_name": null,
                "brands": null,
                "quantity": null
              }
            }
        """.trimIndent()

        val product = OpenFoodFactsProductMapper.map(json, "1234567890123")

        assertNull(product.name)
        assertNull(product.quantity)
        assertNull(product.unit)
        assertEquals("1234567890123", product.barcode)
    }

    @Test
    fun `parses the free-text quantities contributors actually type`() {
        assertEquals(500.0 to "g", OpenFoodFactsProductMapper.parseQuantityText("500 g"))
        assertEquals(1.5 to "l", OpenFoodFactsProductMapper.parseQuantityText("1,5 l"))
        assertEquals(1.0 to "l", OpenFoodFactsProductMapper.parseQuantityText("1L"))
        assertEquals(250.0 to "g", OpenFoodFactsProductMapper.parseQuantityText("250Gramm"))
        // 6 x 33 cl is 198 cl, and cl folds onto ml.
        assertEquals(1980.0 to "ml", OpenFoodFactsProductMapper.parseQuantityText("6 x 33 cl"))
        assertEquals(2.0 to "kg", OpenFoodFactsProductMapper.parseQuantityText("2 kg"))
    }

    @Test
    fun `leaves unparseable quantities untracked`() {
        assertNull(OpenFoodFactsProductMapper.parseQuantityText("1 Packung"))
        assertNull(OpenFoodFactsProductMapper.parseQuantityText("ca. 4 Stück"))
        assertNull(OpenFoodFactsProductMapper.parseQuantityText(""))
        assertNull(OpenFoodFactsProductMapper.parseQuantityText(null))
        assertNull(OpenFoodFactsProductMapper.parseQuantityText("0 g"))
    }

    @Test
    fun `falls back to the free-text quantity when the structured pair is incomplete`() {
        val json = """
            {
              "status": 1,
              "product": {
                "product_name": "Butter",
                "quantity": "250 g",
                "product_quantity": "250"
              }
            }
        """.trimIndent()

        val product = OpenFoodFactsProductMapper.map(json, "1")

        assertEquals(250.0, product.quantity!!, 0.001)
        assertEquals("g", product.unit)
    }

    @Test
    fun `an unknown barcode is not an error`() {
        val json = """{"status": 0, "status_verbose": "product not found"}"""

        val failure = assertThrows(ProductNotFoundException::class.java) {
            OpenFoodFactsProductMapper.map(json, "0000000000000")
        }
        assertEquals("0000000000000", failure.barcode)
    }

    @Test
    fun `a non-JSON body is rejected`() {
        assertThrows(JSONException::class.java) {
            OpenFoodFactsProductMapper.map("<html>rate limited</html>", "4008400202020")
        }
    }

    private fun nameOf(field: String): String? = OpenFoodFactsProductMapper
        .map("""{"status": 1, "product": {$field}}""", "1")
        .name
}
