package com.indigo.mobileobservatory.stacking

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StackExportTransactionTest {
    @Test fun publicFilesAppearOnlyAfterTheirContentIsComplete() = directory { root ->
        val files = listOf(File(root, "result.json"), File(root, "result.png"), File(root, "result-linear.fits"))
        StackExportTransaction(files).use { transaction ->
            files.forEachIndexed { index, file -> transaction.temporary(file).writeText("complete-$index") }
            assertTrue(files.none { it.exists() })
            transaction.commit()
        }
        files.forEachIndexed { index, file -> assertEquals("complete-$index", file.readText()) }
        assertEquals(3, root.listFiles()!!.size)
    }

    @Test fun cancellationDuringPublicationRollsBackOnlyThisExport() = directory { root ->
        val files = listOf(File(root, "result.json"), File(root, "result.png"), File(root, "result.fits"))
        val unrelated = File(root, "other.fit").apply { writeText("original") }
        try {
            StackExportTransaction(files).use { transaction ->
                files.forEach { transaction.temporary(it).writeText("complete") }
                transaction.commit { if (files.first().exists()) throw CancellationException() }
            }
            fail("Cancellation ignored")
        } catch (_: CancellationException) { }
        assertEquals(listOf(unrelated.name), root.listFiles()!!.map { it.name })
        assertEquals("original", unrelated.readText())
    }

    @Test fun collidingExportsPreserveExistingFiles() = directory { root ->
        val first = File(root, "first.fit"); val second = File(root, "second.png").apply { writeText("keep") }
        try {
            StackExportTransaction(listOf(first, second)).use { output ->
                output.temporary(first).writeText("new"); output.temporary(second).writeText("new")
                output.commit()
            }; fail("Existing output overwritten")
        } catch (_: IllegalArgumentException) { }
        assertEquals("keep", second.readText()); assertFalse(first.exists())
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test fun incompleteExportsPublishNothingAndRemoveTemporaryFiles() = directory { root ->
        val files = listOf(File(root, "linear.fit"), File(root, "display.png"))
        try {
            StackExportTransaction(files).use { output ->
                output.temporary(files.first()).writeText("complete")
                output.commit()
            }
            fail("Incomplete export published")
        } catch (_: IllegalArgumentException) { }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("stack-export").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
