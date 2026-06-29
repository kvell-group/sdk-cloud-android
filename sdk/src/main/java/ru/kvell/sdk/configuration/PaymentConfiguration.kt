package ru.kvell.sdk.configuration

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import ru.kvell.sdk.scanner.CardScanner

@Parcelize
data class PaymentConfiguration(val publicId: String,
								val apiSecret: String = "",
								val paymentData: PaymentData,
								val scanner: CardScanner?,
								val requireEmail: Boolean = false,
								val useDualMessagePayment: Boolean = false,
								val disableGPay: Boolean = false,
								val disableYandexPay: Boolean = false,
								val yandexPayMerchantID: String = "",
								val apiUrl: String = ""): Parcelable