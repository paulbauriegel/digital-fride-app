package com.example.groceriesapp.data.recipes

/**
 * Meal type and dietary classification derived from a recipe's free-form [Recipe.tags].
 *
 * Derived rather than stored: the curated catalogue, the AI schema and the JSON-LD importer all
 * already emit tags, so reading them through one normalised vocabulary keeps every existing JSON
 * file byte-compatible while still giving the library real filters. Editors that *write* a
 * category or diet should write the canonical [RecipeCategory.tag] / [DietaryTag.tag].
 */
enum class RecipeCategory(val label: String, val tag: String) {
    BREAKFAST("Breakfast", "breakfast"),
    LUNCH("Lunch", "lunch"),
    DINNER("Dinner", "dinner"),
    SNACK("Snack", "snack"),
    DESSERT("Dessert", "dessert"),
    DRINK("Drink", "drink"),
    OTHER("Other", "other"),
}

enum class DietaryTag(val label: String, val tag: String) {
    VEGETARIAN("Vegetarian", "vegetarian"),
    VEGAN("Vegan", "vegan"),
    GLUTEN_FREE("Gluten-free", "gluten-free"),
    DAIRY_FREE("Dairy-free", "dairy-free"),
    HIGH_PROTEIN("High protein", "high-protein"),
    LOW_CARB("Low carb", "low-carb"),
}

enum class CookTimeBucket(val label: String) {
    UNDER_15("< 15 min"),
    FROM_15_TO_30("15 – 30 min"),
    FROM_30_TO_60("30 – 60 min"),
    OVER_60("> 60 min"),
    ;

    fun contains(prepMinutes: Int): Boolean = RecipeTaxonomy.bucketOf(prepMinutes) == this
}

object RecipeTaxonomy {

    /** Lower-case, trimmed, with spaces and underscores collapsed to single hyphens. */
    fun normalizeTag(raw: String): String =
        raw.trim().lowercase().replace(Regex("[\\s_]+"), "-").replace(Regex("-{2,}"), "-")

    fun categoryOf(recipe: Recipe): RecipeCategory {
        val tags = recipe.tags.map(::normalizeTag).toSet()
        // Explicit tags win, most specific first: a "breakfast" tag on a cake beats the dessert
        // guess, and a dessert beats the catch-all dinner default.
        for ((category, aliases) in CATEGORY_ALIASES) {
            if (tags.any { it in aliases }) return category
        }
        // No meal-type hint at all. A recipe with real steps is a cooked meal — the curated
        // catalogue is mostly mains tagged only by diet — while a two-line note is anyone's guess.
        return if (recipe.steps.size >= 3) RecipeCategory.DINNER else RecipeCategory.OTHER
    }

    fun dietaryOf(recipe: Recipe): Set<DietaryTag> {
        val tags = recipe.tags.map(::normalizeTag).toSet()
        val result = mutableSetOf<DietaryTag>()
        for ((diet, aliases) in DIETARY_ALIASES) {
            if (tags.any { it in aliases }) result += diet
        }
        // Vegan food is vegetarian food; a "vegetarian" filter must not hide vegan dishes.
        if (DietaryTag.VEGAN in result) result += DietaryTag.VEGETARIAN
        return result
    }

    fun bucketOf(prepMinutes: Int): CookTimeBucket = when {
        prepMinutes < 15 -> CookTimeBucket.UNDER_15
        prepMinutes <= 30 -> CookTimeBucket.FROM_15_TO_30
        prepMinutes <= 60 -> CookTimeBucket.FROM_30_TO_60
        else -> CookTimeBucket.OVER_60
    }

    /** Tags that are neither a category nor a diet — what's left for a "Tags" grouping. */
    fun freeTagsOf(recipe: Recipe): List<String> =
        recipe.tags.map(::normalizeTag)
            .filter { tag -> tag.isNotEmpty() && tag !in CLASSIFIED_TAGS }
            .distinct()

    private val CATEGORY_ALIASES: List<Pair<RecipeCategory, Set<String>>> = listOf(
        RecipeCategory.BREAKFAST to setOf("breakfast", "frühstück", "fruehstueck", "brunch"),
        RecipeCategory.DESSERT to setOf("dessert", "nachtisch", "cake", "kuchen", "sweet", "süß", "suess", "baking"),
        RecipeCategory.DRINK to setOf("drink", "drinks", "getränk", "getraenk", "smoothie", "cocktail", "beverage"),
        RecipeCategory.SNACK to setOf("snack", "snacks", "appetizer", "starter", "vorspeise", "dip", "fingerfood"),
        RecipeCategory.LUNCH to setOf("lunch", "mittagessen", "salad", "salat", "soup", "suppe", "bowl", "sandwich"),
        RecipeCategory.DINNER to setOf("dinner", "abendessen", "main", "main-course", "hauptgericht", "hauptspeise"),
        RecipeCategory.OTHER to setOf("other"),
    )

    private val DIETARY_ALIASES: List<Pair<DietaryTag, Set<String>>> = listOf(
        DietaryTag.VEGETARIAN to setOf("vegetarian", "vegetarisch", "veggie"),
        DietaryTag.VEGAN to setOf("vegan"),
        DietaryTag.GLUTEN_FREE to setOf("gluten-free", "glutenfree", "glutenfrei"),
        DietaryTag.DAIRY_FREE to setOf("dairy-free", "dairyfree", "laktosefrei", "milchfrei", "lactose-free"),
        DietaryTag.HIGH_PROTEIN to setOf("high-protein", "protein", "proteinreich", "eiweißreich"),
        DietaryTag.LOW_CARB to setOf("low-carb", "lowcarb", "keto"),
    )

    private val CLASSIFIED_TAGS: Set<String> =
        CATEGORY_ALIASES.flatMap { it.second }.toSet() + DIETARY_ALIASES.flatMap { it.second }
}
