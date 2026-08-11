# MochaNES AI Module

This module (`nes-ai`) contains the Reinforcement Learning (RL) agents and neural network architectures used to train the "Ghost Scout".

## Features

*   **PPO Agent**: Proximal Policy Optimization implementation for continuous learning.
*   **Deep Java Library (DJL)**: Uses PyTorch backend for neural network inference and training.
*   **Curiosity Engine (ICM)**: Intrinsic Curiosity Module that rewards the agent for discovering unpredictable states.
*   **HeadlessNES**: A high-performance wrapper around `nes-emulator` for running thousands of simulation steps per second.

## Documentation
See [AI System Guide](../docs/AI_SYSTEM.md) for the training manual and architecture details.

## Training (GPU Accelerated)
To start the training loop with CUDA support:
```bash
../train_gpu.bat
```
(See root [README](../README.md) for configuration details).

