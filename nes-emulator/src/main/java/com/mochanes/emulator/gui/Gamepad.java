package com.mochanes.emulator.gui;

import com.mochanes.emulator.Controller;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Gamepad support.
 *
 * <p>Java has no gamepad API, and pulling in a native binding (JInput and
 * friends) would add a dependency plus platform libraries. On Linux
 * {@code /dev/input/js*} exposes a stable 8-byte event record, which is enough
 * for a NES pad and needs nothing extra:
 *
 * <pre>
 *   u32 time, s16 value, u8 type, u8 number
 *   type: 0x01 button, 0x02 axis (| 0x80 for the initial state burst)
 * </pre>
 *
 * <p>Reading the device is the only platform-specific part, and it sits behind
 * {@link Source}. Everything above it - which control drives which NES button,
 * the binding tokens, the capture used by the settings dialog - is ordinary
 * Java that a Windows backend would reuse as-is. There is no dependency-free
 * way to read XInput or DirectInput from the JVM, so on Windows (and macOS)
 * {@link #start} finds no source and returns null; the emulator then runs on
 * the keyboard, and the settings dialog says no pad was detected. Adding a
 * backend later means implementing {@link Source} and listing it in
 * {@link #openSource()} - nothing else changes.
 *
 * <p>Runs on its own daemon thread and simply drives the {@link Controller}, so
 * it coexists with the keyboard - either input can press a button.
 */
public class Gamepad implements Runnable {

    private static final int TYPE_BUTTON = 0x01;
    private static final int TYPE_AXIS = 0x02;
    private static final int TYPE_INIT = 0x80;

    /** Beyond this fraction of full deflection an axis counts as pressed. */
    private static final int AXIS_THRESHOLD = 16384;

    /** One physical control changing state. */
    public interface RawListener {
        /**
         * @param token binding token for the control, e.g. {@code b2} or {@code a1-}
         */
        void onControl(String token);
    }

    /**
     * A platform's way of reading pad events.
     *
     * <p>Implementations report events in the same shape the Linux joystick
     * driver uses, since it is the simplest of the three and the others can be
     * translated into it.
     */
    public interface Source extends AutoCloseable {
        /** Human-readable device identity, for the settings dialog. */
        String name();

        /**
         * Blocks for the next event.
         *
         * @param out three-element array filled with {type, number, value}
         * @return false when the device is gone
         */
        boolean next(int[] out) throws Exception;
    }

    private final Controller controller;
    private final Settings settings;
    private final Source source;
    /** Controls currently held, as binding tokens. */
    private final java.util.Set<String> held = new java.util.HashSet<>();
    private volatile boolean running;
    private volatile RawListener rawListener;
    private Thread thread;

    private Gamepad(Controller controller, Settings settings, Source source) {
        this.controller = controller;
        this.settings = settings;
        this.source = source;
    }

    /** Finds the first readable joystick device, or null if none is present. */
    public static File findDevice() {
        for (int i = 0; i < 8; i++) {
            File f = new File("/dev/input/js" + i);
            if (f.exists() && f.canRead()) {
                return f;
            }
        }
        return null;
    }

    /**
     * Opens whichever pad backend this platform has, or null if it has none.
     * A Windows or macOS implementation would be tried here alongside Linux.
     */
    private static Source openSource() {
        File dev = findDevice();
        if (dev != null) {
            try {
                return new LinuxJoystick(dev);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** True when this platform can currently see a pad. */
    public static boolean available() {
        return findDevice() != null;
    }

    /** Name of the pad that would be used, or null when there is none. */
    public static String detectedName() {
        File dev = findDevice();
        return dev == null ? null : dev.getPath();
    }

    /** Starts reading the first available pad, or returns null if there is none. */
    public static Gamepad start(Controller controller, Settings settings) {
        Source src = openSource();
        if (src == null) {
            return null;
        }
        Gamepad g = new Gamepad(controller, settings, src);
        g.running = true;
        g.thread = new Thread(g, "GamepadReader");
        g.thread.setDaemon(true);
        g.thread.start();
        return g;
    }

    public String deviceName() {
        return source.name();
    }

    /**
     * Routes every control change to {@code listener} instead of only to the
     * bound buttons, so the settings dialog can wait for one to be pressed.
     * Called from the reader thread; the dialog hops to the EDT itself.
     */
    public void setRawListener(RawListener listener) {
        this.rawListener = listener;
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        try {
            source.close();
        } catch (Exception ignored) {
            // closing a device that is already gone is not worth reporting
        }
    }

    @Override
    public void run() {
        int[] ev = new int[3];
        try {
            while (running && source.next(ev)) {
                int type = ev[0];
                int number = ev[1];
                int value = ev[2];

                // The driver replays current state on open with the init bit
                // set; those are not real presses.
                if ((type & TYPE_INIT) != 0) {
                    continue;
                }

                if (type == TYPE_BUTTON) {
                    handleButton(number, value != 0);
                } else if (type == TYPE_AXIS) {
                    handleAxis(number, value);
                }
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("[Gamepad] " + source.name() + " closed: " + e.getMessage());
            }
        }
    }

    private void handleButton(int number, boolean pressed) {
        String token = "b" + number;
        if (pressed) {
            report(token);
        }
        setHeld(token, pressed);
        refresh();
    }

    private void handleAxis(int number, int value) {
        boolean negative = value < -AXIS_THRESHOLD;
        boolean positive = value > AXIS_THRESHOLD;

        if (negative) {
            report("a" + number + "-");
        } else if (positive) {
            report("a" + number + "+");
        }
        // Both directions are updated on every event, so letting the stick go
        // clears whichever way it was pushed.
        setHeld("a" + number + "-", negative);
        setHeld("a" + number + "+", positive);
        refresh();
    }

    private void setHeld(String token, boolean pressed) {
        if (pressed) {
            held.add(token);
        } else {
            held.remove(token);
        }
    }

    /**
     * Recomputes every NES button from the set of controls currently held.
     *
     * <p>Done wholesale rather than per event because a button may be bound to
     * several controls: with the d-pad and the stick both mapped to Left,
     * releasing one while the other is still pushed must not release Left.
     */
    private void refresh() {
        String[] bindings = settings.padBindings;
        for (int i = 0; i < bindings.length; i++) {
            boolean pressed = false;
            for (String token : held) {
                if (Settings.padBindingHas(bindings[i], token)) {
                    pressed = true;
                    break;
                }
            }
            controller.setButtonPressed(i, pressed);
        }
    }

    private void report(String token) {
        RawListener listener = rawListener;
        if (listener != null) {
            listener.onControl(token);
        }
    }

    /** Reads the Linux joystick character device. */
    private static final class LinuxJoystick implements Source {
        private final File device;
        private final DataInputStream in;
        private final byte[] record = new byte[8];

        LinuxJoystick(File device) throws Exception {
            this.device = device;
            this.in = new DataInputStream(new FileInputStream(device));
        }

        @Override
        public String name() {
            return device.getPath();
        }

        @Override
        public boolean next(int[] out) throws Exception {
            in.readFully(record);
            out[0] = record[6] & 0xFF;
            out[1] = record[7] & 0xFF;
            out[2] = (short) ((record[4] & 0xFF) | ((record[5] & 0xFF) << 8));
            return true;
        }

        @Override
        public void close() throws Exception {
            in.close();
        }
    }
}
