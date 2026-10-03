package com.example.groceriesapp.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.groceriesapp.data.network.EdekaAccount
import com.example.groceriesapp.data.network.EdekaAuthRequest
import com.example.groceriesapp.data.network.EdekaFormattedReceipt
import com.example.groceriesapp.data.network.EdekaOAuthClient
import com.example.groceriesapp.data.network.EdekaReceiptEntry
import com.example.groceriesapp.data.network.EdekaSoapClient
import com.example.groceriesapp.data.network.EdekaTokens
import com.example.groceriesapp.data.network.EdekaUnauthorizedException
import com.example.groceriesapp.data.network.decodeJwtClaims
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class EdekaRepository(context: Context) {
    private val oauthClient = EdekaOAuthClient()
    private val soapClient = EdekaSoapClient()
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "edeka_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _account = MutableStateFlow(restoreAccount())
    val account: StateFlow<EdekaAccount?> = _account.asStateFlow()

    private val _receipts = MutableStateFlow<List<EdekaReceiptEntry>>(emptyList())
    val receipts: StateFlow<List<EdekaReceiptEntry>> = _receipts.asStateFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.IDLE)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    @Volatile
    private var session: EdekaSoapClient.Session? = null

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

    fun createAuthRequest(): EdekaAuthRequest = oauthClient.createAuthRequest().also {
        persistPendingAuth(it)
        _loginState.value = LoginState.IDLE
    }

    fun pendingAuthRequest(): EdekaAuthRequest? {
        val authorizationUrl = encryptedPrefs.getString(KEY_PENDING_URL, null) ?: return null
        val verifier = encryptedPrefs.getString(KEY_PENDING_VERIFIER, null) ?: return null
        val state = encryptedPrefs.getString(KEY_PENDING_STATE, null) ?: return null
        val deviceId = encryptedPrefs.getString(KEY_PENDING_DEVICE_ID, null) ?: return null
        val sessionId = encryptedPrefs.getString(KEY_PENDING_SESSION_ID, null) ?: return null
        return EdekaAuthRequest(authorizationUrl, verifier, state, deviceId, sessionId)
    }

    fun continuationUrl(intermediateUrl: String): Result<String> =
        runCatching { oauthClient.continuationUrl(intermediateUrl) }

    suspend fun completeLogin(callbackUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        _loginState.value = LoginState.LOGGING_IN
        val pending = pendingAuthRequest()
            ?: return@withContext loginFailure("The EDEKA sign-in session expired. Start again.")
        val code = oauthClient.codeFromCallback(callbackUrl, pending.state)
            .getOrElse { return@withContext loginFailure(it.message ?: "EDEKA sign-in failed.") }
        oauthClient.exchangeCode(code, pending)
            .onSuccess { tokens ->
                persistTokens(tokens)
                clearPendingAuth()
                session = null
                _account.value = tokens.toAccount()
                _loginState.value = LoginState.SUCCESS
            }
            .onFailure { errorValue ->
                _loginState.value = LoginState.ERROR(errorValue.userFriendlyMessage())
            }
            .map { }
    }

    fun failLogin(message: String) {
        _loginState.value = LoginState.ERROR(message)
    }

    fun cancelLogin() {
        clearPendingAuth()
        if (_loginState.value !is LoginState.LOGGING_IN) _loginState.value = LoginState.IDLE
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
        clearPendingAuth()
        session = null
        _account.value = null
        _receipts.value = emptyList()
        _loginState.value = LoginState.IDLE
        _syncState.value = SyncState.IDLE
    }

    suspend fun fetchReceipts(): Result<List<EdekaReceiptEntry>> = withContext(Dispatchers.IO) {
        _syncState.value = SyncState.FETCHING
        runCatching {
            withAuthenticatedSession { activeSession ->
                val entries = linkedMapOf<Int, EdekaReceiptEntry>()
                var offset = 0
                do {
                    val page = soapClient.getReceipts(activeSession, PAGE_SIZE, offset)
                    page.forEach { entries[it.id] = it }
                    offset += page.size
                } while (page.size == PAGE_SIZE)
                entries.values.sortedByDescending(EdekaReceiptEntry::dateAndTime)
            }
        }.onSuccess { entries ->
            _receipts.value = entries
            _syncState.value = SyncState.SUCCESS(entries.size)
        }.onFailure { error ->
            _syncState.value = SyncState.ERROR(error.userFriendlyMessage())
            if (error is IllegalStateException) {
                _account.value = null
                _loginState.value = LoginState.RECONNECT_REQUIRED
            }
        }
    }

    suspend fun fetchReceiptDetail(entry: EdekaReceiptEntry): Result<EdekaFormattedReceipt> =
        withContext(Dispatchers.IO) {
            runCatching {
                withAuthenticatedSession { activeSession ->
                    soapClient.getFormattedReceipt(activeSession, entry.id)
                }
            }
        }

    fun isConnected(): Boolean = _account.value != null

    private fun <T> withAuthenticatedSession(
        operation: (EdekaSoapClient.Session) -> T,
    ): T {
        val firstSession = session ?: establishSession(forceRefresh = false)
        return try {
            operation(firstSession)
        } catch (_: EdekaUnauthorizedException) {
            session = null
            operation(establishSession(forceRefresh = true))
        }
    }

    private fun establishSession(forceRefresh: Boolean): EdekaSoapClient.Session {
        val accessToken = currentAccessToken(forceRefresh)
            ?: throw IllegalStateException("Sign in to EDEKA again.")
        return soapClient.establishSession(accessToken, installationId()).also { session = it }
    }

    private fun currentAccessToken(forceRefresh: Boolean): String? {
        val accessToken = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0L)
        if (!forceRefresh && expiresAt > System.currentTimeMillis() + EXPIRY_SKEW_MILLIS) {
            return accessToken
        }
        val previousRefreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null) ?: run {
            _loginState.value = LoginState.RECONNECT_REQUIRED
            return null
        }
        return oauthClient.refresh(previousRefreshToken)
            .map { refreshed ->
                val complete = refreshed.copy(
                    refreshToken = refreshed.refreshToken ?: previousRefreshToken,
                )
                persistTokens(complete)
                _account.value = complete.toAccount()
                complete.accessToken
            }
            .onFailure {
                _account.value = null
                _loginState.value = LoginState.RECONNECT_REQUIRED
            }
            .getOrNull()
    }

    private fun persistTokens(tokens: EdekaTokens) {
        encryptedPrefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .apply {
                tokens.refreshToken?.let { putString(KEY_REFRESH_TOKEN, it) }
            }
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochMillis)
            .apply()
    }

    private fun persistPendingAuth(request: EdekaAuthRequest) {
        encryptedPrefs.edit()
            .putString(KEY_PENDING_URL, request.authorizationUrl)
            .putString(KEY_PENDING_VERIFIER, request.codeVerifier)
            .putString(KEY_PENDING_STATE, request.state)
            .putString(KEY_PENDING_DEVICE_ID, request.deviceId)
            .putString(KEY_PENDING_SESSION_ID, request.sessionId)
            .apply()
    }

    private fun clearPendingAuth() {
        encryptedPrefs.edit()
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_STATE)
            .remove(KEY_PENDING_DEVICE_ID)
            .remove(KEY_PENDING_SESSION_ID)
            .apply()
    }

    private fun restoreAccount(): EdekaAccount? {
        val token = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val expiresAt = encryptedPrefs.getLong(KEY_EXPIRES_AT, 0L)
        return EdekaAccount(
            email = decodeJwtClaims(token)?.optString("email")?.takeIf(String::isNotBlank),
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun installationId(): String {
        encryptedPrefs.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            encryptedPrefs.edit().putString(KEY_INSTALLATION_ID, it).apply()
        }
    }

    private fun EdekaTokens.toAccount() = EdekaAccount(
        email = decodeJwtClaims(accessToken)?.optString("email")?.takeIf(String::isNotBlank),
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    private fun loginFailure(message: String): Result<Unit> {
        _loginState.value = LoginState.ERROR(message)
        return Result.failure(IOException(message))
    }

    private fun Throwable.userFriendlyMessage(): String {
        val detail = message ?: toString()
        return when {
            detail.contains("timeout", ignoreCase = true) ->
                "Request timed out. Check your connection"
            detail.contains("Unable to resolve host", ignoreCase = true) ||
                detail.contains("Failed to connect", ignoreCase = true) ->
                "Cannot reach EDEKA servers"
            detail.contains("expired", ignoreCase = true) ||
                detail.contains("Sign in", ignoreCase = true) ->
                "Session expired. Reconnect your EDEKA account"
            else -> detail
        }
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val EXPIRY_SKEW_MILLIS = 30_000L
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_PENDING_URL = "pending_url"
        private const val KEY_PENDING_VERIFIER = "pending_verifier"
        private const val KEY_PENDING_STATE = "pending_state"
        private const val KEY_PENDING_DEVICE_ID = "pending_device_id"
        private const val KEY_PENDING_SESSION_ID = "pending_session_id"
    }
}
