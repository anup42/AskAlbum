package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class RetrievalGenerationPointerTest {
    @Test
    fun previousGenerationRecoversAfterInterruptedActivation() {
        val root = Files.createTempDirectory("retrieval-pointers-").toFile()
        try {
            val pointer = RetrievalGenerationPointer(root)
            pointer.activate("generation-one")
            assertEquals("generation-one", pointer.currentName())
            assertNull(pointer.previousName())

            pointer.activate("generation-two")
            assertEquals("generation-two", pointer.currentName())
            assertEquals("generation-one", pointer.previousName())

            root.resolve("current").delete()
            assertNull(pointer.currentName())
            val previous = requireNotNull(pointer.previousName())
            pointer.restore(previous)

            assertEquals("generation-one", pointer.currentName())
            assertEquals("generation-one", pointer.previousName())
        } finally {
            root.deleteRecursively()
        }
    }
}
