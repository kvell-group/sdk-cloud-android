package ru.kvell.demo.api

import android.util.Log
import com.google.gson.annotations.SerializedName
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import ru.kvell.sdk.api.AuthenticationInterceptor
import ru.kvell.sdk.api.models.KvellPaymentsGetTinkoffPayQrLinkResponse
import ru.kvell.sdk.api.models.QrLinkStatusWaitBody
import ru.kvell.sdk.api.models.QrLinkStatusWaitResponse
import ru.kvell.sdk.sbp.SbpPaymentHandler
import ru.kvell.sdk.sbp.SbpPaymentRequest
import ru.kvell.sdk.sbp.SbpPaymentSession
import ru.kvell.sdk.sbp.SbpPaymentStatus


class SbpTestHandler(private val publicId: String, apiSecret: String) : SbpPaymentHandler {

	private companion object {
		const val BASE_URL = "https://cloud.wallet.kvell.group/"
		const val REDIRECT_URL = "https://kvell.group"
		const val TAG = "SbpTest"
	}

	private data class SbpLinkBody(
		@SerializedName("PublicId") val publicId: String,
		@SerializedName("Amount") val amount: String,
		@SerializedName("Currency") val currency: String,
		@SerializedName("Description") val description: String? = null,
		@SerializedName("AccountId") val accountId: String? = null,
		@SerializedName("InvoiceId") val invoiceId: String? = null,
		@SerializedName("Scheme") val scheme: String = "charge",
		@SerializedName("Webview") val webView: Boolean = true,
		@SerializedName("Device") val device: String = "MobileApp",
		@SerializedName("TtlMinutes") val ttlMinutes: Int = 30,
		@SerializedName("SuccessRedirectUrl") val successRedirectUrl: String = REDIRECT_URL,
		@SerializedName("FailRedirectUrl") val failRedirectUrl: String = REDIRECT_URL
	)

	private interface SbpService {
		@POST("payments/qr/sbp/link")
		fun sbpLink(@Body body: SbpLinkBody): Single<KvellPaymentsGetTinkoffPayQrLinkResponse>

		@POST("payments/qr/status/wait")
		fun status(@Body body: QrLinkStatusWaitBody): Single<QrLinkStatusWaitResponse>
	}

	private val disposables = CompositeDisposable()
	private var fallbackPolls = 0

	private val service: SbpService by lazy {
		val client = OkHttpClient.Builder()
			.addInterceptor(HttpLoggingInterceptor { message -> Log.i("SbpTestHttp", message) }
				.setLevel(HttpLoggingInterceptor.Level.BODY))
			.addInterceptor(AuthenticationInterceptor(publicId, apiSecret))
			.build()

		Retrofit.Builder()
			.baseUrl(BASE_URL)
			.addConverterFactory(GsonConverterFactory.create())
			.addCallAdapterFactory(RxJava2CallAdapterFactory.create())
			.client(client)
			.build()
			.create(SbpService::class.java)
	}

	override fun preparePayment(
		request: SbpPaymentRequest,
		completion: (Result<SbpPaymentSession>) -> Unit
	) {
		val body = SbpLinkBody(
			publicId = publicId,
			amount = request.amount,
			currency = request.currency,
			description = request.paymentDescription,
			accountId = request.accountId,
			invoiceId = request.invoiceId
		)

		disposables.add(
			service.sbpLink(body)
				.subscribeOn(Schedulers.io())
				.subscribe({ response ->
					val tx = response.transaction
					Log.i(TAG, "sbp/link success=${response.success} txId=${tx?.transactionId} qrUrl=${tx?.qrUrl}")
					completion(Result.success(SbpPaymentSession(
						sessionId = request.idempotencyKey,
						transactionId = tx?.transactionId,
						providerQrId = tx?.providerQrId,
						redirectUrl = REDIRECT_URL
					)))
				}, { error ->
					Log.w(TAG, "sbp/link error: ${error.message}")
					completion(Result.success(SbpPaymentSession(
						sessionId = request.idempotencyKey,
						transactionId = null,
						redirectUrl = REDIRECT_URL
					)))
				})
		)
	}

	override fun resolveStatus(
		session: SbpPaymentSession,
		completion: (Result<SbpPaymentStatus>) -> Unit
	) {
		val transactionId = session.transactionId
		if (transactionId == null) {

			fallbackPolls++
			completion(Result.success(
				if (fallbackPolls >= 2) SbpPaymentStatus.Success(null) else SbpPaymentStatus.Pending
			))
			return
		}

		disposables.add(
			service.status(QrLinkStatusWaitBody(transactionId))
				.subscribeOn(Schedulers.io())
				.subscribe({ response ->
					val status = response.transaction?.status
					Log.i(TAG, "status/wait success=${response.success} status=$status")
					val mapped = when (status) {
						"Authorized", "Completed", "Cancelled" -> SbpPaymentStatus.Success(response.transaction?.transactionId)
						"Declined" -> SbpPaymentStatus.Failure("Оплата отклонена")
						else -> SbpPaymentStatus.Pending
					}
					completion(Result.success(mapped))
				}, { error ->
					Log.w(TAG, "status/wait error: ${error.message}")

					completion(Result.success(SbpPaymentStatus.Pending))
				})
		)
	}
}
