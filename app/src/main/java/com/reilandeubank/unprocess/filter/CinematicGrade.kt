package com.reilandeubank.unprocess.filter

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The cinematic looks the user can pick in the Select Scene menu.
 * [BLOCKBUSTER] is the default selection.
 */
enum class CinematicMood(val displayName: String) {
    GOLDEN_HOUR("Golden Hour"),
    NEON_NIGHT("Neon Night"),
    MOONLIGHT("Moonlight"),
    BLOCKBUSTER("Blockbuster"),
    GRASSLAND("Grassland"),
    AZURE("Azure"),
    HIGH_KEY("High Key"),
    SILVER_SCREEN("Silver Screen"),
    DAY_TO_NIGHT("Day to Night"),
}

/**
 * One scene-adaptive cinematic grade, produced by [SceneAnalyzer.gradeFor]
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

    /**
     * CPU rendition of the grade for the Select Scene preview tiles — the
     * same maths as the CINEMATIC fragment shader (exposure → WB → HDR
     * toe/shoulder → S-curve → split toning → saturation with skin
     * protection → vignette), minus bloom/softness/grain which are
     * invisible at tile size. Returns a new bitmap; [src] is untouched.
     */
    fun renderPreview(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val gain = Math.pow(2.0, exposure.toDouble()).toFloat()
        val shT = shadowTint
        val hiT = highlightTint
        val invW = 1f / w
        val invH = 1f / h

        for (i in px.indices) {
            val p = px[i]
            var r = (p ushr 16 and 0xff) / 255f
            var g = (p ushr 8 and 0xff) / 255f
            var b = (p and 0xff) / 255f

            // Linear light: exposure + white balance.
            val lr = r * r * gain * wbRed
            val lg = g * g * gain
            val lb = b * b * gain * wbBlue
            r = sqrt(max(0f, lr)); g = sqrt(max(0f, lg)); b = sqrt(max(0f, lb))

            // HDR toe + shoulder (same constants as the shader).
            r += shadowLift * 0.55f * (r + 0.05f) * (1f - r) * (1f - r)
            g += shadowLift * 0.55f * (g + 0.05f) * (1f - g) * (1f - g)
            b += shadowLift * 0.55f * (b + 0.05f) * (1f - b) * (1f - b)
            r -= sq(max(r - 0.72f, 0f)) * highlightRoll * 1.7f
            g -= sq(max(g - 0.72f, 0f)) * highlightRoll * 1.7f
            b -= sq(max(b - 0.72f, 0f)) * highlightRoll * 1.7f

            // Mid-anchored S-curve.
            r += (r * r * (3f - 2f * r) - r) * contrast
            g += (g * g * (3f - 2f * g) - g) * contrast
            b += (b * b * (3f - 2f * b) - b) * contrast

            // Split toning with the shader's 1.6-exponent falloff.
            var y = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
            val shW = Math.pow((1f - y).toDouble(), 1.6).toFloat()
            val hiW = Math.pow(y.toDouble(), 1.6).toFloat()
            r += shT[0] * shW + hiT[0] * hiW
            g += shT[1] * shW + hiT[1] * hiW
            b += shT[2] * shW + hiT[2] * hiW

            // Saturation/vibrance with skin protection.
            y = 0.299f * r + 0.587f * g + 0.114f * b
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val satNow = if (mx <= 0f) 0f else (mx - mn) / mx
            var factor = saturation + vibrance * (1f - satNow)
            val skinW = ((r - g) * 5f).coerceIn(0f, 1f) * ((g - b) * 5f).coerceIn(0f, 1f)
            factor += (min(factor, 1.04f) - factor) * skinW * 0.85f
            r = y + (r - y) * factor
            g = y + (g - y) * factor
            b = y + (b - y) * factor

            // Vignette.
            val xN = (i % w) * invW - 0.5f
            val yN = (i / w) * invH - 0.5f
            val r2 = xN * xN + yN * yN
            val vig = 1f - smoothstep(0.12f, 0.72f, r2) * vignette
            r *= vig; g *= vig; b *= vig

            px[i] = (0xff shl 24) or
                ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
                ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
                (b.coerceIn(0f, 1f) * 255f).toInt()
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun sq(v: Float) = v * v

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * Scene measurement + grade construction for the Narration looks.
 *
 * [gradeFor] builds the grade for the user-chosen mood, adapting the
 * mood's baseline to the measured scene (one viewfinder frame): exposure
 * is pulled toward the mood's target brightness (only partially — AE
 * already did the heavy lifting), the HDR toe/shoulder grow with the
 * measured clipping, contrast complements what the scene already has, and
 * white balance neutralises part of the cast before the mood re-applies
 * its intended bias. Vibrance scales up on muted scenes.
 *
 * [baselineGrade] is the mood's un-adapted character — used for the
 * Select Scene preview tiles and as the fallback when no frame is
 * available.
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
        /** Fixed EV offset applied ON TOP of the adaptive trim — the
         *  day-for-night underexposure lives here, outside the ±0.8 EV
         *  clamp that keeps the other moods' trims tasteful. */
        val evBias: Float = 0f,
    )

    /** Builds [mood]'s grade adapted to the scene in [frame]. */
    fun gradeFor(mood: CinematicMood, frame: Bitmap): CinematicGrade =
        buildGrade(mood, measure(frame))

    /** [mood]'s pure baseline character, with no scene adaptation. */
    fun baselineGrade(mood: CinematicMood): CinematicGrade {
        val base = baselineFor(mood)
        return CinematicGrade(
            moodName = mood.displayName,
            exposure = base.evBias,
            contrast = base.contrast,
            shadowLift = base.lift,
            highlightRoll = base.roll,
            wbRed = base.wbBiasR,
            wbBlue = base.wbBiasB,
            saturation = base.sat,
            vibrance = base.vib,
            shadowTint = base.shadowTint,
            highlightTint = base.highlightTint,
            bloom = base.bloom,
            softness = base.softness,
            vignette = base.vignette,
            grain = base.grain,
        )
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

    // -------- Stage 2: mood baselines --------

    // Baseline strengths are tuned so the grade is unmistakable on a phone
    // screen WITHOUT an A/B reference — the first cut (tints ≈0.02, vignette
    // ≈0.24) was provably invisible next to an ungraded clip of the same
    // scene. Tints sit around 0.05–0.10 and reach into the midtones (the
    // shader applies them with a 1.6-exponent falloff), vignettes around
    // 0.28–0.45. Skin keeps its own protection in the shader, so the
    // stronger pushes land on the scenery, not on faces.
    private fun baselineFor(mood: CinematicMood): Baseline = when (mood) {
        // Warm light kept warm; deep teal shadows against golden highlights.
        CinematicMood.GOLDEN_HOUR -> Baseline(
            targetLuma = 0.46f, contrast = 0.32f, lift = 0.18f, roll = 0.55f,
            wbBiasR = 1.11f, wbBiasB = 0.88f, sat = 1.14f, vib = 0.16f,
            shadowTint = floatArrayOf(-0.058f, 0.012f, 0.084f),
            highlightTint = floatArrayOf(0.084f, 0.038f, -0.058f),
            bloom = 0.70f, softness = 0.45f, vignette = 0.36f, grain = 0.050f,
        )
        // Dark scene with coloured point lights: keep it dark, halate the
        // lights, push blue shadows against a magenta light response.
        CinematicMood.NEON_NIGHT -> Baseline(
            targetLuma = 0.30f, contrast = 0.40f, lift = 0.10f, roll = 0.72f,
            wbBiasR = 1.00f, wbBiasB = 1.09f, sat = 1.26f, vib = 0.22f,
            shadowTint = floatArrayOf(-0.036f, -0.006f, 0.090f),
            highlightTint = floatArrayOf(0.058f, -0.020f, 0.040f),
            bloom = 0.92f, softness = 0.30f, vignette = 0.40f, grain = 0.065f,
        )
        // Flat dark scene without lights: day-for-night blue, soft and quiet.
        CinematicMood.MOONLIGHT -> Baseline(
            targetLuma = 0.32f, contrast = 0.32f, lift = 0.24f, roll = 0.52f,
            wbBiasR = 0.91f, wbBiasB = 1.13f, sat = 0.80f, vib = 0.06f,
            shadowTint = floatArrayOf(-0.032f, 0.006f, 0.084f),
            highlightTint = floatArrayOf(0.000f, 0.012f, 0.032f),
            bloom = 0.62f, softness = 0.45f, vignette = 0.42f, grain = 0.065f,
        )
        // People in frame: the classic teal-and-orange with protected skin.
        CinematicMood.BLOCKBUSTER -> Baseline(
            targetLuma = 0.47f, contrast = 0.38f, lift = 0.20f, roll = 0.62f,
            wbBiasR = 1.05f, wbBiasB = 0.94f, sat = 1.16f, vib = 0.18f,
            shadowTint = floatArrayOf(-0.065f, 0.018f, 0.100f),
            highlightTint = floatArrayOf(0.072f, 0.028f, -0.052f),
            bloom = 0.58f, softness = 0.46f, vignette = 0.32f, grain = 0.045f,
        )
        // Western "Grassland": sun-bleached prairie optics — pale, faded
        // matte shadows tinted dusty sepia, bleached straw highlights,
        // strongly warm/dry white balance, washed-out colour and gritty
        // grain. Think bleach-bypass under a midday desert sun.
        CinematicMood.GRASSLAND -> Baseline(
            targetLuma = 0.50f, contrast = 0.34f, lift = 0.30f, roll = 0.72f,
            wbBiasR = 1.10f, wbBiasB = 0.84f, sat = 0.85f, vib = 0.06f,
            shadowTint = floatArrayOf(0.035f, 0.015f, -0.030f),
            highlightTint = floatArrayOf(0.055f, 0.038f, -0.040f),
            bloom = 0.60f, softness = 0.45f, vignette = 0.34f, grain = 0.070f,
        )
        // Big sky: clean and crisp, slate shadows, warm-white highlights.
        CinematicMood.AZURE -> Baseline(
            targetLuma = 0.50f, contrast = 0.34f, lift = 0.16f, roll = 0.62f,
            wbBiasR = 1.03f, wbBiasB = 1.03f, sat = 1.18f, vib = 0.16f,
            shadowTint = floatArrayOf(-0.024f, 0.000f, 0.055f),
            highlightTint = floatArrayOf(0.050f, 0.024f, -0.031f),
            bloom = 0.52f, softness = 0.36f, vignette = 0.28f, grain = 0.042f,
        )
        // Bright flat light: airy, low contrast, creamy highlights.
        CinematicMood.HIGH_KEY -> Baseline(
            targetLuma = 0.56f, contrast = 0.20f, lift = 0.10f, roll = 0.66f,
            wbBiasR = 1.00f, wbBiasB = 1.00f, sat = 1.02f, vib = 0.11f,
            shadowTint = floatArrayOf(-0.018f, 0.005f, 0.036f),
            highlightTint = floatArrayOf(0.036f, 0.023f, -0.013f),
            bloom = 0.62f, softness = 0.55f, vignette = 0.16f, grain = 0.034f,
        )
        // Scene is already nearly colourless: lean into it, noir-adjacent.
        CinematicMood.SILVER_SCREEN -> Baseline(
            targetLuma = 0.44f, contrast = 0.42f, lift = 0.20f, roll = 0.62f,
            wbBiasR = 1.00f, wbBiasB = 1.00f, sat = 0.55f, vib = 0.04f,
            shadowTint = floatArrayOf(-0.023f, 0.000f, 0.047f),
            highlightTint = floatArrayOf(0.036f, 0.023f, -0.008f),
            bloom = 0.58f, softness = 0.44f, vignette = 0.45f, grain = 0.078f,
        )
        // Day-for-night ("nuit américaine"): footage shot in daylight made
        // to read as night, using the film industry's recipe — underexpose
        // ~2⅓ stops (evBias, outside the adaptive clamp), crush the
        // highlights (roll at the ceiling: nothing stays bright at night
        // except light sources), lift the midtones a touch so the frame
        // stays readable in the dark, cool moonlight white balance with
        // deep blue shadows AND cool highlights, and strong desaturation
        // (scotopic vision barely sees colour).
        CinematicMood.DAY_TO_NIGHT -> Baseline(
            targetLuma = 0.42f, contrast = 0.30f, lift = 0.38f, roll = 0.95f,
            wbBiasR = 0.88f, wbBiasB = 1.15f, sat = 0.62f, vib = 0.04f,
            shadowTint = floatArrayOf(-0.030f, 0.005f, 0.095f),
            highlightTint = floatArrayOf(-0.010f, 0.010f, 0.050f),
            bloom = 0.55f, softness = 0.42f, vignette = 0.46f, grain = 0.065f,
            evBias = -2.3f,
        )
    }

    // -------- Stage 3: adapt the baseline to the measured scene --------

    private fun buildGrade(mood: CinematicMood, s: Stats): CinematicGrade {
        val base = baselineFor(mood)

        // Pull mean luma 70% of the way toward the mood's target, capped at
        // ±0.8 EV — AE already exposed the scene, this is a trim, not a fix.
        // The mood's fixed evBias (day-for-night underexposure) rides on
        // top, outside the clamp.
        val ev = (0.7f * (ln(base.targetLuma / max(0.04f, s.meanLuma)) / LN2))
            .coerceIn(-0.8f, 0.8f) + base.evBias

        // HDR shaping: more shoulder when highlights clip, more toe when
        // shadows crush.
        val roll = (base.roll + s.clipHi * 2.0f).coerceIn(0.25f, 1.0f)
        val lift = (base.lift + s.clipLo * 1.2f).coerceIn(0.05f, 0.60f)

        // Contrast complements the scene toward a filmic RMS target — flat
        // scenes get snap, contrasty scenes get left alone.
        val contrast = (base.contrast + (0.20f - s.rmsContrast) * 1.1f)
            .coerceIn(0.12f, 0.55f)

        // Neutralise ~one third of the measured cast (exponent 0.35), then
        // re-apply the mood's intended bias.
        val wbR = ((s.meanG / max(0.04f, s.meanR)).pow(0.35f) * base.wbBiasR)
            .coerceIn(0.88f, 1.15f)
        val wbB = ((s.meanG / max(0.04f, s.meanB)).pow(0.35f) * base.wbBiasB)
            .coerceIn(0.88f, 1.15f)

        // Muted scenes earn extra vibrance (which spares saturated pixels).
        val vibrance = (base.vib + (0.16f - s.meanSat).coerceAtLeast(0f) * 0.7f)
            .coerceIn(0.02f, 0.40f)

        // Clipped highlights diffuse more — that's where bloom reads as cinema.
        val bloom = (base.bloom + s.clipHi * 1.2f).coerceIn(0.25f, 0.95f)

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
