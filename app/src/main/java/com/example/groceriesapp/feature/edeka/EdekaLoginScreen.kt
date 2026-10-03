package com.example.groceriesapp.feature.edeka

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.groceriesapp.data.network.EdekaAuthRequest
import com.example.groceriesapp.data.network.EdekaOAuthClient

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EdekaLoginScreen(
    authRequest: EdekaAuthRequest,
    incomingLink: String?,
    continuationUrl: (String) -> Result<String>,
    onLinkConsumed: () -> Unit,
    onCancel: () -> Unit,
    onCallback: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val handled = remember { mutableStateOf(false) }

    LaunchedEffect(incomingLink, webView) {
        val link = incomingLink ?: return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        when {
            link.startsWith(EdekaOAuthClient.REDIRECT_URI) -> {
                if (!handled.value) {
                    handled.value = true
                    onCallback(link)
                }
            }
            link.startsWith(EdekaOAuthClient.LOGIN_LINK_PREFIX) -> {
                continuationUrl(link)
                    .onSuccess(view::loadUrl)
                    .onFailure { onError(it.message ?: "Could not continue EDEKA sign-in.") }
            }
            else -> onError("Unsupported EDEKA sign-in link.")
        }
        onLinkConsumed()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Sign in to EDEKA") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (url.startsWith(EdekaOAuthClient.REDIRECT_URI)) {
                                        if (!handled.value) {
                                            handled.value = true
                                            onCallback(url)
                                        }
                                        return true
                                    }
                                    if (url.startsWith(EdekaOAuthClient.LOGIN_LINK_PREFIX)) {
                                        continuationUrl(url)
                                            .onSuccess { view?.loadUrl(it) }
                                            .onFailure {
                                                onError(it.message ?: "Could not continue EDEKA sign-in.")
                                            }
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    loading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                }
                            }
                            webView = this
                            loadUrl(authRequest.authorizationUrl)
                        }
                    },
                )
            }
        }
    }
}
