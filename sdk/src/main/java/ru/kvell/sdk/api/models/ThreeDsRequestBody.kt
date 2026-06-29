package ru.kvell.sdk.api.models

import com.google.gson.annotations.SerializedName

data class ThreeDsRequestBody(
	@SerializedName("TransactionId") val transactionId: Int,
	@SerializedName("PaRes") val paRes: String)