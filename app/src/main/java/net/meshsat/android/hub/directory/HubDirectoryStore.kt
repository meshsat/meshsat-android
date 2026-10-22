package net.meshsat.android.hub.directory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The directory as one JSON file, written whole to a temporary file and moved over the old one,
 * so a crash leaves the last state or the new one, never half of each. A file and not Room: the
 * database has no migrations (fallbackToDestructiveMigration), and an upgrade must not drop the
 * pinned key. [MESHSAT-1642]
 */
class HubDirectoryStore(private val file: File) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<DirectoryState> = _state.asStateFlow()

    private fun load(): DirectoryState = try {
        if (file.exists()) DirectoryState.fromJson(JSONObject(file.readText())) else DirectoryState()
    } catch (e: Exception) {
        DirectoryState()
    }

    val current: DirectoryState get() = _state.value

    @Synchronized
    fun save(s: DirectoryState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(s.toJson().toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _state.value = s
    }

    /** Apply [f] to the current state and save what it returns, as one step. */
    @Synchronized
    fun update(f: (DirectoryState) -> DirectoryState): DirectoryState {
        val next = f(_state.value)
        if (next != _state.value) save(next)
        return next
    }

    companion object {
        @Volatile private var instance: HubDirectoryStore? = null

        fun get(context: Context): HubDirectoryStore = instance ?: synchronized(this) {
            instance ?: HubDirectoryStore(File(context.applicationContext.filesDir, "hub_directory.json")).also { instance = it }
        }
    }
}
