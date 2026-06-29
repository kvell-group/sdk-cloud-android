package ru.kvell.sdk.util

import ru.kvell.sdk.configuration.PaymentData
import ru.kvell.sdk.viewmodel.PaymentProcessViewModelFactory

internal object InjectorUtils {
    fun providePaymentProcessViewModelFactory(paymentData: PaymentData, cryptogram: String, useDualMessagePayment: Boolean, saveCard: Boolean?): PaymentProcessViewModelFactory {
        return PaymentProcessViewModelFactory(paymentData, cryptogram, useDualMessagePayment, saveCard)
    }
}