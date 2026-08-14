package com.sagesearch.android.modelruntime

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ModelFileStore(
    private val directory: File,
    private val availableBytes: () -> Long,
) {
    fun cleanupStalePartials() {
        if (!directory.exists()) return
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(PARTIAL_SUFFIX) }
            .forEach(File::delete)
    }

    fun resolve(fileName: String): File? =
        fileName.takeIf(::isSafeFileName)
            ?.let { File(directory, it) }
            ?.takeIf(File::isFile)

    suspend fun import(
        source: ModelSource,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): ImportedModel {
        if (!source.displayName.endsWith(MODEL_SUFFIX, ignoreCase = true)) {
            throw UnsupportedModelContainer(source.displayName)
        }
        check(directory.exists() || directory.mkdirs()) { "Could not create the private model directory." }
        cleanupStalePartials()

        val knownSize = source.sizeBytes?.takeIf { it >= 0L }
        val margin = max(MINIMUM_HEADROOM_BYTES, (knownSize ?: 0L) / 10L)
        val required = knownSize?.let { size ->
            if (size > Long.MAX_VALUE - margin) Long.MAX_VALUE else size + margin
        } ?: margin
        val available = availableBytes()
        if (available < required) throw InsufficientModelStorage(required, available)

        val partial = File(directory, PARTIAL_FILE_NAME)
        var copied = 0L
        var lastReported = -PROGRESS_STEP_BYTES
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = try {
                source.openStream()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw ModelSourceUnavailable(error)
            }
            input.use { stream ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = try {
                            stream.read(buffer)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            throw ModelSourceUnavailable(error)
                        }
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        if (copied - lastReported >= PROGRESS_STEP_BYTES) {
                            onProgress(copied, knownSize)
                            lastReported = copied
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            onProgress(copied, knownSize)
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(directory, "model-$sha256$MODEL_SUFFIX")
            if (destination.exists()) {
                check(destination.length() == copied) { "Existing private model does not match the selected model." }
                check(partial.delete()) { "Could not remove the completed temporary model." }
            } else if (!partial.renameTo(destination)) {
                throw ModelPreparationException("Could not finalize the private model copy.")
            }
            return ImportedModel(destination, source.displayName, copied, sha256)
        } catch (cancelled: CancellationException) {
            partial.delete()
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    fun deleteCandidate(file: File, keepFileName: String? = null) {
        if (file.parentFile == directory && file.name != keepFileName) file.delete()
    }

    fun cleanupModelsExcept(fileName: String?) {
        if (!directory.exists()) return
        directory.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.name.endsWith(MODEL_SUFFIX) &&
                    file.name != fileName
            }
            .forEach(File::delete)
    }

    private fun isSafeFileName(fileName: String): Boolean =
        fileName.isNotBlank() &&
            fileName == File(fileName).name &&
            fileName.endsWith(MODEL_SUFFIX)

    companion object {
        private const val MODEL_SUFFIX = ".litertlm"
        private const val PARTIAL_SUFFIX = ".partial"
        private const val PARTIAL_FILE_NAME = "incoming.litertlm.partial"
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val PROGRESS_STEP_BYTES = 16L * 1024L * 1024L
        private const val MINIMUM_HEADROOM_BYTES = 256L * 1024L * 1024L
    }
}
