package com.mochanes.emulator.gui;

import java.util.stream.IntStream;

/**
 * A physically-motivated CRT simulation.
 *
 * Rather than overlaying a scanline texture, this models the parts of a real
 * tube that produce the look, in this order:
 *
 * <ol>
 * <li>Phosphor persistence - each phosphor decays over time, so bright moving
 * objects trail. Red/green/blue decay at different rates.</li>
 * <li>Composite (NTSC) bandwidth - chroma carries far less bandwidth than luma,
 * so colour smears horizontally while edges stay sharp.</li>
 * <li>Electron beam spot - the beam is a 2D gaussian, not a pixel. Its width
 * grows with drive voltage, so bright scanlines bloom and swallow the gaps
 * between them while dark ones stay thin. This is what actually creates
 * scanlines; they are the gaps the beam does not reach.</li>
 * <li>Halation - light scattering inside the glass, spreading a glow around
 * bright areas.</li>
 * <li>Screen geometry - the tube face is curved, and can be viewed off-axis.</li>
 * <li>Aperture grille / shadow mask - the metal plate that separates the three
 * phosphor colours.</li>
 * </ol>
 *
 * All compositing happens in linear light; sRGB conversion is applied only at
 * the boundaries, which is what keeps the bloom and scanline falloff from
 * looking muddy.
 */
public class CrtFilter {

    public enum Mask {
        NONE, APERTURE_GRILLE, SHADOW_MASK, SLOT_MASK
    }

    /** Named looks, cycled from the UI. */
    public enum Preset {
        TRINITRON("Sony Trinitron"),
        CONSUMER("Consumer TV"),
        ARCADE("Arcade Monitor"),
        MONOCHROME("Green Phosphor");

        final String label;

        Preset(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    // === Tunables ===
    public boolean enabled = false;
    public Preset preset = Preset.TRINITRON;
    public Mask mask = Mask.APERTURE_GRILLE;

    private float curvature = 0.0f; // barrel distortion; 0 = flat face
    private float tilt = 0.0f; // off-axis viewing angle (the "3D" bit)
    private float maskStrength = 0.50f;
    private float beamMin = 0.42f; // beam sigma (in scanlines) when dark
    private float beamMax = 1.05f; // beam sigma when fully driven
    private float persistR = 0.20f, persistG = 0.26f, persistB = 0.14f;
    private float bloomAmount = 0.30f;
    private float bloomRadius = 2.0f;
    /** Linear-light level above which light scatters into halation. */
    private static final float BLOOM_THRESHOLD = 0.35f;
    private float ntscBleed = 0.75f;
    private float vignette = 0.28f;
    private float brightness = 1.09f; // base gain, before mask compensation
    private float monochrome = 0.0f; // 1 = green phosphor
    private float horizSpot = 0.62f; // beam sigma horizontally, in source pixels
    private float beamGamma = 1.6f;  // how fast the spot widens with drive level

    // === Colour conversion tables ===
    private static final float[] SRGB_TO_LINEAR = new float[256];
    private static final int LIN_LUT = 4096;
    private static final int[] LINEAR_TO_SRGB = new int[LIN_LUT + 1];

    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255f;
            SRGB_TO_LINEAR[i] = (c <= 0.04045f) ? (c / 12.92f) : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
        for (int i = 0; i <= LIN_LUT; i++) {
            float c = i / (float) LIN_LUT;
            float s = (c <= 0.0031308f) ? (c * 12.92f) : (float) (1.055f * Math.pow(c, 1 / 2.4f) - 0.055f);
            LINEAR_TO_SRGB[i] = Math.max(0, Math.min(255, Math.round(s * 255f)));
        }
    }

    // === Buffers (reallocated only when geometry changes) ===
    private int srcW, srcH, outW, outH;
    private float[] phosphor; // persistence state, source res, linear, interleaved RGB
    private float[] signal; // after persistence + NTSC, source res
    private float[] rows; // horizontally resampled: srcH x outW
    private float[] flat; // beam-integrated flat screen: outW x outH
    private float[] bloomSmall, bloomTmp; // downsampled halation buffers
    private int bloomW, bloomH;
    private int[] output;

    // Beam weight LUT indexed by [distance bucket][luminance bucket]
    private static final int DIST_STEPS = 96;
    private static final float DIST_MAX = 3.0f;
    private static final int LUM_STEPS = 32;
    private float[] beamLut;
    private float[] beamNormLut;

    // Horizontal resample taps
    private int hTaps;
    private int[] hIndex;
    private float[] hWeight;

    public CrtFilter() {
        applyPreset(preset);
    }

    // === Public controls ===

    public void toggle() {
        enabled = !enabled;
    }

    public void cyclePreset() {
        Preset[] all = Preset.values();
        preset = all[(preset.ordinal() + 1) % all.length];
        applyPreset(preset);
        invalidate();
    }

    public void cycleMask() {
        Mask[] all = Mask.values();
        mask = all[(mask.ordinal() + 1) % all.length];
    }

    /** Selects a preset directly, resetting its tunables. */
    public void setPreset(Preset p) {
        preset = p;
        applyPreset(p);
        invalidate();
    }

    public void setMask(Mask m) {
        mask = m;
    }

    public void adjustCurvature(float delta) {
        curvature = clamp(curvature + delta, 0f, 0.40f);
    }

    public void adjustTilt(float delta) {
        tilt = clamp(tilt + delta, -0.35f, 0.35f);
    }

    /** How strongly the shadow mask attenuates between phosphor slots. */
    public void adjustMaskStrength(float delta) {
        maskStrength = clamp(maskStrength + delta, 0f, 0.80f);
    }

    /** How much light scatters into the halation glow. */
    public void adjustBloom(float delta) {
        bloomAmount = clamp(bloomAmount + delta, 0f, 1.0f);
    }

    /**
     * Beam focus. Positive sharpens (narrower spot), which is what recovers
     * fine detail like single-pixel text strokes.
     */
    public void adjustSharpness(float delta) {
        horizSpot = clamp(horizSpot - delta, 0.15f, 1.50f);
        invalidate(); // the horizontal tap weights depend on this
    }

    public float getCurvature() {
        return curvature;
    }

    public float getTilt() {
        return tilt;
    }

    public float getMaskStrength() {
        return maskStrength;
    }

    public float getBloom() {
        return bloomAmount;
    }

    /** Reported as focus, so larger reads as sharper. */
    public float getSharpness() {
        return 1.0f - horizSpot;
    }

    /** Returns the tube face to flat and head-on. */
    public void resetGeometry() {
        curvature = 0f;
        tilt = 0f;
    }

    public String status() {
        return String.format("CRT: %s | %s | mask %.2f | bloom %.2f | focus %.2f | curve %.2f | tilt %.2f",
                preset.label(), mask.name().toLowerCase().replace('_', ' '),
                maskStrength, bloomAmount, getSharpness(), curvature, tilt);
    }

    private void applyPreset(Preset p) {
        switch (p) {
            case TRINITRON:
                // Sharp aperture grille, flat-ish face, tight beam.
                mask = Mask.APERTURE_GRILLE;
                curvature = 0.0f;
                beamMin = 0.20f;
                beamMax = 0.60f;
                maskStrength = 0.28f;
                ntscBleed = 0.45f;
                bloomAmount = 0.18f;
                horizSpot = 0.32f;
                monochrome = 0f;
                brightness = 1.09f;
                vignette = 0.10f;
                break;
            case CONSUMER:
                // Soft, curved, heavy composite bleed - a living-room set.
                mask = Mask.SLOT_MASK;
                curvature = 0.0f;
                beamMin = 0.26f;
                beamMax = 0.72f;
                maskStrength = 0.22f;
                ntscBleed = 1.0f;
                bloomAmount = 0.26f;
                horizSpot = 0.80f;
                monochrome = 0f;
                brightness = 0.94f;
                vignette = 0.18f;
                break;
            case ARCADE:
                // Bright, high-contrast, coarse shadow mask.
                mask = Mask.SHADOW_MASK;
                curvature = 0.0f;
                beamMin = 0.18f;
                beamMax = 0.66f;
                maskStrength = 0.34f;
                ntscBleed = 0.30f;
                bloomAmount = 0.30f;
                horizSpot = 0.46f;
                monochrome = 0f;
                brightness = 1.08f;
                vignette = 0.12f;
                break;
            case MONOCHROME:
                mask = Mask.NONE;
                curvature = 0.0f;
                beamMin = 0.24f;
                beamMax = 0.70f;
                maskStrength = 0f;
                ntscBleed = 0.2f;
                bloomAmount = 0.34f;
                horizSpot = 0.65f;
                monochrome = 1f;
                brightness = 1.15f;
                vignette = 0.18f;
                break;
        }
    }

    private void invalidate() {
        beamLut = null;
    }

    // === Main entry point ===

    /**
     * Renders the source framebuffer through the CRT model.
     *
     * @return an ARGB buffer of outW*outH pixels, reused between calls.
     */
    public int[] process(int[] src, int srcW, int srcH, int outW, int outH) {
        ensureBuffers(src, srcW, srcH, outW, outH);

        persistenceAndNtsc(src);
        horizontalPass();
        beamPass();
        halationPass();
        compositePass();

        return output;
    }

    public int outputWidth() {
        return outW;
    }

    public int outputHeight() {
        return outH;
    }

    // === Stage 1: phosphor persistence + composite chroma bleed ===

    private void persistenceAndNtsc(int[] src) {
        final int w = srcW, h = srcH;
        final float bleed = ntscBleed;

        IntStream.range(0, h).parallel().forEach(y -> {
            int row = y * w;

            // Persistence: the phosphor holds a fraction of its previous
            // excitation, so the tube never snaps instantly to black.
            for (int x = 0; x < w; x++) {
                int i = (row + x) * 3;
                int rgb = src[row + x];
                float r = SRGB_TO_LINEAR[(rgb >> 16) & 0xFF];
                float g = SRGB_TO_LINEAR[(rgb >> 8) & 0xFF];
                float b = SRGB_TO_LINEAR[rgb & 0xFF];

                float pr = phosphor[i] * persistR;
                float pg = phosphor[i + 1] * persistG;
                float pb = phosphor[i + 2] * persistB;

                r = Math.max(r, pr);
                g = Math.max(g, pg);
                b = Math.max(b, pb);

                phosphor[i] = r;
                phosphor[i + 1] = g;
                phosphor[i + 2] = b;
            }

            // Composite bandwidth: luma keeps its detail, chroma is smeared.
            // Working in YIQ is what makes this behave like real NTSC rather
            // than a plain blur.
            if (bleed <= 0.001f) {
                System.arraycopy(phosphor, row * 3, signal, row * 3, w * 3);
                return;
            }

            int radius = Math.max(1, Math.round(4 * bleed));
            float lumaMix = 0.30f * bleed;

            for (int x = 0; x < w; x++) {
                float yAcc = 0, iAcc = 0, qAcc = 0, wsum = 0;
                for (int d = -radius; d <= radius; d++) {
                    int sx = Math.min(w - 1, Math.max(0, x + d));
                    int j = (row + sx) * 3;
                    float r = phosphor[j], g = phosphor[j + 1], b = phosphor[j + 2];
                    // Triangular window approximates the chroma filter response.
                    float wt = 1f - Math.abs(d) / (float) (radius + 1);
                    yAcc += wt * (0.299f * r + 0.587f * g + 0.114f * b);
                    iAcc += wt * (0.596f * r - 0.274f * g - 0.322f * b);
                    qAcc += wt * (0.211f * r - 0.523f * g + 0.312f * b);
                    wsum += wt;
                }
                iAcc /= wsum;
                qAcc /= wsum;
                float blurredY = yAcc / wsum;

                int i = (row + x) * 3;
                float r0 = phosphor[i], g0 = phosphor[i + 1], b0 = phosphor[i + 2];
                float sharpY = 0.299f * r0 + 0.587f * g0 + 0.114f * b0;
                float yy = sharpY + (blurredY - sharpY) * lumaMix;

                signal[i] = yy + 0.956f * iAcc + 0.621f * qAcc;
                signal[i + 1] = yy - 0.272f * iAcc - 0.647f * qAcc;
                signal[i + 2] = yy - 1.106f * iAcc + 1.703f * qAcc;
            }
        });
    }

    // === Stage 2: horizontal beam spot, resampling to output width ===

    private void horizontalPass() {
        final int w = srcW, h = srcH, ow = outW;
        final int taps = hTaps;

        IntStream.range(0, h).parallel().forEach(y -> {
            int srcRow = y * w * 3;
            int dstRow = y * ow * 3;
            for (int x = 0; x < ow; x++) {
                float r = 0, g = 0, b = 0;
                int base = x * taps;
                for (int t = 0; t < taps; t++) {
                    float wt = hWeight[base + t];
                    if (wt == 0f)
                        continue;
                    int j = srcRow + hIndex[base + t] * 3;
                    r += wt * signal[j];
                    g += wt * signal[j + 1];
                    b += wt * signal[j + 2];
                }
                int d = dstRow + x * 3;
                rows[d] = r;
                rows[d + 1] = g;
                rows[d + 2] = b;
            }
        });
    }

    // === Stage 3: vertical beam integration (this is what makes scanlines) ===

    private void beamPass() {
        final int h = srcH, ow = outW, oh = outH;
        final float scale = h / (float) oh;

        IntStream.range(0, oh).parallel().forEach(oy -> {
            // Position of this output row in source-scanline units.
            float sy = (oy + 0.5f) * scale - 0.5f;
            int s0 = (int) Math.floor(sy) - 1;
            int dstRow = oy * ow * 3;

            int nearest = Math.max(0, Math.min(h - 1, Math.round(sy)));

            for (int x = 0; x < ow; x++) {
                float r = 0, g = 0, b = 0;
                for (int t = 0; t < 4; t++) {
                    int s = s0 + t;
                    if (s < 0 || s >= h)
                        continue;
                    int j = (s * ow + x) * 3;
                    float lr = rows[j], lg = rows[j + 1], lb = rows[j + 2];

                    // Beam width tracks drive level: bright lines bloom wide
                    // enough to close the gap, dark ones stay pencil-thin.
                    float lum = 0.299f * lr + 0.587f * lg + 0.114f * lb;
                    float d = sy - s;
                    float wt = beamWeight(d, lum);

                    r += wt * lr;
                    g += wt * lg;
                    b += wt * lb;
                }

                // Normalise by the energy of *this* pixel's own spot, so mean
                // brightness is preserved at every drive level while the peak
                // still rides above it. Normalising by the widest spot instead
                // would flatten the scanlines away.
                int j = (nearest * ow + x) * 3;
                float nl = 0.299f * rows[j] + 0.587f * rows[j + 1] + 0.114f * rows[j + 2];
                float norm = beamNormFor(nl);

                int d = dstRow + x * 3;
                flat[d] = r * norm;
                flat[d + 1] = g * norm;
                flat[d + 2] = b * norm;
            }
        });
    }

    private float beamNormFor(float lum) {
        int li = (int) (clamp(lum, 0f, 1f) * (LUM_STEPS - 1) + 0.5f);
        return beamNormLut[li];
    }

    private float beamWeight(float dist, float lum) {
        float ad = Math.abs(dist);
        if (ad >= DIST_MAX)
            return 0f;
        int di = (int) (ad * (DIST_STEPS / DIST_MAX));
        if (di >= DIST_STEPS)
            di = DIST_STEPS - 1;
        int li = (int) (clamp(lum, 0f, 1f) * (LUM_STEPS - 1) + 0.5f);
        return beamLut[li * DIST_STEPS + di];
    }

    // === Stage 4: halation (light scattering in the glass) ===

    private void halationPass() {
        if (bloomAmount <= 0.001f)
            return;

        final int ow = outW, bw = bloomW, bh = bloomH;
        final int fx = ow / bw, fy = outH / bh;

        // Downsample, keeping only the energy above mid-grey - halation is
        // driven by the bright parts of the image.
        IntStream.range(0, bh).parallel().forEach(by -> {
            for (int bx = 0; bx < bw; bx++) {
                float r = 0, g = 0, b = 0;
                for (int y = 0; y < fy; y++) {
                    int row = (by * fy + y) * ow * 3;
                    for (int x = 0; x < fx; x++) {
                        int j = row + (bx * fx + x) * 3;
                        // Only light above the threshold scatters. Summing the
                        // raw image instead adds a flat pedestal everywhere,
                        // which lifts blacks - dark text on a white panel turns
                        // grey and stops being readable.
                        r += Math.max(0f, flat[j] - BLOOM_THRESHOLD);
                        g += Math.max(0f, flat[j + 1] - BLOOM_THRESHOLD);
                        b += Math.max(0f, flat[j + 2] - BLOOM_THRESHOLD);
                    }
                }
                float n = fx * fy;
                int d = (by * bw + bx) * 3;
                bloomSmall[d] = r / n;
                bloomSmall[d + 1] = g / n;
                bloomSmall[d + 2] = b / n;
            }
        });

        blurSmall();
    }

    private void blurSmall() {
        final int bw = bloomW, bh = bloomH;
        int radius = Math.max(1, Math.round(bloomRadius));

        // Separable box blur, run twice to approximate a gaussian.
        for (int pass = 0; pass < 2; pass++) {
            IntStream.range(0, bh).parallel().forEach(y -> {
                int row = y * bw * 3;
                for (int x = 0; x < bw; x++) {
                    float r = 0, g = 0, b = 0;
                    int n = 0;
                    for (int d = -radius; d <= radius; d++) {
                        int sx = x + d;
                        if (sx < 0 || sx >= bw)
                            continue;
                        int j = row + sx * 3;
                        r += bloomSmall[j];
                        g += bloomSmall[j + 1];
                        b += bloomSmall[j + 2];
                        n++;
                    }
                    int j = row + x * 3;
                    bloomTmp[j] = r / n;
                    bloomTmp[j + 1] = g / n;
                    bloomTmp[j + 2] = b / n;
                }
            });
            IntStream.range(0, bh).parallel().forEach(y -> {
                for (int x = 0; x < bw; x++) {
                    float r = 0, g = 0, b = 0;
                    int n = 0;
                    for (int d = -radius; d <= radius; d++) {
                        int sy = y + d;
                        if (sy < 0 || sy >= bh)
                            continue;
                        int j = (sy * bw + x) * 3;
                        r += bloomTmp[j];
                        g += bloomTmp[j + 1];
                        b += bloomTmp[j + 2];
                        n++;
                    }
                    int j = (y * bw + x) * 3;
                    bloomSmall[j] = r / n;
                    bloomSmall[j + 1] = g / n;
                    bloomSmall[j + 2] = b / n;
                }
            });
        }
    }

    /** Bilinear sample of the halation buffer; writes RGB into out[0..2]. */
    private void sampleBloom(float u, float v, float[] out) {
        float fx = clamp(u, 0f, 1f) * (bloomW - 1);
        float fy = clamp(v, 0f, 1f) * (bloomH - 1);
        int x0 = (int) fx, y0 = (int) fy;
        int x1 = Math.min(bloomW - 1, x0 + 1), y1 = Math.min(bloomH - 1, y0 + 1);
        float tx = fx - x0, ty = fy - y0;

        int i00 = (y0 * bloomW + x0) * 3, i10 = (y0 * bloomW + x1) * 3;
        int i01 = (y1 * bloomW + x0) * 3, i11 = (y1 * bloomW + x1) * 3;

        for (int c = 0; c < 3; c++) {
            float top = bloomSmall[i00 + c] + (bloomSmall[i10 + c] - bloomSmall[i00 + c]) * tx;
            float bot = bloomSmall[i01 + c] + (bloomSmall[i11 + c] - bloomSmall[i01 + c]) * tx;
            out[c] = top + (bot - top) * ty;
        }
    }

    // === Stage 5+6: geometry, mask, vignette, output encoding ===

    private void compositePass() {
        final int ow = outW, oh = outH;
        final float curve = curvature;
        final float tiltAmt = tilt;
        final boolean doBloom = bloomAmount > 0.001f;

        // The mask attenuates, so `brightness` carries a compensation factor for
        // the light it removes. That compensation has to track the mask actually
        // in use - otherwise switching to Mask.NONE leaves the gain applied with
        // nothing to offset it, and the picture runs hot enough to wash out dark
        // text on a bright background.
        final float gain = brightness / maskMeanGain();
        final boolean identity = (curve == 0f && tiltAmt == 0f);

        IntStream.range(0, oh).parallel().forEach(oy -> {
            float v = (oy + 0.5f) / oh * 2f - 1f;
            int dst = oy * ow;
            float[] halo = new float[3];

            for (int ox = 0; ox < ow; ox++) {
                float u = (ox + 0.5f) / ow * 2f - 1f;

                // Off-axis view: a perspective divide across x gives the tube
                // an apparent rotation, with the far edge compressed.
                float uu = u, vv = v;
                if (tiltAmt != 0f) {
                    float wDiv = 1f + tiltAmt * u;
                    if (wDiv < 0.05f)
                        wDiv = 0.05f;
                    uu = u / wDiv;
                    vv = v / wDiv;
                }

                // Barrel curvature of the tube face.
                float u2 = uu * uu, v2 = vv * vv;
                float cu = uu * (1f + curve * v2);
                float cv = vv * (1f + curve * u2);

                if (cu < -1f || cu > 1f || cv < -1f || cv > 1f) {
                    output[dst + ox] = 0xFF000000; // outside the glass
                    continue;
                }

                float r, g, b;
                if (identity) {
                    // No geometry to apply: read straight through. Resampling
                    // here would blur the beam and mask structure for nothing.
                    int i = (oy * ow + ox) * 3;
                    r = flat[i];
                    g = flat[i + 1];
                    b = flat[i + 2];
                } else {
                    float sx = (cu + 1f) * 0.5f * (ow - 1);
                    float sy = (cv + 1f) * 0.5f * (oh - 1);

                    int x0 = (int) sx, y0 = (int) sy;
                    int x1 = Math.min(ow - 1, x0 + 1), y1 = Math.min(oh - 1, y0 + 1);
                    float tx = sx - x0, ty = sy - y0;

                    int i00 = (y0 * ow + x0) * 3, i10 = (y0 * ow + x1) * 3;
                    int i01 = (y1 * ow + x0) * 3, i11 = (y1 * ow + x1) * 3;

                    r = bilerp(flat[i00], flat[i10], flat[i01], flat[i11], tx, ty);
                    g = bilerp(flat[i00 + 1], flat[i10 + 1], flat[i01 + 1], flat[i11 + 1], tx, ty);
                    b = bilerp(flat[i00 + 2], flat[i10 + 2], flat[i01 + 2], flat[i11 + 2], tx, ty);
                }

                if (doBloom) {
                    float bu = (cu + 1f) * 0.5f, bv = (cv + 1f) * 0.5f;
                    sampleBloom(bu, bv, halo);
                    r += halo[0] * bloomAmount;
                    g += halo[1] * bloomAmount;
                    b += halo[2] * bloomAmount;
                }

                // Aperture grille / shadow mask, in screen space so the fine
                // structure stays crisp instead of aliasing through the warp.
                if (mask != Mask.NONE && maskStrength > 0f) {
                    float mr = 1f, mg = 1f, mb = 1f;
                    switch (mask) {
                        case APERTURE_GRILLE: {
                            int p = ox % 3;
                            mr = (p == 0) ? 1f : 0f;
                            mg = (p == 1) ? 1f : 0f;
                            mb = (p == 2) ? 1f : 0f;
                            break;
                        }
                        case SHADOW_MASK: {
                            int p = (ox + (oy % 2) * 3) % 6;
                            mr = (p < 2) ? 1f : 0f;
                            mg = (p >= 2 && p < 4) ? 1f : 0f;
                            mb = (p >= 4) ? 1f : 0f;
                            break;
                        }
                        case SLOT_MASK: {
                            int p = (ox + ((oy / 3) % 2) * 3) % 6;
                            mr = (p < 2) ? 1f : 0f;
                            mg = (p >= 2 && p < 4) ? 1f : 0f;
                            mb = (p >= 4) ? 1f : 0f;
                            // Horizontal slot gaps between phosphor rows.
                            if (oy % 6 == 5)
                                mr = mg = mb = 0f;
                            break;
                        }
                        default:
                            break;
                    }
                    // A real mask is an aperture: it blocks light, never adds
                    // it. Gains stay in [1-k, 1] so the brightest a subpixel
                    // can be is unattenuated. Amplifying here (a "mean
                    // preserving" triad peaking above 1) is what blows the
                    // highlights out; the lost average is restored by the
                    // brightness gain below instead.
                    float k = maskStrength;
                    r *= 1f - k * (1f - mr);
                    g *= 1f - k * (1f - mg);
                    b *= 1f - k * (1f - mb);
                }

                if (monochrome > 0f) {
                    float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                    r += (lum * 0.25f - r) * monochrome;
                    g += (lum * 1.05f - g) * monochrome;
                    b += (lum * 0.35f - b) * monochrome;
                }

                // Vignette from the tube's corner falloff.
                if (vignette > 0f) {
                    float rad = uu * uu + vv * vv;
                    float vig = 1f - vignette * rad * 0.5f;
                    if (vig < 0f)
                        vig = 0f;
                    r *= vig;
                    g *= vig;
                    b *= vig;
                }

                r *= gain;
                g *= gain;
                b *= gain;

                output[dst + ox] = 0xFF000000 | (encode(r) << 16) | (encode(g) << 8) | encode(b);
            }
        });
    }

    /**
     * Average transmission of the current mask, per channel. Each channel is
     * unattenuated in its own slot and scaled by (1 - strength) elsewhere; the
     * slot mask additionally blanks one row in six.
     */
    private float maskMeanGain() {
        if (mask == Mask.NONE || maskStrength <= 0f) {
            return 1f;
        }
        float mean = 1f - maskStrength * (2f / 3f);
        if (mask == Mask.SLOT_MASK) {
            mean *= 5f / 6f; // horizontal gaps between phosphor rows
        }
        return Math.max(0.05f, mean);
    }

    private static float bilerp(float a, float b, float c, float d, float tx, float ty) {
        float top = a + (b - a) * tx;
        float bot = c + (d - c) * tx;
        return top + (bot - top) * ty;
    }

    /**
     * Soft highlight shoulder. Below the knee the response is linear; above it
     * the curve eases off, reaching exactly 1.0 at (2 - knee) with matching
     * slope at both ends. Phosphor saturates gradually rather than switching
     * off, and this keeps detail in the beam peaks that would otherwise all
     * land on flat white.
     */
    private static final float KNEE = 0.75f;

    private static float softClip(float x) {
        if (x <= KNEE)
            return x;
        float span = 2f * (1f - KNEE); // reaches 1.0 exactly at KNEE + span
        float t = (x - KNEE) / span;
        if (t >= 1f)
            return 1f;
        return KNEE + span * (t - t * t * 0.5f);
    }

    private static int encode(float linear) {
        if (linear <= 0f)
            return 0;
        float v = softClip(linear);
        if (v >= 1f)
            return 255;
        return LINEAR_TO_SRGB[(int) (v * LIN_LUT)];
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // === Setup ===

    private void ensureBuffers(int[] src, int sw, int sh, int ow, int oh) {
        boolean resized = (sw != srcW || sh != srcH || ow != outW || oh != outH);
        if (resized) {
            srcW = sw;
            srcH = sh;
            outW = ow;
            outH = oh;

            phosphor = new float[sw * sh * 3];
            signal = new float[sw * sh * 3];
            rows = new float[sh * ow * 3];
            flat = new float[ow * oh * 3];
            output = new int[ow * oh];

            // Bloom buffer at roughly 1/8 scale, with exact divisors so the
            // downsample stays a clean box filter.
            bloomW = Math.max(8, ow / 8);
            bloomH = Math.max(8, oh / 8);
            while (ow % bloomW != 0 && bloomW > 8)
                bloomW--;
            while (oh % bloomH != 0 && bloomH > 8)
                bloomH--;
            bloomSmall = new float[bloomW * bloomH * 3];
            bloomTmp = new float[bloomW * bloomH * 3];

            buildHorizontalTaps();
        }
        if (beamLut == null) {
            buildBeamLut();
            if (!resized)
                buildHorizontalTaps();
        }
    }

    private void buildBeamLut() {
        beamLut = new float[LUM_STEPS * DIST_STEPS];
        beamNormLut = new float[LUM_STEPS];

        for (int li = 0; li < LUM_STEPS; li++) {
            float lum = li / (float) (LUM_STEPS - 1);
            // Spot size vs drive level. The exponent matters more than it looks:
            // once sigma passes ~0.35 of the scanline pitch, neighbouring lines
            // overlap enough to fill each other's gaps and the scanlines vanish.
            // Keeping mid-tones thin is what preserves them.
            float sigma = beamMin + (beamMax - beamMin) * (float) Math.pow(lum, beamGamma);
            float inv = 1f / (2f * sigma * sigma);
            for (int di = 0; di < DIST_STEPS; di++) {
                float d = (di + 0.5f) * (DIST_MAX / DIST_STEPS);
                beamLut[li * DIST_STEPS + di] = (float) Math.exp(-d * d * inv);
            }
            // A gaussian of width sigma spread over unit scanline pitch carries
            // sigma*sqrt(2pi) of energy; dividing by it preserves mean level.
            float energy = (float) (sigma * Math.sqrt(2 * Math.PI));
            beamNormLut[li] = 1f / Math.max(0.35f, energy);
        }
    }

    private void buildHorizontalTaps() {
        float scale = srcW / (float) outW;
        float sigma = Math.max(0.25f, horizSpot);
        int radius = Math.max(1, (int) Math.ceil(sigma * 2.5f));
        hTaps = radius * 2 + 2;
        hIndex = new int[outW * hTaps];
        hWeight = new float[outW * hTaps];

        for (int x = 0; x < outW; x++) {
            float sx = (x + 0.5f) * scale - 0.5f;
            int c = Math.round(sx);
            int base = x * hTaps;
            float sum = 0;
            for (int t = 0; t < hTaps; t++) {
                int s = c - radius + t;
                float w = 0;
                if (s >= 0 && s < srcW) {
                    float d = sx - s;
                    w = (float) Math.exp(-d * d / (2f * sigma * sigma));
                }
                hIndex[base + t] = Math.min(srcW - 1, Math.max(0, s));
                hWeight[base + t] = w;
                sum += w;
            }
            if (sum > 0) {
                for (int t = 0; t < hTaps; t++)
                    hWeight[base + t] /= sum;
            }
        }
    }
}
