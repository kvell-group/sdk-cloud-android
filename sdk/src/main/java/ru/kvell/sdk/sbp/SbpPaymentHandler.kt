package ru.kvell.sdk.sbp


data class SbpPaymentRequest(
	val idempotencyKey: String,
	val amount: String,
	val currency: String,
	val invoiceId: String? = null,
	val accountId: String? = null,
	val paymentDescription: String? = null,
	val intentId: String? = null
)


data class SbpPaymentSession(
	val sessionId: String,
	val transactionId: Int? = null,
	val providerQrId: String? = null,
	val redirectUrl: String?
)


sealed class SbpPaymentStatus {
	object Pending : SbpPaymentStatus()
	data class Success(val transactionId: Int?) : SbpPaymentStatus()
	data class Failure(val message: String?) : SbpPaymentStatus()
	object Cancelled : SbpPaymentStatus()
}


sealed class SbpPaymentError(message: String) : Exception(message) {
	object HandlerNotConfigured : SbpPaymentError("Обработчик СБП не настроен")
	object InvalidSession : SbpPaymentError("Backend вернул некорректную СБП-сессию")
	object InvalidRedirectUrl : SbpPaymentError("Backend не вернул безопасную ссылку для оплаты через СБП")
	class PreparationFailed(val reason: String?) : SbpPaymentError(reason ?: "Не удалось подготовить оплату через СБП")
	object Timeout : SbpPaymentError("Истекло время ожидания оплаты через СБП")
}


interface SbpPaymentHandler {
	fun preparePayment(
		request: SbpPaymentRequest,
		completion: (Result<SbpPaymentSession>) -> Unit
	)

	fun resolveStatus(
		session: SbpPaymentSession,
		completion: (Result<SbpPaymentStatus>) -> Unit
	)
}
