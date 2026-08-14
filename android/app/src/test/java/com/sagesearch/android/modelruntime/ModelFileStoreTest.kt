package com.sagesearch.android.modelruntime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun successfulImportIsHashedAndAtomicallyFinalized() = runTest {
        val directory = temporaryFolder.newFolder("models")
        val bytes = "compatible model bytes".encodeToByteArray()
        val progress = mutableListOf<Long>()
        val store = ModelFileStore(directory) { Long.MAX_VALUE }

        val imported = store.import(source("gemma.litertlm", bytes)) { copied, _ -> progress += copied }

        assertTrue(imported.file.isFile)
        assertEquals(bytes.size.toLong(), imported.sizeBytes)
        assertEquals(sha256(bytes), imported.sha256)
        assertTrue(imported.file.name.startsWith("model-${sha256(bytes)}"))
        assertEquals(bytes.size.toLong(), progress.last())
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun cancellationRemovesPartialCopy() = runTest {
        val directory = temporaryFolder.newFolder("cancelled")
        val store = ModelFileStore(directory) { Long.MAX_VALUE }
        val cancellingSource = ModelSource("gemma.litertlm", 128L) {
            object : InputStream() {
                override fun read(): Int = throw CancellationException("cancelled")
            }
        }

        var cancelled = false
        try {
            store.import(cancellingSource)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test
    fun lowSpaceFailsBeforeOpeningSource() = runTest {
        val directory = temporaryFolder.newFolder("low-space")
        var opened = false
        val source = ModelSource("gemma.litertlm", 10L) {
            opened = true
            ByteArrayInputStream(ByteArray(10))
        }

        val error = runCatching { ModelFileStore(directory) { 0L }.import(source) }.exceptionOrNull()

        assertTrue(error is InsufficientModelStorage)
        assertFalse(opened)
    }

    @Test
    fun nonLiteRtContainerIsRejected() = runTest {
        val directory = temporaryFolder.newFolder("unsupported")
        val error = runCatching {
            ModelFileStore(directory) { Long.MAX_VALUE }
                .import(source("gemma.bin", byteArrayOf(1)))
        }.exceptionOrNull()

        assertTrue(error is UnsupportedModelContainer)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun stalePartialIsRemovedWithoutDeletingCompletedModel() {
        val directory = temporaryFolder.newFolder("stale")
        val partial = File(directory, "old.litertlm.partial").apply { writeText("partial") }
        val complete = File(directory, "model-good.litertlm").apply { writeText("complete") }

        ModelFileStore(directory) { Long.MAX_VALUE }.cleanupStalePartials()

        assertFalse(partial.exists())
        assertTrue(complete.exists())
        assertNotNull(complete)
    }

    private fun source(name: String, bytes: ByteArray) =
        ModelSource(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
