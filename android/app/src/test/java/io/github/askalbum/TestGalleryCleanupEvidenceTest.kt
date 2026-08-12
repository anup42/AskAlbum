package io.github.anup42.askalbum

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TestGalleryCleanupEvidenceTest {
    @Test
    fun seedResultIsPreferredWhenAvailable() {
        val seed = completeSeed("scale_run", listOf(mediaUri(1), mediaUri(2)))
        val recovery = recovery("scale_run", listOf(mediaUri(3)))

        val resolved = TestGalleryCleanupEvidence.resolve("scale_run", seed, recovery)

        assertEquals(TestGalleryCleanupEvidence.SEED_RESULT_SOURCE, resolved.source)
        assertEquals(listOf(mediaUri(1), mediaUri(2)), resolved.values)
    }

    @Test
    fun exactPathRecoveryClosesMissingSeedResult() {
        val resolved = TestGalleryCleanupEvidence.resolve(
            runId = "scale_run",
            seedResult = null,
            orphanRecovery = recovery("scale_run", listOf(mediaUri(7), mediaUri(8))),
        )

        assertEquals(TestGalleryCleanupEvidence.ORPHAN_RECOVERY_SOURCE, resolved.source)
        assertEquals(listOf(mediaUri(7), mediaUri(8)), resolved.values)
    }

    @Test
    fun repeatedCleanupPreservesCumulativeRecoveryEvidence() {
        val merged = TestGalleryCleanupEvidence.mergeRecovery(
            runId = "scale_run",
            previous = recovery("scale_run", listOf(mediaUri(1), mediaUri(2))),
            currentlyOwnedUris = listOf(mediaUri(2), mediaUri(3)),
        )

        assertEquals(3, merged.getInt("createdCount"))
        assertEquals(
            listOf(mediaUri(1), mediaUri(2), mediaUri(3)),
            merged.getJSONArray("createdUris").strings(),
        )
    }

    @Test
    fun recoveryWithoutExactPathProofIsRejected() {
        val untrusted = recovery("scale_run", listOf(mediaUri(1))).put("proof", "filename match")

        assertThrows(IllegalArgumentException::class.java) {
            TestGalleryCleanupEvidence.resolve("scale_run", seedResult = null, orphanRecovery = untrusted)
        }
    }

    @Test
    fun duplicateOrNonMediaUrisAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TestGalleryCleanupEvidence.resolve(
                "scale_run",
                completeSeed("scale_run", listOf(mediaUri(1), mediaUri(1))),
                orphanRecovery = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TestGalleryCleanupEvidence.resolve(
                "scale_run",
                completeSeed("scale_run", listOf("content://contacts/people/1")),
                orphanRecovery = null,
            )
        }
    }

    private fun completeSeed(runId: String, uris: List<String>) = JSONObject()
        .put("state", "COMPLETE")
        .put("runId", runId)
        .put("createdUris", JSONArray(uris))
        .put("createdCount", uris.size)

    private fun recovery(runId: String, uris: List<String>) = JSONObject()
        .put("state", "RECOVERED")
        .put("runId", runId)
        .put("createdUris", JSONArray(uris))
        .put("createdCount", uris.size)
        .put("proof", TestGalleryCleanupEvidence.EXACT_PATH_PROOF)

    private fun JSONArray.strings(): List<String> = List(length()) { index -> getString(index) }

    private fun mediaUri(id: Int) = "content://media/external_primary/file/$id"
}
