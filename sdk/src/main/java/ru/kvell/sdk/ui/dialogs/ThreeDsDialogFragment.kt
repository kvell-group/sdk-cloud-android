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
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		isCancelable = false

		binding.webView.webViewClient = ThreeDsWebViewClient()
		binding.webView.settings.domStorageEnabled = true
		binding.webView.settings.javaScriptEnabled = true
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

		// POST-сабмит формы не вызывает shouldOverrideUrlLoading — ловим return URL в onPageStarted
		override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
			Log.d(TAG, "onPageStarted: $url")
			if (url.startsWith(RETURN_URL_PREFIX)) {
				view.stopLoading()
				handleReturnNavigation(view, url)
			} else {
				super.onPageStarted(view, url, favicon)
			}
		}

		override fun onPageFinished(view: WebView, url: String) {
			Log.d(TAG, "onPageFinished: $url")
			// Страница /3ds/return содержит форму c полями PaRes/MD и автосабмитится на PaymentUrl.
			// Считываем значения полей и завершаем 3DS через post3ds.
			extractPaResFromForm(view)
		}
	}

	private var returnHandled = false

	// Переход на return URL: PaRes/MD из query (GET) либо добираем из формы страницы-отправителя (POST)
	private fun handleReturnNavigation(view: WebView, url: String): Boolean {
		if (!url.startsWith(RETURN_URL_PREFIX)) {
			return false
		}
		val uri = Uri.parse(url)
		val queryPaRes = uri.getQueryParameter("PaRes")
		if (!queryPaRes.isNullOrEmpty()) {
			complete(uri.getQueryParameter("MD") ?: md, queryPaRes)
		} else {
			extractPaResFromForm(view, completeIfEmpty = true)
		}
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
