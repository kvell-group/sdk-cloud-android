package ru.kvell.sdk.viewmodel

import androidx.lifecycle.MutableLiveData
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import ru.kvell.sdk.api.KvellPaymentsApi
import javax.inject.Inject

internal class PaymentCardViewModel: BaseViewModel<PaymentCardViewState>() {
	override var currentState = PaymentCardViewState()
	override val viewState: MutableLiveData<PaymentCardViewState> by lazy {
		MutableLiveData(currentState)
	}

	private var disposable: Disposable? = null

	@Inject lateinit var api: KvellPaymentsApi

	fun getPublicKey() {
		disposable = api.getPublicKey()
			.toObservable()
			.observeOn(AndroidSchedulers.mainThread())
			.map { response ->
				val state = currentState.copy(publicKeyPem = response.pem, publicKeyVersion = response.version)
				stateChanged(state)
			}
			.onErrorReturn { }
			.subscribe()
	}

	private fun stateChanged(viewState: PaymentCardViewState) {
		currentState = viewState.copy()
		this.viewState.apply {
			value = viewState
		}
	}

	override fun onCleared() {
		super.onCleared()

		disposable?.dispose()
	}
}

internal data class PaymentCardViewState(
	val publicKeyPem: String? = null,
	val publicKeyVersion: Int? = null
): BaseViewState()
