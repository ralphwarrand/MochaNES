package com.mochanes.ai;

/**
 * Ghost Scout Entry Point.
 * Main class for launching AI Training/Inference modes.
 */
public class NESBotCLI {

    public static void main(String[] args) {
        // [DEBUG] Force strict headers and use System.err to bypass buffering
        System.err.println("=== NESBOT GPU DIAGNOSTICS ===");
        System.err.println("OS: " + System.getProperty("os.name"));
        System.err.println("Arch: " + System.getProperty("os.arch"));
        System.err.println("Java: " + System.getProperty("java.version"));

        // [GPU FIX] Pre-load zlibwapi.dll
        try {
            java.io.File lib = new java.io.File("zlibwapi.dll");
            System.err.println("Checking zlibwapi.dll in CWD: " + lib.getAbsolutePath());
            if (lib.exists()) {
                System.err.println("  - Exists: Yes");
                System.err.println("  - Size: " + lib.length() + " bytes");
                System.load(lib.getAbsolutePath());
                System.err.println("  - [SUCCESS] Loaded zlibwapi.dll from CWD.");
            } else {
                System.err.println("  - [FAIL] File not found in CWD.");
                // Try system load
                System.err.println("Attempting System.loadLibrary(\"zlibwapi\")...");
                System.loadLibrary("zlibwapi");
                System.err.println("  - [SUCCESS] Loaded via System/PATH.");
            }
        } catch (Throwable t) {
            System.err.println("  - [CRITICAL FAILURE] Could not load zlibwapi: " + t.getMessage());
            t.printStackTrace();
        }
        System.err.println("==================================");

        // Configure DJL Threading
        System.setProperty("ai.djl.pytorch.num_interop_threads", "1");
        System.setProperty("ai.djl.pytorch.num_intraop_threads", "1");
        System.setProperty("OMP_NUM_THREADS", "1");

        // MEMORY VISUALIZER REMOVED

        // ZELDA TRAINING ENTRY POINT
        if (args.length > 0 && args[0].equals("--train-zelda")) {
            if (args.length < 2) {
                System.err.println("Usage: GhostScout --train-zelda <rom_path> [output_dir] [num_envs]");
                System.exit(1);
            }
            String rom = args[1];
            String out = (args.length > 2) ? args[2] : "zelda_output";
            int envs = (args.length > 3) ? Integer.parseInt(args[3]) : 16;
            boolean watch = (args.length > 4) && Boolean.parseBoolean(args[4]);

            new com.mochanes.ai.games.zelda.ZeldaTrainer(rom, out, envs, watch).train();
            return;
        }

        // ZELDA PLAY ENTRY POINT
        if (args.length > 0 && args[0].equals("--play-zelda")) {
            if (args.length < 3) {
                System.err.println("Usage: GhostScout --play-zelda <rom_path> <model_path>");
                System.exit(1);
            }
            String rom = args[1];
            String model = args[2];

            new com.mochanes.ai.games.zelda.ZeldaPlayer(rom, model).play();
            return;
        }

        System.err.println(
                "Usage: GhostScout --train-zelda ... | --play-zelda <rom> <model>");
        System.exit(1);
    }
}
