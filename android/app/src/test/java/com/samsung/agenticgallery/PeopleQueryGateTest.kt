package com.samsung.agenticgallery

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
        assertNull(PeopleQueryGate.unavailableReason(peoplePlan, PeopleIndexStatus(enabled = true, identityReadyFaceCount = 1)))
        assertNull(PeopleQueryGate.unavailableReason(QueryCompiler().compile("Show beach photos"), PeopleIndexStatus()))
    }
}
