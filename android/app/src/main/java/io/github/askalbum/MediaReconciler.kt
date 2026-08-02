package io.github.anup42.askalbum

object MediaReconciler {
    fun plan(existing: List<GalleryItem>, snapshot: MediaScanSnapshot): MediaReconciliationPlan {
        val seen = snapshot.items.mapTo(linkedSetOf()) { it.uri }
        val inaccessible = linkedSetOf<String>()
        val deleted = linkedSetOf<String>()
        existing.asSequence()
            .filter { it.source == MediaSource.MEDIA_STORE }
            .forEach { item ->
                val uri = item.contentUri ?: return@forEach
                when {
                    uri in seen -> Unit
                    item.kind in snapshot.fullyCoveredKinds -> deleted += uri
                    else -> inaccessible += uri
                }
            }
        return MediaReconciliationPlan(seen, inaccessible, deleted)
    }
}
