package ru.kvell.sdk.api.models

import com.google.gson.annotations.SerializedName

data class KvellPaymentsBinInfoResponse(
		@SerializedName("Success") val success: Boolean?,
		@SerializedName("Message") val message: String?,
		@SerializedName("Model") val binInfo: KvellPaymentsBinInfo?)

data class KvellPaymentsBinInfo(
		@SerializedName("LogoUrl") val logoUrl: String?,
		@SerializedName("BankName") val bankName: String?)