package app.pulse.core

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("pulse_settings")
// Device-local flags, kept out of backups (backup_rules.xml): a restored "already asked" would stop a new device
// from ever asking.
private val Context.localDataStore by preferencesDataStore("pulse_local")

@Immutable
data class AppSettings(
    val defaultVideo: Boolean = true,        // default download = MP4 video (else M4A)
    val wifiOnly: Boolean = false,           // off unless the user turns it on — downloads use any network by default
    val preferVideoStreaming: Boolean = false,
    val maxConcurrent: Int = 3,
    val downloadRelPath: String? = null,     // preset folder in shared storage ("Download", "Music/Emma", …); null = Download/Emma
    val downloadTreeUri: String? = null,     // any other folder, picked with the system picker (a SAF tree uri); wins over the preset
    val downloadFolderLabel: String? = null, // that folder's readable name, for the Settings row
)

object SettingsStore {
    private val K_VIDEO = booleanPreferencesKey("default_video")
    private val K_WIFI = booleanPreferencesKey("wifi_only")
    private val K_PREFER_VIDEO = booleanPreferencesKey("prefer_video")
    private val K_MAX = intPreferencesKey("max_concurrent")
    private val K_TREE = stringPreferencesKey("download_tree")
    private val K_TREE_LABEL = stringPreferencesKey("download_tree_label")
    private val K_REL = stringPreferencesKey("download_rel_path")

    fun flow(context: Context): Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            defaultVideo = p[K_VIDEO] ?: true,
            wifiOnly = p[K_WIFI] ?: false,
            preferVideoStreaming = p[K_PREFER_VIDEO] ?: false,
            maxConcurrent = p[K_MAX] ?: 3,
            downloadRelPath = p[K_REL],
            downloadTreeUri = p[K_TREE],
            downloadFolderLabel = p[K_TREE_LABEL],
        )
    }

    suspend fun setVideo(context: Context, v: Boolean) = context.settingsDataStore.edit { it[K_VIDEO] = v }
    suspend fun setWifiOnly(context: Context, v: Boolean) = context.settingsDataStore.edit { it[K_WIFI] = v }
    suspend fun setPreferVideo(context: Context, v: Boolean) = context.settingsDataStore.edit { it[K_PREFER_VIDEO] = v }
    suspend fun setMaxConcurrent(context: Context, v: Int) = context.settingsDataStore.edit { it[K_MAX] = v }

    /** Where finished downloads go: one of the preset shared-storage folders. Clears any picked folder. */
    suspend fun setDownloadPreset(context: Context, relPath: String) = context.settingsDataStore.edit {
        it[K_REL] = relPath
        it.remove(K_TREE)
        it.remove(K_TREE_LABEL)
    }

    /** Where finished downloads go: a folder picked with the system picker (tree uri + label), or null to drop it. */
    suspend fun setDownloadFolder(context: Context, treeUri: String?, label: String?) = context.settingsDataStore.edit {
        if (treeUri == null) {
            it.remove(K_TREE)
            it.remove(K_TREE_LABEL)
        } else {
            it[K_TREE] = treeUri
            it[K_TREE_LABEL] = label ?: treeUri
        }
    }
}

/** One-time onboarding flag. */
object OnboardingStore {
    private val K_DONE = booleanPreferencesKey("onboarding_done")

    @Volatile
    var done: Boolean = false
        private set

    suspend fun load(context: Context) {
        done = context.settingsDataStore.data.map { it[K_DONE] ?: false }.first()
    }

    suspend fun setDone(context: Context) {
        done = true
        context.settingsDataStore.edit { it[K_DONE] = true }
    }
}

/** Whether the pre-Android-10 storage-permission prompt was already shown — asked once ever, not every launch. */
object StoragePromptStore {
    private val K_ASKED = booleanPreferencesKey("asked_write_storage")

    suspend fun asked(context: Context): Boolean = context.localDataStore.data.map { it[K_ASKED] ?: false }.first()

    suspend fun setAsked(context: Context) {
        context.localDataStore.edit { it[K_ASKED] = true }
    }
}
