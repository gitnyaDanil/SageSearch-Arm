package com.sagesearch.android.search.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first

class AndroidSearchSessionStore(
    context: Context,
    private val codec: SearchSessionCodec = SearchSessionCodec(),
) : SearchSessionStore {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(context.noBackupFilesDir, FILE_NAME) },
    )

    override suspend fun load(): StoredSearchSession? =
        dataStore.data.first()[Keys.SESSION]?.let(codec::decode)

    override suspend fun save(session: StoredSearchSession) {
        dataStore.edit { values -> values[Keys.SESSION] = codec.encode(session) }
    }

    override suspend fun clear() {
        dataStore.edit { values -> values.remove(Keys.SESSION) }
    }

    private object Keys {
        val SESSION = stringPreferencesKey("latest_search_session")
    }

    companion object {
        private const val FILE_NAME = "latest-search-session.preferences_pb"
    }
}
