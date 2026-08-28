package ru.kvell.sdk.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import ru.kvell.sdk.R
import ru.kvell.sdk.configuration.KvellPaymentsSDK
import ru.kvell.sdk.databinding.DialogKvellPaymentProcessBinding
import ru.kvell.sdk.models.ApiError
import ru.kvell.sdk.models.Currency
import ru.kvell.sdk.sbp.SbpPaymentCoordinator
import ru.kvell.sdk.sbp.SbpPaymentRequest
import ru.kvell.sdk.ui.PaymentActivity
import java.util.UUID

internal class SbpPaymentFragment : DialogFragment() {

	companion object {
		fun newInstance() = SbpPaymentFragment().apply { arguments = Bundle() }
	}

	private var _binding: DialogKvellPaymentProcessBinding? = null
	private val binding get() = _binding!!

	private var coordinator: SbpPaymentCoordinator? = null
	private var terminalNotified = false

	private val idempotencyKey: String by lazy {
		val data = paymentConfiguration?.paymentData
		data?.invoiceId?.takeIf { it.isNotBlank() }
			?: data?.let { d ->
				d.accountId?.takeIf { it.isNotBlank() }?.let { "$it:${d.amount}:${d.currency}" }
			}
			?: UUID.randomUUID().toString()
	}

	private val paymentConfiguration by lazy {
		(activity as? PaymentActivity)?.paymentConfiguration
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setStyle(STYLE_NO_TITLE, R.style.kvell_DialogFullScreen)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		_binding = DialogKvellPaymentProcessBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onStart() {
		super.onStart()
		dialog?.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		renderOrderSummary()
		showPending()

		if (savedInstanceState == null) {
			startSbpPayment()
		}
	}

	override fun onResume() {
		super.onResume()
		coordinator?.onEnterForeground()
	}

	override fun onPause() {
		super.onPause()
		coordinator?.onEnterBackground()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		coordinator?.dispose()
		coordinator = null
		_binding = null
	}

	private fun startSbpPayment() {
		val configuration = paymentConfiguration ?: run {
			showError(null)
			return
		}
		val data = configuration.paymentData

		val coordinator = SbpPaymentCoordinator(
			handler = KvellPaymentsSDK.sbpPaymentHandler,
			pollingIntervalMs = configuration.sbpPollingIntervalMs,
			timeoutMs = configuration.sbpTimeoutMs
		)
		this.coordinator = coordinator

		coordinator.onPresentRedirect = { url -> presentRedirect(url) }
		coordinator.onSuccess = { transactionId -> onSbpSuccess(transactionId) }
		coordinator.onFailure = { message -> showError(message) }
		coordinator.onClosed = { onSbpClosed() }

		val request = SbpPaymentRequest(
			idempotencyKey = idempotencyKey,
			amount = data.amount,
			currency = data.currency,
			invoiceId = data.invoiceId,
			accountId = data.accountId,
			paymentDescription = data.description?.takeIf { it.isNotBlank() }
		)

		coordinator.start(request)
	}

	private fun presentRedirect(url: String) {
		try {
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
			coordinator?.redirectDidPresent()
		} catch (e: android.content.ActivityNotFoundException) {
			coordinator?.dispose()
			showError(null)
		}
	}

	private fun onSbpSuccess(transactionId: Int?) {
		if (!terminalNotified) {
			terminalNotified = true
			(requireActivity() as? PaymentProcessFragment.IPaymentProcessFragment)
				?.onPaymentFinished(transactionId ?: 0)
		}
		showSuccess()
	}

	private fun onSbpClosed() {
		(requireActivity() as? PaymentProcessFragment.IPaymentProcessFragment)?.retryPayment()
		dismiss()
	}

	private fun showPending() {
		showLoader(true)
		binding.textStatus.setText(R.string.kvell_text_process_title_sbp)
		binding.textDescription.setText(R.string.kvell_text_process_description_sbp)
		binding.buttonFinish.isInvisible = false
		binding.buttonFinish.setText(R.string.kvell_text_process_button_sbp)
		binding.buttonFinish.setBackgroundResource(R.drawable.kvell_bg_rounded_white_button_with_border)
		binding.buttonFinish.setTextColor(context?.let { ContextCompat.getColor(it, R.color.kvell_blue) } ?: 0xFFFFFFFF.toInt())
		binding.buttonFinish.setOnClickListener {
			coordinator?.handleManualClose()
		}
	}

	private fun showSuccess() {
		showLoader(false)
		binding.iconStatus.setImageResource(R.drawable.img_success)
		binding.textStatus.setText(R.string.kvell_text_process_title_success)
		binding.textDescription.text = ""
		binding.buttonFinish.isInvisible = false
		binding.buttonFinish.setBackgroundResource(R.drawable.kvell_bg_rounded_black_button)
		binding.buttonFinish.setTextColor(context?.let { ContextCompat.getColor(it, R.color.kvell_white) } ?: 0xFFFFFFFF.toInt())
		binding.buttonFinish.setText(R.string.kvell_text_process_button_success)
		binding.buttonFinish.setOnClickListener {
			(requireActivity() as? PaymentProcessFragment.IPaymentProcessFragment)?.finishPayment()
			dismiss()
		}
	}

	private fun showError(message: String?) {
		if (!terminalNotified) {
			terminalNotified = true
			(requireActivity() as? PaymentProcessFragment.IPaymentProcessFragment)
				?.onPaymentFailed(0, null)
		}
		showLoader(false)
		binding.iconStatus.setImageResource(R.drawable.img_not_success)
		binding.textStatus.setText(R.string.kvell_text_process_title_error)
		binding.textDescription.text = message ?: context?.let { ApiError.getErrorDescription(it, "0") } ?: ""
		binding.buttonFinish.isInvisible = false
		binding.buttonFinish.setBackgroundResource(R.drawable.kvell_bg_rounded_black_button)
		binding.buttonFinish.setTextColor(context?.let { ContextCompat.getColor(it, R.color.kvell_white) } ?: 0xFFFFFFFF.toInt())
		binding.buttonFinish.setText(R.string.kvell_text_process_button_error)
		binding.buttonFinish.setOnClickListener {
			(requireActivity() as? PaymentProcessFragment.IPaymentProcessFragment)?.retryPayment()
			dismiss()
		}
	}

	private fun showLoader(loading: Boolean) {
		binding.progressStatus.isVisible = loading
		binding.iconStatus.isVisible = !loading
	}

	private fun renderOrderSummary() {
		val data = paymentConfiguration?.paymentData ?: return
		val amount = data.amount.toDoubleOrNull() ?: 0.0
		binding.textOrderTotal.text = String.format("%.2f %s", amount, Currency.getSymbol(data.currency))
	}
}
