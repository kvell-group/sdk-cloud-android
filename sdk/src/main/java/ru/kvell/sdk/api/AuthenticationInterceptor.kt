package ru.kvell.sdk.api

import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class AuthenticationInterceptor(
	private val publicId: String,
	private val apiSecret: String = ""
) : Interceptor {

	@Throws(IOException::class)
	override fun intercept(chain: Interceptor.Chain): Response {
		val original: Request = chain.request()

		val raw = "$publicId:$apiSecret"
		val token = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

		val request: Request = original.newBuilder()
			.header("Authorization", "Basic $token")
			.build()
		return chain.proceed(request)
	}
}
