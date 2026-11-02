package ru.kvell.sdk.card

import android.content.res.Resources
import android.text.TextUtils
import android.util.Base64
import com.google.gson.GsonBuilder
import ru.kvell.sdk.api.models.CardCryptogramPacket
import ru.kvell.sdk.api.models.CardInfo
import ru.kvell.sdk.configuration.KvellPaymentsSDK
import ru.kvell.sdk.util.HexPacketHelper
import ru.kvell.sdk.util.PublicKey as StoredPublicKey
import java.io.UnsupportedEncodingException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import java.util.TimeZone
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import java.security.spec.X509EncodedKeySpec
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.NoSuchPaddingException

class Card {
	companion object {

		// Запасной ключ Kvell для cardCryptogram; эту версию нельзя удалять из wallet, пока в ходу сборки с этим SDK
		private const val FALLBACK_KEY_VERSION = 1
		private const val FALLBACK_PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0lWVYLO2qBYsIgzEG//nBk+nNh8SM79D7cCSQyrT5ce/c1mAoKK3THvCTfQwt0edk2eNB6NnTnBlKF2D+7Y1mr/KgNwYVtTz+e9kRmIw8wYCB+AYOFl87YVWneKqFWylhkjZLJhbvBx4aCryfLFARNn5zhZD7QCo7X+xg79wEvWRng0/eIpD8sbvbNB/8n8DVIlJSY0ICdtJu7SAlF5bRsXJOa9wX+/AUHWgq0UOs3yMZL80gDCVaEQlsWUYyqOK/shSgE8WdV8ZpWFbyLMUblghIN/pDLtY5cJr+G24wFZpxs042TcQ0CzCg9cB9SP+9dzcPFPWmKKxyV6LvotYfwIDAQAB"

		private val keyRequested = AtomicBoolean(false)

		// Ключ CloudPayments: им шифруется только cardCryptogramForCVV, Kvell такие криптограммы не расшифрует
		private fun getKeyVersion(): String {
			return "04"
		}

		private fun getPublicKey(): String {
			return "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArBZ1NNjvszen6BNWsgyDUJvDUZDtvR4jKNQtEwW1iW7hqJr0TdD8hgTxw3DfH+Hi/7ZjSNdH5EfChvgVW9wtTxrvUXCOyJndReq7qNMo94lHpoSIVW82dp4rcDB4kU+q+ekh5rj9Oj6EReCTuXr3foLLBVpH0/z1vtgcCfQzsLlGkSTwgLqASTUsuzfI8viVUbxE1a+600hN0uBh/CYKoMnCp/EhxV8g7eUmNsWjZyiUrV8AA/5DgZUCB+jqGQT/Dhc8e21tAkQ3qan/jQ5i/QYocA/4jW3WQAldMLj0PA36kINEbuDKq8qRh25v+k4qyjb7Xp4W2DywmNtG3Q20MQIDAQAB"
		}

		fun getType(number: String): CardType? {
			return CardType.fromString(number)
		}

		fun isValidNumber(cardNumber: String?): Boolean {
			return if (cardNumber == null) {
				false
			} else {
				val number = prepareCardNumber(cardNumber)
				return if (TextUtils.isEmpty(number) || number.length < 14 || number.length > 19 ) {
					false
				} else {
					number.luhnAlgorithm()
				}
			}
		}

		fun String.luhnAlgorithm() = reversed()
			.map(Character::getNumericValue)
			.mapIndexed { index, digit ->
				when {
					index % 2 == 0 -> digit
					digit < 5 -> digit * 2
					else -> digit * 2 - 9
				}
			}.sum() % 10 == 0

		fun isValidExpDate(exp: String?): Boolean {
			return if (exp == null) {
				false
			} else {
				val expDate = exp.replace("/", "")
				if (expDate.length != 4) {
					false
				} else {
					val month = expDate.substring(0,2).toInt()
					return month in 1..12
//					val format: DateFormat = SimpleDateFormat("MMyy", Locale.ENGLISH)
//					format.isLenient = false
//					return try {
//						var date = format.parse(expDate)
//						val calendar = Calendar.getInstance()
//						calendar.time = date ?: Date()
//						calendar[Calendar.DAY_OF_MONTH] = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
//						date = calendar.time
//						//val currentDate = Date()
//						val currentDate = format.parse("0222")
//						currentDate.before(date)
//					} catch (e: ParseException) {
//						e.printStackTrace()
//						false
//					}
				}
			}
		}

		fun isValidCvv(cardNumber: String?, cvv: String?): Boolean {
			return if (cvv?.length!! in 3..4) {
				true
			} else isUzcardCard(cardNumber) || isHumoCard(cardNumber)
		}

		fun isUzcardCard(cardNumber: String?): Boolean {
			//Uzcard 8600
			if (cardNumber?.length!! > 3 && cardNumber?.substring(0, 4) == "8600") {
				return true
			}
			return false
		}

		fun isHumoCard(cardNumber: String?): Boolean {
			//Humo 9860
			if (cardNumber?.length!! > 3 && cardNumber?.substring(0, 4) == "9860") {
				return true
			}
			return false
		}

		private fun prepareCardNumber(cardNumber: String): String {
			return cardNumber.replace("\\s".toRegex(), "")
		}

		fun createHexPacketFromData(cardNumber: String, cardExp: String, cardCvv: String, publicId: String, publicKey: String, keyVersion: Int): String? {

			var clearPublicKey = publicKey
				.replace("-----BEGIN PUBLIC KEY-----", "")
				.replace("-----END PUBLIC KEY-----", "")
				.replace("\\s".toRegex(), "")

			if (clearPublicKey.isBlank()) {
				return null
			}

			val clearNumber = prepareCardNumber(cardNumber)

			val cryptogram = createCardCryptogram(clearNumber, cardExp, cardCvv, publicId, clearPublicKey) ?: return null

			val cardInfo = CardInfo(
				firstSixDigits = clearNumber.substring(0, 6),
				lastFourDigits = clearNumber.substring(clearNumber.length - 4),
				expDateMonth = cardExp.substring(0, 2),
				expDateYear = cardExp.substring(cardExp.length - 2)
			)


			var cardCryptogramPacket = CardCryptogramPacket(
				cardInfo = cardInfo,
				keyVersion = HexPacketHelper.numberToEvenLengthString(keyVersion),
				browserInfoBase64 = makeBrowserInfoBase64(),
				value = cryptogram
				)
			
			var gson = GsonBuilder().disableHtmlEscaping().create();
			var cardCryptogramPacketString = gson.toJson(cardCryptogramPacket)

			cardCryptogramPacketString = Base64.encodeToString(cardCryptogramPacketString.toByteArray(), Base64.NO_WRAP).trim()

			return cardCryptogramPacketString
		}

		private fun makeBrowserInfoBase64(): String {
			val dm = Resources.getSystem().displayMetrics
			val tzOffsetMinutes = -(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
			val info = linkedMapOf(
				"AcceptHeader" to "*/*",
				"JavaEnabled" to false,
				"JavaScriptEnabled" to true,
				"Language" to Locale.getDefault().toLanguageTag(),
				"ColorDepth" to "24",
				"Height" to dm.heightPixels.toString(),
				"Width" to dm.widthPixels.toString(),
				"TimeZone" to tzOffsetMinutes.toString(),
				"UserAgent" to (System.getProperty("http.agent")
					?: "Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL}")
			)
			val json = GsonBuilder().disableHtmlEscaping().create().toJson(info)
			return Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
		}
		@Throws(UnsupportedEncodingException::class, NoSuchPaddingException::class, NoSuchAlgorithmException::class, BadPaddingException::class,
				IllegalBlockSizeException::class, InvalidKeyException::class)
		fun createCardCryptogram(number: String, cardExp: String, cardCvv: String, publicId: String, publicKey: String): String? {
			val cardNumber = prepareCardNumber(number)
			var exp = cardExp.replace("/", "")
			if (cardNumber.length < 14 || exp.length != 4) {
				return null
			}

			exp = exp.substring(2, 4) + cardExp.substring(0, 2)
			val s = "$cardNumber@$exp@$cardCvv@$publicId"
			val bytes = s.toByteArray(charset("ASCII"))
			val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
			val random = SecureRandom()

			val rsaKey = getRSAKey(publicKey) ?: return null

			cipher.init(Cipher.ENCRYPT_MODE, rsaKey, random)
			val crypto = cipher.doFinal(bytes)
			var crypto64 = Base64.encodeToString(crypto, Base64.DEFAULT)
			val cr_array = crypto64.split("\n").toTypedArray()
			crypto64 = ""
			for (i in cr_array.indices) {
				crypto64 += cr_array[i]
			}
			return crypto64
		}

		// Старый формат "01…" Kvell не принимает — собираем пакет createHexPacketFromData: ключ с сервера, из формы SDK или запасной
		@Deprecated("Use createHexPacketFromData(number, cardExp, cardCvv, publicId, publicKey, keyVersion) with key from KvellPaymentsApi.getPublicKey()")
		@Throws(UnsupportedEncodingException::class, NoSuchPaddingException::class, NoSuchAlgorithmException::class, BadPaddingException::class,
				IllegalBlockSizeException::class, InvalidKeyException::class)
		fun cardCryptogram(number: String, cardExp: String, cardCvv: String, publicId: String): String? {
			requestPublicKey(publicId)
			val (publicKey, keyVersion) = StoredPublicKey.cached() ?: (FALLBACK_PUBLIC_KEY to FALLBACK_KEY_VERSION)
			return createHexPacketFromData(number, cardExp, cardCvv, publicId, publicKey, keyVersion)
		}

		// Один фоновый запрос ключа на процесс; при ошибке повторим при следующем вызове
		private fun requestPublicKey(publicId: String) {
			if (!keyRequested.compareAndSet(false, true)) return
			KvellPaymentsSDK.createApi(publicId).getPublicKey()
				.subscribe(
					{ response -> StoredPublicKey.remember(response.pem, response.version) },
					{ keyRequested.set(false) }
				)
		}

		@Deprecated("Kvell не принимает CVV-криптограмму в формате \"03\"")
		@Throws(UnsupportedEncodingException::class, NoSuchPaddingException::class, NoSuchAlgorithmException::class, BadPaddingException::class, IllegalBlockSizeException::class, InvalidKeyException::class)
		fun cardCryptogramForCVV(cardCvv: String): String? {
			val bytes = cardCvv.toByteArray(charset("ASCII"))
			val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
			val random = SecureRandom()
			cipher.init(Cipher.ENCRYPT_MODE, getRSAKey(getPublicKey()), random)
			val crypto = cipher.doFinal(bytes)
			var crypto64 = "03" + getKeyVersion() + Base64.encodeToString(crypto, Base64.DEFAULT)
			val crArray = crypto64.split("\n").toTypedArray()
			crypto64 = ""
			for (i in crArray.indices) {
				crypto64 += crArray[i]
			}
			return crypto64
		}

		private fun getRSAKey(publicKey: String): PublicKey? {
			return try {
				val keyBytes: ByteArray =
					Base64.decode(publicKey.toByteArray(charset("utf-8")), Base64.DEFAULT)
				val spec = X509EncodedKeySpec(keyBytes)
				val kf: KeyFactory = KeyFactory.getInstance("RSA")
				kf.generatePublic(spec)
			} catch (e: NoSuchAlgorithmException) {
				e.printStackTrace()
				null
			} catch (e: InvalidKeySpecException) {
				e.printStackTrace()
				null
			} catch (e: UnsupportedEncodingException) {
				e.printStackTrace()
				null
			}
		}
	}
}