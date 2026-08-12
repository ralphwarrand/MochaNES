package com.mochanes.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * The browser side of the web build: audio, input, ROM loading and page glue.
 *
 * <p>Video lives in {@link Renderer}. Everything here is hand-written
 * JavaScript rather than a typed DOM binding, so the hot paths compile to
 * direct property access, and state lives on a single {@code window.NESW}
 * object rather than leaking into the global namespace.
 */
final class Platform {

    private Platform() {
    }

    /** Called once per animation frame. */
    @JSFunctor
    interface FrameCallback extends JSObject {
        void onFrame();
    }

    /** Returns true if the key belongs to the emulator and should be swallowed. */
    @JSFunctor
    interface KeyCallback extends JSObject {
        boolean onKey(String code, boolean down);
    }

    /**
     * Receives ROM contents as base64.
     *
     * <p>Base64 rather than a typed array keeps the Java/JS boundary to
     * primitives and strings, at the cost of one decode of a small file, once.
     */
    @JSFunctor
    interface RomCallback extends JSObject {
        void onRom(String name, String base64);
    }

    /** A named command from the page: a button press or a settings change. */
    @JSFunctor
    interface CommandCallback extends JSObject {
        void onCommand(String name, String value);
    }

    /**
     * Starts audio output, or resumes it if already started.
     *
     * <p>The context runs at the APU's own 44.1kHz so no resampling is needed. A
     * ring buffer decouples the emulator, which produces samples in bursts as it
     * runs a frame, from the audio thread, which consumes them steadily. On
     * underrun the last sample is held rather than dropping to zero, because a
     * held value is far less audible than the click a hard drop produces.
     *
     * <p>Browsers refuse to start audio without a user gesture, so this is
     * called from the first key press or click, never at load.
     */
    @JSBody(params = {}, script = ""
            + "var N = window.NESW;"
            + "if (N.audio) { N.audio.resume(); return; }"
            + "var AC = window.AudioContext || window.webkitAudioContext;"
            + "if (!AC) return;"
            + "var ac = new AC({ sampleRate: 44100 });"
            + "var size = 8192;"
            + "var ring = new Float32Array(size);"
            + "var w = 0, r = 0, last = 0;"
            + "var node = ac.createScriptProcessor(1024, 0, 1);"
            + "node.onaudioprocess = function(e) {"
            + "  var out = e.outputBuffer.getChannelData(0);"
            + "  var g = N.volume === undefined ? 1.0 : N.volume;"
            + "  for (var i = 0; i < out.length; i++) {"
            + "    if (r !== w) { last = ring[r]; r = (r + 1) % size; }"
            + "    out[i] = last * g;"
            + "  }"
            + "};"
            + "node.connect(ac.destination);"
            + "N.audio = ac;"
            + "N.pending = function() { return (w - r + size) % size; };"
            + "N.push = function(v) {"
            + "  var n = (w + 1) % size;"
            + "  if (n !== r) { ring[w] = v; w = n; }"
            + "};")
    static native void initAudio();

    /** Queues one sample in the range -1..1. Dropped if the ring is full. */
    @JSBody(params = { "value" }, script = "if (window.NESW.push) window.NESW.push(value);")
    static native void pushSample(float value);

    /**
     * How many samples are queued but not yet played: the emulator's only
     * feedback on whether it is running ahead of or behind the audio clock.
     */
    @JSBody(params = {}, script = "return window.NESW.pending ? window.NESW.pending() : 0;")
    static native int queuedSamples();

    @JSBody(params = { "v" }, script = "window.NESW.volume = v;")
    static native void setVolume(float v);

    /** Schedules the next animation frame, which drives the emulation loop. */
    @JSBody(params = { "callback" }, script = "requestAnimationFrame(callback);")
    static native void requestFrame(FrameCallback callback);

    /**
     * Wires up keyboard input.
     *
     * <p>The handler reports whether it consumed the key, so arrows and space
     * still scroll the page when the emulator does not want them.
     */
    @JSBody(params = { "handler" }, script = ""
            + "window.NESW.keyHandler = handler;"
            + "document.addEventListener('keydown', function(e) {"
            + "  if (window.NESW.capturing) return;"
            + "  if (e.repeat) { e.preventDefault(); return; }"
            + "  if (handler(e.code, true)) e.preventDefault();"
            + "});"
            + "document.addEventListener('keyup', function(e) {"
            + "  if (window.NESW.capturing) return;"
            + "  if (handler(e.code, false)) e.preventDefault();"
            + "});")
    static native void initInput(KeyCallback handler);

    /**
     * Reads the first connected gamepad through the Gamepad API.
     *
     * <p>Returns a bitfield in the controller's own order (A, B, Select, Start,
     * Up, Down, Left, Right), or -1 when no pad is present. Standard mapping
     * puts the face buttons at 0-3 and the d-pad at 12-15; the left stick is
     * read too, with a deadzone.
     */
    @JSBody(params = {}, script = ""
            + "if (!navigator.getGamepads) return -1;"
            + "var pads = navigator.getGamepads();"
            + "var p = null;"
            + "for (var i = 0; i < pads.length; i++) if (pads[i]) { p = pads[i]; break; }"
            + "if (!p) return -1;"
            + "var b = p.buttons, a = p.axes, out = 0;"
            + "function down(i) { return b[i] && (b[i].pressed || b[i].value > 0.5); }"
            + "if (down(0) || down(2)) out |= 1;"
            + "if (down(1) || down(3)) out |= 2;"
            + "if (down(8)) out |= 4;"
            + "if (down(9)) out |= 8;"
            + "if (down(12)) out |= 16;"
            + "if (down(13)) out |= 32;"
            + "if (down(14)) out |= 64;"
            + "if (down(15)) out |= 128;"
            + "var dz = 0.4;"
            + "if (a.length > 1) {"
            + "  if (a[1] < -dz) out |= 16;"
            + "  if (a[1] > dz) out |= 32;"
            + "  if (a[0] < -dz) out |= 64;"
            + "  if (a[0] > dz) out |= 128;"
            + "}"
            + "return out;")
    static native int readGamepad();

    /** Wires up ROM loading from the picker, drag-and-drop and the sample button. */
    @JSBody(params = { "handler" }, script = ""
            + "var N = window.NESW;"
            + "function toB64(buf) {"
            + "  var b = new Uint8Array(buf), s = '';"
            + "  for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);"
            + "  return btoa(s);"
            + "}"
            + "function read(file) {"
            + "  var fr = new FileReader();"
            + "  fr.onload = function() { handler(file.name, toB64(fr.result)); };"
            + "  fr.readAsArrayBuffer(file);"
            + "}"
            + "N.loadRom = function(name, b64) { handler(name, b64); };"
            + "N.loadUrl = function(url, name) {"
            + "  fetch(url).then(function(r) { return r.arrayBuffer(); })"
            + "    .then(function(b) { handler(name, toB64(b)); })"
            + "    .catch(function() { if (N.status) N.status('Could not load ' + name); });"
            + "};"
            + "var picker = document.getElementById('rom');"
            + "if (picker) picker.addEventListener('change', function(e) {"
            + "  if (e.target.files[0]) read(e.target.files[0]);"
            + "});"
            + "document.addEventListener('dragover', function(e) { e.preventDefault(); });"
            + "document.addEventListener('drop', function(e) {"
            + "  e.preventDefault();"
            + "  if (e.dataTransfer.files[0]) read(e.dataTransfer.files[0]);"
            + "});"
            + "var demo = document.getElementById('demo');"
            + "if (demo) demo.addEventListener('click', function() { N.loadUrl('nestest.nes', 'nestest.nes'); });")
    static native void initRomLoading(RomCallback handler);

    /**
     * Routes every button and control on the page to one handler.
     *
     * <p>Controls declare themselves with a {@code data-cmd} attribute, so
     * adding one to the page needs no matching change here.
     */
    @JSBody(params = { "handler" }, script = ""
            + "var N = window.NESW;"
            + "N.command = handler;"
            // A named helper rather than an immediately-invoked function: the
            // ahead-of-time compiler re-parses this script and drops the
            // parentheses around an IIFE, leaving an anonymous function
            // statement, which is a syntax error.
            + "function wire(el) {"
            + "  var cmd = el.getAttribute('data-cmd');"
            + "  if (el.tagName === 'INPUT' && (el.type === 'range' || el.type === 'checkbox')) {"
            + "    el.addEventListener('input', function() {"
            + "      handler(cmd, el.type === 'checkbox' ? (el.checked ? '1' : '0') : el.value);"
            + "    });"
            + "  } else if (el.tagName === 'SELECT') {"
            + "    el.addEventListener('change', function() { handler(cmd, el.value); });"
            + "  } else {"
            + "    el.addEventListener('click', function() { handler(cmd, el.getAttribute('data-value') || ''); });"
            + "  }"
            + "}"
            + "var nodes = document.querySelectorAll('[data-cmd]');"
            + "for (var i = 0; i < nodes.length; i++) wire(nodes[i]);")
    static native void initCommands(CommandCallback handler);

    /**
     * Captures the next key press for rebinding.
     *
     * <p>Sets a flag the normal key listener checks, so the key being bound is
     * not also delivered to the emulator.
     */
    @JSBody(params = { "label" }, script = ""
            + "var N = window.NESW;"
            + "N.capturing = true;"
            + "if (N.status) N.status('Press a key for ' + label + ', or Escape to cancel');"
            + "var once = function(e) {"
            + "  e.preventDefault();"
            + "  document.removeEventListener('keydown', once, true);"
            + "  N.capturing = false;"
            + "  if (e.code !== 'Escape' && N.command) N.command('bound', e.code);"
            + "  else if (N.status) N.status('Cancelled');"
            + "};"
            + "document.addEventListener('keydown', once, true);")
    static native void captureKey(String label);

    /** Toggles fullscreen on the element wrapping the screen. */
    @JSBody(params = {}, script = ""
            + "var el = document.getElementById('stage') || document.documentElement;"
            + "if (document.fullscreenElement) document.exitFullscreen();"
            + "else if (el.requestFullscreen) el.requestFullscreen();")
    static native void toggleFullscreen();

    /** Applies an aspect and scale choice to the canvas. */
    @JSBody(params = { "aspect", "scale" }, script = ""
            + "var c = document.getElementById('screen');"
            + "if (!c) return;"
            + "if (scale > 0) {"
            + "  var w = 256 * scale;"
            + "  c.style.width = w + 'px';"
            + "  c.style.height = (aspect === 'pixel' ? 240 * scale : Math.round(w * 3 / 4)) + 'px';"
            + "} else {"
            + "  c.style.width = 'min(92vw, 768px)';"
            + "  c.style.height = 'auto';"
            + "  c.style.aspectRatio = aspect === 'pixel' ? '256 / 240' : (aspect === 'stretch' ? 'auto' : '4 / 3');"
            + "}")
    static native void setDisplayMode(String aspect, int scale);

    /** Replaces the status line under the screen. */
    @JSBody(params = { "text" }, script = ""
            + "var N = window.NESW;"
            + "N.status = N.status || function(t) {"
            + "  var el = document.getElementById('status');"
            + "  if (el) el.textContent = t;"
            + "};"
            + "N.status(text);")
    static native void setStatus(String text);

    /** Writes text into any element by id, used for the readouts. */
    @JSBody(params = { "id", "text" }, script = ""
            + "var el = document.getElementById(id);"
            + "if (el) el.textContent = text;")
    static native void setText(String id, String text);

    /** Reads and writes small settings that should survive a reload. */
    @JSBody(params = { "key", "value" }, script = ""
            + "try { localStorage.setItem('mochanes.' + key, value); } catch (e) {}")
    static native void store(String key, String value);

    @JSBody(params = { "key" }, script = ""
            + "try { var v = localStorage.getItem('mochanes.' + key); return v === null ? '' : v; }"
            + "catch (e) { return ''; }")
    static native String load(String key);

    /** Reflects a value back into a control, so loaded settings show correctly. */
    @JSBody(params = { "id", "value" }, script = ""
            + "var el = document.getElementById(id);"
            + "if (!el) return;"
            + "if (el.type === 'checkbox') el.checked = value === '1';"
            + "else el.value = value;")
    static native void setControl(String id, String value);

    /** Exposes a frame-stepper, used by the debugger and the test harness. */
    @JSBody(params = { "callback" }, script = ""
            + "window.NESW.stepFrames = function(n) { for (var i = 0; i < n; i++) callback(); };")
    static native void exposeStepper(FrameCallback callback);

    /**
     * Loads a ROM named in the query string, as in {@code ?rom=nestest.nes}.
     *
     * <p>Only same-origin paths are honoured - anything with a scheme or a
     * protocol-relative prefix is refused - so a crafted link cannot point the
     * page at another host.
     */
    @JSBody(params = {}, script = ""
            + "var q = new URLSearchParams(location.search).get('rom');"
            + "if (!q) return false;"
            + "if (q.indexOf(':') >= 0 || q.indexOf('//') === 0) return false;"
            + "window.NESW.loadUrl(q, q);"
            + "return true;")
    static native boolean loadRomFromQuery();
}
