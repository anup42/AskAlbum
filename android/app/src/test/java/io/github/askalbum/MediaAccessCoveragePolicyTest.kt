package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAccessCoveragePolicyTest {
    @Test
    fun deniedImageAccessMakesUnscopedPhotoCoverageUnavailable() {
        val report = MediaAccessCoveragePolicy.resolve(
            requiredKinds = MediaAccessCoveragePolicy.requiredKinds(plan(MediaScope.IMAGES)),
            fullyGrantedKinds = emptySet(),
            selectedVisualAccess = false,
        )

        assertEquals(GalleryAccessCoverageStatus.UNAVAILABLE, report.status)
        assertFalse(report.complete)
        assertTrue(report.countDetail(0).contains("Gallery access is off"))
    }

    @Test
    fun selectedOnlyAccessIsPartialRatherThanComplete() {
        val report = MediaAccessCoveragePolicy.resolve(
            requiredKinds = setOf(MediaKind.IMAGE),
            fullyGrantedKinds = emptySet(),
            selectedVisualAccess = true,
        )

        assertEquals(GalleryAccessCoverageStatus.PARTIAL, report.status)
        assertFalse(report.complete)
        assertTrue(report.countDetail(12).contains("not a complete gallery count"))
    }

    @Test
    fun mediaKindSpecificFullPermissionAllowsCompleteCoverage() {
        val report = MediaAccessCoveragePolicy.resolve(
            requiredKinds = MediaAccessCoveragePolicy.requiredKinds(plan(MediaScope.IMAGES)),
            fullyGrantedKinds = setOf(MediaKind.IMAGE),
            selectedVisualAccess = false,
        )

        assertEquals(GalleryAccessCoverageStatus.COMPLETE, report.status)
        assertTrue(report.complete)
    }

    @Test
    fun allMediaScopeRequiresBothImageAndVideoPermission() {
        val report = MediaAccessCoveragePolicy.resolve(
            requiredKinds = MediaAccessCoveragePolicy.requiredKinds(plan(MediaScope.ALL)),
            fullyGrantedKinds = setOf(MediaKind.IMAGE),
            selectedVisualAccess = false,
        )

        assertEquals(GalleryAccessCoverageStatus.PARTIAL, report.status)
        assertFalse(report.complete)
    }

    @Test
    fun explicitResultSetIsAClosedScopeIndependentOfGalleryPermission() {
        val required = MediaAccessCoveragePolicy.requiredKinds(
            plan(MediaScope.IMAGES).copy(baseResultIds = setOf("result-item")),
        )
        val report = MediaAccessCoveragePolicy.resolve(required, emptySet(), selectedVisualAccess = false)

        assertEquals(GalleryAccessCoverageStatus.NOT_REQUIRED, report.status)
        assertTrue(report.complete)
    }

    @Test
    fun repositorySuppliedExecutionScopeIsClosedEvenWhenPlanOmitsBaseIds() {
        val required = MediaAccessCoveragePolicy.requiredKinds(
            plan(MediaScope.IMAGES),
            closedExecutionScope = true,
        )
        val report = MediaAccessCoveragePolicy.resolve(required, emptySet(), selectedVisualAccess = false)

        assertEquals(GalleryAccessCoverageStatus.NOT_REQUIRED, report.status)
        assertTrue(report.complete)
    }

    private fun plan(scope: MediaScope) = GalleryQueryPlan(
        originalQuery = "How many photos did I take?",
        intent = QueryIntent.COUNT,
        mediaScope = scope,
        aggregation = AggregationSpec(AggregationOperation.COUNT),
    )
}
