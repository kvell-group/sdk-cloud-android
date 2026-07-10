package ru.kvell.sdk.ui.dialogs

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.view.isGone
import androidx.fragment.app.DialogFragment
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.kvell.sdk.databinding.DialogKvellThreeDsBinding
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.*

class ThreeDsDialogFragment : DialogFragment() {
	interface ThreeDSDialogListener {
		fun onAuthorizationCompleted(md: String, paRes: String)
		fun onAuthorizationFailed(error: String?)
	}

	companion object {
		private const val TAG = "KvellSDK3DS"
		private const val POST_BACK_URL = "https://api.pay-pulse.example/payments/get3dsData"
		// Return URL бесшовки (совпадает с PaymentUrl в charge) — сигнал завершения 3DS
		private const val RETURN_URL_PREFIX = "https://sdk.pay-pulse.com/return"
		private const val ARG_ACS_URL = "acs_url"
		private const val ARG_MD = "md"
		private const val ARG_PA_REQ = "pa_req"

		fun newInstance(acsUrl: String, paReq: String, md: String) = ThreeDsDialogFragment().apply {
			arguments = Bundle().also {
				it.putString(ARG_ACS_URL, acsUrl)
				it.putString(ARG_MD, md)
				it.putString(ARG_PA_REQ, paReq)
			}
		}
	}

	private var _binding: DialogKvellThreeDsBinding? = null

	private val binding get() = _binding!!

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		_binding = DialogKvellThreeDsBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onDestroyView() {
		super.onDestroyView()
		pollHandler.removeCallbacks(pollRunnable)
		_binding = null
	}

	private val acsUrl by lazy {
		requireArguments().getString(ARG_ACS_URL) ?: ""
	}

	private val md by lazy {
		requireArguments().getString(ARG_MD) ?: ""
	}

	private val paReq by lazy {
		requireArguments().getString(ARG_PA_REQ) ?: ""
	}

	private var listener: ThreeDSDialogListener? = null

	private var webViewUserAgent: String? = null

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		isCancelable = false

		binding.webView.webViewClient = ThreeDsWebViewClient()
		binding.webView.settings.domStorageEnabled = true
		binding.webView.settings.javaScriptEnabled = true
		webViewUserAgent = binding.webView.settings.userAgentString
		binding.webView.settings.javaScriptCanOpenWindowsAutomatically = true
		binding.webView.addJavascriptInterface(ThreeDsJavaScriptInterface(), "JavaScriptThreeDs")

		try {
			val params = StringBuilder()
					.append("PaReq=").append(URLEncoder.encode(paReq, "UTF-8"))
					.append("&MD=").append(URLEncoder.encode(md, "UTF-8"))
					.append("&TermUrl=").append(URLEncoder.encode(POST_BACK_URL, "UTF-8"))
					.toString()
				Log.d(TAG, "POST acsUrl=$acsUrl body=$params")
				binding.webView.postUrl(acsUrl, params.toByteArray())
		} catch (e: UnsupportedEncodingException) {
			e.printStackTrace()
		}

		startPaResPolling()

		binding.icClose.setOnClickListener {
			listener?.onAuthorizationFailed(null)
			dismiss()
		}
	}

	private val pollHandler = Handler(Looper.getMainLooper())
	private val pollRunnable = object : Runnable {
		override fun run() {
			if (returnHandled) return
			_binding?.webView?.let { extractPaResFromForm(it) }
			if (!returnHandled) pollHandler.postDelayed(this, 300)
		}
	}

	// Опрос DOM: как только на странице 3ds/return появятся поля PaRes/MD — читаем и завершаем 3DS
	private fun startPaResPolling() {
		pollHandler.postDelayed(pollRunnable, 500)
	}

	override fun onStart() {
		super.onStart()
		val window = dialog!!.window
		window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
	}

	private inner class ThreeDsWebViewClient : WebViewClient() {
		override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
			return handleReturnNavigation(view, request.url.toString())
		}

		@Deprecated("Deprecated in Java")
		override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
			return handleReturnNavigation(view, url)
		}

		// Страница 3ds/return содержит форму с PaRes, которая мгновенно автосабмитится на
		// PaymentUrl (окно ~5 мс — опрос DOM не успевает). Пытаемся прочитать её сами.
		override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
			val url = request.url.toString()
			if (url.contains("/3ds/return")) {
				Log.d(TAG, "intercept: method=${request.method} mainFrame=${request.isForMainFrame} url=$url")
				if (!returnHandled && request.isForMainFrame) {
					captureReturnPage(url)?.let { return it }
				}
			}
			return super.shouldInterceptRequest(view, request)
		}

		// POST-сабмит формы не вызывает shouldOverrideUrlLoading — ловим return URL в onPageStarted
		override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
			Log.d(TAG, "onPageStarted: $url")
			// Хук ставим на каждой странице как можно раньше: страница 3ds/return автосабмитит
			// форму c PaRes за считанные мс — опрос DOM в это окно не попадает.
			injectSubmitHook(view)
			if (url.startsWith(RETURN_URL_PREFIX)) {
				handleReturnNavigation(view, url)
			} else {
				super.onPageStarted(view, url, favicon)
			}
		}

		override fun onPageFinished(view: WebView, url: String) {
			Log.d(TAG, "onPageFinished: $url")
			injectSubmitHook(view)
			// Страница /3ds/return содержит форму c полями PaRes/MD и автосабмитится на PaymentUrl.
			// Считываем значения полей и завершаем 3DS через post3ds.
			extractPaResFromForm(view)
		}
	}

	// Синхронный перехват сабмита формы 3DS: form.submit() не порождает submit-событие,
	// поэтому оборачиваем ещё и HTMLFormElement.prototype.submit. Как только форма уходит —
	// сразу отдаём PaRes/MD во фрагмент через JS-интерфейс, без гонки с опросом DOM.
	private fun injectSubmitHook(view: WebView) {
		if (returnHandled) return
		val js = "(function(){if(window.__kvellHook)return;window.__kvellHook=true;" +
				"var g=function(n){var e=document.querySelector('[name=\"'+n+'\"]');return e?e.value:null;};" +
				"var cap=function(){var p=g('PaRes')||g('pares')||g('CRes')||g('cres');var m=g('MD')||g('md');" +
				"if(p){try{JavaScriptThreeDs.processData(m||'',p);}catch(e){}}};" +
				"document.addEventListener('submit',cap,true);" +
				"try{var s=HTMLFormElement.prototype.submit;HTMLFormElement.prototype.submit=function(){cap();return s.apply(this,arguments);};}catch(e){}" +
				"})()"
		view.evaluateJavascript(js, null)
	}

	// Сами запрашиваем страницу 3ds/return (с куками WebView), парсим PaRes/MD и завершаем 3DS.
	// Тот же HTML отдаём обратно в WebView, чтобы не делать второй запрос к серверу.
	private fun captureReturnPage(url: String): WebResourceResponse? {
		return try {
			val cookie = CookieManager.getInstance().getCookie(url)
			val userAgent = webViewUserAgent
			val connection = (URL(url).openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				instanceFollowRedirects = false
				if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
				if (!userAgent.isNullOrEmpty()) setRequestProperty("User-Agent", userAgent)
				connectTimeout = 15000
				readTimeout = 15000
			}
			val code = connection.responseCode
			val stream = if (code in 200..399) connection.inputStream else connection.errorStream
			val bytes = stream?.readBytes() ?: ByteArray(0)
			val mime = (connection.contentType ?: "text/html").substringBefore(";").trim()
					.ifEmpty { "text/html" }

			val doc = Jsoup.parse(String(bytes, Charsets.UTF_8))
			val paRes = doc.select("[name=PaRes],[name=pares],[name=CRes],[name=cres]")
					.firstOrNull()?.attr("value")
			Log.d(TAG, "3ds/return fetched ($code), PaRes=${if (paRes.isNullOrEmpty()) "<none>" else "<len ${paRes.length}>"}")
			if (!paRes.isNullOrEmpty()) {
				activity?.runOnUiThread { complete(md, paRes) }
			}
			WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(bytes))
		} catch (e: Exception) {
			Log.d(TAG, "3ds/return fetch failed: ${e.message}")
			null
		}
	}

	private var returnHandled = false

	// Переход на return URL — сигнал завершения 3DS. PaRes мог прийти из query (GET),
	// из submit-хука (processData) или из формы страницы. Если PaRes нет — всё равно
	// гарантированно завершаем 3DS: дальше вызовется payments/get за финальным статусом.
	private fun handleReturnNavigation(view: WebView, url: String): Boolean {
		if (!url.startsWith(RETURN_URL_PREFIX)) {
			return false
		}
		if (returnHandled) return true
		val uri = Uri.parse(url)
		val queryPaRes = uri.getQueryParameter("PaRes")
		if (!queryPaRes.isNullOrEmpty()) {
			complete(uri.getQueryParameter("MD") ?: md, queryPaRes)
			return true
		}
		extractPaResFromForm(view, completeIfEmpty = false)
		// Страховка: если PaRes так и не пришёл (пустая return-страница), завершаем без него.
		pollHandler.postDelayed({ if (!returnHandled) complete(md, "") }, 500)
		return true
	}

	// Читаем скрытые поля формы 3DS (PaRes/MD) со страницы 3ds/return
	private fun extractPaResFromForm(view: WebView, completeIfEmpty: Boolean = false) {
		if (returnHandled) return
		val script = "(function(){var g=function(n){var e=document.querySelector('[name=\"'+n+'\"]');return e?e.value:null;};" +
				"return JSON.stringify({MD:g('MD')||g('md'),PaRes:g('PaRes')||g('pares')});})()"
		view.evaluateJavascript(script) { result ->
			var resultMd = md
			var paRes = ""
			try {
				if (result != null && result != "null" && result != "\"null\"") {
					Log.d(TAG, "form fields: $result")
				}
				val clean = (result ?: "").trim('"').replace("\\\"", "\"")
				if (clean.startsWith("{")) {
					val obj = JsonParser().parse(clean).asJsonObject
					if (obj.has("PaRes") && !obj.get("PaRes").isJsonNull) paRes = obj.get("PaRes").asString
					if (obj.has("MD") && !obj.get("MD").isJsonNull) resultMd = obj.get("MD").asString
				}
			} catch (e: Exception) {
				e.printStackTrace()
			}
			if (paRes.isNotEmpty()) {
				complete(resultMd, paRes)
			} else if (completeIfEmpty) {
				complete(md, "")
			}
		}
	}

	private fun complete(resultMd: String, paRes: String) {
		if (returnHandled) return
		returnHandled = true
		Log.d(TAG, "3DS complete: MD=$resultMd PaRes=${if (paRes.isEmpty()) "<empty>" else paRes}")
		pollHandler.removeCallbacks(pollRunnable)
		activity?.runOnUiThread {
			listener?.onAuthorizationCompleted(resultMd, paRes)
			dismissAllowingStateLoss()
		}
	}

	internal inner class ThreeDsJavaScriptInterface {
		// Вызывается из submit-хука в момент отправки формы 3DS
		@JavascriptInterface
		fun processData(md: String?, paRes: String?) {
			if (paRes.isNullOrEmpty()) return
			activity?.runOnUiThread { complete(this@ThreeDsDialogFragment.md, paRes) }
		}

		@JavascriptInterface
		fun processHTML(html: String?) {
			val doc: Document = Jsoup.parse(html)
			val element: Element? = doc.select("body").first()
			val jsonObject = JsonParser().parse(element?.ownText()).asJsonObject
			val paRes = jsonObject["PaRes"].asString
			requireActivity().runOnUiThread {
				if (!paRes.isNullOrEmpty()) {
					listener?.onAuthorizationCompleted(md, paRes)
				} else {
					listener?.onAuthorizationFailed(html ?: "")
				}
				dismissAllowingStateLoss()
			}
		}
	}

	override fun onAttach(context: Context) {
		super.onAttach(context)

		listener = targetFragment as? ThreeDSDialogListener
		if (listener == null) {
			listener = context as? ThreeDSDialogListener
		}
	}

	override fun onAttach(activity: Activity) {
		super.onAttach(activity)

		listener = targetFragment as? ThreeDSDialogListener
		if (listener == null) {
			listener = activity as? ThreeDSDialogListener
		}
	}
}
