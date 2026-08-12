package io.github.anup42.askalbum

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal data class TestGalleryCleanupUris(
    val values: List<String>,
    val source: String,
)

internal object TestGalleryCleanupEvidence {
    const val EXACT_PATH_PROOF = "exact reserved run-scoped AskAlbumTest paths"
    const val SEED_RESULT_SOURCE = "SEED_RESULT"
    const val ORPHAN_RECOVERY_SOURCE = "ORPHAN_RECOVERY"

    fun resolve(
        runId: String,
        seedResult: JSONObject?,
        orphanRecovery: JSONObject?,
    ): TestGalleryCleanupUris = if (seedResult != null) {
        require(seedResult.optString("state") == "COMPLETE") { "Seed result is incomplete" }
        require(seedResult.optString("runId") == runId) { "Seed result belongs to another run" }
        TestGalleryCleanupUris(
            values = validatedUris(seedResult.getJSONArray("createdUris"), seedResult.optInt("createdCount", -1)),
            source = SEED_RESULT_SOURCE,
        )
    } else {
        val recovery = requireNotNull(orphanRecovery) { "No run-scoped cleanup evidence exists for $runId" }
        require(recovery.optString("state") == "RECOVERED") { "Orphan recovery is incomplete" }
        require(recovery.optString("runId") == runId) { "Orphan recovery belongs to another run" }
        require(recovery.optString("proof") == EXACT_PATH_PROOF) { "Orphan recovery lacks exact-path proof" }
        TestGalleryCleanupUris(
            values = validatedUris(recovery.getJSONArray("createdUris"), recovery.optInt("createdCount", -1)),
            source = ORPHAN_RECOVERY_SOURCE,
        )
    }

    fun mergeRecovery(
        runId: String,
        previous: JSONObject?,
        currentlyOwnedUris: Collection<String>,
    ): JSONObject {
        val priorUris = previous?.let { resolve(runId, seedResult = null, orphanRecovery = it).values }.orEmpty()
        val merged = (priorUris + currentlyOwnedUris).distinct()
        validatedUris(JSONArray(merged), merged.size)
        return JSONObject()
            .put("state", "RECOVERED")
            .put("runId", runId)
            .put("createdUris", JSONArray(merged))
            .put("createdCount", merged.size)
            .put("proof", EXACT_PATH_PROOF)
    }

    private fun validatedUris(values: JSONArray, declaredCount: Int): List<String> {
        val uris = List(values.length()) { index -> values.getString(index) }
        require(declaredCount == uris.size) { "Cleanup evidence count is inconsistent" }
        require(uris.distinct().size == uris.size) { "Cleanup evidence contains duplicate URIs" }
        uris.forEach { value ->
            val uri = runCatching { URI(value) }.getOrNull()
            require(
                uri != null && uri.scheme == "content" && uri.authority == "media" &&
                    uri.rawQuery == null && uri.rawFragment == null,
            ) { "Cleanup evidence contains a non-MediaStore URI" }
        }
        return uris
    }
}
