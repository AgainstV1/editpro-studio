package com.editpro.studio.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.os.Build
import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Everything that decides what one preview frame looks like. It holds the editor's own lists
 * (not copies), so two requests for an unchanged project compare equal almost for free.
 */
data class PreviewRequest(
    val width: Int,
    val height: Int,
    val playheadSec: Double,
    val clips: List<TimelineClip>,
    val assets: List<MediaAsset>,
    val trackStates: Map<TimelineTrack, TrackState>,
    val checkerboard: Boolean,
    val playing: Boolean
)

/**
 * Draws preview frames with [NativeVideoExporter.renderCompositeFrame], the compositor the
 * exporter uses, so the preview shows what the export will contain: real video frames, layer
 * order, grade, motion, fades, motion graphics and titles.
 *
 * All decoding and drawing happens on one background thread ([dispatcher]); every call to
 * [render] must run there. The frame it returns stays valid until two more frames have been
 * rendered, which is ample for the UI to move on to the newer one.
 */
class PreviewCompositor {

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "editpro-preview").apply { isDaemon = true }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private class VideoFrame(val assetId: String, val timeKey: Long, val exact: Boolean, val bitmap: Bitmap)

    private val stills = LinkedHashMap<String, Bitmap>()
    private val retrievers = LinkedHashMap<String, MediaMetadataRetriever>()
    private val videoFrames = HashMap<String, VideoFrame>()
    private val buffers = arrayOfNulls<Bitmap>(3)
    private var bufferIndex = 0
    private var closed = false

    /**
     * True when the last frame from [render] showed a key frame in place of the precise video
     * frame, i.e. when rendering it again with `exact` would improve it.
     */
    @Volatile
    var lastRenderApproximate: Boolean = false
        private set

    /**
     * Renders [req]. With [exact] false a video clip shows its nearest key frame, which is quick
     * enough to follow a scrub; with [exact] true it shows the precise frame. A frame that is
     * already in hand for the same moment is reused either way, so adjusting a slider while
     * paused never re-decodes video. Returns null when nothing could be drawn.
     */
    fun render(req: PreviewRequest, exact: Boolean): Bitmap? {
        if (closed || req.width <= 0 || req.height <= 0) return null
        lastRenderApproximate = false
        return try {
            val assetsById = req.assets.associateBy { it.id }
            val resolved = TimelineEngine.starts(req.clips, assetsById)
            val projectEnd = resolved.maxOfOrNull { it.end } ?: 0.0
            val onScreen = resolved.filter { rc ->
                rc.clip.track.isVisual &&
                    req.trackStates[rc.clip.track]?.hidden != true &&
                    TimelineEngine.isActiveAt(rc, req.playheadSec, projectEnd)
            }

            val stillIds = HashSet<String>()
            val videoAssetIds = HashSet<String>()
            for (rc in onScreen) {
                val asset = assetsById[rc.clip.assetId] ?: continue
                when (asset.kind) {
                    AssetKind.IMAGE -> {
                        stillIds.add(asset.id)
                        if (!stills.containsKey(asset.id)) {
                            asset.localPath
                                ?.let { MediaEngine.decodeStill(it, max(req.width, STILL_MIN_EDGE), max(req.height, STILL_MIN_EDGE)) }
                                ?.let { stills[asset.id] = it }
                        }
                    }
                    AssetKind.VIDEO -> videoAssetIds.add(asset.id)
                    else -> Unit
                }
            }
            trimStills(stillIds)

            val source = VideoFrameSource { clip, asset, timeUs ->
                frameFor(clip.id, asset, timeUs, exact, req.width, req.height)
            }
            val out = nextBuffer(req.width, req.height)
            NativeVideoExporter.renderCompositeFrame(
                canvas = Canvas(out),
                width = req.width,
                height = req.height,
                playheadSec = req.playheadSec,
                allResolved = resolved,
                assetsById = assetsById,
                trackStates = req.trackStates,
                bitmapCache = stills,
                videoRetrievers = emptyMap(),
                checkerboard = req.checkerboard,
                videoFrames = source
            )

            // Frames and decoders for clips that have left the screen are let go.
            val liveClipIds = onScreen.map { it.clip.id }.toSet()
            val staleFrames = videoFrames.keys.filter { it !in liveClipIds }
            for (id in staleFrames) videoFrames.remove(id)?.bitmap?.recycle()
            trimRetrievers(videoAssetIds)
            out
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            releaseCaches()
            null
        }
    }

    private fun frameFor(clipId: String, asset: MediaAsset, timeUs: Long, exact: Boolean, w: Int, h: Int): Bitmap? {
        val key = timeUs / FRAME_BUCKET_US
        // A frame kept for this clip is only useful while the clip still shows the same media.
        val held = videoFrames[clipId]
        val cached = held?.takeIf { it.assetId == asset.id }
        if (cached != null && cached.timeKey == key && (cached.exact || !exact)) {
            if (!cached.exact) lastRenderApproximate = true
            return cached.bitmap
        }
        if (!exact) lastRenderApproximate = true
        val path = asset.localPath ?: return cached?.bitmap
        val retriever = retrieverFor(asset.id, path) ?: return cached?.bitmap
        val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val decoded = try {
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(timeUs, option, max(2, w), max(2, h))
            } else {
                retriever.getFrameAtTime(timeUs, option)?.let { shrink(it, w, h) }
            }
        } catch (_: Exception) {
            null
        }
        // On a miss the last good frame stays up, which reads far better than a black flash.
        if (decoded == null) return cached?.bitmap
        held?.bitmap?.recycle()
        videoFrames[clipId] = VideoFrame(asset.id, key, exact, decoded)
        return decoded
    }

    private fun retrieverFor(assetId: String, path: String): MediaMetadataRetriever? {
        retrievers[assetId]?.let { return it }
        val created = MediaMetadataRetriever()
        return try {
            created.setDataSource(path)
            retrievers[assetId] = created
            created
        } catch (_: Exception) {
            runCatching { created.release() }
            null
        }
    }

    private fun shrink(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width <= w * 2 && src.height <= h * 2) return src
        val ratio = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val scaled = Bitmap.createScaledBitmap(
            src, max(2, (src.width * ratio).toInt()), max(2, (src.height * ratio).toInt()), true
        )
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun nextBuffer(w: Int, h: Int): Bitmap {
        bufferIndex = (bufferIndex + 1) % buffers.size
        val existing = buffers[bufferIndex]
        if (existing != null && existing.width == w && existing.height == h) return existing
        // A replaced buffer may still be on screen for a frame, so it is left to the collector.
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffers[bufferIndex] = created
        return created
    }

    private fun trimStills(inUse: Set<String>) {
        if (stills.size <= MAX_STILLS) return
        val it = stills.entries.iterator()
        while (stills.size > MAX_STILLS && it.hasNext()) {
            val entry = it.next()
            if (entry.key !in inUse) {
                entry.value.recycle()
                it.remove()
            }
        }
    }

    private fun trimRetrievers(inUse: Set<String>) {
        if (retrievers.size <= MAX_RETRIEVERS) return
        val it = retrievers.entries.iterator()
        while (retrievers.size > MAX_RETRIEVERS && it.hasNext()) {
            val entry = it.next()
            if (entry.key !in inUse) {
                runCatching { entry.value.release() }
                it.remove()
            }
        }
    }

    private fun releaseCaches() {
        stills.values.forEach { runCatching { it.recycle() } }
        stills.clear()
        videoFrames.values.forEach { runCatching { it.bitmap.recycle() } }
        videoFrames.clear()
        retrievers.values.forEach { runCatching { it.release() } }
        retrievers.clear()
    }

    /** Frees the decoders and cached pictures. Safe to call from any thread, and more than once. */
    fun close() {
        runCatching {
            executor.execute {
                closed = true
                releaseCaches()
                for (i in buffers.indices) buffers[i] = null
            }
            executor.shutdown()
        }
    }

    private companion object {
        /** Two requests within the same 10 ms of source time are the same picture. */
        const val FRAME_BUCKET_US = 10_000L
        const val MAX_STILLS = 8
        const val MAX_RETRIEVERS = 4
        const val STILL_MIN_EDGE = 720
    }
}
