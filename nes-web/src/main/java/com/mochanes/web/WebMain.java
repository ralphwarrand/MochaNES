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

    /**
     * One NES frame in milliseconds. The console runs at 60.0988Hz, not 60, and
     * no display runs at exactly either.
     */
    private static final double FRAME_MS = 1000.0 / 60.0988;

    /** Ignore gaps longer than this: the tab was hidden, so do not try to catch up. */
    private static final double MAX_GAP_MS = 250.0;

    private static double lastTick;
    private static double accumulator;

    /**
     * Rolling estimate of the display's refresh interval, in milliseconds.
     *
     * <p>Measured rather than assumed: {@code screen.refreshRate} does not
     * exist on the web, and the value decides whether the loop can lock to the
     * display or has to keep accumulating.
     */
    private static double refreshEstimate;
    private static int refreshSamples;

    /**
     * True when one NES frame per animation frame is close enough to real time.
     *
     * <p>A 60Hz display refreshes every 16.667ms and the console wants
     * 16.639ms. Accumulating that 0.027ms difference crosses a whole frame
     * about every ten seconds, and the loop then runs two frames for one
     * refresh - one of which is never seen. Locking one to one trades a 0.16%
     * rate error, which the audio correction already absorbs, for not dropping
     * a frame periodically. Off a matching display - 144Hz, or a tab being
     * throttled - the accumulator is still the right answer, so this stays
     * false and nothing changes.
     */
    private static boolean lockedToDisplay;

    /** Button order used by {@link Controller}: A, B, Select, Start, U, D, L, R. */
    private static final String[] BUTTON_NAMES =
            { "A", "B", "Select", "Start", "Up", "Down", "Left", "Right" };

    private static final String[] DEFAULT_KEYS =
            { "KeyZ", "KeyX", "ShiftRight", "Enter", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight" };

    private static final String[] keys = DEFAULT_KEYS.clone();

    /**
     * Pad bindings in the same token form the desktop build uses: {@code b<n>}
     * for pad button n, {@code a<n>-} / {@code a<n>+} for an axis pushed past
     * the deadzone, several alternatives separated by {@code |}, empty for
     * unbound.
     *
     * <p>The defaults are the standard-mapping layout this used to hardcode,
     * alternatives included: either face button of a pair for A and B, and the
     * d-pad *and* the left stick for the directions, so a stick works out of
     * the box without anyone rebinding anything.
     */
    private static final String[] DEFAULT_PAD = {
            "b0|b2", "b1|b3", "b8", "b9",
            "b12|a1-", "b13|a1+", "b14|a0-", "b15|a0+"
    };

    private static final String[] padBindings = DEFAULT_PAD.clone();

    /** Button index being rebound from the pad, or -1. */
    private static int padBindingButton = -1;
    /** Controls already held when a pad capture started; ignored until released. */
    private static int padCaptureIgnore;

    // Each input source keeps its own held-button mask and the three are OR'd
    // together. Merging at the source rather than writing straight through to
    // the Controller is what lets a pad and the keyboard be used at the same
    // time without one cancelling the other's releases.
    private static int keyState;
    private static int padState;
    private static int touchState;

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

    /** Rows in the memory dump. Enough to be worth scrolling through. */
    private static final int MEM_ROWS = 48;

    /** Instructions shown in the disassembly. */
    private static final int DISASM_LINES = 24;

    /** When true, the memory view follows the program counter. */
    private static boolean memFollowsPc;

    /** Display choices, mirrored so either can be changed without losing the other. */
    private static String aspect = "43";
    private static int scale;

    /* CRT settings, mirrored by the page controls. */
    private static boolean crtOn;
    private static float mask = 0.55f;
    private static float maskType;
    private static float bloom = 0.3f;
    private static float focus = 0.6f;
    private static float scan = 0.8f;
    private static float curve;
    private static float saturation = 1.0f;
    private static float brightness = 1.05f;

    /**
     * The frame the PPU draws into.
     *
     * <p>Given to the PPU as its fast buffer, so drawing a pixel is a plain
     * array store rather than an interface call that ends in a JavaScript one.
     * {@link Renderer#uploadFrame} then hands the whole thing over once.
     */
    private static final int[] frame = new int[WIDTH * HEIGHT];

    /**
     * Pixels hidden at each edge, as a television's bezel did.
     *
     * <p>The NES picks a palette per 16x16 block but scrolls a pixel at a time,
     * so a column revealed by scrolling can show the previous block's colours
     * until the attribute catches up. Games blank the leftmost 8 pixels to hide
     * part of that; the block is 16 wide, so the rest can still show.
     */
    private static int overscanX;
    private static int overscanY;

    /**
     * Kept for the frame-complete signal, and as the path used if the PPU ever
     * runs without a fast buffer attached.
     */
    private static final class Video implements FrameSink {
        boolean complete;

        @Override
        public void setPixel(int x, int y, int color) {
            if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) {
                frame[y * WIDTH + x] = color;
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
        Platform.exposeStepper(WebMain::stepFrameAndUpload);
        Platform.initTouch(WebMain::onCommand);
        Platform.initFocusLoss(WebMain::onCommand);

        restoreSettings();
        applyCrt();
        applyDisplay();

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
            // Draw straight into the shared frame. loadState copies in place and
            // leaves the buffer attached, so this only has to happen per ROM.
            machine.getPpu().setFastRendering(frame);
            nes = machine;
            disassembler = new Disassembler(machine);
            savedState = null;
            romLoaded = true;
            paused = false;
            lastTick = 0;
            accumulator = 0;
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
            if (!keys[i].isEmpty() && keys[i].equals(code)) {
                keyState = down ? (keyState | (1 << i)) : (keyState & ~(1 << i));
                applyInput();
                return true;
            }
        }
        return false;
    }

    /** Merges gamepad state in, so a pad and the keyboard can be used together. */
    private static void pollGamepad() {
        int buttons = Platform.readPadButtons();
        int axes = Platform.readPadAxes();
        if (buttons < 0) {
            // No pad connected: clear the mask rather than leave it held.
            padState = 0;
            applyInput();
            return;
        }
        if (axes < 0) {
            axes = 0;
        }

        if (padBindingButton >= 0) {
            // Nothing on the pad drives the game while a control is being
            // chosen, or the press that binds it also plays.
            padState = 0;
            applyInput();
            capturePadControl(buttons, axes);
            return;
        }

        int state = 0;
        for (int i = 0; i < padBindings.length; i++) {
            if (isPadBindingDown(padBindings[i], buttons, axes)) {
                state |= 1 << i;
            }
        }
        padState = state;
        applyInput();
    }

    /** Whether any control listed in a binding is currently held. */
    private static boolean isPadBindingDown(String binding, int buttons, int axes) {
        if (binding == null || binding.isEmpty()) {
            return false;
        }
        for (String token : binding.split("\\|")) {
            if (isPadControlDown(token, buttons, axes)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the control named by a single token is currently held. */
    private static boolean isPadControlDown(String token, int buttons, int axes) {
        if (token == null || token.length() < 2) {
            return false;
        }
        char kind = token.charAt(0);
        if (kind == 'b') {
            int index = padIndex(token.substring(1));
            return index >= 0 && index < 31 && (buttons & (1 << index)) != 0;
        }
        if (kind == 'a') {
            int index = padIndex(token.substring(1, token.length() - 1));
            if (index < 0 || index >= 16) {
                return false;
            }
            int bit = 2 * index + (token.charAt(token.length() - 1) == '+' ? 1 : 0);
            return (axes & (1 << bit)) != 0;
        }
        return false;
    }

    /** Parses the numeric part of a binding token, or -1 if it is malformed. */
    private static int padIndex(String text) {
        if (text.isEmpty()) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /**
     * Takes the first pad control pressed after a rebind starts.
     *
     * <p>Buttons already held when the capture began are ignored until they are
     * released, so clicking "bind" with a trigger held does not bind the
     * trigger straight away.
     */
    private static void capturePadControl(int buttons, int axes) {
        for (int i = 0; i < 31; i++) {
            if ((buttons & (1 << i)) != 0 && (padCaptureIgnore & (1 << i)) == 0) {
                finishPadBinding("b" + i);
                return;
            }
        }
        for (int i = 0; i < 16; i++) {
            for (int dir = 0; dir < 2; dir++) {
                if ((axes & (1 << (2 * i + dir))) != 0) {
                    finishPadBinding("a" + i + (dir == 1 ? "+" : "-"));
                    return;
                }
            }
        }
        // Buttons that have since been let go stop being ignored.
        padCaptureIgnore &= buttons;
    }

    private static void finishPadBinding(String token) {
        // One control drives one button, so take it off any binding that lists
        // it, leaving that binding's other alternatives in place.
        for (int i = 0; i < padBindings.length; i++) {
            StringBuilder kept = new StringBuilder();
            for (String alt : padBindings[i].split("\\|")) {
                if (!alt.isEmpty() && !alt.equals(token)) {
                    kept.append(kept.length() == 0 ? "" : "|").append(alt);
                }
            }
            padBindings[i] = kept.toString();
        }
        padBindings[padBindingButton] = token;
        save("pad", String.join(",", padBindings));
        refreshBindingLabels();
        Platform.setStatus(BUTTON_NAMES[padBindingButton] + " bound to pad " + token);
        padBindingButton = -1;
        padCaptureIgnore = 0;
    }

    /**
     * Drives the Controller from the union of keyboard, gamepad and touch.
     *
     * <p>Every button is written every time, presses and releases alike. An
     * earlier version had the pad only ever assert, never release, to stop an
     * idle pad cancelling a held key - which left any button pressed on a pad
     * stuck down for good, since nothing else would clear it.
     */
    /** Releases everything currently held, for when the page loses focus. */
    private static void clearInput() {
        keyState = 0;
        padState = 0;
        touchState = 0;
        applyInput();
    }

    private static void applyInput() {
        if (nes == null) {
            return;
        }
        Controller pad = nes.getController();
        if (pad == null) {
            return;
        }
        int state = keyState | padState | touchState;
        for (int i = 0; i < 8; i++) {
            pad.setButtonPressed(i, (state & (1 << i)) != 0);
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
            case "aspect" -> { aspect = value; applyDisplay(); save("aspect", value); }
            case "scale" -> { scale = (int) num(value); applyDisplay(); save("scale", value); }
            case "fullscreen" -> Platform.toggleFullscreen();
            case "pause" -> { paused = !paused; Platform.setText("pauseLabel", paused ? "Resume" : "Pause"); }
            case "reset" -> { if (nes != null) { nes.reset(); Platform.setStatus("Reset"); } }
            case "saveState" -> saveState();
            case "loadState" -> loadState();
            case "step" -> { if (nes != null) { paused = true; stepFrameAndUpload(); Renderer.present(); updateDebug(); } }
            case "touchDown" -> setButton((int) num(value), true);
            case "touchUp" -> setButton((int) num(value), false);
            case "inputLost" -> clearInput();
            case "bind" -> startBinding(value);
            case "bound" -> finishBinding(value);
            case "bindPad" -> startPadBinding(value);
            case "resetInput" -> resetBindings();
            case "overscan" -> { applyOverscan(value); save("overscan", value); }
            case "gotoAddr" -> { debugAddress = parseHex(value); memFollowsPc = false; updateDebug(); }
            case "memPrev" -> { debugAddress = (debugAddress - MEM_ROWS * 16) & 0xFFFF; memFollowsPc = false; updateDebug(); }
            case "memNext" -> { debugAddress = (debugAddress + MEM_ROWS * 16) & 0xFFFF; memFollowsPc = false; updateDebug(); }
            case "memPc" -> { memFollowsPc = !memFollowsPc; updateDebug(); }
            default -> { }
        }
    }

    /** "none", "sides" or "tv"; anything else is treated as off. */
    private static void applyOverscan(String mode) {
        overscanX = ("sides".equals(mode) || "tv".equals(mode)) ? 8 : 0;
        overscanY = "tv".equals(mode) ? 8 : 0;
        Renderer.setSourceSize(WIDTH - 2 * overscanX, HEIGHT - 2 * overscanY);
        applyDisplay();
    }

    private static void applyDisplay() {
        Platform.setDisplayMode(aspect, scale);
    }

    /** Presses or releases a controller button, used by the on-screen pad. */
    private static void setButton(int button, boolean down) {
        if (button < 0 || button > 7) {
            return;
        }
        touchState = down ? (touchState | (1 << button)) : (touchState & ~(1 << button));
        applyInput();
    }

    /** Begins waiting for a pad control to bind to the given NES button. */
    private static void startPadBinding(String indexText) {
        int button = (int) num(indexText);
        if (button < 0 || button >= padBindings.length) {
            return;
        }
        int held = Platform.readPadButtons();
        if (held < 0) {
            Platform.setStatus("No gamepad detected");
            return;
        }
        padBindingButton = button;
        padCaptureIgnore = held;
        Platform.setText("pad" + button, "press...");
        Platform.setStatus("Press a control on the pad for " + BUTTON_NAMES[button]);
    }

    /** Puts both keyboard and pad back to the built-in layout. */
    private static void resetBindings() {
        System.arraycopy(DEFAULT_KEYS, 0, keys, 0, DEFAULT_KEYS.length);
        System.arraycopy(DEFAULT_PAD, 0, padBindings, 0, DEFAULT_PAD.length);
        padBindingButton = -1;
        bindingButton = -1;
        save("keys", String.join(",", keys));
        save("pad", String.join(",", padBindings));
        refreshBindingLabels();
        Platform.setStatus("Controls reset to defaults");
    }

    private static void refreshBindingLabels() {
        for (int i = 0; i < keys.length; i++) {
            Platform.setText("key" + i, keys[i].isEmpty() ? "-" : pretty(keys[i]));
            Platform.setText("pad" + i, padBindings[i].isEmpty() ? "-" : padBindings[i]);
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
            case "trinitron" -> set(0.55f, 0f, 0.3f, 0.6f, 0.8f, 1.0f, 1.05f);
            case "consumer" -> set(0.7f, 1f, 0.45f, 0.35f, 0.9f, 1.1f, 1.15f);
            case "arcade" -> set(0.8f, 2f, 0.55f, 0.5f, 1.0f, 1.15f, 1.2f);
            case "sharp" -> set(0.25f, 0f, 0.12f, 0.95f, 0.55f, 1.0f, 1.0f);
            default -> { return; }
        }
        crtOn = true;
        Platform.setControl("crt", "1");
        Platform.setControl("mask", Float.toString(mask));
        Platform.setControl("bloom", Float.toString(bloom));
        Platform.setControl("focus", Float.toString(focus));
        Platform.setControl("scan", Float.toString(scan));
        Platform.setControl("maskType", Float.toString(maskType));
        // A preset is a whole television, and a television never showed the
        // outermost pixels. Still a separate control, so it can be changed
        // back afterwards.
        applyOverscan("tv");
        Platform.setControl("overscan", "tv");
        save("overscan", "tv");
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

    /**
     * Runs the machine in real time, independent of the display's refresh rate.
     *
     * <p>The obvious loop - one NES frame per animation frame - is wrong on any
     * display that is not 60Hz. At 144Hz it runs the console at nearly two and a
     * half times speed, and the only thing pushing back is the audio queue
     * filling up, which produces exactly the uneven, choppy motion that pacing
     * bug is known for. So time is accumulated instead, and frames are run to
     * consume it.
     *
     * <p>The audio queue is still consulted, but only as a slow correction: the
     * sound card's clock and the system clock drift apart, and over minutes that
     * drift is what empties or overflows the buffer.
     */
    private static void tick() {
        if (romLoaded && nes != null && !paused) {
            double now = Platform.now();
            double elapsed = lastTick == 0 ? FRAME_MS : now - lastTick;
            lastTick = now;

            if (elapsed > MAX_GAP_MS) {
                // Hidden tab, or a long stall. Resume rather than sprint.
                elapsed = FRAME_MS;
                accumulator = 0;
                refreshSamples = 0;
                lockedToDisplay = false;
            }
            trackRefreshRate(elapsed);
            accumulator += elapsed;

            if (audioStarted) {
                // Nudge, do not jump: a hard correction is audible.
                int queued = Platform.queuedSamples();
                if (queued > TARGET_QUEUE * 2) {
                    accumulator -= FRAME_MS * 0.05;      // ahead of the sound card
                } else if (queued < TARGET_QUEUE / 3) {
                    accumulator += FRAME_MS * 0.05;      // behind it
                }
            }

            pollGamepad();

            int ran = 0;
            if (lockedToDisplay) {
                // One frame per refresh, in step with the display.
                stepOneFrame();
                ran = 1;
                accumulator = 0;
            } else {
                while (accumulator >= FRAME_MS && ran < MAX_CATCHUP_FRAMES) {
                    stepOneFrame();
                    accumulator -= FRAME_MS;
                    ran++;
                }
            }
            if (accumulator > FRAME_MS * MAX_CATCHUP_FRAMES) {
                // Too far behind to catch up. Drop the debt instead of running
                // fast for the next second trying to repay it.
                accumulator = 0;
            }

            // Presenting only when something changed saves the whole shader pass
            // on displays that refresh faster than the console.
            if (ran > 0) {
                Renderer.uploadFrame(frame, overscanX, overscanY);
                Renderer.present();
                countFrame();
                if (Platform.isDebugOpen()) {
                    updateDebug();
                }
            }
        }
        Platform.requestFrame(WebMain::tick);
    }

    /**
     * Follows the animation-frame interval and decides whether to lock to it.
     *
     * <p>The estimate is a slow average so one late frame does not flip the
     * mode, and locking needs the display to be within 1% of the console's
     * rate - near enough that a viewer cannot see the difference, but far
     * enough out that 50Hz, 75Hz and 144Hz stay on the accumulator.
     */
    private static void trackRefreshRate(double elapsed) {
        if (elapsed <= 1.0 || elapsed > 100.0) {
            return; // nonsense sample: a stall, or the first tick
        }
        refreshEstimate = refreshSamples == 0
                ? elapsed
                : refreshEstimate + (elapsed - refreshEstimate) * 0.05;
        if (refreshSamples < 240) {
            refreshSamples++;
            return;
        }
        boolean close = Math.abs(refreshEstimate - FRAME_MS) / FRAME_MS < 0.01;
        if (close != lockedToDisplay) {
            lockedToDisplay = close;
            accumulator = 0;
        }
    }

    /**
     * Runs a frame and hands it to the renderer.
     *
     * <p>What the debugger's step button and the test harness want: they drive
     * frames one at a time and then look at the picture, so the frame has to
     * reach the shared buffer. The main loop keeps calling {@link #stepOneFrame}
     * directly, since it may run several frames to catch up and only the last
     * one needs uploading.
     */
    private static void stepFrameAndUpload() {
        stepOneFrame();
        Renderer.uploadFrame(frame, overscanX, overscanY);
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
        for (int i = 0; i < DISASM_LINES && disassembler != null; i++) {
            code.append(i == 0 ? "> " : "  ").append(disassembler.disassemble(addr)).append('\n');
            addr = (addr + Math.max(1, disassembler.getInstructionLength(addr))) & 0xFFFF;
        }
        Platform.setText("disasm", code.toString());

        if (memFollowsPc) {
            // Snap to the start of the row holding PC, so the view does not
            // jitter sideways as the program counter moves within a line.
            debugAddress = cpu.getPC() & 0xFFF0;
        }

        StringBuilder dump = new StringBuilder();
        for (int row = 0; row < MEM_ROWS; row++) {
            int base = (debugAddress + row * 16) & 0xFFFF;
            dump.append(hex(base, 4)).append("  ");
            for (int i = 0; i < 16; i++) {
                dump.append(hex(nes.getMemory().peek((base + i) & 0xFFFF) & 0xFF, 2)).append(' ');
            }
            dump.append(' ');
            for (int i = 0; i < 16; i++) {
                int v = nes.getMemory().peek((base + i) & 0xFFFF) & 0xFF;
                dump.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
            }
            dump.append('\n');
        }
        Platform.setText("memory", dump.toString());
        Platform.setText("memRange", hex(debugAddress, 4) + " - "
                + hex((debugAddress + MEM_ROWS * 16 - 1) & 0xFFFF, 4)
                + (memFollowsPc ? "  (following PC)" : ""));
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

        aspect = loadOr("aspect", "43");
        applyOverscan(loadOr("overscan", "none"));
        scale = (int) numOr(loadOr("scale", ""), 0f);
        saturation = numOr(loadOr("sat", ""), saturation);
        brightness = numOr(loadOr("bright", ""), brightness);
        float volume = numOr(loadOr("volume", ""), 1f);
        Platform.setVolume(volume);

        String saved = loadOr("keys", "");
        if (!saved.isEmpty()) {
            String[] parts = saved.split(",", -1);
            for (int i = 0; i < keys.length && i < parts.length; i++) {
                keys[i] = parts[i];
            }
        }
        String savedPad = loadOr("pad", "");
        if (!savedPad.isEmpty()) {
            String[] parts = savedPad.split(",", -1);
            for (int i = 0; i < padBindings.length && i < parts.length; i++) {
                padBindings[i] = parts[i];
            }
        }
        refreshBindingLabels();

        Platform.setControl("crt", crtOn ? "1" : "0");
        Platform.setControl("mask", Float.toString(mask));
        Platform.setControl("bloom", Float.toString(bloom));
        Platform.setControl("focus", Float.toString(focus));
        Platform.setControl("scan", Float.toString(scan));
        Platform.setControl("curve", Float.toString(curve));
        Platform.setControl("maskType", Float.toString(maskType));
        Platform.setControl("aspect", aspect);
        Platform.setControl("scale", Integer.toString(scale));
        Platform.setControl("volume", Float.toString(volume));
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
