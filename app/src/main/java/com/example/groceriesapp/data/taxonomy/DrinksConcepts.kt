package com.example.groceriesapp.data.taxonomy

import com.example.groceriesapp.R

// Generated from the curated concept catalogue. Icon artwork: OpenMoji (CC BY-SA 4.0).
internal val drinksConcepts: List<FoodConcept> = listOf(
    // DRINKS
    FoodConcept(
        id = "WATER",
        category = GroceryCategory.DRINKS,
        displayName = "Water",
        iconRes = R.drawable.ic_food_water,
        keywords = listOf("wasser", "mineralwasser", "sprudel"),
    ),
    FoodConcept(
        id = "JUICE",
        category = GroceryCategory.DRINKS,
        displayName = "Juice",
        iconRes = R.drawable.ic_food_juice,
        keywords = listOf("apfelsaft", "orangensaft", "traubensaft", "traubensaf", "zitronensaft", "ananassaft", "birnensaft", "kirschsaft", "saft"),
    ),
    FoodConcept(
        id = "SOFT_DRINK",
        category = GroceryCategory.DRINKS,
        displayName = "Soft drink",
        iconRes = R.drawable.ic_food_soft_drink,
        // "bionade" is a branded organic lemonade — the name itself doesn't say "limonade".
        keywords = listOf(
            "cola", "limonade", "limo", "fanta", "sprite", "bionade",
            // Fassbrause, spelled out and truncated, plus the "KROM FB ZIT NAT" receipt line
            // where "FB" is all that survives of the word.
            "fassbrause", "fassbra", "krom fb",
        ),
    ),
    FoodConcept(
        id = "COFFEE",
        category = GroceryCategory.DRINKS,
        displayName = "Coffee",
        iconRes = R.drawable.ic_food_coffee,
        // "kaffe" also catches receipt-truncated brand names like "Heldenkaffe" (missing final "e").
        keywords = listOf("kaffee", "espresso", "kaffe"),
    ),
    FoodConcept(
        id = "TEA",
        category = GroceryCategory.DRINKS,
        displayName = "Tea",
        iconRes = R.drawable.ic_food_tea,
        keywords = listOf("tee", "chai", "yogi tea"),
    ),
    FoodConcept(
        id = "BEER",
        category = GroceryCategory.DRINKS,
        displayName = "Beer",
        iconRes = R.drawable.ic_food_beer,
        keywords = listOf("bier", "pils"),
    ),
    FoodConcept(
        id = "GINGER_BEER",
        category = GroceryCategory.DRINKS,
        displayName = "Ginger beer",
        iconRes = R.drawable.ic_food_ginger_beer,
        // "bundaberg" catches the brand alone, since receipts often drop "ginger beer" itself
        // (e.g. "Bundaberg Ginger").
        keywords = listOf("ginger beer", "ginger-beer", "gingerbeer", "bundaberg"),
    ),
    FoodConcept(
        id = "WINE",
        category = GroceryCategory.DRINKS,
        displayName = "Wine",
        iconRes = R.drawable.ic_food_wine,
        keywords = listOf("wein"),
    ),
    FoodConcept(
        id = "SPARKLING_WINE",
        category = GroceryCategory.DRINKS,
        displayName = "Sparkling wine",
        iconRes = R.drawable.ic_food_sparkling_wine,
        keywords = listOf("sekt", "prosecco", "champagner"),
    ),
    FoodConcept(
        id = "SPIRITS",
        category = GroceryCategory.DRINKS,
        displayName = "Spirits",
        iconRes = R.drawable.ic_food_spirits,
        keywords = listOf("wodka", "whisky", "likör"),
    ),
    FoodConcept(
        id = "SMOOTHIE",
        category = GroceryCategory.DRINKS,
        displayName = "Smoothie",
        iconRes = R.drawable.ic_food_smoothie,
        keywords = listOf("smoothie"),
    ),
)
