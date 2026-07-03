package ru.kvell.sdk.api.models

import com.google.gson.annotations.SerializedName

data class PaymentGetBody(
	@SerializedName("TransactionId") val transactionId: Int)
