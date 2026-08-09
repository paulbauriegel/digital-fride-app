package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extracts a [Recipe] from a web page's `schema.org/Recipe` JSON-LD.
 *
 * Nearly every recipe site embeds this markup, so the common case needs no AI call at all: the
 * amounts, servings and steps come straight from the publisher rather than being re-derived by a
 * model. [RecipesRepository.importRecipeFromUrl] only falls back to the OpenAI path when this
 * returns null, which also means URL import works with no API key configured.
 *
 * Pure and unit-testable; the HTTP fetch lives in
 * [com.example.groceriesapp.data.network.RecipeUrlClient]. Everything here is tolerant: any shape
 * it doesn't recognise yields null rather than throwing, so a malformed page degrades to the AI
 * fallback instead of failing the import.
 */
object RecipeJsonLd {

    private val SCRIPT_BLOCK = Regex(
        """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Leading quantity: `200`, `1,5`, `1.5`, `1/2`, `½`, or a range `2-3` (low end wins). */
    private val LEADING_AMOUNT = Regex("""^\s*(\d+[.,]?\d*\s*/\s*\d+|\d+[.,]?\d*|[¼½¾⅓⅔⅛])""")

    /** Trailing "(optional)" / ", optional" / "optional", in English and German. */
    private val OPTIONAL_SUFFIX = Regex(
        """[\s,]*[(\[]?\s*(optional|nach belieben|nach wunsch)\s*[)\]]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val VULGAR_FRACTIONS = mapOf(
        '¼' to 0.25, '½' to 0.5, '¾' to 0.75,
        '⅓' to 1.0 / 3.0, '⅔' to 2.0 / 3.0, '⅛' to 0.125,
    )

    /**
     * Measure words that [UnitNormalizer] deliberately leaves as [UnitKind.UNKNOWN] — it only
     * knows units it can convert. They are still units for parsing purposes; [Recipe]'s unit field
     * is free text and unconvertible units already fall back to presence-only matching.
     */
    private val EXTRA_UNITS = setOf(
        "el", "tl", "essloffel", "esslöffel", "teeloffel", "teelöffel",
        "prise", "bund", "packung", "pck", "dose", "glas", "tasse", "becher", "zehe", "zehen",
        "scheibe", "scheiben", "blatt", "blätter", "msp", "schuss", "handvoll",
        "tbsp", "tsp", "cup", "cups", "clove", "cloves", "pinch", "dash", "slice", "slices",
        "can", "jar", "bunch", "package", "oz", "lb", "pint", "quart",
    )

    fun parseFromHtml(
        html: String,
        createdAtEpochMillis: Long,
        idPrefix: String = "user_",
    ): Recipe? {
        for (match in SCRIPT_BLOCK.findAll(html)) {
            val block = match.groupValues[1].trim()
            if (block.isEmpty()) continue
            val recipeNode = runCatching { findRecipeNode(block) }.getOrNull() ?: continue
            val recipe = runCatching {
                toRecipe(recipeNode, createdAtEpochMillis, idPrefix)
            }.getOrNull()
            if (recipe != null) return recipe
        }
        return null
    }

    /**
     * Strips a page down to readable text for the AI fallback. Script and style bodies go first so
     * their contents don't survive as prose.
     */
    fun htmlToPlainText(html: String): String = html
        .replace(Regex("""<(script|style)[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</(p|div|li|h[1-6]|tr)>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), " ")
        .let(::decodeEntities)
        .replace(Regex("""[ \t ]+"""), " ")
        .replace(Regex("""\n\s*\n\s*\n+"""), "\n\n")
        .trim()

    // --- JSON-LD traversal ----------------------------------------------------------------------

    /** Recipe nodes hide inside bare objects, arrays, and `@graph` wrappers depending on the CMS. */
    private fun findRecipeNode(block: String): JSONObject? {
        val trimmed = block.trimStart()
        val roots: List<Any> = when {
            trimmed.startsWith("[") -> JSONArray(block).let { array ->
                List(array.length()) { array.opt(it) }.filterNotNull()
            }
            else -> listOf(JSONObject(block))
        }
        for (root in roots) {
            findRecipeIn(root, depth = 0)?.let { return it }
        }
        return null
    }

    private fun findRecipeIn(node: Any?, depth: Int): JSONObject? {
        if (depth > 4) return null
        when (node) {
            is JSONObject -> {
                if (declaresRecipeType(node)) return node
                node.optJSONArray("@graph")?.let { graph ->
                    for (i in 0 until graph.length()) {
                        findRecipeIn(graph.opt(i), depth + 1)?.let { return it }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findRecipeIn(node.opt(i), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    /** `@type` is either a string or an array of strings; a node may be both Recipe and something else. */
    private fun declaresRecipeType(node: JSONObject): Boolean {
        val type = node.opt("@type") ?: return false
        return when (type) {
            is String -> type.equals("Recipe", ignoreCase = true)
            is JSONArray -> (0 until type.length()).any {
                type.optString(it).equals("Recipe", ignoreCase = true)
            }
            else -> false
        }
    }

    // --- Mapping --------------------------------------------------------------------------------

    private fun toRecipe(
        node: JSONObject,
        createdAtEpochMillis: Long,
        idPrefix: String,
    ): Recipe? {
        val title = decodeEntities(node.optString("name").trim()).takeIf(String::isNotBlank)
            ?: return null
        val ingredients = collectIngredients(node)
        if (ingredients.isEmpty()) return null
        val steps = collectSteps(node)
        if (steps.isEmpty()) return null
        return Recipe(
            id = idPrefix + UUID.randomUUID().toString(),
            title = title,
            ingredients = ingredients,
            steps = steps,
            servings = parseYield(node) ?: 2,
            prepMinutes = parseMinutes(node) ?: 30,
            tags = collectTags(node),
            imageRef = null,
            source = RecipeSource.USER,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    private fun collectIngredients(node: JSONObject): List<RecipeIngredient> {
        val raw = node.optJSONArray("recipeIngredient")
            ?: node.optJSONArray("ingredients")
            ?: return emptyList()
        return buildList {
            for (i in 0 until raw.length()) {
                val line = decodeEntities(raw.optString(i)).trim()
                if (line.isEmpty()) continue
                parseIngredientLine(line)?.let(::add)
            }
        }
    }

    /** `"200 g Mehl"` → amount 200, unit "g", name "Mehl". Never throws; worst case name-only. */
    internal fun parseIngredientLine(line: String): RecipeIngredient? {
        var rest = line.trim()
        if (rest.isEmpty()) return null

        val amountMatch = LEADING_AMOUNT.find(rest)
        val amount = amountMatch?.let { parseAmount(it.groupValues[1]) }
        if (amountMatch != null) rest = rest.removeRange(amountMatch.range).trim()

        // A range like "2-3 Eier" leaves a dangling "-3"; drop the upper bound.
        rest = rest.removePrefix("-").removePrefix("–").trimStart()
        Regex("""^\d+[.,]?\d*\s+""").find(rest)?.let { rest = rest.removeRange(it.range).trimStart() }

        var unit: String? = null
        val firstToken = rest.substringBefore(' ').trim().trimEnd('.')
        if (firstToken.isNotEmpty() && isUnitToken(firstToken)) {
            unit = firstToken
            rest = rest.removePrefix(rest.substringBefore(' ')).trim()
        }

        // Publishers mark these inline ("… (optional)", "… , optional"). Without lifting it onto
        // the flag the matcher reports the ingredient as missing rather than not needed.
        val optional = OPTIONAL_SUFFIX.containsMatchIn(rest)
        if (optional) rest = OPTIONAL_SUFFIX.replace(rest, "")

        val name = rest.trim().trim(',', ';').ifBlank { line.trim() }
        if (name.isEmpty()) return null
        return RecipeIngredient(
            foodConceptId = FoodConceptCatalog.match(name)?.id,
            displayName = name,
            amount = amount?.takeIf { it.isFinite() && it > 0.0 },
            unit = unit,
            optional = optional,
        )
    }

    private fun isUnitToken(token: String): Boolean {
        val lower = token.lowercase()
        if (lower in EXTRA_UNITS) return true
        // UnitNormalizer maps "" to COUNT, so guard against an empty token slipping through.
        return lower.isNotEmpty() && UnitNormalizer.kindOf(lower) != UnitKind.UNKNOWN
    }

    private fun parseAmount(token: String): Double? {
        val cleaned = token.trim()
        if (cleaned.length == 1 && cleaned[0] in VULGAR_FRACTIONS) {
            return VULGAR_FRACTIONS[cleaned[0]]
        }
        if ('/' in cleaned) {
            val (numerator, denominator) = cleaned.split("/", limit = 2)
            val n = numerator.trim().replace(',', '.').toDoubleOrNull() ?: return null
            val d = denominator.trim().replace(',', '.').toDoubleOrNull() ?: return null
            return if (d == 0.0) null else n / d
        }
        return cleaned.replace(',', '.').toDoubleOrNull()
    }

    private fun collectSteps(node: JSONObject): List<String> {
        val instructions = node.opt("recipeInstructions") ?: return emptyList()
        return flattenInstructions(instructions, depth = 0)
            .map { decodeEntities(stripTags(it)).trim() }
            .filter { it.isNotEmpty() }
    }

    private fun flattenInstructions(node: Any?, depth: Int): List<String> {
        if (depth > 3) return emptyList()
        return when (node) {
            // A single blob: publishers newline-separate it, so that is the only safe split.
            is String -> stripTags(node).split('\n').map(String::trim).filter(String::isNotEmpty)
            is JSONArray -> buildList {
                for (i in 0 until node.length()) {
                    addAll(flattenInstructions(node.opt(i), depth + 1))
                }
            }
            is JSONObject -> {
                // HowToSection nests its steps; HowToStep carries the text directly.
                node.optJSONArray("itemListElement")?.let { return flattenInstructions(it, depth + 1) }
                val text = node.optString("text").takeIf(String::isNotBlank)
                    ?: node.optString("name").takeIf(String::isNotBlank)
                text?.let { listOf(it) }.orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun collectTags(node: JSONObject): List<String> = buildList {
        listOf("recipeCategory", "recipeCuisine", "keywords").forEach { key ->
            when (val value = node.opt(key)) {
                is String -> value.split(',').map(String::trim).filter(String::isNotEmpty).forEach(::add)
                is JSONArray -> for (i in 0 until value.length()) {
                    value.optString(i).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
        }
    }.map(::decodeEntities).distinct().take(6)

    /** `recipeYield` is a bare number, `"4 Portionen"`, or a list of either. */
    private fun parseYield(node: JSONObject): Int? {
        val value = node.opt("recipeYield") ?: node.opt("yield") ?: return null
        val text = when (value) {
            is JSONArray -> (0 until value.length()).firstNotNullOfOrNull {
                value.optString(it).takeIf(String::isNotBlank)
            }
            else -> value.toString()
        } ?: return null
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()?.takeIf { it in 1..100 }
    }

    private fun parseMinutes(node: JSONObject): Int? {
        parseIsoDurationMinutes(node.optString("totalTime"))?.let { return it }
        val prep = parseIsoDurationMinutes(node.optString("prepTime")) ?: 0
        val cook = parseIsoDurationMinutes(node.optString("cookTime")) ?: 0
        return (prep + cook).takeIf { it > 0 }
    }

    /** ISO-8601 durations as schema.org uses them: `PT30M`, `PT1H30M`, `P0DT1H`. */
    internal fun parseIsoDurationMinutes(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = Regex(
            """^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?""",
            RegexOption.IGNORE_CASE,
        ).find(text) ?: return null
        val days = match.groupValues[1].toIntOrNull() ?: 0
        val hours = match.groupValues[2].toIntOrNull() ?: 0
        val minutes = match.groupValues[3].toIntOrNull() ?: 0
        return (days * 24 * 60 + hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun stripTags(value: String): String =
        value.replace(Regex("""<[^>]+>"""), " ").replace(Regex("""[ \t ]+"""), " ")

    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        var result = value
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
        // Numeric entities are common in German recipe titles (e.g. &#228; for ä).
        result = Regex("""&#(\d{2,5});""").replace(result) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        return result
    }
}
