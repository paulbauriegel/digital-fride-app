package com.example.groceriesapp.data.taxonomy

import com.example.groceriesapp.R

// Generated from the curated concept catalogue. Icon artwork: OpenMoji (CC BY-SA 4.0).
internal val pantrySnacksConcepts: List<FoodConcept> = listOf(
    // FROZEN
    FoodConcept(
        id = "FROZEN_PIZZA",
        category = GroceryCategory.FROZEN,
        displayName = "Pizza",
        iconRes = R.drawable.ic_food_frozen_pizza,
        keywords = listOf("pizza"),
    ),
    FoodConcept(
        id = "FLAMMKUCHEN",
        category = GroceryCategory.FROZEN,
        displayName = "Tarte flambee",
        iconRes = R.drawable.ic_food_flammkuchen,
        keywords = listOf("flammkuchen", "flammkuche"),
    ),
    FoodConcept(
        id = "ICE_CREAM",
        category = GroceryCategory.FROZEN,
        displayName = "Ice cream",
        iconRes = R.drawable.ic_food_ice_cream,
        // "mini mix" catches assorted ice-cream-on-a-stick multipacks (e.g. "Mini Mix classic"),
        // the same product family as "magnum".
        keywords = listOf("eiscreme", "speiseeis", "magnum", "mini mix"),
    ),
    FoodConcept(
        id = "FROZEN_VEG",
        category = GroceryCategory.FROZEN,
        displayName = "Frozen vegetables",
        iconRes = R.drawable.ic_food_frozen_veg,
        keywords = listOf("tiefkühlgemüse", "rahmspinat"),
    ),
    FoodConcept(
        id = "FROZEN_FRIES",
        category = GroceryCategory.FROZEN,
        displayName = "Chips",
        iconRes = R.drawable.ic_food_frozen_fries,
        keywords = listOf("pommes", "kroketten"),
    ),
    FoodConcept(
        id = "READY_MEAL",
        category = GroceryCategory.FROZEN,
        displayName = "Ready meal",
        iconRes = R.drawable.ic_food_canned_soup,
        keywords = listOf(
            "rotes curry", "gruenes curry", "grünes curry",
            "bratkartoffel", "bratkrt", "fertiggericht",
            // Only ever appears in fried noodle/rice dishes (Bami/Nasi Goreng), all prepared meals.
            "goreng",
            "butter chicken", "mexican chicken",
            "huehnerfrikasse", "hühnerfrikassee",
        ),
        // These must outrank the ingredient concepts they contain — SPICE_MIX's "curry",
        // POTATO's "kartoffel", BUTTER's "butter" — since the product is a prepared dish,
        // not the raw ingredient.
        priorityKeywords = listOf(
            "rotes curry", "gruenes curry", "grünes curry",
            "bratkartoffel", "bratkrt", "fertiggericht",
            "butter chicken", "mexican chicken",
        ),
    ),
    // SNACKS_SWEETS
    FoodConcept(
        id = "CHOCOLATE",
        category = GroceryCategory.SNACKS_SWEETS,
        displayName = "Chocolate",
        iconRes = R.drawable.ic_food_chocolate,
        keywords = listOf("schokolade", "schoko"),
    ),
    FoodConcept(
        id = "COOKIES",
        category = GroceryCategory.SNACKS_SWEETS,
        displayName = "Biscuits",
        iconRes = R.drawable.ic_food_cookies,
        keywords = listOf("butterkeks", "keks", "plätzchen"),
    ),
    FoodConcept(
        id = "CANDY",
        category = GroceryCategory.SNACKS_SWEETS,
        displayName = "Sweets",
        iconRes = R.drawable.ic_food_candy,
        // "fruchtbumi" is an OCR misread of "Fruchtgummi" (g misread as b).
        keywords = listOf("bonbon", "gummibär", "fruchtgummi", "fruchtbumi", "lakritz", "phantasia"),
    ),
    FoodConcept(
        id = "CHIPS",
        category = GroceryCategory.SNACKS_SWEETS,
        displayName = "Crisps",
        iconRes = R.drawable.ic_food_chips,
        keywords = listOf("chips"),
    ),
    FoodConcept(
        id = "POPCORN",
        category = GroceryCategory.SNACKS_SWEETS,
        displayName = "Popcorn",
        iconRes = R.drawable.ic_food_popcorn,
        keywords = listOf("popcorn"),
    ),
    FoodConcept(
        id = "NUTS",
        category = GroceryCategory.SNACKS_SWEETS,
        displayName = "Nuts",
        iconRes = R.drawable.ic_food_nuts,
        keywords = listOf("nuss", "nüsse", "mandel", "cashew"),
    ),
    FoodConcept(
        id = "DRIED_MANGO",
        category = GroceryCategory.SNACKS_SWEETS,
        displayName = "Dried mango",
        iconRes = R.drawable.ic_food_mango,
        keywords = listOf("mangostücke", "mangostuecke", "mangostreifen"),
        // Must outrank fresh MANGO, whose "mango" keyword these all contain.
        priorityKeywords = listOf("mangostücke", "mangostuecke", "mangostreifen"),
    ),
    FoodConcept(
        id = "SALTY_SNACKS",
        category = GroceryCategory.SNACKS_SWEETS,
        displayName = "Salty snacks",
        iconRes = R.drawable.ic_food_salty_snacks,
        keywords = listOf("salzstangen", "cracker"),
    ),
    FoodConcept(
        id = "MUESLI_BAR",
        category = GroceryCategory.SNACKS_SWEETS,
        displayName = "Cereal bar",
        iconRes = R.drawable.ic_food_muesli_bar,
        keywords = listOf("müsliriegel", "riegel"),
    ),
    // CONDIMENTS_SAUCES
    FoodConcept(
        id = "KETCHUP",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Ketchup",
        iconRes = R.drawable.ic_food_ketchup,
        keywords = listOf("ketchup"),
    ),
    FoodConcept(
        id = "BARBECUE_SAUCE",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Barbecue sauce",
        // No dedicated artwork; ketchup is the closest match — same bottled-condiment role.
        iconRes = R.drawable.ic_food_ketchup,
        keywords = listOf("barbecue sauce", "bbq sauce", "grillsauce", "grillsoße"),
    ),
    FoodConcept(
        id = "MAYONNAISE",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Mayonnaise",
        iconRes = R.drawable.ic_food_mayonnaise,
        keywords = listOf("mayonnaise", "remoulade"),
    ),
    FoodConcept(
        id = "MUSTARD",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Mustard",
        iconRes = R.drawable.ic_food_mustard,
        keywords = listOf("senf"),
    ),
    FoodConcept(
        id = "VINEGAR",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Vinegar",
        iconRes = R.drawable.ic_food_vinegar,
        keywords = listOf("essig"),
    ),
    FoodConcept(
        id = "OIL",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Oil",
        iconRes = R.drawable.ic_food_oil,
        keywords = listOf("olivenöl", "sonnenblumenöl", "rapsöl", "öl", "oel"),
    ),
    FoodConcept(
        id = "SOY_SAUCE",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Soy sauce",
        iconRes = R.drawable.ic_food_soy_sauce,
        keywords = listOf("sojasauce", "sojasoße"),
    ),
    FoodConcept(
        id = "PESTO",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Pesto",
        iconRes = R.drawable.ic_food_pesto,
        keywords = listOf("pesto"),
    ),
    FoodConcept(
        id = "TOMATO_SAUCE",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Tomato sauce",
        iconRes = R.drawable.ic_food_tomato_sauce,
        keywords = listOf("passata", "tomatenmark", "tomatensauce"),
    ),
    FoodConcept(
        id = "SALAD_DRESSING",
        category = GroceryCategory.CONDIMENTS_SAUCES,
        displayName = "Dressing",
        iconRes = R.drawable.ic_food_salad_dressing,
        keywords = listOf("dressing"),
    ),
    // CANNED_JARRED
    FoodConcept(
        id = "CANNED_TOMATOES",
        category = GroceryCategory.CANNED_JARRED,
        displayName = "Tinned tomatoes",
        iconRes = R.drawable.ic_food_canned_tomatoes,
        keywords = listOf("dosentomaten", "gehackte tomaten"),
    ),
    FoodConcept(
        id = "CANNED_BEANS",
        category = GroceryCategory.CANNED_JARRED,
        displayName = "Tinned beans",
        iconRes = R.drawable.ic_food_canned_beans,
        keywords = listOf("kidneybohnen"),
    ),
    FoodConcept(
        id = "CANNED_CORN",
        category = GroceryCategory.CANNED_JARRED,
        displayName = "Tinned sweetcorn",
        iconRes = R.drawable.ic_food_canned_corn,
        keywords = listOf("dosenmais"),
    ),
    FoodConcept(
        id = "CANNED_FISH",
        category = GroceryCategory.CANNED_JARRED,
        displayName = "Tinned fish",
        iconRes = R.drawable.ic_food_canned_fish,
        keywords = listOf("ölsardine", "sardine"),
    ),
    FoodConcept(
        id = "COCONUT_MILK",
        category = GroceryCategory.CANNED_JARRED,
        displayName = "Coconut milk",
        iconRes = R.drawable.ic_food_coconut_milk,
        keywords = listOf("kokosmilch"),
    ),
    FoodConcept(
        id = "CANNED_SOUP",
        category = GroceryCategory.CANNED_JARRED,
        displayName = "Soup",
        iconRes = R.drawable.ic_food_canned_soup,
        keywords = listOf("suppe", "eintopf"),
    ),
    FoodConcept(
        id = "COMPOTE",
        category = GroceryCategory.CANNED_JARRED,
        displayName = "Compote",
        iconRes = R.drawable.ic_food_compote,
        keywords = listOf("apfelmus", "kompott"),
    ),
    FoodConcept(
        id = "CANNED_FRUIT",
        category = GroceryCategory.CANNED_JARRED,
        displayName = "Tinned fruit",
        // No dedicated artwork; the compote jar is the closest match among the bundled icons.
        iconRes = R.drawable.ic_food_compote,
        // Distinct product from COMPOTE: whole/diced fruit pieces in syrup, not a cooked-down
        // sauce. "5-Fruchtcocktail" (five-fruit mix) is the common branding; the leading digit
        // doesn't need its own keyword since "fruchtcocktail" already matches as a substring.
        keywords = listOf("fruchtcocktail", "früchtecocktail", "obstsalat"),
    ),
    // SPICES_BAKING
    FoodConcept(
        id = "SALT",
        category = GroceryCategory.SPICES_BAKING,
        displayName = "Salt",
        iconRes = R.drawable.ic_food_salt,
        keywords = listOf("salz"),
    ),
    FoodConcept(
        id = "PEPPER_SPICE",
        category = GroceryCategory.SPICES_BAKING,
        displayName = "Pepper",
        iconRes = R.drawable.ic_food_pepper_spice,
        keywords = listOf("pfeffer"),
    ),
    FoodConcept(
        id = "SPICE_MIX",
        category = GroceryCategory.SPICES_BAKING,
        displayName = "Spices",
        iconRes = R.drawable.ic_food_spice_mix,
        keywords = listOf("gewürz", "paprikapulver", "curry", "oregano", "thymian", "zimt"),
    ),
    FoodConcept(
        id = "SUGAR",
        category = GroceryCategory.SPICES_BAKING,
        displayName = "Sugar",
        iconRes = R.drawable.ic_food_sugar,
        keywords = listOf("zucker"),
    ),
    FoodConcept(
        id = "VANILLA",
        category = GroceryCategory.SPICES_BAKING,
        displayName = "Vanilla",
        iconRes = R.drawable.ic_food_vanilla,
        keywords = listOf("vanille"),
    ),
    FoodConcept(
        id = "BAKING_POWDER",
        category = GroceryCategory.SPICES_BAKING,
        displayName = "Baking powder",
        iconRes = R.drawable.ic_food_baking_powder,
        keywords = listOf("backpulver", "hefe", "natron"),
    ),
    FoodConcept(
        id = "COCOA",
        category = GroceryCategory.SPICES_BAKING,
        displayName = "Cocoa",
        iconRes = R.drawable.ic_food_cocoa,
        keywords = listOf("kakao"),
    ),
    FoodConcept(
        id = "STOCK",
        category = GroceryCategory.SPICES_BAKING,
        displayName = "Stock",
        iconRes = R.drawable.ic_food_stock,
        keywords = listOf("brühe", "bouillon"),
    ),
    // PANTRY
    FoodConcept(
        id = "BABY_FOOD",
        category = GroceryCategory.PANTRY,
        displayName = "Baby food",
        iconRes = R.drawable.ic_food_baby_food,
        keywords = listOf("babynahrung", "gläschen"),
    ),
)
