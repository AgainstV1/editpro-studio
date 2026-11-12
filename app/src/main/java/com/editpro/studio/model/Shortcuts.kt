package com.editpro.studio.model

/**
 * Every action that can be driven from the keyboard, with its default key chords.
 *
 * This is the single source of truth: the key handler resolves a pressed chord through
 * [ShortcutBindings], the shortcuts sheet lists these entries, and rebinding only stores the
 * chords that differ from the defaults. Chords are plain strings ("Ctrl+Shift+Z") so the whole
 * thing is testable without Compose.
 */
enum class EditorShortcut(
    val id: String,
    val label: String,
    val group: String,
    vararg defaultChords: String
) {
    // Transport
    PLAY_PAUSE("play", "Play / pause", "Transport", "Space"),
    SHUTTLE_BACK("shuttle_back", "Play backwards (again for faster)", "Transport", "J"),
    SHUTTLE_STOP("shuttle_stop", "Stop", "Transport", "K"),
    SHUTTLE_FORWARD("shuttle_forward", "Play forwards (again for faster)", "Transport", "L"),
    STEP_BACK("step_back", "Back one frame", "Transport", "Left"),
    STEP_FORWARD("step_forward", "Forward one frame", "Transport", "Right"),
    JUMP_BACK("jump_back", "Back one second", "Transport", "Shift+Left"),
    JUMP_FORWARD("jump_forward", "Forward one second", "Transport", "Shift+Right"),
    GO_START("go_start", "Go to start", "Transport", "Home"),
    GO_END("go_end", "Go to end", "Transport", "End"),
    PREV_EDIT("prev_edit", "Previous cut", "Transport", "Up"),
    NEXT_EDIT("next_edit", "Next cut", "Transport", "Down"),

    // Editing
    SPLIT("split", "Split at playhead", "Editing", "S", "Ctrl+B"),
    SPLIT_ALL("split_all", "Split all tracks at playhead", "Editing", "Shift+S"),
    DELETE("delete", "Delete selection", "Editing", "Delete", "Backspace"),
    RIPPLE_DELETE("ripple_delete", "Ripple delete selection", "Editing", "Shift+Delete", "Shift+Backspace"),
    RIPPLE_TRIM_START("ripple_trim_start", "Ripple trim start to playhead", "Editing", "Q"),
    RIPPLE_TRIM_END("ripple_trim_end", "Ripple trim end to playhead", "Editing", "W"),
    TRIM_START("trim_start", "Trim start to playhead", "Editing", "Alt+Q"),
    TRIM_END("trim_end", "Trim end to playhead", "Editing", "Alt+W"),
    NUDGE_LEFT("nudge_left", "Nudge one frame left", "Editing", "Comma"),
    NUDGE_RIGHT("nudge_right", "Nudge one frame right", "Editing", "Period"),
    NUDGE_LEFT_BIG("nudge_left_big", "Nudge ten frames left", "Editing", "Shift+Comma"),
    NUDGE_RIGHT_BIG("nudge_right_big", "Nudge ten frames right", "Editing", "Shift+Period"),
    DUPLICATE("duplicate", "Duplicate selection", "Editing", "Ctrl+D"),
    CLOSE_GAPS("close_gaps", "Close gaps on the selected track", "Editing", "Ctrl+G"),
    DETACH_AUDIO("detach_audio", "Detach audio from video clip", "Editing", "Ctrl+Shift+D"),
    ADD_TEXT("add_text", "Add text clip", "Editing", "T"),
    ADD_GRAPHIC("add_graphic", "Add motion graphic", "Editing", "G"),
    UNDO("undo", "Undo", "Editing", "Ctrl+Z"),
    REDO("redo", "Redo", "Editing", "Ctrl+Shift+Z", "Ctrl+Y"),

    // Clipboard & selection
    COPY("copy", "Copy clips", "Clipboard & selection", "Ctrl+C"),
    CUT("cut", "Cut clips", "Clipboard & selection", "Ctrl+X"),
    PASTE("paste", "Paste clips at playhead", "Clipboard & selection", "Ctrl+V"),
    COPY_ATTRS("copy_attrs", "Copy clip attributes", "Clipboard & selection", "Ctrl+Alt+C"),
    PASTE_ATTRS("paste_attrs", "Paste clip attributes", "Clipboard & selection", "Ctrl+Alt+V"),
    SELECT_ALL("select_all", "Select all clips", "Clipboard & selection", "Ctrl+A"),
    SELECT_FORWARD("select_forward", "Select everything after the playhead", "Clipboard & selection", "Shift+A"),
    DESELECT("deselect", "Deselect", "Clipboard & selection", "Escape", "Ctrl+Shift+A"),
    SELECT_PREV("select_prev", "Select previous clip", "Clipboard & selection", "Ctrl+Left"),
    SELECT_NEXT("select_next", "Select next clip", "Clipboard & selection", "Ctrl+Right"),
    SELECTION_MODE("selection_mode", "Multi-select mode (tap to add)", "Clipboard & selection", "Shift+V"),

    // Keyframes & markers
    ADD_KEYFRAME("add_keyframe", "Add keyframe at playhead", "Keyframes & markers", "Shift+K"),
    DELETE_KEYFRAME("delete_keyframe", "Delete keyframe at playhead", "Keyframes & markers", "Alt+Delete", "Alt+Backspace"),
    PREV_KEYFRAME("prev_keyframe", "Previous keyframe", "Keyframes & markers", "Alt+Left"),
    NEXT_KEYFRAME("next_keyframe", "Next keyframe", "Keyframes & markers", "Alt+Right"),
    KEYFRAME_LANE("keyframe_lane", "Show / hide keyframe editor", "Keyframes & markers", "Alt+K"),
    ADD_MARKER("marker", "Add marker", "Keyframes & markers", "M", "Ctrl+M"),
    NEXT_MARKER("next_marker", "Next marker", "Keyframes & markers", "Shift+M"),
    PREV_MARKER("prev_marker", "Previous marker", "Keyframes & markers", "Ctrl+Shift+M"),
    MARK_IN("mark_in", "Set export in point", "Keyframes & markers", "I"),
    MARK_OUT("mark_out", "Set export out point", "Keyframes & markers", "O"),
    CLEAR_RANGE("clear_range", "Clear export in / out", "Keyframes & markers", "Alt+X"),

    // Timeline view
    ZOOM_IN("zoom_in", "Zoom timeline in", "Timeline view", "Equals"),
    ZOOM_OUT("zoom_out", "Zoom timeline out", "Timeline view", "Minus"),
    ZOOM_FIT("zoom_fit", "Fit timeline to window", "Timeline view", "Shift+Z"),
    TOGGLE_SNAP("snap", "Magnetic snapping", "Timeline view", "N"),
    TOGGLE_FOLLOW("follow", "Follow playhead", "Timeline view", "F"),
    TOGGLE_SCRUB("scrub", "Playhead follows cursor", "Timeline view", "Alt+F"),
    COLLAPSE_TRACKS("collapse_tracks", "Collapse / expand all tracks", "Timeline view", "Shift+T"),

    // Panels
    TOGGLE_LIBRARY("panel_library", "Show / hide media library", "Panels", "Ctrl+1"),
    TOGGLE_INSPECTOR("panel_inspector", "Show / hide inspector", "Panels", "Ctrl+2"),
    TOGGLE_TIMELINE("panel_timeline", "Show / hide timeline", "Panels", "Ctrl+3"),
    MAXIMIZE_PREVIEW("maximize_preview", "Maximise preview", "Panels", "Grave"),
    FULLSCREEN("fullscreen", "Full screen", "Panels", "F11"),

    // Project
    NEW_PROJECT("new", "New project", "Project", "Ctrl+N"),
    OPEN_PROJECT_FILE("open", "Import project file", "Project", "Ctrl+O"),
    SAVE("save", "Save project", "Project", "Ctrl+S"),
    EXPORT_PROJECT_FILE("save_as", "Export project file", "Project", "Ctrl+Shift+S"),
    IMPORT_MEDIA("import", "Import media", "Project", "Ctrl+I"),
    EXPORT_VIDEO("export", "Export video", "Project", "Ctrl+E"),
    HOME("home", "Back to projects", "Project", "Ctrl+W"),

    // Workspace
    TOOLS("tools", "Tools panel", "Workspace", "Ctrl+K"),
    PRESETS("presets", "Preset packs", "Workspace", "Ctrl+Alt+P"),
    SHORTCUTS("shortcuts", "Keyboard shortcuts", "Workspace", "Ctrl+Slash"),
    ASSISTANT("assistant", "Editing assistant", "Workspace", "Ctrl+J"),
    MODE_EDIT("mode_edit", "Normal mode", "Workspace", "Alt+1"),
    MODE_COLOR("mode_color", "Colour mode", "Workspace", "Alt+2"),
    MODE_MOTION("mode_motion", "Motion mode", "Workspace", "Alt+3"),
    MODE_MUSIC("mode_music", "Music mode", "Workspace", "Alt+4"),
    TOGGLE_SCOPES("scopes", "Toggle scopes", "Workspace", "Alt+S"),
    GRADE_SPLIT("grade_split", "Toggle grade split", "Workspace", "Backslash"),
    TOGGLE_THEME("theme", "Light / dark theme", "Workspace", "Alt+L", "Ctrl+Shift+L");

    val defaults: List<String> = defaultChords.map { ShortcutChords.normalize(it) }

    companion object {
        fun fromId(id: String): EditorShortcut? = entries.find { it.id == id }
        val groups: List<String> get() = entries.map { it.group }.distinct()
    }
}

/** Canonical chord strings: modifiers in the order Ctrl, Alt, Shift, then the key name. */
object ShortcutChords {

    private val KEY_ALIASES = mapOf(
        " " to "Space", "spacebar" to "Space", "space" to "Space",
        "del" to "Delete", "delete" to "Delete", "backspace" to "Backspace",
        "esc" to "Escape", "escape" to "Escape", "enter" to "Enter", "return" to "Enter",
        "arrowleft" to "Left", "arrowright" to "Right", "arrowup" to "Up", "arrowdown" to "Down",
        "left" to "Left", "right" to "Right", "up" to "Up", "down" to "Down",
        "home" to "Home", "end" to "End", "pageup" to "PageUp", "pagedown" to "PageDown", "tab" to "Tab",
        "," to "Comma", "comma" to "Comma", "." to "Period", "period" to "Period",
        "/" to "Slash", "slash" to "Slash", "\\" to "Backslash", "backslash" to "Backslash",
        ";" to "Semicolon", "semicolon" to "Semicolon", "'" to "Apostrophe", "apostrophe" to "Apostrophe",
        "[" to "LeftBracket", "leftbracket" to "LeftBracket", "]" to "RightBracket", "rightbracket" to "RightBracket",
        "-" to "Minus", "minus" to "Minus", "=" to "Equals", "equals" to "Equals",
        "`" to "Grave", "grave" to "Grave"
    )

    private val PRETTY = mapOf(
        "Comma" to ",", "Period" to ".", "Slash" to "/", "Backslash" to "\\", "Semicolon" to ";",
        "Apostrophe" to "'", "LeftBracket" to "[", "RightBracket" to "]", "Minus" to "−", "Equals" to "=",
        "Grave" to "`", "Left" to "←", "Right" to "→", "Up" to "↑", "Down" to "↓", "Escape" to "Esc"
    )

    fun canonicalKey(raw: String): String {
        val trimmed = if (raw == " ") raw else raw.trim()
        KEY_ALIASES[trimmed.lowercase()]?.let { return it }
        if (trimmed.length == 1) return trimmed.uppercase()
        if (trimmed.length in 2..3 && (trimmed[0] == 'f' || trimmed[0] == 'F') &&
            trimmed.drop(1).all { it.isDigit() }
        ) return "F" + trimmed.drop(1)
        return trimmed.replaceFirstChar { it.uppercase() }
    }

    fun of(key: String, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false): String =
        buildString {
            if (ctrl) append("Ctrl+")
            if (alt) append("Alt+")
            if (shift) append("Shift+")
            append(canonicalKey(key))
        }

    /** Accepts loose input ("ctrl+shift+z", "Cmd+S", "Shift+Delete") and returns the canonical chord. */
    fun normalize(raw: String): String {
        if (raw.isBlank() && raw != " ") return ""
        // A chord made of the plus key itself is written with its name, so splitting on '+' is safe.
        val parts = raw.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return if (raw.contains(' ')) "Space" else ""
        var ctrl = false
        var alt = false
        var shift = false
        var key = ""
        for (part in parts) {
            when (part.lowercase()) {
                "ctrl", "control", "cmd", "command", "meta" -> ctrl = true
                "alt", "option", "opt" -> alt = true
                "shift" -> shift = true
                else -> key = part
            }
        }
        if (key.isEmpty()) return ""
        return of(key, ctrl, alt, shift)
    }

    /** Human-readable form for the shortcuts sheet and menus ("Ctrl+←", "Shift+,"). */
    fun pretty(chord: String): String {
        if (chord.isEmpty()) return "—"
        val parts = chord.split('+')
        val key = parts.last()
        return (parts.dropLast(1) + (PRETTY[key] ?: key)).joinToString("+")
    }
}

/**
 * The effective key map: the defaults with the user's [overrides] applied.
 *
 * An override maps an action id to one chord, replacing all of that action's defaults; an empty
 * string unbinds the action. Immutable — rebinding returns a new instance.
 */
class ShortcutBindings(overrides: Map<String, String> = emptyMap()) {

    val overrides: Map<String, String> = overrides
        .filterKeys { EditorShortcut.fromId(it) != null }
        .mapValues { ShortcutChords.normalize(it.value) }

    private val byChord: Map<String, EditorShortcut> = buildMap {
        // Explicit choices win over defaults when the two collide.
        for (action in EditorShortcut.entries) {
            val custom = this@ShortcutBindings.overrides[action.id] ?: continue
            if (custom.isNotEmpty() && !containsKey(custom)) put(custom, action)
        }
        for (action in EditorShortcut.entries) {
            if (this@ShortcutBindings.overrides.containsKey(action.id)) continue
            for (chord in action.defaults) if (!containsKey(chord)) put(chord, action)
        }
    }

    fun chords(action: EditorShortcut): List<String> {
        val custom = overrides[action.id] ?: return action.defaults.filter { byChord[it] == action }
        return if (custom.isEmpty() || byChord[custom] != action) emptyList() else listOf(custom)
    }

    fun isCustom(action: EditorShortcut): Boolean = overrides.containsKey(action.id)

    fun resolve(chord: String): EditorShortcut? = byChord[ShortcutChords.normalize(chord)]

    fun display(action: EditorShortcut): String {
        val list = chords(action)
        return if (list.isEmpty()) "—" else list.joinToString(" / ") { ShortcutChords.pretty(it) }
    }

    /** First chord of an action, pretty-printed, for menu hints. Null when unbound. */
    fun hint(action: EditorShortcut): String? = chords(action).firstOrNull()?.let { ShortcutChords.pretty(it) }

    /**
     * Binds [chord] to [action]. Whatever action held that chord before gives it up: it keeps its
     * other chord if it has one and otherwise becomes unbound, so a key never does two things.
     */
    fun rebind(action: EditorShortcut, chord: String): ShortcutBindings {
        val normalized = ShortcutChords.normalize(chord)
        val next = overrides.toMutableMap()
        if (normalized.isNotEmpty()) {
            val previous = byChord[normalized]
            if (previous != null && previous != action) {
                next[previous.id] = chords(previous).firstOrNull { it != normalized } ?: ""
            }
        }
        next[action.id] = normalized
        return ShortcutBindings(next)
    }

    fun reset(action: EditorShortcut): ShortcutBindings = ShortcutBindings(overrides - action.id)
}
