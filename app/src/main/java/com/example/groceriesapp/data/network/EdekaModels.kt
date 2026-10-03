package com.example.groceriesapp.data.network

data class EdekaTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
)

data class EdekaAccount(
    val email: String?,
    val expiresAtEpochMillis: Long,
)

data class EdekaAuthRequest(
    val authorizationUrl: String,
    val codeVerifier: String,
    val state: String,
    val deviceId: String,
    val sessionId: String,
)

data class EdekaReceiptEntry(
    val id: Int,
    val dateAndTime: String,
    val totalCents: Int?,
    val currency: String,
    val retailerName: String?,
    val shopName: String?,
    val shopStreet: String?,
    val shopZipCode: String?,
    val shopCity: String?,
) {
    val merchantName: String
        get() = shopName?.takeIf(String::isNotBlank)
            ?: retailerName?.takeIf(String::isNotBlank)
            ?: "EDEKA"
}

enum class EdekaReceiptLineType {
    NORMAL,
    DISCOUNT,
    SEPARATOR,
    UNKNOWN,
}

data class EdekaFormattedReceiptLine(
    val type: EdekaReceiptLineType,
    val left: String,
    val right: String,
)

data class EdekaFormattedReceipt(
    val id: Int,
    val canceled: Boolean,
    val totalCents: Int?,
    val currency: String,
    val retailerName: String?,
    val shopName: String?,
    val lines: List<EdekaFormattedReceiptLine>,
)
