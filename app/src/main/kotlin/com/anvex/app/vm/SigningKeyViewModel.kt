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

    /**
     * Typed status (audit 2026-11-03): the screen used to color the
     * status by substring-matching its text ("fail", "denied", …) —
     * the kind now travels with the message.
     */
    sealed interface Status {
        val text: String
        data class Info(override val text: String) : Status
        data class Success(override val text: String) : Status
        data class Failure(override val text: String) : Status
    }

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status

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
                _status.value = Status.Success("Signing key generated on this device.")
            } catch (e: Exception) {
                _status.value = Status.Failure(e.message ?: "Key generation failed.")
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
        val gen = _generated.value ?: run {
            // Was a silent no-op: the push button looked dead when no key
            // had been generated yet (audit 2026-11-03).
            _status.value = Status.Info("Generate a signing key first.")
            return
        }
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
                _status.value = Status.Success("4 secrets pushed to $owner/$repo.")
            } catch (t: Throwable) {
                // Throwable, not just Exception: crypto/native failures can
                // surface as Errors (e.g. UnsatisfiedLinkError), which must
                // never crash the app from here.
                _status.value = Status.Failure(t.message ?: "Secret push failed.")
            } finally {
                storePassword.wipe()
                keyPassword.wipe()
                _busy.value = false
            }
        }
    }

    fun clearGenerated() {
        // Zero the key material BEFORE dropping the reference (audit
        // 2026-11-03): passwords are wiped everywhere else in this flow,
        // but the PKCS12 bytes themselves used to linger in the heap
        // until GC got around to them.
        _generated.value?.pkcs12?.fill(0)
        _generated.value = null
        _pushDone.value = false
        _status.value = null
    }

    fun setStatus(status: Status) {
        _status.value = status
    }
}
