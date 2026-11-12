package com.editpro.studio.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.editpro.studio.media.GalleryPublisher
import com.editpro.studio.media.MediaEngine
import com.editpro.studio.media.NativeVideoExporter
import com.editpro.studio.media.PreviewAudioEngine
import com.editpro.studio.agent.AgentEngine
import com.editpro.studio.agent.OpenAiClient
import com.editpro.studio.agent.SecureStore
import com.editpro.studio.mcp.McpServer
import com.editpro.studio.mcp.McpService
import com.editpro.studio.mcp.McpTools
import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.AudioGearPreset
import com.editpro.studio.model.CameraRig
import com.editpro.studio.model.ClipFilters
import com.editpro.studio.model.ClipMenuAction
import com.editpro.studio.model.EditorShortcut
import com.editpro.studio.model.EditorSnapshot
import com.editpro.studio.model.EquipmentRigPreset
import com.editpro.studio.model.ExportSettings
import com.editpro.studio.model.InspectorTab
import com.editpro.studio.model.Keyframe
import com.editpro.studio.model.KeyframeOps
import com.editpro.studio.model.KeyframePatch
import com.editpro.studio.model.LookPreset
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.MgTemplate
import com.editpro.studio.model.MotionGraphics
import com.editpro.studio.model.MotionKeyframeStep
import com.editpro.studio.model.PresetCatalog
import com.editpro.studio.model.ProjectSerializer
import com.editpro.studio.model.ProjectSettings
import com.editpro.studio.model.ProjectStore
import com.editpro.studio.model.ProjectSummary
import com.editpro.studio.model.RenderJob
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.ScopesMode
import com.editpro.studio.model.ShortcutBindings
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineMarker
import com.editpro.studio.model.TimelineOps
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import com.editpro.studio.model.VolumePoint
import com.editpro.studio.model.WorkspaceMode
import com.editpro.studio.model.formatFileSize
import com.editpro.studio.model.formatTimecode
import com.editpro.studio.model.generateUid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A destructive action that asks before it runs. Held as data (never a lambda) so it survives
 * recomposition cleanly. Starting, opening and duplicating projects no longer ask: every project
 * is stored separately, so none of them can discard work.
 */
sealed interface PendingConfirm {
    data class DeleteProject(val id: String, val name: String) : PendingConfirm
    data class DeleteAssets(val ids: Set<String>, val clipCount: Int) : PendingConfirm
    object ResetShortcuts : PendingConfirm
}

/** The three panels around the preview that can be collapsed out of the way. */
enum class WorkspacePanel { LIBRARY, INSPECTOR, TIMELINE }

/** One line in the in-app agent transcript. [role] is user, assistant, tool, toolResult or error. */
data class AgentMessage(val role: String, val text: String)

data class EditorUiState(
    val projectName: String = "Untitled Project",
    val saveStatus: String = "Local project · ready",
    val projectSettings: ProjectSettings = ProjectSettings(),
    val assets: List<MediaAsset> = emptyList(),
    val clips: List<TimelineClip> = emptyList(),
    val markers: List<TimelineMarker> = emptyList(),
    val trackStates: Map<TimelineTrack, TrackState> = TimelineTrack.entries.associateWith { TrackState() },
    val selectedAssetId: String? = null,
    val selectedClipId: String? = null,
    val selectedClipIds: Set<String> = emptySet(),
    val mediaFilter: String = "all", // all, video, audio, image
    val activeBin: String = "all",
    val customBins: List<String> = listOf("Imported", "Favorites", "Music", "Titles & Graphics", "B-roll"),
    val assetSearchQuery: String = "",
    val playheadSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val isPlaying: Boolean = false,
    val workspaceMode: WorkspaceMode = WorkspaceMode.EDIT,
    val activeInspectorTab: InspectorTab = InspectorTab.CLIP,
    val fxPresetSearch: String = "",
    val fxPresetCategory: String = "all",
    val timelineZoomPxPerSec: Double = 75.0,
    val timelineAutoFit: Boolean = true,
    val timelineCompact: Boolean = false,
    val timelineFollowPlayhead: Boolean = true,
    val magneticSnap: Boolean = true,
    val safeGuidesOn: Boolean = false,
    val checkerboardOn: Boolean = false,
    val gradeSplitOn: Boolean = false,
    val gradeSplitPercent: Float = 50f,
    val scopesForceOn: Boolean = false,
    val scopesMode: ScopesMode = ScopesMode.PARADE,
    val isLightTheme: Boolean = false,
    val uiZoom: Float = 1.0f,
    val leftPanelWidthDp: Float = 250f,
    val rightPanelWidthDp: Float = 285f,
    val timelineHeightDp: Float = 278f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val copiedClipTemplate: TimelineClip? = null,
    val customStylePresets: Map<String, ClipFilters> = emptyMap(),
    val customMotionPresets: Map<String, List<MotionKeyframeStep>> = emptyMap(),
    val customEquipmentRigs: Map<String, EquipmentRigPreset> = emptyMap(),
    val customAudioGear: Map<String, AudioGearPreset> = emptyMap(),
    /** The effective key map: defaults plus the user's own bindings. */
    val bindings: ShortcutBindings = ShortcutBindings(),
    val exportSettings: ExportSettings = ExportSettings(),
    val renderQueue: List<RenderJob> = emptyList(),
    val isExporting: Boolean = false,
    val exportProgressPct: Int = 0,
    val exportStatusText: String = "Android Hardware Turbo encoder ready · zero external dependencies.",
    val lastExportedFilePath: String? = null,
    // Modals
    val showExportModal: Boolean = false,
    val showToolsModal: Boolean = false,
    val showSolidModal: Boolean = false,
    val showPresetsModal: Boolean = false,
    val showShortcutsModal: Boolean = false,
    val alertTitle: String? = null,
    val alertMessage: String? = null,
    val toastMessage: String? = null,
    val pendingConfirm: PendingConfirm? = null,
    val showHome: Boolean = true,
    /** Every project stored on this device, most recently changed first. */
    val projects: List<ProjectSummary> = emptyList(),
    /** The project open in the editor; null on a fresh install before anything is created. */
    val currentProjectId: String? = null,
    /** Library items picked for a bulk action (Ctrl / Shift click, or long-press on touch). */
    val selectedAssetIds: Set<String> = emptySet(),
    /** Clips held by Copy / Cut, with their original timing so Paste keeps their spacing. */
    val clipClipboard: List<TimelineClip> = emptyList(),
    /** Touch equivalent of holding Ctrl: every tap adds to or removes from the selection. */
    val selectionMode: Boolean = false,
    val showKeyframeLane: Boolean = false,
    val libraryCollapsed: Boolean = false,
    val inspectorCollapsed: Boolean = false,
    val timelineCollapsed: Boolean = false,
    val previewMaximized: Boolean = false,
    val showGraphicsModal: Boolean = false,
    val showNewProjectModal: Boolean = false,
    /** Playback speed and direction for J / K / L shuttling: ±1, ±2, ±4 or ±8. */
    val shuttleRate: Double = 1.0,
    /** Ordered ids of the clip context-menu entries the user has enabled. */
    val clipMenuOrder: List<String> = ClipMenuAction.defaultOrder,
    val showClipMenuModal: Boolean = false,
    /** When on, moving the mouse over the timeline moves the playhead (Alt+F). */
    val scrubFollow: Boolean = false,

    // Agent access (MCP). Off until the user starts it.
    val mcpRunning: Boolean = false,
    val mcpPort: Int = 8765,
    val mcpToken: String = "",
    val mcpLog: List<String> = emptyList(),
    /**
     * MCP is opt-in: the built-in assistant is the normal path, and anyone exposing a socket is
     * responsible for running and securing it.
     */
    val mcpAutoStart: Boolean = false,

    // Built-in agent (bring your own key)
    val agentBaseUrl: String = "https://api.openai.com/v1",
    val agentModel: String = "gpt-4o-mini",
    val agentHasKey: Boolean = false,
    val agentRunning: Boolean = false,
    val agentStatus: String = "",
    val agentMessages: List<AgentMessage> = emptyList(),
    val showAgentChat: Boolean = false
) {
    val assetsById: Map<String, MediaAsset>
        get() = assets.associateBy { it.id }

    val resolvedClips: List<ResolvedClip>
        get() = TimelineEngine.starts(clips, assetsById)

    val selectedClip: TimelineClip?
        get() = clips.find { it.id == selectedClipId }

    val selectedAsset: MediaAsset?
        get() = assets.find { it.id == selectedAssetId }

    /** Selected clips, falling back to the primary one when the set is empty. */
    val selectionIds: Set<String>
        get() = if (selectedClipIds.isNotEmpty()) selectedClipIds else setOfNotNull(selectedClipId)

    val currentProject: ProjectSummary?
        get() = projects.find { it.id == currentProjectId }

    /** Clip-local time of the playhead inside the selected clip, or null when it is elsewhere. */
    val selectedClipLocalTime: Double?
        get() {
            val id = selectedClipId ?: return null
            val rc = resolvedClips.find { it.clip.id == id } ?: return null
            return if (playheadSec < rc.start - 1e-6 || playheadSec > rc.end + 1e-6) null
            else (playheadSec - rc.start).coerceIn(0.0, rc.duration)
        }

    /**
     * The selected clip as the inspector should show it. Once a clip has keyframes the renderer
     * reads the keyframes rather than the clip's base values, so the sliders show the animated
     * values under the playhead — what is actually on screen.
     */
    val inspectedClip: TimelineClip?
        get() {
            val c = selectedClip ?: return null
            if (c.keyframes.isEmpty()) return c
            val rc = resolvedClips.find { it.clip.id == c.id } ?: return c
            return shownAt(c, (playheadSec - rc.start).coerceIn(0.0, rc.duration))
        }

    val allEquipmentRigs: Map<String, EquipmentRigPreset>
        get() = PresetCatalog.builtinEquipmentRigs + customEquipmentRigs

    val allAudioGear: Map<String, AudioGearPreset>
        get() = PresetCatalog.builtinAudioGear + customAudioGear

    companion object {
        /** [clip] with its transform and grade replaced by their animated values at [localT]. */
        fun shownAt(clip: TimelineClip, localT: Double): TimelineClip {
            val a = TimelineEngine.animatedProps(clip, localT)
            return clip.copy(
                scale = a.scale, opacity = a.opacity, rotation = a.rotation, x = a.x, y = a.y, filters = a.filters
            )
        }
    }
}

class EditProViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("editpro_studio_prefs", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val projectStore = ProjectStore(File(application.filesDir, "projects"))
    private val saveLock = Any()
    private val history = ArrayDeque<EditorSnapshot>()
    private val future = ArrayDeque<EditorSnapshot>()
    private var playbackJob: Job? = null
    private var autosaveJob: Job? = null
    private var exportJob: Job? = null
    private val previewAudio = PreviewAudioEngine()
    private var mcpServer: McpServer? = null
    private var agentJob: Job? = null
    private val agentEngine by lazy { AgentEngine(McpTools(this)) }

    init {
        loadPersistedPreferences()
        loadShortcutBindings()
        loadClipMenu()
        loadMcpToken()
        loadAgentSettings()
        migrateLegacyAutosave()
        refreshProjects()
    }

    private fun loadPersistedPreferences() {
        val snapOn = prefs.getBoolean("magnetic_snap", true)
        val autoFit = prefs.getBoolean("timeline_autofit", true)
        val compact = prefs.getBoolean("timeline_compact", false)
        val follow = prefs.getBoolean("follow_playhead", true)
        val scrub = prefs.getBoolean("scrub_follow", false)
        val light = prefs.getBoolean("light_theme", false)
        val modeId = prefs.getString("workspace_mode", "edit") ?: "edit"
        val mode = WorkspaceMode.entries.find { it.id == modeId } ?: WorkspaceMode.EDIT
        _uiState.update {
            it.copy(
                magneticSnap = snapOn,
                timelineAutoFit = autoFit,
                timelineCompact = compact,
                timelineFollowPlayhead = follow,
                scrubFollow = scrub,
                isLightTheme = light,
                workspaceMode = mode,
                activeInspectorTab = mode.defaultInspectorTab(),
                libraryCollapsed = prefs.getBoolean("panel_library_collapsed", false),
                inspectorCollapsed = prefs.getBoolean("panel_inspector_collapsed", false),
                timelineCollapsed = prefs.getBoolean("panel_timeline_collapsed", false),
                showKeyframeLane = prefs.getBoolean("keyframe_lane", false)
            )
        }
    }

    // -------------------------------------------------------------------------
    // PROJECT STORAGE
    // Every project owns a folder (see ProjectStore): its edit, its imported media and its
    // thumbnails. The editor always works on exactly one of them and saves into it.
    // -------------------------------------------------------------------------

    /**
     * Versions before 3.0 kept one autosaved edit in preferences and all media in a shared
     * folder. That edit becomes the first stored project, with its media moved into the
     * project's own folder, so nothing is lost by the upgrade.
     */
    private fun migrateLegacyAutosave() {
        val raw = prefs.getString("filmcraft_autosave", null) ?: return
        try {
            val doc = ProjectSerializer.deserializeProject(raw)
            if (doc.clips.isNotEmpty() || doc.assets.isNotEmpty()) {
                val created = projectStore.create(doc.name, doc.settings)
                val assets = doc.assets.map { projectStore.adoptAsset(created.id, it) }
                val json = ProjectSerializer.serializeProject(created.name, doc.settings, doc.clips, doc.markers, assets)
                projectStore.save(
                    created.id, json, created.name, doc.clips.size, assets.size,
                    TimelineEngine.calcProjectDuration(doc.clips, assets.associateBy { it.id }),
                    doc.settings, thumbnailFor(assets, doc.clips)
                )
            }
            prefs.edit().remove("filmcraft_autosave").remove("recent_projects").apply()
        } catch (_: Exception) {
            // An unreadable autosave is left in place rather than deleted; it is simply not migrated.
        }
    }

    private fun refreshProjects() {
        val list = runCatching { projectStore.list() }.getOrDefault(emptyList())
        _uiState.update { it.copy(projects = list) }
    }

    private fun thumbnailFor(assets: List<MediaAsset>, clips: List<TimelineClip>): String? {
        fun pathOf(a: MediaAsset): String? = when (a.kind) {
            AssetKind.IMAGE -> a.localPath
            AssetKind.VIDEO -> a.filmstripPaths.firstOrNull()
            else -> null
        }
        val byId = assets.associateBy { it.id }
        // Prefer what the edit opens on; fall back to anything in the library.
        return clips.filter { it.track.isVisual }
            .sortedWith(compareBy({ it.startAt }, { it.track.ordinal }))
            .firstNotNullOfOrNull { c -> c.assetId?.let { byId[it] }?.let { pathOf(it) } }
            ?: assets.firstNotNullOfOrNull { pathOf(it) }
    }

    /**
     * The project the editor saves into. Normally one is already open; if edits arrive with none
     * (the assistant adding media from the home screen, say) a project is created for them.
     */
    private fun ensureProjectId(force: Boolean = false): String? {
        val s = _uiState.value
        s.currentProjectId?.let { return it }
        if (!force && s.clips.isEmpty() && s.assets.isEmpty()) return null
        val created = runCatching { projectStore.create(s.projectName, s.projectSettings) }.getOrNull() ?: return null
        _uiState.update { it.copy(currentProjectId = created.id, projectName = created.name) }
        prefs.edit().putString("last_project_id", created.id).apply()
        return created.id
    }

    /** The part of the editor state that is written to the project file. */
    private data class SavedContent(
        val projectId: String?,
        val name: String,
        val settings: ProjectSettings,
        val clips: List<TimelineClip>,
        val markers: List<TimelineMarker>,
        val assets: List<MediaAsset>,
        val trackStates: Map<TimelineTrack, TrackState>
    )

    private fun contentOf(s: EditorUiState) = SavedContent(
        s.currentProjectId, s.projectName, s.projectSettings, s.clips, s.markers, s.assets, s.trackStates
    )

    /**
     * What the project file on disk holds. A save is skipped when the editor still matches it, so
     * merely opening and closing a project neither rewrites it nor moves it up the project list.
     */
    @Volatile
    private var savedContent: SavedContent? = null

    private fun writeProject(id: String, s: EditorUiState) {
        synchronized(saveLock) {
            runCatching {
                val json = ProjectSerializer.serializeProject(
                    name = s.projectName,
                    settings = s.projectSettings,
                    clips = s.clips,
                    markers = s.markers,
                    assets = s.assets,
                    trackStates = s.trackStates
                )
                projectStore.save(
                    id, json, s.projectName, s.clips.size, s.assets.size, s.durationSec,
                    s.projectSettings, thumbnailFor(s.assets, s.clips)
                )
                savedContent = contentOf(s).copy(projectId = id)
            }
        }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(900L)
            val id = ensureProjectId() ?: return@launch
            // State and id are read together, so a save can never land in a different project.
            val snapshot = _uiState.value
            if (snapshot.currentProjectId != id) return@launch
            if (contentOf(snapshot) == savedContent) return@launch
            withContext(Dispatchers.IO) { writeProject(id, snapshot) }
            _uiState.update { if (it.currentProjectId == id) it.copy(saveStatus = "All changes saved") else it }
        }
    }

    /** Writes any pending changes immediately — before leaving a project, and for Ctrl+S. */
    private fun flushSave(): Boolean {
        autosaveJob?.cancel()
        autosaveJob = null
        val id = ensureProjectId() ?: return false
        val current = _uiState.value
        if (contentOf(current) != savedContent) writeProject(id, current)
        return true
    }

    /** Writes pending changes without a message — called when the app leaves the foreground. */
    fun saveProjectQuietly() {
        if (flushSave()) _uiState.update { it.copy(saveStatus = "All changes saved") }
    }

    fun saveProjectNow() {
        if (flushSave()) {
            _uiState.update { it.copy(saveStatus = "All changes saved") }
            refreshProjects()
            showToast("Project saved")
        } else {
            showToast("Nothing to save yet")
        }
    }

    fun showToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
        viewModelScope.launch {
            delay(3000L)
            _uiState.update { if (it.toastMessage == msg) it.copy(toastMessage = null) else it }
        }
    }

    fun showAlert(title: String, message: String) {
        _uiState.update { it.copy(alertTitle = title, alertMessage = message) }
    }

    fun dismissAlert() {
        _uiState.update { it.copy(alertTitle = null, alertMessage = null) }
    }

    // -------------------------------------------------------------------------
    // UNDO / REDO SNAPSHOT ENGINE (60 STEPS)
    // -------------------------------------------------------------------------
    private fun currentSnapshot(): EditorSnapshot {
        val s = _uiState.value
        return EditorSnapshot(
            clips = s.clips,
            markers = s.markers,
            playhead = s.playheadSec,
            selectedClipId = s.selectedClipId,
            projectName = s.projectName,
            duration = s.durationSec,
            settings = s.projectSettings,
            assets = s.assets,
            selectedClipIds = s.selectedClipIds
        )
    }

    fun snapHistory() {
        history.addLast(currentSnapshot())
        if (history.size > 60) history.removeFirst()
        future.clear()
        _uiState.update { it.copy(canUndo = history.isNotEmpty(), canRedo = false) }
    }

    fun undo() {
        if (history.isEmpty()) return
        future.addLast(currentSnapshot())
        val prev = history.removeLast()
        restoreSnapshot(prev)
        showToast("Undo")
    }

    fun redo() {
        if (future.isEmpty()) return
        history.addLast(currentSnapshot())
        val next = future.removeLast()
        restoreSnapshot(next)
        showToast("Redo")
    }

    private fun restoreSnapshot(snap: EditorSnapshot) {
        _uiState.update { cur ->
            // The library is part of the snapshot, so undoing an import or a removal restores
            // the clips *and* their media. Favourite / bin changes are not edits and are kept.
            val live = cur.assets.associateBy { it.id }
            val assets = snap.assets.map { a -> live[a.id]?.let { a.copy(favorite = it.favorite, bin = it.bin) } ?: a }
            val dur = TimelineEngine.calcProjectDuration(snap.clips, assets.associateBy { it.id })
            val ids = snap.clips.map { it.id }.toSet()
            val selected = snap.selectedClipIds.filter { it in ids }.toSet()
            val primary = snap.selectedClipId?.takeIf { it in ids }
            cur.copy(
                clips = snap.clips,
                markers = snap.markers,
                assets = assets,
                selectedAssetId = cur.selectedAssetId?.takeIf { id -> assets.any { it.id == id } },
                selectedAssetIds = cur.selectedAssetIds.filter { id -> assets.any { it.id == id } }.toSet(),
                playheadSec = min(snap.playhead, dur),
                selectedClipId = primary,
                selectedClipIds = if (selected.isNotEmpty()) selected else setOfNotNull(primary),
                projectName = snap.projectName,
                durationSec = dur,
                projectSettings = snap.settings,
                canUndo = history.isNotEmpty(),
                canRedo = future.isNotEmpty()
            )
        }
        scheduleAutosave()
    }

    private fun recalcAndSync(statusMsg: String? = null) {
        _uiState.update { s ->
            val dur = TimelineEngine.calcProjectDuration(s.clips, s.assetsById)
            val clampedPlayhead = if (dur > 0.0) min(s.playheadSec, dur) else 0.0
            s.copy(
                durationSec = dur,
                playheadSec = clampedPlayhead,
                saveStatus = statusMsg ?: s.saveStatus
            )
        }
        scheduleAutosave()
    }

    // -------------------------------------------------------------------------
    // WORKSPACE MODES, THEME & LAYOUT
    // -------------------------------------------------------------------------
    fun setWorkspaceMode(mode: WorkspaceMode) {
        prefs.edit().putString("workspace_mode", mode.id).apply()
        _uiState.update { s ->
            var selId = s.selectedClipId
            if (selId == null && s.clips.isNotEmpty()) {
                selId = if (mode == WorkspaceMode.MUSIC) {
                    (s.clips.find { it.track.isAudio } ?: s.clips.first()).id
                } else {
                    (s.clips.find { it.track.isVisual } ?: s.clips.first()).id
                }
            }
            s.copy(
                workspaceMode = mode,
                activeInspectorTab = mode.defaultInspectorTab(),
                selectedClipId = selId,
                selectedClipIds = selId?.let { setOf(it) } ?: emptySet()
            )
        }
        showToast("${mode.label} Mode (${mode.shortcutHint})")
    }

    fun setInspectorTab(tab: InspectorTab) {
        _uiState.update { it.copy(activeInspectorTab = tab) }
    }

    fun toggleLightTheme() {
        val next = !_uiState.value.isLightTheme
        prefs.edit().putBoolean("light_theme", next).apply()
        _uiState.update { it.copy(isLightTheme = next) }
        showToast(if (next) "☀ Light Mode On (Shortcut: Alt+L)" else "☾ Dark Mode On (Shortcut: Alt+L)")
    }

    fun toggleMagneticSnap() {
        val next = !_uiState.value.magneticSnap
        prefs.edit().putBoolean("magnetic_snap", next).apply()
        _uiState.update { it.copy(magneticSnap = next) }
        showToast(if (next) "Magnetic snapping enabled" else "Magnetic snapping disabled")
    }

    fun toggleAutoFitTimeline() {
        val next = !_uiState.value.timelineAutoFit
        prefs.edit().putBoolean("timeline_autofit", next).apply()
        _uiState.update { it.copy(timelineAutoFit = next) }
        showToast(if (next) "Auto Fit enabled" else "Manual timeline zoom enabled")
    }

    fun toggleCompactTimeline() {
        val next = !_uiState.value.timelineCompact
        prefs.edit().putBoolean("timeline_compact", next).apply()
        _uiState.update { it.copy(timelineCompact = next) }
        showToast(if (next) "Compact timeline rows enabled" else "Comfortable timeline rows enabled")
    }

    fun toggleFollowPlayhead() {
        val next = !_uiState.value.timelineFollowPlayhead
        prefs.edit().putBoolean("follow_playhead", next).apply()
        _uiState.update { it.copy(timelineFollowPlayhead = next) }
        showToast(if (next) "Playhead cursor-follow ON" else "Playhead cursor-follow OFF")
    }

    fun toggleSafeGuides() {
        val next = !_uiState.value.safeGuidesOn
        _uiState.update { it.copy(safeGuidesOn = next) }
        showToast(if (next) "Safe-area guides enabled" else "Safe-area guides hidden")
    }

    fun toggleCheckerboard() {
        val next = !_uiState.value.checkerboardOn
        _uiState.update { it.copy(checkerboardOn = next) }
        showToast(if (next) "Checkerboard preview background enabled" else "Preview background restored")
    }

    fun toggleGradeSplitWipe() {
        val next = !_uiState.value.gradeSplitOn
        _uiState.update { it.copy(gradeSplitOn = next) }
        showToast(if (next) "Split-screen Before/After grade wipe ON" else "Split-screen grade wipe OFF")
    }

    fun setGradeSplitPercent(pct: Float) {
        _uiState.update { it.copy(gradeSplitPercent = pct.coerceIn(5f, 95f)) }
    }

    fun toggleScopesPinned() {
        val next = !_uiState.value.scopesForceOn
        _uiState.update { it.copy(scopesForceOn = next) }
        showToast(if (next) "Live RGB Scopes pinned on" else "Live RGB Scopes set to auto (Colour mode)")
    }

    fun setScopesMode(mode: ScopesMode) {
        _uiState.update { it.copy(scopesMode = mode) }
    }

    fun setTimelineZoom(pxPerSec: Double) {
        _uiState.update {
            it.copy(
                timelineAutoFit = false,
                timelineZoomPxPerSec = pxPerSec.coerceIn(1.0, 300.0)
            )
        }
    }

    /**
     * The zoom the timeline is actually drawn at. While Fit is on that is not the stored zoom,
     * and zooming in or out should carry on from what is on screen rather than jump.
     */
    private var visibleTimelineZoom = 75.0

    fun noteVisibleTimelineZoom(pxPerSec: Double) {
        if (pxPerSec.isFinite() && pxPerSec > 0.0) visibleTimelineZoom = pxPerSec
    }

    fun zoomTimelineBy(factor: Double) {
        val s = _uiState.value
        setTimelineZoom((if (s.timelineAutoFit) visibleTimelineZoom else s.timelineZoomPxPerSec) * factor)
    }

    fun zoomToSelectedClip(viewportWidthPx: Float) {
        val s = _uiState.value
        val selId = s.selectedClipId ?: run {
            showToast("Select a clip first")
            return
        }
        val rc = s.resolvedClips.find { it.clip.id == selId } ?: return
        val available = max(160f, viewportWidthPx - 150f)
        val targetScale = (available / max(0.5, rc.duration * 1.6)).coerceIn(8.0, 180.0)
        _uiState.update {
            it.copy(
                timelineAutoFit = false,
                timelineZoomPxPerSec = targetScale
            )
        }
    }

    fun resizePanels(leftDeltaDp: Float = 0f, rightDeltaDp: Float = 0f, timelineDeltaDp: Float = 0f) {
        _uiState.update {
            it.copy(
                leftPanelWidthDp = (it.leftPanelWidthDp + leftDeltaDp).coerceIn(150f, 420f),
                rightPanelWidthDp = (it.rightPanelWidthDp + rightDeltaDp).coerceIn(190f, 420f),
                timelineHeightDp = (it.timelineHeightDp + timelineDeltaDp).coerceIn(170f, 460f)
            )
        }
    }

    // -------------------------------------------------------------------------
    // MEDIA LIBRARY & ASSET MANAGEMENT
    // -------------------------------------------------------------------------
    fun importMediaUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val existing = _uiState.value.assets
            val added = mutableListOf<MediaAsset>()
            val duplicates = mutableListOf<String>()
            val failed = mutableListOf<String>()

            // A duplicate is the same display name AND the same byte size AND the same
            // duration. Two genuinely different files that merely share a filename will
            // not match, so nothing distinct is ever dropped.
            fun isDuplicateOf(asset: MediaAsset, other: MediaAsset): Boolean =
                other.name == asset.name &&
                    other.sizeBytes == asset.sizeBytes &&
                    abs(other.durationSec - asset.durationSec) < 0.05

            for (u in uris) {
                val asset = MediaEngine.importMediaUri(ctx, u)
                if (asset == null) {
                    failed.add(u.lastPathSegment ?: "unknown file")
                    continue
                }
                if (existing.any { isDuplicateOf(asset, it) } || added.any { isDuplicateOf(asset, it) }) {
                    duplicates.add(asset.name)
                    // Drop the copy that was just made so no orphan file is left behind.
                    asset.localPath?.let { p -> runCatching { File(p).delete() } }
                    continue
                }
                added.add(asset)
            }

            if (added.isNotEmpty()) {
                // Imported media belongs to the open project: it moves into the project's folder.
                val projectId = ensureProjectId(force = true)
                val owned = if (projectId == null) added else withContext(Dispatchers.IO) {
                    added.map { projectStore.adoptAsset(projectId, it) }
                }
                if (_uiState.value.currentProjectId != projectId) {
                    // The project was closed while the files were being copied.
                    withContext(Dispatchers.IO) { owned.forEach { a -> a.localPath?.let { runCatching { File(it).delete() } } } }
                    showToast("Import cancelled: the project was closed.")
                    return@launch
                }
                snapHistory()
                _uiState.update { s ->
                    s.copy(
                        assets = s.assets + owned,
                        selectedAssetId = owned.first().id,
                        selectedAssetIds = owned.map { it.id }.toSet(),
                        saveStatus = "${owned.size} media file(s) imported"
                    )
                }
                scheduleAutosave()
            }

            val summary = buildList {
                if (added.isNotEmpty()) add("${added.size} imported")
                if (duplicates.isNotEmpty()) add("${duplicates.size} duplicate(s) skipped")
                if (failed.isNotEmpty()) add("${failed.size} unreadable")
            }
            when {
                summary.isEmpty() -> showToast("No supported media found.")
                duplicates.isEmpty() && failed.isEmpty() -> showToast("Import ready: ${added.size} file(s).")
                else -> {
                    showToast(summary.joinToString(" · "))
                    showAlert(
                        "IMPORT SUMMARY",
                        buildString {
                            appendLine("Imported: ${added.size}")
                            if (duplicates.isNotEmpty()) {
                                appendLine("Already in the library (skipped): ${duplicates.joinToString(", ").take(200)}")
                            }
                            if (failed.isNotEmpty()) {
                                appendLine("Could not be read: ${failed.joinToString(", ").take(200)}")
                            }
                        }.trim()
                    )
                }
            }
        }
    }

    /**
     * Selects a library item. [additive] (Ctrl-click, or a tap in multi-select mode) toggles it
     * in the selection; [range] (Shift-click) selects everything between the last pick and it,
     * in the order of [visibleOrder] — the library as currently filtered.
     */
    fun selectAsset(
        assetId: String,
        additive: Boolean = false,
        range: Boolean = false,
        visibleOrder: List<String> = emptyList()
    ) {
        _uiState.update { s ->
            when {
                range && s.selectedAssetId != null && visibleOrder.isNotEmpty() -> {
                    val a = visibleOrder.indexOf(s.selectedAssetId)
                    val b = visibleOrder.indexOf(assetId)
                    if (a < 0 || b < 0) s.copy(selectedAssetId = assetId, selectedAssetIds = setOf(assetId))
                    else s.copy(selectedAssetIds = visibleOrder.subList(min(a, b), max(a, b) + 1).toSet())
                }
                additive -> {
                    val base = if (s.selectedAssetIds.isEmpty()) setOfNotNull(s.selectedAssetId) else s.selectedAssetIds
                    val next = if (assetId in base) base - assetId else base + assetId
                    s.copy(selectedAssetIds = next, selectedAssetId = if (assetId in next) assetId else next.lastOrNull())
                }
                else -> s.copy(selectedAssetId = assetId, selectedAssetIds = setOf(assetId))
            }
        }
    }

    fun selectAllAssets(visibleOrder: List<String>) {
        _uiState.update { it.copy(selectedAssetIds = visibleOrder.toSet(), selectedAssetId = it.selectedAssetId ?: visibleOrder.firstOrNull()) }
    }

    fun clearAssetSelection() {
        _uiState.update { it.copy(selectedAssetIds = emptySet()) }
    }

    /** Library items a bulk action applies to: the multi-selection, or the single selected item. */
    private fun targetAssetIds(s: EditorUiState, clickedId: String? = null): Set<String> = when {
        clickedId != null && clickedId !in s.selectedAssetIds -> setOf(clickedId)
        s.selectedAssetIds.isNotEmpty() -> s.selectedAssetIds
        else -> setOfNotNull(clickedId ?: s.selectedAssetId)
    }

    /** Appends the chosen library items to the timeline one after another, each on its natural track. */
    fun addAssetsToTimeline(clickedId: String? = null) {
        val s = _uiState.value
        val ids = targetAssetIds(s, clickedId)
        val chosen = s.assets.filter { it.id in ids }
        if (chosen.isEmpty()) {
            showToast("Select media in the library first")
            return
        }
        if (chosen.size == 1) {
            addAssetToTimeline(chosen.first())
            return
        }
        snapHistory()
        var clips = s.clips
        val created = ArrayList<String>()
        for (asset in chosen) {
            val track = TimelineOps.naturalTrack(asset)
            if (s.trackStates[track]?.locked == true) continue
            val end = TimelineEngine.starts(clips, s.assetsById).filter { it.clip.track == track }.maxOfOrNull { it.end } ?: 0.0
            val clip = TimelineClip(
                id = generateUid(), assetId = asset.id, track = track, startAt = end,
                trimIn = 0.0, trimOut = asset.durationSec, label = asset.name
            )
            clips = clips + clip
            created.add(clip.id)
        }
        if (created.isEmpty()) {
            showToast("The target tracks are locked.")
            return
        }
        _uiState.update { it.copy(clips = clips, selectedClipId = created.last(), selectedClipIds = created.toSet()) }
        recalcAndSync("${created.size} clips added to timeline")
        showToast("Added ${created.size} clips to the timeline")
    }

    /** Asks before removing library items, because their clips go with them. */
    fun requestDeleteAssets(clickedId: String? = null) {
        val s = _uiState.value
        val ids = targetAssetIds(s, clickedId)
        if (ids.isEmpty()) {
            showToast("Select media in the library first")
            return
        }
        val used = s.clips.count { it.assetId in ids }
        _uiState.update { it.copy(pendingConfirm = PendingConfirm.DeleteAssets(ids, used)) }
    }

    /**
     * Removes media from the library together with the clips that use it. Undoable: the files
     * stay on disk until the project is closed, so undo brings everything back.
     */
    fun deleteAssets(ids: Set<String>) {
        val s = _uiState.value
        val present = s.assets.filter { it.id in ids }
        if (present.isEmpty()) return
        snapHistory()
        _uiState.update { st ->
            val clips = st.clips.filterNot { it.assetId in ids }
            val kept = clips.map { it.id }.toSet()
            st.copy(
                assets = st.assets.filterNot { it.id in ids },
                clips = clips,
                selectedAssetId = st.selectedAssetId?.takeIf { it !in ids },
                selectedAssetIds = st.selectedAssetIds - ids,
                selectedClipId = st.selectedClipId?.takeIf { it in kept },
                selectedClipIds = st.selectedClipIds.filter { it in kept }.toSet()
            )
        }
        recalcAndSync("Media removed from project")
        showToast(if (present.size == 1) "Removed ${present.first().name}" else "Removed ${present.size} media files")
    }

    /** Selects every library item no clip uses, ready to be removed in one go. */
    fun selectUnusedAssets() {
        val s = _uiState.value
        val used = s.clips.mapNotNull { it.assetId }.toSet()
        val unused = s.assets.filter { it.id !in used }.map { it.id }.toSet()
        _uiState.update { it.copy(selectedAssetIds = unused, selectedAssetId = unused.firstOrNull() ?: it.selectedAssetId) }
        showToast(if (unused.isEmpty()) "Every media file is used on the timeline" else "${unused.size} unused media file(s) selected")
    }

    fun renameAsset(assetId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        _uiState.update { s -> s.copy(assets = s.assets.map { if (it.id == assetId) it.copy(name = clean) else it }) }
        scheduleAutosave()
    }

    fun toggleAssetFavorite(assetId: String) {
        _uiState.update { s ->
            s.copy(assets = s.assets.map { a -> if (a.id == assetId) a.copy(favorite = !a.favorite) else a })
        }
        scheduleAutosave()
    }

    fun moveAssetToBin(assetId: String, binName: String) {
        val clean = binName.trim().ifEmpty { "Imported" }
        _uiState.update { s ->
            val updatedBins = if (clean in s.customBins) s.customBins else s.customBins + clean
            s.copy(
                customBins = updatedBins,
                assets = s.assets.map { a ->
                    if (a.id == assetId) a.copy(bin = clean, favorite = a.favorite || clean == "Favorites") else a
                }
            )
        }
        showToast("Asset moved to $clean")
        scheduleAutosave()
    }

    fun addCustomBin(binName: String) {
        val clean = binName.trim()
        if (clean.isEmpty()) return
        _uiState.update { s ->
            val updated = if (clean in s.customBins) s.customBins else s.customBins + clean
            s.copy(customBins = updated, activeBin = clean)
        }
        showToast("Bin '$clean' created.")
    }

    fun setMediaFilter(filter: String) {
        _uiState.update { it.copy(mediaFilter = filter) }
    }

    fun setActiveBin(bin: String) {
        _uiState.update { it.copy(activeBin = bin) }
    }

    fun setAssetSearchQuery(q: String) {
        _uiState.update { it.copy(assetSearchQuery = q) }
    }

    fun setProjectName(name: String) {
        _uiState.update { it.copy(projectName = name.ifBlank { "Untitled Project" }, saveStatus = "Project name updated") }
        scheduleAutosave()
    }

    // -------------------------------------------------------------------------
    // TIMELINE CLIP & TRACK EDITING OPERATIONS
    // -------------------------------------------------------------------------
    fun addAssetToTimeline(asset: MediaAsset, targetTrack: TimelineTrack? = null, startAtOverride: Double? = null) {
        val s = _uiState.value
        // A drop on a track that cannot hold this kind of media goes to its natural track instead.
        val track = TimelineOps.dropTrack(asset, targetTrack)
        if (s.trackStates[track]?.locked == true) {
            showToast("${track.label} is locked. Unlock it before adding clips.")
            return
        }
        snapHistory()
        val trackEnd = s.resolvedClips.filter { it.clip.track == track }.maxOfOrNull { it.end } ?: 0.0
        val id = generateUid()
        val clipStart = TimelineEngine.resolveTimelineStart(
            desired = startAtOverride ?: trackEnd,
            duration = max(0.1, asset.durationSec),
            track = track,
            clipId = id,
            scalePxPerSec = s.timelineZoomPxPerSec,
            direction = 1.0,
            allClips = s.clips,
            assetsById = s.assetsById,
            playhead = s.playheadSec,
            magneticSnap = s.magneticSnap && startAtOverride != null,
            extraSnapTimes = if (startAtOverride != null) TimelineOps.snapTimes(s.resolvedClips, emptySet(), s.markers) else emptyList()
        )
        val newClip = TimelineClip(
            id = id,
            assetId = asset.id,
            track = track,
            startAt = clipStart,
            trimIn = 0.0,
            trimOut = asset.durationSec,
            label = asset.name,
            scale = if (track == TimelineTrack.OVERLAY) 35.0 else 100.0
        )
        _uiState.update {
            it.copy(
                clips = it.clips + newClip,
                selectedClipId = newClip.id,
                selectedClipIds = setOf(newClip.id)
            )
        }
        recalcAndSync("Clip added to timeline")
        showToast("Added ${asset.name} to ${track.label}")
    }

    fun addSolidColorClip(name: String, colorHex: String, durationSec: Double) {
        snapHistory()
        val cleanDur = durationSec.coerceIn(0.1, 600.0)
        val cleanName = name.trim().ifEmpty { "Solid ${colorHex.uppercase()}" }
        val asset = MediaAsset(
            id = generateUid(),
            name = cleanName,
            mimeType = "image/x-filmcraft-solid",
            kind = AssetKind.COLOR,
            colorHex = colorHex,
            durationSec = cleanDur,
            bin = "Titles & Graphics"
        )
        val s = _uiState.value
        val trackEnd = s.resolvedClips.filter { it.clip.track == TimelineTrack.VIDEO }.maxOfOrNull { it.end } ?: 0.0
        val clip = TimelineClip(
            id = generateUid(),
            assetId = asset.id,
            track = TimelineTrack.VIDEO,
            startAt = trackEnd,
            trimIn = 0.0,
            trimOut = cleanDur,
            label = cleanName
        )
        _uiState.update {
            it.copy(
                assets = it.assets + asset,
                selectedAssetId = asset.id,
                clips = it.clips + clip,
                selectedClipId = clip.id,
                selectedClipIds = setOf(clip.id),
                showSolidModal = false
            )
        }
        recalcAndSync("Added solid colour clip")
        showToast("Added $cleanName · ${cleanDur}s solid colour clip")
    }

    fun addBlankTextClip(atSec: Double? = null) {
        val s = _uiState.value
        if (s.trackStates[TimelineTrack.TEXT]?.locked == true) {
            showToast("${TimelineTrack.TEXT.label} is locked.")
            return
        }
        snapHistory()
        val id = generateUid()
        val clip = TimelineClip(
            id = id,
            assetId = null,
            track = TimelineTrack.TEXT,
            startAt = TimelineEngine.resolveTimelineStart(
                desired = atSec ?: s.playheadSec, duration = 5.0, track = TimelineTrack.TEXT, clipId = id,
                scalePxPerSec = 0.0, direction = 1.0, allClips = s.clips, assetsById = s.assetsById,
                playhead = s.playheadSec, magneticSnap = false
            ),
            trimIn = 0.0,
            trimOut = 5.0,
            text = "Edit this title",
            fontSize = 42,
            fontFamily = "system-ui",
            fontWeight = 800,
            textColor = "#ffffff",
            textAlign = "center",
            strokeColor = "#000000",
            strokeWidth = 0f,
            shadow = true,
            label = "Text Overlay"
        )
        _uiState.update {
            it.copy(
                clips = it.clips + clip,
                selectedClipId = clip.id,
                selectedClipIds = setOf(clip.id),
                activeInspectorTab = InspectorTab.CLIP
            )
        }
        recalcAndSync("Text overlay added")
        showToast("Text overlay added at ${formatTimecode(clip.startAt)}")
    }

    fun importSrtContent(srtText: String) {
        val cues = TimelineEngine.parseSrtToClips(srtText)
        if (cues.isEmpty()) {
            showToast("No valid SRT subtitle cues found.")
            return
        }
        snapHistory()
        _uiState.update { s ->
            s.copy(
                clips = s.clips + cues,
                selectedClipId = cues.first().id,
                selectedClipIds = setOf(cues.first().id)
            )
        }
        recalcAndSync("Imported ${cues.size} SRT subtitle cues")
        showToast("Imported ${cues.size} subtitle cues.")
    }

    /**
     * Selects a clip. [additive] (Ctrl-click, or any tap while multi-select mode is on) toggles
     * the clip in the selection; [range] (Shift-click) selects everything between the primary
     * clip and this one, across the tracks between them.
     */
    fun selectClip(clipId: String?, additive: Boolean = false, range: Boolean = false) {
        if (clipId == null) {
            _uiState.update { it.copy(selectedClipId = null, selectedClipIds = emptySet()) }
            return
        }
        _uiState.update { s ->
            when {
                range && s.selectedClipId != null && s.selectedClipId != clipId -> {
                    val ids = TimelineOps.rangeBetween(s.resolvedClips, s.selectedClipId, clipId)
                    s.copy(selectedClipIds = if (additive) s.selectedClipIds + ids else ids)
                }
                additive || s.selectionMode -> {
                    val base = s.selectionIds
                    val next = if (clipId in base) base - clipId else base + clipId
                    s.copy(selectedClipId = if (clipId in next) clipId else next.lastOrNull(), selectedClipIds = next)
                }
                else -> s.copy(selectedClipId = clipId, selectedClipIds = setOf(clipId))
            }
        }
    }

    /** Replaces (or, with [additive], extends) the selection — used by the marquee and by tools. */
    fun selectClipsByIds(ids: Set<String>, additive: Boolean = false) {
        _uiState.update { s ->
            val existing = s.clips.map { it.id }.toSet()
            val next = (if (additive) s.selectionIds + ids else ids).filter { it in existing }.toSet()
            s.copy(
                selectedClipIds = next,
                selectedClipId = s.selectedClipId?.takeIf { it in next } ?: next.lastOrNull()
            )
        }
    }

    /** Selects what a marquee dragged across [tracks] between two times touches. */
    fun selectClipsInRange(tracks: Set<TimelineTrack>, fromSec: Double, toSec: Double, additive: Boolean = false) {
        val s = _uiState.value
        selectClipsByIds(TimelineOps.clipsInRange(s.resolvedClips, tracks, fromSec, toSec), additive)
    }

    fun selectAllClips(track: TimelineTrack? = null) {
        val s = _uiState.value
        val ids = s.clips.filter { track == null || it.track == track }.map { it.id }.toSet()
        if (ids.isEmpty()) {
            showToast("Nothing to select.")
            return
        }
        selectClipsByIds(ids)
        showToast("${ids.size} clip(s) selected")
    }

    fun selectClipsFromPlayhead() {
        val s = _uiState.value
        val ids = TimelineOps.clipsFrom(s.resolvedClips, s.playheadSec - 1e-6)
        if (ids.isEmpty()) {
            showToast("No clips after the playhead.")
            return
        }
        selectClipsByIds(ids)
        showToast("${ids.size} clip(s) selected")
    }

    fun toggleSelectionMode() {
        val next = !_uiState.value.selectionMode
        _uiState.update { it.copy(selectionMode = next) }
        showToast(if (next) "Multi-select on: tap clips to add them" else "Multi-select off")
    }

    /**
     * Applies an edit to the selected clip. On a clip with keyframes the edit is routed into
     * the animation (see [KeyframeOps.reconcileEdit]): the transform shows and edits the values
     * under the playhead, so a slider always changes what is on screen.
     */
    fun updateSelectedClip(transform: (TimelineClip) -> TimelineClip) {
        val s = _uiState.value
        val id = s.selectedClipId ?: return
        val rc = s.resolvedClips.find { it.clip.id == id }
        _uiState.update { state ->
            state.copy(
                clips = state.clips.map { c ->
                    when {
                        c.id != id -> c
                        c.keyframes.isEmpty() || rc == null -> transform(c)
                        else -> {
                            val localT = (state.playheadSec - rc.start).coerceIn(0.0, rc.duration)
                            val shown = EditorUiState.shownAt(c, localT)
                            KeyframeOps.reconcileEdit(shown, transform(shown), localT)
                        }
                    }
                }
            )
        }
        recalcAndSync("Edited locally")
    }

    /** Applies the same change to every selected clip (bulk colour, volume, speed…). */
    fun updateSelectedClips(transform: (TimelineClip) -> TimelineClip) {
        val s = _uiState.value
        val ids = editableSelection(s)
        if (ids.isEmpty()) return
        _uiState.update { state -> state.copy(clips = state.clips.map { if (it.id in ids) transform(it) else it }) }
        recalcAndSync("Edited ${ids.size} clip(s)")
    }

    /** Selected clips that sit on unlocked tracks — the ones an edit may touch. */
    private fun editableSelection(s: EditorUiState): Set<String> {
        val ids = s.selectionIds
        return s.clips.filter { it.id in ids && s.trackStates[it.track]?.locked != true }.map { it.id }.toSet()
    }

    private fun lockedTracks(s: EditorUiState): Set<TimelineTrack> =
        s.trackStates.filterValues { it.locked }.keys

    fun moveOrTrimClip(
        clipId: String,
        newTrack: TimelineTrack? = null,
        desiredStartSec: Double? = null,
        newTrimInSec: Double? = null,
        newTrimOutSec: Double? = null,
        scalePxPerSec: Double = 75.0
    ) {
        val s = _uiState.value
        val target = s.clips.find { it.id == clipId } ?: return
        if (s.trackStates[target.track]?.locked == true) {
            showToast("This track is locked. Unlock it before editing.")
            return
        }
        val asset = target.assetId?.let { s.assetsById[it] }
        val maxOut = if (asset != null && (asset.kind == AssetKind.VIDEO || asset.kind == AssetKind.AUDIO) && !asset.durationGuess) {
            asset.durationSec
        } else 3600.0

        val updatedTrimIn = newTrimInSec?.coerceIn(0.0, max(0.0, (newTrimOutSec ?: target.trimOut) - 0.1)) ?: target.trimIn
        val updatedTrimOut = newTrimOutSec?.coerceIn(updatedTrimIn + 0.1, maxOut) ?: target.trimOut
        val destTrack = (newTrack ?: target.track).takeIf {
            it == target.track || (TimelineOps.canPlace(target, asset, it) && s.trackStates[it]?.locked != true)
        } ?: target.track
        val dur = TimelineEngine.clipDuration(target.copy(trimIn = updatedTrimIn, trimOut = updatedTrimOut), s.assetsById)

        val resolvedStart = if (desiredStartSec != null) {
            TimelineEngine.resolveTimelineStart(
                desired = desiredStartSec,
                duration = dur,
                track = destTrack,
                clipId = clipId,
                scalePxPerSec = scalePxPerSec,
                direction = desiredStartSec - target.startAt,
                allClips = s.clips,
                assetsById = s.assetsById,
                playhead = s.playheadSec,
                magneticSnap = s.magneticSnap,
                extraSnapTimes = TimelineOps.snapTimes(s.resolvedClips, setOf(clipId), s.markers)
            )
        } else target.startAt

        _uiState.update { state ->
            state.copy(
                clips = state.clips.map { c ->
                    if (c.id == clipId) {
                        c.copy(
                            track = destTrack,
                            startAt = resolvedStart,
                            trimIn = updatedTrimIn,
                            trimOut = updatedTrimOut
                        )
                    } else c
                }
            )
        }
        recalcAndSync("Timeline edit changed")
    }

    /**
     * Clips an at-the-playhead edit applies to: the selected clips under the playhead, or — when
     * none of the selection is there — every clip under it on an unlocked track.
     */
    private fun clipsUnderPlayhead(s: EditorUiState, selectionOnly: Boolean): Set<String> {
        val under = s.resolvedClips.filter { rc ->
            s.playheadSec > rc.start + TimelineOps.MIN_PIECE && s.playheadSec < rc.end - TimelineOps.MIN_PIECE &&
                s.trackStates[rc.clip.track]?.locked != true
        }
        val selected = under.filter { it.clip.id in s.selectionIds }
        return (if (selected.isNotEmpty() || selectionOnly) selected else under).map { it.clip.id }.toSet()
    }

    /** Splits the selected clips under the playhead; with nothing selected there, every clip under it. */
    fun splitAtPlayhead(allTracks: Boolean = false) {
        val s = _uiState.value
        val targets = if (allTracks) null else clipsUnderPlayhead(s, selectionOnly = false)
        val edit = if (targets != null && targets.isEmpty()) null
        else TimelineOps.splitAt(s.clips, s.assetsById, s.playheadSec, targets, lockedTracks(s))
        if (edit == null) {
            showToast("Move the playhead inside a clip to split it")
            return
        }
        snapHistory()
        _uiState.update {
            it.copy(
                clips = edit.clips,
                selectedClipId = edit.affectedIds.lastOrNull(),
                selectedClipIds = edit.affectedIds
            )
        }
        recalcAndSync("Clip split")
        showToast(
            if (edit.affectedIds.size > 1) "${edit.affectedIds.size} clips split at ${formatTimecode(s.playheadSec)}"
            else "Clip split at ${formatTimecode(s.playheadSec)}"
        )
    }

    fun deleteSelectedClips() {
        val s = _uiState.value
        val idsToRemove = editableSelection(s)
        if (idsToRemove.isEmpty()) {
            showToast(if (s.selectionIds.isEmpty()) "Select a clip first" else "The selected clips are on locked tracks.")
            return
        }
        snapHistory()
        _uiState.update {
            it.copy(
                clips = it.clips.filterNot { c -> c.id in idsToRemove },
                selectedClipId = null,
                selectedClipIds = emptySet()
            )
        }
        recalcAndSync("Clip deleted")
        showToast(if (idsToRemove.size > 1) "${idsToRemove.size} clips deleted" else "Clip deleted")
    }

    /** Deletes the selection and closes the holes it leaves, on each affected track. */
    fun rippleDeleteSelectedClip() {
        val s = _uiState.value
        val ids = editableSelection(s)
        if (ids.isEmpty()) {
            showToast(if (s.selectionIds.isEmpty()) "Select a clip first" else "The selected clips are on locked tracks.")
            return
        }
        snapHistory()
        val updated = TimelineOps.rippleDelete(s.clips, s.assetsById, ids)
        _uiState.update {
            it.copy(
                clips = updated,
                selectedClipId = null,
                selectedClipIds = emptySet()
            )
        }
        recalcAndSync("Ripple deleted")
        showToast(if (ids.size > 1) "Deleted ${ids.size} clips and closed the gaps" else "Deleted clip and closed the gap")
    }

    /**
     * Trims the clips under the playhead to it. [keepTail] removes everything before the
     * playhead (Q), otherwise everything after it (W); [ripple] closes the hole that leaves.
     */
    fun trimToPlayhead(keepTail: Boolean, ripple: Boolean) {
        val s = _uiState.value
        val targets = clipsUnderPlayhead(s, selectionOnly = false)
        if (targets.isEmpty()) {
            showToast("Move the playhead inside a clip to trim it")
            return
        }
        var clips = s.clips
        var changed = 0
        for (id in targets) {
            val next = TimelineOps.trimToTime(clips, s.assetsById, id, s.playheadSec, keepTail, ripple) ?: continue
            clips = next
            changed++
        }
        if (changed == 0) return
        snapHistory()
        // A rippled head trim pulls the cut back to where the clip began; follow it.
        val newPlayhead = if (keepTail && ripple) {
            s.resolvedClips.filter { it.clip.id in targets }.minOf { it.start }
        } else s.playheadSec
        _uiState.update { it.copy(clips = clips, playheadSec = newPlayhead) }
        recalcAndSync("Trimmed to playhead")
        showToast(if (keepTail) "Trimmed start to playhead" else "Trimmed end to playhead")
    }

    /**
     * Packs clips end to end: on [onlyTrack] when given, otherwise on the tracks of the
     * selection, or on every unlocked track when nothing is selected.
     */
    fun closeGaps(onlyTrack: TimelineTrack? = null) {
        val s = _uiState.value
        val selectedTracks = if (onlyTrack != null) setOf(onlyTrack)
        else s.clips.filter { it.id in s.selectionIds }.map { it.track }.toSet()
        val tracks = (selectedTracks.ifEmpty { TimelineTrack.entries.toSet() }) - lockedTracks(s)
        val updated = TimelineOps.closeAllGaps(s.clips, s.assetsById, tracks)
        if (updated == s.clips) {
            showToast("No gaps to close.")
            return
        }
        snapHistory()
        _uiState.update { it.copy(clips = updated) }
        recalcAndSync("Gaps closed")
        showToast("Gaps closed")
    }

    /** Closes the single gap under [atSec] on [track] (right-click an empty stretch of a track). */
    fun closeGapAt(track: TimelineTrack, atSec: Double) {
        val s = _uiState.value
        if (s.trackStates[track]?.locked == true) {
            showToast("${track.label} is locked.")
            return
        }
        val updated = TimelineOps.closeGapAt(s.clips, s.assetsById, track, atSec)
        if (updated == null) {
            showToast("There is no gap here to close.")
            return
        }
        snapHistory()
        _uiState.update { it.copy(clips = updated) }
        recalcAndSync("Gap closed")
        showToast("Gap closed on ${track.label}")
    }

    /** Opens [lengthSec] of empty space on [track] at [atSec], pushing later clips right. */
    fun insertGapAt(track: TimelineTrack, atSec: Double, lengthSec: Double = 1.0) {
        val s = _uiState.value
        if (s.trackStates[track]?.locked == true) {
            showToast("${track.label} is locked.")
            return
        }
        snapHistory()
        _uiState.update { it.copy(clips = TimelineOps.insertSpace(s.clips, s.assetsById, track, atSec, lengthSec)) }
        recalcAndSync("Gap inserted")
        showToast("Inserted ${"%.1f".format(lengthSec)} s on ${track.label}")
    }

    fun duplicateSelectedClips() {
        val s = _uiState.value
        val ids = editableSelection(s)
        val group = s.resolvedClips.filter { it.clip.id in ids }
        if (group.isEmpty()) {
            showToast("Select a clip first")
            return
        }
        snapHistory()
        // The copies keep the group's spacing and land directly after it.
        val edit = TimelineOps.paste(
            s.clips, s.assetsById,
            group.map { it.clip.copy(startAt = it.start, label = "${it.clip.label} copy") },
            group.maxOf { it.end }
        )
        _uiState.update {
            it.copy(
                clips = edit.clips,
                selectedClipId = edit.affectedIds.firstOrNull(),
                selectedClipIds = edit.affectedIds
            )
        }
        recalcAndSync("Clip duplicated")
        showToast(if (edit.affectedIds.size > 1) "${edit.affectedIds.size} clips duplicated" else "Clip duplicated")
    }

    // ---- Clip clipboard

    fun copySelectedClips(): Boolean {
        val s = _uiState.value
        val group = s.resolvedClips.filter { it.clip.id in s.selectionIds }
        if (group.isEmpty()) {
            showToast("Select a clip first")
            return false
        }
        _uiState.update { it.copy(clipClipboard = group.map { rc -> rc.clip.copy(startAt = rc.start) }) }
        showToast(if (group.size > 1) "${group.size} clips copied" else "Clip copied")
        return true
    }

    fun cutSelectedClips() {
        val s = _uiState.value
        val ids = editableSelection(s)
        val group = s.resolvedClips.filter { it.clip.id in ids }
        if (group.isEmpty()) {
            showToast(if (s.selectionIds.isEmpty()) "Select a clip first" else "The selected clips are on locked tracks.")
            return
        }
        snapHistory()
        _uiState.update {
            it.copy(
                clipClipboard = group.map { rc -> rc.clip.copy(startAt = rc.start) },
                clips = it.clips.filterNot { c -> c.id in ids },
                selectedClipId = null,
                selectedClipIds = emptySet()
            )
        }
        recalcAndSync("Clips cut")
        showToast(if (group.size > 1) "${group.size} clips cut" else "Clip cut")
    }

    /** Pastes the clipboard at [atSec] (the playhead by default), keeping the clips' spacing and tracks. */
    fun pasteClips(atSec: Double? = null) {
        val s = _uiState.value
        val locked = lockedTracks(s)
        // Clips whose media has since left the library cannot be pasted.
        val usable = s.clipClipboard.filter { (it.assetId == null || s.assetsById.containsKey(it.assetId)) && it.track !in locked }
        if (usable.isEmpty()) {
            showToast(if (s.clipClipboard.isEmpty()) "Copy or cut a clip first" else "Nothing on the clipboard can be pasted here.")
            return
        }
        snapHistory()
        val edit = TimelineOps.paste(s.clips, s.assetsById, usable, atSec ?: s.playheadSec)
        _uiState.update {
            it.copy(clips = edit.clips, selectedClipId = edit.affectedIds.firstOrNull(), selectedClipIds = edit.affectedIds)
        }
        recalcAndSync("Clips pasted")
        showToast(if (edit.affectedIds.size > 1) "${edit.affectedIds.size} clips pasted" else "Clip pasted")
    }

    // ---- Moving the selection

    /**
     * Moves every selected clip by [deltaSec] together. The group stops at the first obstacle on
     * any of its tracks, so a group move can never overlap clips. Returns the distance moved.
     * Call [snapHistory] first; a drag calls this many times for one undo step.
     */
    fun moveSelectionBy(deltaSec: Double, ids: Set<String>? = null): Double {
        val s = _uiState.value
        val group = (ids ?: s.selectionIds).filter { id ->
            s.clips.find { it.id == id }?.let { s.trackStates[it.track]?.locked != true } == true
        }.toSet()
        if (group.isEmpty() || deltaSec == 0.0) return 0.0
        val applied = TimelineOps.clampGroupDelta(s.resolvedClips, group, deltaSec)
        if (applied == 0.0) return 0.0
        _uiState.update { it.copy(clips = TimelineOps.moveGroup(it.clips, it.assetsById, group, deltaSec)) }
        recalcAndSync("Timeline edit changed")
        return applied
    }

    /**
     * Where a group drag should land: [wantedDeltaSec] adjusted so an edge of the dragged clip
     * snaps to a nearby cut, marker or the playhead (when snapping is on).
     */
    fun snapGroupDelta(
        anchorClipId: String,
        wantedDeltaSec: Double,
        group: Set<String>,
        scalePxPerSec: Double = _uiState.value.timelineZoomPxPerSec
    ): Double {
        val s = _uiState.value
        if (!s.magneticSnap || scalePxPerSec <= 0.0) return wantedDeltaSec
        val anchor = s.resolvedClips.find { it.clip.id == anchorClipId } ?: return wantedDeltaSec
        val targets = TimelineOps.snapTimes(s.resolvedClips, group, s.markers) + s.playheadSec
        val start = anchor.start + wantedDeltaSec
        val end = start + anchor.duration
        var best = wantedDeltaSec
        var bestPx = 11.0
        for (t in targets) {
            val dStart = abs(t - start) * scalePxPerSec
            if (dStart < bestPx) { bestPx = dStart; best = t - anchor.start }
            val dEnd = abs(t - end) * scalePxPerSec
            if (dEnd < bestPx) { bestPx = dEnd; best = t - anchor.end }
        }
        return best
    }

    /**
     * One step of dragging clips along the timeline. [desiredAnchorStartSec] is where the clip
     * under the pointer should now begin. A single clip may also change track and hop over its
     * neighbours; a group keeps its shape and stops at the first obstacle. Call [snapHistory]
     * once when the drag starts.
     */
    fun dragClipsTo(
        anchorClipId: String,
        group: Set<String>,
        desiredAnchorStartSec: Double,
        targetTrack: TimelineTrack?,
        scalePxPerSec: Double
    ) {
        val s = _uiState.value
        val anchor = s.resolvedClips.find { it.clip.id == anchorClipId } ?: return
        if (group.size <= 1) {
            moveOrTrimClip(anchorClipId, targetTrack, max(0.0, desiredAnchorStartSec), null, null, scalePxPerSec)
            return
        }
        val wanted = max(0.0, desiredAnchorStartSec) - anchor.start
        if (abs(wanted) < 1e-6) return
        moveSelectionBy(snapGroupDelta(anchorClipId, wanted, group, scalePxPerSec), group)
    }

    /**
     * One step of dragging a clip edge. [origin] and [originStartSec] describe the clip when the
     * drag began and [deltaSec] the distance dragged since, so the whole gesture is one clean
     * edit. Call [snapHistory] once when the drag starts.
     */
    fun trimClipEdge(origin: TimelineClip, originStartSec: Double, head: Boolean, deltaSec: Double, scalePxPerSec: Double) {
        val s = _uiState.value
        if (s.clips.none { it.id == origin.id }) return
        if (s.trackStates[origin.track]?.locked == true) {
            showToast("This track is locked. Unlock it before editing.")
            return
        }
        val snapTimes = if (s.magneticSnap) {
            TimelineOps.snapTimes(s.resolvedClips, setOf(origin.id), s.markers) + s.playheadSec
        } else emptyList()
        val window = if (s.magneticSnap && scalePxPerSec > 0.0) 11.0 / scalePxPerSec else 0.0
        val trimmed = TimelineOps.trimEdge(s.clips, s.assetsById, origin, originStartSec, head, deltaSec, snapTimes, window)
        _uiState.update { st -> st.copy(clips = st.clips.map { if (it.id == origin.id) trimmed.copy(track = it.track) else it }) }
        recalcAndSync("Timeline edit changed")
    }

    /** Runs an entry of the clip context menu on the current selection. */
    fun performClipMenuAction(action: ClipMenuAction) {
        when (action) {
            ClipMenuAction.CUT -> cutSelectedClips()
            ClipMenuAction.COPY -> copySelectedClips()
            ClipMenuAction.PASTE -> pasteClips()
            ClipMenuAction.DUPLICATE -> duplicateSelectedClips()
            ClipMenuAction.SPLIT -> splitAtPlayhead()
            ClipMenuAction.TRIM_START -> trimToPlayhead(keepTail = true, ripple = false)
            ClipMenuAction.TRIM_END -> trimToPlayhead(keepTail = false, ripple = false)
            ClipMenuAction.DETACH_AUDIO -> detachAudioFromSelection()
            ClipMenuAction.REPLACE_MEDIA -> {
                val assetId = _uiState.value.selectedAssetId
                if (assetId == null) showToast("Select the replacement in the media library first")
                else replaceSelectedClipMedia(assetId)
            }
            ClipMenuAction.KEYFRAME -> addOrUpdateKeyframeAtPlayhead()
            ClipMenuAction.COPY_PROPS -> copySelectedClipAttributes()
            ClipMenuAction.PASTE_PROPS -> pasteClipAttributesToSelected()
            ClipMenuAction.RESET_PROPS -> resetSelectedClipEffectsAndTransform()
            ClipMenuAction.RIPPLE_DELETE -> rippleDeleteSelectedClip()
            ClipMenuAction.DELETE -> deleteSelectedClips()
            ClipMenuAction.DETECT_BEATS -> detectBeatsOnSelectedClip()
            ClipMenuAction.PRESET_HUB -> setShowPresetsModal(true)
            ClipMenuAction.GRADE_SPLIT -> toggleGradeSplitWipe()
            ClipMenuAction.MODE_EDIT -> setWorkspaceMode(WorkspaceMode.EDIT)
            ClipMenuAction.MODE_COLOR -> setWorkspaceMode(WorkspaceMode.COLOR)
            ClipMenuAction.MODE_MOTION -> setWorkspaceMode(WorkspaceMode.MOTION)
            ClipMenuAction.MODE_MUSIC -> setWorkspaceMode(WorkspaceMode.MUSIC)
            ClipMenuAction.RIG_ARRI -> applyEquipmentRig("ARRI Alexa Mini LF · Cooke S4 35mm")
            ClipMenuAction.RIG_RED -> applyEquipmentRig("RED V-Raptor 8K · Atlas Orion 2x Anamorphic")
            ClipMenuAction.AUDIO_SSL -> applyAudioGearPreset("SSL 4000G Master Bus Glue")
        }
    }

    /** Moves the selection to another track when every selected clip is allowed there. */
    fun moveSelectionToTrack(track: TimelineTrack) {
        val s = _uiState.value
        if (s.trackStates[track]?.locked == true) {
            showToast("${track.label} is locked.")
            return
        }
        val ids = editableSelection(s)
        val group = s.resolvedClips.filter { it.clip.id in ids && it.clip.track != track }
        if (group.isEmpty()) return
        if (group.any { !TimelineOps.canPlace(it.clip, it.clip.assetId?.let { id -> s.assetsById[id] }, track) }) {
            showToast("Those clips cannot go on ${track.label}.")
            return
        }
        snapHistory()
        var clips = s.clips.filterNot { it.id in ids && it.track != track }
        for (rc in group.sortedBy { it.start }) {
            val start = TimelineEngine.resolveTimelineStart(
                desired = rc.start, duration = rc.duration, track = track, clipId = rc.clip.id,
                scalePxPerSec = 0.0, direction = 1.0, allClips = clips, assetsById = s.assetsById,
                playhead = s.playheadSec, magneticSnap = false
            )
            clips = clips + rc.clip.copy(track = track, startAt = start)
        }
        _uiState.update { it.copy(clips = clips) }
        recalcAndSync("Moved to ${track.label}")
        showToast("Moved ${group.size} clip(s) to ${track.label}")
    }

    fun moveSelectedClipToPlayhead() {
        val s = _uiState.value
        val ids = editableSelection(s)
        val group = s.resolvedClips.filter { it.clip.id in ids }
        if (group.isEmpty()) {
            showToast("Select a clip first")
            return
        }
        snapHistory()
        moveSelectionBy(s.playheadSec - group.minOf { it.start }, ids)
        showToast(if (group.size > 1) "Clips moved to playhead" else "Clip moved to playhead")
    }

    fun nudgeSelectedClip(deltaSec: Double) {
        val s = _uiState.value
        val ids = editableSelection(s)
        if (ids.isEmpty()) {
            showToast("Select a clip first")
            return
        }
        snapHistory()
        if (moveSelectionBy(deltaSec, ids) == 0.0) showToast("No room to move that way")
    }

    /** Nudges by whole frames of the project frame rate (the , and . keys). */
    fun nudgeSelectionFrames(frames: Int) {
        nudgeSelectedClip(frames.toDouble() / max(1, _uiState.value.projectSettings.fps))
    }

    /**
     * Puts a video clip's sound on an audio track as its own clip and silences the original, so
     * picture and sound can be trimmed, moved and mixed separately.
     */
    fun detachAudioFromSelection() {
        val s = _uiState.value
        val group = s.resolvedClips.filter { rc ->
            rc.clip.id in editableSelection(s) && rc.clip.track.isVisual &&
                s.assetsById[rc.clip.assetId]?.kind == AssetKind.VIDEO
        }
        if (group.isEmpty()) {
            showToast("Select a video clip to detach its audio")
            return
        }
        var clips = s.clips
        val created = ArrayList<String>()
        for (rc in group) {
            val resolved = TimelineEngine.starts(clips, s.assetsById)
            val track = listOf(TimelineTrack.AUDIO, TimelineTrack.AUDIO2).firstOrNull { t ->
                s.trackStates[t]?.locked != true &&
                    resolved.none { it.clip.track == t && it.start < rc.end - 0.001 && it.end > rc.start + 0.001 }
            } ?: continue
            val sound = rc.clip.copy(
                id = generateUid(), track = track, startAt = rc.start,
                label = "${rc.clip.label} (audio)", keyframes = emptyList(),
                transition = "none", transitionOut = "none", fadeOut = 0.0
            )
            clips = clips.map { if (it.id == rc.clip.id) it.copy(volume = 0.0, volumeKeyframes = emptyList()) else it } + sound
            created.add(sound.id)
        }
        if (created.isEmpty()) {
            showToast("No free audio track at that position.")
            return
        }
        snapHistory()
        _uiState.update { it.copy(clips = clips, selectedClipId = created.last(), selectedClipIds = created.toSet()) }
        recalcAndSync("Audio detached")
        showToast(if (created.size > 1) "Audio detached from ${created.size} clips" else "Audio detached to its own clip")
    }

    /** Swaps the media behind the selected clip for a library item, keeping its edit and look. */
    fun replaceSelectedClipMedia(assetId: String) {
        val s = _uiState.value
        val c = s.selectedClip ?: run { showToast("Select a clip first"); return }
        val asset = s.assetsById[assetId] ?: return
        if (c.assetId == null || !TimelineOps.canPlace(c.copy(assetId = asset.id), asset, c.track)) {
            showToast("${asset.name} cannot replace a clip on ${c.track.label}.")
            return
        }
        snapHistory()
        _uiState.update { st -> st.copy(clips = st.clips.map { if (it.id == c.id) TimelineOps.replaceMedia(it, asset) else it }) }
        recalcAndSync("Media replaced")
        showToast("Replaced with ${asset.name}")
    }

    fun selectAdjacentClip(direction: Int) {
        val s = _uiState.value
        val list = s.resolvedClips
        if (list.isEmpty()) {
            showToast("Timeline is empty.")
            return
        }
        val curIdx = list.indexOfFirst { it.clip.id == s.selectedClipId }
        val nextIdx = if (curIdx < 0) {
            if (direction > 0) 0 else list.lastIndex
        } else {
            (curIdx + direction + list.size) % list.size
        }
        val chosen = list[nextIdx]
        _uiState.update {
            it.copy(
                selectedClipId = chosen.clip.id,
                selectedClipIds = setOf(chosen.clip.id),
                playheadSec = chosen.start
            )
        }
        showToast("Selected ${chosen.clip.label}")
    }

    fun copySelectedClipAttributes() {
        val c = _uiState.value.selectedClip ?: run { showToast("Select a clip first"); return }
        _uiState.update { it.copy(copiedClipTemplate = c) }
        showToast("Clip attributes copied")
    }

    fun pasteClipAttributesToSelected() {
        val s = _uiState.value
        val tpl = s.copiedClipTemplate ?: run { showToast("Copy attributes from a clip first"); return }
        val c = s.selectedClip ?: run { showToast("Select a destination clip first"); return }
        snapHistory()
        updateSelectedClip {
            it.copy(
                scale = tpl.scale,
                opacity = tpl.opacity,
                rotation = tpl.rotation,
                x = tpl.x,
                y = tpl.y,
                flipH = tpl.flipH,
                flipV = tpl.flipV,
                filters = tpl.filters,
                rig = tpl.rig,
                volume = tpl.volume,
                speed = tpl.speed,
                transition = tpl.transition,
                transitionOut = tpl.transitionOut,
                transitionDuration = tpl.transitionDuration,
                fadeOut = tpl.fadeOut,
                keyframes = tpl.keyframes
            )
        }
        showToast("Clip attributes pasted")
    }

    fun resetSelectedClipEffectsAndTransform() {
        if (_uiState.value.selectedClip == null) return
        snapHistory()
        updateSelectedClip {
            it.copy(
                filters = ClipFilters(),
                rig = CameraRig(),
                scale = 100.0,
                opacity = 100.0,
                rotation = 0.0,
                x = 0.0,
                y = 0.0,
                flipH = false,
                flipV = false
                // Keyframes are deliberately preserved: use "Clear keyframes" in the
                // Motion tab when the animation itself should be removed.
            )
        }
        showToast("Effects and transform reset (keyframes kept)")
    }

    /** Resets only the colour / effects grade, leaving transform, audio, timing and keyframes alone. */
    fun resetSelectedClipColor() {
        if (_uiState.value.selectedClip == null) return
        snapHistory()
        updateSelectedClip { it.copy(filters = ClipFilters()) }
        showToast("Colour & effects reset")
    }

    /** Resets only the transform, leaving colour, audio, timing and keyframes alone. */
    fun resetSelectedClipTransform() {
        if (_uiState.value.selectedClip == null) return
        snapHistory()
        updateSelectedClip {
            it.copy(
                scale = 100.0,
                opacity = 100.0,
                rotation = 0.0,
                x = 0.0,
                y = 0.0,
                flipH = false,
                flipV = false
            )
        }
        showToast("Transform reset")
    }

    fun createFreezeFrameFromSelectedClip() {
        val s = _uiState.value
        val c = s.selectedClip ?: run { showToast("Select a video clip first"); return }
        val asset = c.assetId?.let { s.assetsById[it] }
        if (asset == null || asset.kind != AssetKind.VIDEO) {
            showToast("Select a video clip to create a freeze frame")
            return
        }
        val rc = s.resolvedClips.find { it.clip.id == c.id }
        val localTime = c.trimIn + max(0.0, s.playheadSec - (rc?.start ?: 0.0))
        viewModelScope.launch {
            val freezeAsset = MediaEngine.extractFreezeFrameAsset(
                context = getApplication(),
                videoAsset = asset,
                timeSec = localTime,
                clipLabel = c.label
            )
            if (freezeAsset != null) {
                val owned = ensureProjectId(force = true)?.let { projectStore.adoptAsset(it, freezeAsset) } ?: freezeAsset
                snapHistory()
                _uiState.update { state -> state.copy(assets = state.assets + owned, selectedAssetId = owned.id, selectedAssetIds = setOf(owned.id)) }
                scheduleAutosave()
                showToast("Freeze-frame image added to library")
            } else {
                showToast("Could not extract freeze-frame from this video clip.")
            }
        }
    }

    fun toggleTrackLock(track: TimelineTrack) {
        _uiState.update { s ->
            val cur = s.trackStates[track] ?: TrackState()
            s.copy(trackStates = s.trackStates + (track to cur.copy(locked = !cur.locked)))
        }
        scheduleAutosave()
    }

    fun toggleTrackHidden(track: TimelineTrack) {
        _uiState.update { s ->
            val cur = s.trackStates[track] ?: TrackState()
            s.copy(trackStates = s.trackStates + (track to cur.copy(hidden = !cur.hidden)))
        }
        scheduleAutosave()
    }

    fun toggleTrackMute(track: TimelineTrack) {
        _uiState.update { s ->
            val cur = s.trackStates[track] ?: TrackState()
            s.copy(trackStates = s.trackStates + (track to cur.copy(muted = !cur.muted)))
        }
        scheduleAutosave()
    }

    fun toggleTrackSolo(track: TimelineTrack) {
        _uiState.update { s ->
            val cur = s.trackStates[track] ?: TrackState()
            s.copy(trackStates = s.trackStates + (track to cur.copy(solo = !cur.solo)))
        }
        scheduleAutosave()
    }

    fun isTrackAudible(track: TimelineTrack): Boolean =
        track in TimelineEngine.audibleTracks(_uiState.value.trackStates)

    fun toggleTrackCollapsed(track: TimelineTrack) {
        _uiState.update { s ->
            val cur = s.trackStates[track] ?: TrackState()
            s.copy(trackStates = s.trackStates + (track to cur.copy(collapsed = !cur.collapsed)))
        }
        scheduleAutosave()
    }

    /** Collapses every track, or expands them all again when they are already collapsed. */
    fun toggleAllTracksCollapsed() {
        _uiState.update { s ->
            val collapse = TimelineTrack.entries.any { s.trackStates[it]?.collapsed != true }
            s.copy(trackStates = TimelineTrack.entries.associateWith { (s.trackStates[it] ?: TrackState()).copy(collapsed = collapse) })
        }
        scheduleAutosave()
    }

    /** Collapses every track that has no clips, leaving the ones in use open. */
    fun collapseEmptyTracks() {
        _uiState.update { s ->
            val used = s.clips.map { it.track }.toSet()
            s.copy(trackStates = TimelineTrack.entries.associateWith { (s.trackStates[it] ?: TrackState()).copy(collapsed = it !in used) })
        }
        scheduleAutosave()
    }

    // -------------------------------------------------------------------------
    // KEYFRAMES, MOTION PRESETS, COLOUR LOOKS, CAMERA RIGS & AUDIO GEAR
    // -------------------------------------------------------------------------
    /** The selected clip with its resolved position, when the playhead is inside it. */
    private fun selectedAtPlayhead(): Pair<ResolvedClip, Double>? {
        val s = _uiState.value
        val id = s.selectedClipId ?: return null
        val rc = s.resolvedClips.find { it.clip.id == id } ?: return null
        val localT = ((s.playheadSec - rc.start).coerceIn(0.0, rc.duration) * 1000.0).roundToInt() / 1000.0
        return rc to localT
    }

    private fun replaceClip(updated: TimelineClip, status: String) {
        _uiState.update { st -> st.copy(clips = st.clips.map { if (it.id == updated.id) updated else it }) }
        recalcAndSync(status)
    }

    /** Sets a keyframe at the playhead holding the values currently on screen. */
    fun addOrUpdateKeyframeAtPlayhead() {
        val s = _uiState.value
        val (rc, localT) = selectedAtPlayhead() ?: run { showToast("Select a clip first"); return }
        snapHistory()
        replaceClip(KeyframeOps.upsert(rc.clip, localT), "Keyframe set")
        showToast("Keyframe set at ${formatTimecode(s.playheadSec)}")
    }

    /** Adds a keyframe at a clip-local time (double-click in the keyframe lane). */
    fun addKeyframeAt(localT: Double) {
        val c = _uiState.value.selectedClip ?: return
        snapHistory()
        replaceClip(KeyframeOps.upsert(c, localT), "Keyframe set")
    }

    fun removeKeyframeAtPlayhead() {
        val (rc, localT) = selectedAtPlayhead() ?: run { showToast("Select a clip first"); return }
        val index = KeyframeOps.indexAt(rc.clip, localT)
        if (index < 0) {
            showToast("No keyframe at the playhead")
            return
        }
        removeKeyframeAtIndex(index)
        showToast("Keyframe removed")
    }

    /** [index] is in time order, as shown in the keyframe editor. */
    fun removeKeyframeAtIndex(index: Int) {
        val c = _uiState.value.selectedClip ?: return
        if (index !in c.keyframes.indices) return
        snapHistory()
        replaceClip(KeyframeOps.remove(c, index), "Keyframe removed")
    }

    /**
     * Retimes a keyframe to [newLocalT]. A drag calls this repeatedly, so the caller takes the
     * undo snapshot once when the drag starts. Returns the keyframe's new index.
     */
    fun moveKeyframe(index: Int, newLocalT: Double): Int {
        val s = _uiState.value
        val c = s.selectedClip ?: return index
        val rc = s.resolvedClips.find { it.clip.id == c.id } ?: return index
        val updated = KeyframeOps.move(c, index, newLocalT, rc.duration)
        replaceClip(updated, "Keyframe moved")
        val t = newLocalT.coerceIn(0.0, rc.duration)
        return KeyframeOps.indexAt(updated, t).takeIf { it >= 0 } ?: index
    }

    fun setKeyframeEasing(index: Int, easing: String) {
        val c = _uiState.value.selectedClip ?: return
        if (index !in c.keyframes.indices) return
        snapHistory()
        replaceClip(KeyframeOps.setEasing(c, index, easing), "Keyframe easing changed")
    }

    /** Edits the values stored in one keyframe, wherever the playhead is. */
    fun patchKeyframe(index: Int, patch: KeyframePatch) {
        val c = _uiState.value.selectedClip ?: return
        val k = c.keyframes.sortedBy { it.time }.getOrNull(index) ?: return
        replaceClip(KeyframeOps.upsert(c, k.time, patch), "Keyframe edited")
    }

    /** Moves the playhead to the previous / next keyframe of the selected clip. */
    fun jumpToKeyframe(direction: Int) {
        val s = _uiState.value
        val c = s.selectedClip ?: run { showToast("Select a clip first"); return }
        val rc = s.resolvedClips.find { it.clip.id == c.id } ?: return
        val localT = s.playheadSec - rc.start
        val target = if (direction < 0) KeyframeOps.prevTime(c, localT) else KeyframeOps.nextTime(c, localT)
        if (target == null) {
            showToast(if (c.keyframes.isEmpty()) "This clip has no keyframes" else if (direction < 0) "No earlier keyframe" else "No later keyframe")
            return
        }
        seekTo(rc.start + target)
    }

    fun clearKeyframesOnSelectedClip() {
        if (_uiState.value.selectedClip == null) return
        snapHistory()
        updateSelectedClip { it.copy(keyframes = emptyList()) }
    }

    fun toggleKeyframeLane() {
        val next = !_uiState.value.showKeyframeLane
        prefs.edit().putBoolean("keyframe_lane", next).apply()
        _uiState.update { it.copy(showKeyframeLane = next) }
    }

    fun applyLookPreset(preset: LookPreset) {
        if (_uiState.value.selectedClip == null) {
            showToast("Select a clip first")
            return
        }
        snapHistory()
        updateSelectedClip { it.copy(filters = preset.filters) }
        showToast("Applied ${preset.title} look")
    }

    fun applyMotionPreset(presetName: String) {
        val s = _uiState.value
        val c = s.selectedClip ?: run { showToast("Select a timeline clip first."); return }
        val dur = TimelineEngine.clipDuration(c, s.assetsById)
        snapHistory()
        val customSteps = s.customMotionPresets[presetName]
        val updated = if (customSteps != null) {
            val baseScale = c.scale / 100.0
            val kfs = customSteps.map { st ->
                Keyframe(
                    time = (st.timeSec * dur * 1000.0).roundToInt() / 1000.0,
                    scale = (st.scale ?: 100.0) * baseScale,
                    opacity = st.opacity ?: c.opacity,
                    rotation = st.rotation ?: c.rotation,
                    x = c.x + (st.x ?: 0.0),
                    y = c.y + (st.y ?: 0.0),
                    filters = c.filters
                )
            }
            c.copy(keyframes = kfs)
        } else {
            PresetCatalog.applyMotionToClip(c, presetName, dur)
        }
        updateSelectedClip { updated }
        showToast("Applied motion: $presetName")
    }

    fun applyEquipmentRig(name: String) {
        val s = _uiState.value
        val rigPreset = s.allEquipmentRigs[name] ?: return
        val c = s.selectedClip ?: run { showToast("Select a clip first"); return }
        val dur = TimelineEngine.clipDuration(c, s.assetsById)
        snapHistory()
        updateSelectedClip { cur ->
            var next = cur.copy(
                scale = rigPreset.scale,
                filters = rigPreset.filters ?: cur.filters,
                rig = rigPreset.rig,
                equipmentName = name
            )
            if (rigPreset.motionPresetName != null) {
                next = PresetCatalog.applyMotionToClip(next, rigPreset.motionPresetName, dur)
            }
            next
        }
        showToast("Applied equipment rig: $name")
    }

    fun applyAudioGearPreset(name: String) {
        val s = _uiState.value
        val gear = s.allAudioGear[name] ?: return
        if (s.selectedClip == null) { showToast("Select a clip first"); return }
        snapHistory()
        updateSelectedClip {
            it.copy(
                audioEq = gear.eq,
                audioComp = gear.comp,
                audioPan = gear.pan,
                audioDeess = gear.deess,
                audioReverb = gear.reverb,
                volume = gear.volume,
                audioPresetName = name
            )
        }
        showToast("Applied audio gear: $name")
    }

    fun addVolumeAutomationPointAtPlayhead() {
        val s = _uiState.value
        val c = s.selectedClip ?: return
        val rc = s.resolvedClips.find { it.clip.id == c.id } ?: return
        val local = (s.playheadSec - rc.start).coerceIn(0.0, rc.duration)
        snapHistory()
        val filtered = c.volumeKeyframes.filter { abs(it.time - local) >= 0.05 }
        val updated = (filtered + VolumePoint(time = local, value = 100.0)).sortedBy { it.time }
        updateSelectedClip { it.copy(volumeKeyframes = updated) }
        showToast("Volume automation point added at ${"%.2f".format(local)}s")
    }

    fun updateVolumeAutomationPoint(index: Int, value: Double) {
        val c = _uiState.value.selectedClip ?: return
        if (index !in c.volumeKeyframes.indices) return
        val updated = c.volumeKeyframes.toMutableList().apply {
            this[index] = this[index].copy(value = value.coerceIn(0.0, 200.0))
        }
        updateSelectedClip { it.copy(volumeKeyframes = updated) }
    }

    fun removeVolumeAutomationPoint(index: Int) {
        val c = _uiState.value.selectedClip ?: return
        if (index !in c.volumeKeyframes.indices) return
        val updated = c.volumeKeyframes.toMutableList().apply { removeAt(index) }
        updateSelectedClip { it.copy(volumeKeyframes = updated) }
    }

    fun clearVolumeAutomation() {
        updateSelectedClip { it.copy(volumeKeyframes = emptyList()) }
        showToast("Volume automation cleared")
    }

    fun detectBeatsOnSelectedClip() {
        val s = _uiState.value
        val c = s.selectedClip ?: run { showToast("Select an audio or video clip first"); return }
        val rc = s.resolvedClips.find { it.clip.id == c.id } ?: return
        val asset = c.assetId?.let { s.assetsById[it] }
        snapHistory()
        val beatMarkers = MediaEngine.detectAudioBeats(
            asset = asset,
            clipStartSec = rc.start,
            trimInSec = c.trimIn,
            clipDurationSec = rc.duration
        )
        _uiState.update { it.copy(markers = (it.markers + beatMarkers).sortedBy { m -> m.time }) }
        showToast("Added ${beatMarkers.size} beat markers to timeline")
    }

    // -------------------------------------------------------------------------
    // MARKERS & TRANSPORT PLAYBACK
    // -------------------------------------------------------------------------
    fun addMarkerAtPlayhead(customLabel: String? = null) = addMarkerAt(_uiState.value.playheadSec, customLabel)

    fun addMarkerAt(timeSec: Double, customLabel: String? = null) {
        snapHistory()
        val s = _uiState.value
        val t = timeSec.coerceAtLeast(0.0)
        val label = customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Marker ${s.markers.size + 1}"
        val next = (s.markers.filterNot { abs(it.time - t) < 0.01 } + TimelineMarker(time = t, label = label)).sortedBy { it.time }
        _uiState.update { it.copy(markers = next) }
        scheduleAutosave()
        showToast("Marker added at ${formatTimecode(t)}")
    }

    fun removeMarker(marker: TimelineMarker) {
        if (marker !in _uiState.value.markers) return
        snapHistory()
        _uiState.update { it.copy(markers = it.markers - marker) }
        scheduleAutosave()
        showToast("Marker removed")
    }

    fun renameMarker(marker: TimelineMarker, label: String) {
        val clean = label.trim()
        if (clean.isEmpty() || marker !in _uiState.value.markers) return
        snapHistory()
        _uiState.update { it.copy(markers = it.markers.map { m -> if (m == marker) m.copy(label = clean) else m }) }
        scheduleAutosave()
    }

    fun clearMarkers() {
        if (_uiState.value.markers.isEmpty()) return
        snapHistory()
        _uiState.update { it.copy(markers = emptyList()) }
        scheduleAutosave()
        showToast("All markers removed")
    }

    fun jumpToMarker(direction: Int) {
        val s = _uiState.value
        val sorted = s.markers.sortedBy { it.time }
        val target = if (direction < 0) {
            sorted.lastOrNull { it.time < s.playheadSec - 0.02 }
        } else {
            sorted.firstOrNull { it.time > s.playheadSec + 0.02 }
        }
        if (target == null) {
            showToast(if (direction < 0) "No earlier marker." else "No later marker.")
            return
        }
        seekTo(target.time)
    }

    fun seekTo(timeSec: Double) {
        val s = _uiState.value
        val clamped = timeSec.coerceIn(0.0, max(0.0, s.durationSec))
        _uiState.update { it.copy(playheadSec = clamped) }
        // While playing, re-cue the audio so it follows the new position.
        if (s.isPlaying) cueAudio(clamped)
    }

    /** Sound is previewed at normal speed only; shuttling faster or backwards is silent. */
    private fun cueAudio(fromSec: Double) {
        val s = _uiState.value
        if (s.shuttleRate != 1.0) {
            previewAudio.stop()
            return
        }
        previewAudio.start(
            scope = viewModelScope,
            clips = s.resolvedClips,
            assets = s.assetsById,
            audibleTracks = TimelineEngine.audibleTracks(s.trackStates),
            fromSec = fromSec
        )
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) pausePlayback() else startPlayback(1.0)
    }

    /**
     * J / K / L transport. L plays forwards and J backwards; pressing the same key again doubles
     * the speed up to 8x, and K (or [direction] 0) stops.
     */
    fun shuttle(direction: Int) {
        val s = _uiState.value
        if (direction == 0) {
            pausePlayback()
            return
        }
        val sign = if (direction > 0) 1.0 else -1.0
        val rate = if (s.isPlaying && s.shuttleRate * sign > 0.0) {
            sign * min(8.0, abs(s.shuttleRate) * 2.0)
        } else sign
        startPlayback(rate)
        if (abs(rate) != 1.0 || rate < 0.0) showToast(if (rate < 0.0) "◀ ${abs(rate).roundToInt()}x" else "▶ ${rate.roundToInt()}x")
    }

    private fun startPlayback(rate: Double) {
        val s = _uiState.value
        if (s.durationSec <= 0.0) {
            showToast("Add clips to the timeline first")
            return
        }
        var from = s.playheadSec
        if (rate > 0.0 && from >= s.durationSec - 0.02) from = 0.0
        if (rate < 0.0 && from <= 0.02) from = s.durationSec
        _uiState.update { it.copy(isPlaying = true, shuttleRate = rate, playheadSec = from) }
        cueAudio(from)

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            var lastMs = System.currentTimeMillis()
            var tick = 0
            while (isActive && _uiState.value.isPlaying) {
                delay(16L) // ~60fps frame ticker
                val now = System.currentTimeMillis()
                val dt = ((now - lastMs) / 1000.0).coerceIn(0.0, 0.1)
                lastMs = now
                val cur = _uiState.value
                tick++
                // Refresh fades/automation a few times a second rather than every frame.
                if (tick % 6 == 0 && cur.shuttleRate == 1.0) previewAudio.updateVolumes(cur.resolvedClips, cur.playheadSec)
                val nextTime = cur.playheadSec + dt * cur.shuttleRate
                if (nextTime >= cur.durationSec) {
                    _uiState.update { it.copy(playheadSec = cur.durationSec, isPlaying = false, shuttleRate = 1.0) }
                    break
                } else if (nextTime <= 0.0) {
                    _uiState.update { it.copy(playheadSec = 0.0, isPlaying = false, shuttleRate = 1.0) }
                    break
                } else {
                    _uiState.update { it.copy(playheadSec = nextTime) }
                }
            }
            previewAudio.stop()
        }
    }

    fun pausePlayback() {
        playbackJob?.cancel()
        previewAudio.stop()
        _uiState.update { it.copy(isPlaying = false, shuttleRate = 1.0) }
    }

    /** Steps the playhead by whole frames of the project frame rate, pausing first. */
    fun stepFrames(frames: Int) {
        if (_uiState.value.isPlaying) pausePlayback()
        val s = _uiState.value
        val fps = max(1, s.projectSettings.fps)
        // Step from the frame the playhead is on, so repeated steps do not drift off the grid.
        val frame = Math.round(s.playheadSec * fps) + frames
        seekTo(frame.toDouble() / fps)
    }

    /** Moves the playhead to the previous / next cut (the start or end of any clip). */
    fun jumpToEditPoint(direction: Int) {
        val s = _uiState.value
        val target = if (direction < 0) TimelineOps.prevEditPoint(s.resolvedClips, s.playheadSec)
        else TimelineOps.nextEditPoint(s.resolvedClips, s.playheadSec)
        if (target == null) {
            showToast(if (direction < 0) "No earlier cut." else "No later cut.")
            return
        }
        seekTo(target)
    }

    // -------------------------------------------------------------------------
    // 1-CLICK PRO DEMO REEL GENERATOR
    // -------------------------------------------------------------------------
    fun loadProDemoReel() {
        // The demo is a project of its own, so loading it never replaces the user's edit.
        if (!createProject("Pro Demo Reel", announce = false)) return
        val projectId = _uiState.value.currentProjectId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val mediaDir = projectStore.mediaDir(projectId)
            val img1File = MediaEngine.createSyntheticDemoImageFile(
                File(mediaDir, "demo_scene01.png"),
                "SCENE 01 · ARRI ALEXA LF",
                "#102542",
                "#f87060"
            )
            val img2File = MediaEngine.createSyntheticDemoImageFile(
                File(mediaDir, "demo_scene02.png"),
                "SCENE 02 · ANAMORPHIC NIGHT",
                "#1b1438",
                "#00a896"
            )
            val pipFile = MediaEngine.createSyntheticDemoImageFile(
                File(mediaDir, "demo_broll_pip.png"),
                "B-ROLL · DRONE HUD",
                "#2d3142",
                "#ef8354"
            )
            val wavFile = MediaEngine.createSyntheticAudioWavFile(
                File(mediaDir, "demo_synth_score.wav"),
                durationSec = 6
            )

            val a1 = MediaAsset(
                id = generateUid(), name = "Scene01_AlexaLF.png", mimeType = "image/png",
                kind = AssetKind.IMAGE, sizeBytes = img1File.length(), durationSec = 3.0,
                localPath = img1File.absolutePath, bin = "Imported", favorite = true
            )
            val a2 = MediaAsset(
                id = generateUid(), name = "Scene02_Anamorphic.png", mimeType = "image/png",
                kind = AssetKind.IMAGE, sizeBytes = img2File.length(), durationSec = 3.0,
                localPath = img2File.absolutePath, bin = "Imported", favorite = false
            )
            val a3 = MediaAsset(
                id = generateUid(), name = "BRoll_Overlay.png", mimeType = "image/png",
                kind = AssetKind.IMAGE, sizeBytes = pipFile.length(), durationSec = 3.0,
                localPath = pipFile.absolutePath, bin = "B-roll", favorite = false
            )
            val a4 = MediaAsset(
                id = generateUid(), name = "Cinema_Synth_Score.wav", mimeType = "audio/wav",
                kind = AssetKind.AUDIO, sizeBytes = wavFile.length(), durationSec = 6.0,
                localPath = wavFile.absolutePath, bin = "Music", favorite = true,
                waveformPeaks = MediaEngine.extractAudioWaveformPeaks(wavFile, 240)
            )

            val c1 = TimelineClip(
                id = generateUid(), assetId = a1.id, track = TimelineTrack.VIDEO,
                startAt = 0.0, trimIn = 0.0, trimOut = 3.0,
                filters = ClipFilters(brightness = 102.0, contrast = 116.0, saturation = 108.0, temperature = 12.0, tint = 2.0, sharpen = 18.0, vignette = 20.0, grain = 10.0, liftR = 0.0, liftG = 2.0, liftB = 5.0, gammaR = 3.0, gammaG = 1.0, gammaB = -2.0, gainR = 6.0, gainG = 2.0, gainB = -4.0),
                rig = CameraRig(shutter = 180.0, aberration = 1.0, anamorphic = 12.0),
                equipmentName = "ARRI Alexa Mini LF · Cooke S4 35mm",
                scale = 105.0,
                keyframes = listOf(
                    Keyframe(time = 0.0, scale = 100.0, opacity = 100.0, rotation = 0.0, x = -2.0, y = 0.0),
                    Keyframe(time = 3.0, scale = 114.0, opacity = 100.0, rotation = 0.0, x = 2.0, y = 0.0)
                ),
                label = "Scene 01 · Alexa LF", transition = "fade", transitionDuration = 0.45
            )
            val c2 = TimelineClip(
                id = generateUid(), assetId = a2.id, track = TimelineTrack.VIDEO,
                startAt = 3.0, trimIn = 0.0, trimOut = 3.0,
                filters = ClipFilters(brightness = 98.0, contrast = 124.0, saturation = 115.0, temperature = -14.0, tint = 8.0, sharpen = 22.0, vignette = 24.0, grain = 14.0),
                rig = CameraRig(tiltX = 4.0, swivelY = -8.0, shutter = 200.0, aberration = 2.0, anamorphic = 18.0),
                equipmentName = "RED V-Raptor 8K · Atlas Orion 2x Anamorphic",
                scale = 108.0,
                keyframes = listOf(
                    Keyframe(time = 0.0, scale = 118.0, opacity = 100.0, rotation = -2.0, x = 4.0, y = 0.0),
                    Keyframe(time = 3.0, scale = 102.0, opacity = 100.0, rotation = 0.0, x = 0.0, y = 0.0)
                ),
                label = "Scene 02 · Anamorphic", transition = "dissolve", transitionDuration = 0.5
            )
            val cPip = TimelineClip(
                id = generateUid(), assetId = a3.id, track = TimelineTrack.OVERLAY,
                startAt = 1.2, trimIn = 0.0, trimOut = 2.6,
                filters = ClipFilters(brightness = 104.0, contrast = 110.0, saturation = 105.0),
                rig = CameraRig(tiltX = 6.0, swivelY = 10.0),
                cornerRadius = 12, blendMode = "screen",
                opacity = 92.0, scale = 36.0, x = 26.0, y = -22.0,
                label = "HUD B-Roll PiP", transition = "fade"
            )
            val cTxt = TimelineClip(
                id = generateUid(), assetId = null, track = TimelineTrack.TEXT,
                startAt = 0.3, trimIn = 0.0, trimOut = 4.8,
                text = "EDITPRO STUDIO 2.0\nCOLOUR · MOTION · MUSIC",
                fontSize = 38, fontFamily = "system-ui", fontWeight = 800,
                textColor = "#ffffff", textAlign = "center", verticalPosition = "bottom",
                strokeColor = "#000000", strokeWidth = 1f, shadow = true,
                label = "Main Title Card"
            )
            val cAud = TimelineClip(
                id = generateUid(), assetId = a4.id, track = TimelineTrack.AUDIO,
                startAt = 0.0, trimIn = 0.0, trimOut = 6.0,
                volume = 106.0, fadeInAudio = 0.4, fadeOutAudio = 0.8,
                audioEq = listOf(4.0, 2.0, 1.0, 3.0, 4.5),
                audioComp = 48.0, audioDeess = 20.0, audioReverb = 14.0,
                audioPresetName = "SSL 4000G Master Bus Glue",
                volumeKeyframes = listOf(
                    VolumePoint(0.0, 95.0),
                    VolumePoint(3.0, 110.0),
                    VolumePoint(6.0, 90.0)
                ),
                label = "Cinema Synth Score"
            )

            withContext(Dispatchers.Main) {
                // The user may have left the demo project while its media was being generated.
                if (_uiState.value.currentProjectId != projectId) return@withContext
                _uiState.update { s ->
                    val nextAssets = s.assets + listOf(a1, a2, a3, a4)
                    val nextClips = listOf(c1, c2, cPip, cTxt, cAud)
                    s.copy(
                        showHome = false,
                        assets = nextAssets,
                        clips = nextClips,
                        markers = listOf(
                            TimelineMarker(0.0, "Intro Cut"),
                            TimelineMarker(1.5, "Beat Drop"),
                            TimelineMarker(3.0, "Scene 02 Transition")
                        ),
                        selectedClipId = c1.id,
                        selectedClipIds = setOf(c1.id),
                        playheadSec = 0.8,
                        durationSec = 6.0,
                        saveStatus = "Pro Demo Reel loaded"
                    )
                }
                scheduleAutosave()
                showToast("Pro Demo Reel loaded! Switch between Normal, Colour, Motion & Music modes.")
            }
        }
    }

    // -------------------------------------------------------------------------
    // PROJECT HEALTH, FRAME CAPTURE, PROJECT PERSISTENCE & EXPORT
    // -------------------------------------------------------------------------
    fun runProjectHealthScan() {
        val s = _uiState.value
        val issues = mutableListOf<String>()
        val missing = s.clips.count { it.assetId != null && !s.assetsById.containsKey(it.assetId) }
        val zeroDur = s.clips.count { TimelineEngine.clipDuration(it, s.assetsById) <= 0.0 }
        val hasMainVisual = s.clips.any { it.track == TimelineTrack.VIDEO && it.assetId != null && s.assetsById.containsKey(it.assetId) }
        val largeAssets = s.assets.count { it.sizeBytes > 500L * 1024L * 1024L }
        // Media is referenced by path, not embedded in the project file, so a project moved
        // between devices can point at files that are no longer there.
        val missingFiles = s.assets.count { a ->
            val p = a.localPath
            p != null && !File(p).exists()
        }

        if (missing > 0) issues.add("$missing clip(s) reference missing media")
        if (missingFiles > 0) issues.add("$missingFiles media file(s) are no longer on this device — re-import them")
        if (zeroDur > 0) issues.add("$zeroDur clip(s) have invalid duration")
        if (!hasMainVisual) issues.add("No main VIDEO 1 visual clip; MP4 export recommends one")
        if (largeAssets > 0) issues.add("$largeAssets source file(s) exceed 500 MB")
        if (issues.isEmpty()) issues.add("No project-structure problems found")

        val report = buildString {
            issues.forEach { appendLine("• $it") }
            appendLine()
            append("Media: ${s.assets.size} · Timeline clips: ${s.clips.size} · Duration: ${formatTimecode(s.durationSec)}")
        }
        showAlert("FILMCRAFT PROJECT HEALTH", report)
    }

    fun clearTimeline() {
        if (_uiState.value.clips.isEmpty()) {
            showToast("Timeline is already empty.")
            return
        }
        snapHistory()
        _uiState.update {
            it.copy(
                clips = emptyList(),
                markers = emptyList(),
                selectedClipId = null,
                selectedClipIds = emptySet(),
                playheadSec = 0.0,
                durationSec = 0.0
            )
        }
        scheduleAutosave()
        showToast("Timeline cleared; media library preserved")
    }

    /** Resets the editor to an empty edit. Layout, presets and preferences are kept. */
    private fun resetEditor(name: String, settings: ProjectSettings, projectId: String?) {
        pausePlayback()
        history.clear()
        future.clear()
        _uiState.update {
            it.copy(
                currentProjectId = projectId,
                projectName = name,
                projectSettings = settings,
                exportSettings = exportSettingsFor(settings, it.exportSettings),
                assets = emptyList(),
                clips = emptyList(),
                markers = emptyList(),
                trackStates = TimelineTrack.entries.associateWith { TrackState() },
                selectedAssetId = null,
                selectedAssetIds = emptySet(),
                selectedClipId = null,
                selectedClipIds = emptySet(),
                clipClipboard = emptyList(),
                playheadSec = 0.0,
                durationSec = 0.0,
                canUndo = false,
                canRedo = false,
                renderQueue = emptyList()
            )
        }
    }

    /** Export settings whose aspect, resolution and frame rate match a project's canvas. */
    private fun exportSettingsFor(settings: ProjectSettings, base: ExportSettings): ExportSettings {
        val w = settings.width
        val h = settings.height
        val aspect = when {
            w == h -> "square"
            h > w -> "portrait"
            abs(w * 3.0 / 4.0 - h) < 2.0 -> "classic"
            else -> "landscape"
        }
        val longSide = when (aspect) {
            "square" -> w * 16 / 9
            "portrait" -> h
            else -> w
        }
        val resolution = when {
            longSide >= 1900 -> "1920x1080"
            longSide <= 900 -> "854x480"
            else -> "1280x720"
        }
        return base.copy(aspect = aspect, resolution = resolution, fps = settings.fps, rangeInSec = null, rangeOutSec = null)
    }

    /**
     * Creates a new stored project and opens it. The project being edited is saved first, so
     * nothing is discarded.
     */
    fun createProject(name: String, settings: ProjectSettings = ProjectSettings(), announce: Boolean = true): Boolean {
        closeCurrentProject()
        val created = runCatching { projectStore.create(name.trim().ifEmpty { "Untitled Project" }, settings) }.getOrNull()
        if (created == null) {
            showAlert("COULD NOT CREATE PROJECT", "The project folder could not be written. Check the device has free storage.")
            return false
        }
        resetEditor(created.name, settings, created.id)
        savedContent = contentOf(_uiState.value)
        prefs.edit().putString("last_project_id", created.id).apply()
        _uiState.update { it.copy(showHome = false, showNewProjectModal = false, saveStatus = "New project created") }
        refreshProjects()
        if (announce) showToast("Created \"${created.name}\"")
        return true
    }

    fun startNewProject() {
        createProject("Untitled Project")
    }

    /** Opens a stored project in the editor. */
    fun openProject(id: String) {
        if (id == _uiState.value.currentProjectId) {
            _uiState.update { it.copy(showHome = false) }
            return
        }
        closeCurrentProject()
        val raw = projectStore.load(id)
        val doc = raw?.let { runCatching { ProjectSerializer.deserializeProject(it) }.getOrNull() }
        if (doc == null) {
            refreshProjects()
            showAlert("COULD NOT OPEN PROJECT", "This project's file is missing or damaged.")
            return
        }
        resetEditor(doc.name, doc.settings, id)
        // Media removed from the library in an earlier session is deleted now, when no undo
        // step can still bring it back.
        runCatching {
            projectStore.pruneMedia(id, doc.assets.flatMap { listOfNotNull(it.localPath) + it.filmstripPaths }.toSet())
        }
        val dur = TimelineEngine.calcProjectDuration(doc.clips, doc.assets.associateBy { it.id })
        _uiState.update {
            it.copy(
                showHome = false,
                assets = doc.assets,
                clips = doc.clips,
                markers = doc.markers,
                trackStates = TimelineTrack.entries.associateWith { t -> doc.trackStates[t] ?: TrackState() },
                durationSec = dur,
                selectedClipId = doc.clips.firstOrNull()?.id,
                selectedClipIds = setOfNotNull(doc.clips.firstOrNull()?.id),
                saveStatus = "Opened · ${doc.name}"
            )
        }
        // What is now in the editor is exactly what is on disk.
        savedContent = contentOf(_uiState.value)
        prefs.edit().putString("last_project_id", id).apply()
    }

    /** Stops playback and writes the open project's pending changes. */
    private fun closeCurrentProject() {
        pausePlayback()
        flushSave()
    }

    /** Leaves the editor for the project list. */
    fun goHome() {
        closeCurrentProject()
        refreshProjects()
        _uiState.update { it.copy(showHome = true) }
    }

    fun renameProject(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        if (id == _uiState.value.currentProjectId) {
            setProjectName(clean)
            flushSave()
        } else if (!projectStore.rename(id, clean)) {
            showToast("Could not rename that project.")
        }
        refreshProjects()
    }

    fun duplicateProject(id: String) {
        if (id == _uiState.value.currentProjectId) flushSave()
        viewModelScope.launch {
            // Copying media can take a while for a large project.
            val copy = withContext(Dispatchers.IO) { projectStore.duplicate(id) }
            refreshProjects()
            showToast(if (copy != null) "Duplicated as \"${copy.name}\"" else "Could not duplicate that project.")
        }
    }

    fun requestDeleteProject(id: String) {
        val project = _uiState.value.projects.find { it.id == id } ?: return
        _uiState.update { it.copy(pendingConfirm = PendingConfirm.DeleteProject(id, project.name)) }
    }

    /** Deletes a stored project together with the media imported into it. */
    fun deleteProject(id: String) {
        if (id == _uiState.value.currentProjectId) {
            autosaveJob?.cancel()
            autosaveJob = null
            resetEditor("Untitled Project", ProjectSettings(), null)
            _uiState.update { it.copy(showHome = true) }
        }
        val ok = projectStore.delete(id)
        if (prefs.getString("last_project_id", null) == id) prefs.edit().remove("last_project_id").apply()
        refreshProjects()
        showToast(if (ok) "Project deleted" else "Could not delete that project.")
    }

    fun setShowNewProjectModal(show: Boolean) = _uiState.update { it.copy(showNewProjectModal = show) }

    /** The project as a portable `.filmcraft` document, for "Export project file". */
    fun exportProjectJson(): String {
        val s = _uiState.value
        return ProjectSerializer.serializeProject(
            name = s.projectName,
            settings = s.projectSettings,
            clips = s.clips,
            markers = s.markers,
            assets = s.assets,
            trackStates = s.trackStates
        )
    }

    /**
     * Imports a `.filmcraft` file as a new project and opens it. Media the file refers to that
     * is still on this device is copied into the new project.
     */
    fun openProjectJson(rawJson: String) {
        closeCurrentProject()
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) { projectStore.importDocument(rawJson) }
            if (created == null) {
                showToast("Could not open project: the file is not a valid EditPro project.")
                return@launch
            }
            refreshProjects()
            openProject(created.id)
            showToast("Project imported: ${created.name}")
        }
    }

    fun saveCurrentClipAsStylePreset(name: String) {
        val c = _uiState.value.selectedClip ?: run { showToast("Select a timeline clip first."); return }
        val clean = name.trim()
        if (clean.isEmpty()) return
        _uiState.update { it.copy(customStylePresets = it.customStylePresets + (clean to c.filters)) }
        showToast("Saved style preset: $clean")
    }

    fun saveCurrentClipAsMotionPreset(name: String) {
        val s = _uiState.value
        val c = s.selectedClip ?: return
        if (c.keyframes.isEmpty()) { showToast("Add keyframes first"); return }
        val clean = name.trim()
        if (clean.isEmpty()) return
        val dur = max(0.1, TimelineEngine.clipDuration(c, s.assetsById))
        val baseScale = c.scale / 100.0
        val steps = c.keyframes.map { k ->
            MotionKeyframeStep(
                timeSec = k.time / dur,
                scale = k.scale / baseScale,
                opacity = k.opacity,
                rotation = k.rotation,
                x = k.x,
                y = k.y
            )
        }
        _uiState.update { it.copy(customMotionPresets = it.customMotionPresets + (clean to steps)) }
        showToast("Saved motion preset: $clean")
    }

    fun saveCurrentClipAsEquipmentRig(name: String) {
        val c = _uiState.value.selectedClip ?: run { showToast("Select a clip first"); return }
        val clean = name.trim()
        if (clean.isEmpty()) return
        val rig = EquipmentRigPreset(
            name = clean,
            category = "custom",
            scale = c.scale,
            filters = c.filters,
            rig = c.rig
        )
        _uiState.update { it.copy(customEquipmentRigs = it.customEquipmentRigs + (clean to rig)) }
        showToast("Saved equipment rig: $clean")
    }

    fun hotImportPresetBundleJson(rawJson: String) {
        try {
            val obj = JSONObject(rawJson)
            var count = 0
            val newStyles = _uiState.value.customStylePresets.toMutableMap()
            obj.optJSONObject("styles")?.let { stylesObj ->
                for (key in stylesObj.keys()) {
                    val item = stylesObj.optJSONObject(key)
                    val filterJson = item?.optJSONObject("style")?.optJSONObject("filters") ?: item?.optJSONObject("filters")
                    if (filterJson != null) {
                        newStyles[key] = ProjectSerializer.jsonToFilters(filterJson)
                        count++
                    }
                }
            }
            val newEq = _uiState.value.customEquipmentRigs.toMutableMap()
            obj.optJSONObject("equipment")?.let { eqObj ->
                for (key in eqObj.keys()) {
                    val item = eqObj.optJSONObject(key) ?: continue
                    newEq[key] = EquipmentRigPreset(
                        name = key,
                        category = item.optString("category", "custom"),
                        scale = item.optDouble("scale", 100.0),
                        filters = item.optJSONObject("filters")?.let { ProjectSerializer.jsonToFilters(it) },
                        rig = ProjectSerializer.jsonToRig(item.optJSONObject("rig"))
                    )
                    count++
                }
            }
            val newAud = _uiState.value.customAudioGear.toMutableMap()
            obj.optJSONObject("audioPresets")?.let { audObj ->
                for (key in audObj.keys()) {
                    val item = audObj.optJSONObject(key) ?: continue
                    val eqArr = item.optJSONArray("eq")
                    val eqList = if (eqArr != null && eqArr.length() == 5) {
                        (0 until 5).map { eqArr.optDouble(it, 0.0) }
                    } else listOf(0.0, 0.0, 0.0, 0.0, 0.0)
                    newAud[key] = AudioGearPreset(
                        name = key,
                        eq = eqList,
                        comp = item.optDouble("comp", 0.0),
                        pan = item.optDouble("pan", 0.0),
                        deess = item.optDouble("deess", 0.0),
                        reverb = item.optDouble("reverb", 0.0),
                        volume = item.optDouble("volume", 100.0)
                    )
                    count++
                }
            }
            _uiState.update {
                it.copy(
                    customStylePresets = newStyles,
                    customEquipmentRigs = newEq,
                    customAudioGear = newAud
                )
            }
            showToast("Hot-imported $count preset & equipment item(s) live!")
        } catch (e: Exception) {
            showToast("Invalid preset pack JSON: ${e.message}")
        }
    }

    fun loadBuiltinExpansionPack() {
        val packJson = """
        {
          "format": "editpro-preset-bundle",
          "version": 2,
          "styles": {
            "Kodak 2383 Print Film": {"style":{"filters":{"brightness":98,"contrast":122,"saturation":94,"temperature":12,"tint":4,"vignette":22,"grain":24,"liftR":-2,"liftB":6,"gainR":8,"gainB":-4}}},
            "Fuji Eterna 250D Soft": {"style":{"filters":{"brightness":103,"contrast":96,"saturation":90,"temperature":-4,"tint":-8,"vignette":14,"grain":16}}},
            "Oppenheimer IMAX B&W": {"style":{"filters":{"brightness":96,"contrast":148,"saturation":0,"sharpen":32,"vignette":28,"grain":22}}},
            "Dune Arrakis Spice Gold": {"style":{"filters":{"brightness":102,"contrast":126,"saturation":88,"temperature":44,"tint":8,"vignette":26,"sharpen":20}}}
          },
          "equipment": {
            "ARRI Alexa 65 · Prime DNA 50mm": {"category":"cinema","scale":104,"filters":{"brightness":100,"contrast":118,"saturation":96,"temperature":4,"vignette":18,"sharpen":24},"rig":{"tiltX":0,"swivelY":0,"shutter":180,"aberration":1,"anamorphic":0}},
            "Sony FX3 · Sirui Saturn Anamorphic": {"category":"cinema","scale":108,"filters":{"brightness":99,"contrast":116,"saturation":110,"temperature":-8,"tint":6,"vignette":20},"rig":{"tiltX":0,"swivelY":0,"shutter":180,"aberration":2.5,"anamorphic":20}},
            "VHS 1994 Camcorder Deck": {"category":"vintage","scale":104,"filters":{"brightness":104,"contrast":112,"saturation":128,"temperature":10,"tint":16,"grain":48,"blur":0.8},"rig":{"tiltX":0,"swivelY":0,"shutter":120,"aberration":6,"anamorphic":0}}
          },
          "audioPresets": {
            "Dolby Atmos Cinema Dialogue": {"eq":[-4,2,4.5,3.5,2.5],"comp":58,"pan":0,"deess":42,"reverb":12,"volume":112},
            "808 Trap Sub & Crisp Hats": {"eq":[8.5,2,-3,3.5,7],"comp":64,"pan":0,"deess":15,"reverb":8,"volume":108},
            "Warm Vinyl Documentary Bed": {"eq":[-2,3.5,1,-2,-5],"comp":34,"pan":0,"deess":20,"reverb":16,"volume":92}
          }
        }
        """.trimIndent()
        hotImportPresetBundleJson(packJson)
    }

    // -------------------------------------------------------------------------
    // EXPORT & RENDER QUEUE
    // -------------------------------------------------------------------------
    fun updateExportSettings(transform: (ExportSettings) -> ExportSettings) {
        _uiState.update { st ->
            val next = transform(st.exportSettings)
            // The preview stage follows the export frame, so what is framed is what is rendered.
            val (w, h) = next.copy(androidLowRamGuard = false).resolvedDimensions()
            st.copy(exportSettings = next, projectSettings = ProjectSettings(w, h, next.fps))
        }
        scheduleAutosave()
    }

    fun clearExportRange() {
        _uiState.update { it.copy(exportSettings = it.exportSettings.copy(rangeInSec = null, rangeOutSec = null)) }
        showToast("Export range cleared: the whole timeline will be rendered")
    }

    fun setExportRangePointAtPlayhead(isInPoint: Boolean) {
        val t = (_uiState.value.playheadSec * 100.0).roundToInt() / 100.0
        updateExportSettings { cur ->
            if (isInPoint) cur.copy(rangeInSec = t) else cur.copy(rangeOutSec = t)
        }
        showToast("${if (isInPoint) "In" else "Out"} point set to ${formatTimecode(t)}")
    }

    fun queueCurrentExport() {
        val s = _uiState.value
        val job = RenderJob(
            name = s.projectName,
            clips = s.clips,
            markers = s.markers,
            settings = s.exportSettings
        )
        _uiState.update { it.copy(renderQueue = it.renderQueue + job) }
        showToast("Current project added to render queue (${_uiState.value.renderQueue.size})")
    }

    /** Stops an in-flight render (or render queue). The partial MP4 is left in the cache
     *  and is overwritten by the next render, which deletes any existing output first. */
    fun cancelExport() {
        if (!_uiState.value.isExporting) return
        exportJob?.cancel()
    }

    fun startExportNow() {
        val s = _uiState.value
        if (s.isExporting) return
        pausePlayback()
        _uiState.update {
            it.copy(isExporting = true, exportProgressPct = 1, exportStatusText = "Starting render…")
        }
        exportJob = viewModelScope.launch {
            try {
                val result = NativeVideoExporter.exportProjectToMp4(
                    context = getApplication(),
                    projectName = s.projectName,
                    clips = s.clips,
                    assetsById = s.assetsById,
                    trackStates = s.trackStates,
                    settings = s.exportSettings,
                    onProgress = { pct, msg ->
                        _uiState.update { it.copy(exportProgressPct = pct, exportStatusText = msg) }
                    }
                )
                // Publish the finished file into the shared Movies collection so the Photos /
                // Gallery app can see it (app-private storage is invisible to the gallery).
                var finalMessage = result.message
                if (result.success) {
                    result.outputFile?.let { f ->
                        val note = withContext(Dispatchers.IO) {
                            GalleryPublisher.publish(getApplication(), f, f.name)
                        }
                        finalMessage = "${result.message} · $note"
                    }
                }
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgressPct = if (result.success) 100 else 0,
                        exportStatusText = finalMessage,
                        // Keep the previous delivered file if this render did not produce one.
                        lastExportedFilePath = result.outputFile?.absolutePath ?: it.lastExportedFilePath
                    )
                }
                showToast(finalMessage)
            } catch (ce: CancellationException) {
                // The render was cancelled: always clear the busy flag so the UI cannot get stuck.
                withContext(NonCancellable) {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportProgressPct = 0,
                            exportStatusText = "Export cancelled."
                        )
                    }
                    showToast("Export cancelled.")
                }
                throw ce
            } finally {
                exportJob = null
            }
        }
    }

    fun runRenderQueue() {
        val queue = _uiState.value.renderQueue
        if (queue.isEmpty() || _uiState.value.isExporting) return
        _uiState.update {
            it.copy(renderQueue = emptyList(), isExporting = true, exportProgressPct = 1)
        }
        exportJob = viewModelScope.launch {
            try {
                val s = _uiState.value
                var succeeded = 0
                var failed = 0
                var lastPath: String? = null
                for ((index, job) in queue.withIndex()) {
                    val result = NativeVideoExporter.exportProjectToMp4(
                        context = getApplication(),
                        projectName = "${job.name}_render${index + 1}",
                        clips = job.clips,
                        assetsById = s.assetsById,
                        trackStates = s.trackStates,
                        settings = job.settings,
                        onProgress = { pct, msg ->
                            _uiState.update {
                                it.copy(
                                    exportProgressPct = pct,
                                    exportStatusText = "Job ${index + 1}/${queue.size}: $msg"
                                )
                            }
                        }
                    )
                    if (result.success) {
                        succeeded++
                        lastPath = result.outputFile?.absolutePath
                        result.outputFile?.let { f ->
                            withContext(Dispatchers.IO) {
                                GalleryPublisher.publish(getApplication(), f, f.name)
                            }
                        }
                    } else {
                        failed++
                    }
                }
                val summary = "Render queue finished: $succeeded succeeded, $failed failed."
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgressPct = 100,
                        exportStatusText = summary,
                        lastExportedFilePath = lastPath ?: it.lastExportedFilePath
                    )
                }
                showToast(summary)
            } catch (ce: CancellationException) {
                withContext(NonCancellable) {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportProgressPct = 0,
                            exportStatusText = "Render queue cancelled."
                        )
                    }
                    showToast("Render queue cancelled.")
                }
                throw ce
            } finally {
                exportJob = null
            }
        }
    }

    // Modal visibility helpers
    fun setShowExportModal(show: Boolean) = _uiState.update {
        if (show && !it.isExporting) {
            // Opening the sheet should offer a fresh export, not re-show the last summary.
            it.copy(showExportModal = true, exportProgressPct = 0)
        } else {
            it.copy(showExportModal = show)
        }
    }
    fun setShowToolsModal(show: Boolean) = _uiState.update { it.copy(showToolsModal = show) }
    fun setShowSolidModal(show: Boolean) = _uiState.update { it.copy(showSolidModal = show) }
    fun setShowPresetsModal(show: Boolean) = _uiState.update { it.copy(showPresetsModal = show) }
    fun setShowShortcutsModal(show: Boolean) = _uiState.update { it.copy(showShortcutsModal = show) }

    // -------------------------------------------------------------------------
    // DESTRUCTIVE-ACTION GUARD
    // Deleting a project or removing media asks first. The request is held as data (never a
    // lambda) so it survives recomposition cleanly.
    // -------------------------------------------------------------------------
    fun clearConfirm() {
        _uiState.update { it.copy(pendingConfirm = null) }
    }

    fun confirmPending() {
        val action = _uiState.value.pendingConfirm ?: return
        clearConfirm()
        when (action) {
            is PendingConfirm.DeleteProject -> deleteProject(action.id)
            is PendingConfirm.DeleteAssets -> deleteAssets(action.ids)
            PendingConfirm.ResetShortcuts -> resetAllShortcuts()
        }
    }

    override fun onCleared() {
        // Last chance to persist: the debounced autosave is cancelled with the scope.
        runCatching { flushSave() }
        previewAudio.stop()
        mcpServer?.stop()
        mcpServer = null
        super.onCleared()
    }

    // -------------------------------------------------------------------------
    // CLIP CONTEXT MENU (user-configurable)
    // -------------------------------------------------------------------------
    fun toggleClipMenuItem(id: String) {
        val current = _uiState.value.clipMenuOrder
        val next = if (id in current) current - id else current + id
        _uiState.update { it.copy(clipMenuOrder = next) }
        persistClipMenu(next)
    }

    fun moveClipMenuItem(id: String, delta: Int) {
        val current = _uiState.value.clipMenuOrder.toMutableList()
        val index = current.indexOf(id)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, current.lastIndex)
        if (target == index) return
        current.removeAt(index)
        current.add(target, id)
        val next = current.toList()
        _uiState.update { it.copy(clipMenuOrder = next) }
        persistClipMenu(next)
    }

    fun resetClipMenu() {
        val next = ClipMenuAction.defaultOrder
        _uiState.update { it.copy(clipMenuOrder = next) }
        persistClipMenu(next)
        showToast("Clip menu reset to defaults")
    }

    fun setShowClipMenuModal(show: Boolean) = _uiState.update { it.copy(showClipMenuModal = show) }

    fun toggleScrubFollow() {
        val next = !_uiState.value.scrubFollow
        _uiState.update { it.copy(scrubFollow = next) }
        prefs.edit().putBoolean("scrub_follow", next).apply()
        showToast(if (next) "Playhead follows the cursor" else "Scrub-follow off")
    }

    private fun loadClipMenu() {
        val raw = prefs.getString("clip_menu_order", null) ?: return
        var ids = raw.split(",").map { it.trim() }.filter { ClipMenuAction.fromId(it) != null }
        if (prefs.getInt("clip_menu_version", 1) < 3) {
            ids = ClipMenuAction.upgradeSavedOrder(ids)
            prefs.edit().putInt("clip_menu_version", 3).putString("clip_menu_order", ids.joinToString(",")).apply()
        }
        _uiState.update { it.copy(clipMenuOrder = ids) }
    }

    private fun persistClipMenu(order: List<String>) {
        prefs.edit().putString("clip_menu_order", order.joinToString(",")).putInt("clip_menu_version", 3).apply()
    }

    private companion object {
        const val AGENT_KEY = "agent_api_key"
    }

    // -------------------------------------------------------------------------
    // AGENT ACCESS (MCP)
    // -------------------------------------------------------------------------
    fun startMcpServer(): Boolean {
        if (_uiState.value.mcpRunning) return true
        val token = _uiState.value.mcpToken.ifBlank { generateMcpToken() }
        val server = McpServer(
            port = _uiState.value.mcpPort,
            token = token,
            tools = McpTools(this),
            onLog = { message -> appendMcpLog(message) }
        )
        val started = server.start()
        if (started) {
            mcpServer = server
            prefs.edit().putString("mcp_token", token).apply()
            _uiState.update { it.copy(mcpRunning = true, mcpToken = token) }
            appendMcpLog("Agent access on \u00b7 http://127.0.0.1:${_uiState.value.mcpPort}/mcp")
        } else {
            appendMcpLog("Could not start on port ${_uiState.value.mcpPort}")
        }
        return started
    }

    fun stopMcpServer() {
        mcpServer?.stop()
        mcpServer = null
        _uiState.update { it.copy(mcpRunning = false) }
        runCatching { McpService.stop(getApplication()) }
    }

    /**
     * Turns agent access on or off for good: the choice is persisted and, when on, a foreground
     * service keeps the socket reachable while the editor is off screen.
     */
    fun setAgentAccessEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("mcp_auto_start", enabled).apply()
        _uiState.update { it.copy(mcpAutoStart = enabled) }
        val app = getApplication<Application>()
        if (enabled) {
            runCatching { McpService.start(app) }
            startMcpServer()
        } else {
            stopMcpServer()
        }
    }

    fun regenerateMcpToken() {
        val token = generateMcpToken()
        prefs.edit().putString("mcp_token", token).apply()
        _uiState.update { it.copy(mcpToken = token) }
        if (_uiState.value.mcpRunning) {
            stopMcpServer()
            startMcpServer()
        }
        showToast("New pairing token generated")
    }

    private fun loadMcpToken() {
        val stored = prefs.getString("mcp_token", null)
        // Tokens shorter than 16 characters predate tunnel support and are too weak to face the
        // internet, so they are replaced on upgrade.
        val token = stored?.takeIf { it.length >= 16 } ?: generateMcpToken()
        if (stored != token) prefs.edit().putString("mcp_token", token).apply()
        val autoStart = prefs.getBoolean("mcp_auto_start", false)
        _uiState.update { it.copy(mcpToken = token, mcpAutoStart = autoStart) }
    }

    /**
     * 24 characters (~120 bits). Longer than the original 8 because the endpoint may be reachable
     * through a tunnel, where the token is the only thing guarding the project.
     */
    private fun generateMcpToken(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toList()
        return (1..24).map { chars.random() }.joinToString("")
    }

    private fun appendMcpLog(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _uiState.update { it.copy(mcpLog = (listOf("$stamp  $message") + it.mcpLog).take(40)) }
    }

    // --- clip operations the MCP tools drive. All go through snapHistory, so they are undoable. ---
    fun mcpAddClip(
        assetId: String,
        trackKey: String?,
        startSec: Double?,
        trimIn: Double?,
        trimOut: Double?
    ): String? {
        val state = _uiState.value
        val asset = state.assetsById[assetId] ?: return null
        val track = trackKey?.let { key -> TimelineTrack.entries.find { it.key == key } }
        val before = state.clips.map { it.id }.toSet()
        addAssetToTimeline(asset, track, startSec)
        val created = _uiState.value.clips.lastOrNull { it.id !in before } ?: return null
        if (trimIn != null || trimOut != null) {
            _uiState.update { st ->
                st.copy(
                    clips = st.clips.map {
                        if (it.id == created.id) {
                            it.copy(trimIn = trimIn ?: it.trimIn, trimOut = trimOut ?: it.trimOut)
                        } else it
                    }
                )
            }
            recalcAndSync()
        }
        return created.id
    }

    fun mcpUpdateClip(clipId: String, apply: (TimelineClip) -> TimelineClip): Boolean {
        if (_uiState.value.clips.none { it.id == clipId }) return false
        snapHistory()
        _uiState.update { st ->
            st.copy(clips = st.clips.map { if (it.id == clipId) apply(it) else it })
        }
        recalcAndSync()
        return true
    }

    fun mcpDeleteClip(clipId: String): Boolean {
        if (_uiState.value.clips.none { it.id == clipId }) return false
        snapHistory()
        _uiState.update { st ->
            st.copy(
                clips = st.clips.filterNot { it.id == clipId },
                selectedClipId = if (st.selectedClipId == clipId) null else st.selectedClipId,
                selectedClipIds = st.selectedClipIds - clipId
            )
        }
        recalcAndSync("Clip deleted")
        return true
    }

    fun mcpSplitClip(clipId: String, atSec: Double): Boolean {
        if (_uiState.value.clips.none { it.id == clipId }) return false
        selectClip(clipId, false)
        seekTo(atSec)
        splitAtPlayhead()
        return true
    }

    fun mcpApplyLook(clipId: String, presetId: String): Boolean {
        val preset = PresetCatalog.lookPresets.find { it.id == presetId } ?: return false
        if (_uiState.value.clips.none { it.id == clipId }) return false
        selectClip(clipId, false)
        applyLookPreset(preset)
        return true
    }

    fun mcpAddKeyframe(clipId: String, timeSec: Double): Boolean {
        if (_uiState.value.clips.none { it.id == clipId }) return false
        selectClip(clipId, false)
        seekTo(timeSec)
        addOrUpdateKeyframeAtPlayhead()
        return true
    }

    fun mcpSetTrackState(
        trackKey: String,
        muted: Boolean?,
        hidden: Boolean?,
        solo: Boolean?,
        locked: Boolean?,
        collapsed: Boolean? = null
    ): Boolean {
        val track = TimelineTrack.entries.find { it.key == trackKey } ?: return false
        _uiState.update { st ->
            val current = st.trackStates[track] ?: TrackState()
            st.copy(
                trackStates = st.trackStates + (track to current.copy(
                    muted = muted ?: current.muted,
                    hidden = hidden ?: current.hidden,
                    solo = solo ?: current.solo,
                    locked = locked ?: current.locked,
                    collapsed = collapsed ?: current.collapsed
                ))
            )
        }
        // Track states are part of the project file.
        scheduleAutosave()
        return true
    }

    fun mcpSetPlayback(playing: Boolean) {
        val isPlaying = _uiState.value.isPlaying
        if (playing && !isPlaying) togglePlayPause()
        if (!playing && isPlaying) pausePlayback()
    }

    // -------------------------------------------------------------------------
    // BUILT-IN AGENT (bring your own key)
    // -------------------------------------------------------------------------
    fun loadAgentSettings() {
        val baseUrl = prefs.getString("agent_base_url", "https://api.openai.com/v1")
            ?: "https://api.openai.com/v1"
        val model = prefs.getString("agent_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        val hasKey = !SecureStore.get(getApplication(), AGENT_KEY).isNullOrBlank()
        _uiState.update { it.copy(agentBaseUrl = baseUrl, agentModel = model, agentHasKey = hasKey) }
    }

    fun setAgentBaseUrl(url: String) {
        val clean = url.trim()
        prefs.edit().putString("agent_base_url", clean).apply()
        _uiState.update { it.copy(agentBaseUrl = clean) }
    }

    fun setAgentModel(model: String) {
        val clean = model.trim()
        prefs.edit().putString("agent_model", clean).apply()
        _uiState.update { it.copy(agentModel = clean) }
    }

    fun saveAgentKey(key: String) {
        val clean = key.trim()
        if (clean.isEmpty()) return
        SecureStore.put(getApplication(), AGENT_KEY, clean)
        _uiState.update { it.copy(agentHasKey = true) }
        showToast("API key saved securely")
    }

    fun clearAgentKey() {
        SecureStore.clear(getApplication(), AGENT_KEY)
        _uiState.update { it.copy(agentHasKey = false) }
        showToast("API key removed")
    }

    fun setShowAgentChat(show: Boolean) {
        _uiState.update { it.copy(showAgentChat = show) }
    }

    fun clearAgentChat() {
        agentEngine.reset()
        _uiState.update { it.copy(agentMessages = emptyList(), agentStatus = "") }
    }

    fun stopAgent() {
        agentJob?.cancel()
    }

    fun sendAgentMessage(text: String) {
        val message = text.trim()
        val state = _uiState.value
        if (message.isEmpty() || state.agentRunning) return

        if (!state.agentHasKey) {
            appendAgent("error", "Add an API key in agent settings first.")
            return
        }
        if (state.agentBaseUrl.isBlank() || state.agentModel.isBlank()) {
            appendAgent("error", "Set the base URL and model name in agent settings first.")
            return
        }

        _uiState.update {
            it.copy(
                agentMessages = it.agentMessages + AgentMessage("user", message),
                agentRunning = true,
                agentStatus = "Thinking..."
            )
        }

        val client = OpenAiClient(state.agentBaseUrl, SecureStore.get(getApplication(), AGENT_KEY).orEmpty(), state.agentModel)

        // IO, not Main: tool handlers hop to Main internally, and blocking Main on itself would deadlock.
        agentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                agentEngine.send(client, message) { event ->
                    when (event) {
                        is AgentEngine.Event.Thinking ->
                            _uiState.update { st ->
                                st.copy(agentStatus = if (event.step <= 1) "Thinking..." else "Step ${event.step}...")
                            }
                        is AgentEngine.Event.ToolStarted ->
                            _uiState.update { st ->
                                st.copy(
                                    agentStatus = "Running ${event.name}...",
                                    agentMessages = st.agentMessages + AgentMessage("tool", event.name)
                                )
                            }
                        is AgentEngine.Event.ToolFinished ->
                            _uiState.update { st ->
                                st.copy(agentMessages = st.agentMessages + AgentMessage("toolResult", event.summary))
                            }
                        is AgentEngine.Event.Assistant ->
                            _uiState.update { st ->
                                st.copy(agentMessages = st.agentMessages + AgentMessage("assistant", event.text))
                            }
                    }
                }
                _uiState.update { it.copy(agentRunning = false, agentStatus = "") }
            } catch (ce: CancellationException) {
                withContext(NonCancellable) {
                    _uiState.update {
                        it.copy(
                            agentRunning = false,
                            agentStatus = "",
                            agentMessages = it.agentMessages + AgentMessage("error", "Stopped.")
                        )
                    }
                }
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        agentRunning = false,
                        agentStatus = "",
                        agentMessages = it.agentMessages + AgentMessage(
                            "error",
                            e.message ?: e.javaClass.simpleName
                        )
                    )
                }
            } finally {
                agentJob = null
            }
        }
    }

    private fun appendAgent(role: String, text: String) {
        _uiState.update { it.copy(agentMessages = it.agentMessages + AgentMessage(role, text)) }
    }

    /** Adds an asset produced by a web fetch to the library, ready for add_clip. */
    fun mcpAddFetchedAsset(asset: MediaAsset) {
        val owned = ensureProjectId(force = true)?.let { projectStore.adoptAsset(it, asset) } ?: asset
        snapHistory()
        _uiState.update { st ->
            st.copy(
                assets = st.assets + owned,
                selectedAssetId = owned.id,
                selectedAssetIds = setOf(owned.id),
                saveStatus = "Fetched ${owned.name}"
            )
        }
        scheduleAutosave()
    }

    /** Adds a motion graphic for the assistant; returns the new clip id. */
    fun mcpAddGraphic(templateId: String, startSec: Double?, durationSec: Double?, text: String?): String? {
        val before = _uiState.value.clips.map { it.id }.toSet()
        if (startSec != null) seekTo(startSec)
        addMotionGraphic(templateId)
        val created = _uiState.value.clips.lastOrNull { it.id !in before } ?: return null
        if (durationSec != null || text != null) {
            _uiState.update { st ->
                st.copy(clips = st.clips.map {
                    if (it.id == created.id) {
                        it.copy(trimOut = durationSec?.coerceIn(0.2, 600.0) ?: it.trimOut, text = text ?: it.text)
                    } else it
                })
            }
            recalcAndSync()
        }
        return created.id
    }

    /** Runs one of the multi-clip timeline edits on [clipIds] for the assistant. */
    fun mcpEditClips(clipIds: Set<String>, operation: String, atSec: Double?): Boolean {
        val known = _uiState.value.clips.map { it.id }.toSet()
        val ids = clipIds.filter { it in known }.toSet()
        if (ids.isEmpty() && operation != "paste" && operation != "close_gaps") return false
        if (ids.isNotEmpty()) selectClipsByIds(ids)
        if (atSec != null) seekTo(atSec)
        when (operation) {
            "ripple_delete" -> rippleDeleteSelectedClip()
            "duplicate" -> duplicateSelectedClips()
            "copy" -> copySelectedClips()
            "cut" -> cutSelectedClips()
            "paste" -> pasteClips()
            "close_gaps" -> closeGaps()
            "detach_audio" -> detachAudioFromSelection()
            "trim_start" -> trimToPlayhead(keepTail = true, ripple = false)
            "trim_end" -> trimToPlayhead(keepTail = false, ripple = false)
            "ripple_trim_start" -> trimToPlayhead(keepTail = true, ripple = true)
            "ripple_trim_end" -> trimToPlayhead(keepTail = false, ripple = true)
            else -> return false
        }
        return true
    }

    /** Moves clips together by [deltaSec] for the assistant; returns the distance actually moved. */
    fun mcpMoveClips(clipIds: Set<String>, deltaSec: Double): Double {
        val known = _uiState.value.clips.map { it.id }.toSet()
        val ids = clipIds.filter { it in known }.toSet()
        if (ids.isEmpty()) return 0.0
        snapHistory()
        return moveSelectionBy(deltaSec, ids)
    }

    /** Sets or edits a keyframe on a clip for the assistant; [timeSec] is on the timeline. */
    fun mcpSetKeyframe(clipId: String, timeSec: Double, patch: KeyframePatch): Boolean {
        val s = _uiState.value
        val rc = s.resolvedClips.find { it.clip.id == clipId } ?: return false
        val localT = (timeSec - rc.start).coerceIn(0.0, rc.duration)
        snapHistory()
        replaceClip(KeyframeOps.upsert(rc.clip, localT, patch), "Keyframe set")
        return true
    }

    fun mcpRemoveKeyframe(clipId: String, timeSec: Double): Boolean {
        val s = _uiState.value
        val rc = s.resolvedClips.find { it.clip.id == clipId } ?: return false
        val index = KeyframeOps.indexAt(rc.clip, (timeSec - rc.start).coerceIn(0.0, rc.duration))
        if (index < 0) return false
        snapHistory()
        replaceClip(KeyframeOps.remove(rc.clip, index), "Keyframe removed")
        return true
    }

    // -------------------------------------------------------------------------
    // MOTION GRAPHICS
    // -------------------------------------------------------------------------
    fun setShowGraphicsModal(show: Boolean) = _uiState.update { it.copy(showGraphicsModal = show) }

    /**
     * Adds a motion-graphics clip at the playhead (or across the whole edit, for templates such
     * as bars and progress). It uses the template's track when that is free, else the other
     * title track, else the first free position after the playhead.
     */
    fun addMotionGraphic(templateId: String) {
        val template: MgTemplate = MotionGraphics.find(templateId) ?: run {
            showToast("Unknown motion graphic.")
            return
        }
        val s = _uiState.value
        val draft = MotionGraphics.createClip(template, s.playheadSec, s.durationSec)
        val dur = TimelineEngine.clipDuration(draft, s.assetsById)
        val candidates = (listOf(template.track) + TimelineTrack.entries.filter { it.isTitle && it != template.track })
            .filter { s.trackStates[it]?.locked != true }
        if (candidates.isEmpty()) {
            showToast("The graphics and text tracks are locked.")
            return
        }
        val free = candidates.firstOrNull { t ->
            s.resolvedClips.none { it.clip.track == t && it.start < draft.startAt + dur - 0.001 && it.end > draft.startAt + 0.001 }
        }
        val track = free ?: candidates.first()
        val start = if (free != null) draft.startAt else TimelineEngine.resolveTimelineStart(
            desired = draft.startAt, duration = dur, track = track, clipId = draft.id,
            scalePxPerSec = 0.0, direction = 1.0, allClips = s.clips, assetsById = s.assetsById,
            playhead = s.playheadSec, magneticSnap = false
        )
        val clip = draft.copy(track = track, startAt = start)
        snapHistory()
        _uiState.update {
            it.copy(
                clips = it.clips + clip,
                selectedClipId = clip.id,
                selectedClipIds = setOf(clip.id),
                activeInspectorTab = InspectorTab.CLIP,
                showGraphicsModal = false
            )
        }
        recalcAndSync("Motion graphic added")
        showToast("Added ${template.title} to ${track.label}")
    }

    // -------------------------------------------------------------------------
    // PANELS
    // -------------------------------------------------------------------------
    fun togglePanel(panel: WorkspacePanel) {
        _uiState.update {
            when (panel) {
                WorkspacePanel.LIBRARY -> it.copy(libraryCollapsed = !it.libraryCollapsed, previewMaximized = false)
                WorkspacePanel.INSPECTOR -> it.copy(inspectorCollapsed = !it.inspectorCollapsed, previewMaximized = false)
                WorkspacePanel.TIMELINE -> it.copy(timelineCollapsed = !it.timelineCollapsed, previewMaximized = false)
            }
        }
        val s = _uiState.value
        prefs.edit()
            .putBoolean("panel_library_collapsed", s.libraryCollapsed)
            .putBoolean("panel_inspector_collapsed", s.inspectorCollapsed)
            .putBoolean("panel_timeline_collapsed", s.timelineCollapsed)
            .apply()
    }

    /** Hides every panel so the preview fills the window; the panels' own state is kept. */
    fun toggleMaximizePreview() {
        _uiState.update { it.copy(previewMaximized = !it.previewMaximized) }
    }

    // -------------------------------------------------------------------------
    // KEYBOARD SHORTCUTS
    // -------------------------------------------------------------------------
    private fun loadShortcutBindings() {
        val raw = prefs.getString("shortcut_overrides", null) ?: return
        runCatching {
            val obj = JSONObject(raw)
            val map = obj.keys().asSequence().associateWith { obj.optString(it, "") }
            _uiState.update { it.copy(bindings = ShortcutBindings(map)) }
        }
    }

    private fun applyBindings(bindings: ShortcutBindings) {
        val obj = JSONObject()
        bindings.overrides.forEach { (id, chord) -> obj.put(id, chord) }
        prefs.edit().putString("shortcut_overrides", obj.toString()).apply()
        _uiState.update { it.copy(bindings = bindings) }
    }

    /** Binds [chord] to [action]; an empty chord leaves the action without a key. */
    fun rebindShortcut(action: EditorShortcut, chord: String) {
        val current = _uiState.value.bindings
        val displaced = if (chord.isEmpty()) null else current.resolve(chord)?.takeIf { it != action }
        val next = current.rebind(action, chord)
        applyBindings(next)
        showToast(
            when {
                chord.isEmpty() -> "\"${action.label}\" has no shortcut now"
                displaced != null -> "${next.display(action)} now runs \"${action.label}\" instead of \"${displaced.label}\""
                else -> "\"${action.label}\" is now ${next.display(action)}"
            }
        )
    }

    fun resetShortcut(action: EditorShortcut) {
        applyBindings(_uiState.value.bindings.reset(action))
    }

    fun requestResetShortcuts() {
        _uiState.update { it.copy(pendingConfirm = PendingConfirm.ResetShortcuts) }
    }

    fun resetAllShortcuts() {
        applyBindings(ShortcutBindings())
        showToast("Keyboard shortcuts reset to defaults")
    }

    /**
     * Runs a keyboard action. Returns false for the few actions only the screen can perform
     * (the system file pickers and full screen), which the caller handles itself.
     */
    fun performShortcut(action: EditorShortcut): Boolean {
        val s = _uiState.value
        when (action) {
            EditorShortcut.PLAY_PAUSE -> togglePlayPause()
            EditorShortcut.SHUTTLE_BACK -> shuttle(-1)
            EditorShortcut.SHUTTLE_STOP -> shuttle(0)
            EditorShortcut.SHUTTLE_FORWARD -> shuttle(1)
            EditorShortcut.STEP_BACK -> stepFrames(-1)
            EditorShortcut.STEP_FORWARD -> stepFrames(1)
            EditorShortcut.JUMP_BACK -> seekTo(s.playheadSec - 1.0)
            EditorShortcut.JUMP_FORWARD -> seekTo(s.playheadSec + 1.0)
            EditorShortcut.GO_START -> seekTo(0.0)
            EditorShortcut.GO_END -> seekTo(s.durationSec)
            EditorShortcut.PREV_EDIT -> jumpToEditPoint(-1)
            EditorShortcut.NEXT_EDIT -> jumpToEditPoint(1)

            EditorShortcut.SPLIT -> splitAtPlayhead()
            EditorShortcut.SPLIT_ALL -> splitAtPlayhead(allTracks = true)
            EditorShortcut.DELETE -> deleteSelectedClips()
            EditorShortcut.RIPPLE_DELETE -> rippleDeleteSelectedClip()
            EditorShortcut.RIPPLE_TRIM_START -> trimToPlayhead(keepTail = true, ripple = true)
            EditorShortcut.RIPPLE_TRIM_END -> trimToPlayhead(keepTail = false, ripple = true)
            EditorShortcut.TRIM_START -> trimToPlayhead(keepTail = true, ripple = false)
            EditorShortcut.TRIM_END -> trimToPlayhead(keepTail = false, ripple = false)
            EditorShortcut.NUDGE_LEFT -> nudgeSelectionFrames(-1)
            EditorShortcut.NUDGE_RIGHT -> nudgeSelectionFrames(1)
            EditorShortcut.NUDGE_LEFT_BIG -> nudgeSelectionFrames(-10)
            EditorShortcut.NUDGE_RIGHT_BIG -> nudgeSelectionFrames(10)
            EditorShortcut.DUPLICATE -> duplicateSelectedClips()
            EditorShortcut.CLOSE_GAPS -> closeGaps()
            EditorShortcut.DETACH_AUDIO -> detachAudioFromSelection()
            EditorShortcut.ADD_TEXT -> addBlankTextClip()
            EditorShortcut.ADD_GRAPHIC -> setShowGraphicsModal(true)
            EditorShortcut.UNDO -> undo()
            EditorShortcut.REDO -> redo()

            EditorShortcut.COPY -> copySelectedClips()
            EditorShortcut.CUT -> cutSelectedClips()
            EditorShortcut.PASTE -> pasteClips()
            EditorShortcut.COPY_ATTRS -> copySelectedClipAttributes()
            EditorShortcut.PASTE_ATTRS -> pasteClipAttributesToSelected()
            EditorShortcut.SELECT_ALL -> selectAllClips()
            EditorShortcut.SELECT_FORWARD -> selectClipsFromPlayhead()
            EditorShortcut.DESELECT -> {
                _uiState.update { it.copy(selectedClipId = null, selectedClipIds = emptySet(), selectionMode = false, selectedAssetIds = emptySet()) }
            }
            EditorShortcut.SELECT_PREV -> selectAdjacentClip(-1)
            EditorShortcut.SELECT_NEXT -> selectAdjacentClip(1)
            EditorShortcut.SELECTION_MODE -> toggleSelectionMode()

            EditorShortcut.ADD_KEYFRAME -> addOrUpdateKeyframeAtPlayhead()
            EditorShortcut.DELETE_KEYFRAME -> removeKeyframeAtPlayhead()
            EditorShortcut.PREV_KEYFRAME -> jumpToKeyframe(-1)
            EditorShortcut.NEXT_KEYFRAME -> jumpToKeyframe(1)
            EditorShortcut.KEYFRAME_LANE -> toggleKeyframeLane()
            EditorShortcut.ADD_MARKER -> addMarkerAtPlayhead()
            EditorShortcut.NEXT_MARKER -> jumpToMarker(1)
            EditorShortcut.PREV_MARKER -> jumpToMarker(-1)
            EditorShortcut.MARK_IN -> setExportRangePointAtPlayhead(true)
            EditorShortcut.MARK_OUT -> setExportRangePointAtPlayhead(false)
            EditorShortcut.CLEAR_RANGE -> clearExportRange()

            EditorShortcut.ZOOM_IN -> zoomTimelineBy(1.25)
            EditorShortcut.ZOOM_OUT -> zoomTimelineBy(1.0 / 1.25)
            EditorShortcut.ZOOM_FIT -> if (!s.timelineAutoFit) toggleAutoFitTimeline()
            EditorShortcut.TOGGLE_SNAP -> toggleMagneticSnap()
            EditorShortcut.TOGGLE_FOLLOW -> toggleFollowPlayhead()
            EditorShortcut.TOGGLE_SCRUB -> toggleScrubFollow()
            EditorShortcut.COLLAPSE_TRACKS -> toggleAllTracksCollapsed()

            EditorShortcut.TOGGLE_LIBRARY -> togglePanel(WorkspacePanel.LIBRARY)
            EditorShortcut.TOGGLE_INSPECTOR -> togglePanel(WorkspacePanel.INSPECTOR)
            EditorShortcut.TOGGLE_TIMELINE -> togglePanel(WorkspacePanel.TIMELINE)
            EditorShortcut.MAXIMIZE_PREVIEW -> toggleMaximizePreview()
            EditorShortcut.FULLSCREEN -> return false

            EditorShortcut.NEW_PROJECT -> setShowNewProjectModal(true)
            EditorShortcut.OPEN_PROJECT_FILE -> return false
            EditorShortcut.SAVE -> saveProjectNow()
            EditorShortcut.EXPORT_PROJECT_FILE -> return false
            EditorShortcut.IMPORT_MEDIA -> return false
            EditorShortcut.EXPORT_VIDEO -> setShowExportModal(true)
            EditorShortcut.HOME -> goHome()

            EditorShortcut.TOOLS -> setShowToolsModal(true)
            EditorShortcut.PRESETS -> setShowPresetsModal(true)
            EditorShortcut.SHORTCUTS -> setShowShortcutsModal(true)
            EditorShortcut.ASSISTANT -> setShowAgentChat(true)
            EditorShortcut.MODE_EDIT -> setWorkspaceMode(WorkspaceMode.EDIT)
            EditorShortcut.MODE_COLOR -> setWorkspaceMode(WorkspaceMode.COLOR)
            EditorShortcut.MODE_MOTION -> setWorkspaceMode(WorkspaceMode.MOTION)
            EditorShortcut.MODE_MUSIC -> setWorkspaceMode(WorkspaceMode.MUSIC)
            EditorShortcut.TOGGLE_SCOPES -> toggleScopesPinned()
            EditorShortcut.GRADE_SPLIT -> toggleGradeSplitWipe()
            EditorShortcut.TOGGLE_THEME -> toggleLightTheme()
        }
        return true
    }
}
