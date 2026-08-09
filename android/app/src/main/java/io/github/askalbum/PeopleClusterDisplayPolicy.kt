package io.github.anup42.askalbum

/** Keeps sparse automatic clusters out of the People browser without deleting local data. */
internal object PeopleClusterDisplayPolicy {
    const val MIN_MEDIA_COUNT = 5

    fun visible(clusters: List<PersonClusterReviewItem>): List<PersonClusterReviewItem> =
        clusters.filter { it.hidden || it.mediaCount >= MIN_MEDIA_COUNT }
}
