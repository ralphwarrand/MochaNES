package com.mochanes.ai.games.zelda;

import com.mochanes.ai.HeadlessNES;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Collections;

public class ZeldaTrainer {

    private final String romPath;
    private final String outputDir;
    private final int numEnvs;
    private final ZeldaAgent agent;

    // Config
    private final int STEPS_PER_EPOCH = 128; // Steps per env before update
    private final int TOTAL_UPDATES = 10000;

    private boolean enableWatch = false;
    private ZeldaWatcher watcher;

    private class EnvContext {
        HeadlessNES nes;
        ZeldaGameState stateReader;
        int lastHearts = -1;
        int lastRupees = -1;
        int lastKeys = -1;
        int lastBombs = -1;
        java.util.Set<Integer> visitedScreens = new java.util.HashSet<>();
        int startupFrames = 0;

        EnvContext(HeadlessNES nes, ZeldaGameState stateReader) {
            this.nes = nes;
            this.stateReader = stateReader;
        }
    }

    public ZeldaTrainer(String rom, String out, int envs, boolean watch) {
        this.romPath = rom;
        this.outputDir = out;
        this.numEnvs = envs;
        this.agent = new ZeldaAgent();
        this.enableWatch = watch;

        if (enableWatch) {
            this.watcher = new ZeldaWatcher();
        }
    }

    public void train() {
        System.out.println("Starting Zelda Trainer with " + numEnvs + " environments. Watch Mode: " + enableWatch);

        // Init Envs
        List<EnvContext> envContexts = new ArrayList<>();

        for (int i = 0; i < numEnvs; i++) {
            HeadlessNES nes = new HeadlessNES(romPath);
            envContexts.add(new EnvContext(nes, new ZeldaGameState(nes)));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numEnvs);

        long totalFrames = 0;
        long startTime = System.currentTimeMillis();

        for (int update = 0; update < TOTAL_UPDATES; update++) {

            // 1. Collection Phase
            List<ZeldaAgent.ZeldaExperience> buffer = Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> tasks = new ArrayList<>();

            // We need to keep track of previous states/actions for GAE calculation
            // For simplicity in this v1, we just collect steps.
            // Proper PPO needs Trajectories.
            // Lets run each env for STEPS_PER_EPOCH and gathering a trajectory.

            for (int i = 0; i < numEnvs; i++) {
                final int envIdx = i;
                tasks.add(executor.submit(() -> {
                    EnvContext ctx = envContexts.get(envIdx);

                    // Run trajectory
                    List<ZeldaAgent.ZeldaExperience> trajectory = new ArrayList<>();
                    float accumulatedReward = 0;

                    for (int s = 0; s < STEPS_PER_EPOCH; s++) {
                        // Startup sequence / menu bypass
                        int mode = ctx.stateReader.getGameMode();
                        // Mode 5 is gameplay overworld. Mode 6 is death screen.
                        if (mode != 5) {
                            // Sequence: Wait, Wait, Start(title), Select(to File 2), Select(to File 3), Select(to Register), Start(enter Reg), Start(File 1), A(Type), Start(Finish), Start(Pick File 1), Start(Play)
                            int[] macro = {0, 0, 8, 4, 4, 4, 8, 8, 1, 8, 8, 8}; 
                            int macroStep = ctx.startupFrames / 90; // Next step every 1.5 seconds
                            
                            int actionBitmask = 0;
                            if (macroStep < macro.length) {
                                // Hold button for exactly 5 frames to prevent auto-repeat double pressing
                                actionBitmask = (ctx.startupFrames % 90 < 5) ? macro[macroStep] : 0;
                            } else if (ctx.startupFrames > 3600) {
                                // Reset macro if still stuck after 60 seconds
                                ctx.startupFrames = 0;
                            }
                            
                            ctx.nes.setControllerState(actionBitmask);
                            ctx.nes.runFrame();
                            ctx.startupFrames++;
                            if (enableWatch && envIdx == 0 && (ctx.startupFrames % watcher.getRenderSkipFrames() == 0)) {
                                watcher.update(ctx.nes.getScreenBuffer(), ctx.stateReader, new float[ZeldaFeatureExtractor.INPUT_SIZE], 0, 0);
                            }
                            continue; // ALWAYS block ML models from controlling non-gameplay screens
                        } else {
                            // If we just entered gameplay, reset the macro frame counter in case we die later
                            ctx.startupFrames = 0;
                        }

                        // Observe
                        float[] vec = ZeldaFeatureExtractor.extract(ctx.stateReader);

                        // Act
                        ZeldaAgent.StepResult res = agent.act(vec);

                        // Step Env
                        ctx.nes.setControllerState(res.bitmask);
                        ctx.nes.runFrame(); 

                        // Reward Calc
                        float reward = calculateReward(ctx);
                        accumulatedReward += reward;

                        // Visualization (Env 0 only)
                        if (enableWatch && envIdx == 0) {
                            if (s % watcher.getRenderSkipFrames() == 0) {
                                watcher.update(ctx.nes.getScreenBuffer(), ctx.stateReader, vec, reward, res.actionIdx);
                            }
                        }

                        trajectory.add(
                                new ZeldaAgent.ZeldaExperience(vec, res.actionIdx, reward, res.value, res.logProb));
                    }

                    if (trajectory.isEmpty()) return; // All frames were startup block

                    // Bootstrap Value (Next state)
                    float[] nextVec = ZeldaFeatureExtractor.extract(ctx.stateReader);
                    ZeldaAgent.StepResult nextRes = agent.act(nextVec);
                    float nextValue = nextRes.value;

                    // Compute GAE / Returns
                    float gae = 0;
                    float gamma = 0.99f;
                    float lam = 0.95f;

                    for (int t = trajectory.size() - 1; t >= 0; t--) {
                        ZeldaAgent.ZeldaExperience step = trajectory.get(t);
                        float delta = step.reward + gamma * nextValue - step.value;
                        gae = delta + gamma * lam * gae;

                        step.advantage = gae;
                        step.returnTarget = step.advantage + step.value;

                        nextValue = step.value;
                    }

                    buffer.addAll(trajectory);

                    // Reached game over? (Mode drops from 5?)
                    if (ctx.stateReader.getHearts() == 0) {
                         // Eventually trigger reload or soft reset
                    }
                }));
            }

            // Wait for all
            for (Future<?> f : tasks) {
                try {
                    f.get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            totalFrames += (numEnvs * STEPS_PER_EPOCH);

            // 2. Training Phase
            ZeldaAgent.TrainingMetrics metrics = agent.train(buffer);

            // Log
            if (update % 10 == 0) {
                float fps = totalFrames / ((System.currentTimeMillis() - startTime) / 1000.0f);

                double meanReward = buffer.stream().mapToDouble(e -> e.reward).average().orElse(0);

                System.out.println(String.format("Up %d | FPS: %.0f | Rwd: %.4f | Loss: A=%.3f C=%.3f E=%.3f",
                        update, fps, meanReward, metrics.actorLoss, metrics.criticLoss, metrics.entropy));

                if (enableWatch) {
                    watcher.addMetrics(metrics, (float) meanReward);
                }

                agent.save(outputDir);
            }
        }

        executor.shutdown();
    }

    private float calculateReward(EnvContext ctx) {
        float r = 0;
        
        int currentHearts = ctx.stateReader.getHearts();
        int currentRupees = ctx.stateReader.getRupees();
        int currentKeys = ctx.stateReader.getKeys();
        int currentBombs = ctx.stateReader.getBombs();
        int currentMapPos = ctx.stateReader.getMapPosition();

        if (ctx.lastHearts == -1) {
            // Initialize on first valid frame
            ctx.lastHearts = currentHearts;
            ctx.lastRupees = currentRupees;
            ctx.lastKeys = currentKeys;
            ctx.lastBombs = currentBombs;
            ctx.visitedScreens.add(currentMapPos);
            return 0; 
        }

        // Damage Penalty
        if (currentHearts < ctx.lastHearts) {
            r -= 1.0f; // Strongly penalize damage
        } else if (currentHearts > ctx.lastHearts) {
            r += 0.5f; // Reward healing
        }

        // Inventory Pickup
        if (currentRupees > ctx.lastRupees) r += 0.1f;
        if (currentKeys > ctx.lastKeys) r += 0.5f;
        if (currentBombs > ctx.lastBombs) r += 0.2f;

        // Exploration Reward
        if (!ctx.visitedScreens.contains(currentMapPos)) {
            r += 1.0f; // Substantial reward for finding a new screen
            ctx.visitedScreens.add(currentMapPos);
        }

        // Survival (small positive to encourage staying alive without damage)
        r += 0.001f;

        // Update last state
        ctx.lastHearts = currentHearts;
        ctx.lastRupees = currentRupees;
        ctx.lastKeys = currentKeys;
        ctx.lastBombs = currentBombs;

        return r;
    }

}
