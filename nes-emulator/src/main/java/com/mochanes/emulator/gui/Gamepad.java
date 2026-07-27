package com.mochanes.emulator.gui;

import com.mochanes.emulator.Controller;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Gamepad support via the Linux joystick device.
 *
 * Java has no gamepad API, and pulling in a native binding (JInput and friends)
 * would add a dependency plus platform libraries. On Linux {@code /dev/input/js*}
 * exposes a stable 8-byte event record, which is enough for a NES pad and needs
 * nothing extra:
 *
 * <pre>
 *   u32 time, s16 value, u8 type, u8 number
 *   type: 0x01 button, 0x02 axis (| 0x80 for the initial state burst)
 * </pre>
 *
 * Runs on its own daemon thread and simply drives the {@link Controller}, so it
 * coexists with the keyboard - either input can press a button.
 */
public class Gamepad implements Runnable {

    private static final int TYPE_BUTTON = 0x01;
    private static final int TYPE_AXIS = 0x02;
    private static final int TYPE_INIT = 0x80;

    /** Beyond this fraction of full deflection an axis counts as pressed. */
    private static final int AXIS_THRESHOLD = 16384;

    private final Controller controller;
    private final File device;
    private volatile boolean running;
    private Thread thread;

    private Gamepad(Controller controller, File device) {
        this.controller = controller;
        this.device = device;
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

    /** Starts reading the first available pad, or returns null if there is none. */
    public static Gamepad start(Controller controller) {
        File dev = findDevice();
        if (dev == null) {
            return null;
        }
        Gamepad g = new Gamepad(controller, dev);
        g.running = true;
        g.thread = new Thread(g, "GamepadReader");
        g.thread.setDaemon(true);
        g.thread.start();
        return g;
    }

    public String deviceName() {
        return device.getPath();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        try (DataInputStream in = new DataInputStream(new FileInputStream(device))) {
            byte[] ev = new byte[8];
            while (running) {
                in.readFully(ev);

                int type = ev[6] & 0xFF;
                int number = ev[7] & 0xFF;
                int value = (short) ((ev[4] & 0xFF) | ((ev[5] & 0xFF) << 8));

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
                System.err.println("[Gamepad] " + device + " closed: " + e.getMessage());
            }
        }
    }

    /**
     * Standard pad layout: face buttons drive A/B, and the two centre buttons
     * drive Select/Start. Both face-button pairs are mapped so either row works.
     */
    private void handleButton(int number, boolean pressed) {
        switch (number) {
            case 0: // south
            case 3: // north - so either "bottom" or "right" style layout works
                controller.setButtonPressed(0, pressed); // A
                break;
            case 1: // east
            case 2: // west
                controller.setButtonPressed(1, pressed); // B
                break;
            case 6:
            case 8:
                controller.setButtonPressed(2, pressed); // Select
                break;
            case 7:
            case 9:
                controller.setButtonPressed(3, pressed); // Start
                break;
            default:
                break;
        }
    }

    /** Axis 0/1 are the left stick; 6/7 are usually the d-pad hat. */
    private void handleAxis(int number, int value) {
        boolean negative = value < -AXIS_THRESHOLD;
        boolean positive = value > AXIS_THRESHOLD;

        if (number == 0 || number == 6) { // horizontal
            controller.setButtonPressed(6, negative); // Left
            controller.setButtonPressed(7, positive); // Right
        } else if (number == 1 || number == 7) { // vertical
            controller.setButtonPressed(4, negative); // Up
            controller.setButtonPressed(5, positive); // Down
        }
    }
}
