## KvellPayments SDK for Android

KvellPayments SDK позволяет интегрировать приём платежей в мобильные приложения для платформы Android.
SDK работает с платёжным сервисом Kvell (pay-pulse) и даёт нативную платёжную форму вместо WebView.

### Требования
Android 6.0 или выше (API level 23).

### Структура проекта
* **sdk** — исходный код SDK
* **app** — пример приложения с использованием SDK

### Подключение
Пока SDK поставляется исходным кодом: подключите модуль `:sdk` в `settings.gradle` своего проекта или соберите `.aar` из модуля `sdk`.

```
implementation project(':sdk')
```

Если используется Yandex Pay — добавьте Yandex Client ID (если не используется, оставьте пустым):

```
android {
    defaultConfig {
        manifestPlaceholders = [
            YANDEX_CLIENT_ID: ""
        ]
    }
}
```

### Авторизация
Для запросов нужны **PublicId** и **ApiSecret** терминала (выдаются в Kvell). SDK авторизуется по HTTP Basic (`PublicId:ApiSecret`).
Не храните `ApiSecret` в коде релизного приложения — передавайте его из защищённого источника.

## Способы использования

Вы можете использовать SDK одним из способов:
* стандартная платёжная форма KvellPayments;
* своя форма с использованием функций `KvellPaymentsApi`.

### Стандартная платёжная форма

1. Создайте лаунчер для получения результата через Activity Result API:

```
val launcher = KvellPaymentsSDK.getInstance().launcher(this, result = {
    if (it.status != null) {
        if (it.status == KvellPaymentsSDK.TransactionStatus.Succeeded) {
            Toast.makeText(this, "Успешно! Транзакция №${it.transactionId}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Ошибка! Транзакция №${it.transactionId}. Код ${it.reasonCode}", Toast.LENGTH_SHORT).show()
        }
    }
})
```

2. Создайте `PaymentData` (при необходимости — `PaymentDataPayer` с данными плательщика):

```
val payer = PaymentDataPayer().apply {
    firstName = "Ivan"
    lastName = "Ivanov"
    phone = "+79991234567" // формат +<11 цифр>
}

val paymentData = PaymentData(
    amount = "100",          // сумма
    currency = "RUB",        // валюта
    invoiceId = "AB1234",    // номер счёта/заказа
    description = "Заказ",    // описание
    accountId = "user-1",    // идентификатор пользователя
    email = "user@mail.ru",  // e-mail для квитанции
    payer = payer,           // данные плательщика
    jsonData = "{\"name\":\"Ivan\"}"
)
```

3. Создайте `PaymentConfiguration`, передав `publicId`, `apiSecret`, `paymentData`:

```
val configuration = PaymentConfiguration(
    publicId = publicId,             // PublicId терминала Kvell
    apiSecret = apiSecret,           // ApiSecret терминала Kvell
    paymentData = paymentData,       // информация о платеже
    scanner = CardIOScanner(),       // сканер карт (опционально)
    requireEmail = false,            // требовать e-mail
    useDualMessagePayment = false,   // двухстадийная схема (auth), по умолчанию одностадийная (charge)
    disableGPay = false,
    disableYandexPay = false,
    yandexPayMerchantID = "",
    apiUrl = ""                      // пусто = боевой URL по умолчанию
)
```

4. Запустите форму оплаты:

```
launcher.launch(configuration)
```

### Своя форма через KvellPaymentsApi

1. Получите публичный ключ и сформируйте криптограмму. Криптограмма строится из данных карты и `publicId` (имя держателя в неё **не** входит):

```
val api = KvellPaymentsApi(/* ... */) // или KvellPaymentsSDK.createApi(publicId, apiSecret)

val key = api.getPublicKey().blockingGet() // pem + version
val cryptogram = Card.createHexPacketFromData(
    cardNumber, cardExp, cardCvv,
    publicId, key.pem ?: "", key.version ?: 0
)
```

2. Проведите платёж — `charge` (одностадийный) или `auth` (двухстадийный):

```
val api = KvellPaymentsSDK.createApi(publicId, apiSecret)
val body = PaymentRequestBody(
    amount = "100",
    currency = "RUB",
    ipAddress = "127.0.0.1",
    cryptogram = cryptogram
)
api.charge(body)
    .toObservable()
    .flatMap(KvellPaymentsTransactionResponse::handleError)
    .map { it.transaction }
```

3. Если требуется 3DS — покажите форму подтверждения:

```
val acsUrl = transaction.acsUrl
val paReq = transaction.paReq
val md = transaction.transactionId.toString()
ThreeDsDialogFragment
    .newInstance(acsUrl, paReq, md)
    .show(supportFragmentManager, "3DS")
```

4. Реализуйте `ThreeDSDialogListener` и завершите оплату через `post3ds` (`TransactionId` + `PaRes`):

```
override fun onAuthorizationCompleted(md: String, paRes: String) {
    api.postThreeDs(md.toInt(), paRes)
}

override fun onAuthorizationFailed(error: String?) {
    Log.d("Error", "AuthorizationFailed: $error")
}
```

### Вспомогательные функции

```
Card.isValidNumber(cardNumber)                 // проверка номера карты
Card.isValidExpDate(expDate)                   // срок действия, формат MM/yy
Card.getType(cardNumber)                        // тип платёжной системы
Card.cardCryptogramForCVV(cvv)                  // криптограмма CVV для оплаты сохранённой картой
```

### Сканер карт
Подключается любой сканер, вызываемый через Activity. Реализуйте `CardScanner` и передайте объект в `PaymentConfiguration`. Если не реализован — кнопка сканирования не показывается. Пример со сканером CardIO — в модуле `app`.

### Ограничения текущей версии
Внешний сервис пока не поддерживает часть методов, поэтому в SDK скрыты:
* определение банка-эмитента (`bins/info`);
* конфигурация мерчанта (`merchant/configuration`);
* TinkoffPay / оплата по QR.

Будут включены по мере появления соответствующих эндпоинтов.

### Поддержка
По техническим вопросам — через issues этого репозитория.
