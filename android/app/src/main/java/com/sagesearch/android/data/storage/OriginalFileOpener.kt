package com.sagesearch.android.data.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.sagesearch.android.model.SearchResult
import java.io.IOException

sealed interface FileOpenOutcome {
    data object Opened : FileOpenOutcome
    data object Unavailable : FileOpenOutcome
}

fun interface FileLaunchGateway {
    fun launch(contentUri: String, mimeType: String): Boolean
}

interface OriginalFileOpener {
    fun open(result: SearchResult): FileOpenOutcome
}

class SafeOriginalFileOpener(
    private val gateway: FileLaunchGateway,
) : OriginalFileOpener {
    override fun open(result: SearchResult): FileOpenOutcome = try {
        if (gateway.launch(result.contentUri, result.mimeType)) FileOpenOutcome.Opened else FileOpenOutcome.Unavailable
    } catch (error: SecurityException) {
        FileOpenOutcome.Unavailable
    } catch (error: ActivityNotFoundException) {
        FileOpenOutcome.Unavailable
    } catch (error: IllegalArgumentException) {
        FileOpenOutcome.Unavailable
    } catch (error: IOException) {
        FileOpenOutcome.Unavailable
    }
}

class AndroidFileLaunchGateway(
    private val context: Context,
) : FileLaunchGateway {
    override fun launch(contentUri: String, mimeType: String): Boolean {
        val uri = Uri.parse(contentUri)
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
            ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }
}
