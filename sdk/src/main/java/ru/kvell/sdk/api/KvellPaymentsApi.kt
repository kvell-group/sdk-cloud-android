package ru.kvell.sdk.api

import android.net.Uri
import com.google.gson.Gson
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import retrofit2.HttpException
import ru.kvell.sdk.api.models.*
import java.net.URLDecoder
import javax.inject.Inject

class KvellPaymentsApi @Inject constructor(private val apiService: KvellPaymentsApiService) {
	companion object {
		private const val THREE_DS_SUCCESS_URL = "https://api.cloudpayments.ru/threeds/success"
		private const val THREE_DS_FAIL_URL = "https://api.cloudpayments.ru/threeds/fail"
	}

	fun getPublicKey(): Single<KvellPaymentsPublicKeyResponse> {
		return apiService.getPublicKey()
			.subscribeOn(Schedulers.io())
	}

	fun getMerchantConfiguration(publicId: String): Single<KvellPaymentsMerchantConfigurationResponse> {
		return apiService.getMerchantConfiguration(publicId)
			.subscribeOn(Schedulers.io())
	}

	fun charge(requestBody: PaymentRequestBody): Single<KvellPaymentsTransactionResponse> {
		return apiService.charge(requestBody)
			.subscribeOn(Schedulers.io())
	}

	fun auth(requestBody: PaymentRequestBody): Single<KvellPaymentsTransactionResponse> {
		return apiService.auth(requestBody)
			.subscribeOn(Schedulers.io())
	}

	fun postThreeDs(transactionId: Int, paRes: String): Single<KvellPaymentsTransactionResponse> {
		return apiService.postThreeDs(ThreeDsRequestBody(transactionId = transactionId, paRes = paRes))
			.subscribeOn(Schedulers.io())
	}

	fun getTinkoffPayQrLink(requestBody: TinkoffPayQrLinkBody): Single<KvellPaymentsGetTinkoffPayQrLinkResponse> {
		return apiService.getTinkoffPayQrLink(requestBody)
			.subscribeOn(Schedulers.io())
	}

	fun qrLinkStatusWait(requestBody: QrLinkStatusWaitBody): Single<QrLinkStatusWaitResponse> {
		return apiService.qrLinkStatusWait(requestBody)
			.subscribeOn(Schedulers.io())
	}

	fun getBinInfo(firstSixDigits: String): Single<KvellPaymentsBinInfo> =
		if (firstSixDigits.length < 6) {
			Single.error(KvellPaymentsTransactionError("You must specify the first 6 digits of the card number"))
		} else {
			val firstSix = firstSixDigits.subSequence(0, 6).toString()
			apiService.getBinInfo(firstSix)
					.subscribeOn(Schedulers.io())
					.map { it.binInfo ?: KvellPaymentsBinInfo("", "") }
					.onErrorReturn { KvellPaymentsBinInfo("", "") }
		}
}