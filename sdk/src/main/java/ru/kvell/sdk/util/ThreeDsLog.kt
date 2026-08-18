package ru.kvell.sdk.util

import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ThreeDsLog {
	private const val TAG = "KvellSDK3DS"
	private val lineTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
	private val sessionTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
	private val buffer = StringBuilder()

	@Synchronized
	fun start() {
		buffer.setLength(0)
		d("=== 3DS session ${sessionTime.format(Date())} ===")
	}

	@Synchronized
	fun d(message: String) {
		Log.d(TAG, message)
		buffer.append(lineTime.format(Date())).append("  ").append(message).append('\n')
	}

	@Synchronized
	fun isEmpty(): Boolean = buffer.isEmpty()

	@Synchronized
	private fun text(): String = buffer.toString()

	fun share(context: Context) {
		if (isEmpty()) return
		val send = Intent(Intent.ACTION_SEND).apply {
			type = "text/plain"
			putExtra(Intent.EXTRA_SUBJECT, "Kvell 3DS log ${sessionTime.format(Date())}")
			putExtra(Intent.EXTRA_TEXT, text())
		}
		context.startActivity(
			Intent.createChooser(send, "Отправить лог 3DS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		)
	}
}
