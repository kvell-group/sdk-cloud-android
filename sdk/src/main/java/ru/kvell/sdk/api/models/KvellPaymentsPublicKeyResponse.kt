package ru.kvell.sdk.api.models

import com.google.gson.annotations.SerializedName

data class KvellPaymentsPublicKeyResponse(
		@SerializedName("pem") val pem: String?,
		@SerializedName("version") val version: Int?)

