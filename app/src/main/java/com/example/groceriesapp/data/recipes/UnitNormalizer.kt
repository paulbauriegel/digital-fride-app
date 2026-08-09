package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.COUNTABLE_UNITS

enum class UnitKind {
    MASS,
    VOLUME,
    COUNT,
    UNKNOWN,
}

/** An amount in the base unit of its kind: grams for MASS, millilitres for VOLUME, pieces for COUNT. */
data class NormalizedAmount(val kind: UnitKind, val value: Double)

/**
 * Best-effort unit comparison between recipe ingredients and inventory rows. Units are free text
 * on both sides, so anything unrecognized ("EL", "Bund", "Packung") stays [UnitKind.UNKNOWN] and
 * callers fall back to presence-only matching instead of guessing.
 */
object UnitNormalizer {

    fun kindOf(unit: String?): UnitKind {
        val normalized = unit?.trim()?.lowercase().orEmpty()
        return when {
            normalized.isEmpty() || normalized in COUNTABLE_UNITS -> UnitKind.COUNT
            normalized in MASS_FACTORS -> UnitKind.MASS
            normalized in VOLUME_FACTORS -> UnitKind.VOLUME
            else -> UnitKind.UNKNOWN
        }
    }

    fun normalize(amount: Double?, unit: String?): NormalizedAmount? {
        if (amount == null || !amount.isFinite() || amount <= 0.0) return null
        val normalized = unit?.trim()?.lowercase().orEmpty()
        return when {
            normalized.isEmpty() || normalized in COUNTABLE_UNITS ->
                NormalizedAmount(UnitKind.COUNT, amount)
            normalized in MASS_FACTORS ->
                NormalizedAmount(UnitKind.MASS, amount * MASS_FACTORS.getValue(normalized))
            normalized in VOLUME_FACTORS ->
                NormalizedAmount(UnitKind.VOLUME, amount * VOLUME_FACTORS.getValue(normalized))
            else -> NormalizedAmount(UnitKind.UNKNOWN, amount)
        }
    }

    private val MASS_FACTORS = mapOf(
        "g" to 1.0,
        "gr" to 1.0,
        "gramm" to 1.0,
        "gram" to 1.0,
        "kg" to 1_000.0,
        "kilo" to 1_000.0,
        "kilogramm" to 1_000.0,
    )

    private val VOLUME_FACTORS = mapOf(
        "ml" to 1.0,
        "milliliter" to 1.0,
        "l" to 1_000.0,
        "liter" to 1_000.0,
        "litre" to 1_000.0,
    )
}
