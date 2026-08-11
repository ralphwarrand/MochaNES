package com.mochanes.ai.games.zelda;

import com.mochanes.ai.HeadlessNES;

public class ZeldaPlayer {

    private final String romPath;
    private final String modelPath;
    private final ZeldaAgent agent;
    private final ZeldaWatcher watcher;

    public ZeldaPlayer(String rom, String model) {
        this.romPath = rom;
        this.modelPath = model;
        this.agent = new ZeldaAgent();
        this.agent.load(modelPath);
        this.watcher = new ZeldaWatcher();
    }

    public void play() {
        System.out.println("Starting Zelda Player...");

        HeadlessNES nes = new HeadlessNES(romPath);
        ZeldaGameState state = new ZeldaGameState(nes);

        // Loop forever
        while (true) {
            long start = System.nanoTime();

            // 1. Observe
            float[] vec = ZeldaFeatureExtractor.extract(state);

            // 2. Act
            ZeldaAgent.StepResult res = agent.act(vec);

            // 3. Step
            nes.setControllerState(res.bitmask);
            nes.runFrame();

            // 4. Visualize
            // We want simple reward calc for display
            float reward = 0; // Stateless calc not easily available here without tracking
            watcher.update(nes.getScreenBuffer(), state, vec, reward, res.actionIdx);

            // 5. Throttle to ~60FPS
            long elapsed = System.nanoTime() - start;
            long target = 16666666; // 16.6ms
            if (elapsed < target) {
                try {
                    Thread.sleep((target - elapsed) / 1000000);
                } catch (InterruptedException e) {
                }
            }
        }
    }
}
