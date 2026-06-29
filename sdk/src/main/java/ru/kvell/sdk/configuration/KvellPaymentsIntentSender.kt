package ru.kvell.sdk.configuration

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import ru.kvell.sdk.models.Transaction

internal class KvellPaymentsIntentSender : ActivityResultContract<PaymentConfiguration, Transaction>() {
	override fun createIntent(context: Context, input: PaymentConfiguration): Intent {
		return KvellPaymentsSDK.getInstance().getStartIntent(context, input)
	}

	override fun parseResult(resultCode: Int, intent: Intent?): Transaction {
		if (resultCode == Activity.RESULT_OK) {
			val id = intent?.getIntExtra(KvellPaymentsSDK.IntentKeys.TransactionId.name, 0) ?: 0
			val status = intent?.getSerializableExtra(KvellPaymentsSDK.IntentKeys.TransactionStatus.name) as? KvellPaymentsSDK.TransactionStatus
			val reasonCode = intent?.getIntExtra(KvellPaymentsSDK.IntentKeys.TransactionReasonCode.name, 0) ?: 0

			return Transaction(id, status, reasonCode)
		}

		return Transaction(0, null, 0)
	}
}