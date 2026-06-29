package ru.kvell.sdk.dagger2

import dagger.Component
import dagger.Module
import dagger.Provides
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import ru.kvell.sdk.Constants
import ru.kvell.sdk.api.AuthenticationInterceptor
import ru.kvell.sdk.api.KvellPaymentsApiService
import ru.kvell.sdk.api.KvellPaymentsApi
import ru.kvell.sdk.viewmodel.PaymentCardViewModel
import ru.kvell.sdk.viewmodel.PaymentOptionsViewModel
import ru.kvell.sdk.viewmodel.PaymentProcessViewModel
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
class KvellPaymentsModule {
	@Provides
	@Singleton
	fun provideRepository(apiService: KvellPaymentsApiService)
			= KvellPaymentsApi(apiService)
}

@Module
class KvellPaymentsNetModule(private val publicId: String, private val apiSecret: String = "", private var apiUrl: String = Constants.baseApiUrl) {
	@Provides
	@Singleton
	fun providesHttpLoggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor()
		.setLevel(HttpLoggingInterceptor.Level.BODY)

	@Provides
	@Singleton
	fun providesAuthenticationInterceptor(): AuthenticationInterceptor
			= AuthenticationInterceptor(publicId, apiSecret)

	@Provides
	@Singleton
	fun provideOkHttpClientBuilder(loggingInterceptor: HttpLoggingInterceptor): OkHttpClient.Builder
			= OkHttpClient.Builder()
		.addInterceptor(loggingInterceptor)

	@Provides
	@Singleton
	fun provideApiService(okHttpClientBuilder: OkHttpClient.Builder,
						  authenticationInterceptor: AuthenticationInterceptor): KvellPaymentsApiService {
		val client = okHttpClientBuilder
			.addInterceptor(authenticationInterceptor)
			.connectTimeout(60, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.followRedirects(false)
			.build()

		if (apiUrl.isEmpty())
			apiUrl = Constants.baseApiUrl

		val retrofit = Retrofit.Builder()
			.baseUrl(apiUrl)
			.addConverterFactory(GsonConverterFactory.create())
			.addCallAdapterFactory(RxJava2CallAdapterFactory.create())
			.client(client)
			.build()

		return retrofit.create(KvellPaymentsApiService::class.java)
	}
}

@Singleton
@Component(modules = [KvellPaymentsModule::class, KvellPaymentsNetModule::class])
internal interface KvellPaymentsComponent {
	fun inject(optionsViewModel: PaymentOptionsViewModel)
	fun inject(cardViewModel: PaymentCardViewModel)
	fun inject(processViewModel: PaymentProcessViewModel)
}
