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
        runner.start();

        currentNes = nes;
        currentRunner = runner;
        System.out.println("Emulator running.");

        SwingUtilities.invokeLater(() -> {
            currentDebugger = new DebuggerWindow(nes, runner);
            currentDebugger.setVisible(true);
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

}
