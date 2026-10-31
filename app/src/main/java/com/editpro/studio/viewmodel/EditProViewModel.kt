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
import com.editpro.studio.mcp.McpServer
import com.editpro.studio.mcp.McpService
import com.editpro.studio.mcp.McpTools
import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.AudioGearPreset
import com.editpro.studio.model.CameraRig
import com.editpro.studio.model.ClipFilters
import com.editpro.studio.model.ClipMenuAction
import com.editpro.studio.model.EditorSnapshot
import com.editpro.studio.model.EquipmentRigPreset
import com.editpro.studio.model.ExportSettings
import com.editpro.studio.model.InspectorTab
import com.editpro.studio.model.Keyframe
import com.editpro.studio.model.LookPreset
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.MotionKeyframeStep
import com.editpro.studio.model.PresetCatalog
import com.editpro.studio.model.ProjectSerializer
import com.editpro.studio.model.ProjectSettings
import com.editpro.studio.model.RenderJob
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.ScopesMode
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineMarker
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

/** An action that would discard the current timeline and therefore asks first. */
enum class PendingConfirm { NEW_PROJECT, OPEN_PROJECT, DEMO_REEL }

/**
 * A record of a project the user saved through the system file picker. It is metadata only —
 * the app cannot reopen the file without the user picking it again, so this is not a live
 * index of the file system.
 */
data class RecentProject(
    val name: String,
    val savedAtEpochMs: Long,
    val clipCount: Int,
    val durationSec: Double
)

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
    val shortcuts: Map<String, String> = defaultShortcuts,
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
    val recentProjects: List<RecentProject> = emptyList(),
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
    /** Agent access stays on across launches unless the user turns it off. */
    val mcpAutoStart: Boolean = true
) {
    val assetsById: Map<String, MediaAsset>
        get() = assets.associateBy { it.id }

    val resolvedClips: List<ResolvedClip>
        get() = TimelineEngine.starts(clips, assetsById)

    val selectedClip: TimelineClip?
        get() = clips.find { it.id == selectedClipId }

    val selectedAsset: MediaAsset?
        get() = assets.find { it.id == selectedAssetId }

    val allEquipmentRigs: Map<String, EquipmentRigPreset>
        get() = PresetCatalog.builtinEquipmentRigs + customEquipmentRigs

    val allAudioGear: Map<String, AudioGearPreset>
        get() = PresetCatalog.builtinAudioGear + customAudioGear

    companion object {
        val defaultShortcuts: Map<String, String> = linkedMapOf(
            "play" to "Space",
            "split" to "s",
            "delete" to "Delete",
            "ripple" to "Shift+Delete",
            "undo" to "Ctrl+z",
            "redo" to "Ctrl+Shift+z",
            "duplicate" to "Ctrl+d",
            "marker" to "Ctrl+m",
            "previous" to "ArrowLeft",
            "next" to "ArrowRight",
            "home" to "Home",
            "end" to "End",
            "autoFit" to "Ctrl+Alt+f",
            "compact" to "Ctrl+Alt+c",
            "snap" to "Ctrl+Alt+s",
            "addVideo" to "v",
            "addAudio" to "a",
            "addImage" to "i",
            "addText" to "t",
            "follow" to "Ctrl+Alt+g",
            "presets" to "Ctrl+Alt+p"
        )
    }
}

class EditProViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("editpro_studio_prefs", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val history = ArrayDeque<EditorSnapshot>()
    private val future = ArrayDeque<EditorSnapshot>()
    private var playbackJob: Job? = null
    private var autosaveJob: Job? = null
    private var exportJob: Job? = null
    private val previewAudio = PreviewAudioEngine()
    private var mcpServer: McpServer? = null

    init {
        loadPersistedPreferences()
        loadRecentProjects()
        loadClipMenu()
        loadMcpToken()
        recoverAutosaveIfPresent()
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
                activeInspectorTab = mode.defaultInspectorTab()
            )
        }
    }

    private fun recoverAutosaveIfPresent() {
        val raw = prefs.getString("filmcraft_autosave", null) ?: return
        try {
            val doc = ProjectSerializer.deserializeProject(raw)
            if (doc.clips.isNotEmpty() || doc.assets.isNotEmpty()) {
                val dur = TimelineEngine.calcProjectDuration(doc.clips, doc.assets.associateBy { it.id })
                _uiState.update {
                    it.copy(
                        projectName = doc.name,
                        projectSettings = doc.settings,
                        assets = doc.assets,
                        clips = doc.clips,
                        markers = doc.markers,
                        durationSec = dur,
                        selectedClipId = doc.clips.firstOrNull()?.id,
                        selectedClipIds = doc.clips.firstOrNull()?.let { c -> setOf(c.id) } ?: emptySet(),
                        saveStatus = "Recovered autosaved project · ${doc.name}"
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(900L)
            val s = _uiState.value
            try {
                val json = ProjectSerializer.serializeProject(
                    name = s.projectName,
                    settings = s.projectSettings,
                    clips = s.clips,
                    markers = s.markers,
                    assets = s.assets
                )
                prefs.edit().putString("filmcraft_autosave", json).apply()
            } catch (_: Exception) {
            }
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
    fun snapHistory() {
        val s = _uiState.value
        history.addLast(
            EditorSnapshot(
                clips = s.clips,
                markers = s.markers,
                playhead = s.playheadSec,
                selectedClipId = s.selectedClipId,
                projectName = s.projectName,
                duration = s.durationSec,
                settings = s.projectSettings
            )
        )
        if (history.size > 60) history.removeFirst()
        future.clear()
        _uiState.update { it.copy(canUndo = history.isNotEmpty(), canRedo = false) }
    }

    fun undo() {
        if (history.isEmpty()) return
        val cur = _uiState.value
        future.addLast(
            EditorSnapshot(
                clips = cur.clips,
                markers = cur.markers,
                playhead = cur.playheadSec,
                selectedClipId = cur.selectedClipId,
                projectName = cur.projectName,
                duration = cur.durationSec,
                settings = cur.projectSettings
            )
        )
        val prev = history.removeLast()
        restoreSnapshot(prev)
        showToast("Undo")
    }

    fun redo() {
        if (future.isEmpty()) return
        val cur = _uiState.value
        history.addLast(
            EditorSnapshot(
                clips = cur.clips,
                markers = cur.markers,
                playhead = cur.playheadSec,
                selectedClipId = cur.selectedClipId,
                projectName = cur.projectName,
                duration = cur.durationSec,
                settings = cur.projectSettings
            )
        )
        val next = future.removeLast()
        restoreSnapshot(next)
        showToast("Redo")
    }

    private fun restoreSnapshot(snap: EditorSnapshot) {
        val assetsById = _uiState.value.assetsById
        val dur = TimelineEngine.calcProjectDuration(snap.clips, assetsById)
        _uiState.update {
            it.copy(
                clips = snap.clips,
                markers = snap.markers,
                playheadSec = min(snap.playhead, dur),
                selectedClipId = snap.selectedClipId,
                selectedClipIds = snap.selectedClipId?.let { id -> setOf(id) } ?: emptySet(),
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
                timelineZoomPxPerSec = pxPerSec.coerceIn(5.0, 180.0)
            )
        }
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
                _uiState.update { s ->
                    s.copy(
                        assets = s.assets + added,
                        selectedAssetId = added.first().id,
                        saveStatus = "${added.size} media file(s) imported"
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

    fun selectAsset(assetId: String) {
        _uiState.update { it.copy(selectedAssetId = assetId) }
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
        snapHistory()
        val s = _uiState.value
        val track = targetTrack ?: if (asset.kind == AssetKind.AUDIO) TimelineTrack.AUDIO else TimelineTrack.VIDEO
        val trackEnd = s.resolvedClips.filter { it.clip.track == track }.maxOfOrNull { it.end } ?: 0.0
        val clipStart = startAtOverride ?: trackEnd
        val newClip = TimelineClip(
            id = generateUid(),
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

    fun addBlankTextClip() {
        snapHistory()
        val s = _uiState.value
        val clip = TimelineClip(
            id = generateUid(),
            assetId = null,
            track = TimelineTrack.TEXT,
            startAt = s.playheadSec,
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
        showToast("Text overlay added at ${formatTimecode(s.playheadSec)}")
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

    fun selectClip(clipId: String?, additiveShift: Boolean = false) {
        if (clipId == null) {
            _uiState.update { it.copy(selectedClipId = null, selectedClipIds = emptySet()) }
            return
        }
        _uiState.update { s ->
            val nextSet = if (additiveShift) {
                val mutable = s.selectedClipIds.toMutableSet()
                if (clipId in mutable) mutable.remove(clipId) else mutable.add(clipId)
                mutable
            } else {
                setOf(clipId)
            }
            val primary = if (clipId in nextSet) clipId else nextSet.firstOrNull()
            s.copy(selectedClipId = primary, selectedClipIds = nextSet)
        }
    }

    fun selectClipsByIds(ids: Set<String>) {
        _uiState.update { s ->
            s.copy(
                selectedClipIds = ids,
                selectedClipId = ids.lastOrNull()
            )
        }
    }

    fun updateSelectedClip(transform: (TimelineClip) -> TimelineClip) {
        val s = _uiState.value
        val id = s.selectedClipId ?: return
        _uiState.update { state ->
            state.copy(
                clips = state.clips.map { c -> if (c.id == id) transform(c) else c }
            )
        }
        recalcAndSync("Edited locally")
    }

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
        val destTrack = newTrack ?: target.track
        val dur = max(0.1, updatedTrimOut - updatedTrimIn)

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
                magneticSnap = s.magneticSnap
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

    fun splitAtPlayhead() {
        val s = _uiState.value
        val c = s.selectedClip ?: run {
            showToast("Select a clip first")
            return
        }
        val rc = s.resolvedClips.find { it.clip.id == c.id } ?: return
        if (s.playheadSec <= rc.start + 0.05 || s.playheadSec >= rc.end - 0.05) {
            showToast("Move playhead inside the selected clip")
            return
        }
        snapHistory()
        val cutPoint = c.trimIn + (s.playheadSec - rc.start)
        val leftClip = c.copy(trimOut = cutPoint)
        val rightClip = c.copy(
            id = generateUid(),
            startAt = s.playheadSec,
            trimIn = cutPoint,
            trimOut = c.trimOut,
            label = "${c.label} (B)"
        )
        val nextClips = mutableListOf<TimelineClip>()
        for (item in s.clips) {
            if (item.id == c.id) {
                nextClips.add(leftClip)
                nextClips.add(rightClip)
            } else {
                nextClips.add(item)
            }
        }
        _uiState.update {
            it.copy(
                clips = nextClips,
                selectedClipId = rightClip.id,
                selectedClipIds = setOf(rightClip.id)
            )
        }
        recalcAndSync("Clip split")
        showToast("Clip split at ${formatTimecode(s.playheadSec)}")
    }

    fun deleteSelectedClips() {
        val s = _uiState.value
        val idsToRemove = if (s.selectedClipIds.isNotEmpty()) s.selectedClipIds else setOfNotNull(s.selectedClipId)
        if (idsToRemove.isEmpty()) {
            showToast("Select a clip first")
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

    fun rippleDeleteSelectedClip() {
        val s = _uiState.value
        val c = s.selectedClip ?: run {
            showToast("Select a clip first")
            return
        }
        val rc = s.resolvedClips.find { it.clip.id == c.id } ?: return
        val gap = rc.duration
        val track = c.track
        val cutStart = rc.start
        snapHistory()
        val updated = s.clips.filter { it.id != c.id }.map { other ->
            val otherStart = s.resolvedClips.find { it.clip.id == other.id }?.start ?: other.startAt
            if (other.track == track && otherStart > cutStart + 0.001) {
                other.copy(startAt = max(cutStart, otherStart - gap))
            } else other
        }
        _uiState.update {
            it.copy(
                clips = updated,
                selectedClipId = null,
                selectedClipIds = emptySet()
            )
        }
        recalcAndSync("Ripple deleted clip")
        showToast("Deleted clip and closed the gap on ${track.label}")
    }

    fun duplicateSelectedClips() {
        val s = _uiState.value
        val group = if (s.selectedClipIds.size > 1) {
            s.clips.filter { it.id in s.selectedClipIds }
        } else {
            listOfNotNull(s.selectedClip)
        }
        if (group.isEmpty()) {
            showToast("Select a clip first")
            return
        }
        snapHistory()
        val spanShift = group.maxOf { TimelineEngine.sourceSpanDuration(it, s.assetsById) }.coerceAtLeast(0.1)
        val copies = group.map { orig ->
            val st = s.resolvedClips.find { it.clip.id == orig.id }?.start ?: orig.startAt
            orig.copy(
                id = generateUid(),
                startAt = st + spanShift,
                label = "${orig.label} copy"
            )
        }
        _uiState.update {
            it.copy(
                clips = it.clips + copies,
                selectedClipId = copies.first().id,
                selectedClipIds = copies.map { c -> c.id }.toSet()
            )
        }
        recalcAndSync("Clip duplicated")
        showToast(if (copies.size > 1) "${copies.size} clips duplicated" else "Clip duplicated")
    }

    fun moveSelectedClipToPlayhead() {
        val s = _uiState.value
        val c = s.selectedClip ?: run { showToast("Select a clip first"); return }
        snapHistory()
        updateSelectedClip { it.copy(startAt = s.playheadSec) }
        showToast("Clip moved to playhead")
    }

    fun nudgeSelectedClip(deltaSec: Double) {
        val s = _uiState.value
        val c = s.selectedClip ?: run { showToast("Select a clip first"); return }
        val st = s.resolvedClips.find { it.clip.id == c.id }?.start ?: c.startAt
        snapHistory()
        updateSelectedClip { it.copy(startAt = max(0.0, st + deltaSec)) }
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
                _uiState.update { state -> state.copy(assets = state.assets + freezeAsset) }
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
    }

    fun toggleTrackHidden(track: TimelineTrack) {
        _uiState.update { s ->
            val cur = s.trackStates[track] ?: TrackState()
            s.copy(trackStates = s.trackStates + (track to cur.copy(hidden = !cur.hidden)))
        }
    }

    fun toggleTrackMute(track: TimelineTrack) {
        _uiState.update { s ->
            val cur = s.trackStates[track] ?: TrackState()
            s.copy(trackStates = s.trackStates + (track to cur.copy(muted = !cur.muted)))
        }
    }

    fun toggleTrackSolo(track: TimelineTrack) {
        _uiState.update { s ->
            val cur = s.trackStates[track] ?: TrackState()
            s.copy(trackStates = s.trackStates + (track to cur.copy(solo = !cur.solo)))
        }
    }

    fun isTrackAudible(track: TimelineTrack): Boolean {
        val states = _uiState.value.trackStates
        val ts = states[track] ?: return true
        if (ts.muted || ts.hidden) return false
        val anySolo = listOf(TimelineTrack.AUDIO, TimelineTrack.AUDIO2).any { states[it]?.solo == true }
        return !anySolo || ts.solo
    }

    // -------------------------------------------------------------------------
    // KEYFRAMES, MOTION PRESETS, COLOUR LOOKS, CAMERA RIGS & AUDIO GEAR
    // -------------------------------------------------------------------------
    fun addOrUpdateKeyframeAtPlayhead() {
        val s = _uiState.value
        val c = s.selectedClip ?: return
        val rc = s.resolvedClips.find { it.clip.id == c.id } ?: return
        val localT = ((s.playheadSec - rc.start).coerceIn(0.0, rc.duration) * 1000.0).roundToInt() / 1000.0
        val anim = TimelineEngine.animatedProps(c, localT)
        snapHistory()
        val filtered = c.keyframes.filter { abs(it.time - localT) >= 0.08 }
        val newKf = Keyframe(
            time = localT,
            scale = anim.scale,
            opacity = anim.opacity,
            rotation = anim.rotation,
            x = anim.x,
            y = anim.y,
            filters = anim.filters
        )
        updateSelectedClip { it.copy(keyframes = (filtered + newKf).sortedBy { k -> k.time }) }
        showToast("Keyframe set at ${formatTimecode(s.playheadSec)}")
    }

    fun removeKeyframeAtIndex(index: Int) {
        val c = _uiState.value.selectedClip ?: return
        if (index !in c.keyframes.indices) return
        snapHistory()
        val updated = c.keyframes.toMutableList().apply { removeAt(index) }
        updateSelectedClip { it.copy(keyframes = updated) }
    }

    fun clearKeyframesOnSelectedClip() {
        if (_uiState.value.selectedClip == null) return
        snapHistory()
        updateSelectedClip { it.copy(keyframes = emptyList()) }
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
        val dur = TimelineEngine.sourceSpanDuration(c, s.assetsById)
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
        val dur = TimelineEngine.sourceSpanDuration(c, s.assetsById)
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
    fun addMarkerAtPlayhead(customLabel: String? = null) {
        snapHistory()
        val s = _uiState.value
        val label = customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Marker ${s.markers.size + 1}"
        val next = (s.markers + TimelineMarker(time = s.playheadSec, label = label)).sortedBy { it.time }
        _uiState.update { it.copy(markers = next) }
        showToast("Marker added at ${formatTimecode(s.playheadSec)}")
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
        if (s.isPlaying) {
            previewAudio.start(
                scope = viewModelScope,
                clips = s.resolvedClips,
                assets = s.assetsById,
                audibleTracks = audibleAudioTracks(),
                fromSec = clamped
            )
        }
    }

    /** Audio tracks that should currently be heard (mute, hide and solo resolved). */
    private fun audibleAudioTracks(): Set<TimelineTrack> =
        listOf(TimelineTrack.AUDIO, TimelineTrack.AUDIO2).filter { isTrackAudible(it) }.toSet()

    fun togglePlayPause() {
        val s = _uiState.value
        if (s.isPlaying) {
            pausePlayback()
            return
        }
        if (s.durationSec <= 0.0) {
            showToast("Add clips to the timeline first")
            return
        }
        if (s.playheadSec >= s.durationSec - 0.02) {
            _uiState.update { it.copy(playheadSec = 0.0) }
        }
        _uiState.update { it.copy(isPlaying = true) }

        // Cue the audio for everything from the playhead onward.
        previewAudio.start(
            scope = viewModelScope,
            clips = s.resolvedClips,
            assets = s.assetsById,
            audibleTracks = audibleAudioTracks(),
            fromSec = _uiState.value.playheadSec
        )

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
                if (tick % 6 == 0) previewAudio.updateVolumes(cur.resolvedClips, cur.playheadSec)
                val nextTime = cur.playheadSec + dt
                if (nextTime >= cur.durationSec) {
                    _uiState.update { it.copy(playheadSec = cur.durationSec, isPlaying = false) }
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
        _uiState.update { it.copy(isPlaying = false) }
    }

    // -------------------------------------------------------------------------
    // 1-CLICK PRO DEMO REEL GENERATOR
    // -------------------------------------------------------------------------
    fun loadProDemoReel() {
        snapHistory()
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
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

    fun startNewProject() {
        snapHistory()
        _uiState.update {
            it.copy(
                showHome = false,
                projectName = "Untitled Project",
                clips = emptyList(),
                markers = emptyList(),
                selectedClipId = null,
                selectedClipIds = emptySet(),
                playheadSec = 0.0,
                durationSec = 0.0,
                saveStatus = "New project created"
            )
        }
        scheduleAutosave()
        showToast("New project created. Your imported media remains in the library.")
    }

    fun exportProjectJson(): String {
        val s = _uiState.value
        return ProjectSerializer.serializeProject(
            name = s.projectName,
            settings = s.projectSettings,
            clips = s.clips,
            markers = s.markers,
            assets = s.assets
        )
    }

    fun openProjectJson(rawJson: String) {
        try {
            val doc = ProjectSerializer.deserializeProject(rawJson)
            snapHistory()
            val mergedAssets = (_uiState.value.assets + doc.assets).distinctBy { it.id }
            val dur = TimelineEngine.calcProjectDuration(doc.clips, mergedAssets.associateBy { it.id })
            _uiState.update {
                it.copy(
                    showHome = false,
                    projectName = doc.name,
                    projectSettings = doc.settings,
                    assets = mergedAssets,
                    clips = doc.clips,
                    markers = doc.markers,
                    durationSec = dur,
                    playheadSec = 0.0,
                    selectedClipId = doc.clips.firstOrNull()?.id,
                    selectedClipIds = doc.clips.firstOrNull()?.let { c -> setOf(c.id) } ?: emptySet(),
                    saveStatus = "Project opened · ${doc.name}"
                )
            }
            showToast("Project opened: ${doc.name}")
        } catch (e: Exception) {
            showToast("Could not open project: ${e.message}")
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
        val dur = max(0.1, TimelineEngine.sourceSpanDuration(c, s.assetsById))
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
        _uiState.update { it.copy(exportSettings = transform(it.exportSettings)) }
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
    // New / Open / Load-demo all replace the timeline, so they ask first whenever
    // there is real work on it. The choice is held as an enum (never a lambda) so
    // it survives recomposition cleanly.
    // -------------------------------------------------------------------------
    fun hasWorkToDiscard(): Boolean =
        _uiState.value.clips.isNotEmpty() || _uiState.value.markers.isNotEmpty()

    fun requestConfirm(action: PendingConfirm) {
        _uiState.update { it.copy(pendingConfirm = action) }
    }

    fun clearConfirm() {
        _uiState.update { it.copy(pendingConfirm = null) }
    }

    override fun onCleared() {
        previewAudio.stop()
        mcpServer?.stop()
        mcpServer = null
        super.onCleared()
    }

    // -------------------------------------------------------------------------
    // PROJECT HOME & RECENT PROJECTS
    // -------------------------------------------------------------------------
    fun setShowHome(show: Boolean) {
        _uiState.update { it.copy(showHome = show) }
    }

    /** Called after a project has been written successfully through the file picker. */
    fun recordRecentProject() {
        val s = _uiState.value
        val entry = RecentProject(
            name = s.projectName,
            savedAtEpochMs = System.currentTimeMillis(),
            clipCount = s.clips.size,
            durationSec = s.durationSec
        )
        val next = (listOf(entry) + s.recentProjects.filter { it.name != entry.name }).take(12)
        _uiState.update { it.copy(recentProjects = next) }
        persistRecentProjects(next)
    }

    /** Removes only the list entry; the saved project file is left untouched. */
    fun removeRecentProject(name: String) {
        val next = _uiState.value.recentProjects.filterNot { it.name == name }
        _uiState.update { it.copy(recentProjects = next) }
        persistRecentProjects(next)
        showToast("Removed \"$name\" from the recent list. The saved file is unchanged.")
    }

    private fun loadRecentProjects() {
        val raw = prefs.getString("recent_projects", null) ?: return
        try {
            val arr = org.json.JSONArray(raw)
            val list = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    RecentProject(
                        name = o.optString("name", "Project"),
                        savedAtEpochMs = o.optLong("savedAt", 0L),
                        clipCount = o.optInt("clips", 0),
                        durationSec = o.optDouble("duration", 0.0)
                    )
                }
            }
            _uiState.update { it.copy(recentProjects = list) }
        } catch (_: Exception) {
        }
    }

    private fun persistRecentProjects(list: List<RecentProject>) {
        try {
            val arr = org.json.JSONArray()
            list.forEach { rp ->
                arr.put(org.json.JSONObject().apply {
                    put("name", rp.name)
                    put("savedAt", rp.savedAtEpochMs)
                    put("clips", rp.clipCount)
                    put("duration", rp.durationSec)
                })
            }
            prefs.edit().putString("recent_projects", arr.toString()).apply()
        } catch (_: Exception) {
        }
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
        val ids = raw.split(",").map { it.trim() }.filter { ClipMenuAction.fromId(it) != null }
        _uiState.update { it.copy(clipMenuOrder = ids) }
    }

    private fun persistClipMenu(order: List<String>) {
        prefs.edit().putString("clip_menu_order", order.joinToString(",")).apply()
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
        val autoStart = prefs.getBoolean("mcp_auto_start", true)
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
        locked: Boolean?
    ): Boolean {
        val track = TimelineTrack.entries.find { it.key == trackKey } ?: return false
        _uiState.update { st ->
            val current = st.trackStates[track] ?: TrackState()
            st.copy(
                trackStates = st.trackStates + (track to current.copy(
                    muted = muted ?: current.muted,
                    hidden = hidden ?: current.hidden,
                    solo = solo ?: current.solo,
                    locked = locked ?: current.locked
                ))
            )
        }
        return true
    }

    fun mcpSetPlayback(playing: Boolean) {
        val isPlaying = _uiState.value.isPlaying
        if (playing && !isPlaying) togglePlayPause()
        if (!playing && isPlaying) pausePlayback()
    }

    /** Adds an asset produced by a web fetch to the library, ready for add_clip. */
    fun mcpAddFetchedAsset(asset: MediaAsset) {
        _uiState.update { st ->
            st.copy(
                assets = st.assets + asset,
                selectedAssetId = asset.id,
                saveStatus = "Fetched ${asset.name}"
            )
        }
        scheduleAutosave()
    }
}
