package ru.kvell.sdk.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.fragment.app.viewModels
import ru.kvell.sdk.R
import ru.kvell.sdk.databinding.DialogKvellPaymentProcessBinding
import ru.kvell.sdk.models.ApiError
import ru.kvell.sdk.ui.PaymentActivity
import ru.kvell.sdk.ui.dialogs.base.BasePaymentDialogFragment
import ru.kvell.sdk.util.InjectorUtils
import ru.kvell.sdk.viewmodel.PaymentProcessViewModel
import ru.kvell.sdk.viewmodel.PaymentProcessViewState

internal enum class PaymentProcessStatus {
	InProcess,
	TinkoffPay,
	Succeeded,
	Failed;
}

internal class PaymentProcessFragment: BasePaymentDialogFragment<PaymentProcessViewState, PaymentProcessViewModel>(), ThreeDsDialogFragment.ThreeDSDialogListener {
	interface IPaymentProcessFragment {
		fun onPaymentFinished(transactionId: Int)
		fun onPaymentFailed(transactionId: Int, reasonCode: Int?)
		fun finishPayment()
		fun retryPayment()
	}

	companion object {
		private const val ARG_CRYPTOGRAM = "ARG_CRYPTOGRAM"
		private const val ARG_MODE = "ARG_MODE"
		const val MODE_CARD = "MODE_CARD"
		const val MODE_GOOGLE_PAY = "MODE_GOOGLE_PAY"
		const val MODE_YANDEX_PAY = "MODE_YANDEX_PAY"
		const val MODE_TINKOFF_PAY = "MODE_TINKOFF_PAY"

		fun newInstance(mode: String, cryptogram: String) = PaymentProcessFragment().apply {
			arguments = Bundle()
			arguments?.putString(ARG_MODE, mode)
			arguments?.putString(ARG_CRYPTOGRAM, cryptogram)
		}

		fun newInstance(mode: String) = PaymentProcessFragment().apply {
			arguments = Bundle()
			arguments?.putString(ARG_MODE, mode)
		}
	}

	private var _binding: DialogKvellPaymentProcessBinding? = null

	private val binding get() = _binding!!

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		_binding = DialogKvellPaymentProcessBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
	}

	private var currentState: PaymentProcessViewState? = null

	override val viewModel: PaymentProcessViewModel by viewModels {
		InjectorUtils.providePaymentProcessViewModelFactory(
			paymentConfiguration!!.paymentData,
			cryptogram,
			paymentConfiguration!!.useDualMessagePayment,
			(activity as PaymentActivity).payParams.saveCard)
	}

	override fun render(state: PaymentProcessViewState) {
		currentState = state
		updateWith(state.status, state.errorMessage)

		if (!state.acsUrl.isNullOrEmpty() && !state.paReq.isNullOrEmpty() && state.transaction?.transactionId != null) {
			val dialog = ThreeDsDialogFragment.newInstance(state.acsUrl, state.paReq, state.transaction.transactionId.toString())
			dialog.setTargetFragment(this, 1)
			dialog.show(parentFragmentManager, null)

			viewModel.clearThreeDsData()
		}

		if (!state.qrUrl.isNullOrEmpty() && state.transactionId != 0) {
			val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.qrUrl))
			context?.startActivity(intent)

			viewModel.qrLinkStatusWait(state.transactionId)
			viewModel.clearQrLinkData()
		}
	}

	private val mode by lazy {
		arguments?.getString(ARG_MODE) ?: ""
	}

	private val cryptogram by lazy {
		arguments?.getString(ARG_CRYPTOGRAM) ?: ""
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		if (savedInstanceState == null) {
			activity().component.inject(viewModel)

			if (mode == MODE_TINKOFF_PAY) {
				updateWith(PaymentProcessStatus.TinkoffPay)
				viewModel.getTinkoffQrPayLink()
			} else {
				updateWith(PaymentProcessStatus.InProcess)
				viewModel.pay()
			}
		}
	}

	private fun updateWith(status: PaymentProcessStatus, error: String? = null) {

		var status = status

		if (mode == MODE_TINKOFF_PAY && status == PaymentProcessStatus.InProcess) {
			status = PaymentProcessStatus.TinkoffPay
		}

		when (status) {
			PaymentProcessStatus.InProcess -> {
				binding.iconStatus.setImageResource(R.drawable.kvell_ic_progress)
				binding.textStatus.setText(R.string.kvell_text_process_title)
				binding.textDescription.text = ""
				binding.buttonFinish.isInvisible = true
			}

			PaymentProcessStatus.TinkoffPay -> {
				binding.iconStatus.setImageResource(R.drawable.kvell_ic_progress)
				binding.textStatus.setText(R.string.kvell_text_process_title_tinkoff_pay)
				binding.textDescription.setText(R.string.kvell_text_process_description_tinkoff_pay)
				binding.buttonFinish.isInvisible = false
				binding.buttonFinish.setText(R.string.kvell_text_process_button_tinkoff_pay)
				binding.buttonFinish.setBackgroundResource(R.drawable.kvell_bg_rounded_white_button_with_border)
				binding.buttonFinish.setTextColor(context?.let { ContextCompat.getColor(it, R.color.kvell_blue) } ?: 0xFFFFFF)

				binding.buttonFinish.setOnClickListener {

					val listener = requireActivity() as? IPaymentProcessFragment
					listener?.retryPayment()
					dismiss()
				}
			}

			PaymentProcessStatus.Succeeded, PaymentProcessStatus.Failed -> {
				binding.buttonFinish.isInvisible = false
				binding.buttonFinish.setBackgroundResource(R.drawable.kvell_bg_rounded_blue_button)
				binding.buttonFinish.setTextColor(context?.let { ContextCompat.getColor(it, R.color.kvell_white) } ?: 0xFFFFFF)

				val listener = requireActivity() as? IPaymentProcessFragment

				if (status == PaymentProcessStatus.Succeeded) {
					binding.iconStatus.setImageResource(R.drawable.img_success)
					binding.textStatus.setText(R.string.kvell_text_process_title_success)
					binding.textDescription.text = ""
					binding.buttonFinish.setText(R.string.kvell_text_process_button_success)

					listener?.onPaymentFinished(currentState?.transaction?.transactionId ?: 0)

					binding.buttonFinish.setOnClickListener {

						listener?.finishPayment()
						dismiss()
					}
				} else {

					binding.iconStatus.setImageResource(R.drawable.img_not_success)
					binding.textStatus.text =
						context?.let { ApiError.getErrorDescription(it, currentState?.reasonCode.toString()) }
					binding.textDescription.text =
						context?.let { ApiError.getErrorDescriptionExtra(it, currentState?.reasonCode.toString()) }

					binding.buttonFinish.setText(R.string.kvell_text_process_button_error)

					listener?.onPaymentFailed(currentState?.transaction?.transactionId ?: 0, currentState?.reasonCode)

					binding.buttonFinish.setOnClickListener {

						listener?.retryPayment()
						dismiss()
					}
				}
			}
		}
	}

	override fun onAuthorizationCompleted(md: String, paRes: String) {
		viewModel.finishThreeDs(md, paRes)
	}

	override fun onAuthorizationFailed(error: String?) {
		updateWith(PaymentProcessStatus.Failed, error)
	}
}