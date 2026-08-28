package ru.kvell.sdk.sbp

import android.net.Uri
import android.os.Handler
import android.os.Looper

internal class SbpPaymentCoordinator(
	private val handler: SbpPaymentHandler?,
	private val pollingIntervalMs: Long,
	private val timeoutMs: Long
) {
	enum class State {
		Idle, Preparing, PresentingRedirect, WaitingForReturn, Polling,
		Success, Failure, Cancelled, Timeout
	}

	var onStateChange: ((State) -> Unit)? = null
	var onPresentRedirect: ((String) -> Unit)? = null
	var onReturnForStatusCheck: ((() -> Unit) -> Unit)? = null
	var onSuccess: ((Int?) -> Unit)? = null
	var onFailure: ((String) -> Unit)? = null
	var onClosed: (() -> Unit)? = null

	var state: State = State.Idle
		private set(value) {
			field = value
			onStateChange?.invoke(value)
		}

	var session: SbpPaymentSession? = null
		private set

	private val mainHandler = Handler(Looper.getMainLooper())
	private var timeoutRunnable: Runnable? = null
	private var pollRunnable: Runnable? = null

	private var isForeground = true
	private var statusRequestInFlight = false
	private var needsStatusCheck = false
	private var closeAfterStatusCheck = false
	private var wasInBackground = false
	private var returnTransitionInFlight = false
	private var deferredStatusResult: Pair<Result<SbpPaymentStatus>, SbpPaymentSession>? = null
	private var terminalCallbackSent = false
	private var disposed = false

	private val isRunning: Boolean
		get() = when (state) {
			State.Idle, State.Success, State.Failure, State.Cancelled, State.Timeout -> false
			State.Preparing, State.PresentingRedirect, State.WaitingForReturn, State.Polling -> true
		}

	private fun isApplicationActive(): Boolean = isForeground

	fun start(request: SbpPaymentRequest) = runOnMain {
		if (state != State.Idle) return@runOnMain
		val handler = handler ?: run {
			finishFailure(SbpPaymentError.HandlerNotConfigured.message ?: "")
			return@runOnMain
		}

		state = State.Preparing
		scheduleTimeout()
		handler.preparePayment(request) { result ->
			runOnMain { handlePreparation(result) }
		}
	}

	fun redirectDidPresent() = runOnMain {
		if (state == State.PresentingRedirect) checkStatus()
	}

	fun handleManualClose() = runOnMain {
		if (!isRunning) return@runOnMain
		if (session == null) {
			finishClosed(State.Cancelled)
			return@runOnMain
		}
		closeAfterStatusCheck = true
		checkStatus()
	}

	fun onEnterBackground() = runOnMain {
		isForeground = false
		if (!isRunning) return@runOnMain
		wasInBackground = true
		stopPolling()
		state = State.WaitingForReturn
	}

	fun onEnterForeground() = runOnMain {
		isForeground = true
		if (!isRunning || !wasInBackground) return@runOnMain
		wasInBackground = false
		checkStatusAfterReturn()
	}

	fun dispose() {
		disposed = true
		stopWork()
	}

	private fun handlePreparation(result: Result<SbpPaymentSession>) {
		if (disposed || state != State.Preparing || terminalCallbackSent) return

		result.fold(
			onSuccess = { session ->
				if (session.sessionId.trim().isEmpty()) {
					finishFailure(SbpPaymentError.InvalidSession.message ?: "")
					return
				}
				val redirect = session.redirectUrl
				if (redirect == null || !isSecureRedirect(redirect)) {
					finishFailure(SbpPaymentError.InvalidRedirectUrl.message ?: "")
					return
				}
				this.session = session
				state = State.PresentingRedirect
				onPresentRedirect?.invoke(redirect)
			},
			onFailure = { error ->
				finishFailure(SbpPaymentError.PreparationFailed(error.localizedMessage).message ?: "")
			}
		)
	}

	private fun checkStatus() {
		val session = session ?: return
		val handler = handler ?: return
		if (!isRunning) return

		if (!isApplicationActive()) {
			stopPolling()
			needsStatusCheck = true
			state = State.WaitingForReturn
			return
		}
		if (statusRequestInFlight) {
			needsStatusCheck = true
			return
		}

		stopPolling()
		needsStatusCheck = false
		statusRequestInFlight = true
		state = State.Polling

		handler.resolveStatus(session) { result ->
			runOnMain { handleStatus(result, session) }
		}
	}

	private fun handleStatus(result: Result<SbpPaymentStatus>, checkedSession: SbpPaymentSession) {
		if (disposed || session?.sessionId != checkedSession.sessionId || !isRunning) return
		if (returnTransitionInFlight) {
			deferredStatusResult = result to checkedSession
			return
		}
		statusRequestInFlight = false

		val status = result.getOrNull()
		when (status) {
			is SbpPaymentStatus.Success -> finishSuccess(status.transactionId ?: checkedSession.transactionId)
			is SbpPaymentStatus.Failure -> finishFailure(status.message ?: "Оплата через СБП отклонена")
			is SbpPaymentStatus.Cancelled -> finishFailure("Оплата через СБП отменена", State.Cancelled)
			// Pending либо ошибка запроса (transient) — не считаем подтверждением, продолжаем ожидание.
			SbpPaymentStatus.Pending, null -> {
				when {
					closeAfterStatusCheck -> finishClosed(State.Cancelled)
					!isApplicationActive() -> state = State.WaitingForReturn
					needsStatusCheck -> {
						needsStatusCheck = false
						checkStatus()
					}
					else -> startPolling()
				}
			}
		}
	}

	private fun startPolling() {
		if (!isRunning || !isApplicationActive()) {
			state = State.WaitingForReturn
			return
		}
		state = State.Polling
		pollRunnable?.let { mainHandler.removeCallbacks(it) }
		val runnable = Runnable { checkStatus() }
		pollRunnable = runnable
		mainHandler.postDelayed(runnable, pollingIntervalMs)
	}

	private fun checkStatusAfterReturn() {
		if (!isRunning) return
		if (returnTransitionInFlight) {
			needsStatusCheck = true
			return
		}

		stopPolling()
		needsStatusCheck = true
		state = State.WaitingForReturn
		returnTransitionInFlight = true

		val completion: () -> Unit = {
			runOnMain {
				if (isRunning) {
					returnTransitionInFlight = false
					val deferred = deferredStatusResult
					if (deferred != null) {
						deferredStatusResult = null
						handleStatus(deferred.first, deferred.second)
					} else {
						checkStatus()
					}
				}
			}
		}

		val transition = onReturnForStatusCheck
		if (transition != null) transition(completion) else completion()
	}

	private fun scheduleTimeout() {
		timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
		val runnable = Runnable {
			if (isRunning) finishFailure(SbpPaymentError.Timeout.message ?: "", State.Timeout)
		}
		timeoutRunnable = runnable
		mainHandler.postDelayed(runnable, timeoutMs)
	}

	private fun finishSuccess(transactionId: Int?) = finish(State.Success) { onSuccess?.invoke(transactionId) }

	private fun finishFailure(message: String, state: State = State.Failure) =
		finish(state) { onFailure?.invoke(message) }

	private fun finishClosed(state: State) = finish(state) { onClosed?.invoke() }

	private fun finish(terminalState: State, callback: () -> Unit) {
		if (terminalCallbackSent) return
		terminalCallbackSent = true
		stopWork()
		state = terminalState
		callback()
	}

	private fun stopPolling() {
		pollRunnable?.let { mainHandler.removeCallbacks(it) }
		pollRunnable = null
	}

	private fun stopWork() {
		stopPolling()
		timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
		timeoutRunnable = null
	}

	private fun runOnMain(work: () -> Unit) {
		if (Looper.myLooper() == Looper.getMainLooper()) work() else mainHandler.post(work)
	}

	private fun isSecureRedirect(url: String): Boolean {
		if (!url.startsWith("https://", ignoreCase = true)) return false
		return !Uri.parse(url).host.isNullOrEmpty()
	}
}
