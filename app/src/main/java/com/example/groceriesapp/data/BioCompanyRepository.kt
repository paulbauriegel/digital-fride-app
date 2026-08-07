package com.example.groceriesapp.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.groceriesapp.data.network.BioCompanyAccount
import com.example.groceriesapp.data.network.BioCompanyApiClient
import com.example.groceriesapp.data.network.BioCompanyPointsResponse
import com.example.groceriesapp.data.network.BioCompanyReceiptDetail
import com.example.groceriesapp.data.network.BioCompanyReceiptEntry
import com.example.groceriesapp.data.network.BioCompanyReceiptDetailItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BioCompanyRepository(context: Context) {

    private val apiClient = BioCompanyApiClient()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "biocompany_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _account = MutableStateFlow<BioCompanyAccount?>(restoreSession())
    val account: StateFlow<BioCompanyAccount?> = _account.asStateFlow()

    private val _receipts = MutableStateFlow<List<BioCompanyReceiptEntry>>(emptyList())
    val receipts: StateFlow<List<BioCompanyReceiptEntry>> = _receipts.asStateFlow()

    private val _receiptDetail = MutableStateFlow<BioCompanyReceiptDetail?>(null)
    val receiptDetail: StateFlow<BioCompanyReceiptDetail?> = _receiptDetail.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _points = MutableStateFlow<BioCompanyPointsResponse?>(null)
    val points: StateFlow<BioCompanyPointsResponse?> = _points.asStateFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.IDLE)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    sealed class SyncState {
        object IDLE : SyncState()
        object FETCHING : SyncState()
        data class SUCCESS(val count: Int) : SyncState()
        data class ERROR(val message: String) : SyncState()
    }

    sealed class ImportState {
        object IDLE : ImportState()
        object IMPORTING : ImportState()
        data class SUCCESS(val imported: Int, val failed: Int) : ImportState()
        data class ERROR(val message: String) : ImportState()
    }

    sealed class LoginState {
        object IDLE : LoginState()
        object LOGGING_IN : LoginState()
        object SUCCESS : LoginState()
        data class ERROR(val message: String) : LoginState()
    }

    init {
        if (_account.value == null) {
            val email = encryptedPrefs.getString(KEY_EMAIL, null)
            val password = encryptedPrefs.getString(KEY_PASSWORD, null)
            if (email != null && password != null) {
                repositoryScope.launch { login(email, password, isSilentRefresh = true) }
            }
        }
    }

    suspend fun login(
        email: String,
        password: String,
        isSilentRefresh: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        _loginState.value = LoginState.LOGGING_IN
        _syncState.value = SyncState.IDLE
        val result = apiClient.login(email, password)
        if (result.isSuccess) {
            saveCredentials(email, password)
            _account.value = apiClient.getAccount()
            _loginState.value = LoginState.SUCCESS
        } else {
            val error = result.exceptionOrNull() ?: java.lang.Exception("Unknown error")
            apiClient.clearSession()
            _account.value = null
            if (isSilentRefresh) {
                // The saved token merely expired; keep the stored credentials so the user
                // isn't forced to type them in again. A transient network failure here must
                // not wipe a previously working login.
                _loginState.value = LoginState.IDLE
            } else {
                clearCredentials()
                _loginState.value = LoginState.ERROR(error.userFriendlyMessage())
            }
        }
        result.map { }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        apiClient.logout()
        clearCredentials()
        _account.value = null
        _receipts.value = emptyList()
        _receiptDetail.value = null
        _points.value = null
        _syncState.value = SyncState.IDLE
        _loginState.value = LoginState.IDLE
    }

    suspend fun fetchReceipts(): Result<List<BioCompanyReceiptEntry>> = withContext(Dispatchers.IO) {
        _syncState.value = SyncState.FETCHING
        return@withContext apiClient.getReceipts()
            .onSuccess { entries ->
                _receipts.value = entries
                _syncState.value = SyncState.SUCCESS(entries.size)
            }
            .onFailure { error ->
                _syncState.value = SyncState.ERROR(error.userFriendlyMessage())
            }
    }

    suspend fun fetchReceiptDetail(entry: BioCompanyReceiptEntry): Result<BioCompanyReceiptDetail> = withContext(Dispatchers.IO) {
        return@withContext apiClient.getReceiptDetail(entry)
            .onSuccess { detail -> _receiptDetail.value = detail }
    }

    suspend fun fetchPoints(): Result<BioCompanyPointsResponse> = withContext(Dispatchers.IO) {
        return@withContext apiClient.getCustomerPoints()
            .onSuccess { resp -> _points.value = resp }
    }

    fun isConnected(): Boolean = apiClient.isLoggedIn

    private fun restoreSession(): BioCompanyAccount? {
        val email = encryptedPrefs.getString(KEY_EMAIL, null) ?: return null
        val password = encryptedPrefs.getString(KEY_PASSWORD, null) ?: return null
        val token = encryptedPrefs.getString(KEY_TOKEN, null) ?: return null
        val custNum = encryptedPrefs.getString(KEY_CUSTOMERNUMBER, null) ?: return null
        val expiry = encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0)
        if (expiry <= System.currentTimeMillis()) {
            return null
        }
        apiClient.restoreSession(email, password, token, custNum, expiry)
        return apiClient.getAccount()
    }

    private fun saveCredentials(email: String, password: String) {
        val acc = apiClient.getAccount() ?: return
        val editor = encryptedPrefs.edit()
        editor.putString(KEY_EMAIL, email)
        editor.putString(KEY_PASSWORD, password)
        editor.putString(KEY_TOKEN, acc.token)
        editor.putString(KEY_CUSTOMERNUMBER, acc.customerNumber)
        editor.putLong(KEY_TOKEN_EXPIRY, acc.tokenExpiryEpochMillis)
        editor.apply()
    }

    private fun clearCredentials() {
        val editor = encryptedPrefs.edit()
        editor.remove(KEY_EMAIL)
        editor.remove(KEY_PASSWORD)
        editor.remove(KEY_TOKEN)
        editor.remove(KEY_CUSTOMERNUMBER)
        editor.remove(KEY_TOKEN_EXPIRY)
        editor.apply()
    }

    private fun saveSessionAfterRefresh() {
        apiClient.getAccount()?.let { acc ->
            val editor = encryptedPrefs.edit()
            editor.putString(KEY_TOKEN, acc.token)
            editor.putString(KEY_CUSTOMERNUMBER, acc.customerNumber)
            editor.putLong(KEY_TOKEN_EXPIRY, acc.tokenExpiryEpochMillis)
            editor.apply()
        }
    }

    private fun Throwable.userFriendlyMessage(): String {
        val msg = message ?: toString()
        return when {
            msg.contains("Empty response", ignoreCase = true) -> "No response from server"
            msg.contains("Not authenticated", ignoreCase = true) -> "Please sign in again"
            msg.contains("Re-authentication failed", ignoreCase = true) -> "Re-authentication failed"
            msg.contains("SocketTimeout", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) -> "Request timed out. Check your connection"
            msg.contains("Failed to connect", ignoreCase = true) -> "Cannot reach BIO COMPANY servers"
            else -> "Network error: $msg"
        }
    }

    companion object {
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOKEN = "token"
        private const val KEY_CUSTOMERNUMBER = "customernumber"
        private const val KEY_TOKEN_EXPIRY = "tokenExpiry"
    }
}
