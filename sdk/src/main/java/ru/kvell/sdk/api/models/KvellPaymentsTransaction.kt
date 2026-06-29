package ru.kvell.sdk.api.models

import com.google.gson.annotations.SerializedName

data class KvellPaymentsTransaction(
	@SerializedName("TransactionId") val transactionId: Int?,
	@SerializedName("ReasonCode") val reasonCode: Int?,
	@SerializedName("CardHolderMessage") val cardHolderMessage: String?,
	@SerializedName("PaReq") val paReq: String?,
	@SerializedName("AcsUrl") val acsUrl: String?,
	@SerializedName("ThreeDsCallbackId") val threeDsCallbackId: String?)