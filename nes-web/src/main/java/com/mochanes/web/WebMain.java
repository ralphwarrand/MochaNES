package com.mochanes.web;

import com.mochanes.emulator.AudioSink;
import com.mochanes.emulator.Controller;
import com.mochanes.emulator.Disassembler;
import com.mochanes.emulator.FrameSink;
import com.mochanes.emulator.NES;

/**
 * Browser front-end.
 *
 * <p>The emulation core is used unmodified; this supplies the two sinks it asks
 * for and drives it from requestAnimationFrame.
 *
 * <p><b>Pacing.</b> On the desktop the emulator paces itself by blocking on the
 * audio device. Nothing blocks in a browser, so the animation frame is the
 * clock: each callback runs whole NES frames until one has been drawn. Because
 * a display may not be 60Hz, the loop also watches how much audio is queued and
 * runs an extra frame when it is falling behind - bounded, so a long stall
 * cannot become an unbounded catch-up burst.
 */
public final class WebMain {

    private static final int WIDTH = 256;
    private static final int HEIGHT = 240;

    /** Samples the audio ring should stay ahead by: roughly two frames' worth. */
    private static final int TARGET_QUEUE = 1500;

    /** Never run more than this many NES frames for one animation frame. */
    private static final int MAX_CATCHUP_FRAMES = 3;

    /** Button order used by {@link Controller}: A, B, Select, Start, U, D, L, R. */
    private static final String[] BUTTON_NAMES =
            { "A", "B", "Select", "Start", "Up", "Down", "Left", "Right" };

    private static final String[] DEFAULT_KEYS =
            { "KeyZ", "KeyX", "ShiftRight", "Enter", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight" };

    private static final String[] keys = DEFAULT_KEYS.clone();

    private static NES nes;
    private static NES savedState;
    private static Disassembler disassembler;

    private static final Video video = new Video();
    private static final Audio audio = new Audio();

    private static boolean romLoaded;
    private static boolean audioStarted;
    private static boolean paused;
    private static boolean crtAvailable;
    private static int bindingButton = -1;

    private static int framesThisSecond;
    private static long fpsWindowStart;
    private static int debugAddress = 0x0000;

    /* CRT settings, mirrored by the page controls. */
    private static boolean crtOn;
    private static float mask = 0.55f;
    private static float maskType;
    private static float bloom = 0.5f;
    private static float focus = 0.6f;
    private static float scan = 0.8f;
    private static float curve;
    private static float saturation = 1.0f;
    private static float brightness = 1.05f;

    /** Writes straight into the shared frame buffer; no intermediate array. */
    private static final class Video implements FrameSink {
        boolean complete;

        @Override
        public void setPixel(int x, int y, int color) {
            if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) {
                Renderer.setPixel(y * WIDTH + x, color);
            }
        }

        @Override
        public void refresh() {
            complete = true;
        }
    }

    /** Converts the APU's 16-bit little-endian output into floats and queues it. */
    private static final class Audio implements AudioSink {
        @Override
        public void write(byte[] samples, int offset, int length) {
            for (int i = offset; i + 1 < offset + length; i += 2) {
                int lo = samples[i] & 0xFF;
                int hi = samples[i + 1];          // signed: carries the sign bit
                short s = (short) ((hi << 8) | lo);
                Platform.pushSample(s / 32768.0f);
            }
        }

        @Override
        public void close() {
            // The audio context outlives the emulator; nothing to release.
        }
    }

    public static void main(String[] args) {
        crtAvailable = Renderer.init("screen");
        Platform.initInput(WebMain::onKey);
        Platform.initRomLoading(WebMain::onRom);
        Platform.initCommands(WebMain::onCommand);
        Platform.exposeStepper(WebMain::stepOneFrame);

        restoreSettings();
        applyCrt();
        Platform.setDisplayMode("43", 0);

        if (!crtAvailable) {
            Platform.setText("crtNote", "WebGL unavailable - CRT simulation disabled.");
        }
        if (!Platform.loadRomFromQuery()) {
            Platform.setStatus("Choose a ROM, or drop one on the page.");
        }
        Platform.requestFrame(WebMain::tick);
    }

    // ---------------------------------------------------------------- loading

    private static void onRom(String name, String base64) {
        try {
            byte[] rom = Base64.decode(base64);
            NES machine = new NES(video, audio);
            machine.loadROM(rom);
            machine.reset();
            nes = machine;
            disassembler = new Disassembler(machine);
            savedState = null;
            romLoaded = true;
            paused = false;
            Platform.setStatus(name + " - mapper " + machine.getMemory().getMapperID());
        } catch (Exception e) {
            romLoaded = false;
            Platform.setStatus("Could not load " + name + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ input

    private static boolean onKey(String code, boolean down) {
        if (!audioStarted && down) {
            // The first key press is the user gesture browsers require.
            Platform.initAudio();
            audioStarted = true;
        }
        if (nes == null) {
            return false;
        }
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(code)) {
                Controller pad = nes.getController();
                if (pad != null) {
                    pad.setButtonPressed(i, down);
                }
                return true;
            }
        }
        return false;
    }

    /** Merges gamepad state in, so a pad and the keyboard can be used together. */
    private static void pollGamepad() {
        int state = Platform.readGamepad();
        if (state < 0 || nes == null) {
            return;
        }
        Controller pad = nes.getController();
        if (pad == null) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            boolean pressed = (state & (1 << i)) != 0;
            if (pressed || !keys[i].isEmpty()) {
                // Only assert; releases still come from the key handler, so a
                // held key is not cancelled by an idle pad.
                if (pressed) {
                    pad.setButtonPressed(i, true);
                }
            }
        }
    }

    // --------------------------------------------------------------- commands

    private static void onCommand(String name, String value) {
        if (!audioStarted) {
            Platform.initAudio();
            audioStarted = true;
        }
        switch (name) {
            case "crt" -> { crtOn = "1".equals(value); applyCrt(); save("crt", crtOn ? "1" : "0"); }
            case "mask" -> { mask = num(value); applyCrt(); save("mask", value); }
            case "maskType" -> { maskType = num(value); applyCrt(); save("maskType", value); }
            case "bloom" -> { bloom = num(value); applyCrt(); save("bloom", value); }
            case "focus" -> { focus = num(value); applyCrt(); save("focus", value); }
            case "scan" -> { scan = num(value); applyCrt(); save("scan", value); }
            case "curve" -> { curve = num(value); applyCrt(); save("curve", value); }
            case "sat" -> { saturation = num(value); applyCrt(); save("sat", value); }
            case "bright" -> { brightness = num(value); applyCrt(); save("bright", value); }
            case "preset" -> applyPreset(value);
            case "volume" -> { Platform.setVolume(num(value)); save("volume", value); }
            case "aspect" -> { Platform.setDisplayMode(value, 0); save("aspect", value); }
            case "scale" -> Platform.setDisplayMode(loadOr("aspect", "43"), (int) num(value));
            case "fullscreen" -> Platform.toggleFullscreen();
            case "pause" -> { paused = !paused; Platform.setText("pauseLabel", paused ? "Resume" : "Pause"); }
            case "reset" -> { if (nes != null) { nes.reset(); Platform.setStatus("Reset"); } }
            case "saveState" -> saveState();
            case "loadState" -> loadState();
            case "step" -> { if (nes != null) { paused = true; stepOneFrame(); Renderer.present(); updateDebug(); } }
            case "bind" -> startBinding(value);
            case "bound" -> finishBinding(value);
            case "gotoAddr" -> { debugAddress = parseHex(value); updateDebug(); }
            default -> { }
        }
    }

    private static void startBinding(String indexText) {
        bindingButton = (int) num(indexText);
        if (bindingButton >= 0 && bindingButton < BUTTON_NAMES.length) {
            Platform.captureKey(BUTTON_NAMES[bindingButton]);
        }
    }

    private static void finishBinding(String code) {
        if (bindingButton < 0 || bindingButton >= keys.length) {
            return;
        }
        // A key already in use is released from its old button, so two buttons
        // can never fight over one key.
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(code)) {
                keys[i] = "";
                Platform.setText("key" + i, "-");
            }
        }
        keys[bindingButton] = code;
        Platform.setText("key" + bindingButton, pretty(code));
        save("keys", String.join(",", keys));
        Platform.setStatus(BUTTON_NAMES[bindingButton] + " bound to " + pretty(code));
        bindingButton = -1;
    }

    private static void applyPreset(String preset) {
        switch (preset) {
            case "trinitron" -> set(0.55f, 0f, 0.5f, 0.6f, 0.8f, 1.0f, 1.05f);
            case "consumer" -> set(0.7f, 1f, 0.75f, 0.35f, 0.9f, 1.1f, 1.15f);
            case "arcade" -> set(0.8f, 2f, 0.9f, 0.5f, 1.0f, 1.15f, 1.2f);
            case "sharp" -> set(0.25f, 0f, 0.2f, 0.95f, 0.55f, 1.0f, 1.0f);
            default -> { return; }
        }
        crtOn = true;
        Platform.setControl("crt", "1");
        Platform.setControl("mask", Float.toString(mask));
        Platform.setControl("bloom", Float.toString(bloom));
        Platform.setControl("focus", Float.toString(focus));
        Platform.setControl("scan", Float.toString(scan));
        Platform.setControl("maskType", Float.toString(maskType));
        applyCrt();
    }

    private static void set(float m, float mt, float bl, float fo, float sc, float sa, float br) {
        mask = m;
        maskType = mt;
        bloom = bl;
        focus = fo;
        scan = sc;
        saturation = sa;
        brightness = br;
    }

    private static void applyCrt() {
        Renderer.setCrtParams(crtOn && crtAvailable ? 1f : 0f, mask, maskType,
                bloom, focus, scan, curve, saturation, brightness);
    }

    // ------------------------------------------------------------ save states

    /**
     * Save states are a clone of the machine rather than a serialised blob:
     * the core already supports fast cloning, so this costs microseconds and
     * needs no format.
     */
    private static void saveState() {
        if (nes == null) {
            return;
        }
        savedState = nes.copy(video);
        Platform.setStatus("State saved");
    }

    private static void loadState() {
        if (nes == null || savedState == null) {
            Platform.setStatus("No saved state");
            return;
        }
        nes.loadState(savedState);
        Platform.setStatus("State loaded");
    }

    // ------------------------------------------------------------- main loop

    private static void tick() {
        if (romLoaded && nes != null && !paused) {
            pollGamepad();
            int budget = MAX_CATCHUP_FRAMES;
            do {
                stepOneFrame();
                budget--;
                // Keep going only while the audio queue is short, meaning the
                // emulator is behind the sound card rather than ahead of it.
            } while (budget > 0 && audioStarted && Platform.queuedSamples() < TARGET_QUEUE);

            Renderer.present();
            countFrame();
        }
        Platform.requestFrame(WebMain::tick);
    }

    private static void stepOneFrame() {
        if (nes == null) {
            return;
        }
        video.complete = false;
        // A frame is ~29,780 CPU cycles. The bound is a safety net: a ROM that
        // disables rendering never raises the flag, and without it the tab would
        // hang rather than simply showing nothing.
        int guard = 200000;
        while (!video.complete && guard-- > 0) {
            nes.stepInstruction();
        }
    }

    private static void countFrame() {
        framesThisSecond++;
        long now = System.currentTimeMillis();
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
        } else if (now - fpsWindowStart >= 1000) {
            Platform.setText("fps", framesThisSecond + " fps");
            framesThisSecond = 0;
            fpsWindowStart = now;
            updateDebug();
        }
    }

    // -------------------------------------------------------------- debugger

    private static void updateDebug() {
        if (nes == null || nes.getCpu() == null) {
            return;
        }
        var cpu = nes.getCpu();
        // Register bitfield order is 0:X, 1:Y, 2:A, 3:SP.
        String regs = "PC " + hex(cpu.getPC(), 4)
                + "   A " + hex(cpu.getReg(2), 2)
                + "   X " + hex(cpu.getReg(0), 2)
                + "   Y " + hex(cpu.getReg(1), 2)
                + "   SP " + hex(cpu.getSP(), 2)
                + "   P " + hex(cpu.getFlags(), 2) + " [" + flags(cpu.getFlags()) + "]";
        Platform.setText("regs", regs);

        StringBuilder code = new StringBuilder();
        int addr = cpu.getPC();
        for (int i = 0; i < 12 && disassembler != null; i++) {
            code.append(disassembler.disassemble(addr)).append('\n');
            addr = (addr + Math.max(1, disassembler.getInstructionLength(addr))) & 0xFFFF;
        }
        Platform.setText("disasm", code.toString());

        StringBuilder dump = new StringBuilder();
        for (int row = 0; row < 12; row++) {
            int base = (debugAddress + row * 16) & 0xFFFF;
            dump.append(hex(base, 4)).append("  ");
            for (int i = 0; i < 16; i++) {
                dump.append(hex(nes.getMemory().peek((base + i) & 0xFFFF) & 0xFF, 2)).append(' ');
            }
            dump.append('\n');
        }
        Platform.setText("memory", dump.toString());
    }

    private static String flags(int p) {
        String names = "NV-BDIZC";
        StringBuilder sb = new StringBuilder();
        for (int bit = 7; bit >= 0; bit--) {
            char c = names.charAt(7 - bit);
            sb.append((p & (1 << bit)) != 0 ? c : '.');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- settings

    private static void restoreSettings() {
        crtOn = "1".equals(loadOr("crt", "0"));
        mask = numOr(loadOr("mask", ""), mask);
        maskType = numOr(loadOr("maskType", ""), maskType);
        bloom = numOr(loadOr("bloom", ""), bloom);
        focus = numOr(loadOr("focus", ""), focus);
        scan = numOr(loadOr("scan", ""), scan);
        curve = numOr(loadOr("curve", ""), curve);

        String saved = loadOr("keys", "");
        if (!saved.isEmpty()) {
            String[] parts = saved.split(",", -1);
            for (int i = 0; i < keys.length && i < parts.length; i++) {
                keys[i] = parts[i];
            }
        }
        for (int i = 0; i < keys.length; i++) {
            Platform.setText("key" + i, keys[i].isEmpty() ? "-" : pretty(keys[i]));
        }

        Platform.setControl("crt", crtOn ? "1" : "0");
        Platform.setControl("mask", Float.toString(mask));
        Platform.setControl("bloom", Float.toString(bloom));
        Platform.setControl("focus", Float.toString(focus));
        Platform.setControl("scan", Float.toString(scan));
        Platform.setControl("curve", Float.toString(curve));
    }

    private static void save(String key, String value) {
        Platform.store(key, value);
    }

    private static String loadOr(String key, String fallback) {
        String v = Platform.load(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    // --------------------------------------------------------------- helpers

    private static float num(String s) {
        return numOr(s, 0f);
    }

    private static float numOr(String s, float fallback) {
        try {
            return s == null || s.isEmpty() ? fallback : Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseHex(String s) {
        try {
            return Integer.parseInt(s.trim().replace("$", "").replace("0x", ""), 16) & 0xFFFF;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String hex(int v, int digits) {
        String s = Integer.toHexString(v).toUpperCase();
        while (s.length() < digits) {
            s = "0" + s;
        }
        return s;
    }

    /** Turns a KeyboardEvent code into something worth showing a person. */
    private static String pretty(String code) {
        if (code.startsWith("Key")) {
            return code.substring(3);
        }
        if (code.startsWith("Arrow")) {
            return code.substring(5);
        }
        if (code.startsWith("Digit")) {
            return code.substring(5);
        }
        return code;
    }
}
