package com.example.groceriesapp.data.taxonomy

import com.example.groceriesapp.R

// Generated from the curated concept catalogue. Icon artwork: OpenMoji (CC BY-SA 4.0).
internal val meatFishConcepts: List<FoodConcept> = listOf(
    // MEAT
    FoodConcept(
        id = "CHICKEN",
        category = GroceryCategory.MEAT,
        displayName = "Chicken",
        iconRes = R.drawable.ic_food_chicken,
        // "ha-innenfilet" is a receipt abbreviation for "Hähnchen Innenfilet" (chicken
        // tenderloin) — kept as the exact abbreviated string rather than the generic
        // "innenfilet" cut name, which is also used for pork and turkey tenderloin.
        keywords = listOf("hähnchen", "haehnchen", "huhn", "hühner", "poulet", "ha-innenfilet"),
    ),
    FoodConcept(
        id = "TURKEY",
        category = GroceryCategory.MEAT,
        displayName = "Turkey",
        iconRes = R.drawable.ic_food_turkey,
        keywords = listOf("pute", "truthahn"),
    ),
    FoodConcept(
        id = "BEEF",
        category = GroceryCategory.MEAT,
        displayName = "Beef",
        iconRes = R.drawable.ic_food_beef,
        keywords = listOf("rind", "steak"),
    ),
    FoodConcept(
        id = "PORK",
        category = GroceryCategory.MEAT,
        displayName = "Pork",
        iconRes = R.drawable.ic_food_pork,
        keywords = listOf("schwein", "schnitzel", "kotelett"),
    ),
    FoodConcept(
        id = "MINCED_MEAT",
        category = GroceryCategory.MEAT,
        displayName = "Minced meat",
        iconRes = R.drawable.ic_food_minced_meat,
        keywords = listOf("hackfleisch", "gehacktes", "mett"),
    ),
    FoodConcept(
        id = "LAMB",
        category = GroceryCategory.MEAT,
        displayName = "Lamb",
        iconRes = R.drawable.ic_food_lamb,
        keywords = listOf("lamm"),
    ),
    FoodConcept(
        id = "SAUSAGE",
        category = GroceryCategory.MEAT,
        displayName = "Sausage",
        iconRes = R.drawable.ic_food_sausage,
        keywords = listOf("wurst", "bratwurst", "wiener", "bockwurst"),
    ),
    FoodConcept(
        id = "VEGAN_SAUSAGE",
        // Filed alongside real sausage rather than a dedicated vegetarian category — same
        // shelving, storage and shelf-life pattern, the same choice PLANT_MILK makes under DAIRY.
        category = GroceryCategory.MEAT,
        displayName = "Vegan sausage",
        iconRes = R.drawable.ic_food_sausage,
        // "vegane bratw" (not bare "bratw") so a truncated *real* bratwurst ("Bratw." without the
        // "vegane" qualifier) doesn't get misclassified as vegan.
        keywords = listOf("vegane bratw", "vegane bratwurst", "veganer wurst", "veggie wurst"),
    ),
    FoodConcept(
        // Grill cuts and skewers sold by their purpose rather than their animal — "Grillfackel",
        // "Brat-/Grillwurst" — where the receipt line never names the meat.
        id = "GRILL_MEAT",
        category = GroceryCategory.MEAT,
        displayName = "Grill meat",
        iconRes = R.drawable.ic_food_sausage,
        keywords = listOf("grillfackel", "brat/grill", "grillt"),
    ),
    FoodConcept(
        id = "SALAMI",
        category = GroceryCategory.MEAT,
        displayName = "Salami",
        iconRes = R.drawable.ic_food_salami,
        keywords = listOf("salami"),
    ),
    FoodConcept(
        id = "HAM",
        category = GroceryCategory.MEAT,
        displayName = "Ham",
        iconRes = R.drawable.ic_food_ham,
        keywords = listOf("schinken"),
    ),
    FoodConcept(
        id = "BACON",
        category = GroceryCategory.MEAT,
        displayName = "Bacon",
        iconRes = R.drawable.ic_food_bacon,
        keywords = listOf("speck", "bacon"),
    ),
    FoodConcept(
        id = "COLD_CUTS",
        category = GroceryCategory.MEAT,
        displayName = "Cold cuts",
        iconRes = R.drawable.ic_food_cold_cuts,
        keywords = listOf("aufschnitt", "lyoner", "lamm-lachs", "lammlachs"),
    ),
    // FISH_SEAFOOD
    FoodConcept(
        id = "SALMON",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Salmon",
        iconRes = R.drawable.ic_food_salmon,
        keywords = listOf("lachs"),
    ),
    FoodConcept(
        id = "TUNA",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Tuna",
        iconRes = R.drawable.ic_food_tuna,
        keywords = listOf("thunfisch"),
    ),
    FoodConcept(
        id = "COD",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Cod",
        iconRes = R.drawable.ic_food_cod,
        keywords = listOf("kabeljau", "dorsch"),
    ),
    FoodConcept(
        id = "POLLOCK",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Pollock",
        iconRes = R.drawable.ic_food_pollock,
        keywords = listOf("seelachs"),
    ),
    FoodConcept(
        id = "HERRING",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Herring",
        iconRes = R.drawable.ic_food_herring,
        keywords = listOf("hering", "matjes"),
    ),
    FoodConcept(
        id = "TROUT",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Trout",
        iconRes = R.drawable.ic_food_trout,
        keywords = listOf("forelle"),
    ),
    FoodConcept(
        id = "MACKEREL",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Mackerel",
        iconRes = R.drawable.ic_food_mackerel,
        keywords = listOf("makrele"),
    ),
    FoodConcept(
        id = "SHRIMP",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Prawns",
        iconRes = R.drawable.ic_food_shrimp,
        keywords = listOf("garnele", "shrimp", "krabbe"),
    ),
    FoodConcept(
        id = "MUSSELS",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Mussels",
        iconRes = R.drawable.ic_food_mussels,
        keywords = listOf("muschel"),
    ),
    FoodConcept(
        id = "FISH_STICKS",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Fish fingers",
        iconRes = R.drawable.ic_food_fish_sticks,
        keywords = listOf("fischstäbchen"),
    ),
    FoodConcept(
        id = "FISH",
        category = GroceryCategory.FISH_SEAFOOD,
        displayName = "Fish",
        iconRes = R.drawable.ic_food_fish,
        keywords = listOf("fisch"),
    ),
)
