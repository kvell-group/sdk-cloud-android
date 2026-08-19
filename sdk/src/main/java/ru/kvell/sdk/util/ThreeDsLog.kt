package ru.kvell.sdk.util

import android.util.Log
import ru.kvell.sdk.BuildConfig

internal object ThreeDsLog {
	private const val TAG = "KvellSDK3DS"

	fun start() {
		if (BuildConfig.DEBUG) d("=== 3DS session ===")
	}

	fun d(message: String) {
		if (BuildConfig.DEBUG) Log.d(TAG, message)
	}
}
