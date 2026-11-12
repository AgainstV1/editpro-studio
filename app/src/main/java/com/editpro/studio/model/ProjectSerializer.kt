package com.editpro.studio.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class ProjectDocument(
    val format: String = "filmcraft-project",
    val version: Int = 2,
    val name: String,
    val createdAt: String = Instant.now().toString(),
    val settings: ProjectSettings,
    val clips: List<TimelineClip>,
    val markers: List<TimelineMarker>,
    val assets: List<MediaAsset>,
    /** Per-track lock / hide / mute / solo / collapse. Empty for files written before version 3. */
    val trackStates: Map<TimelineTrack, TrackState> = emptyMap()
)

data class UniversalPresetBundle(
    val format: String = "editpro-preset-bundle",
    val version: Int = 2,
    val name: String = "Edit Pro Universal Equipment Bundle",
    val styles: Map<String, TimelineClip>,
    val motions: Map<String, List<Keyframe>>,
    val equipment: Map<String, EquipmentRigPreset>,
    val audioPresets: Map<String, AudioGearPreset>
)

object ProjectSerializer {

    fun serializeProject(
        name: String,
        settings: ProjectSettings,
        clips: List<TimelineClip>,
        markers: List<TimelineMarker>,
        assets: List<MediaAsset>,
        trackStates: Map<TimelineTrack, TrackState> = emptyMap()
    ): String {
        val root = JSONObject()
        root.put("format", "filmcraft-project")
        // Version 3 adds optional fields only (track states, motion graphics, per-keyframe easing,
        // waveform and filmstrip caches). Version 2 files still open, and a version 2 reader
        // simply ignores what it does not know.
        root.put("version", 3)
        root.put("name", name)
        root.put("createdAt", Instant.now().toString())
        root.put("settings", JSONObject().apply {
            put("width", settings.width)
            put("height", settings.height)
            put("fps", settings.fps)
        })
        root.put("clips", JSONArray().apply {
            clips.forEach { put(clipToJson(it)) }
        })
        root.put("markers", JSONArray().apply {
            markers.forEach { m ->
                put(JSONObject().apply {
                    put("time", m.time)
                    put("label", m.label)
                })
            }
        })
        root.put("assets", JSONArray().apply {
            assets.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("type", a.mimeType)
                    put("kind", a.kind.id)
                    put("size", a.sizeBytes)
                    put("duration", a.durationSec)
                    put("bin", a.bin)
                    put("favorite", a.favorite)
                    if (a.colorHex != null) put("color", a.colorHex)
                    if (a.uriString != null) put("uriString", a.uriString)
                    if (a.localPath != null) put("localPath", a.localPath)
                    if (a.durationGuess) put("durationGuess", true)
                    // Cached so reopening a project does not lose its waveforms and thumbnails.
                    if (a.waveformPeaks.isNotEmpty()) {
                        put("waveform", JSONArray().apply {
                            a.waveformPeaks.forEach { peak -> put(Math.round(peak * 1000f) / 1000.0) }
                        })
                    }
                    if (a.filmstripPaths.isNotEmpty()) put("filmstrip", JSONArray(a.filmstripPaths))
                })
            }
        })
        if (trackStates.isNotEmpty()) {
            root.put("tracks", JSONObject().apply {
                trackStates.forEach { (track, ts) ->
                    put(track.key, JSONObject().apply {
                        put("locked", ts.locked)
                        put("hidden", ts.hidden)
                        put("muted", ts.muted)
                        put("solo", ts.solo)
                        put("collapsed", ts.collapsed)
                    })
                }
            })
        }
        root.put("note", "Portable EditPro / Filmcraft edit decision list.")
        return root.toString(2)
    }

    fun deserializeProject(rawJson: String): ProjectDocument {
        val obj = JSONObject(rawJson)
        val fmt = obj.optString("format", "")
        require(fmt == "filmcraft-project") { "Not a valid Filmcraft / EditPro project file" }
        val name = obj.optString("name", "Imported Project")
        val settingsObj = obj.optJSONObject("settings")
        val settings = ProjectSettings(
            width = settingsObj?.optInt("width", 1280) ?: 1280,
            height = settingsObj?.optInt("height", 720) ?: 720,
            fps = settingsObj?.optInt("fps", 30) ?: 30
        )
        val clipsArr = obj.optJSONArray("clips") ?: JSONArray()
        val clips = (0 until clipsArr.length()).mapNotNull { i ->
            clipsArr.optJSONObject(i)?.let { jsonToClip(it) }
        }
        val markersArr = obj.optJSONArray("markers") ?: JSONArray()
        val markers = (0 until markersArr.length()).mapNotNull { i ->
            markersArr.optJSONObject(i)?.let {
                TimelineMarker(
                    time = it.optDouble("time", 0.0),
                    label = it.optString("label", "Marker")
                )
            }
        }
        val assetsArr = obj.optJSONArray("assets") ?: JSONArray()
        val assets = (0 until assetsArr.length()).mapNotNull { i ->
            assetsArr.optJSONObject(i)?.let {
                MediaAsset(
                    id = it.optString("id", generateUid()),
                    name = it.optString("name", "Asset"),
                    mimeType = it.optString("type", ""),
                    kind = AssetKind.fromId(it.optString("kind", "video")),
                    sizeBytes = it.optLong("size", 0L),
                    durationSec = it.optDouble("duration", 5.0),
                    bin = it.optString("bin", "Imported"),
                    favorite = it.optBoolean("favorite", false),
                    colorHex = it.optString("color").takeIf { c -> c.isNotEmpty() },
                    uriString = it.optString("uriString").takeIf { u -> u.isNotEmpty() },
                    localPath = it.optString("localPath").takeIf { p -> p.isNotEmpty() },
                    durationGuess = it.optBoolean("durationGuess", false),
                    waveformPeaks = it.optJSONArray("waveform")?.let { arr ->
                        (0 until arr.length()).map { i -> arr.optDouble(i, 0.0).toFloat() }
                    } ?: emptyList(),
                    filmstripPaths = it.optJSONArray("filmstrip")?.let { arr ->
                        (0 until arr.length()).map { i -> arr.optString(i, "") }.filter { p -> p.isNotEmpty() }
                    } ?: emptyList()
                )
            }
        }
        val tracksObj = obj.optJSONObject("tracks")
        val trackStates = if (tracksObj == null) emptyMap() else TimelineTrack.entries.mapNotNull { track ->
            tracksObj.optJSONObject(track.key)?.let { t ->
                track to TrackState(
                    locked = t.optBoolean("locked", false),
                    hidden = t.optBoolean("hidden", false),
                    muted = t.optBoolean("muted", false),
                    solo = t.optBoolean("solo", false),
                    collapsed = t.optBoolean("collapsed", false)
                )
            }
        }.toMap()
        return ProjectDocument(
            name = name,
            settings = settings,
            clips = clips,
            markers = markers,
            assets = assets,
            trackStates = trackStates
        )
    }

    fun clipToJson(c: TimelineClip): JSONObject = JSONObject().apply {
        put("id", c.id)
        if (c.assetId != null) put("assetId", c.assetId) else put("assetId", JSONObject.NULL)
        put("track", c.track.key)
        put("startAt", c.startAt)
        put("trimIn", c.trimIn)
        put("trimOut", c.trimOut)
        put("filters", filtersToJson(c.filters))
        put("rig", rigToJson(c.rig))
        put("volume", c.volume)
        put("text", c.text)
        put("fontSize", c.fontSize)
        put("fontFamily", c.fontFamily)
        put("fontWeight", c.fontWeight)
        put("textColor", c.textColor)
        put("textAlign", c.textAlign)
        put("verticalPosition", c.verticalPosition)
        put("strokeColor", c.strokeColor)
        put("strokeWidth", c.strokeWidth.toDouble())
        put("shadow", c.shadow)
        put("opacity", c.opacity)
        put("scale", c.scale)
        put("rotation", c.rotation)
        put("x", c.x)
        put("y", c.y)
        put("flipH", c.flipH)
        put("flipV", c.flipV)
        put("fadeOut", c.fadeOut)
        put("fadeInAudio", c.fadeInAudio)
        put("fadeOutAudio", c.fadeOutAudio)
        put("transition", c.transition)
        put("transitionOut", c.transitionOut)
        put("transitionDuration", c.transitionDuration)
        put("speed", c.speed)
        put("ease", c.ease)
        put("bezier", JSONArray(c.bezier))
        put("curveName", c.curveName)
        put("equipmentName", c.equipmentName)
        put("audioPresetName", c.audioPresetName)
        put("blendMode", c.blendMode)
        put("cornerRadius", c.cornerRadius)
        put("audioEq", JSONArray(c.audioEq))
        put("audioPan", c.audioPan)
        put("audioComp", c.audioComp)
        put("audioDeess", c.audioDeess)
        put("audioReverb", c.audioReverb)
        put("label", c.label)
        if (c.graphic.isNotEmpty()) put("graphic", c.graphic)
        put("accentColor", c.accentColor)
        put("boxW", c.boxW)
        put("boxH", c.boxH)
        put("keyframes", JSONArray().apply {
            c.keyframes.forEach { k ->
                put(JSONObject().apply {
                    put("time", k.time)
                    put("scale", k.scale)
                    put("opacity", k.opacity)
                    put("rotation", k.rotation)
                    put("x", k.x)
                    put("y", k.y)
                    put("filters", filtersToJson(k.filters))
                    if (k.easing.isNotEmpty()) put("easing", k.easing)
                })
            }
        })
        put("volumeKeyframes", JSONArray().apply {
            c.volumeKeyframes.forEach { vp ->
                put(JSONObject().apply {
                    put("time", vp.time)
                    put("value", vp.value)
                })
            }
        })
    }

    fun jsonToClip(o: JSONObject): TimelineClip {
        val assetIdRaw = if (o.isNull("assetId")) null else o.optString("assetId").takeIf { it.isNotEmpty() }
        val filtersObj = o.optJSONObject("filters")
        val rigObj = o.optJSONObject("rig")
        val bezArr = o.optJSONArray("bezier")
        val bezier = if (bezArr != null && bezArr.length() == 4) {
            listOf(bezArr.optDouble(0, 0.42), bezArr.optDouble(1, 0.0), bezArr.optDouble(2, 0.58), bezArr.optDouble(3, 1.0))
        } else listOf(0.42, 0.0, 0.58, 1.0)
        val eqArr = o.optJSONArray("audioEq")
        val audioEq = if (eqArr != null && eqArr.length() == 5) {
            (0 until 5).map { eqArr.optDouble(it, 0.0) }
        } else listOf(0.0, 0.0, 0.0, 0.0, 0.0)

        val kfArr = o.optJSONArray("keyframes") ?: JSONArray()
        val keyframes = (0 until kfArr.length()).mapNotNull { i ->
            kfArr.optJSONObject(i)?.let { k ->
                Keyframe(
                    time = k.optDouble("time", 0.0),
                    scale = k.optDouble("scale", 100.0),
                    opacity = k.optDouble("opacity", 100.0),
                    rotation = k.optDouble("rotation", 0.0),
                    x = k.optDouble("x", 0.0),
                    y = k.optDouble("y", 0.0),
                    filters = jsonToFilters(k.optJSONObject("filters")),
                    easing = k.optString("easing", "")
                )
            }
        }
        val vkArr = o.optJSONArray("volumeKeyframes") ?: JSONArray()
        val volumeKeyframes = (0 until vkArr.length()).mapNotNull { i ->
            vkArr.optJSONObject(i)?.let { vp ->
                VolumePoint(
                    time = vp.optDouble("time", 0.0),
                    value = vp.optDouble("value", 100.0)
                )
            }
        }

        return TimelineClip(
            id = o.optString("id", generateUid()),
            assetId = assetIdRaw,
            track = TimelineTrack.fromKey(o.optString("track", "video")),
            startAt = o.optDouble("startAt", 0.0),
            trimIn = o.optDouble("trimIn", 0.0),
            trimOut = o.optDouble("trimOut", 5.0),
            filters = jsonToFilters(filtersObj),
            rig = jsonToRig(rigObj),
            volume = o.optDouble("volume", 100.0),
            text = o.optString("text", ""),
            fontSize = o.optInt("fontSize", 42),
            fontFamily = o.optString("fontFamily", "system-ui"),
            fontWeight = o.optInt("fontWeight", 800),
            textColor = o.optString("textColor", "#ffffff"),
            textAlign = o.optString("textAlign", "center"),
            verticalPosition = o.optString("verticalPosition", "center"),
            strokeColor = o.optString("strokeColor", "#000000"),
            strokeWidth = o.optDouble("strokeWidth", 0.0).toFloat(),
            shadow = o.optBoolean("shadow", true),
            opacity = o.optDouble("opacity", 100.0),
            scale = o.optDouble("scale", 100.0),
            rotation = o.optDouble("rotation", 0.0),
            x = o.optDouble("x", 0.0),
            y = o.optDouble("y", 0.0),
            flipH = o.optBoolean("flipH", false),
            flipV = o.optBoolean("flipV", false),
            fadeOut = o.optDouble("fadeOut", 0.0),
            fadeInAudio = o.optDouble("fadeInAudio", 0.0),
            fadeOutAudio = o.optDouble("fadeOutAudio", 0.0),
            transition = o.optString("transition", "none"),
            transitionOut = o.optString("transitionOut", "none"),
            transitionDuration = o.optDouble("transitionDuration", 0.45),
            speed = o.optDouble("speed", 1.0),
            ease = o.optString("ease", "ease"),
            bezier = bezier,
            curveName = o.optString("curveName", "Cubic Smooth"),
            equipmentName = o.optString("equipmentName", ""),
            audioPresetName = o.optString("audioPresetName", ""),
            blendMode = o.optString("blendMode", "normal"),
            cornerRadius = o.optInt("cornerRadius", 0),
            audioEq = audioEq,
            audioPan = o.optDouble("audioPan", 0.0),
            audioComp = o.optDouble("audioComp", 0.0),
            audioDeess = o.optDouble("audioDeess", 0.0),
            audioReverb = o.optDouble("audioReverb", 0.0),
            keyframes = keyframes,
            volumeKeyframes = volumeKeyframes,
            label = o.optString("label", "Clip"),
            graphic = o.optString("graphic", ""),
            accentColor = o.optString("accentColor", "#0a84ff"),
            boxW = o.optDouble("boxW", 40.0),
            boxH = o.optDouble("boxH", 40.0)
        )
    }

    fun filtersToJson(f: ClipFilters): JSONObject = JSONObject().apply {
        put("brightness", f.brightness)
        put("contrast", f.contrast)
        put("saturation", f.saturation)
        put("blur", f.blur)
        put("temperature", f.temperature)
        put("tint", f.tint)
        put("sharpen", f.sharpen)
        put("vignette", f.vignette)
        put("hue", f.hue)
        put("sepia", f.sepia)
        put("invert", f.invert)
        put("grain", f.grain)
        put("liftR", f.liftR); put("liftG", f.liftG); put("liftB", f.liftB); put("liftY", f.liftY)
        put("gammaR", f.gammaR); put("gammaG", f.gammaG); put("gammaB", f.gammaB); put("gammaY", f.gammaY)
        put("gainR", f.gainR); put("gainG", f.gainG); put("gainB", f.gainB); put("gainY", f.gainY)
    }

    fun jsonToFilters(o: JSONObject?): ClipFilters {
        if (o == null) return ClipFilters()
        return ClipFilters(
            brightness = o.optDouble("brightness", 100.0),
            contrast = o.optDouble("contrast", 100.0),
            saturation = o.optDouble("saturation", 100.0),
            blur = o.optDouble("blur", 0.0),
            temperature = o.optDouble("temperature", 0.0),
            tint = o.optDouble("tint", 0.0),
            sharpen = o.optDouble("sharpen", 0.0),
            vignette = o.optDouble("vignette", 0.0),
            hue = o.optDouble("hue", 0.0),
            sepia = o.optDouble("sepia", 0.0),
            invert = o.optDouble("invert", 0.0),
            grain = o.optDouble("grain", 0.0),
            liftR = o.optDouble("liftR", 0.0),
            liftG = o.optDouble("liftG", 0.0),
            liftB = o.optDouble("liftB", 0.0),
            liftY = o.optDouble("liftY", 0.0),
            gammaR = o.optDouble("gammaR", 0.0),
            gammaG = o.optDouble("gammaG", 0.0),
            gammaB = o.optDouble("gammaB", 0.0),
            gammaY = o.optDouble("gammaY", 0.0),
            gainR = o.optDouble("gainR", 0.0),
            gainG = o.optDouble("gainG", 0.0),
            gainB = o.optDouble("gainB", 0.0),
            gainY = o.optDouble("gainY", 0.0)
        )
    }

    fun rigToJson(r: CameraRig): JSONObject = JSONObject().apply {
        put("tiltX", r.tiltX)
        put("swivelY", r.swivelY)
        put("shutter", r.shutter)
        put("aberration", r.aberration)
        put("anamorphic", r.anamorphic)
    }

    fun jsonToRig(o: JSONObject?): CameraRig {
        if (o == null) return CameraRig()
        return CameraRig(
            tiltX = o.optDouble("tiltX", 0.0),
            swivelY = o.optDouble("swivelY", 0.0),
            shutter = o.optDouble("shutter", 180.0),
            aberration = o.optDouble("aberration", 0.0),
            anamorphic = o.optDouble("anamorphic", 0.0)
        )
    }

    fun exportPresetBundleJson(
        customStyles: Map<String, ClipFilters>,
        customMotions: Map<String, List<MotionKeyframeStep>>,
        customEquipment: Map<String, EquipmentRigPreset>,
        customAudioGear: Map<String, AudioGearPreset>
    ): String {
        val root = JSONObject()
        root.put("format", "editpro-preset-bundle")
        root.put("version", 2)
        root.put("name", "Edit Pro Universal Equipment Bundle")
        root.put("createdAt", Instant.now().toString())

        val stylesObj = JSONObject()
        customStyles.forEach { (name, f) ->
            stylesObj.put(name, JSONObject().apply {
                put("style", JSONObject().apply {
                    put("filters", filtersToJson(f))
                })
                put("updatedAt", Instant.now().toString())
            })
        }
        root.put("styles", stylesObj)

        val motionsObj = JSONObject()
        customMotions.forEach { (name, steps) ->
            motionsObj.put(name, JSONArray().apply {
                steps.forEach { st ->
                    put(JSONObject().apply {
                        put("t", st.timeSec)
                        st.scale?.let { put("scale", it) }
                        st.opacity?.let { put("opacity", it) }
                        st.rotation?.let { put("rotation", it) }
                        st.x?.let { put("x", it) }
                        st.y?.let { put("y", it) }
                    })
                }
            })
        }
        root.put("motions", motionsObj)

        val eqObj = JSONObject()
        (PresetCatalog.builtinEquipmentRigs + customEquipment).forEach { (name, eq) ->
            eqObj.put(name, JSONObject().apply {
                put("category", eq.category)
                put("scale", eq.scale)
                eq.filters?.let { put("filters", filtersToJson(it)) }
                put("rig", rigToJson(eq.rig))
            })
        }
        root.put("equipment", eqObj)

        val audObj = JSONObject()
        (PresetCatalog.builtinAudioGear + customAudioGear).forEach { (name, a) ->
            audObj.put(name, JSONObject().apply {
                put("eq", JSONArray(a.eq))
                put("comp", a.comp)
                put("pan", a.pan)
                put("deess", a.deess)
                put("reverb", a.reverb)
                put("volume", a.volume)
            })
        }
        root.put("audioPresets", audObj)
        return root.toString(2)
    }
}
