package ru.kvell.sdk.api.models

import com.google.gson.annotations.SerializedName
import io.reactivex.Observable

data class KvellPaymentsTransactionResponse(
	@SerializedName("Success") val success: Boolean?,
	@SerializedName("Message") val message: String?,
	@SerializedName("Model") val transaction: KvellPaymentsTransaction?) {
	fun handleError(): Observable<KvellPaymentsTransactionResponse> {
		return if (success == true || (!transaction?.acsUrl.isNullOrEmpty() && !transaction?.paReq.isNullOrEmpty())){
			Observable.just(this)
		} else {
			Observable.error(KvellPaymentsTransactionError(message ?: transaction?.cardHolderMessage.orEmpty()))
		}
	}
}