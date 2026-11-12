# Maintenance notes

Practical notes for whoever works on this app next. Everything here was written by reading
the source; where something has not actually been run, it says so.

## How this project is verified

**Compilation is verified automatically; behaviour is not.** Every push runs the GitHub Actions
workflow, which executes the JVM unit tests and builds both APKs. A green run means the code
compiles and the existing unit tests pass — it does **not** mean any UI flow was exercised.
There is no instrumentation test suite.

The unit tests cover the pure logic only: the timeline engine (snapping, collision resolution,
interpolation and easing, audio envelope, SRT parsing), multi-clip edits (split, ripple, trim,
paste, group moves, edge trims), keyframe edits, the key map and rebinding, project storage on
disk, the project file round-trip, motion-graphics timing, audio DSP, preset catalog sizes and
export dimension maths.

The 3.0 editing overhaul (project list, multi-select timeline, keyframe lane, motion graphics,
shared preview compositor, rebindable shortcuts) was written without a device to run it on: it
compiles, its logic is unit-tested and CI builds it, but **none of its screens or gestures had
been exercised on a device when it was merged**. The checklist below is the list of what to try
first.

Anything touching Compose UI, MediaCodec, the file system or the Storage Access Framework has
**never been run in an automated test**. Treat those changes as unverified until someone
installs the APK and tries them.

## Manual test checklist

Worth walking through after any change to the editor. Roughly ordered by how much it would hurt
if it broke.

1. **Project round-trip** — create a project, import media, add clips, go back to the project
   list (the *Projects* button or Back) and open it again. Confirm the timeline, markers, track
   states and clip properties survive, and that a force-stop and relaunch loses nothing.
2. **Project file export / import** — Tools → *Export project file*, then *Import project file*
   from the project list. It should arrive as a second project with its own copy of the media.
   Exporting to a location you cannot write should give an "EXPORT FAILED" dialog, not a crash.
3. **Shortcut/typing separation** — with a hardware keyboard, type a name containing `s`, `t`,
   spaces and Delete into the project-name field, the library search, and the inspector's *Clip
   name* and title text fields. Nothing on the timeline should change.
4. **Export and retrieve** — render a short project (2–5 s), then use **Open** and **Share** in
   the export dialog. Confirm the file plays, has sound, and shows up in your Photos/Gallery app
   under `Movies/EditPro`.
5. **Cancel a render** — start a longer render and hit *Cancel export*. The progress bar should
   stop, the button should return to *Render MP4*, and the app should stay usable.
6. **Import batch** — import several files at once, including one unsupported file and one
   duplicate. Confirm the summary names what was skipped.
7. **Project list** — relaunch the app and confirm the project list appears with a card per
   project. Rename, duplicate and delete a project from its menu (⋯, right-click or long-press);
   deleting should ask first, and should remove that project's media from the device but leave
   other projects and exported videos alone.
8. **Upgrade from 2.x** — install over a 2.x build that has an autosaved edit. The edit should
   appear as the first project, with its media playable.
9. **Undo/redo** — after each of the above, undo and redo and confirm the timeline returns to
   the expected states.
10. **Rendered output vs preview** — see the table below.
11. **Audio in preview** — import an audio file, add it to an audio track, press play and confirm
    you hear it; then mute that track and confirm it goes silent.
12. **Right-click a clip** — with a mouse, secondary-click a clip and confirm the context menu
    opens (long-press still works for touch).
13. **Shortcuts** — try Space, J / K / L, the arrow keys, S, Q, W, Ctrl+C / X / V, Ctrl+Z, Ctrl+S,
    Ctrl+E and Ctrl+K. Then open Tools → *Keyboard shortcuts*, rebind one action to a key another
    action uses, confirm the takeover prompt, and check both the new key and *Reset* work.
14. **Selecting and moving** — Ctrl-click and Shift-click clips, drag a marquee over empty space
    (long-press first on touch), drag the selection, and drag a single clip onto another track.
    Drag the same clip twice in a row: the second drag must start from where the first ended.
15. **Trimming** — drag both edges of a clip towards its neighbours and past the ends of its
    media. The edge should stop, never overlap and never make the clip jump.
16. **Keyframes** — add two keyframes with different scale, scrub between them, drag a diamond in
    the keyframe lane, change an easing, then split the clip and confirm both halves still move
    as before.
17. **Motion graphics** — add a lower third and a countdown, edit their text and colours, and
    compare the preview with a short export.
18. **Video preview** — put a camera video on the timeline, scrub and play it. The preview should
    show the video's own frames (at a reduced frame rate) with sound.
19. **Panels** — collapse and restore the library, the inspector and the timeline, maximise the
    preview, and relaunch to confirm the collapsed state is remembered.

## What actually renders

The preview and the exporter draw every frame with the same function,
`NativeVideoExporter.renderCompositeFrame`: an Android `Canvas` plus `ColorMatrixColorFilter`, with
titles and motion graphics drawn by `TitleRenderer`. The preview calls it off the main thread
through `PreviewCompositor` at a reduced resolution. So a control either reaches both or neither;
the table keeps both columns to make that visible.

| Control | Preview | Export |
| --- | --- | --- |
| Brightness, Contrast, Saturation | yes | yes |
| Temperature, Tint | yes | yes |
| Lift / Gamma / Gain (3-way wheels) | yes | yes |
| Sepia, Invert | yes | yes |
| Vignette | yes | yes |
| Sharpen | small brightness boost only | same |
| Scale, Opacity, Rotation, Position, Flip | yes | yes |
| 3D Tilt / Swivel | yes | yes |
| Anamorphic Squeeze | yes | yes |
| Text / titles | yes | yes |
| Motion graphics (17 templates) | yes | yes |
| Fade / dissolve entrance, fade-out | yes | yes |
| Other entrance transitions | not drawn | not drawn |
| Exit transitions | all drawn as a fade | same |
| Blend modes, corner radius | yes | yes |
| **Blur** | **no** | **no** |
| **Hue Shift** | **no** | **no** |
| **Film Grain** | **no** | **no** |
| **Shutter Motion Blur** | **no** | **no** |
| **Chromatic Aberration** | **no** | **no** |

The five controls in bold now carry a **"· not rendered yet"** label in the inspector so they are
not mistaken for working effects. Their values are still stored in the project file, so
implementing them later will light up existing projects rather than requiring a migration.

Audio is closer to complete than it was. **Volume, fades and volume automation are applied** in
both preview and export, and a **video clip's own sound** is included in both. The export mixes
every audible clip into one stereo AAC track at 44.1 kHz and applies **the five-band EQ, the
compressor, pan and a soft limiter**. The preview applies pan and clip speed but not EQ or
compression, so a heavily processed clip sounds plainer while editing than in the render.
**De-essing and reverb are stored and shown in the UI but are not applied** anywhere.

## Known gaps and follow-ups

- **Video preview is not real-time.** Frames come from `MediaMetadataRetriever`, which decodes
  from the previous key frame every time. That is exact, but a frame costs tens to hundreds of
  milliseconds, so video plays in the preview at a few frames per second (sound is unaffected).
  A streaming `MediaCodec` decoder would fix both this and the next point.
- **Exports with video are slow** for the same reason: every output frame asks for the exact
  source frame. The previous behaviour (nearest key frame) was fast and visibly wrong.
- **Export audio: de-essing and reverb are not processed.** Audio is capped at 5 minutes per
  render and clip `speed` shifts pitch rather than time-stretching. Shuttling the preview (J / L
  at other than normal speed) is silent.
- **Only fade and dissolve entrances render.** Other entrance transitions are stored but not
  drawn, and every exit transition is drawn as a fade. Clip transitions do not apply to motion
  graphics, which have their own intro and outro.
- **Group drags stay on their tracks.** A single clip can be dragged to another track; a
  multi-selection moves along the timeline only (use *Move to track* in the clip menu).
- **Removed media is deleted when the project is next opened**, not when it is removed, so undo
  can bring it back for the rest of the session.
- **Gallery publishing is best-effort.** On Android 10+ it goes through MediaStore and needs no
  permission; on Android 8–9 it needs `WRITE_EXTERNAL_STORAGE` and falls back to a message if
  that is denied. A publish failure never fails the export.
- **Exports accumulate** in the app's files directory. The folder is capped at the newest 12
  renders; if a longer retention policy is wanted, that cap is in `NativeVideoExporter`.
- **No instrumentation tests.** The manual checklist above is the substitute.
- **Preset organisation** (roadmap Section 2, item 6) is partly present: presets are grouped in
  the preset hub and there is JSON import/export with validation. Per-category search inside the
  inspector's look list exists; a global preset search does not.
- **Face tracking** (roadmap Section 3, item 4) was deliberately not attempted. It needs a real
  tracking model, and adding one would mean either a large on-device dependency or a network
  service, which the project's rules rule out. There is no placeholder button for it.

## Release signing

Release builds are signed only when a keystore is configured, and stay unsigned otherwise — the
build never fails because of it. To enable signing, add four repository secrets
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); the workflow decodes them
into `KEYSTORE_FILE` and the build picks them up. All builds must use the same keystore, or an
update will not install over a previously installed copy.
