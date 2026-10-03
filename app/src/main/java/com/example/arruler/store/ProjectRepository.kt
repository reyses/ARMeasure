package com.example.arruler.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Writes bytes to a temp file; replaceable so tests can simulate a failure mid-write. */
typealias FileWriter = (tmp: File, bytes: ByteArray) -> Unit

private val defaultWriter: FileWriter = { f, b ->
    FileOutputStream(f).use { out ->
        out.write(b)
        out.fd.sync()
    }
}

/**
 * Atomic write: bytes go to a sibling temp file which is then renamed over [target], so a reader
 * (or a crash) sees either the old file or the new one, never a partial file. On any failure the
 * temp file is removed and [target] is untouched.
 */
internal fun writeAtomic(target: File, bytes: ByteArray, writer: FileWriter = defaultWriter) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    try {
        writer(tmp, bytes)
        if (!tmp.renameTo(target)) {
            // Some file systems refuse to rename over an existing file.
            if (target.exists() && !target.delete()) throw IOException("cannot replace $target")
            if (!tmp.renameTo(target)) throw IOException("cannot rename $tmp")
        }
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
}

/**
 * Projects stored one file each at root/<id>.json. All mutations write to disk first and
 * publish to [projects] only on success (a write failure throws and leaves state unchanged).
 * Thread-safe; the files are small so I/O is synchronous.
 */
class ProjectRepository(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val writer: FileWriter = defaultWriter,
) {
    private val lock = Any()
    private val _projects = MutableStateFlow(load())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private fun fileOf(id: String) = File(root, "$id.json")

    private fun load(): List<Project> {
        root.listFiles { f -> f.name.endsWith(".json.tmp") }?.forEach { it.delete() }
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try { ProjectCodec.decode(f.readText()) } catch (_: Exception) { null }
        }.sortedByDescending { it.updatedAt }
    }

    fun project(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    fun createProject(name: String): Project = synchronized(lock) {
        val t = now()
        val p = Project(newId(), name.trim().ifEmpty { "My place" }, t, t)
        persist(p)
        publish(p)
        p
    }

    fun renameProject(id: String, name: String): Boolean =
        update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun deleteProject(id: String) = synchronized(lock) {
        val f = fileOf(id)
        if (f.exists() && !f.delete()) throw IOException("cannot delete $f")
        _projects.value = _projects.value.filterNot { it.id == id }
    }

    /** Appends [room]; false when the project does not exist. */
    fun addRoom(projectId: String, room: SavedRoom): Boolean =
        update(projectId) { it.copy(rooms = it.rooms + room) }

    fun renameRoom(projectId: String, roomId: String, name: String): Boolean = update(projectId) { p ->
        p.copy(rooms = p.rooms.map { if (it.id == roomId) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    fun deleteRoom(projectId: String, roomId: String): Boolean =
        update(projectId) { p -> p.copy(rooms = p.rooms.filterNot { it.id == roomId }) }

    fun newRoomId(): String = newId()

    private fun update(id: String, change: (Project) -> Project): Boolean = synchronized(lock) {
        val old = project(id) ?: return false
        val next = change(old).copy(updatedAt = now())
        persist(next)
        publish(next)
        true
    }

    private fun persist(p: Project) {
        if (!root.exists() && !root.mkdirs() && !root.exists()) throw IOException("cannot create $root")
        writeAtomic(fileOf(p.id), ProjectCodec.encode(p).toByteArray(Charsets.UTF_8), writer)
    }

    private fun publish(p: Project) {
        val list = _projects.value.filterNot { it.id == p.id } + p
        _projects.value = list.sortedByDescending { it.updatedAt }
    }
}
