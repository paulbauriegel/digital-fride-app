package com.example.groceriesapp.data.network

data class BioCompanyAccount(
    val email: String,
    val customerNumber: String,
    val token: String,
    val tokenExpiryEpochMillis: Long,
)

data class BioCompanyTokenResponse(
    val token: String,
    val customernumber: String,
)

data class BioCompanyReceiptEntry(
    val checkout: Int,
    val date: String,
    val receiptnumber: Int,
    val amount: Double,
    val positions: Int,
)

data class BioCompanyReceiptDetail(
    val receiptnumber: Int,
    val checkout: Int,
    val date: String,
    val amount: Double,
    val items: List<BioCompanyReceiptDetailItem>,
)

data class BioCompanyReceiptDetailItem(
    val name: String,
    val quantity: Double,
    val unit: String?,
    val priceCents: Int,
)

data class BioCompanyPointsResponse(
    val customernumber: String?,
    val points: Int,
)
