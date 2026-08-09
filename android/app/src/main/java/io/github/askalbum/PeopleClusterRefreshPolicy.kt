package io.github.anup42.askalbum

internal object PeopleClusterRefreshPolicy {
    fun shouldReload(knownRevision: String?, currentRevision: String, force: Boolean = false): Boolean =
        force || knownRevision == null || knownRevision != currentRevision
}
