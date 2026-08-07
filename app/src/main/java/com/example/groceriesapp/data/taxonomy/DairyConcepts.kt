package com.example.groceriesapp.data.taxonomy

import com.example.groceriesapp.R

// Generated from the curated concept catalogue. Icon artwork: OpenMoji (CC BY-SA 4.0).
internal val dairyConcepts: List<FoodConcept> = listOf(
    // DAIRY
    FoodConcept(
        id = "WHOLE_MILK",
        category = GroceryCategory.DAIRY,
        displayName = "Milk",
        iconRes = R.drawable.ic_food_whole_milk,
        keywords = listOf("vollmilch", "frischmilch", "milch"),
    ),
    FoodConcept(
        id = "HMILCH",
        category = GroceryCategory.DAIRY,
        displayName = "UHT milk",
        iconRes = R.drawable.ic_food_hmilch,
        keywords = listOf("h-milch", "hmilch", "haltbare milch"),
    ),
    FoodConcept(
        id = "BUTTERMILK",
        category = GroceryCategory.DAIRY,
        displayName = "Buttermilk",
        iconRes = R.drawable.ic_food_buttermilk,
        keywords = listOf("buttermilch"),
    ),
    FoodConcept(
        id = "CREAM",
        category = GroceryCategory.DAIRY,
        displayName = "Cream",
        iconRes = R.drawable.ic_food_cream,
        keywords = listOf("schlagsahne", "sahne"),
    ),
    FoodConcept(
        id = "SOUR_CREAM",
        category = GroceryCategory.DAIRY,
        displayName = "Sour cream",
        iconRes = R.drawable.ic_food_sour_cream,
        keywords = listOf("schmand", "saure sahne", "creme fraiche", "crème fraîche"),
    ),
    FoodConcept(
        id = "YOGURT",
        category = GroceryCategory.DAIRY,
        displayName = "Yogurt",
        iconRes = R.drawable.ic_food_yogurt,
        keywords = listOf("joghurt", "jogurt", "actimel"),
        priorityKeywords = listOf("joghurt", "jogurt", "actimel"),
    ),
    FoodConcept(
        id = "QUARK",
        category = GroceryCategory.DAIRY,
        displayName = "Quark",
        iconRes = R.drawable.ic_food_quark,
        keywords = listOf("quark"),
        priorityKeywords = listOf("quark"),
    ),
    FoodConcept(
        id = "SKYR",
        category = GroceryCategory.DAIRY,
        displayName = "Skyr",
        iconRes = R.drawable.ic_food_skyr,
        keywords = listOf("skyr"),
        priorityKeywords = listOf("skyr"),
    ),
    FoodConcept(
        id = "BUTTER",
        category = GroceryCategory.DAIRY,
        displayName = "Butter",
        iconRes = R.drawable.ic_food_butter,
        keywords = listOf("butter"),
    ),
    FoodConcept(
        id = "MARGARINE",
        category = GroceryCategory.DAIRY,
        displayName = "Margarine",
        iconRes = R.drawable.ic_food_margarine,
        keywords = listOf("margarine"),
    ),
    FoodConcept(
        id = "CHEESE_HARD",
        category = GroceryCategory.DAIRY,
        displayName = "Hard cheese",
        iconRes = R.drawable.ic_food_cheese_hard,
        keywords = listOf("gouda", "emmentaler", "bergkäse", "parmesan", "cheddar"),
    ),
    FoodConcept(
        id = "CHEESE_SOFT",
        category = GroceryCategory.DAIRY,
        displayName = "Soft cheese",
        iconRes = R.drawable.ic_food_cheese_soft,
        keywords = listOf("camembert", "brie"),
    ),
    FoodConcept(
        id = "CHEESE_FRESH",
        category = GroceryCategory.DAIRY,
        displayName = "Fresh cheese",
        iconRes = R.drawable.ic_food_cheese_fresh,
        keywords = listOf("frischkäse", "mozzarella", "feta", "hüttenkäse", "ricotta"),
    ),
    FoodConcept(
        id = "CHEESE_SLICED",
        category = GroceryCategory.DAIRY,
        displayName = "Sliced cheese",
        iconRes = R.drawable.ic_food_cheese_sliced,
        keywords = listOf("schnittkäse", "scheibletten"),
    ),
    FoodConcept(
        id = "CHEESE",
        category = GroceryCategory.DAIRY,
        displayName = "Cheese",
        iconRes = R.drawable.ic_food_cheese,
        keywords = listOf("käse", "kaese"),
    ),
    FoodConcept(
        id = "PUDDING",
        category = GroceryCategory.DAIRY,
        displayName = "Pudding",
        iconRes = R.drawable.ic_food_pudding,
        keywords = listOf("pudding", "grießbrei"),
    ),
    FoodConcept(
        id = "PLANT_MILK",
        category = GroceryCategory.DAIRY,
        displayName = "Plant drink",
        iconRes = R.drawable.ic_food_plant_milk,
        keywords = listOf(
            "hafermilch", "mandelmilch", "sojamilch", "haferdrink", "sojadrink", "barista",
            "erbsenprotein", "erbsenprot", "erbsendrink",
        ),
        // Pea-protein drinks must outrank PEAS, whose "erbse" keyword they contain.
        priorityKeywords = listOf("erbsenprotein", "erbsenprot", "erbsendrink"),
    ),
    // EGGS
    FoodConcept(
        id = "EGGS",
        category = GroceryCategory.EGGS,
        displayName = "Eggs",
        iconRes = R.drawable.ic_food_eggs,
        keywords = listOf("eier", "freilandei", "vollei"),
    ),
)
