package com.anvex.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anvex.core.android.AppGraph
import com.anvex.core.android.signing.SigningKeyManager
import com.anvex.core.android.signing.toUtf8Bytes
import com.anvex.core.android.signing.wipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4.4.6: on-device APK signing-key flow.
 *
 * Holds the generated PKCS12 bytes in memory only (never persisted); passwords
 * are [CharArray] and wiped after generation / secret push.
 */
class SigningKeyViewModel : ViewModel() {
    data class Generated(
        val pkcs12: ByteArray,
        val alias: String,
        val fingerprint: String,
    )

    private val _generated = MutableStateFlow<Generated?>(null)
    val generated: StateFlow<Generated?> = _generated

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val _pushDone = MutableStateFlow(false)
    val pushDone: StateFlow<Boolean> = _pushDone

    fun generate(alias: String, storePassword: CharArray, keyPassword: CharArray, validityYears: Int) {
        if (_busy.value) return
        _busy.value = true
        _status.value = null
        _pushDone.value = false
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    SigningKeyManager.generate(alias.trim(), storePassword, keyPassword, validityYears)
                }
                _generated.value = Generated(result.pkcs12Bytes, result.alias, result.certFingerprint)
                _status.value = "Signing key generated on this device."
            } catch (e: Exception) {
                _status.value = e.message ?: "Key generation failed."
            } finally {
                storePassword.wipe()
                keyPassword.wipe()
                _busy.value = false
            }
        }
    }

    fun pushSecrets(
        owner: String,
        repo: String,
        storePassword: CharArray,
        keyPassword: CharArray,
    ) {
        val gen = _generated.value ?: return
        if (_busy.value) return
        _busy.value = true
        _status.value = null
        viewModelScope.launch {
            try {
                val token = AppGraph.get().github.savedToken()
                    ?: throw IllegalStateException("Connect GitHub first (GitHub Sync screen).")
                SigningKeyManager.pushSigningSecrets(
                    owner.trim(), repo.trim(),
                    gen.pkcs12, gen.alias,
                    storePassword, keyPassword,
                    token,
                ).getOrThrow()
                _pushDone.value = true
                _status.value = "4 secrets pushed to $owner/$repo."
            } catch (t: Throwable) {
                // Throwable, not just Exception: crypto/native failures can
                // surface as Errors (e.g. UnsatisfiedLinkError), which must
                // never crash the app from here.
                _status.value = t.message ?: "Secret push failed."
            } finally {
                storePassword.wipe()
                keyPassword.wipe()
                _busy.value = false
            }
        }
    }

    fun clearGenerated() {
        _generated.value = null
        _pushDone.value = false
        _status.value = null
    }

    fun setStatus(message: String) {
        _status.value = message
    }
}
