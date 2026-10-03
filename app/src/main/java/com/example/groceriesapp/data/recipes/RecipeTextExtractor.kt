package com.example.groceriesapp.data.recipes

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Turns the raw text of a photographed recipe into a structured [Recipe] draft. Mirrors
 * `vision/parsing/ReceiptItemExtractor`: a rules engine that always works, an AI engine that
 * works better, and a wrapper that makes sure a scan never fails because of the AI one.
 */
fun interface RecipeTextExtractor {
    /** Null when the text does not read as a recipe at all. */
    suspend fun extract(rawText: String, nowEpochMillis: Long): Recipe?
}

/**
 * Heuristic parser for cookbook pages and recipe cards, German or English. Good enough to
 * prefill the review screen; the user corrects the rest. Pure and deterministic.
 */
class RulesRecipeTextExtractor : RecipeTextExtractor {

    override suspend fun extract(rawText: String, nowEpochMillis: Long): Recipe? =
        parse(rawText, nowEpochMillis)

    fun parse(rawText: String, nowEpochMillis: Long): Recipe? {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val ingredientsHeader = lines.indexOfFirst { INGREDIENTS_HEADER.containsMatchIn(it) }
        val stepsHeader = lines.indexOfFirst { STEPS_HEADER.containsMatchIn(it) }

        val ingredientLines = mutableListOf<String>()
        val stepLines = mutableListOf<String>()
        val otherLines = mutableListOf<String>()

        if (ingredientsHeader >= 0 || stepsHeader >= 0) {
            // Sections in either order: each block runs from its header to the next header.
            val headers = listOf(ingredientsHeader, stepsHeader).filter { it >= 0 }.sorted()
            fun blockEnd(start: Int) = headers.firstOrNull { it > start } ?: lines.size
            otherLines += lines.subList(0, headers.first())
            if (stepsHeader >= 0) stepLines += lines.subList(stepsHeader + 1, blockEnd(stepsHeader))
            if (ingredientsHeader >= 0) {
                lines.subList(ingredientsHeader + 1, blockEnd(ingredientsHeader)).forEach { line ->
                    // A page with only a "Zutaten" header still has its method below the list.
                    val isStep = NUMBERED_STEP.containsMatchIn(line) ||
                        (!looksLikeIngredient(line) && line.looksLikeProse())
                    if (isStep) stepLines += line else ingredientLines += line
                }
            }
        } else {
            var seenIngredient = false
            lines.forEach { line ->
                when {
                    NUMBERED_STEP.containsMatchIn(line) -> stepLines += line
                    looksLikeIngredient(line) -> {
                        seenIngredient = true
                        ingredientLines += line
                    }
                    seenIngredient && line.looksLikeProse() -> stepLines += line
                    else -> otherLines += line
                }
            }
        }

        val ingredients = ingredientLines
            .map { it.trimStart('-', '•', '*', '·', '–', ' ') }
            .filter { it.isNotEmpty() && !META_LINE.containsMatchIn(it) }
            .mapNotNull { RecipeJsonLd.parseIngredientLine(it) }
        val steps = mergeSteps(stepLines)
        if (ingredients.isEmpty() && steps.isEmpty()) return null

        val metaText = (otherLines + lines).joinToString("\n")
        val title = otherLines
            .firstOrNull { it.length <= MAX_TITLE_LENGTH && !META_LINE.containsMatchIn(it) && !looksLikeIngredient(it) }
            ?.trimEnd(':')
            ?: lines.first().take(MAX_TITLE_LENGTH)

        return Recipe(
            id = "user_" + UUID.randomUUID(),
            title = title.ifBlank { "Scanned recipe" },
            ingredients = ingredients,
            steps = steps,
            servings = parseServings(metaText) ?: DEFAULT_SERVINGS,
            prepMinutes = parseMinutes(metaText) ?: DEFAULT_MINUTES,
            source = RecipeSource.USER,
            createdAtEpochMillis = nowEpochMillis,
        )
    }

    private fun looksLikeIngredient(line: String): Boolean {
        if (line.length > MAX_INGREDIENT_LENGTH) return false
        if (NUMBERED_STEP.containsMatchIn(line)) return false
        return BULLET.containsMatchIn(line) || LEADING_QUANTITY.containsMatchIn(line)
    }

    /** Numbered lines start steps; unnumbered continuation lines join the step before them. */
    private fun mergeSteps(lines: List<String>): List<String> {
        val steps = mutableListOf<String>()
        lines.forEach { raw ->
            val numbered = NUMBERED_STEP.find(raw)
            val text = if (numbered != null) raw.removeRange(numbered.range).trim() else raw
            if (text.isEmpty()) return@forEach
            val previous = steps.lastOrNull()
            if (numbered == null && previous != null && !previous.endsWithSentence()) {
                steps[steps.lastIndex] = "$previous $text"
            } else {
                steps += text
            }
        }
        return steps
    }

    private fun String.endsWithSentence(): Boolean = lastOrNull()?.let { it in ".!?" } == true

    /** Long, or a short full sentence — either way an instruction rather than a list item. */
    private fun String.looksLikeProse(): Boolean =
        length >= LONG_LINE || (endsWithSentence() && split(' ').size >= MIN_SENTENCE_WORDS)

    private fun parseServings(text: String): Int? {
        SERVINGS_AFTER.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.takeIf { n -> n in 1..50 } }
        SERVINGS_BEFORE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.takeIf { n -> n in 1..50 } }
        return null
    }

    private fun parseMinutes(text: String): Int? {
        val hours = HOURS.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val minutes = MINUTES.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val total = (hours?.let { (it * 60).toInt() } ?: 0) + (minutes ?: 0)
        return total.takeIf { it in 1..(24 * 60) }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 80
        const val MAX_INGREDIENT_LENGTH = 80
        const val LONG_LINE = 40
        const val MIN_SENTENCE_WORDS = 4
        const val DEFAULT_SERVINGS = 2
        const val DEFAULT_MINUTES = 30

        val INGREDIENTS_HEADER = Regex("""^(zutaten|ingredients|ingrédients|ingredienti)\b""", RegexOption.IGNORE_CASE)
        val STEPS_HEADER = Regex(
            """^(zubereitung|anleitung|so geht'?s|instructions|method|directions|preparation|steps)\b""",
            RegexOption.IGNORE_CASE,
        )
        val NUMBERED_STEP = Regex("""^(\d{1,2})[.)]\s+""")
        val BULLET = Regex("""^[-•*·–]\s*\S""")
        val LEADING_QUANTITY = Regex("""^(\d+([.,/]\d+)?|[½¼¾⅓⅔⅛])\s*(\S)""")
        val META_LINE = Regex(
            """(portionen|personen|servings?|serves|portions?|minuten|minutes?|\bmins?\b|stunden|hours?|kcal|calories)""",
            RegexOption.IGNORE_CASE,
        )
        val SERVINGS_AFTER = Regex("""(\d{1,2})\s*(portionen|personen|servings?|portions?|stück)""", RegexOption.IGNORE_CASE)
        val SERVINGS_BEFORE = Regex("""(?:für|for|serves|ergibt|yields?)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val MINUTES = Regex("""(\d{1,3})\s*(min\b|minuten|minutes?|mins?\b)""", RegexOption.IGNORE_CASE)
        val HOURS = Regex("""(\d{1,2}(?:[.,]\d)?)\s*(h\b|std\b|stunden?|hours?|hrs?\b)""", RegexOption.IGNORE_CASE)
    }
}

/** OpenAI structuring of scanned text — the previous photo-import path, now one engine of several. */
class OpenAiRecipeTextExtractor(
    private val dependencies: RecipeAiDependencies,
) : RecipeTextExtractor {
    override suspend fun extract(rawText: String, nowEpochMillis: Long): Recipe? {
        val apiKey = dependencies.apiKeyOrNull() ?: error("No OpenAI API key is configured.")
        val content = dependencies.chatClient.completeStructured(
            apiKey = apiKey,
            model = dependencies.keyStore.textModel(),
            systemPrompt = OpenAiRecipePrompts.suggestSystemPrompt(),
            userContent = OpenAiRecipePrompts.importUserContent(rawText),
            schemaName = "recipe_import",
            schema = OpenAiRecipePrompts.buildSchema(),
        )
        return OpenAiRecipePrompts.parseRecipes(
            content = content,
            source = RecipeSource.USER,
            createdAtEpochMillis = nowEpochMillis,
            idPrefix = "user_",
        ).firstOrNull()
    }
}

/**
 * Runs [primary] when there is one and falls back to [rules] on any error, timeout, or empty
 * result — so a scan produces a draft whether or not an API key is configured or the network
 * is up. Cancellation still propagates.
 */
class FallbackRecipeTextExtractor(
    private val primary: RecipeTextExtractor?,
    private val rules: RecipeTextExtractor = RulesRecipeTextExtractor(),
    private val primaryTimeoutMillis: Long = 120_000L,
) : RecipeTextExtractor {
    override suspend fun extract(rawText: String, nowEpochMillis: Long): Recipe? {
        if (primary == null) return rules.extract(rawText, nowEpochMillis)
        val primaryResult = try {
            withTimeout(primaryTimeoutMillis) { primary.extract(rawText, nowEpochMillis) }
        } catch (error: TimeoutCancellationException) {
            Log.w(TAG, "AI recipe extraction timed out, using rules", error)
            return rules.extract(rawText, nowEpochMillis)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "AI recipe extraction failed, using rules", error)
            return rules.extract(rawText, nowEpochMillis)
        }
        return primaryResult ?: rules.extract(rawText, nowEpochMillis)
    }

    private companion object {
        const val TAG = "RecipeExtraction"
    }
}
