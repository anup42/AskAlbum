package io.github.anup42.askalbum

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class GalleryAccessCoverageStatus {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
    NOT_REQUIRED,
}

data class GalleryAccessCoverageReport(
    val status: GalleryAccessCoverageStatus,
    val requiredKinds: Set<MediaKind> = emptySet(),
    val fullyGrantedKinds: Set<MediaKind> = emptySet(),
    val selectedVisualAccess: Boolean = false,
) {
    val complete: Boolean
        get() = status == GalleryAccessCoverageStatus.COMPLETE ||
            status == GalleryAccessCoverageStatus.NOT_REQUIRED

    fun warning(): String? = when (status) {
        GalleryAccessCoverageStatus.COMPLETE,
        GalleryAccessCoverageStatus.NOT_REQUIRED,
        -> null
        GalleryAccessCoverageStatus.PARTIAL ->
            "Android has granted only partial gallery access. Results cover currently accessible items, not the complete gallery."
        GalleryAccessCoverageStatus.UNAVAILABLE ->
            "Gallery access is off. Results cover only items already available inside AskAlbum, not the complete gallery."
    }

    fun countDetail(eligibleAccessibleCount: Int): String = when (status) {
        GalleryAccessCoverageStatus.PARTIAL ->
            "The app evaluated $eligibleAccessibleCount currently accessible eligible items. Android has granted only partial gallery access, so this is not a complete gallery count."
        GalleryAccessCoverageStatus.UNAVAILABLE ->
            "The app evaluated $eligibleAccessibleCount eligible items already available inside AskAlbum. Gallery access is off, so this is not a complete gallery count."
        GalleryAccessCoverageStatus.COMPLETE,
        GalleryAccessCoverageStatus.NOT_REQUIRED,
        -> "This is not an exhaustive visual predicate count; channel coverage is shown below."
    }
}

internal object MediaAccessCoveragePolicy {
    fun requiredKinds(
        plan: GalleryQueryPlan,
        closedExecutionScope: Boolean = false,
    ): Set<MediaKind> {
        if (closedExecutionScope || plan.baseResultIds != null) return emptySet()
        return when (plan.mediaScope) {
            MediaScope.IMAGES -> setOf(MediaKind.IMAGE)
            MediaScope.VIDEOS -> setOf(MediaKind.VIDEO)
            MediaScope.DOCUMENTS -> setOf(MediaKind.IMAGE)
            MediaScope.ALL -> setOf(MediaKind.IMAGE, MediaKind.VIDEO)
        }
    }

    fun resolve(
        requiredKinds: Set<MediaKind>,
        fullyGrantedKinds: Set<MediaKind>,
        selectedVisualAccess: Boolean,
    ): GalleryAccessCoverageReport {
        if (requiredKinds.isEmpty()) {
            return GalleryAccessCoverageReport(GalleryAccessCoverageStatus.NOT_REQUIRED)
        }
        val grantedRequiredKinds = fullyGrantedKinds intersect requiredKinds
        val status = when {
            grantedRequiredKinds.size == requiredKinds.size -> GalleryAccessCoverageStatus.COMPLETE
            grantedRequiredKinds.isNotEmpty() || selectedVisualAccess -> GalleryAccessCoverageStatus.PARTIAL
            else -> GalleryAccessCoverageStatus.UNAVAILABLE
        }
        return GalleryAccessCoverageReport(
            status = status,
            requiredKinds = requiredKinds,
            fullyGrantedKinds = grantedRequiredKinds,
            selectedVisualAccess = selectedVisualAccess,
        )
    }
}

internal class AndroidMediaAccessCoverage(context: Context) {
    private val appContext = context.applicationContext

    fun reportFor(
        plan: GalleryQueryPlan,
        closedExecutionScope: Boolean = false,
    ): GalleryAccessCoverageReport {
        val requiredKinds = MediaAccessCoveragePolicy.requiredKinds(plan, closedExecutionScope)
        return MediaAccessCoveragePolicy.resolve(
            requiredKinds = requiredKinds,
            fullyGrantedKinds = requiredKinds.filterTo(mutableSetOf(), ::hasFullPermission),
            selectedVisualAccess = hasSelectedVisualAccess(),
        )
    }

    fun hasFullPermission(kind: MediaKind): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> permissionGranted(
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        kind == MediaKind.IMAGE -> permissionGranted(Manifest.permission.READ_MEDIA_IMAGES)
        kind == MediaKind.VIDEO -> permissionGranted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> false
    }

    private fun hasSelectedVisualAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        permissionGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun permissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
