package com.reilandeubank.unprocess.filter

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One scene-adaptive cinematic grade, produced by [SceneAnalyzer.analyze]
 * from a single viewfinder frame and baked into the recording by the
 * CINEMATIC fragment shader in [AnalogLookRenderer].
 *
 * The parameters deliberately mirror a colourist's toolkit rather than a
 * fixed LUT: exposure trim, HDR-style toe/shoulder, S-curve contrast, white
 * balance, split-toned colour grade, saturation/vibrance, highlight
 * diffusion (bloom), optical softness, vignette and fine grain.
 * [toUniforms] packs them in the layout the shader consumes.
 */
data class CinematicGrade(
    /** Human-readable mood label, shown on the Analyze Scene chip. */
    val moodName: String,
    /** Exposure trim in EV, applied as 2^ev on linear light. */
    val exposure: Float,
    /** 0..1 strength of the mid-anchored S-curve. */
    val contrast: Float,
    /** 0..1 toe lift — reveals shadow detail (the HDR half for the darks). */
    val shadowLift: Float,
    /** 0..1 shoulder roll-off — protects highlights (the other HDR half). */
    val highlightRoll: Float,
    /** White-balance multiplier on linear red (green is the anchor). */
    val wbRed: Float,
    /** White-balance multiplier on linear blue. */
    val wbBlue: Float,
    /** Global saturation multiplier (1 = unchanged). */
    val saturation: Float,
    /** Extra saturation applied to muted pixels only. */
    val vibrance: Float,
    /** Small RGB offsets blended into shadows (≈ zero-mean keeps luma honest). */
    val shadowTint: FloatArray,
    /** Small RGB offsets blended into highlights. */
    val highlightTint: FloatArray,
    /** Highlight diffusion strength (Pro-Mist-style glow), 0..1. */
    val bloom: Float,
    /** Fine-detail diffusion, 0..1 — takes the digital edge off. */
    val softness: Float,
    /** Vignette strength, 0..~0.4. */
    val vignette: Float,
    /** Fine grain amplitude, 0..~0.08. */
    val grain: Float,
) {
    /** Packs the grade as the 18 floats [AnalogLookRenderer] uploads as uniforms. */
    fun toUniforms(): FloatArray = floatArrayOf(
        exposure, contrast, shadowLift, highlightRoll,
        wbRed, wbBlue, saturation, vibrance,
        shadowTint[0], shadowTint[1], shadowTint[2],
        highlightTint[0], highlightTint[1], highlightTint[2],
        bloom, softness, vignette, grain,
    )
}

/**
 * Builds a [CinematicGrade] from one viewfinder frame.
 *
 * Stage 1 measures the scene: luma distribution (histogram → mean,
 * percentiles, clipping fractions), RMS contrast, colour cast, mean
 * saturation, and the fractions of skin-toned / foliage / sky / saturated
 * point-light pixels.
 *
 * Stage 2 picks the closest cinematic mood — each mood is a different
 * answer to "how would a colourist grade this scene?" (golden-hour warmth
 * with teal shadows, neon night with halated lights, airy high key, …).
 *
 * Stage 3 adapts the mood's baseline to the measurements: exposure is
 * pulled toward the mood's target brightness (only partially — AE already
 * did the heavy lifting), the HDR toe/shoulder grow with the measured
 * shadow/highlight clipping, contrast complements what the scene already
 * has, and white balance neutralises part of the cast before the mood
 * re-applies its intended bias. Vibrance scales up on muted scenes.
 *
 * Pure-Kotlin pixel statistics on a 64×64 downscale — sub-millisecond
 * work, no ML runtime, identical behaviour on every device.
 */
object SceneAnalyzer {

    private const val SAMPLE = 64
    private val LN2 = ln(2.0f)

    private class Stats {
        var meanLuma = 0f
        var rmsContrast = 0f
        var meanR = 0f
        var meanG = 0f
        var meanB = 0f
        var meanSat = 0f
        var p95 = 1f
        var clipLo = 0f
        var clipHi = 0f
        var skinFrac = 0f
        var greenFrac = 0f
        var skyFrac = 0f
        var lightsFrac = 0f
    }

    /** The cinematic moods the analyzer can land on. */
    private enum class Mood(val displayName: String) {
        GOLDEN_HOUR("Golden Hour"),
        NEON_NIGHT("Neon Night"),
        MOONLIGHT("Moonlight"),
        BLOCKBUSTER("Blockbuster"),
        VERDANT("Verdant"),
        AZURE("Azure"),
        HIGH_KEY("High Key"),
        SILVER_SCREEN("Silver Screen"),
        CLASSIC("Classic Film"),
    }

    /** A mood's baseline grade before per-scene adaptation. */
    private class Baseline(
        val targetLuma: Float,
        val contrast: Float,
        val lift: Float,
        val roll: Float,
        val wbBiasR: Float,
        val wbBiasB: Float,
        val sat: Float,
        val vib: Float,
        val shadowTint: FloatArray,
        val highlightTint: FloatArray,
        val bloom: Float,
        val softness: Float,
        val vignette: Float,
        val grain: Float,
    )

    fun analyze(frame: Bitmap): CinematicGrade {
        val s = measure(frame)
        val mood = pickMood(s)
        return buildGrade(mood, s)
    }

    // -------- Stage 1: measurement --------

    private fun measure(frame: Bitmap): Stats {
        val small = Bitmap.createScaledBitmap(frame, SAMPLE, SAMPLE, true)
        val px = IntArray(SAMPLE * SAMPLE)
        small.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        if (small !== frame) small.recycle()

        val n = px.size
        val hist = IntArray(32)
        var sumR = 0f; var sumG = 0f; var sumB = 0f
        var sumY = 0f; var sumY2 = 0f; var sumSat = 0f
        var skin = 0; var green = 0; var sky = 0; var lights = 0

        for (i in 0 until n) {
            val p = px[i]
            val r = (p ushr 16 and 0xff) / 255f
            val g = (p ushr 8 and 0xff) / 255f
            val b = (p and 0xff) / 255f
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            sumR += r; sumG += g; sumB += b
            sumY += y; sumY2 += y * y
            hist[min(31, (y * 32f).toInt())]++

            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val sat = if (mx <= 0f) 0f else (mx - mn) / mx
            sumSat += sat

            // Hue classification only for usefully colourful pixels.
            if (sat > 0.12f && mx > 0.08f) {
                val hue = hueOf(r, g, b, mx, mn)
                when {
                    hue in 8f..50f && sat in 0.15f..0.65f && y > 0.15f -> skin++
                    hue in 65f..165f -> green++
                    // Sky only counts in the upper half of the frame.
                    hue in 185f..255f && i < n / 2 -> sky++
                }
                if (y > 0.5f && sat > 0.5f) lights++
            }
        }

        val inv = 1f / n
        return Stats().apply {
            meanLuma = sumY * inv
            rmsContrast = sqrt(max(0f, sumY2 * inv - meanLuma * meanLuma))
            meanR = sumR * inv
            meanG = sumG * inv
            meanB = sumB * inv
            meanSat = sumSat * inv
            p95 = percentile(hist, n, 0.95f)
            clipLo = (hist[0] + hist[1]) * inv
            clipHi = (hist[30] + hist[31]) * inv
            skinFrac = skin * inv
            greenFrac = green * inv
            skyFrac = sky * inv
            lightsFrac = lights * inv
        }
    }

    private fun hueOf(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
        val d = mx - mn
        if (d <= 0f) return 0f
        var h = when (mx) {
            r -> 60f * ((g - b) / d)
            g -> 60f * ((b - r) / d) + 120f
            else -> 60f * ((r - g) / d) + 240f
        }
        if (h < 0f) h += 360f
        return h
    }

    private fun percentile(hist: IntArray, total: Int, p: Float): Float {
        val target = (total * p).toInt()
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return (i + 0.5f) / hist.size
        }
        return 1f
    }

    // -------- Stage 2: mood selection --------

    private fun pickMood(s: Stats): Mood {
        val night = s.meanLuma < 0.24f && s.p95 < 0.80f
        val warmth = s.meanR - s.meanB
        return when {
            night && s.lightsFrac > 0.02f -> Mood.NEON_NIGHT
            night -> Mood.MOONLIGHT
            s.meanLuma > 0.62f && s.rmsContrast < 0.24f -> Mood.HIGH_KEY
            s.skinFrac > 0.07f -> Mood.BLOCKBUSTER
            warmth > 0.05f -> Mood.GOLDEN_HOUR
            s.greenFrac > 0.26f -> Mood.VERDANT
            s.skyFrac > 0.18f -> Mood.AZURE
            s.meanSat < 0.10f -> Mood.SILVER_SCREEN
            else -> Mood.CLASSIC
        }
    }

    private fun baselineFor(mood: Mood): Baseline = when (mood) {
        // Warm light kept warm; teal in the shadows against golden highlights.
        Mood.GOLDEN_HOUR -> Baseline(
            targetLuma = 0.46f, contrast = 0.22f, lift = 0.16f, roll = 0.50f,
            wbBiasR = 1.05f, wbBiasB = 0.95f, sat = 1.05f, vib = 0.10f,
            shadowTint = floatArrayOf(-0.020f, 0.004f, 0.030f),
            highlightTint = floatArrayOf(0.030f, 0.014f, -0.022f),
            bloom = 0.55f, softness = 0.40f, vignette = 0.26f, grain = 0.045f,
        )
        // Dark scene with coloured point lights: keep it dark, halate the
        // lights, push blue shadows against a magenta light response.
        Mood.NEON_NIGHT -> Baseline(
            targetLuma = 0.30f, contrast = 0.30f, lift = 0.10f, roll = 0.65f,
            wbBiasR = 1.00f, wbBiasB = 1.04f, sat = 1.12f, vib = 0.16f,
            shadowTint = floatArrayOf(-0.012f, -0.002f, 0.030f),
            highlightTint = floatArrayOf(0.018f, -0.006f, 0.012f),
            bloom = 0.80f, softness = 0.28f, vignette = 0.30f, grain = 0.060f,
        )
        // Flat dark scene without lights: day-for-night blue, soft and quiet.
        Mood.MOONLIGHT -> Baseline(
            targetLuma = 0.32f, contrast = 0.24f, lift = 0.22f, roll = 0.45f,
            wbBiasR = 0.96f, wbBiasB = 1.06f, sat = 0.92f, vib = 0.06f,
            shadowTint = floatArrayOf(-0.010f, 0.002f, 0.028f),
            highlightTint = floatArrayOf(0.000f, 0.004f, 0.010f),
            bloom = 0.55f, softness = 0.40f, vignette = 0.32f, grain = 0.060f,
        )
        // People in frame: the classic teal-and-orange with protected skin.
        Mood.BLOCKBUSTER -> Baseline(
            targetLuma = 0.47f, contrast = 0.28f, lift = 0.18f, roll = 0.55f,
            wbBiasR = 1.02f, wbBiasB = 0.99f, sat = 1.06f, vib = 0.12f,
            shadowTint = floatArrayOf(-0.022f, 0.006f, 0.034f),
            highlightTint = floatArrayOf(0.024f, 0.010f, -0.018f),
            bloom = 0.45f, softness = 0.42f, vignette = 0.24f, grain = 0.040f,
        )
        // Foliage-dominated: forest-teal shadows under soft golden light.
        Mood.VERDANT -> Baseline(
            targetLuma = 0.46f, contrast = 0.22f, lift = 0.20f, roll = 0.50f,
            wbBiasR = 1.02f, wbBiasB = 0.98f, sat = 1.04f, vib = 0.10f,
            shadowTint = floatArrayOf(-0.014f, 0.008f, 0.020f),
            highlightTint = floatArrayOf(0.020f, 0.012f, -0.014f),
            bloom = 0.40f, softness = 0.38f, vignette = 0.22f, grain = 0.045f,
        )
        // Big sky: clean and crisp, slate shadows, warm-white highlights.
        Mood.AZURE -> Baseline(
            targetLuma = 0.50f, contrast = 0.24f, lift = 0.14f, roll = 0.55f,
            wbBiasR = 1.01f, wbBiasB = 1.01f, sat = 1.08f, vib = 0.10f,
            shadowTint = floatArrayOf(-0.008f, 0.000f, 0.018f),
            highlightTint = floatArrayOf(0.016f, 0.008f, -0.010f),
            bloom = 0.40f, softness = 0.32f, vignette = 0.20f, grain = 0.038f,
        )
        // Bright flat light: airy, low contrast, creamy highlights.
        Mood.HIGH_KEY -> Baseline(
            targetLuma = 0.56f, contrast = 0.14f, lift = 0.10f, roll = 0.60f,
            wbBiasR = 1.00f, wbBiasB = 1.00f, sat = 0.98f, vib = 0.08f,
            shadowTint = floatArrayOf(-0.006f, 0.002f, 0.012f),
            highlightTint = floatArrayOf(0.012f, 0.008f, -0.004f),
            bloom = 0.50f, softness = 0.50f, vignette = 0.10f, grain = 0.030f,
        )
        // Scene is already nearly colourless: lean into it, noir-adjacent.
        Mood.SILVER_SCREEN -> Baseline(
            targetLuma = 0.44f, contrast = 0.32f, lift = 0.18f, roll = 0.55f,
            wbBiasR = 1.00f, wbBiasB = 1.00f, sat = 0.82f, vib = 0.04f,
            shadowTint = floatArrayOf(-0.008f, 0.000f, 0.016f),
            highlightTint = floatArrayOf(0.012f, 0.008f, -0.002f),
            bloom = 0.45f, softness = 0.40f, vignette = 0.34f, grain = 0.065f,
        )
        // Everything else: a balanced, universally flattering film grade.
        Mood.CLASSIC -> Baseline(
            targetLuma = 0.46f, contrast = 0.24f, lift = 0.16f, roll = 0.50f,
            wbBiasR = 1.02f, wbBiasB = 0.99f, sat = 1.04f, vib = 0.10f,
            shadowTint = floatArrayOf(-0.014f, 0.003f, 0.022f),
            highlightTint = floatArrayOf(0.020f, 0.010f, -0.014f),
            bloom = 0.45f, softness = 0.38f, vignette = 0.24f, grain = 0.045f,
        )
    }

    // -------- Stage 3: adapt the baseline to the measured scene --------

    private fun buildGrade(mood: Mood, s: Stats): CinematicGrade {
        val base = baselineFor(mood)

        // Pull mean luma 70% of the way toward the mood's target, capped at
        // ±0.8 EV — AE already exposed the scene, this is a trim, not a fix.
        val ev = (0.7f * (ln(base.targetLuma / max(0.04f, s.meanLuma)) / LN2))
            .coerceIn(-0.8f, 0.8f)

        // HDR shaping: more shoulder when highlights clip, more toe when
        // shadows crush.
        val roll = (base.roll + s.clipHi * 2.0f).coerceIn(0.25f, 1.0f)
        val lift = (base.lift + s.clipLo * 1.2f).coerceIn(0.05f, 0.60f)

        // Contrast complements the scene toward a filmic RMS target — flat
        // scenes get snap, contrasty scenes get left alone.
        val contrast = (base.contrast + (0.20f - s.rmsContrast) * 1.1f)
            .coerceIn(0.08f, 0.45f)

        // Neutralise ~one third of the measured cast (exponent 0.35), then
        // re-apply the mood's intended bias.
        val wbR = ((s.meanG / max(0.04f, s.meanR)).pow(0.35f) * base.wbBiasR)
            .coerceIn(0.88f, 1.15f)
        val wbB = ((s.meanG / max(0.04f, s.meanB)).pow(0.35f) * base.wbBiasB)
            .coerceIn(0.88f, 1.15f)

        // Muted scenes earn extra vibrance (which spares saturated pixels).
        val vibrance = (base.vib + (0.16f - s.meanSat).coerceAtLeast(0f) * 0.7f)
            .coerceIn(0.02f, 0.32f)

        // Clipped highlights diffuse more — that's where bloom reads as cinema.
        val bloom = (base.bloom + s.clipHi * 1.2f).coerceIn(0.20f, 0.90f)

        return CinematicGrade(
            moodName = mood.displayName,
            exposure = ev,
            contrast = contrast,
            shadowLift = lift,
            highlightRoll = roll,
            wbRed = wbR,
            wbBlue = wbB,
            saturation = base.sat,
            vibrance = vibrance,
            shadowTint = base.shadowTint,
            highlightTint = base.highlightTint,
            bloom = bloom,
            softness = base.softness,
            vignette = base.vignette,
            grain = base.grain,
        )
    }
}
