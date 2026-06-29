package ru.kvell.sdk.api.models

import com.google.gson.annotations.SerializedName
import io.reactivex.Observable

data class KvellPaymentsGetTinkoffPayQrLinkResponse(
	@SerializedName("Success") val success: Boolean?,
	@SerializedName("Message") val message: String?,
	@SerializedName("Model") val transaction: KvellPaymentsTinkoffPayQrLinkTransaction?) {
	fun handleError(): Observable<KvellPaymentsGetTinkoffPayQrLinkResponse> {
		return if (success == true ) {
			Observable.just(this)
		} else {
			Observable.error(KvellPaymentsTransactionError(message ?: ""))
		}
	}
}