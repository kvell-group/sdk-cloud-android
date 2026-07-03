package ru.kvell.sdk.api

import io.reactivex.Single
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.kvell.sdk.api.models.KvellPaymentsBinInfoResponse
import ru.kvell.sdk.api.models.KvellPaymentsGetTinkoffPayQrLinkResponse
import ru.kvell.sdk.api.models.KvellPaymentsMerchantConfigurationResponse
import ru.kvell.sdk.api.models.KvellPaymentsPublicKeyResponse
import ru.kvell.sdk.api.models.PaymentGetBody
import ru.kvell.sdk.api.models.PaymentRequestBody
import ru.kvell.sdk.api.models.ThreeDsRequestBody
import ru.kvell.sdk.api.models.KvellPaymentsTransactionResponse
import ru.kvell.sdk.api.models.QrLinkStatusWaitBody
import ru.kvell.sdk.api.models.QrLinkStatusWaitResponse
import ru.kvell.sdk.api.models.TinkoffPayQrLinkBody

interface KvellPaymentsApiService {
	@POST("payments/cards/charge")
	fun charge(@Body body: PaymentRequestBody): Single<KvellPaymentsTransactionResponse>

	@POST("payments/cards/auth")
	fun auth(@Body body: PaymentRequestBody): Single<KvellPaymentsTransactionResponse>

	@POST("payments/cards/post3ds")
	fun postThreeDs(@Body body: ThreeDsRequestBody): Single<KvellPaymentsTransactionResponse>

	@POST("payments/get")
	fun getPayment(@Body body: PaymentGetBody): Single<KvellPaymentsTransactionResponse>

	@GET("bins/info/{firstSixDigits}")
	fun getBinInfo(@Path("firstSixDigits") firstSixDigits: String): Single<KvellPaymentsBinInfoResponse>

	@GET("payments/publickey")
	fun getPublicKey(): Single<KvellPaymentsPublicKeyResponse>

	@GET("merchant/configuration")
	fun getMerchantConfiguration(@Query("terminalPublicId") publicId: String): Single<KvellPaymentsMerchantConfigurationResponse>

	@POST("payments/qr/tinkoffpay/link")
	fun getTinkoffPayQrLink(@Body body: TinkoffPayQrLinkBody): Single<KvellPaymentsGetTinkoffPayQrLinkResponse>

	@POST("payments/qr/status/wait")
	fun qrLinkStatusWait(@Body body: QrLinkStatusWaitBody): Single<QrLinkStatusWaitResponse>
}