# MochaNES

**MochaNES** is a high-performance Java NES emulator and **AI Research Platform**.
It features a cycle-accurate 6502/PPU core and a state-of-the-art **Intrinsic Curiosity Agent (PPO + ICM)** that learns to play games from raw pixels, motivated solely by the desire to see new things.

> [!IMPORTANT]
> **New: GPU Acceleration Enabled!**
> The project now supports NVIDIA GPU Training (CUDA 12.1) via DJL/PyTorch.

---

## 📚 Documentation
*   **[AI System Guide](docs/AI_SYSTEM.md)**: Deep dive into the PPO Agent, Orthogonal Initialization, and Curiosity Rewards.
*   **[Architecture Guide](docs/Architecture.md)**: Details on the Emulator Core (CPU, PPU, Memory).
*   **[Tools Guide](docs/Tools_Guide.md)**: Manual for the Debugger and Performance Monitor.

---

## Features

### Emulator Core
- **Performance**: Optimized for >1,000,000 FPS headless execution (Fast PPU mode).
- **CPU/PPU**: Cycle-accurate 6502 & Scanline-based PPU (Loopy's Logic).
- **State Management**: O(1) State Cloning & Seeking for efficient AI rollouts.

### AI Capabilities (The "Ghost Scout")
- **Visual Learning**: Learns entirely from **Raw Pixels** (Purist Design). No RAM hacks.
- **PPO + ICM**: Proximal Policy Optimization driven by Intrinsic Curiosity (Prediction Error).
- **Parallel Training**: Runs 16+ emulators in parallel to collect experience.
- **Advanced Memory**: Uses Perceptual Hashing (Episodic Memory) to avoid "Novelty Loops".
- **Hardware Accelerated**: Uses DJL (Deep Java Library) + PyTorch + CUDA for massive throughput.

---

## 🚀 Getting Started

### Prerequisites
- **Java JDK 17+**
- **Maven 3.8+**
- **NVIDIA GPU** (Drivers >= 531.14 for CUDA 12.1) [Optional but Recommended]
- **Windows** (Preferred for GPU support)

### Quick Start: Train the AI
To start the Reinforcement Learning process on GPU:

```cmd
train_gpu.bat
```

*   **What this does**:
    1.  Compiles the project.
    2.  Configures `PATH` for DJL/CUDA dependencies.
    3.  Launches the **Online Trainer** (16 Parallel Environments).
*   **Visualize**: The Trainer will open a window showing Env 0.
*   **Config**: Edit `configs/ppo_default.properties` to tune Hyperparameters (Learning Rate, Gamma, Entropy).

### Play Manually
To play a ROM normally:
```cmd
run.bat resources/nestest.nes
```

### Replay & Analysis
To watch the AI's best performers or debug a training run:
```cmd
replay_last_run.bat
```
*   **HUD**: Displays Value Estimate, Action Probabilities, and Novelty signals in real-time.

---

## Project Structure

```
MochaNES/
├── nes-emulator/       # Core Emulation Logic (CPU, PPU, Mappers)
├── nes-ai/             # AI Module (PPO, ICM, Trainer, DJL Integration)
├── docs/               # Technical Documentation
├── configs/            # Hyperparameters (ppo_default.properties)
├── resources/          # ROMs and Test Data
├── train_gpu.bat       # Main Entry Point for Training
└── README.md           # This file
```
