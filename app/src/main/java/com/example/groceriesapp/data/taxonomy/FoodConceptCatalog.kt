package com.example.groceriesapp.data.taxonomy

import java.util.Locale

/**
 * The bundled food concept catalogue and the receipt-name matcher built on top of it.
 *
 * Matching runs in two tiers, both substring-based over lowercased German product text:
 * 1. [FoodConcept.priorityKeywords] first, longest first — "carrier" nouns (yogurt, quark, skyr)
 *    that must win over a same-string flavour word, e.g. "Joghurt Erdbeere" must resolve to yogurt
 *    even though "erdbeere" is a longer substring than "joghurt".
 * 2. [FoodConcept.keywords], longest first — the general case, where specific terms beat the
 *    generic ones they contain ("buttermilch" over "butter", "apfelsaft" over "apfel").
 */
object FoodConceptCatalog {

    val all: List<FoodConcept> =
        produceConcepts +
            dairyConcepts +
            meatFishConcepts +
            bakeryGrainsConcepts +
            pantrySnacksConcepts +
            drinksConcepts +
            householdConcepts

    private val byId: Map<String, FoodConcept> by lazy { all.associateBy(FoodConcept::id) }

    private val priorityKeywordIndex: List<Pair<String, FoodConcept>> by lazy {
        all.flatMap { concept -> concept.priorityKeywords.map { it to concept } }
            .sortedByDescending { it.first.length }
    }

    private val keywordIndex: List<Pair<String, FoodConcept>> by lazy {
        all.flatMap { concept -> concept.keywords.map { it to concept } }
            .sortedByDescending { it.first.length }
    }

    fun byId(id: String?): FoodConcept? = id?.let(byId::get)

    fun match(rawName: String): FoodConcept? {
        val normalized = rawName.lowercase(Locale.GERMANY)
        priorityKeywordIndex.firstOrNull { (keyword, _) -> normalized.contains(keyword) }
            ?.let { return it.second }
        return keywordIndex.firstOrNull { (keyword, _) -> normalized.contains(keyword) }?.second
    }
}
