package app.pulse.core

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("pulse_settings")

@Immutable
data class AppSettings(
    val defaultVideo: Boolean = true,        // default download = MP4 video (else M4A)
    val wifiOnly: Boolean = true,
    val preferVideoStreaming: Boolean = false,
    val maxConcurrent: Int = 3,
)

object SettingsStore {
    private val K_VIDEO = booleanPreferencesKey("default_video")
    private val K_WIFI = booleanPreferencesKey("wifi_only")
    private val K_PREFER_VIDEO = booleanPreferencesKey("prefer_video")
    private val K_MAX = intPreferencesKey("max_concurrent")

    fun flow(context: Context): Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            defaultVideo = p[K_VIDEO] ?: true,
            wifiOnly = p[K_WIFI] ?: true,
            preferVideoStreaming = p[K_PREFER_VIDEO] ?: false,
            maxConcurrent = p[K_MAX] ?: 3,
        )
    }

    suspend fun setVideo(context: Context, v: Boolean) = context.settingsDataStore.edit { it[K_VIDEO] = v }
    suspend fun setWifiOnly(context: Context, v: Boolean) = context.settingsDataStore.edit { it[K_WIFI] = v }
    suspend fun setPreferVideo(context: Context, v: Boolean) = context.settingsDataStore.edit { it[K_PREFER_VIDEO] = v }
    suspend fun setMaxConcurrent(context: Context, v: Int) = context.settingsDataStore.edit { it[K_MAX] = v }
}
