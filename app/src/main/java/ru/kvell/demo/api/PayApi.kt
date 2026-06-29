package ru.kvell.demo.api

import io.reactivex.Observable
import ru.kvell.sdk.api.models.*
import ru.kvell.sdk.configuration.KvellPaymentsSDK
import ru.kvell.demo.Constants

class PayApi {
	companion object {
		private val api = KvellPaymentsSDK.createApi(Constants.merchantPublicId, Constants.apiSecret)

		fun charge(cardCryptogramPacket: String, cardHolderName: String?, amount: Int): Observable<KvellPaymentsTransaction> {
			// Параметры см. в PaymentRequestBody
			val body = PaymentRequestBody(amount = amount.toString(),
										  currency = "RUB",
										  ipAddress = "127.0.0.1",
										  name = cardHolderName,
										  cryptogram = cardCryptogramPacket)
			return api.charge(body)
				.toObservable()
				.flatMap(KvellPaymentsTransactionResponse::handleError)
				.map { it.transaction }
		}

		fun auth(cardCryptogramPacket: String, cardHolderName: String?, amount: Int): Observable<KvellPaymentsTransaction> {
			// Параметры см. в PaymentRequestBody
			val body = PaymentRequestBody(amount = amount.toString(),
										  currency = "RUB",
										  ipAddress = "127.0.0.1",
										  name = cardHolderName,
										  cryptogram = cardCryptogramPacket)
			return api.auth(body)
				.toObservable()
				.flatMap(KvellPaymentsTransactionResponse::handleError)
				.map { it.transaction }
		}

		fun postThreeDs(transactionId: Int, paRes: String): Observable<KvellPaymentsTransactionResponse> {
			return api.postThreeDs(transactionId, paRes)
				.toObservable()
		}

		fun getBinInfo(firstSixDigits: String): Observable<KvellPaymentsBinInfo> {
			return api.getBinInfo(firstSixDigits)
					.toObservable()
		}
	}
}