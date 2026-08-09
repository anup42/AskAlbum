package io.github.anup42.askalbum

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleIndexWorkerPolicyTest {
    @Test
    fun cancellationIsPropagatedInsteadOfQuarantined() {
        assertTrue(PeopleIndexFailurePolicy.shouldPropagate(CancellationException("work stopped")))
        assertFalse(PeopleIndexFailurePolicy.shouldPropagate(IOException("bad media")))
    }
}
