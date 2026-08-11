package com.mochanes.emulator;

import com.mochanes.emulator.gui.Display;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ReplayRunner implements Runnable {
    private final NES nes;
    private final Display display;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private Thread thread;
    private Map<Integer, Integer> replayData = new HashMap<>();

    public ReplayRunner(NES nes, Display display, String logPath) {
        this.nes = nes;
        this.display = display;
        loadReplayLog(logPath);
    }

    private void loadReplayLog(String path) {
        System.out.println("Loading replay log: " + path);
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    int frame = Integer.parseInt(parts[0].trim());
                    int buttons = Integer.parseInt(parts[1].trim());
                    replayData.put(frame, buttons);
                }
            }
            System.out.println("Loaded " + replayData.size() + " input events.");
        } catch (IOException e) {
            System.err.println("Failed to load replay log: " + e.getMessage());
        }
    }

    public void start() {
        if (running)
            return;
        running = true;
        thread = new Thread(this, "ReplayThread");
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (thread != null)
                thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        int frameCounter = 0;

        while (running) {
            // Frame Loop
            CPU cpu = nes.getCpu();
            PPU ppu = nes.getPpu();
            APU apu = nes.getApu();

            if (cpu == null || ppu == null)
                continue;

            // Apply Replay Input for this Frame
            int buttons = replayData.getOrDefault(frameCounter, 0);
            setControllerState(buttons);
            display.setOverlayText(buttons == 0 ? "" : decodeInputs(buttons));

            // Generate one frame
            while (!ppu.frameComplete && running) {
                stepSystem(cpu, ppu, apu);
            }
            ppu.frameComplete = false;

            frameCounter++;

            // Replay Finished?
            if (frameCounter > replayData.keySet().stream().max(Integer::compare).orElse(0) + 60) {
                System.out.println("Replay Finished.");
                running = false;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                }
                System.exit(0);
            }
        }
    }

    private void stepSystem(CPU cpu, PPU ppu, APU apu) {
        nes.stepInstruction();
    }

    private void setControllerState(int buttons) {
        // 0: A, 1: B, 2: Select, 3: Start, 4: Up, 5: Down, 6: Left, 7: Right
        nes.getController().setButtonPressed(0, (buttons & 1) != 0);
        nes.getController().setButtonPressed(1, (buttons & 2) != 0);
        nes.getController().setButtonPressed(2, (buttons & 4) != 0);
        nes.getController().setButtonPressed(3, (buttons & 8) != 0);
        nes.getController().setButtonPressed(4, (buttons & 16) != 0);
        nes.getController().setButtonPressed(5, (buttons & 32) != 0);
        nes.getController().setButtonPressed(6, (buttons & 64) != 0);
        nes.getController().setButtonPressed(7, (buttons & 128) != 0);
    }

    private String decodeInputs(int buttons) {
        StringBuilder sb = new StringBuilder();
        if ((buttons & 1) != 0)
            sb.append("A ");
        if ((buttons & 2) != 0)
            sb.append("B ");
        if ((buttons & 4) != 0)
            sb.append("SEL ");
        if ((buttons & 8) != 0)
            sb.append("STRT ");
        if ((buttons & 16) != 0)
            sb.append("UP ");
        if ((buttons & 32) != 0)
            sb.append("DWN ");
        if ((buttons & 64) != 0)
            sb.append("LFT ");
        if ((buttons & 128) != 0)
            sb.append("RGT ");
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ReplayRunner <romPath> <logPath>");
            System.exit(1);
        }
        String romPath = args[0];
        String logPath = args[1];

        // Create UI
        Display display = new Display(false);
        NES nes = new NES(display);

        try {
            nes.loadROM(romPath);
            ReplayRunner runner = new ReplayRunner(nes, display, logPath);
            runner.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
