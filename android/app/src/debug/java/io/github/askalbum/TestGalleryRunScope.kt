package io.github.anup42.askalbum

/** Exact shared-storage boundary reserved for one connected-test gallery run. */
internal object TestGalleryRunScope {
    private val runIdPattern = Regex("[A-Za-z0-9_-]{6,64}")

    fun relativePaths(runId: String): List<String> {
        require(runIdPattern.matches(runId)) { "Invalid gallery run ID" }
        return listOf(
            "Pictures/AskAlbumTest/$runId/",
            "Documents/AskAlbumTest/$runId/",
        )
    }

    fun relativePath(runId: String, isDocument: Boolean): String =
        relativePaths(runId)[if (isDocument) 1 else 0]
}
