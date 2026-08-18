package com.mochanes.emulator.gui;

import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * User preferences: key bindings, video scaling and CRT state.
 *
 * Persisted to {@code ~/.config/mochanes/settings.properties} so the emulator
 * comes back the way it was left. Loading never throws - a missing or damaged
 * file just falls back to defaults, because losing preferences should not stop
 * the emulator from starting.
 */
public class Settings {

    /** NES button order used throughout: A, B, Select, Start, Up, Down, Left, Right. */
    public static final String[] BUTTON_NAMES = {
            "A", "B", "Select", "Start", "Up", "Down", "Left", "Right"
    };

    private static final int[] DEFAULT_KEYS = {
            KeyEvent.VK_Z, KeyEvent.VK_X, KeyEvent.VK_SHIFT, KeyEvent.VK_ENTER,
            KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT
    };

    /**
     * How many pixels the CRT simulation may render before it is scaled up.
     *
     * <p>Its cost is proportional to the pixels asked for, so a maximised
     * window on a large screen can cost more than a frame's worth of time. The
     * budget caps that; the picture is rendered smaller and enlarged, which
     * softens the shadow mask, so the choice is left to the user.
     */
    public enum CrtDetail {
        FULL("Full - native resolution", 0),
        BALANCED("Balanced", 1_200_000),
        FAST("Fast - softest mask", 600_000);

        final String label;
        final int maxPixels;

        CrtDetail(String label, int maxPixels) {
            this.label = label;
            this.maxPixels = maxPixels;
        }

        public String label() {
            return label;
        }

        /** Pixel budget, or 0 for no limit. */
        public int maxPixels() {
            return maxPixels;
        }
    }

    /**
     * How much of the picture's edge is hidden.
     *
     * <p>A television overscanned: the outermost pixels fell behind the bezel
     * and were never seen. Games relied on it - the NES picks a palette per
     * 16x16 block but scrolls a pixel at a time, so a column revealed by
     * scrolling can show the previous block's colours until the attribute
     * catches up. Games blank the leftmost 8 pixels to hide part of that, but
     * the block is 16 wide, so the rest can still show. Cropping the edges puts
     * back what a TV did.
     */
    public enum Overscan {
        NONE("Off - show all 256x240", 0, 0),
        SIDES("Hide 8px left and right", 8, 0),
        TV("Hide 8px all round (as on a TV)", 8, 8);

        final String label;
        final int horizontal;
        final int vertical;

        Overscan(String label, int horizontal, int vertical) {
            this.label = label;
            this.horizontal = horizontal;
            this.vertical = vertical;
        }

        public String label() {
            return label;
        }

        public int horizontal() {
            return horizontal;
        }

        public int vertical() {
            return vertical;
        }
    }

    /** How the picture is fitted to the window. */
    public enum Aspect {
        PIXEL_PERFECT("Pixel Perfect (integer)"),
        ASPECT_4_3("4:3 (as on a TV)"),
        STRETCH("Stretch to fill");

        final String label;

        Aspect(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Default pad bindings, in the token form described on {@link #padBindings}.
     *
     * <p>These reproduce what the driver used to hardcode, alternatives and
     * all: either face button of a pair for A and B so both "bottom" and
     * "right" layouts work, and both the usual stick axes and the hat axes for
     * the d-pad. Pads that report their controls somewhere else entirely are
     * why this is rebindable rather than a still longer list of guesses.
     */
    private static final String[] DEFAULT_PAD = {
            "b0|b3", "b1|b2", "b6|b8", "b7|b9",
            "a1-|a7-", "a1+|a7+", "a0-|a6-", "a0+|a6+"
    };

    /** Separates the alternatives within one binding. */
    public static final String ALT = "|";

    public final int[] keys = DEFAULT_KEYS.clone();

    /**
     * What each NES button is bound to on the pad, as a small token:
     * {@code b<n>} for pad button n, {@code a<n>-} / {@code a<n>+} for axis n
     * pushed past the threshold in the negative or positive direction, and the
     * empty string for unbound.
     *
     * <p>Tokens rather than raw indices because a d-pad is a pair of buttons on
     * some pads and an axis on others, and both have to be expressible. The
     * form carries no platform detail, so it means the same thing to the Linux
     * joystick reader and to the browser's Gamepad API.
     */
    public final String[] padBindings = DEFAULT_PAD.clone();

    public int scale = 3; // window size multiplier
    public Aspect aspect = Aspect.PIXEL_PERFECT;
    public CrtDetail crtDetail = CrtDetail.BALANCED;
    public Overscan overscan = Overscan.NONE;
    public boolean gamepadEnabled = true;

    private final File file;

    public Settings() {
        String home = System.getProperty("user.home", ".");
        this.file = new File(home + "/.config/mochanes/settings.properties");
        load();
    }

    public int keyFor(int button) {
        return keys[button];
    }

    /** Returns the button bound to a key code, or -1. */
    public int buttonForKey(int keyCode) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == keyCode) {
                return i;
            }
        }
        return -1;
    }

    public void bind(int button, int keyCode) {
        // A key can only drive one button; clear any previous owner.
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == keyCode) {
                keys[i] = -1;
            }
        }
        keys[button] = keyCode;
    }

    public void resetBindings() {
        System.arraycopy(DEFAULT_KEYS, 0, keys, 0, DEFAULT_KEYS.length);
        System.arraycopy(DEFAULT_PAD, 0, padBindings, 0, DEFAULT_PAD.length);
    }

    /** What the pad drives this NES button, or "" when nothing does. */
    public String padFor(int button) {
        return padBindings[button];
    }

    /** True when {@code binding} lists {@code token} as one of its controls. */
    public static boolean padBindingHas(String binding, String token) {
        if (binding == null || binding.isEmpty()) {
            return false;
        }
        for (String alt : binding.split("\\|")) {
            if (alt.equals(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Binds one control to a button, replacing whatever was there.
     *
     * <p>Rebinding sets a single control rather than a list; the alternatives
     * in the defaults exist so an untouched pad works whichever way it reports
     * its d-pad, not because a user needs to type them.
     */
    public void bindPad(int button, String token) {
        // As with keys, one control drives one button; take it off any other.
        for (int i = 0; i < padBindings.length; i++) {
            if (padBindingHas(padBindings[i], token)) {
                StringBuilder kept = new StringBuilder();
                for (String alt : padBindings[i].split("\\|")) {
                    if (!alt.equals(token)) {
                        kept.append(kept.length() == 0 ? "" : ALT).append(alt);
                    }
                }
                padBindings[i] = kept.toString();
            }
        }
        padBindings[button] = token == null ? "" : token;
    }

    /** Human-readable form of a binding, for the settings dialog. */
    public static String padLabel(String binding) {
        if (binding == null || binding.isEmpty()) {
            return "(unbound)";
        }
        StringBuilder out = new StringBuilder();
        for (String token : binding.split("\\|")) {
            out.append(out.length() == 0 ? "" : " / ").append(controlLabel(token));
        }
        return out.toString();
    }

    private static String controlLabel(String token) {
        if (token.startsWith("b")) {
            return "Button " + token.substring(1);
        }
        if (token.startsWith("a") && token.length() >= 3) {
            return "Axis " + token.substring(1, token.length() - 1)
                    + (token.endsWith("-") ? " -" : " +");
        }
        return token;
    }

    public void load() {
        if (!file.isFile()) {
            return;
        }
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (Exception e) {
            return; // corrupt file: keep defaults
        }
        for (int i = 0; i < keys.length; i++) {
            keys[i] = intProp(p, "key." + BUTTON_NAMES[i], keys[i]);
            padBindings[i] = p.getProperty("pad." + BUTTON_NAMES[i], padBindings[i]).trim();
        }
        scale = Math.max(1, Math.min(6, intProp(p, "video.scale", scale)));
        gamepadEnabled = Boolean.parseBoolean(p.getProperty("input.gamepad", String.valueOf(gamepadEnabled)));
        try {
            aspect = Aspect.valueOf(p.getProperty("video.aspect", aspect.name()));
        } catch (IllegalArgumentException ignored) {
            // unknown value: keep default
        }
        try {
            crtDetail = CrtDetail.valueOf(p.getProperty("video.crtDetail", crtDetail.name()));
        } catch (IllegalArgumentException ignored) {
            // unknown value: keep default
        }
        try {
            overscan = Overscan.valueOf(p.getProperty("video.overscan", overscan.name()));
        } catch (IllegalArgumentException ignored) {
            // unknown value: keep default
        }
    }

    public void save() {
        Properties p = new Properties();
        for (int i = 0; i < keys.length; i++) {
            p.setProperty("key." + BUTTON_NAMES[i], String.valueOf(keys[i]));
            p.setProperty("pad." + BUTTON_NAMES[i], padBindings[i]);
        }
        p.setProperty("video.scale", String.valueOf(scale));
        p.setProperty("video.aspect", aspect.name());
        p.setProperty("video.crtDetail", crtDetail.name());
        p.setProperty("video.overscan", overscan.name());
        p.setProperty("input.gamepad", String.valueOf(gamepadEnabled));
        try {
            Files.createDirectories(file.getParentFile().toPath());
            try (FileOutputStream out = new FileOutputStream(file)) {
                p.store(out, "MochaNES settings");
            }
        } catch (Exception e) {
            System.err.println("[Settings] could not save: " + e.getMessage());
        }
    }

    private static int intProp(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
