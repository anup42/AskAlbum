package com.askphotos.android

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class SensitiveEvidenceGate(
    private val activity: FragmentActivity,
    private val onAuthorized: (SearchHit) -> Unit,
) {
    private var pending: SearchHit? = null
    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                pending?.let(onAuthorized)
                pending = null
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                pending = null
            }
        },
    )

    fun open(hit: SearchHit) {
        if (!SensitiveEvidencePolicy.requiresAuthentication(hit)) {
            onAuthorized(hit)
            return
        }
        pending = hit
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock sensitive gallery evidence")
                .setSubtitle("Authenticate to view OCR that may contain private information")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }
}
