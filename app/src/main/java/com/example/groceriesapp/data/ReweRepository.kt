package com.example.groceriesapp.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.groceriesapp.data.network.ReweAccount
import com.example.groceriesapp.data.network.ReweApiClient
import com.example.groceriesapp.data.network.ReweAuthRequest
import com.example.groceriesapp.data.network.ReweEbonEntry
import com.example.groceriesapp.data.network.ReweMtlsIdentity
import com.example.groceriesapp.data.network.ReweOAuthClient
import com.example.groceriesapp.data.network.ReweTokens
import com.example.groceriesapp.data.network.decodeJwtClaims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Owns the REWE connection: the imported mTLS identity, OAuth tokens, and the receipt list.
 *
 * Unlike [BioCompanyRepository] there is no stored password to silently re-login with, so once the
 * refresh token is rejected the only way forward is a new browser sign-in.
 */
class ReweRepository(context: Context) {

    private val oauthClient = ReweOAuthClient()
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "rewe_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _account = MutableStateFlow<ReweAccount?>(null)
    val account: StateFlow<ReweAccount?> = _account.asStateFlow()

    private val _receipts = MutableStateFlow<List<ReweEbonEntry>>(emptyList())
    val receipts: StateFlow<List<ReweEbonEntry>> = _receipts.asStateFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.IDLE)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _credentials = MutableStateFlow(MtlsCredentialState(false, false))
    val credentials: StateFlow<MtlsCredentialState> = _credentials.asStateFlow()

    sealed class LoginState {
        object IDLE : LoginState()
        object LOGGING_IN : LoginState()
        object SUCCESS : LoginState()
        object RECONNECT_REQUIRED : LoginState()
        data class ERROR(val message: String) : LoginState()
    }

    sealed class SyncState {
        object IDLE : SyncState()
        object FETCHING : SyncState()
        data class SUCCESS(val count: Int) : SyncState()
        data class ERROR(val message: String) : SyncState()
    }

    data class MtlsCredentialState(
        val hasCertificate: Boolean,
        val hasPrivateKey: Boolean,
    ) {
        val isComplete: Boolean get() = hasCertificate && hasPrivateKey
    }

    init {
        _credentials.value = MtlsCredentialState(
            hasCertificate = encryptedPrefs.getString(KEY_CERT_PEM, null) != null,
            hasPrivateKey = encryptedPrefs.getString(KEY_KEY_PEM, null) != null,
        )
        _account.value = restoreAccount()
    }

    fun importCertificate(pem: String): Result<Unit> = runCatching {
        require(pem.contains("-----BEGIN CERTIFICATE-----")) {
            "That file does not contain a PEM certificate."
        }
        encryptedPrefs.edit().putString(KEY_CERT_PEM, pem).apply()
        _credentials.value = _credentials.value.copy(hasCertificate = true)
        validateIdentityIfComplete()
    }

    fun importPrivateKey(pem: String): Result<Unit> = runCatching {
        require(pem.contains("-----BEGIN PRIVATE KEY-----") ||
            pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            "That file does not contain a PEM private key."
        }
        encryptedPrefs.edit().putString(KEY_KEY_PEM, pem).apply()
        _credentials.value = _credentials.value.copy(hasPrivateKey = true)
        validateIdentityIfComplete()
    }

    /** Surfaces a malformed or mismatched pair at import time rather than on the first API call. */
    private fun validateIdentityIfComplete() {
        if (_credentials.value.isComplete) loadIdentity()
    }

    fun createAuthRequest(): ReweAuthRequest = oauthClient.createAuthRequest()

    suspend fun completeLogin(
        callbackUrl: String,
        code: String,
        codeVerifier: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        _loginState.value = LoginState.LOGGING_IN
        oauthClient.exchangeCode(callbackUrl, code, codeVerifier)
            .onSuccess { tokens ->
                persistTokens(tokens)
                _account.value = tokens.toAccount()
                _loginState.value = LoginState.SUCCESS
            }
            .onFailure { error ->
                _loginState.value = LoginState.ERROR(error.userFriendlyMessage())
            }
            .map { }
    }

    fun failLogin(message: String) {
        _loginState.value = LoginState.ERROR(message)
    }

    fun resetLoginState() {
        if (_loginState.value !is LoginState.LOGGING_IN) {
            _loginState.value = LoginState.IDLE
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
        _account.value = null
        _receipts.value = emptyList()
        _loginState.value = LoginState.IDLE
        _syncState.value = SyncState.IDLE
    }

    suspend fun fetchReceipts(): Result<List<ReweEbonEntry>> = withContext(Dispatchers.IO) {
        _syncState.value = SyncState.FETCHING
        runCatching {
            val client = requireApiClient()
            val entries = mutableListOf<ReweEbonEntry>()
            var page = 1
            var pageCount: Int
            do {
                val result = client.getEbons(page = page).getOrThrow()
                entries += result.entries
                pageCount = result.pageCount
                page++
            } while (page <= pageCount)
            entries.toList()
        }
            .onSuccess { entries ->
                _receipts.value = entries
                _syncState.value = SyncState.SUCCESS(entries.size)
            }
            .onFailure { error ->
                _syncState.value = SyncState.ERROR(error.userFriendlyMessage())
                if (error is IllegalStateException) _loginState.value = LoginState.RECONNECT_REQUIRED
            }
    }

    suspend fun downloadReceiptPdf(entry: ReweEbonEntry): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching { requireApiClient() }
                .mapCatching { client -> client.downloadReceiptPdf(entry.receiptId).getOrThrow() }
        }

    fun isConnected(): Boolean = _account.value != null && _credentials.value.isComplete

    private fun requireApiClient(): ReweApiClient {
        val identity = loadIdentity()
            ?: throw IllegalStateException("Import the REWE client certificate first.")
        val accessToken = currentAccessToken()
            ?: throw IllegalStateException("Sign in to REWE first.")
        return ReweApiClient(
            identity = identity,
            accessToken = accessToken,
            refreshAccessToken = ::refreshAccessToken,
        )
    }

    /** Returns a still-valid access token, refreshing first when it is close to expiry. */
    private fun currentAccessToken(): String? {
        val token = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt > System.currentTimeMillis() + EXPIRY_SKEW_MILLIS) return token
        return refreshAccessToken() ?: token
    }

    private fun refreshAccessToken(): String? {
        val refreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null) ?: run {
            _loginState.value = LoginState.RECONNECT_REQUIRED
            return null
        }
        return oauthClient.refresh(refreshToken)
            .onSuccess { tokens ->
                persistTokens(tokens)
                _account.value = tokens.toAccount()
            }
            .onFailure {
                _account.value = null
                _loginState.value = LoginState.RECONNECT_REQUIRED
            }
            .getOrNull()
            ?.accessToken
    }

    private fun loadIdentity(): ReweMtlsIdentity? {
        val cert = encryptedPrefs.getString(KEY_CERT_PEM, null) ?: return null
        val key = encryptedPrefs.getString(KEY_KEY_PEM, null) ?: return null
        return ReweMtlsIdentity.from(cert, key)
    }

    private fun persistTokens(tokens: ReweTokens) {
        encryptedPrefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .apply {
                tokens.refreshToken?.let { putString(KEY_REFRESH_TOKEN, it) }
            }
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochMillis)
            .apply()
    }

    private fun restoreAccount(): ReweAccount? {
        val token = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0L)
        val claims = decodeJwtClaims(token)
        return ReweAccount(
            email = claims?.optString("email")?.takeIf(String::isNotBlank),
            name = claims?.optString("name")?.takeIf(String::isNotBlank),
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun ReweTokens.toAccount(): ReweAccount {
        val claims = decodeJwtClaims(accessToken)
        return ReweAccount(
            email = claims?.optString("email")?.takeIf(String::isNotBlank),
            name = claims?.optString("name")?.takeIf(String::isNotBlank),
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
    }

    private fun Throwable.userFriendlyMessage(): String {
        val msg = message ?: toString()
        return when {
            msg.contains("timeout", ignoreCase = true) ->
                "Request timed out. Check your connection"
            msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("Failed to connect", ignoreCase = true) ->
                "Cannot reach REWE servers"
            msg.contains("HTTP 401") || msg.contains("Session expired") ->
                "Session expired. Reconnect your REWE account"
            msg.contains("handshake", ignoreCase = true) ->
                "The client certificate was rejected by REWE"
            else -> msg
        }
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_CERT_PEM = "certificate_pem"
        private const val KEY_KEY_PEM = "private_key_pem"
        private const val EXPIRY_SKEW_MILLIS = 30_000L
    }
}
