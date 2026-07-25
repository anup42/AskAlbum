package com.samsung.agenticgallery

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** Visible debug trampoline required by Android 12+ before starting foreground download work. */
class TestGemmaDownloadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setContentView(TextView(this).apply {
            text = "Starting verified Gemma model download…"
            textSize = 18f
            setPadding(48, 96, 48, 48)
        })
        sendBroadcast(
            Intent("com.samsung.agenticgallery.test.DOWNLOAD_GEMMA")
                .setComponent(ComponentName(this, TestGemmaModelReceiver::class.java))
                .putExtra("tier", intent.getStringExtra("tier"))
                .putExtra("operation_id", intent.getStringExtra("operation_id")),
        )
        window.decorView.postDelayed({ finish() }, 5_000)
    }
}
