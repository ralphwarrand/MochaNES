package com.mochanes.emulator;

import com.mochanes.emulator.gui.DebuggerWindow;
import com.mochanes.emulator.gui.Display;
import javax.swing.SwingUtilities;

import java.io.IOException;

public class Main {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--replay")) {
            String logPath = args.length > 1 ? args[1] : "replay.log";
            String romPath = args.length > 2 ? args[2] : "resources/nestest.nes";
            runReplay(logPath, romPath);
        } else {
            String romPath = args.length > 0 ? args[0] : "resources/nestest.nes";
            runGameLoop(romPath);
        }
    }

    // The currently running session. Replaced wholesale when the user opens a
    // different ROM from the File menu.
    private static NES currentNes;
    private static EmulatorRunner currentRunner;
    private static DebuggerWindow currentDebugger;

    private static void runGameLoop(String romPath) {
        // GUI Initialization (EDT recommended, but simple here)
        Display display = new Display();

        display.setRomLoadHandler(file -> startSession(display, file.getPath()));
        display.setResetHandler(() -> {
            if (currentNes != null) {
                currentNes.reset();
            }
        });

        try {
            startSession(display, romPath);
        } catch (RuntimeException e) {
            System.err.println("Error loading ROM: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Tears down any running session and brings up a new one for the given ROM.
     * Throws {@link RuntimeException} on failure so the GUI can report it.
     */
    private static void startSession(Display display, String romPath) {
        // Stop the old emulation thread first; stop() joins, so nothing else is
        // still touching the display by the time we swap.
        if (currentRunner != null) {
            currentRunner.stop();
        }
        if (currentNes != null) {
            currentNes.getApu().close(); // release the old audio line
        }
        if (currentDebugger != null) {
            currentDebugger.dispose();
            currentDebugger = null;
        }

        display.setRomName(new java.io.File(romPath).getName());

        NES nes = new NES(display);
        try {
            nes.loadROM(romPath);
        } catch (IOException e) {
            nes.getApu().close();
            throw new RuntimeException(e.getMessage(), e);
        }

        display.setController(nes.getController());
        nes.reset();

        System.out.println("Starting Emulation Thread...");
        EmulatorRunner runner = new EmulatorRunner(nes);
        runner.setTargetFps(displayRefreshRate());
        runner.start();

        currentNes = nes;
        currentRunner = runner;
        System.out.println("Emulator running.");

        // The debugger is not opened here. It is a developer tool, and having it
        // appear beside the game every launch is noise for anyone who just wants
        // to play. File > Debugger opens it, and it is built on first use.
        display.setDebuggerHandler(() -> showDebugger(nes, runner));

        String romName = new java.io.File(romPath).getName();
        display.setSaveStateHandler(file -> writeState(display, nes, runner,
                file != null ? file : defaultStateFile(romName)));
        display.setLoadStateHandler(file -> readState(display, nes, runner,
                file != null ? file : defaultStateFile(romName)));
    }

    /**
     * Opens the debugger, creating it the first time and reusing it after.
     *
     * <p>A previous window is disposed when a new ROM is loaded, since it holds
     * references to the machine that has been replaced.
     */
    private static void showDebugger(NES nes, EmulatorRunner runner) {
        SwingUtilities.invokeLater(() -> {
            // Loading a ROM disposes the old window and clears this, so a
            // surviving one always belongs to the machine now running. Raise it
            // rather than rebuilding, which would lose the open tab and scroll
            // position.
            if (currentDebugger == null) {
                currentDebugger = new DebuggerWindow(nes, runner);
            }
            currentDebugger.setVisible(true);
            currentDebugger.toFront();
        });
    }

    private static void runReplay(String logPath, String romPath) {
        try {
            // GUI Initialization
            Display display = new Display();

            // Core Initialization
            NES nes = new NES(display);
            nes.loadROM(romPath);

            // Replay Runner
            System.out.println("Starting Replay Thread with log: " + logPath);
            ReplayRunner runner = new ReplayRunner(nes, display, logPath);

            // Initial Reset
            nes.reset();

            // Start
            runner.start();
            System.out.println("Replay running.");

        } catch (IOException e) {
            System.err.println("Error loading ROM for replay: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Quick-slot path for a ROM, beside the settings file. */
    private static java.io.File defaultStateFile(String romName) {
        String home = System.getProperty("user.home", ".");
        return new java.io.File(home + "/.config/mochanes/states/" + romName + ".mst");
    }

    /**
     * Writes a snapshot of the machine.
     *
     * <p>The emulator thread is stopped for the duration. It runs the CPU and
     * PPU continuously, so serialising underneath it would capture a machine
     * caught between two states - a PPU part-way through a frame that the CPU
     * has already moved past - which would not reload into anything coherent.
     */
    private static void writeState(com.mochanes.emulator.gui.Display display, NES nes,
            EmulatorRunner runner, java.io.File target) {
        boolean wasPaused = runner.isPaused();
        runner.setPaused(true);
        try {
            java.nio.file.Files.createDirectories(target.getParentFile().toPath());
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(target)))) {
                nes.saveState(out);
            }
            display.setOverlayText(null);
            System.out.println("State saved: " + target);
        } catch (Exception e) {
            System.err.println("Could not save state: " + e.getMessage());
        } finally {
            runner.setPaused(wasPaused);
        }
    }

    private static void readState(com.mochanes.emulator.gui.Display display, NES nes,
            EmulatorRunner runner, java.io.File target) {
        if (!target.isFile()) {
            System.err.println("No state at " + target);
            return;
        }
        boolean wasPaused = runner.isPaused();
        runner.setPaused(true);
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(target)))) {
            nes.loadState(in);
            System.out.println("State loaded: " + target);
        } catch (Exception e) {
            System.err.println("Could not load state: " + e.getMessage());
        } finally {
            runner.setPaused(wasPaused);
        }
    }

    /**
     * The screen's refresh rate, or the console's own rate when it cannot be
     * read or is nowhere near it.
     *
     * <p>Producing frames at the display's rate keeps the two in step. Left at
     * 60.0988Hz against a 60Hz screen the phases drift, and roughly every ten
     * seconds a frame arrives with no refresh to show it - seen as a hitch
     * rather than as the emulator running at the wrong speed.
     */
    private static double displayRefreshRate() {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                return EmulatorRunner.NES_FPS;
            }
            int rate = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDisplayMode().getRefreshRate();
            if (rate == java.awt.DisplayMode.REFRESH_RATE_UNKNOWN || rate <= 0) {
                return EmulatorRunner.NES_FPS;
            }
            // Only follow the display when it is close; a 144Hz screen must not
            // make the console run at two and a half times speed.
            if (Math.abs(rate - EmulatorRunner.NES_FPS) / EmulatorRunner.NES_FPS < 0.01) {
                return rate;
            }
        } catch (Throwable ignored) {
            // Any windowing trouble: fall back to the console's own rate.
        }
        return EmulatorRunner.NES_FPS;
    }

}
