package com.example.groceriesapp.feature.rewe

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.groceriesapp.data.network.ReweAuthRequest
import com.example.groceriesapp.data.network.ReweOAuthClient

/**
 * Hosts REWE's Keycloak sign-in page and intercepts the redirect back to
 * [ReweOAuthClient.REDIRECT_URI], which carries the authorization code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReweLoginScreen(
    authRequest: ReweAuthRequest,
    onCancel: () -> Unit,
    onCodeReceived: (callbackUrl: String, code: String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    // The redirect can fire more than once (redirect chain, reloads); only the first one counts.
    val handled = remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Sign in to REWE") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
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
                                    return interceptRedirect(url, handled, onCodeReceived, onError)
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    loading = true
                                    if (url != null) {
                                        interceptRedirect(url, handled, onCodeReceived, onError)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                }
                            }
                            loadUrl(authRequest.authorizationUrl)
                        }
                    },
                )
            }
        }
    }
}

private fun interceptRedirect(
    url: String,
    handled: androidx.compose.runtime.MutableState<Boolean>,
    onCodeReceived: (String, String) -> Unit,
    onError: (String) -> Unit,
): Boolean {
    val uri = Uri.parse(url)
    // REWE has been observed landing on /login/oauth2/code/sso instead of the registered
    // redirect_uri, so match on either the redirect URI or any rewe.de URL carrying the code.
    val isCallback = url.startsWith(ReweOAuthClient.REDIRECT_URI) ||
        (uri.host?.endsWith("rewe.de") == true &&
            (uri.getQueryParameter("code") != null || uri.getQueryParameter("error") != null))
    if (!isCallback) return false
    if (handled.value) return true
    handled.value = true

    val error = uri.getQueryParameter("error")
    if (error != null) {
        onError(uri.getQueryParameter("error_description") ?: error)
        return true
    }
    val code = uri.getQueryParameter("code")
    if (code.isNullOrBlank()) {
        onError("REWE did not return an authorization code.")
        return true
    }
    onCodeReceived(url, code)
    return true
}
