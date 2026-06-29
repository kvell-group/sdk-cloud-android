package ru.kvell.sdk.models

import ru.kvell.sdk.configuration.KvellPaymentsSDK

data class Transaction (
	val transactionId: Int?,
	val status: KvellPaymentsSDK.TransactionStatus?,
	val reasonCode: Int?
	)