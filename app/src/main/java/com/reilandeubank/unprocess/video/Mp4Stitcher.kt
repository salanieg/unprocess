package com.reilandeubank.unprocess.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Joins multiple MP4 segments (all recorded with the SAME MediaRecorder
 * configuration — codec, resolution, frame rate, audio params) into one
 * gapless file WITHOUT re-encoding: samples are copied track-by-track via
 * [MediaExtractor] → [MediaMuxer], with presentation times shifted by the
 * accumulated duration of the previous segments.
 *
 * This is the assembly step of the Narration mode's Cut/Continue flow.
 * Each Cut finalizes a complete, individually playable segment file, so a
 * crash can only ever lose the segment currently being written — and the
 * survivors stitch back together here (also used by crash recovery).
 *
 * Segments that can't be opened (e.g. a torn last segment after a crash —
 * MP4s without their moov box) are skipped, not fatal.
 */
object Mp4Stitcher {

    private const val TAG = "Mp4Stitcher"

    /** Headroom for the largest single sample: a 4K keyframe at 40 Mbps
     *  bursts to well under 2 MB; 8 MB is comfortably safe. */
    private const val SAMPLE_BUFFER_BYTES = 8 * 1024 * 1024

    /**
     * Stitches [segments] (in order) into [output]. Returns true if at
     * least one segment was written. The descriptor must be opened "rw".
     */
    fun stitch(segments: List<File>, output: FileDescriptor): Boolean {
        val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var videoOut = -1
        var audioOut = -1
        var offsetUs = 0L
        val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()

        try {
            for (segment in segments) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.absolutePath)
                } catch (exc: Exception) {
                    // Torn segment (crash mid-write) — skip it, keep the rest.
                    Log.w(TAG, "Skipping unreadable segment ${segment.name}: ${exc.message}")
                    extractor.release()
                    continue
                }
                try {
                    var videoIn = -1
                    var audioIn = -1
                    var videoDurUs = 0L
                    var audioDurUs = 0L
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            format.getLong(MediaFormat.KEY_DURATION)
                        } else 0L
                        if (mime.startsWith("video/") && videoIn < 0) {
                            videoIn = i
                            videoDurUs = durUs
                        } else if (mime.startsWith("audio/") && audioIn < 0) {
                            audioIn = i
                            audioDurUs = durUs
                        }
                    }
                    if (videoIn < 0) {
                        Log.w(TAG, "Segment ${segment.name} has no video track; skipping")
                        continue
                    }

                    // All segments share one recorder config — the first
                    // readable one defines the output tracks.
                    if (!started) {
                        videoOut = muxer.addTrack(extractor.getTrackFormat(videoIn))
                        if (audioIn >= 0) {
                            audioOut = muxer.addTrack(extractor.getTrackFormat(audioIn))
                        }
                        muxer.start()
                        started = true
                    }

                    extractor.selectTrack(videoIn)
                    if (audioIn >= 0 && audioOut >= 0) extractor.selectTrack(audioIn)

                    var maxSampleUs = 0L
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val sampleUs = extractor.sampleTime
                        val track = extractor.sampleTrackIndex
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = sampleUs + offsetUs
                        info.flags =
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else 0
                        val outTrack = if (track == videoIn) videoOut else audioOut
                        if (outTrack >= 0) muxer.writeSampleData(outTrack, buffer, info)
                        if (sampleUs > maxSampleUs) maxSampleUs = sampleUs
                        extractor.advance()
                    }

                    // Advance the timeline by the segment's real duration
                    // (container metadata when present, last sample as the
                    // fallback) plus one 24 fps frame so the next segment's
                    // first frame doesn't collide with this one's last.
                    val segDurUs = maxOf(videoDurUs, audioDurUs, maxSampleUs)
                    offsetUs += segDurUs + 41_667L
                } finally {
                    extractor.release()
                }
            }
            if (started) muxer.stop()
        } catch (exc: Exception) {
            Log.e(TAG, "Stitching failed", exc)
            return false
        } finally {
            try {
                muxer.release()
            } catch (exc: Exception) {
                Log.w(TAG, "Muxer release failed: ${exc.message}")
            }
        }
        return started
    }
}
