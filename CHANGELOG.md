# Changelog

All notable changes to Edit Pro Studio (Android). Newest first.

## 3.0.0 — unreleased

The editing overhaul. The app now keeps separate projects, the timeline edits several clips at
once, animation is editable, the preview shows what the export will contain, and every keyboard
action can be rebound.

### Added
- **Projects, each in its own folder.** The app opens on a front page of project cards (thumbnail,
  length, clip count, size, when it was last edited). A card opens with a tap; its menu renames,
  duplicates or deletes it. A project owns its edit *and* the media imported into it, so deleting
  one removes exactly that project's files. New projects start from a dialog that asks for a
  name, a shape (16:9, 9:16, 1:1, 4:3), a size and a frame rate. The single autosaved edit of
  earlier versions becomes the first project, with its media moved into the project's folder.
- **Multi-clip editing.** Ctrl-click adds to the selection, Shift-click selects a range, dragging
  across empty space draws a marquee (long-press first on touch), and a *Multi* toggle makes every
  tap additive for fingers. A selection moves as a group and stops at the first clip in its way.
  Cut, copy and paste keep the clips' spacing; ripple delete, ripple trim (Q / W), trim to
  playhead, close gaps, insert space, split every track, select everything after the playhead and
  nudge by frames all work on the selection.
- **Dragging between tracks.** A clip follows the pointer up and down as well as along, onto any
  track that can hold it.
- **Detach audio.** A video clip's sound becomes its own clip on an audio track.
- **Replace media.** Swap the file behind a clip and keep its grade, motion and position.
- **Collapsible tracks.** Each track folds to a thin strip from its header; there are commands to
  fold all tracks and to fold the empty ones. A new **GRAPHICS** track sits between the picture
  tracks and the text track.
- **Collapsible panels.** The media library, the inspector and the timeline each fold away, and the
  preview can be maximised. The layout is remembered.
- **Context menus everywhere.** Right-click or long-press a clip, a track header, a marker, the
  ruler, empty timeline space, a library item or a project card. The clip menu is still the
  user's own list and now shows the key currently bound to each entry.
- **Keyframe editing.** Changing Scale, Position, Rotation or Opacity on an animated clip keys the
  change at the playhead, so a slider always changes what is on screen. Each keyframe chooses how
  it eases into the next (clip curve, linear, ease, ease in, ease out, hold). A keyframe lane in
  the timeline shows the selected clip's keyframes as diamonds that can be dragged, and the
  inspector lists them with previous / next / add / delete. Splitting or trimming an animated clip
  keeps the animation attached to the picture.
- **Motion graphics.** 17 templates: lower third, title card, typewriter, quote, chapter tag,
  credits roll, callout, subscribe button, countdown, number counter, running timer, progress bar,
  cinematic bars, rectangle, ellipse, divider line and arrow. Each one becomes an ordinary clip —
  retype it, recolour it, trim it, keyframe it. The picker shows a real frame of each.
- **Rebindable keyboard shortcuts.** About 85 actions, every one listed in *Keyboard shortcuts*
  with a *Change* button that waits for the next key press. A key already in use is only taken
  over after confirmation. New defaults include J / K / L shuttle, frame stepping with the arrow
  keys, Up / Down to jump between cuts, I / O for the export range and Ctrl+C / X / V.
- **Media library housekeeping.** Multi-select, add several items to the timeline in one go, rename,
  select unused media, and remove media from the project (with its clips, after a confirmation,
  and undoable). Items used on the timeline carry an *In use* badge.
- **Markers** can be renamed and deleted, show their labels, and the export range is drawn on the
  ruler.
- **Assistant and MCP tools** for the new operations: `list_keyframes`, `set_keyframe`,
  `remove_keyframe`, `list_motion_graphics`, `add_motion_graphic`, `select_clips`, `move_clips`
  and `edit_clips`. `set_track_state` can collapse a track and `update_clip` reaches the
  motion-graphics fields, speed and pan.

### Changed
- **The preview is drawn by the export compositor.** It now shows real video frames (it used to
  show a placeholder for video), and layer order, grade, motion, fades, titles and graphics are the
  same code path as the rendered file. While paused the nearest key frame appears at once and the
  exact frame follows when the playhead rests.
- **Export uses the exact video frame** for every output frame instead of the nearest key frame.
  Key frames are often a second apart, which made exported motion stutter. Renders that contain
  video are slower as a result.
- **Video clips carry their sound**, in the preview and in the export. Solo is resolved across
  picture and audio tracks, and picture tracks have their own mute and solo.
- **Export audio is processed**: the five-band EQ, the compressor, pan and a soft limiter are now
  applied. De-essing and reverb still are not; see `docs/MAINTENANCE.md`.
- **Edge trims stop at the neighbouring clip**, at the start of the timeline and at the ends of
  the source, and snap to cuts, markers and the playhead.
- **Timeline zoom** runs from a whole long edit down to a few frames, Ctrl + mouse wheel zooms
  around the pointer, Shift + wheel scrolls sideways, and *Fit* really fits long edits.
- **Undo covers the media library**, and a slider drag is one undo step.
- **Save writes into the project.** Projects autosave, and are saved when the app leaves the
  foreground. Exporting and importing `.filmcraft` files moved to Tools and the front page.
- **New, Open and Demo no longer ask before replacing your work**, because they no longer replace
  it: each is a separate project.
- **Changing the export shape or size changes the preview frame**, so what is framed is what is
  rendered.
- **Layers follow track order** (VIDEO 1 at the bottom), and at a cut only the incoming clip is
  drawn.
- **Clip speed changes clip length.** A 2× clip now occupies half its source span on the timeline.
- Still images are decoded at the size they are needed and the right way up (EXIF orientation).
- The project file is format version 3. It only adds fields (track states, motion graphics,
  keyframe easing), so files from earlier versions open unchanged.

### Fixed
- Dragging a clip a second time could send it back to where the first drag started, and dragging
  the playhead readout scrubbed from a stale position: the gesture code held on to old values.
- Trimming an edge into a neighbouring clip made the clip jump to the other side of it.
- Typing in the inspector's text fields with a hardware keyboard triggered editor shortcuts.
- Hover-scrub (*playhead follows cursor*) was offset by the scroll position once the timeline was
  scrolled.
- A long edit at high zoom could ask for a timeline wider than a layout can be.
- Portrait photos could appear on their side.
- The MCP server reported a fixed, out-of-date version number.

## 2.9.0 — unreleased

### Added
- **Phone support.** Below 600dp wide — or below 480dp tall, which catches a phone held sideways —
  the three-pane workspace becomes one pane at a time with a Media / Preview / Inspector switcher.
  The timeline stays visible underneath, because that is the surface you actually edit on.
- Splitters are not drawn on a phone; with one pane there is nothing to split, and a draggable
  5dp divider is just a place to mis-tap.

### Changed
- Icon buttons get a 44dp minimum touch target on phones, up from 30dp. Tablets keep the dense
  look they had.
- The header and timeline toolbar scroll horizontally on a phone instead of clipping controls off
  the edge; the header also drops the wordmark and narrows the project name field.
- Dialogs are capped at 86% of the window height as well as their own maximum, so they stay inside
  a short landscape window.
- One shared `rememberIsCompact()` replaces the per-file breakpoint checks, so the workspace,
  header, timeline and buttons cannot disagree about what device they are on.

## 2.8.0 — unreleased

### Added
- **Built-in editing assistant (bring your own key).** A chat panel that drives the same tools as
  the MCP server, but by calling the model directly from the app — no tunnel, no exposed port, no
  Termux. Works with any OpenAI-compatible endpoint (OpenAI, Gemini, OpenRouter, Groq, DeepSeek,
  Mistral, Ollama and friends). See [docs/ASSISTANT.md](docs/ASSISTANT.md).
- The API key is encrypted with a hardware-backed Android Keystore key; only ciphertext reaches
  preferences.
- Cleartext HTTP is permitted to 127.0.0.1 only, so a local model server works without weakening
  anything else.

### Changed
- **MCP is now opt-in** (default off). The built-in assistant is the normal path; turning the
  socket on is the user's choice and their responsibility to run and secure.

## 2.7.0 — unreleased

### Added
- **Tunnel support, and a guide for it.** [docs/TUNNEL.md](docs/TUNNEL.md) walks through exposing
  the MCP server to a cloud agent with `cloudflared` in Termux. The socket still binds to
  127.0.0.1 — the tunnel client connects locally, so nothing is exposed on the LAN.
- **SSE framing.** Clients that advertise `Accept: text/event-stream` now get the response as a
  single SSE `message` event instead of plain JSON, which unblocks several MCP clients.

### Security
- **Tokens are now 24 characters** (~120 bits) instead of 8, because the endpoint may face the
  internet. Shorter legacy tokens are replaced on upgrade.
- **Constant-time token comparison**, plus a brake that pauses authentication for 30 s after five
  consecutive failures.
- Rejected requests are logged with the caller's forwarded IP.

## 2.6.0 — unreleased

### Changed
- **Agent access is always on.** A foreground service keeps the MCP socket reachable while the
  editor is off screen, which is when an agent on the same tablet needs it. The on/off choice is
  persisted (default on) and no longer has to be flipped before every session.
- The view model is now process-wide so the service and the UI act on the same project state.

### Added
- **`fetch_url` — the agent's browser.** Downloads an image, video or audio file from a direct URL
  into the media library, with the same duration probe, filmstrip and waveform as a hand import,
  so `add_clip` can use it immediately. http/https only, 250 MB cap, 60 s timeout.

## 2.5.0 — unreleased

### Added
- **Agent access over MCP.** The app can host a local Model Context Protocol server so an AI agent
  can read and edit the open project. Off by default; start it from *Tools → Agent access (MCP)*.
  Bound to 127.0.0.1, bearer-token auth, 19 editing tools. See [docs/MCP.md](docs/MCP.md).
- Agent edits run through the same view-model operations as the UI, so they are undoable and
  indistinguishable from hand edits. No export trigger and no timeline-wide clear by design.

## 2.4.0 — unreleased

### Changed
- **New app icon.** The placeholder film-strip glyph is replaced by the supplied hexagonal mark,
  built as a real adaptive icon (foreground at five densities, kept inside the 66% safe zone) with
  legacy launcher icons for launchers that ignore adaptive icons.
- **macOS chrome removed.** The three window dots in the header are gone — they read as buttons,
  mean nothing on Android and cluttered the bar. Full screen moved to the Tools panel. All
  Cmd/Opt/Ctrl/Shift glyph badges are gone too; mode hints read `Alt+1` instead of the Option
  symbol.
- **The clip context menu is now configurable and looks like a desktop menu.** Compact rows, the
  shortcut hint right-aligned, destructive actions tinted, separators between groups. Add, remove
  and reorder entries from *Customise clip menu* in the Tools panel; defaults are clip-editing
  actions only, with mode switches and camera/audio rigs opt-in.
- **Scrubbing responds on the first movement** — the ruler seeks on drag-start as well as on drag.
- **Repository** renamed from `EditPro-proprietary-` to `editpro-studio` and released under MIT.

### Added
- **Scrub-follow mode (Alt+F).** While enabled, moving the mouse over the timeline moves the
  playhead with no click. Persisted across launches.

## 2.3.0 — unreleased

### Fixed
- **Keyboard shortcuts never fired at all.** Space, S, Backspace/Delete and the rest were bound
  correctly, but Compose only dispatches hardware key events to a *focused* node and nothing in
  the app ever requested focus. The workspace root now takes focus when the editor opens and
  reclaims it whenever a dialog closes. This was one root cause behind three reported bugs.
- **Library tiles had no thumbnails.** Images now decode from their local copy and videos use the
  first extracted filmstrip frame, with a play badge; audio and solids keep their labels.
- **The export sheet was oversized.** It is now a compact card capped at 400dp: a single summary
  line (resolution · fps · aspect · duration), settings behind an *Options* toggle, and progress
  only while rendering. The unused auto-duck checkbox was removed — ducking was never applied.
- **After an export the sheet showed too much.** It now collapses to "Saved to Photos" with
  exactly two actions: **Close** and **Share**. Reopening it offers a fresh export.

### Added
- **Drag assets onto the timeline.** Long-press a library tile to pick it up; a ghost follows the
  pointer, and the drop point picks the track (vertically) and the time (horizontally). Dropping
  outside the timeline, or onto a track that cannot hold that media kind, falls back to the
  asset's natural track. Double-tap still appends at the end of the track.

## 2.2.0 — unreleased

Fixes for four reported problems. No new features.

### Fixed
- **Audio was silent everywhere.** There was no audio playback code at all, and the exporter
  wrote a single video track. The preview now plays the timeline's audio clips (cued to the
  playhead, with volume, fades and automation), and the exported MP4 carries a stereo AAC track.
- **Exports never appeared in Photos.** Renders were written to app-private storage, which the
  gallery cannot see. Finished files are now also published to MediaStore under `Movies/EditPro`.
- **The three dots in the header did nothing.** They looked like macOS window buttons but had no
  click handler. Red now returns to the project home, yellow minimises the app, green toggles
  immersive full screen.
- **Right-click did nothing on clips.** The clip context menu existed but was reachable only by
  long-press, which is useless with a mouse. A secondary-button press now opens it.
- **Seven advertised shortcuts were unbound.** The header badges showed Cmd/Ctrl+N, S, O, E, K, I
  and Ctrl+Alt+P, but none of them were handled — pressing them did nothing. All now work and go
  through the same "discard current work?" guard as the buttons.

### Added
- **Keyboard & mouse shortcuts sheet** (Tools panel), so the bindings are discoverable instead of
  being guessed from badges.

## 2.1.0 — unreleased

A reliability and usability pass driven by the incremental improvement roadmap
(Section 1 in full, plus the highest-value items from Sections 2 and 3).

### Fixed
- **Editor shortcuts no longer steal keystrokes.** The workspace handled keys with
  `onPreviewKeyEvent`, which runs before the focused child, so typing in the project-name
  or media-search field triggered editor actions instead: `s` split the clip, space toggled
  playback, `Delete` removed clips, `t` added a text clip. Both fields now report focus and
  the global handler stands down while either has it.
- **Saving can no longer crash the app.** The Save / Open / subtitle-import / preset-export
  callbacks had no error handling; a failed write threw out of the activity callback. They
  now report success only after the write completes and surface failures in a dialog.
- **Rendered videos are no longer left in the cache.** Exports were written to
  `cacheDir/exports`, which Android may clear at any time. They now go to the app's private
  files directory, and the folder is capped at the newest 12 renders.
- **The exported video is reachable at last.** `lastExportedFilePath` was set but never read,
  and the declared `FileProvider` was never used, so a finished render was unreachable. The
  export dialog now shows the file (name + size) with **Open** and **Share** actions.
- **The global Reset no longer deletes keyframes.** It silently wiped animation; the Motion
  tab's *Clear keyframes* is now the explicit way to do that.

### Added
- **Project home screen** shown before the editor: New / Open / Continue, plus a recent-project
  list recorded on save. It states plainly that Android apps cannot browse storage on their
  own, and *Remove* only clears the list entry — never the saved file.
- **Confirm before discarding work**: New / Open / Load-demo ask first when the timeline has
  content.
- **Cancel a render**, including a whole render queue. The busy flag is always cleared so the
  UI cannot get stuck.
- **Import reporting**: a batch import now says how many files were imported, skipped as
  duplicates, or could not be read, and names them.
- **Duplicate detection** on import (same name + byte size + duration); the redundant copy is
  deleted so no orphan files accumulate.
- **Missing-media state**: assets with no local copy are flagged in the library, and the health
  scan reports media files that have disappeared from the device.
- **Group-scoped resets**: *Reset transform* (Layout tab) and *Reset colour & effects* (Effects
  tab) touch only their own group.
- Touch targets now meet a 40dp minimum and action buttons declare `Role.Button`.

### Changed
- Render-queue results now report successes and failures instead of a bare "finished" message.
- Five controls that had no effect on the rendered output are now labelled **"· not rendered
  yet"** in the inspector (Blur, Hue Shift, Film Grain, Shutter Motion Blur, Chromatic
  Aberration). They still store their values for future use — they are just no longer
  presented as if they did something.

## 2.0.0
- Initial Android port: multi-track timeline, colour grading with 3-way wheels and scopes,
  motion presets and 3D rigs, audio EQ/mastering, and native `MediaCodec` + `MediaMuxer`
  H.264 export.
