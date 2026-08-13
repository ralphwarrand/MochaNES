package com.mochanes.web;

import org.teavm.jso.JSBody;

/**
 * Video output: a WebGL CRT simulation, with a plain 2D canvas as fallback.
 *
 * <p>The desktop CRT filter runs on the CPU and costs about 9ms a frame. The
 * same model as a fragment shader is essentially free, because beam profiles,
 * masks and blooms are exactly what a GPU does. It is a single pass: for each
 * output pixel, gather the two source scanlines that can light it, filter them,
 * apply the shadow mask, then add bloom and halation.
 *
 * <p>The emulator writes pixels into a shared Int32Array either way, so nothing
 * in the emulation core knows which path is active.
 *
 * <p>The shader source is written inline in the scripts below rather than passed
 * in as a parameter: the ahead-of-time compiler rewrites these scripts, and
 * parameter names can collide with the locals it introduces. It is one long line
 * because GLSL needs no line breaks without preprocessor directives, which also
 * rules out any chance of a stray line comment swallowing the rest.
 */
final class Renderer {

    private Renderer() {
    }

    /**
     * Sets up the canvas, preferring WebGL and falling back to a 2D context.
     *
     * @return true if the CRT simulation is available
     */
    @JSBody(params = { "canvasId" }, script = ""
            + "window.NESW = window.NESW || {};"
            + "var N = window.NESW;"
            + "var cv = document.getElementById(canvasId);"
            + "N.canvas = cv;"
            + "N.buffer = new ArrayBuffer(256 * 240 * 4);"
            + "N.pixels = new Int32Array(N.buffer);"
            + "N.bytes = new Uint8Array(N.buffer);"

            + "var VSRC = 'attribute vec2 aPos; varying vec2 vUv;"
            + " void main() { vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);"
            + " gl_Position = vec4(aPos, 0.0, 1.0); }';"

            + "var FSRC = 'precision highp float; varying vec2 vUv; uniform sampler2D uTex;"
            + " uniform vec2 uOutput; uniform float uCrt; uniform float uMask; uniform float uMaskType;"
            + " uniform float uBloom; uniform float uFocus; uniform float uScan; uniform float uCurve;"
            + " uniform float uSat; uniform float uBright;"
            + " const vec2 SRC = vec2(256.0, 240.0);"
            + " const vec3 LUMA = vec3(0.299, 0.587, 0.114);"

            /* Compositing in linear light is what keeps the bloom and the
               scanline falloff from looking muddy, but the conversion sits in
               the hottest path there is: every texture fetch runs it, and there
               are around twenty per output pixel. At pow(c, 2.2) that is sixty
               pow calls per pixel and it dominated the frame.

               Squaring is gamma 2.0 rather than 2.2. The difference is a slight
               shift in midtone contrast, far below what this effect's own
               softness hides, and it costs one multiply instead. */
            + " vec3 toLinear(vec3 c) { c = max(c, 0.0); return c * c; }"
            + " vec3 toSrgb(vec3 c) { return sqrt(max(c, 0.0)); }"
            /* Taps reach past the picture at the edges. Vertical bounds were
               guarded but horizontal ones were not, so CLAMP_TO_EDGE repeated
               the first and last columns into the filter and bloom, brightening
               and smearing exactly those columns. Off the picture is blanking,
               which is black. */
            + " vec3 fetch(float x, float row) {"
            + "   if (row < 0.0 || row > SRC.y - 1.0) return vec3(0.0);"
            + "   if (x < 0.0 || x > 1.0) return vec3(0.0);"
            + "   return toLinear(texture2D(uTex, vec2(x, (row + 0.5) / SRC.y)).rgb); }"

            /* One scanline, filtered horizontally. Luma keeps a sharp centre tap
               while chroma is averaged over five, which is how composite video
               behaves: colour smears sideways but edges stay crisp. */
            + " vec3 line(float x, float row) {"
            + "   float t = 1.0 / SRC.x;"
            + "   vec3 sharp = fetch(x, row);"
            + "   vec3 soft = fetch(x - 2.0 * t, row) * 0.12 + fetch(x - t, row) * 0.24"
            + "             + sharp * 0.28 + fetch(x + t, row) * 0.24 + fetch(x + 2.0 * t, row) * 0.12;"
            + "   float y = dot(sharp, LUMA);"
            + "   vec3 chroma = soft - vec3(dot(soft, LUMA));"
            + "   return max(vec3(y) + chroma * uSat, 0.0); }"

            /* The electron beam. Its width grows with drive level, so a bright
               line blooms wide enough to close the gap above it while a dim one
               stays thin. That, not a dark overlay, is what scanlines are. */
            + " float beam(float d, float level) {"
            + "   float w = mix(0.30, 0.72, sqrt(clamp(level, 0.0, 1.0)));"
            + "   w *= mix(2.4, 1.0, uFocus);"
            + "   float n = d / max(w, 0.05);"
            + "   return exp(-n * n * 1.7); }"

            /* Aperture grille, shadow mask and slot mask, in screen space so the
               structure stays crisp instead of aliasing against the source. */
            + " vec3 maskAt(vec2 p) {"
            + "   if (uMaskType < 0.5) {"
            + "     float q = mod(p.x, 3.0);"
            + "     return vec3(q < 1.0 ? 1.0 : 0.28, (q >= 1.0 && q < 2.0) ? 1.0 : 0.28, q >= 2.0 ? 1.0 : 0.28); }"
            + "   if (uMaskType < 1.5) {"
            + "     float q = mod(p.x + floor(mod(p.y, 4.0) * 0.5) * 1.5, 3.0);"
            + "     return vec3(q < 1.0 ? 1.0 : 0.34, (q >= 1.0 && q < 2.0) ? 1.0 : 0.34, q >= 2.0 ? 1.0 : 0.34); }"
            + "   if (uMaskType < 2.5) {"
            + "     float q = mod(p.x, 2.0);"
            + "     float r = mod(floor(p.y * 0.5), 2.0);"
            + "     return vec3(abs(q - r) < 0.5 ? 1.0 : 0.4); }"
            + "   return vec3(1.0); }"

            + " float maskMean() {"
            + "   if (uMaskType < 0.5) return 0.52;"
            + "   if (uMaskType < 1.5) return 0.56;"
            + "   if (uMaskType < 2.5) return 0.70;"
            + "   return 1.0; }"
            + " void main() {"
            + "   vec2 uv = vUv;"
            + "   if (uCurve > 0.001) {"
            + "     vec2 c = uv * 2.0 - 1.0;"
            + "     c *= 1.0 + uCurve * 0.12 * dot(c, c);"
            + "     uv = c * 0.5 + 0.5; }"
            + "   if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {"
            + "     gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }"
            + "   if (uCrt < 0.5) {"
            + "     gl_FragColor = vec4(texture2D(uTex, uv).rgb, 1.0); return; }"
            + "   float py = uv.y * SRC.y - 0.5;"
            + "   float row = floor(py);"
            + "   float f = py - row;"
            + "   vec3 a = line(uv.x, row);"
            + "   vec3 b = line(uv.x, row + 1.0);"
            + "   float wa = beam(f, dot(a, LUMA));"
            + "   float wb = beam(1.0 - f, dot(b, LUMA));"
            + "   float sum = max(wa + wb, 0.001);"
            + "   vec3 col = (a * wa + b * wb) / sum;"
            + "   col *= mix(1.0, min(sum, 1.0), uScan);"

            /* Halation: light scattering forward through the glass, driven only
               by the bright parts of a wide neighbourhood. */
            + "   if (uBloom > 0.001) {"
            + "     float t = 1.0 / SRC.x;"
            + "     vec3 glow = vec3(0.0);"
            + "     for (int i = -2; i <= 2; i++) {"
            + "       float o = float(i);"
            + "       vec3 s = fetch(uv.x + o * t * 2.5, row) + fetch(uv.x + o * t * 2.5, row + 1.0);"
            + "       glow += max(s * 0.5 - 0.45, 0.0) * (1.0 - abs(o) / 3.0); }"
            + "     col += glow * uBloom * 0.24; }"
            + "   vec2 sp = vUv * uOutput;"
            + "   vec3 m = mix(vec3(1.0), maskAt(sp), uMask);"
            + "   col *= m * (1.0 / mix(1.0, maskMean(), uMask)) * uBright;"

            /* A soft shoulder instead of a hard clamp, so highlights roll off the
               way a phosphor saturates rather than flat-topping. */
            + "   col = col / (1.0 + max(col - 0.75, 0.0));"
            + "   gl_FragColor = vec4(toSrgb(col), 1.0); }';"

            + "var opts = { alpha: false, antialias: false, preserveDrawingBuffer: true };"
            + "var gl = cv.getContext('webgl', opts) || cv.getContext('experimental-webgl', opts);"
            + "function use2d() {"
            + "  var c2 = cv.getContext('2d', { alpha: false });"
            + "  if (!c2) return false;"
            + "  c2.imageSmoothingEnabled = false;"
            + "  N.ctx2d = c2;"
            + "  N.img = c2.createImageData(256, 240);"
            + "  return false;"
            + "}"
            + "if (!gl) return use2d();"
            + "function build(type, src) {"
            + "  var s = gl.createShader(type);"
            + "  gl.shaderSource(s, src);"
            + "  gl.compileShader(s);"
            + "  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {"
            + "    console.error('shader: ' + gl.getShaderInfoLog(s));"
            + "    return null; }"
            + "  return s; }"
            + "var vs = build(gl.VERTEX_SHADER, VSRC);"
            + "var fs = build(gl.FRAGMENT_SHADER, FSRC);"
            + "if (!vs || !fs) return use2d();"
            + "var pr = gl.createProgram();"
            + "gl.attachShader(pr, vs); gl.attachShader(pr, fs); gl.linkProgram(pr);"
            + "if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) {"
            + "  console.error('link: ' + gl.getProgramInfoLog(pr));"
            + "  return use2d(); }"
            + "gl.useProgram(pr);"
            + "var vb = gl.createBuffer();"
            + "gl.bindBuffer(gl.ARRAY_BUFFER, vb);"
            + "gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);"
            + "var loc = gl.getAttribLocation(pr, 'aPos');"
            + "gl.enableVertexAttribArray(loc);"
            + "gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);"
            + "var tx = gl.createTexture();"
            + "gl.bindTexture(gl.TEXTURE_2D, tx);"
            + "gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);"
            + "gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);"
            + "gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);"
            + "gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);"
            + "N.gl = gl; N.prog = pr; N.tex = tx; N.u = {};"
            + "var names = ['uOutput','uCrt','uMask','uMaskType','uBloom','uFocus','uScan','uCurve','uSat','uBright'];"
            + "for (var i = 0; i < names.length; i++) N.u[names[i]] = gl.getUniformLocation(pr, names[i]);"
            + "return true;")
    static native boolean init(String canvasId);

    /** Writes one pixel. 0xRRGGBB in, 0xAABBGGRR out for a little-endian view. */
    @JSBody(params = { "index", "rgb" }, script = ""
            + "window.NESW.pixels[index] = 0xFF000000 | ((rgb & 0xFF) << 16)"
            + " | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);")
    static native void setPixel(int index, int rgb);

    /** Uploads and draws the completed frame. */
    @JSBody(params = {}, script = ""
            + "var N = window.NESW;"
            + "if (!N.gl) {"
            + "  if (!N.ctx2d) return;"
            + "  N.img.data.set(N.bytes);"
            + "  N.ctx2d.putImageData(N.img, 0, 0);"
            + "  return; }"
            + "var gl = N.gl, cv = N.canvas;"
            /* The drawing buffer is kept at exactly one CSS pixel per texel.
               The shadow mask is drawn in buffer pixels - stripes three across -
               so any mismatch between buffer and displayed size makes the
               browser rescale them, and the mask turns into a blurred smear.
               That is what a smaller buffer bought in performance and cost in
               appearance, most visibly in fullscreen where the gap is widest.

               Rendering above one CSS pixel is still refused: at device pixel
               ratio 2 it quadruples the fragment count for mask detail the eye
               cannot separate anyway. */
            + "var dw = Math.max(1, Math.round(cv.clientWidth));"
            + "var dh = Math.max(1, Math.round(cv.clientHeight));"
            + "if (cv.width !== dw || cv.height !== dh) { cv.width = dw; cv.height = dh; }"
            + "gl.viewport(0, 0, cv.width, cv.height);"
            + "gl.uniform2f(N.u.uOutput, cv.width, cv.height);"
            + "gl.bindTexture(gl.TEXTURE_2D, N.tex);"
            + "gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 256, 240, 0, gl.RGBA, gl.UNSIGNED_BYTE, N.bytes);"
            + "gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);")
    static native void present();

    /** Pushes the current CRT settings to the shader. */
    @JSBody(params = { "crt", "mask", "maskType", "bloom", "focus", "scan", "curve", "sat", "bright" },
            script = ""
            + "var N = window.NESW;"
            + "if (!N.gl) return;"
            + "N.gl.uniform1f(N.u.uCrt, crt);"
            + "N.gl.uniform1f(N.u.uMask, mask);"
            + "N.gl.uniform1f(N.u.uMaskType, maskType);"
            + "N.gl.uniform1f(N.u.uBloom, bloom);"
            + "N.gl.uniform1f(N.u.uFocus, focus);"
            + "N.gl.uniform1f(N.u.uScan, scan);"
            + "N.gl.uniform1f(N.u.uCurve, curve);"
            + "N.gl.uniform1f(N.u.uSat, sat);"
            + "N.gl.uniform1f(N.u.uBright, bright);")
    static native void setCrtParams(float crt, float mask, float maskType, float bloom,
            float focus, float scan, float curve, float sat, float bright);
}
