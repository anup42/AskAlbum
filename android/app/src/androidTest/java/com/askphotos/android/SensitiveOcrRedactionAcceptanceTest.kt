package com.askphotos.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveOcrRedactionAcceptanceTest {
    @Test
    fun wifiCredentialIsRetrievedButWithheldFromAnswerAndGemmaPacket() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val repository = (instrumentation.targetContext.applicationContext as AskPhotosApplication).repository
        val indexedWifi = requireNotNull(repository.allItems().firstOrNull { it.filename == "synthetic_wifi_card.png" }) {
            "The retained core corpus has no synthetic Wi-Fi card row"
        }
        val indexedText = (indexedWifi.ocrText + " " + repository.ocrBlocks(indexedWifi.id).joinToString(" ") { it.text }).lowercase()
        assertTrue("The retained Wi-Fi card has no indexed credential OCR (state=${indexedWifi.indexState})", "mango-tree-2048" in indexedText)

        val outcome = repository.search("What is the Wi-Fi password in my test card?")
        val hit = requireNotNull(outcome.hits.firstOrNull { it.item.filename == "synthetic_wifi_card.png" }) {
            "The indexed synthetic Wi-Fi card was not retrieved; plan=${outcome.plan}; hits=${outcome.hits.map { it.item.filename }}"
        }
        val privateText = (hit.item.ocrText + " " + hit.evidence.joinToString(" ") { it.text }).lowercase()
        assertTrue("The local OCR fixture was not indexed", "mango-tree-2048" in privateText)
        assertTrue(outcome.answer.requiresAuthentication)
        assertTrue(outcome.answer.evidenceIds.isEmpty())
        assertTrue(outcome.answer.claims.isEmpty())
        val renderedAnswer = (outcome.answer.headline + " " + outcome.answer.detail).lowercase()
        assertFalse("Sensitive OCR leaked into the answer card", "mango-tree-2048" in renderedAnswer)
        assertTrue(SensitiveEvidencePolicy.requiresAuthentication(hit))
        runCatching {
            GroundedEvidencePacketBuilder.build(GroundedAnswerInput(outcome.plan, outcome.hits, outcome.answer))
        }.onSuccess { error("Sensitive OCR entered a Gemma evidence packet") }
        Unit
    }
}
