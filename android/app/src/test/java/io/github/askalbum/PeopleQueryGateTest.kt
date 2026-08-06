package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleQueryGateTest {
    private val peoplePlan = QueryCompiler().compile("Show photos with Dad").copy(
        peopleClauses = listOf(PersonClause("dad")),
    )

    @Test
    fun peopleQueryFailsClosedWhileConsentIsOff() {
        val reason = PeopleQueryGate.unavailableReason(peoplePlan, PeopleIndexStatus(enabled = false))
        assertTrue(reason.orEmpty().contains("off"))
    }

    @Test
    fun faceBoxesAloneNeverEnableIdentitySearch() {
        val status = PeopleIndexStatus(enabled = true, faceInstanceCount = 12, reviewedClusterCount = 1)
        val reason = PeopleQueryGate.unavailableReason(peoplePlan, status)
        assertTrue(reason.orEmpty().contains("does not prove identity"))
    }

    @Test
    fun identityReadyRecordsOpenGateAndNonPeopleQueriesBypassIt() {
        assertNull(
            PeopleQueryGate.unavailableReason(
                peoplePlan,
                PeopleIndexStatus(enabled = true, reviewedClusterCount = 1, identityReadyFaceCount = 1),
            ),
        )
        assertNull(PeopleQueryGate.unavailableReason(QueryCompiler().compile("Show beach photos"), PeopleIndexStatus()))
    }

    @Test
    fun hiddenOnlyIdentityCannotUnlockPeopleSearch() {
        val reason = PeopleQueryGate.unavailableReason(
            peoplePlan,
            PeopleIndexStatus(enabled = true, reviewedClusterCount = 0, identityReadyFaceCount = 1),
        )

        assertTrue(reason.orEmpty().contains("Hidden or ignored"))
    }

    @Test
    fun unavailablePeopleCoverageUsesMediaUnitsAndReportsNoSearch() {
        val report = PeopleUnavailableCoveragePolicy.report(5_000)

        assertEquals(5_000, report.eligibleCount)
        assertEquals(0, report.indexedCount)
        assertEquals(0, report.searchedCount)
        assertEquals(ChannelStatus.UNAVAILABLE, report.status)
    }

    @Test
    fun anotherClusterCannotUnlockARequestedClusterWithoutAnIdentityEmbedding() {
        val status = PeopleIndexStatus(enabled = true, reviewedClusterCount = 1, identityReadyFaceCount = 1)
        val reason = PeopleQueryGate.unavailableReason(
            peoplePlan,
            status,
            identityReadyFor = { requested -> requested != "dad" },
        )

        assertTrue(reason.orEmpty().contains("requested identity"))
    }
}
