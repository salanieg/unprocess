package com.reilandeubank.unprocess.filter

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * In-place(-ish) film simulation processor. [apply] returns the same Bitmap
 * passed in (after mutating its pixels) — the caller does not need to recycle
 * a separate output.
 *
 * The pipeline mirrors the order a Lightroom-style preset would apply:
 *
 *   WB (temp/tint) → light/contrast tone LUT (incl. master point curve)
 *   → per-channel R/G/B curves → HSL (hue/sat/lum per color range)
 *   → calibration shifts on primaries → vibrance/saturation → split-toning
 *   → grain → vignette
 *
 * Bitmaps are processed in row strips so we never need a full second
 * IntArray copy of a 12+ MP capture in memory.
 */
object FilmFilter {

    /**
     * Number of rows processed per chunk. Bigger chunks → fewer native
     * `getPixels`/`setPixels` calls per worker (each one is a JNI boundary
     * cross + a memcpy). 256 keeps the per-worker buffer at width*256*4
     * bytes ≈ 4 MB on a 4000-px capture, well within the heap.
     */
    private const val ROW_CHUNK = 256

    /** Center hue (degrees) for each of the 8 HSL color ranges. */
    private val HSL_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 300f)

    /**
     * Applies [simulation] to [bitmap]. Returns either the same bitmap
     * (mutated in place — possible when the input is already mutable) or a
     * new mutable copy (if the input is immutable, or for NORMAL when nothing
     * needs to be done it just returns the input unchanged).
     *
     * Callers must compare the returned reference against the input and
     * recycle the unused one — see usage in `CameraFragment.handleCaptureClick`.
     */
    fun apply(bitmap: Bitmap, simulation: FilmSimulation): Bitmap {
        if (simulation == FilmSimulation.NORMAL) return bitmap
        val p = simulation.params
        val target = if (bitmap.isMutable) bitmap
            else bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        return processInPlace(target, p)
    }

    private fun processInPlace(bitmap: Bitmap, p: FilmParams): Bitmap {

        // Pre-compose master tone LUT + per-channel curves into a single
        // 256-entry LUT per channel. In the hot loop this turns 6 array
        // lookups per pixel (master[r], master[g], master[b], red[r],
        // green[g], blue[b]) into 3 (redCombined[r], greenCombined[g],
        // blueCombined[b]) — half the cache traffic on the LUTs.
        val masterLut = buildMasterLut(p)
        val rawRedLut = buildChannelLut(p.redCurve)
        val rawGreenLut = buildChannelLut(p.greenCurve)
        val rawBlueLut = buildChannelLut(p.blueCurve)
        val redLut = IntArray(256) { rawRedLut[masterLut[it]] }
        val greenLut = IntArray(256) { rawGreenLut[masterLut[it]] }
        val blueLut = IntArray(256) { rawBlueLut[masterLut[it]] }

        val tempShift = p.temp / 100f
        val tintShift = p.tint / 100f
        val vibrance = p.vibrance / 100f
        val saturation = p.saturation / 100f

        val width = bitmap.width
        val height = bitmap.height

        // Grain is deterministic per-bitmap so the saved JPEG matches the
        // thumbnail; seed depends on dimensions only.
        val grain = if (p.grainAmount != 0) {
            Grain(p.grainAmount, p.grainSize, p.grainRoughness, width, height)
        } else null

        val vignette = if (p.vignetteAmount != 0) {
            Vignette(p.vignetteAmount, p.vignetteMidpoint, width, height)
        } else null

        // Decide once whether the filter actually needs the HSV round-trip.
        // The HSV path costs ~2 JNI calls per pixel; if we only need
        // vibrance/saturation (which can be done cheaply in RGB), we can
        // skip it entirely. Dyna in particular hits the fast path here.
        val needsHsv = anyHslAdj(p) || anyCalibAdj(p) ||
                p.shadowTint != 0 || p.highlightSat != 0 || p.shadowSat != 0

        val numWorkers = workerCount(height)
        val chunkRows = ((height + numWorkers - 1) / numWorkers).coerceAtLeast(ROW_CHUNK)

        // ===== Pass 1: per-pixel tone/colour (parallel) =====
        // (WB → master tone LUT → per-channel curves → HSL → calibration →
        //  shadow tint → split toning → vibrance / saturation)
        //
        // Workers process disjoint row ranges. Each worker holds its own
        // rowBuf / hsv / weights buffers so no synchronisation is needed,
        // and Bitmap.getPixels/setPixels on non-overlapping regions is safe
        // in practice (the bitmap is just a contiguous pixel buffer).
        parallelize(numWorkers) { workerIdx ->
            val startY = workerIdx * chunkRows
            val endY = min(startY + chunkRows, height)
            if (startY >= endY) return@parallelize

            val rowBuf = IntArray(width * ROW_CHUNK)
            val hsv = FloatArray(3)
            val weights = FloatArray(8)

            var y = startY
            while (y < endY) {
                val rows = min(ROW_CHUNK, endY - y)
                bitmap.getPixels(rowBuf, 0, width, 0, y, width, rows)

                for (i in 0 until rows * width) {
                    val px = rowBuf[i]
                    val a = px ushr 24 and 0xff
                    var r = px ushr 16 and 0xff
                    var g = px ushr 8 and 0xff
                    var b = px and 0xff

                    // --- White balance (simple scaled multipliers around 1.0) ---
                    if (tempShift != 0f || tintShift != 0f) {
                        var rf = r / 255f
                        var gf = g / 255f
                        var bf = b / 255f
                        if (tempShift != 0f) {
                            rf *= 1f + 0.25f * tempShift
                            bf *= 1f - 0.25f * tempShift
                        }
                        if (tintShift != 0f) {
                            gf *= 1f - 0.20f * tintShift
                            rf *= 1f + 0.05f * tintShift
                            bf *= 1f + 0.05f * tintShift
                        }
                        r = (rf * 255f).toInt().coerceIn(0, 255)
                        g = (gf * 255f).toInt().coerceIn(0, 255)
                        b = (bf * 255f).toInt().coerceIn(0, 255)
                    }

                    // --- Master tone + per-channel curves baked into 3 LUTs ---
                    r = redLut[r]; g = greenLut[g]; b = blueLut[b]

                    if (needsHsv) {
                        // Full HSL/calibration/split-toning path. Inline
                        // RGB↔HSV conversion avoids the JNI overhead of
                        // Color.RGBToHSV / HSVToColor (~50-100 ns each).
                        rgbToHsv(r, g, b, hsv)
                        applyHsl(hsv, p, weights)
                        applyCalibration(hsv, p)
                        applyShadowTint(hsv, p.shadowTint)
                        applySplitToning(hsv, p)

                        if (vibrance != 0f) {
                            val s = hsv[1]
                            hsv[1] = (s + vibrance * (1f - s)).coerceIn(0f, 1f)
                        }
                        if (saturation != 0f) {
                            hsv[1] = (hsv[1] * (1f + saturation)).coerceIn(0f, 1f)
                        }
                        val rgb = hsvToRgb(hsv)
                        r = rgb ushr 16 and 0xff
                        g = rgb ushr 8 and 0xff
                        b = rgb and 0xff
                    } else if (vibrance != 0f || saturation != 0f) {
                        // Fast path: blend each channel toward/away from
                        // luminance. Mathematically equivalent to mul-by-S
                        // in HSV space, and skips both JNI conversions.
                        val lumF = 0.299f * r + 0.587f * g + 0.114f * b
                        var factor = 1f + saturation
                        if (vibrance != 0f) {
                            val mx = if (r >= g) (if (r >= b) r else b) else (if (g >= b) g else b)
                            val mn = if (r <= g) (if (r <= b) r else b) else (if (g <= b) g else b)
                            val s = if (mx == 0) 0f else (mx - mn).toFloat() / mx
                            factor += vibrance * (1f - s)
                        }
                        if (factor != 1f) {
                            r = (lumF + (r - lumF) * factor).toInt().coerceIn(0, 255)
                            g = (lumF + (g - lumF) * factor).toInt().coerceIn(0, 255)
                            b = (lumF + (b - lumF) * factor).toInt().coerceIn(0, 255)
                        }
                    }

                    rowBuf[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }

                bitmap.setPixels(rowBuf, 0, width, 0, y, width, rows)
                y += rows
            }
        }

        // ===== Pass 2: local-contrast (Clarity) =====
        if (p.clarity != 0) {
            applyClarity(bitmap, p.clarity / 100f)
        }

        // ===== Pass 3: position-dependent effects (vignette, grain) =====
        if (grain != null || vignette != null) {
            parallelize(numWorkers) { workerIdx ->
                val startY = workerIdx * chunkRows
                val endY = min(startY + chunkRows, height)
                if (startY >= endY) return@parallelize
                val rowBuf = IntArray(width * ROW_CHUNK)
                var y = startY
                while (y < endY) {
                    val rows = min(ROW_CHUNK, endY - y)
                    bitmap.getPixels(rowBuf, 0, width, 0, y, width, rows)
                    var idx = 0
                    for (ry in 0 until rows) {
                        val absY = y + ry
                        for (x in 0 until width) {
                            val px = rowBuf[idx]
                            var r = px ushr 16 and 0xff
                            var g = px ushr 8 and 0xff
                            var b = px and 0xff
                            val a = px ushr 24 and 0xff

                            if (vignette != null) {
                                val mul = vignette.factorAt(x, absY)
                                r = (r * mul).toInt().coerceIn(0, 255)
                                g = (g * mul).toInt().coerceIn(0, 255)
                                b = (b * mul).toInt().coerceIn(0, 255)
                            }
                            if (grain != null) {
                                val n = grain.valueAt(x, absY)
                                r = (r + n).coerceIn(0, 255)
                                g = (g + n).coerceIn(0, 255)
                                b = (b + n).coerceIn(0, 255)
                            }

                            rowBuf[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            idx++
                        }
                    }
                    bitmap.setPixels(rowBuf, 0, width, 0, y, width, rows)
                    y += rows
                }
            }
        }

        return bitmap
    }

    // -------- Parallelism --------

    /**
     * Number of worker threads to use for a bitmap of [height] rows. Capped
     * by core count (Lightroom-style filters are memory-bandwidth bound at
     * some point so more than ~6 doesn't help), and capped from below so we
     * never try to slice the bitmap into chunks smaller than [ROW_CHUNK].
     */
    private fun workerCount(height: Int): Int {
        // Use all available cores up to 8 — most current Android SoCs have
        // 8 (4 big + 4 little). Above 8 the memory bandwidth becomes the
        // bottleneck anyway.
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        return min(cores, max(1, height / ROW_CHUNK))
    }

    /**
     * Runs [work] N times concurrently with worker index 0..[n]-1. Joins
     * before returning. Single-threaded fall-through when n == 1 so callers
     * don't pay the thread-spawn cost on tiny captures.
     */
    private fun parallelize(n: Int, work: (Int) -> Unit) {
        if (n <= 1) { work(0); return }
        val threads = (0 until n).map { idx ->
            Thread({ work(idx) }, "FilmFilter-$idx").apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    private fun anyHslAdj(p: FilmParams): Boolean {
        for (i in 0..7) {
            if (p.hslHue[i] != 0 || p.hslSat[i] != 0 || p.hslLum[i] != 0) return true
        }
        return false
    }

    private fun anyCalibAdj(p: FilmParams): Boolean =
        p.redPrimaryHue != 0 || p.redPrimarySat != 0 ||
        p.greenPrimaryHue != 0 || p.greenPrimarySat != 0 ||
        p.bluePrimaryHue != 0 || p.bluePrimarySat != 0

    // -------- Clarity (local-contrast enhancement) --------

    /**
     * Lightroom-style "Clarity" — boosts mid-frequency luminance contrast so
     * the image looks crisper / more "HDR" without affecting global tonality
     * or colour saturation (much).
     *
     * Implementation: extract a luminance plane from a downscaled copy of
     * the bitmap, separable-box-blur it (3 passes ≈ Gaussian), then walk the
     * full bitmap and add `amount * (pixelLum − blurredLum)` to each channel.
     * Effect is gated to the mid-tonal range so it doesn't crush already-dark
     * shadows or blow out highlights — that's where "details lost" usually
     * comes from with naive clarity.
     */
    private fun applyClarity(bitmap: Bitmap, amount: Float) {
        if (amount == 0f) return
        val w = bitmap.width
        val h = bitmap.height

        // Downsample so the blur is cheap. 1/8 keeps a 4000-px wide capture
        // at 500 px wide for the blur step.
        val scaleDown = 8
        val smallW = max(8, w / scaleDown)
        val smallH = max(8, h / scaleDown)

        val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)
        val smallPixels = IntArray(smallW * smallH)
        small.getPixels(smallPixels, 0, smallW, 0, 0, smallW, smallH)
        if (small !== bitmap) small.recycle()

        // Extract BT.601 luminance into a float plane.
        val lum = FloatArray(smallW * smallH)
        for (i in smallPixels.indices) {
            val px = smallPixels[i]
            val r = (px ushr 16) and 0xff
            val g = (px ushr 8) and 0xff
            val b = px and 0xff
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val blurred = separableBoxBlur(lum, smallW, smallH, radius = 6, passes = 3)

        val strength = (amount * 0.45f).coerceIn(-1f, 1f)
        val invScale = 1f / scaleDown.toFloat()

        // Pre-compute the x→blurred-column lookup once per call. The blurred
        // plane was already gauss-smoothed at 1/8 resolution, so nearest-
        // neighbour sampling is visually indistinguishable from bilinear
        // and saves 4 array reads + 4 mul + 3 add per pixel.
        val xSamples = IntArray(w) { x ->
            (x * invScale).toInt().coerceIn(0, smallW - 1)
        }

        // The application pass is per-pixel and easily parallelisable —
        // each worker owns its own rowBuf, the blurred plane is read-only.
        val numWorkers = workerCount(h)
        val chunkRows = ((h + numWorkers - 1) / numWorkers).coerceAtLeast(ROW_CHUNK)

        parallelize(numWorkers) { workerIdx ->
            val startY = workerIdx * chunkRows
            val endY = min(startY + chunkRows, h)
            if (startY >= endY) return@parallelize
            val rowBuf = IntArray(w * ROW_CHUNK)
            var y = startY
            while (y < endY) {
                val rows = min(ROW_CHUNK, endY - y)
                bitmap.getPixels(rowBuf, 0, w, 0, y, w, rows)
                for (ry in 0 until rows) {
                    val sy = ((y + ry) * invScale).toInt().coerceIn(0, smallH - 1)
                    val syRow = sy * smallW

                    for (x in 0 until w) {
                        val bL = blurred[syRow + xSamples[x]]

                        val idx = ry * w + x
                        val px = rowBuf[idx]
                        val a = px ushr 24 and 0xff
                        var r = px ushr 16 and 0xff
                        var g = px ushr 8 and 0xff
                        var bl = px and 0xff

                        val pxLum = 0.299f * r + 0.587f * g + 0.114f * bl
                        val detail = pxLum - bL
                        val midWeight = 1f - abs(pxLum / 255f - 0.5f) * 2f
                        val dMag = abs(detail)
                        val edgeFalloff = 1f / (1f + (dMag * dMag) * (1f / 6400f))
                        val delta = (detail * strength * (0.4f + 0.6f * midWeight) * edgeFalloff).toInt()

                        r = (r + delta).coerceIn(0, 255)
                        g = (g + delta).coerceIn(0, 255)
                        bl = (bl + delta).coerceIn(0, 255)

                        rowBuf[idx] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                }
                bitmap.setPixels(rowBuf, 0, w, 0, y, w, rows)
                y += rows
            }
        }
    }

    /**
     * Separable box blur via rolling sum (O(w·h) per pass per direction).
     * [passes] = 3 closely approximates a Gaussian with σ ≈ radius·√(passes/3).
     */
    private fun separableBoxBlur(src: FloatArray, w: Int, h: Int, radius: Int, passes: Int): FloatArray {
        val count = (2 * radius + 1).toFloat()
        var a = src.copyOf()
        var b = FloatArray(w * h)
        repeat(passes) {
            // Horizontal
            for (y in 0 until h) {
                val rowStart = y * w
                var sum = 0f
                for (k in -radius..radius) sum += a[rowStart + k.coerceIn(0, w - 1)]
                b[rowStart] = sum / count
                for (x in 1 until w) {
                    val leaving = (x - 1 - radius).coerceIn(0, w - 1)
                    val entering = (x + radius).coerceIn(0, w - 1)
                    sum += a[rowStart + entering] - a[rowStart + leaving]
                    b[rowStart + x] = sum / count
                }
            }
            run { val t = a; a = b; b = t }
            // Vertical
            for (x in 0 until w) {
                var sum = 0f
                for (k in -radius..radius) sum += a[k.coerceIn(0, h - 1) * w + x]
                b[x] = sum / count
                for (y in 1 until h) {
                    val leaving = (y - 1 - radius).coerceIn(0, h - 1)
                    val entering = (y + radius).coerceIn(0, h - 1)
                    sum += a[entering * w + x] - a[leaving * w + x]
                    b[y * w + x] = sum / count
                }
            }
            run { val t = a; a = b; b = t }
        }
        return a
    }

    // -------- LUT construction --------

    /**
     * Builds a 256-entry LUT that combines contrast, highlights, shadows,
     * whites, blacks and the master point curve into a single lookup.
     * Light values are interpreted as -100..+100 (Lightroom-ish).
     */
    private fun buildMasterLut(p: FilmParams): IntArray {
        val lut = IntArray(256)
        val contrast = p.contrast / 100f
        val highlights = p.highlights / 100f
        val shadows = p.shadows / 100f
        val whites = p.whites / 100f
        val blacks = p.blacks / 100f

        for (i in 0..255) {
            var v = i / 255f

            if (contrast != 0f) {
                v = ((v - 0.5f) * (1f + contrast) + 0.5f).coerceIn(0f, 1f)
            }
            if (highlights != 0f && v > 0.5f) {
                val w = (v - 0.5f) * 2f
                v = (v + highlights * w * 0.25f).coerceIn(0f, 1f)
            }
            if (shadows != 0f && v < 0.5f) {
                val w = (0.5f - v) * 2f
                v = (v + shadows * w * 0.25f).coerceIn(0f, 1f)
            }
            if (whites != 0f && v > 0.7f) {
                val w = ((v - 0.7f) / 0.3f).coerceIn(0f, 1f)
                v = (v + whites * w * 0.2f).coerceIn(0f, 1f)
            }
            if (blacks != 0f && v < 0.3f) {
                val w = ((0.3f - v) / 0.3f).coerceIn(0f, 1f)
                v = (v + blacks * w * 0.2f).coerceIn(0f, 1f)
            }

            // Apply master point curve in 0..255 space.
            val afterCurve = applyCurve(v * 255f, p.pointCurve)
            lut[i] = afterCurve.toInt().coerceIn(0, 255)
        }
        return lut
    }

    /** Builds a 256-entry LUT from a per-channel control-point curve. */
    private fun buildChannelLut(curve: List<Pair<Int, Int>>): IntArray {
        val lut = IntArray(256)
        for (i in 0..255) {
            lut[i] = applyCurve(i.toFloat(), curve).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /**
     * Linear-interpolates [x] (0..255) through the sorted-by-input control
     * points in [curve]. Returns 0..255.
     */
    private fun applyCurve(x: Float, curve: List<Pair<Int, Int>>): Float {
        if (curve.isEmpty()) return x
        val sorted = if (curve.size <= 1 || curve.zipWithNext().all { it.first.first <= it.second.first }) {
            curve
        } else {
            curve.sortedBy { it.first }
        }
        if (x <= sorted.first().first) return sorted.first().second.toFloat()
        if (x >= sorted.last().first) return sorted.last().second.toFloat()
        for (i in 0 until sorted.size - 1) {
            val (x0, y0) = sorted[i]
            val (x1, y1) = sorted[i + 1]
            if (x in x0.toFloat()..x1.toFloat()) {
                val t = if (x1 == x0) 0f else (x - x0) / (x1 - x0).toFloat()
                return y0 + t * (y1 - y0)
            }
        }
        return x
    }

    // -------- Inline RGB↔HSV --------
    //
    // Equivalent to Color.RGBToHSV / Color.HSVToColor, but pure Kotlin
    // so the JIT can inline them into the per-pixel loop and skip the JNI
    // boundary cross (~50-100 ns each, ~2 s saved across 12 MP × 2 calls).

    private fun rgbToHsv(r: Int, g: Int, b: Int, hsv: FloatArray) {
        val mx = if (r >= g) (if (r >= b) r else b) else (if (g >= b) g else b)
        val mn = if (r <= g) (if (r <= b) r else b) else (if (g <= b) g else b)
        val delta = mx - mn
        hsv[2] = mx / 255f
        hsv[1] = if (mx == 0) 0f else delta.toFloat() / mx
        if (delta == 0) {
            hsv[0] = 0f
        } else {
            val df = delta.toFloat()
            var h = when (mx) {
                r -> 60f * ((g - b) / df)
                g -> 60f * ((b - r) / df) + 120f
                else -> 60f * ((r - g) / df) + 240f
            }
            if (h < 0f) h += 360f
            hsv[0] = h
        }
    }

    /** Returns RGB packed as 0x00RRGGBB (alpha is not set). */
    private fun hsvToRgb(hsv: FloatArray): Int {
        val s = hsv[1]
        val v = hsv[2]
        if (s == 0f) {
            val gray = (v * 255f).toInt().coerceIn(0, 255)
            return (gray shl 16) or (gray shl 8) or gray
        }
        val hh = hsv[0] / 60f
        val c = v * s
        val x = c * (1f - abs(hh.mod(2f) - 1f))
        val m = v - c
        val r1: Float; val g1: Float; val b1: Float
        when (hh.toInt()) {
            0 -> { r1 = c; g1 = x; b1 = 0f }
            1 -> { r1 = x; g1 = c; b1 = 0f }
            2 -> { r1 = 0f; g1 = c; b1 = x }
            3 -> { r1 = 0f; g1 = x; b1 = c }
            4 -> { r1 = x; g1 = 0f; b1 = c }
            else -> { r1 = c; g1 = 0f; b1 = x }
        }
        val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    // -------- HSL --------

    /**
     * Computes weights (sum-normalized) of [hueDeg] across the 8 HSL color
     * ranges. Result is written into [out] (length 8).
     */
    private fun hueWeights(hueDeg: Float, out: FloatArray) {
        var sum = 0f
        for (i in 0..7) {
            val center = HSL_CENTERS[i]
            var d = abs(hueDeg - center)
            if (d > 180f) d = 360f - d
            // Falloff window of 30° on either side — gives smooth blend
            // between adjacent Lightroom color ranges.
            val w = if (d >= 60f) 0f else {
                val n = 1f - d / 60f
                n * n
            }
            out[i] = w
            sum += w
        }
        if (sum > 0f) {
            for (i in 0..7) out[i] /= sum
        }
    }

    /**
     * [weights] is a 8-entry scratch buffer the caller owns (so this is
     * thread-safe — each worker passes its own buffer).
     */
    private fun applyHsl(hsv: FloatArray, p: FilmParams, weights: FloatArray) {
        // Cheap fast-path: skip the trig+weighting if all adjustments are 0.
        var anyAdj = false
        for (i in 0..7) {
            if (p.hslHue[i] != 0 || p.hslSat[i] != 0 || p.hslLum[i] != 0) { anyAdj = true; break }
        }
        if (!anyAdj) return

        hueWeights(hsv[0], weights)
        var hueShift = 0f
        var satFactor = 1f
        var lumFactor = 1f
        for (i in 0..7) {
            val w = weights[i]
            if (w == 0f) continue
            hueShift += w * p.hslHue[i] * 0.36f  // ±100 → ±36°
            satFactor += w * p.hslSat[i] / 100f
            lumFactor += w * p.hslLum[i] / 100f
        }
        hsv[0] = (hsv[0] + hueShift).mod(360f)
        hsv[1] = (hsv[1] * satFactor).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * lumFactor).coerceIn(0f, 1f)
    }

    private fun applyCalibration(hsv: FloatArray, p: FilmParams) {
        if (p.redPrimaryHue == 0 && p.redPrimarySat == 0 &&
            p.greenPrimaryHue == 0 && p.greenPrimarySat == 0 &&
            p.bluePrimaryHue == 0 && p.bluePrimarySat == 0) return

        val h = hsv[0]
        // Distance to each primary; weight = max(0, 1 - d/60)^2
        val wR = primaryWeight(h, 0f)
        val wG = primaryWeight(h, 120f)
        val wB = primaryWeight(h, 240f)
        val sum = wR + wG + wB
        if (sum <= 0f) return
        val nR = wR / sum; val nG = wG / sum; val nB = wB / sum

        val hueShift = nR * p.redPrimaryHue * 0.4f +
                nG * p.greenPrimaryHue * 0.4f +
                nB * p.bluePrimaryHue * 0.4f
        val satFactor = 1f + (nR * p.redPrimarySat + nG * p.greenPrimarySat + nB * p.bluePrimarySat) / 100f

        hsv[0] = (hsv[0] + hueShift).mod(360f)
        hsv[1] = (hsv[1] * satFactor).coerceIn(0f, 1f)
    }

    private fun primaryWeight(hue: Float, center: Float): Float {
        var d = abs(hue - center)
        if (d > 180f) d = 360f - d
        if (d >= 60f) return 0f
        val n = 1f - d / 60f
        return n * n
    }

    private fun applyShadowTint(hsv: FloatArray, shadowTint: Int) {
        if (shadowTint == 0) return
        // Push hue toward magenta (+) or green (-) for low-value pixels only.
        val v = hsv[2]
        if (v >= 0.5f) return
        val w = (0.5f - v) * 2f
        val shift = if (shadowTint > 0) {
            // toward magenta (300°)
            (300f - hsv[0]).let { if (it > 180f) it - 360f else if (it < -180f) it + 360f else it } * w * (shadowTint / 100f) * 0.25f
        } else {
            // toward green (120°)
            (120f - hsv[0]).let { if (it > 180f) it - 360f else if (it < -180f) it + 360f else it } * w * (-shadowTint / 100f) * 0.25f
        }
        hsv[0] = (hsv[0] + shift).mod(360f)
    }

    private fun applySplitToning(hsv: FloatArray, p: FilmParams) {
        if (p.highlightSat == 0 && p.shadowSat == 0) return
        val v = hsv[2]
        // Linear weights: 0 at v=0.5 (mid), 1 at extremes.
        val hiW = ((v - 0.5f) * 2f).coerceAtLeast(0f)
        val shW = ((0.5f - v) * 2f).coerceAtLeast(0f)

        if (p.highlightSat != 0 && hiW > 0f) {
            val targetH = p.highlightHue.toFloat().mod(360f)
            val targetS = (p.highlightSat / 100f) * hiW
            blendTowardHs(hsv, targetH, targetS)
        }
        if (p.shadowSat != 0 && shW > 0f) {
            val targetH = p.shadowHue.toFloat().mod(360f)
            val targetS = (p.shadowSat / 100f) * shW
            blendTowardHs(hsv, targetH, targetS)
        }
    }

    private fun blendTowardHs(hsv: FloatArray, hue: Float, weight: Float) {
        // Soft additive: nudge hue toward target, raise saturation slightly.
        val current = hsv[0]
        var diff = hue - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        hsv[0] = (current + diff * weight * 0.3f).mod(360f)
        hsv[1] = (hsv[1] + weight * 0.3f).coerceIn(0f, 1f)
    }

    // -------- Effects --------

    /**
     * Deterministic noise field. Cell size set by [size] (Lightroom's grain
     * size 0..100 ≈ 1..5 px); cells smoothed by [roughness] using a cosine
     * blend with neighbours so grain doesn't look pixelated.
     */
    private class Grain(amount: Int, size: Int, roughness: Int, val w: Int, val h: Int) {
        private val amplitude: Float = amount * 0.45f
        private val cellPx: Int = max(1, (1 + size / 25f).toInt())
        private val smoothness: Float = (roughness / 100f).coerceIn(0f, 1f)
        private val rng = Random(w.toLong() * 73856093L xor h.toLong() * 19349663L xor amount.toLong())
        private val cellsX = max(1, (w + cellPx - 1) / cellPx)
        private val cellsY = max(1, (h + cellPx - 1) / cellPx)
        private val field = FloatArray(cellsX * cellsY).apply {
            for (i in indices) this[i] = (rng.nextFloat() - 0.5f) * 2f
        }

        fun valueAt(x: Int, y: Int): Int {
            val cx = min(cellsX - 1, x / cellPx)
            val cy = min(cellsY - 1, y / cellPx)
            val baseIdx = cy * cellsX + cx
            val base = field[baseIdx]
            // Light smoothing: blend with right/down neighbours by smoothness.
            val rightIdx = if (cx + 1 < cellsX) baseIdx + 1 else baseIdx
            val downIdx = if (cy + 1 < cellsY) baseIdx + cellsX else baseIdx
            val tx = (x % cellPx) / cellPx.toFloat()
            val ty = (y % cellPx) / cellPx.toFloat()
            val sx = if (smoothness > 0f) 0.5f - 0.5f * cos((tx * 3.1415927f)) else tx
            val sy = if (smoothness > 0f) 0.5f - 0.5f * cos((ty * 3.1415927f)) else ty
            val v = base * (1f - sx) * (1f - sy) +
                    field[rightIdx] * sx * (1f - sy) +
                    field[downIdx] * (1f - sx) * sy +
                    field[if (cx + 1 < cellsX && cy + 1 < cellsY) baseIdx + cellsX + 1 else baseIdx] * sx * sy
            return (v * amplitude).toInt()
        }
    }

    /**
     * Radial darkening (or brightening for positive [amount] — Lightroom-style
     * with negative amounts darkening corners). Midpoint controls how soon
     * the falloff begins from the centre.
     */
    private class Vignette(amount: Int, midpoint: Int, val w: Int, val h: Int) {
        private val cx = w / 2f
        private val cy = h / 2f
        private val maxR = sqrt(cx * cx + cy * cy)
        private val amt = amount / 100f  // negative → darker corners
        private val mid = (midpoint.coerceIn(0, 100)) / 100f

        fun factorAt(x: Int, y: Int): Float {
            val dx = x - cx
            val dy = y - cy
            val r = sqrt(dx * dx + dy * dy) / maxR  // 0 center, 1 corner
            if (r <= mid) return 1f
            val t = ((r - mid) / (1f - mid)).coerceIn(0f, 1f)
            val falloff = 1f - exp(-3f * t * t)  // smooth ramp
            return (1f + amt * falloff).coerceIn(0f, 2f)
        }
    }

    // -------- Float helpers --------

    private fun Float.mod(m: Float): Float {
        var r = this % m
        if (r < 0f) r += m
        return r
    }
}
