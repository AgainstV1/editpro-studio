package com.editpro.studio.mcp

import android.app.Application
import com.editpro.studio.media.WebAssetFetcher
import com.editpro.studio.model.KeyframeOps
import com.editpro.studio.model.KeyframePatch
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.MotionGraphics
import com.editpro.studio.model.PresetCatalog
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.viewmodel.EditProViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The MCP tool surface: what an agent can actually do to a project.
 *
 * Deliberately editing-only, per the project owner's decision: there is no export trigger and no
 * timeline-wide clear. Every mutating tool goes through the same view-model operations the UI
 * uses, so agent edits land in the normal undo history and are indistinguishable from hand edits.
 *
 * Tools that are not exposed, on purpose:
 *  - blur / hue / grain: stored by the model but not applied to the render, so an agent could
 *    "set" them and see no effect. Better absent than misleading.
 */
class McpTools(private val viewModel: EditProViewModel) {

    private val specs: List<ToolSpec> = buildSpecs()

    fun count(): Int = specs.size

    fun initializeResult(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion", "")
        val version = if (requested in SUPPORTED_PROTOCOLS) requested else SUPPORTED_PROTOCOLS.first()
        return JSONObject()
            .put("protocolVersion", version)
            .put(
                "capabilities",
                JSONObject().put("tools", JSONObject().put("listChanged", false))
            )
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", "editpro-studio")
                    .put("title", "Edit Pro Studio")
                    .put("version", appVersion())
            )
            .put(
                "instructions",
                "Edits an Edit Pro Studio video project on the user's tablet. Start with get_summary " +
                    "and list_media, then build with add_clip / add_text / add_motion_graphic and refine " +
                    "with update_clip, set_keyframe, edit_clips and move_clips. " +
                    "Every change is undoable, so prefer several small edits over one large one."
            )
    }

    /** The installed app's version, so the server never reports a stale hard-coded number. */
    private fun appVersion(): String = try {
        val app = viewModel.getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    fun definitions(): JSONArray {
        val out = JSONArray()
        specs.forEach { spec ->
            out.put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("inputSchema", spec.schema)
            )
        }
        return out
    }

    /** The same tools, shaped for OpenAI-compatible function calling. */
    fun openAiTools(): JSONArray {
        val out = JSONArray()
        specs.forEach { spec ->
            out.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("parameters", spec.schema)
                    )
            )
        }
        return out
    }

    /**
     * Runs a tool and returns its raw result as JSON text. Throws on failure so the in-app agent
     * loop can hand the error back to the model instead of dying.
     */
    suspend fun execute(name: String, args: JSONObject): String {
        val spec = specs.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown tool '$name'.")
        val value = if (spec.onMain) {
            withContext(Dispatchers.Main) { spec.handler(args) }
        } else {
            spec.handler(args)
        }
        return if (value is String) value else value.toString()
    }

    suspend fun call(name: String, args: JSONObject): JSONObject {
        val spec = specs.firstOrNull { it.name == name }
            ?: return errorContent("Unknown tool '$name'. Call tools/list for the available tools.")
        return try {
            val value = if (spec.onMain) {
                withContext(Dispatchers.Main) { spec.handler(args) }
            } else {
                spec.handler(args)
            }
            val text = if (value is String) value else value.toString()
            JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .put("isError", false)
        } catch (e: Exception) {
            errorContent(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun errorContent(message: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
        .put("isError", true)

    // ------------------------------------------------------------------ specs

    private data class ToolSpec(
        val name: String,
        val description: String,
        val schema: JSONObject,
        /** Network tools must not run on the main thread; they are already on a worker thread. */
        val onMain: Boolean = true,
        val handler: (JSONObject) -> Any
    )

    private fun obj(vararg props: Pair<String, JSONObject>): JSONObject {
        val properties = JSONObject()
        props.forEach { (name, spec) -> properties.put(name, spec) }
        return JSONObject().put("type", "object").put("properties", properties)
    }

    private fun p(name: String, type: String, desc: String): Pair<String, JSONObject> =
        name to JSONObject().put("type", type).put("description", desc)

    private fun buildSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            "get_summary",
            "Current project at a glance: name, duration, playhead, selection, every track's state, " +
                "and counts of clips and media. Call this first.",
            obj(),
        ) {
            val s = viewModel.uiState.value
            val tracks = JSONArray()
            TimelineTrack.entries.forEach { track ->
                val ts = s.trackStates[track]
                tracks.put(
                    JSONObject()
                        .put("track", track.key)
                        .put("label", track.label)
                        .put("clips", s.clips.count { it.track == track })
                        .put("muted", ts?.muted ?: false)
                        .put("hidden", ts?.hidden ?: false)
                        .put("solo", ts?.solo ?: false)
                        .put("locked", ts?.locked ?: false)
                        .put("collapsed", ts?.collapsed ?: false)
                )
            }
            JSONObject()
                .put("projectName", s.projectName)
                .put("durationSec", s.durationSec)
                .put("playheadSec", s.playheadSec)
                .put("isPlaying", s.isPlaying)
                .put("clipCount", s.clips.size)
                .put("assetCount", s.assets.size)
                .put("selectedClipId", s.selectedClipId ?: JSONObject.NULL)
                .put("selectedClipIds", JSONArray(s.selectionIds.toList()))
                .put("projectId", s.currentProjectId ?: JSONObject.NULL)
                .put("width", s.projectSettings.width)
                .put("height", s.projectSettings.height)
                .put("fps", s.projectSettings.fps)
                .put("markers", JSONArray().also { arr ->
                    s.markers.forEach { m -> arr.put(JSONObject().put("timeSec", m.time).put("label", m.label)) }
                })
                .put("workspaceMode", s.workspaceMode.id)
                .put("canUndo", s.canUndo)
                .put("canRedo", s.canRedo)
                .put("tracks", tracks)
        },

        ToolSpec(
            "get_project",
            "The whole project as portable .filmcraft JSON — the same document Save writes. Use it to " +
                "inspect anything the other tools do not expose.",
            obj(),
        ) { viewModel.exportProjectJson() },

        ToolSpec(
            "list_media",
            "Every media asset in the library. Use the returned ids with add_clip.",
            obj(),
        ) {
            val s = viewModel.uiState.value
            val out = JSONArray()
            s.assets.forEach { a -> out.put(assetJson(a)) }
            out
        },

        ToolSpec(
            "list_clips",
            "Every clip on the timeline with its track, absolute start/end, trim points and transform.",
            obj(),
        ) {
            val s = viewModel.uiState.value
            val out = JSONArray()
            s.clips.forEach { c -> out.put(clipJson(c, s.assetsById)) }
            out
        },

        ToolSpec(
            "list_look_presets",
            "Available colour looks. Pass the id to apply_look.",
            obj(),
        ) {
            val out = JSONArray()
            PresetCatalog.lookPresets.forEach { preset ->
                out.put(
                    JSONObject()
                        .put("id", preset.id)
                        .put("title", preset.title)
                        .put("category", preset.category)
                )
            }
            out
        },

        ToolSpec(
            "fetch_url",
            "Download an image, video or audio file from a URL straight into the media library and " +
                "return its asset id, ready for add_clip. Direct media links only - not HTML pages. " +
                "http/https, 250 MB limit, 60 s timeout.",
            obj(
                p("url", "string", "Direct link to an image, video or audio file."),
                p("name", "string", "Optional filename to store it under.")
            ),
            onMain = false
        ) { args ->
            val url = args.optString("url", "").trim()
            if (url.isEmpty()) throw IllegalArgumentException("url is required.")
            val app = viewModel.getApplication<Application>()
            val asset = runBlocking {
                WebAssetFetcher.fetch(app, url, args.optString("name", "").ifEmpty { null })
            }
            if (asset != null) {
                runBlocking(Dispatchers.Main) { viewModel.mcpAddFetchedAsset(asset) }
            }
            JSONObject()
                .put("assetId", asset?.id ?: JSONObject.NULL)
                .put("name", asset?.name ?: JSONObject.NULL)
                .put("kind", asset?.kind?.id ?: JSONObject.NULL)
                .put("durationSec", asset?.durationSec ?: JSONObject.NULL)
                .put("sizeBytes", asset?.sizeBytes ?: JSONObject.NULL)
        },

        ToolSpec(
            "add_clip",
            "Place a media asset on the timeline. Without startSec it appends to the end of the target " +
                "track. Returns the new clip id.",
            obj(
                p("assetId", "string", "Asset id from list_media."),
p("track", "string", "video | video2 | overlay | audio | audio2. Defaults to the natural track."),
                p("startSec", "number", "Absolute timeline position in seconds."),
                p("trimIn", "number", "Seconds trimmed from the start of the source."),
                p("trimOut", "number", "Seconds kept from the source start.")
            ),
        ) { args ->
            val assetId = args.optString("assetId", "")
            val id = viewModel.mcpAddClip(
                assetId = assetId,
                trackKey = args.optString("track", "").ifEmpty { null },
                startSec = if (args.has("startSec")) args.optDouble("startSec") else null,
                trimIn = if (args.has("trimIn")) args.optDouble("trimIn") else null,
                trimOut = if (args.has("trimOut")) args.optDouble("trimOut") else null
            ) ?: throw IllegalArgumentException("No media asset with id '$assetId'. Use list_media to find one.")
            JSONObject().put("clipId", id)
        },

        ToolSpec(
            "update_clip",
            "Change any property of one clip: position, trim, track, transform, volume, text, fades and " +
                "the full colour grade. Only the keys you pass are changed. Returns the updated clip. " +
                "On a clip that has keyframes, scale / opacity / rotation / x / y are driven by the " +
                "keyframes, so change those with set_keyframe instead.",
            obj(
                p("clipId", "string", "Target clip id."),
                p("startSec", "number", "Absolute timeline position."),
                p("trimIn", "number", "Source in-point."),
                p("trimOut", "number", "Source out-point."),
                p("track", "string", "Move to another track."),
                p("volume", "number", "0-200, percent."),
                p("scale", "number", "Percent of frame."),
                p("opacity", "number", "0-100."),
                p("rotation", "number", "Degrees, -180 to 180."),
                p("x", "number", "Horizontal offset, percent of frame."),
                p("y", "number", "Vertical offset, percent of frame."),
                p("flipH", "boolean", "Mirror horizontally."),
                p("flipV", "boolean", "Mirror vertically."),
                p("text", "string", "Text content, for text clips."),
                p("fontSize", "integer", "Text size."),
                p("textColor", "string", "Text colour as #rrggbb."),
                p("accentColor", "string", "Motion graphics: plate / bar / shape colour as #rrggbb."),
                p("textAlign", "string", "left | center | right."),
                p("verticalPosition", "string", "top | center | bottom."),
                p("boxW", "number", "Shape graphics: width, percent of frame."),
                p("boxH", "number", "Shape graphics: height, percent of frame."),
                p("introSec", "number", "Motion graphics: length of the intro and outro animation."),
                p("speed", "number", "Playback speed, 0.25-4."),
                p("pan", "number", "Audio pan, -100 (left) to 100 (right)."),
                p("label", "string", "Clip name shown on the timeline."),
                p("fadeOut", "number", "Picture fade-out seconds."),
                p("fadeInAudio", "number", "Audio fade-in seconds."),
                p("fadeOutAudio", "number", "Audio fade-out seconds."),
                p("transition", "string", "Entrance transition id."),
                p("transitionOut", "string", "Exit transition id."),
                p("brightness", "number", "100 = unchanged."),
                p("contrast", "number", "100 = unchanged."),
                p("saturation", "number", "100 = unchanged."),
                p("temperature", "number", "Negative cools, positive warms."),
                p("tint", "number", "Green/magenta balance."),
                p("sharpen", "number", "0-100."),
                p("vignette", "number", "0-100."),
                p("sepia", "number", "0-100."),
                p("invert", "number", "0-100."),
                p("liftR", "number", "Shadow red wheel."),
                p("liftG", "number", "Shadow green wheel."),
                p("liftB", "number", "Shadow blue wheel."),
                p("gammaR", "number", "Midtone red wheel."),
                p("gammaG", "number", "Midtone green wheel."),
                p("gammaB", "number", "Midtone blue wheel."),
                p("gainR", "number", "Highlight red wheel."),
                p("gainG", "number", "Highlight green wheel."),
                p("gainB", "number", "Highlight blue wheel.")
            ),
        ) { args ->
            val clipId = args.optString("clipId", "")
            val ok = viewModel.mcpUpdateClip(clipId) { clip -> applyProps(clip, args) }
            if (!ok) throw IllegalArgumentException("No clip with id '$clipId'. Use list_clips.")
            val s = viewModel.uiState.value
            val updated = s.clips.first { it.id == clipId }
            clipJson(updated, s.assetsById)
        },

        ToolSpec(
            "delete_clip",
            "Remove one clip. Undoable.",
            obj(p("clipId", "string", "Clip to remove.")),
        ) { args ->
            val clipId = args.optString("clipId", "")
            if (!viewModel.mcpDeleteClip(clipId)) {
                throw IllegalArgumentException("No clip with id '$clipId'. Use list_clips.")
            }
            JSONObject().put("deleted", clipId)
        },

        ToolSpec(
            "split_clip",
            "Cut one clip in two at an absolute timeline time. Undoable.",
            obj(
                p("clipId", "string", "Clip to split."),
                p("atSec", "number", "Absolute timeline time of the cut.")
            ),
        ) { args ->
            val clipId = args.optString("clipId", "")
            val at = args.optDouble("atSec", -1.0)
            if (at < 0.0) throw IllegalArgumentException("atSec is required.")
            if (!viewModel.mcpSplitClip(clipId, at)) {
                throw IllegalArgumentException("No clip with id '$clipId'. Use list_clips.")
            }
            JSONObject().put("split", clipId).put("atSec", at)
        },

        ToolSpec(
            "set_track_state",
"Mute, hide, solo, lock or collapse a track. Only the flags you pass are changed.",
            obj(
                p("track", "string", "video | video2 | overlay | graphics | text | audio | audio2."),
                p("collapsed", "boolean", "Shrink the track to a thin strip in the timeline."),
                p("muted", "boolean", "Silence the track."),
                p("hidden", "boolean", "Exclude from the render."),
                p("solo", "boolean", "Solo this audio track."),
                p("locked", "boolean", "Prevent edits.")
            ),
        ) { args ->
            val trackKey = args.optString("track", "")
            val ok = viewModel.mcpSetTrackState(
                trackKey = trackKey,
                muted = if (args.has("muted")) args.optBoolean("muted") else null,
                hidden = if (args.has("hidden")) args.optBoolean("hidden") else null,
                solo = if (args.has("solo")) args.optBoolean("solo") else null,
                locked = if (args.has("locked")) args.optBoolean("locked") else null,
                collapsed = if (args.has("collapsed")) args.optBoolean("collapsed") else null
            )
            if (!ok) throw IllegalArgumentException("Unknown track '$trackKey'.")
            JSONObject().put("track", trackKey)
        },

        ToolSpec(
            "add_text",
            "Create a title clip on the TEXT track and set its content. Returns the new clip id.",
            obj(
                p("text", "string", "The words to show."),
                p("startSec", "number", "Absolute timeline position."),
                p("durationSec", "number", "How long the title stays up.")
            ),
        ) { args ->
            val before = viewModel.uiState.value.clips.map { it.id }.toSet()
            viewModel.addBlankTextClip(if (args.has("startSec")) args.optDouble("startSec", 0.0) else null)
            val created = viewModel.uiState.value.clips.lastOrNull { it.id !in before }
                ?: throw IllegalStateException("Could not create a text clip. The text track may be locked.")
            val text = args.optString("text", "Title")
            viewModel.mcpUpdateClip(created.id) { clip ->
                clip.copy(
                    text = text,
                    trimOut = if (args.has("durationSec")) args.optDouble("durationSec", clip.trimOut).coerceAtLeast(0.2) else clip.trimOut
                )
            }
            val placed = viewModel.uiState.value.clips.first { it.id == created.id }
            JSONObject().put("clipId", created.id).put("startSec", placed.startAt)
        },

        ToolSpec(
            "add_marker",
            "Drop a marker at the playhead, optionally labelled.",
            obj(p("label", "string", "Marker label.")),
        ) { args ->
            viewModel.addMarkerAtPlayhead(args.optString("label", "").ifEmpty { null })
            JSONObject().put("ok", true)
        },

        ToolSpec(
            "seek",
            "Move the playhead without playing.",
            obj(p("timeSec", "number", "Absolute timeline time.")),
        ) { args ->
            viewModel.seekTo(args.optDouble("timeSec", 0.0))
            JSONObject().put("playheadSec", viewModel.uiState.value.playheadSec)
        },

        ToolSpec(
            "set_playback",
            "Start or pause preview playback.",
            obj(p("playing", "boolean", "True to play, false to pause.")),
        ) { args ->
            viewModel.mcpSetPlayback(args.optBoolean("playing", false))
            JSONObject().put("isPlaying", viewModel.uiState.value.isPlaying)
        },

        ToolSpec("undo", "Undo the last change, including the agent's own.", obj()) {
            viewModel.undo()
            JSONObject().put("ok", true)
        },

        ToolSpec("redo", "Redo the last undone change.", obj()) {
            viewModel.redo()
            JSONObject().put("ok", true)
        },

        ToolSpec(
            "set_project_name",
            "Rename the project.",
            obj(p("name", "string", "New project name.")),
        ) { args ->
            viewModel.setProjectName(args.optString("name", "Untitled Project"))
            JSONObject().put("projectName", viewModel.uiState.value.projectName)
        },

        ToolSpec(
            "apply_look",
            "Apply a colour look preset to one clip. Use list_look_presets for ids.",
            obj(
                p("clipId", "string", "Target clip."),
                p("presetId", "string", "Look preset id.")
            ),
        ) { args ->
            val clipId = args.optString("clipId", "")
            val presetId = args.optString("presetId", "")
            if (!viewModel.mcpApplyLook(clipId, presetId)) {
                throw IllegalArgumentException("Unknown clip '$clipId' or preset '$presetId'.")
            }
            JSONObject().put("applied", presetId).put("clipId", clipId)
        },

        ToolSpec(
            "add_keyframe",
            "Add (or replace) a keyframe at a time on one clip, capturing its current transform. " +
                "Select-then-seek internally, so the playhead ends up at that time.",
            obj(
                p("clipId", "string", "Target clip."),
                p("timeSec", "number", "Absolute timeline time for the keyframe.")
            ),
        ) { args ->
            val clipId = args.optString("clipId", "")
            val t = args.optDouble("timeSec", 0.0)
            if (!viewModel.mcpAddKeyframe(clipId, t)) {
                throw IllegalArgumentException("No clip with id '$clipId'.")
            }
            JSONObject().put("clipId", clipId).put("timeSec", t)
        },

        ToolSpec(
            "list_keyframes",
            "The keyframes of one clip, in time order, with the values and easing each one holds.",
            obj(p("clipId", "string", "Target clip.")),
        ) { args ->
            val clipId = args.optString("clipId", "")
            val s = viewModel.uiState.value
            val rc = s.resolvedClips.find { it.clip.id == clipId }
                ?: throw IllegalArgumentException("No clip with id '$clipId'. Use list_clips.")
            val out = JSONArray()
            rc.clip.keyframes.sortedBy { it.time }.forEach { k ->
                out.put(
                    JSONObject()
                        .put("timeSec", rc.start + k.time)
                        .put("clipTimeSec", k.time)
                        .put("scale", k.scale)
                        .put("opacity", k.opacity)
                        .put("rotation", k.rotation)
                        .put("x", k.x)
                        .put("y", k.y)
                        .put("easing", k.easing.ifEmpty { "clip" })
                )
            }
            out
        },

        ToolSpec(
            "set_keyframe",
            "Animate a clip: write a keyframe at a timeline time with the values you pass (the rest are " +
                "taken from the clip's motion at that moment). Two keyframes with different values make a " +
                "move, zoom, spin or fade between them. easing controls the move that starts at this keyframe.",
            obj(
                p("clipId", "string", "Target clip."),
                p("timeSec", "number", "Absolute timeline time for the keyframe."),
                p("scale", "number", "Percent of frame."),
                p("opacity", "number", "0-100."),
                p("rotation", "number", "Degrees."),
                p("x", "number", "Horizontal offset, percent of frame."),
                p("y", "number", "Vertical offset, percent of frame."),
                p("easing", "string", "clip | linear | ease | in | out | hold.")
            ),
        ) { args ->
            val clipId = args.optString("clipId", "")
            if (!args.has("timeSec")) throw IllegalArgumentException("timeSec is required.")
            val easing = if (args.has("easing")) {
                val raw = args.optString("easing", "").lowercase()
                val id = if (raw == "clip") "" else raw
                if (KeyframeOps.EASINGS.none { it.first == id }) {
                    throw IllegalArgumentException("Unknown easing '$raw'. Use clip, linear, ease, in, out or hold.")
                }
                id
            } else null
            fun num(key: String): Double? = if (args.has(key)) args.optDouble(key).takeIf { it.isFinite() } else null
            val patch = KeyframePatch(
                scale = num("scale"),
                opacity = num("opacity")?.coerceIn(0.0, 100.0),
                rotation = num("rotation"),
                x = num("x"),
                y = num("y"),
                easing = easing
            )
            val t = args.optDouble("timeSec", 0.0)
            if (!viewModel.mcpSetKeyframe(clipId, t, patch)) {
                throw IllegalArgumentException("No clip with id '$clipId'. Use list_clips.")
            }
            val clip = viewModel.uiState.value.clips.first { it.id == clipId }
            JSONObject().put("clipId", clipId).put("timeSec", t).put("keyframeCount", clip.keyframes.size)
        },

        ToolSpec(
            "remove_keyframe",
            "Delete the keyframe at a timeline time on one clip.",
            obj(
                p("clipId", "string", "Target clip."),
                p("timeSec", "number", "Absolute timeline time of the keyframe.")
            ),
        ) { args ->
            val clipId = args.optString("clipId", "")
            val t = args.optDouble("timeSec", 0.0)
            if (!viewModel.mcpRemoveKeyframe(clipId, t)) {
                throw IllegalArgumentException("No keyframe at ${t}s on clip '$clipId'. Use list_keyframes.")
            }
            JSONObject().put("clipId", clipId).put("removedAtSec", t)
        },

        ToolSpec(
            "list_motion_graphics",
            "The motion-graphics templates: animated titles, lower thirds, counters, bars and shapes. " +
                "Pass an id to add_motion_graphic.",
            obj(),
        ) {
            val out = JSONArray()
            MotionGraphics.templates.forEach { t ->
                out.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("title", t.title)
                        .put("category", t.category)
                        .put("description", t.description)
                        .put("defaultText", t.defaultText)
                        .put("textHint", t.textHint)
                        .put("defaultDurationSec", t.defaultDuration)
                        .put("coversWholeEdit", t.fullLength)
                )
            }
            out
        },

        ToolSpec(
            "add_motion_graphic",
            "Add a motion graphic as a clip. Text lines are separated by a newline; what each line means " +
                "is in the template's textHint. Restyle it afterwards with update_clip (text, textColor, " +
                "accentColor, fontSize, x, y, scale…). Returns the new clip id.",
            obj(
                p("templateId", "string", "Template id from list_motion_graphics."),
                p("startSec", "number", "Absolute timeline position. Defaults to the playhead."),
                p("durationSec", "number", "How long it stays on screen."),
                p("text", "string", "Text content; use \\n between lines.")
            ),
        ) { args ->
            val templateId = args.optString("templateId", "")
            if (MotionGraphics.find(templateId) == null) {
                throw IllegalArgumentException("Unknown template '$templateId'. Use list_motion_graphics.")
            }
            val id = viewModel.mcpAddGraphic(
                templateId = templateId,
                startSec = if (args.has("startSec")) args.optDouble("startSec", 0.0) else null,
                durationSec = if (args.has("durationSec")) args.optDouble("durationSec", 4.0) else null,
                text = if (args.has("text")) args.optString("text", "") else null
            ) ?: throw IllegalStateException("Could not add the graphic. The graphics and text tracks may be locked.")
            val s = viewModel.uiState.value
            clipJson(s.clips.first { it.id == id }, s.assetsById)
        },

        ToolSpec(
            "select_clips",
            "Select clips in the editor so the user can see what you are about to change. An empty list " +
                "clears the selection.",
            obj("clipIds" to idArraySchema("Clip ids to select.")),
        ) { args ->
            val ids = idList(args)
            if (ids.isEmpty()) viewModel.selectClip(null) else viewModel.selectClipsByIds(ids)
            JSONObject().put("selectedClipIds", JSONArray(viewModel.uiState.value.selectionIds.toList()))
        },

        ToolSpec(
            "move_clips",
            "Slide several clips along the timeline together, keeping their spacing. The group stops at " +
                "the first clip in its way rather than overlapping it. Returns how far it actually moved.",
            obj(
                "clipIds" to idArraySchema("Clips to move."),
                p("deltaSec", "number", "Seconds to move; negative moves earlier.")
            ),
        ) { args ->
            val ids = idList(args)
            if (ids.isEmpty()) throw IllegalArgumentException("clipIds is required.")
            val moved = viewModel.mcpMoveClips(ids, args.optDouble("deltaSec", 0.0))
            JSONObject().put("movedSec", moved)
        },

        ToolSpec(
            "edit_clips",
            "Run a timeline edit on several clips at once. Operations: ripple_delete (remove and close " +
                "the gap), duplicate, copy, cut, paste (at atSec, or the playhead), close_gaps (on the " +
                "clips' tracks, or every track when clipIds is empty), detach_audio (video sound becomes " +
                "its own audio clip), trim_start / trim_end (cut the clip at atSec and keep the other " +
                "side), ripple_trim_start / ripple_trim_end (the same, closing the gap).",
            obj(
                "clipIds" to idArraySchema("Clips to act on. Not needed for paste or close_gaps."),
                p("operation", "string", "One of the operations listed above."),
                p("atSec", "number", "Timeline time for paste and the trim operations.")
            ),
        ) { args ->
            val operation = args.optString("operation", "")
            val before = viewModel.uiState.value.clips.map { it.id }.toSet()
            val ok = viewModel.mcpEditClips(
                clipIds = idList(args),
                operation = operation,
                atSec = if (args.has("atSec")) args.optDouble("atSec", 0.0) else null
            )
            if (!ok) {
                throw IllegalArgumentException(
                    "Could not run '$operation'. Check the operation name and that the clip ids exist (list_clips)."
                )
            }
            val after = viewModel.uiState.value.clips.map { it.id }.toSet()
            JSONObject()
                .put("operation", operation)
                .put("createdClipIds", JSONArray((after - before).toList()))
                .put("removedClipIds", JSONArray((before - after).toList()))
        }
    )

    private fun idArraySchema(description: String): JSONObject = JSONObject()
        .put("type", "array")
        .put("items", JSONObject().put("type", "string"))
        .put("description", description)

    private fun idList(args: JSONObject): Set<String> {
        val array = args.optJSONArray("clipIds") ?: return emptySet()
        return (0 until array.length()).mapNotNull { i -> array.optString(i, "").ifEmpty { null } }.toSet()
    }

    // --------------------------------------------------------------- mapping

    private fun applyProps(clip: TimelineClip, args: JSONObject): TimelineClip {
        var out = clip
        if (args.has("startSec")) out = out.copy(startAt = args.optDouble("startSec", out.startAt).coerceAtLeast(0.0))
        if (args.has("trimIn")) out = out.copy(trimIn = args.optDouble("trimIn", out.trimIn).coerceAtLeast(0.0))
        if (args.has("trimOut")) out = out.copy(trimOut = args.optDouble("trimOut", out.trimOut).coerceAtLeast(0.0))
        if (args.has("track")) {
            TimelineTrack.entries.find { it.key == args.optString("track") }?.let { out = out.copy(track = it) }
        }
        if (args.has("volume")) out = out.copy(volume = args.optDouble("volume", out.volume).coerceIn(0.0, 200.0))
        if (args.has("scale")) out = out.copy(scale = args.optDouble("scale", out.scale))
        if (args.has("opacity")) out = out.copy(opacity = args.optDouble("opacity", out.opacity).coerceIn(0.0, 100.0))
        if (args.has("rotation")) out = out.copy(rotation = args.optDouble("rotation", out.rotation))
        if (args.has("x")) out = out.copy(x = args.optDouble("x", out.x))
        if (args.has("y")) out = out.copy(y = args.optDouble("y", out.y))
        if (args.has("flipH")) out = out.copy(flipH = args.optBoolean("flipH", out.flipH))
        if (args.has("flipV")) out = out.copy(flipV = args.optBoolean("flipV", out.flipV))
        if (args.has("text")) out = out.copy(text = args.optString("text", out.text))
        if (args.has("fontSize")) out = out.copy(fontSize = args.optInt("fontSize", out.fontSize))
        if (args.has("textColor")) out = out.copy(textColor = args.optString("textColor", out.textColor))
        if (args.has("accentColor")) out = out.copy(accentColor = args.optString("accentColor", out.accentColor))
        if (args.has("textAlign")) {
            args.optString("textAlign").takeIf { it in setOf("left", "center", "right") }?.let { out = out.copy(textAlign = it) }
        }
        if (args.has("verticalPosition")) {
            args.optString("verticalPosition").takeIf { it in setOf("top", "center", "bottom") }
                ?.let { out = out.copy(verticalPosition = it) }
        }
        if (args.has("boxW")) out = out.copy(boxW = args.optDouble("boxW", out.boxW).coerceIn(0.1, 100.0))
        if (args.has("boxH")) out = out.copy(boxH = args.optDouble("boxH", out.boxH).coerceIn(0.1, 100.0))
        if (args.has("introSec")) {
            out = out.copy(transitionDuration = args.optDouble("introSec", out.transitionDuration).coerceIn(0.0, 5.0))
        }
        if (args.has("speed")) out = out.copy(speed = args.optDouble("speed", out.speed).coerceIn(0.25, 4.0))
        if (args.has("pan")) out = out.copy(audioPan = args.optDouble("pan", out.audioPan).coerceIn(-100.0, 100.0))
        if (args.has("label")) out = out.copy(label = args.optString("label", out.label))
        if (args.has("fadeOut")) out = out.copy(fadeOut = args.optDouble("fadeOut", out.fadeOut).coerceAtLeast(0.0))
        if (args.has("fadeInAudio")) out = out.copy(fadeInAudio = args.optDouble("fadeInAudio", out.fadeInAudio))
        if (args.has("fadeOutAudio")) out = out.copy(fadeOutAudio = args.optDouble("fadeOutAudio", out.fadeOutAudio))
        if (args.has("transition")) out = out.copy(transition = args.optString("transition", out.transition))
        if (args.has("transitionOut")) out = out.copy(transitionOut = args.optString("transitionOut", out.transitionOut))

        val f = out.filters
        fun num(key: String, current: Double) = if (args.has(key)) args.optDouble(key, current) else current
        val graded = f.copy(
            brightness = num("brightness", f.brightness),
            contrast = num("contrast", f.contrast),
            saturation = num("saturation", f.saturation),
            temperature = num("temperature", f.temperature),
            tint = num("tint", f.tint),
            sharpen = num("sharpen", f.sharpen),
            vignette = num("vignette", f.vignette),
            sepia = num("sepia", f.sepia),
            invert = num("invert", f.invert),
            liftR = num("liftR", f.liftR),
            liftG = num("liftG", f.liftG),
            liftB = num("liftB", f.liftB),
            gammaR = num("gammaR", f.gammaR),
            gammaG = num("gammaG", f.gammaG),
            gammaB = num("gammaB", f.gammaB),
            gainR = num("gainR", f.gainR),
            gainG = num("gainG", f.gainG),
            gainB = num("gainB", f.gainB)
        )
        return out.copy(filters = graded)
    }

    private fun assetJson(a: MediaAsset): JSONObject = JSONObject()
        .put("id", a.id)
        .put("name", a.name)
        .put("kind", a.kind.id)
        .put("durationSec", a.durationSec)
        .put("sizeBytes", a.sizeBytes)
        .put("hasLocalCopy", a.localPath != null)

    private fun clipJson(c: TimelineClip, assets: Map<String, MediaAsset>): JSONObject {
        val duration = TimelineEngine.clipDuration(c, assets)
        val assetName = c.assetId?.let { assets[it]?.name }
        return JSONObject()
            .put("id", c.id)
            .put("track", c.track.key)
            .put("label", c.label.ifEmpty { assetName ?: c.track.label })
            .put("assetId", c.assetId ?: JSONObject.NULL)
            .put("assetName", assetName ?: JSONObject.NULL)
            .put("startSec", c.startAt)
            .put("endSec", c.startAt + duration)
            .put("durationSec", duration)
            .put("trimIn", c.trimIn)
            .put("trimOut", c.trimOut)
            .put("volume", c.volume)
            .put("scale", c.scale)
            .put("opacity", c.opacity)
            .put("rotation", c.rotation)
            .put("x", c.x)
            .put("y", c.y)
            .put("text", c.text)
            .put("graphic", c.graphic.ifEmpty { JSONObject.NULL })
            .put("speed", c.speed)
            .put("keyframeCount", c.keyframes.size)
    }

    private companion object {
        val SUPPORTED_PROTOCOLS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
    }
}
