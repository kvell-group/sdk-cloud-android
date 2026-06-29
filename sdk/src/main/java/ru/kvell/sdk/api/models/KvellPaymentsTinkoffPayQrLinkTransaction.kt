package ru.kvell.sdk.api.models

import com.google.gson.annotations.SerializedName

data class KvellPaymentsTinkoffPayQrLinkTransaction(
	@SerializedName("TransactionId") val transactionId: Int?,
	@SerializedName("ProviderQrId") val providerQrId: String?,
	@SerializedName("QrUrl") val qrUrl: String?)