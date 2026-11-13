package com.editpro.studio.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One entry of the project list on the home screen. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val modifiedAtMs: Long,
    val clipCount: Int = 0,
    val assetCount: Int = 0,
    val durationSec: Double = 0.0,
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    /** Bytes used by this project's own folder (project file + its imported media). */
    val sizeBytes: Long = 0L,
    /** A media file that can stand in as the project's poster frame, if there is one. */
    val thumbnailPath: String? = null
)

/**
 * Project-specific storage.
 *
 * Every project owns one folder under [root]:
 *
 * ```
 * <root>/<projectId>/project.filmcraft   the edit, same format as an exported project file
 * <root>/<projectId>/meta.json           name, counts and dates for the project list
 * <root>/<projectId>/media/              media imported into this project
 * <root>/<projectId>/cache/              thumbnails that can be rebuilt
 * ```
 *
 * Keeping a project's media inside its folder is what makes a project self-contained: deleting
 * the project frees exactly its own files, and duplicating it copies them. Nothing here depends
 * on Android — [root] is just a directory — so the storage rules are unit-tested on the JVM.
 */
class ProjectStore(private val root: File) {

    fun projectDir(id: String): File = File(root, safeId(id))
    fun projectFile(id: String): File = File(projectDir(id), PROJECT_FILE)
    fun mediaDir(id: String): File = File(projectDir(id), "media").apply { mkdirs() }
    fun cacheDir(id: String): File = File(projectDir(id), "cache").apply { mkdirs() }
    private fun metaFile(id: String): File = File(projectDir(id), META_FILE)

    fun exists(id: String): Boolean = isValidId(id) && projectFile(id).isFile

    /** Every stored project, most recently changed first. Unreadable folders are skipped. */
    fun list(): List<ProjectSummary> {
        val dirs = root.listFiles { f -> f.isDirectory && isValidId(f.name) } ?: return emptyList()
        return dirs.mapNotNull { dir -> runCatching { summary(dir.name) }.getOrNull() }
            .sortedByDescending { it.modifiedAtMs }
    }

    fun summary(id: String): ProjectSummary? {
        if (!exists(id)) return null
        val meta = runCatching { JSONObject(metaFile(id).readText()) }.getOrNull() ?: JSONObject()
        val file = projectFile(id)
        return ProjectSummary(
            id = id,
            name = meta.optString("name", "").ifBlank { "Untitled Project" },
            createdAtMs = meta.optLong("createdAt", file.lastModified()),
            modifiedAtMs = meta.optLong("modifiedAt", file.lastModified()),
            clipCount = meta.optInt("clips", 0),
            assetCount = meta.optInt("assets", 0),
            durationSec = meta.optDouble("duration", 0.0),
            width = meta.optInt("width", 1280),
            height = meta.optInt("height", 720),
            fps = meta.optInt("fps", 30),
            sizeBytes = folderSize(projectDir(id)),
            thumbnailPath = meta.optString("thumbnail", "").takeIf { it.isNotEmpty() && File(it).isFile }
        )
    }

    /** Creates an empty project and returns its summary. The name is made unique in the list. */
    fun create(
        name: String,
        settings: ProjectSettings = ProjectSettings(),
        nowMs: Long = System.currentTimeMillis()
    ): ProjectSummary {
        val id = newId()
        val unique = uniqueName(name)
        val json = ProjectSerializer.serializeProject(
            name = unique,
            settings = settings,
            clips = emptyList(),
            markers = emptyList(),
            assets = emptyList()
        )
        write(id, json, unique, 0, 0, 0.0, settings, null, createdAtMs = nowMs, nowMs = nowMs)
        return summary(id)!!
    }

    /**
     * Writes the project file and refreshes its list entry. The file is written to a temporary
     * name and renamed into place, so a crash mid-save cannot leave a truncated project behind.
     */
    fun save(
        id: String,
        projectJson: String,
        name: String,
        clipCount: Int,
        assetCount: Int,
        durationSec: Double,
        settings: ProjectSettings,
        thumbnailPath: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val created = runCatching { JSONObject(metaFile(id).readText()).optLong("createdAt", nowMs) }
            .getOrDefault(nowMs)
        write(id, projectJson, name, clipCount, assetCount, durationSec, settings, thumbnailPath, created, nowMs)
    }

    private fun write(
        id: String,
        projectJson: String,
        name: String,
        clipCount: Int,
        assetCount: Int,
        durationSec: Double,
        settings: ProjectSettings,
        thumbnailPath: String?,
        createdAtMs: Long,
        nowMs: Long
    ) {
        val dir = projectDir(id).apply { mkdirs() }
        atomicWrite(File(dir, PROJECT_FILE), projectJson)
        val meta = JSONObject()
            .put("name", name.ifBlank { "Untitled Project" })
            .put("createdAt", createdAtMs)
            .put("modifiedAt", nowMs)
            .put("clips", clipCount)
            .put("assets", assetCount)
            .put("duration", durationSec)
            .put("width", settings.width)
            .put("height", settings.height)
            .put("fps", settings.fps)
        if (thumbnailPath != null) meta.put("thumbnail", thumbnailPath)
        atomicWrite(File(dir, META_FILE), meta.toString())
    }

    fun load(id: String): String? =
        if (exists(id)) runCatching { projectFile(id).readText() }.getOrNull() else null

    /** Renames a project in both the list entry and the project file itself. */
    fun rename(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty() || !exists(id)) return false
        return runCatching {
            val doc = JSONObject(projectFile(id).readText()).put("name", clean)
            atomicWrite(projectFile(id), doc.toString(2))
            val meta = runCatching { JSONObject(metaFile(id).readText()) }.getOrNull() ?: JSONObject()
            atomicWrite(metaFile(id), meta.put("name", clean).toString())
            true
        }.getOrDefault(false)
    }

    /**
     * Copies a project with its media. Paths inside the copy are re-pointed at the copy's own
     * folder, so the two projects share nothing and either can be deleted safely.
     */
    fun duplicate(id: String, nowMs: Long = System.currentTimeMillis()): ProjectSummary? {
        if (!exists(id)) return null
        val source = summary(id) ?: return null
        val copyId = newId()
        val from = projectDir(id)
        val to = projectDir(copyId)
        return runCatching {
            from.copyRecursively(to, overwrite = true)
            val name = uniqueName("${source.name} copy")
            val rewritten = rewritePaths(projectFile(copyId).readText(), from.absolutePath, to.absolutePath, name)
            atomicWrite(projectFile(copyId), rewritten)
            val meta = (runCatching { JSONObject(metaFile(copyId).readText()) }.getOrNull() ?: JSONObject())
                .put("name", name)
                .put("createdAt", nowMs)
                .put("modifiedAt", nowMs)
            val thumb = meta.optString("thumbnail", "")
            if (thumb.startsWith(from.absolutePath)) {
                meta.put("thumbnail", to.absolutePath + thumb.removePrefix(from.absolutePath))
            }
            atomicWrite(metaFile(copyId), meta.toString())
            summary(copyId)
        }.getOrElse {
            to.deleteRecursively()
            null
        }
    }

    /** Deletes the project folder, including the media imported into it. */
    fun delete(id: String): Boolean {
        if (!isValidId(id)) return false
        val dir = projectDir(id)
        return dir.exists() && dir.deleteRecursively()
    }

    /** "Name", "Name 2", "Name 3"… — the first that no stored project already uses. */
    fun uniqueName(base: String): String {
        val clean = base.trim().ifEmpty { "Untitled Project" }
        val taken = list().map { it.name.lowercase() }.toSet()
        if (clean.lowercase() !in taken) return clean
        var n = 2
        while ("$clean $n".lowercase() in taken) n++
        return "$clean $n"
    }

    /** True when [path] points inside the folder of project [id]. */
    fun owns(id: String, path: String?): Boolean {
        if (path.isNullOrEmpty() || !isValidId(id)) return false
        return File(path).absolutePath.startsWith(projectDir(id).absolutePath + File.separator)
    }

    /**
     * Takes ownership of a media file: moves it into the project's `media/` folder (or, for
     * [cacheGroup], into `cache/<cacheGroup>/`) and returns the path to use from now on. A file
     * that is already inside the project, or that does not exist, is returned unchanged.
     */
    fun adopt(id: String, path: String?, cacheGroup: String? = null): String? {
        if (path.isNullOrEmpty() || !isValidId(id)) return path
        val source = File(path)
        if (!source.isFile || owns(id, path)) return path
        val destDir = if (cacheGroup == null) mediaDir(id) else File(cacheDir(id), cacheGroup).apply { mkdirs() }
        var target = File(destDir, source.name)
        var n = 2
        while (target.exists()) {
            target = File(destDir, "${source.nameWithoutExtension}_$n" + source.extension.let { if (it.isEmpty()) "" else ".$it" })
            n++
        }
        return runCatching {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            target.absolutePath
        }.getOrDefault(path)
    }

    /** Adopts an asset's media file and its filmstrip thumbnails into project [id]. */
    fun adoptAsset(id: String, asset: MediaAsset): MediaAsset {
        if (!isValidId(id)) return asset
        val local = adopt(id, asset.localPath)
        val strip = asset.filmstripPaths.map { adopt(id, it, "filmstrips/${asset.id}") ?: it }
        return if (local == asset.localPath && strip == asset.filmstripPaths) asset
        else asset.copy(localPath = local, filmstripPaths = strip)
    }

    /**
     * Deletes files in the project's `media/` and `cache/` folders that nothing in [keepPaths]
     * refers to — what is left behind when media is removed from the library. Returns the number
     * of files deleted. Only ever touches files inside the project's own folder.
     */
    fun pruneMedia(id: String, keepPaths: Set<String>): Int {
        if (!isValidId(id) || !projectDir(id).isDirectory) return 0
        val keep = keepPaths.map { File(it).absolutePath }.toSet()
        var removed = 0
        for (dir in listOf(File(projectDir(id), "media"), File(projectDir(id), "cache"))) {
            if (!dir.isDirectory) continue
            dir.walkBottomUp().forEach { f ->
                if (f.isFile && f.absolutePath !in keep) {
                    if (f.delete()) removed++
                } else if (f.isDirectory && f != dir && f.list()?.isEmpty() == true) {
                    f.delete()
                }
            }
        }
        return removed
    }

    /**
     * Stores a project file from outside (an exported `.filmcraft`) as a new project. Media the
     * file points to that still exists on this device is copied in, so the new project does not
     * depend on wherever the file came from. Returns null when the file cannot be read.
     */
    fun importDocument(projectJson: String, nowMs: Long = System.currentTimeMillis()): ProjectSummary? {
        val doc = runCatching { ProjectSerializer.deserializeProject(projectJson) }.getOrNull() ?: return null
        val id = newId()
        return runCatching {
            val assets = doc.assets.map { asset ->
                fun copyIn(path: String?, destDir: File): String? {
                    if (path.isNullOrEmpty()) return path
                    val source = File(path)
                    if (!source.isFile) return path
                    val target = File(destDir.apply { mkdirs() }, source.name)
                    source.copyTo(target, overwrite = true)
                    return target.absolutePath
                }
                asset.copy(
                    localPath = copyIn(asset.localPath, mediaDir(id)),
                    filmstripPaths = asset.filmstripPaths.map {
                        copyIn(it, File(cacheDir(id), "filmstrips/${asset.id}")) ?: it
                    }
                )
            }
            val name = uniqueName(doc.name)
            val json = ProjectSerializer.serializeProject(name, doc.settings, doc.clips, doc.markers, assets, doc.trackStates)
            val thumbnail = assets.firstNotNullOfOrNull { a ->
                when (a.kind) {
                    AssetKind.IMAGE -> a.localPath
                    AssetKind.VIDEO -> a.filmstripPaths.firstOrNull()
                    else -> null
                }
            }
            write(
                id, json, name, doc.clips.size, assets.size,
                TimelineEngine.calcProjectDuration(doc.clips, assets.associateBy { it.id }),
                doc.settings, thumbnail, createdAtMs = nowMs, nowMs = nowMs
            )
            summary(id)
        }.getOrElse {
            projectDir(id).deleteRecursively()
            null
        }
    }

    private fun newId(): String {
        var id = generateUid()
        while (projectDir(id).exists()) id = generateUid()
        return id
    }

    private fun safeId(id: String): String {
        require(isValidId(id)) { "Invalid project id" }
        return id
    }

    companion object {
        const val PROJECT_FILE = "project.filmcraft"
        const val META_FILE = "meta.json"

        /** Ids become folder names, so anything that could escape the store is rejected. */
        fun isValidId(id: String): Boolean = id.isNotEmpty() && id.length <= 64 &&
            id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

        fun folderSize(dir: File): Long =
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        private fun atomicWrite(target: File, text: String) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                // Some file systems refuse to rename over an existing file.
                target.delete()
                if (!tmp.renameTo(target)) {
                    target.writeText(text)
                    tmp.delete()
                }
            }
        }

        /**
         * Re-points every media path under [oldPrefix] to [newPrefix] and optionally renames the
         * project. Works on the parsed document, because the serialised text escapes slashes.
         */
        fun rewritePaths(projectJson: String, oldPrefix: String, newPrefix: String, newName: String? = null): String {
            val doc = JSONObject(projectJson)
            if (newName != null) doc.put("name", newName)
            fun fix(value: String): String =
                if (value.startsWith(oldPrefix)) newPrefix + value.removePrefix(oldPrefix) else value
            val assets = doc.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val local = asset.optString("localPath", "")
                if (local.isNotEmpty()) asset.put("localPath", fix(local))
                val strip = asset.optJSONArray("filmstrip")
                if (strip != null) {
                    val fixed = JSONArray()
                    for (j in 0 until strip.length()) fixed.put(fix(strip.optString(j, "")))
                    asset.put("filmstrip", fixed)
                }
            }
            return doc.toString(2)
        }
    }
}

/** Compact running time for a project card: "0:42", "12:05", "1:02:30". */
fun formatDurationShort(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.coerceAtLeast(0.0).toLong() else 0L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * How long ago something happened, in words. Anything older than a week is given as a date by
 * [olderThanAWeek], because "23 days ago" is harder to place than a date.
 */
fun formatRelativeTime(nowMs: Long, thenMs: Long, olderThanAWeek: (Long) -> String = { "" }): String {
    val diff = (nowMs - thenMs).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        hours < 24L -> "$hours h ago"
        days == 1L -> "yesterday"
        days < 7L -> "$days days ago"
        else -> olderThanAWeek(thenMs).ifEmpty { "$days days ago" }
    }
}
