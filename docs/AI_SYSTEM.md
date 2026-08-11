# AI System Technical Manual

## 1. Introduction: The Purist Agent
The **MochaNES AI** utilizes a custom implementation of **Proximal Policy Optimization (PPO)** driven by **Intrinsic Curiosity**. It is a "Purist" system:
*   **Input**: Raw Pixels (120x128 Grayscale). No RAM access.
*   **Output**: Joypad Button Combinations (Discrete 256).
*   **Motivation**: Pure Curiosity (ICM Prediction Error). Zero external game reward.

---

## 2. Neural Architecture

### 2.1 The "Intuition Network" (CNN)
The network (`DJLIntuitionNetwork.java`) processes the visual input.

**Input Shape**: `[Batch, 4, 120, 128]`
*   **4 Stacked Frames**: Represents motion/velocity. Frame $t, t-4, t-8, t-12$.
*   **Resolution**: Downsampled from 256x240 to 128x120 to save VRAM while preserving 8x8 tile details.

**The Backbone (Feature Extractor)**:
1.  **Conv2d**: 32 Filters, 8x8 Kernel, Stride 4. (Receptive Field: Large) -> ReLU.
2.  **Conv2d**: 64 Filters, 4x4 Kernel, Stride 2. (Receptive Field: Medium) -> ReLU.
3.  **Conv2d**: 64 Filters, 3x3 Kernel, Stride 1. (Receptive Field: Fine) -> ReLU.
4.  **Flatten**: Converts 3D maps to 1D vector (Size: 3136).

**The Heads**:
1.  **Actor (Policy)**: `Linear(512) -> ReLU -> Linear(256) -> Softmax`.
    *   Initialize: Orthogonal (Gain 0.01) -> High Entropy (~5.54).
2.  **Critic (Value)**: `Linear(512) -> ReLU -> Linear(1)`.
    *   Initialize: Orthogonal (Gain 1.0).

### 2.2 The Curiosity Module (ICM)
Based on Pathak et al. (2017).
1.  **Encoder ($\phi$)**: A Siamesed CNN (same structure as Backbone) encoding State $S_t$ into Latent Feature $\phi(S_t)$.
2.  **Forward Model**: Predicts $\phi(S_{t+1})$ given $\phi(S_t)$ and Action $A_t$.
    *   Loss: MSE($\phi_{pred}, \phi_{real}$).
    *   Reward: $\eta \times \text{Loss}$.
3.  **Inverse Model**: Predicts Action $A_t$ given $\phi(S_t)$ and $\phi(S_{t+1})$.
    *   Result: Forces $\phi$ to capture only controllable aspects of the screen.

---

## 3. Algorithm: PPO with GAE

We calculate gradients using the **Clipped Surrogate Objective**:


$$ L^{CLIP}(\theta) = \hat{\mathbb{E}}_t \left[ \min(r_t(\theta)\hat{A}_t, \text{clip}(r_t(\theta), 1-\epsilon, 1+\epsilon)\hat{A}_t) \right] $$

*   $r_t(\theta)$: Probability Ratio $\frac{\pi_{new}(a|s)}{\pi_{old}(a|s)}$.
*   $\epsilon$: Clip Parameter (0.2). Ensures stability by limiting update size.
*   $\hat{A}_t$: Generalized Advantage Estimate (GAE).
    *   $\lambda = 0.95$ (GAE smoothing factor).
    *   $\gamma = 0.99$ (Discount factor).

---

## 4. Episodic Memory (The Hippocampus)

To solve the "Noisy TV Problem" and "Novelty Loops", we augment ICM with a counting mechanism.

### 4.1 Perceptual Hashing
1.  **Downsample**: Screen -> 42x40 Thumbnail.
2.  **Hash**: MurmurHash3 (32-bit of the int[] array).
3.  **Storage**: A persistent `LongDoubleHashMap` (Hash -> VisitCount).

### 4.2 The "Boredom" Formula
The final intrinsic reward $r_{final}$ is modulated by familiarity:

$$ r_{final} = \frac{r_{icm}}{\sqrt{N_{visits}}} $$

*   $N=1$: Reward = 100%.
*   $N=4$: Reward = 50%.
*   $N \to \infty$: Reward $\to 0$.

### 4.3 The "Shock Collar" (Short-Term Memory)
We maintain a rolling buffer (Deque) of the last 120 seconds (7200 frames).
*   If `CurrentHash` exists in `ShortTermMemory`:
    *   Apply Penalty: `-0.5`.
*   **Result**: The agent is physically punished for "backtracking" or looping in menus.

---

---

## 5. The Training Lifecycle

The training process is a **Synchronous Parallel** loop managed by `OnlineTrainer.java`.

### 5.1 The Loop (Step-by-Step)
1.  **Rollout (Assessment)**:
    *   16 independent NES Emulators run in parallel on the CPU (`ThreadPool`).
    *   Each agent plays for **128 frames** (`STEPS_PER_ENV`).
    *   **Action Selection**: The CPU queries the GPU Model (in batches) to decide what buttons to press.
    *   **Data Collection**: We store the tuple `(State, Action, Reward, Value, LogProb)` for every step.

2.  **Advantage Calculation (Hindsight)**:
    *   Once all 16 agents finish their 128 steps, the main thread pauses them.
    *   We calculate **GAE (Generalized Advantage Estimation)**.
    *   *Question asked*: "Was that move actually good, knowing what happened 100 frames later?"

3.  **Optimization (Learning)**:
    *   The collected experience (16 * 128 = 2048 samples) is moved to the **GPU**.
    *   We run **4 PPO Epochs**.
    *   In each epoch, the data is shuffled and split into **Mini-Batches (512)**.
    *   The Neural Network weights are updated to maximize the PPO Objective.

4.  **Synchronization**:
    *   The updated weights are copied from GPU VRAM back to the CPU RAM.
    *   The 16 Emulators resume playing with the new, slightly smarter brain.

### 5.2 Training Specs (Hyperparameters)

| Parameter | Value | Description |
|-----------|-------|-------------|
| `NUM_ENVS` | 16 | Parallel threads collecting data. |
| `STEPS_PER_ENV` | 128 | Frames per rollout batch (Total 2048). |
| `EPOCHS` | 4 | PPO update passes per batch. |
| `MINIBATCH` | 512 | Sub-batch size for GPU. |
| `LR` | 1.0e-4 | Learning Rate (Adam Optimizer). |
| `ENTROPY_COEFF` | 0.01 | Bonus for randomness (Verification). |
| `ORTHO_GAIN` | $\sqrt{2}$ | Weight Init for Backbone. |

---

## 6. Infrastructure

### 6.1 Parallelism
We use a `ThreadPoolExecutor` to step 16 `HeadlessNES` instances.
*   **Sync Step**: All environments step together.
*   **Thread Safety**: DJL Predictors are NOT thread-safe. We use a pool of Predictors or synchronized block for inference.

### 6.2 GPU Management
*   **Memory**: We use `NDManager.newSubManager()` for every batch to prevent VRAM leaks.
*   **Data Transfer**: We use specialized `FloatBuffer` transfers to minimize CPU-GPU latency.

---

## 7. Future Upgrades

### 7.1 Color Vision
*   **Plan**: Change Input Channel 1 (Gray) -> 3 (RGB).
*   **Impact**: 3x Input Size. Backbone must be widened.

### 7.2 LSTM / Transformer
*   **Plan**: Replace Frame Stacking (Memory 4 frames) with Recurrent State (Memory infinite).
*   **Impact**: Ability to remember "I have the key" across rooms.
