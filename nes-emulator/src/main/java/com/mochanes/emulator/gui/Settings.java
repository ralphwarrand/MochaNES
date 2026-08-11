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

    public final int[] keys = DEFAULT_KEYS.clone();
    public int scale = 3; // window size multiplier
    public Aspect aspect = Aspect.PIXEL_PERFECT;
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
        }
        scale = Math.max(1, Math.min(6, intProp(p, "video.scale", scale)));
        gamepadEnabled = Boolean.parseBoolean(p.getProperty("input.gamepad", String.valueOf(gamepadEnabled)));
        try {
            aspect = Aspect.valueOf(p.getProperty("video.aspect", aspect.name()));
        } catch (IllegalArgumentException ignored) {
            // unknown value: keep default
        }
    }

    public void save() {
        Properties p = new Properties();
        for (int i = 0; i < keys.length; i++) {
            p.setProperty("key." + BUTTON_NAMES[i], String.valueOf(keys[i]));
        }
        p.setProperty("video.scale", String.valueOf(scale));
        p.setProperty("video.aspect", aspect.name());
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
