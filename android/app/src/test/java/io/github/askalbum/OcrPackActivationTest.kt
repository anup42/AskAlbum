package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OcrPackActivationTest {
    @Test
    fun previousPackIsRetainedAndRecoveredAfterInterruptedActivation() {
        val root = Files.createTempDirectory("ocr-activation-").toFile()
        try {
            val activation = OcrPackActivation(root)
            val first = File(root, "active.next").apply { mkdirs(); File(this, "marker").writeText("first") }
            activation.activate(first)
            val second = File(root, "active.next").apply { mkdirs(); File(this, "marker").writeText("second") }
            activation.activate(second)

            assertEquals("second", File(activation.activeRoot, "marker").readText())
            val backup = root.listFiles()!!.single { it.name.startsWith("active.previous-") }
            assertEquals("first", File(backup, "marker").readText())

            activation.activeRoot.deleteRecursively()
            assertTrue(
                activation.recoverIfMissing { directory ->
                    File(directory, "marker").readText() == "first"
                },
            )
            assertEquals("first", File(activation.activeRoot, "marker").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validStagingDirectoryIsRecoveredWhenActivationStoppedBeforeRename() {
        val root = Files.createTempDirectory("ocr-staging-recovery-").toFile()
        try {
            val activation = OcrPackActivation(root)
            val staging = File(root, "active.next").apply { mkdirs(); File(this, "marker").writeText("staged") }

            assertTrue(activation.recoverIfMissing { File(it, "marker").readText() == "staged" })
            assertEquals("staged", File(activation.activeRoot, "marker").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
