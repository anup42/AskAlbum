package io.github.anup42.askalbum

import android.content.Context
import java.io.File
import org.json.JSONObject

internal fun seededMediaIds(repository: GalleryRepository, context: Context, runId: String): Set<String> {
    val resultFile = File(context.filesDir, "test-seed/$runId/seed-result.json")
    require(resultFile.isFile) { "Seed result is unavailable for run $runId" }
    val result = JSONObject(resultFile.readText())
    val uris = result.getJSONArray("createdUris").let { array ->
        (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it) }
    }
    val ids = repository.allItems()
        .asSequence()
        .filter { it.contentUri in uris }
        .map(GalleryItem::id)
        .toSet()
    require(ids.size == result.getInt("createdCount")) {
        "Seeded URI set resolved ${ids.size} of ${result.getInt("createdCount")} media rows"
    }
    return ids
}
