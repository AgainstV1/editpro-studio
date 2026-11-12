package com.editpro.studio.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.ExportSettings
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import com.editpro.studio.model.formatTimecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Supplies decoded video frames to the compositor. The preview uses one so it can keep and
 * reuse the frame it already has; the caller owns the bitmap it returns.
 */
fun interface VideoFrameSource {
    fun frameAt(clip: TimelineClip, asset: MediaAsset, timeUs: Long): Bitmap?
}

data class ExportResult(
    val success: Boolean,
    val outputFile: File? = null,
    val message: String
)

object NativeVideoExporter {

    /**
     * Renders the timeline to an H.264 MP4 with Android's own `MediaCodec` + `MediaMuxer`.
     * Every frame comes from [renderCompositeFrame] — the same compositor the preview uses — so
     * the picture layers, keyframe animation, grade, camera rig, fades, vignette, motion graphics
     * and titles match what was on screen while editing. The audio track is mixed by
     * [TimelineAudioEncoder].
     */
    suspend fun exportProjectToMp4(
        context: Context,
        projectName: String,
        clips: List<TimelineClip>,
        assetsById: Map<String, MediaAsset>,
        trackStates: Map<TimelineTrack, TrackState>,
        settings: ExportSettings,
        rangeOverride: Pair<Double, Double>? = null,
        onProgress: (Int, String) -> Unit
    ): ExportResult = withContext(Dispatchers.Default) {
        val started = TimelineEngine.starts(clips, assetsById)
        val visibleVisuals = started.filter { rc ->
            rc.clip.track.isVisual &&
                trackStates[rc.clip.track]?.hidden != true &&
                rc.clip.assetId != null &&
                assetsById.containsKey(rc.clip.assetId)
        }
        val visibleTexts = started.filter { rc ->
            rc.clip.track.isTitle && trackStates[rc.clip.track]?.hidden != true
        }
        if (visibleVisuals.isEmpty() && visibleTexts.isEmpty()) {
            return@withContext ExportResult(
                success = false,
                message = "Add at least one visible video, image, text or graphics clip before exporting."
            )
        }

        val (width, height) = settings.resolvedDimensions(deviceRamGb = 8)
        val fps = settings.fps.coerceIn(24, 60)
        val bitrate = ((width * height * fps * 0.14) * 0.88.pow((settings.crf - 18) / 2.0)).roundToInt()
            .coerceIn(800_000, 24_000_000)

        val totalProjectDur = TimelineEngine.calcProjectDuration(clips, assetsById)
        val fromSec = rangeOverride?.first ?: settings.rangeInSec ?: 0.0
        val toSec = rangeOverride?.second ?: settings.rangeOutSec ?: totalProjectDur
        val renderDuration = max(0.5, toSec - fromSec)
        val totalFrames = max(1, (renderDuration * fps).roundToInt())

        // Exports live in the app's private FILES directory, not the cache: Android is free to
        // clear the cache at any time, which would silently destroy a finished render.
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        val safeTitle = projectName.replace(Regex("[^a-zA-Z0-9_-]+"), "_").take(50).ifEmpty { "EditPro_Project" }
        val outputFile = File(exportDir, "${safeTitle}_AndroidTurbo.mp4")
        if (outputFile.exists()) outputFile.delete()

        onProgress(2, "Initializing Android Hardware H.264 encoder (${width}×${height} @ ${fps}fps)…")

        // Preload image bitmaps and open MediaMetadataRetrievers for video clips
        val bitmapCache = mutableMapOf<String, Bitmap>()
        val videoRetrievers = mutableMapOf<String, MediaMetadataRetriever>()

        try {
            for (rc in visibleVisuals) {
                val asset = assetsById[rc.clip.assetId] ?: continue
                val path = asset.localPath ?: continue
                if (asset.kind == AssetKind.IMAGE && !bitmapCache.containsKey(asset.id)) {
                    // Twice the frame leaves room to zoom in without softening the picture.
                    MediaEngine.decodeStill(path, width * 2, height * 2)?.let { bitmapCache[asset.id] = it }
                } else if (asset.kind == AssetKind.VIDEO && !videoRetrievers.containsKey(asset.id)) {
                    val ret = MediaMetadataRetriever()
                    try {
                        ret.setDataSource(path)
                        videoRetrievers[asset.id] = ret
                    } catch (_: Exception) {
                        ret.release()
                    }
                }
            }

            // Mix and encode the timeline's audio into an AAC track. Deliberately wrapped: if
            // anything in the audio path fails, the video must still export (just silent).
            val encodedAudio = try {
                TimelineAudioEncoder.encode(started, assetsById, trackStates, fromSec, toSec)
            } catch (_: Exception) {
                null
            }
            if (encodedAudio != null) {
                onProgress(3, "Audio mixed and encoded (AAC · stereo · 44.1 kHz).")
            }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val frameCanvas = Canvas(frameBitmap)
            val argbPixels = IntArray(width * height)
            val yuvBuffer = ByteArray(width * height * 3 / 2)

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    try {
                        val inIdx = encoder.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    } catch (_: Exception) {}
                }
                while (true) {
                    val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            if (encodedAudio != null) {
                                audioTrackIndex = muxer.addTrack(encodedAudio.format)
                            }
                            muxer.start()
                            muxerStarted = true
                            // Audio is fully encoded up-front, so it can be written straight away.
                            if (encodedAudio != null && audioTrackIndex >= 0) {
                                for (sample in encodedAudio.samples) {
                                    val audioInfo = MediaCodec.BufferInfo()
                                    audioInfo.set(0, sample.data.size, sample.ptsUs, sample.flags)
                                    muxer.writeSampleData(
                                        audioTrackIndex,
                                        ByteBuffer.wrap(sample.data),
                                        audioInfo
                                    )
                                }
                            }
                        }
                    } else if (outIdx >= 0) {
                        val encodedData: ByteBuffer? = encoder.getOutputBuffer(outIdx)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            for (frameIdx in 0..totalFrames) {
                coroutineContext.ensureActive()
                val timelineSec = fromSec + (frameIdx.toDouble() / fps)
                renderCompositeFrame(
                    canvas = frameCanvas,
                    width = width,
                    height = height,
                    playheadSec = timelineSec,
                    allResolved = started,
                    assetsById = assetsById,
                    trackStates = trackStates,
                    bitmapCache = bitmapCache,
                    videoRetrievers = videoRetrievers,
                    checkerboard = false
                )

                frameBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
                argbToNv12(argbPixels, yuvBuffer, width, height)

                val inIdx = encoder.dequeueInputBuffer(15_000L)
                if (inIdx >= 0) {
                    val inputBuf = encoder.getInputBuffer(inIdx)
                    inputBuf?.clear()
                    inputBuf?.put(yuvBuffer)
                    val ptsUs = ((frameIdx.toDouble() / fps) * 1_000_000.0).toLong()
                    encoder.queueInputBuffer(inIdx, 0, yuvBuffer.size, ptsUs, 0)
                }
                drainEncoder(endOfStream = false)

                val pct = min(99, ((frameIdx.toDouble() / totalFrames) * 100.0).roundToInt())
                if (frameIdx % 4 == 0 || frameIdx == totalFrames) {
                    onProgress(
                        pct,
                        "⚡ Android Hardware Turbo encoding: $pct% (${formatTimecode(timelineSec - fromSec)} / ${formatTimecode(renderDuration)})"
                    )
                }
            }

            drainEncoder(endOfStream = true)
            frameBitmap.recycle()

            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            try { muxer.release() } catch (_: Exception) {}

            // Keep the exports folder bounded — retain only the newest renders.
            runCatching {
                exportDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(12)
                    ?.forEach { it.delete() }
            }

            val sizeKb = max(1L, outputFile.length() / 1024L)
            onProgress(100, "✓ Android Hardware Turbo Export complete (MP4 · $sizeKb KB).")
            ExportResult(
                success = true,
                outputFile = outputFile,
                message = "Exported ${outputFile.name} ($sizeKb KB)"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cooperative cancellation must propagate, not be reported as a render failure.
            throw e
        } catch (e: Exception) {
            ExportResult(
                success = false,
                message = "Export failed: ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            bitmapCache.values.forEach { it.recycle() }
            videoRetrievers.values.forEach { try { it.release() } catch (_: Exception) {} }
        }
    }

    /**
     * Draws one frame of the timeline onto any Android `Canvas`. This is the single compositor:
     * the exporter calls it for every output frame and the preview ([PreviewCompositor]) calls it
     * for what is on screen, so the two cannot drift apart.
     *
     * Video frames come from [videoFrames] when one is given, otherwise straight from
     * [videoRetrievers].
     */
    fun renderCompositeFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        playheadSec: Double,
        allResolved: List<ResolvedClip>,
        assetsById: Map<String, MediaAsset>,
        trackStates: Map<TimelineTrack, TrackState>,
        bitmapCache: Map<String, Bitmap>,
        videoRetrievers: Map<String, MediaMetadataRetriever>,
        checkerboard: Boolean = false,
        videoFrames: VideoFrameSource? = null
    ) {
        val bgPaint = Paint().apply {
            color = if (checkerboard) Color.parseColor("#27272b") else Color.parseColor("#050608")
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val projectEnd = allResolved.maxOfOrNull { it.end } ?: 0.0
        // Layer order is the track order (VIDEO 1 at the bottom, OVERLAY on top), not the order
        // the clips happen to start in.
        val activeVisuals = allResolved.filter { rc ->
            rc.clip.track.isVisual &&
                trackStates[rc.clip.track]?.hidden != true &&
                TimelineEngine.isActiveAt(rc, playheadSec, projectEnd)
        }.sortedBy { it.clip.track.ordinal }

        var maxVignette = 0.0
        for (rc in activeVisuals) {
            val c = rc.clip
            val asset = c.assetId?.let { assetsById[it] } ?: continue
            val localT = playheadSec - rc.start
            val anim = TimelineEngine.animatedProps(c, localT)
            val f = anim.filters
            maxVignette = max(maxVignette, f.vignette)

            val transDur = max(0.05, c.transitionDuration)
            val transProgress = ((playheadSec - rc.start) / transDur).coerceIn(0.0, 1.0)
            val fadeAlpha = if (c.transition == "fade" || c.transition == "dissolve") transProgress else 1.0
            val exitProgress = if (c.transitionOut != "none") {
                ((rc.end - playheadSec) / transDur).coerceIn(0.0, 1.0)
            } else 1.0
            val fadeOutAlpha = if (c.fadeOut > 0.0) {
                ((rc.end - playheadSec) / c.fadeOut).coerceIn(0.0, 1.0)
            } else 1.0
            val finalAlpha = ((anim.opacity / 100.0) * fadeAlpha * exitProgress * fadeOutAlpha)
                .coerceIn(0.0, 1.0)

            canvas.save()
            val tx = (width / 2f) + ((anim.x / 100.0) * width).toFloat()
            val ty = (height / 2f) + ((anim.y / 100.0) * height).toFloat()
            val sc = (anim.scale / 100.0).toFloat()
            val squeezeY = (1.0 - min(0.35, c.rig.anamorphic / 100.0)).toFloat()

            canvas.translate(tx, ty)
            if (c.rig.tiltX != 0.0 || c.rig.swivelY != 0.0) {
                val cam = Camera()
                cam.save()
                cam.rotateX(c.rig.tiltX.toFloat())
                cam.rotateY(c.rig.swivelY.toFloat())
                val mat = Matrix()
                cam.getMatrix(mat)
                cam.restore()
                canvas.concat(mat)
            }
            canvas.rotate(anim.rotation.toFloat())
            canvas.scale(
                sc * (if (c.flipH) -1f else 1f),
                sc * squeezeY * (if (c.flipV) -1f else 1f)
            )

            val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (finalAlpha * 255).roundToInt().coerceIn(0, 255)
                colorFilter = ColorMatrixColorFilter(MediaEngine.buildColorMatrix(f))
                when (c.blendMode) {
                    "screen" -> xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    "multiply" -> xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                    "overlay" -> xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                    "lighten" -> xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                }
            }

            when (asset.kind) {
                AssetKind.COLOR -> {
                    layerPaint.color = try {
                        Color.parseColor(asset.colorHex ?: "#2678ff")
                    } catch (_: Exception) {
                        Color.parseColor("#2678ff")
                    }
                    layerPaint.alpha = (finalAlpha * 255).roundToInt().coerceIn(0, 255)
                    canvas.drawRect(-width / 2f, -height / 2f, width / 2f, height / 2f, layerPaint)
                }
                AssetKind.IMAGE -> {
                    val bmp = bitmapCache[asset.id]
                    if (bmp != null) {
                        drawContainedBitmap(canvas, bmp, width, height, layerPaint)
                    }
                }
                AssetKind.VIDEO -> {
                    val ret = videoRetrievers[asset.id]
                    val targetUs = max(0L, ((c.trimIn + localT * c.speed) * 1_000_000.0).toLong())
                    if (videoFrames != null) {
                        videoFrames.frameAt(c, asset, targetUs)?.let {
                            drawContainedBitmap(canvas, it, width, height, layerPaint)
                        }
                    } else if (ret != null) {
                        // The exact frame, not the nearest key frame: key frames are often a
                        // second apart, which turned exported motion into a slideshow. The key
                        // frame is only a fallback for the odd position a decoder cannot reach.
                        val vBmp = try {
                            ret.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                                ?: ret.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } catch (_: Exception) {
                            null
                        }
                        if (vBmp != null) {
                            drawContainedBitmap(canvas, vBmp, width, height, layerPaint)
                            vBmp.recycle()
                        }
                    }
                }
                AssetKind.AUDIO -> Unit
            }
            canvas.restore()
        }

        // Vignette layer
        if (maxVignette > 0.0) {
            val vigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    width / 2f,
                    height / 2f,
                    max(width, height) * 0.62f,
                    intArrayOf(Color.TRANSPARENT, Color.argb((min(0.85, maxVignette / 100.0) * 255).roundToInt(), 0, 0, 0)),
                    floatArrayOf(0.3f, 1.0f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vigPaint)
        }

        // Motion graphics and titles, drawn by the renderer the preview also uses.
        TitleRenderer.drawTitles(canvas, width, height, playheadSec, allResolved, trackStates)
    }

    private fun drawContainedBitmap(canvas: Canvas, bmp: Bitmap, stageW: Int, stageH: Int, paint: Paint) {
        val bw = max(1, bmp.width)
        val bh = max(1, bmp.height)
        val ratio = min(stageW.toFloat() / bw, stageH.toFloat() / bh)
        val dw = bw * ratio
        val dh = bh * ratio
        val dst = RectF(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun argbToNv12(argb: IntArray, yuv: ByteArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[index++]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < yuv.size) {
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
