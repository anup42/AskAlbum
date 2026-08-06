package io.github.anup42.askalbum

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerCancellationPolicyTest {
    @Test
    fun mediaScanCancellationIsPropagated() {
        assertTrue(MediaScanFailurePolicy.shouldPropagate(CancellationException("stopped")))
        assertFalse(MediaScanFailurePolicy.shouldPropagate(IOException("scan failed")))
    }

    @Test
    fun identityExpansionCancellationIsPropagated() {
        assertTrue(ReviewedIdentityExpansionFailurePolicy.shouldPropagate(CancellationException("stopped")))
        assertFalse(ReviewedIdentityExpansionFailurePolicy.shouldPropagate(IOException("expansion failed")))
    }
}
