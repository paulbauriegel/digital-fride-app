package com.example.groceriesapp.data.network

data class ReweTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
)

data class ReweAccount(
    val email: String?,
    val name: String?,
    val expiresAtEpochMillis: Long,
)

data class ReweMarket(
    val name: String?,
    val street: String?,
    val zipCode: String?,
    val city: String?,
    val wwIdent: String?,
)

data class ReweEbonEntry(
    val receiptId: String,
    val receiptTimestamp: String,
    val receiptTotalPriceCents: Int,
    val market: ReweMarket?,
    val cancelled: Boolean,
)
