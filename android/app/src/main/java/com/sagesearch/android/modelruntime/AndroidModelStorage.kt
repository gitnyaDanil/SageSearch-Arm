package com.sagesearch.android.modelruntime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first

class AndroidModelSourceResolver(
    private val resolver: ContentResolver,
) : ModelSourceResolver {
    override fun resolve(contentUri: String): ModelSource {
        val uri = Uri.parse(contentUri)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only Android document URIs are accepted." }
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "selected-model.litertlm"
        var sizeBytes: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        return ModelSource(displayName, sizeBytes) {
            resolver.openInputStream(uri) ?: throw IllegalArgumentException("Selected document cannot be opened.")
        }
    }
}

class AndroidModelMetadataStore(context: Context) : ModelMetadataStore {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(context.noBackupFilesDir, FILE_NAME) },
    )

    override suspend fun read(): ModelMetadata? {
        val values = dataStore.data.first()
        val fileName = values[Keys.FILE_NAME] ?: return null
        val displayName = values[Keys.DISPLAY_NAME] ?: return null
        val sizeBytes = values[Keys.SIZE_BYTES] ?: return null
        val sha256 = values[Keys.SHA256] ?: return null
        val runtimeVersion = values[Keys.RUNTIME_VERSION] ?: return null
        return ModelMetadata(fileName, displayName, sizeBytes, sha256, runtimeVersion)
    }

    override suspend fun write(metadata: ModelMetadata) {
        dataStore.edit { values ->
            values[Keys.FILE_NAME] = metadata.fileName
            values[Keys.DISPLAY_NAME] = metadata.displayName
            values[Keys.SIZE_BYTES] = metadata.sizeBytes
            values[Keys.SHA256] = metadata.sha256
            values[Keys.RUNTIME_VERSION] = metadata.runtimeVersion
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private object Keys {
        val FILE_NAME = stringPreferencesKey("model_file_name")
        val DISPLAY_NAME = stringPreferencesKey("model_display_name")
        val SIZE_BYTES = longPreferencesKey("model_size_bytes")
        val SHA256 = stringPreferencesKey("model_sha256")
        val RUNTIME_VERSION = stringPreferencesKey("model_runtime_version")
    }

    companion object {
        private const val FILE_NAME = "model-status.preferences_pb"
    }
}

fun privateModelFileStore(context: Context): ModelFileStore {
    val directory = File(context.noBackupFilesDir, "models")
    return ModelFileStore(directory) { context.noBackupFilesDir.usableSpace }
}
