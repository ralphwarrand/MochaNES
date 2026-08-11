package com.mochanes.ai.games.zelda;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.GradientCollector;
import ai.djl.training.Trainer;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;

public class ZeldaAgent {

    private final NDManager manager;
    private final Model model;
    private final Trainer trainer;
    private final Random rng;

    // Hyperparams
    private final float learningRate = 0.0003f;
    private final float clipParam = 0.2f;

    // Action Space Mapping (Index -> Bitmask)
    // 0:Up, 1:Down, 2:Left, 3:Right, 4:A, 5:B, 6:Up+A, 7:NoOp
    private static final int[] ACTION_SPACE = {
            16, // Up
            32, // Down
            64, // Left
            128, // Right
            1, // A
            2, // B
            17, // Up+A (Combo)
            0 // NoOp
    };

    // ThreadLocal Predictor to avoid synchronization on inference
    private final ThreadLocal<ai.djl.inference.Predictor<float[], StepResult>> predictorHolder;

    public ZeldaAgent() {
        this.manager = NDManager.newBaseManager();
        this.rng = new Random();
        this.model = Model.newInstance("zelda-agent");
        this.model.setBlock(new ZeldaSimpleNet());

        // Setup Training
        Tracker lrs = Tracker.fixed(learningRate);
        Optimizer adam = Optimizer.adam().optLearningRateTracker(lrs).build();

        DefaultTrainingConfig config = new DefaultTrainingConfig(Loss.l2Loss())
                .optOptimizer(adam);

        this.trainer = model.newTrainer(config);

        // Initialize shape [1, INPUT_SIZE]
        this.trainer.initialize(new Shape(1, ZeldaFeatureExtractor.INPUT_SIZE));

        // Init Predictor ThreadLocal
        this.predictorHolder = ThreadLocal.withInitial(() -> model.newPredictor(new ZeldaTranslator()));
    }

    public StepResult act(float[] stateVector) {
        try {
            return predictorHolder.get().predict(stateVector);
        } catch (Exception e) {
            throw new RuntimeException("Inference failed", e);
        }
    }

    // Replaces the synchronized act logic with a Translator approach
    private class ZeldaTranslator implements ai.djl.translate.Translator<float[], StepResult> {
        @Override
        public NDList processInput(ai.djl.translate.TranslatorContext ctx, float[] input) {
            return new NDList(ctx.getNDManager().create(input).reshape(1, input.length));
        }

        @Override
        public StepResult processOutput(ai.djl.translate.TranslatorContext ctx, NDList list) {
            NDArray logits = list.get(0); // [1, 8]
            float value = list.get(1).getFloat();

            NDArray probs = logits.softmax(1);
            float[] probArray = probs.toFloatArray();

            int actionIdx = sample(probArray);
            float logProb = (float) Math.log(probArray[actionIdx] + 1e-8);

            return new StepResult(ACTION_SPACE[actionIdx], actionIdx, logProb, value);
        }

        @Override // Required for some DJL versions
        public ai.djl.translate.Batchifier getBatchifier() {
            return null; // We manual batch or single
        }
    }

    private int sample(float[] probs) {
        double r = java.util.concurrent.ThreadLocalRandom.current().nextDouble(); // Faster concurrent random
        double cum = 0.0;
        for (int i = 0; i < probs.length; i++) {
            cum += probs[i];
            if (r <= cum)
                return i;
        }
        return probs.length - 1;
    }

    public TrainingMetrics train(List<ZeldaExperience> experiences) {
        if (experiences.isEmpty())
            return new TrainingMetrics(0, 0, 0);

        int batchSize = experiences.size();

        // Convert List to Arrays
        float[] allStates = new float[batchSize * ZeldaFeatureExtractor.INPUT_SIZE];
        int[] allActions = new int[batchSize];
        float[] allReturns = new float[batchSize]; // Target Value
        float[] allAdvs = new float[batchSize];
        float[] allOldLogProbs = new float[batchSize];

        for (int i = 0; i < batchSize; i++) {
            ZeldaExperience ex = experiences.get(i);
            System.arraycopy(ex.state, 0, allStates, i * ZeldaFeatureExtractor.INPUT_SIZE, ex.state.length);
            allActions[i] = ex.actionIdx;
            allReturns[i] = ex.returnTarget;
            allAdvs[i] = ex.advantage;
            allOldLogProbs[i] = ex.logProb;
        }

        // Update (PPO)
        // For simplicity, 1 Epoch for now. Can loop here.
        try (GradientCollector collector = trainer.newGradientCollector()) {
            NDManager sub = manager.newSubManager();

            NDArray states = sub.create(allStates, new Shape(batchSize, ZeldaFeatureExtractor.INPUT_SIZE));
            NDArray actions = sub.create(allActions);
            NDArray returns = sub.create(allReturns);
            NDArray advs = sub.create(allAdvs);
            NDArray oldLogProbs = sub.create(allOldLogProbs);

            // Forward
            NDList out = trainer.forward(new NDList(states));
            NDArray logits = out.get(0); // [Batch, 8]
            NDArray values = out.get(1).reshape(batchSize);

            // Calc New Log Probs
            NDArray probs = logits.softmax(1);
            NDArray actionOneHot = actions.oneHot(8).toType(ai.djl.ndarray.types.DataType.FLOAT32, false);
            NDArray activeProbs = probs.mul(actionOneHot).sum(new int[] { 1 });
            NDArray newLogProbs = activeProbs.log();

            // Ratio
            NDArray ratio = newLogProbs.sub(oldLogProbs).exp();

            // Surrogate Loss
            NDArray surr1 = ratio.mul(advs);
            NDArray surr2 = ratio.clip(1.0f - clipParam, 1.0f + clipParam).mul(advs);
            NDArray actorLoss = surr1.minimum(surr2).neg().mean();

            // Critic Loss
            NDArray criticLoss = values.sub(returns).pow(2).mean().mul(0.5f);

            // Entropy (Valid Entropy)
            // sum(-p * log p)
            NDArray logSoftmax = logits.logSoftmax(1);
            NDArray entropy = probs.mul(logSoftmax).sum(new int[] { 1 }).neg().mean().mul(0.01f).neg(); // Maximize
                                                                                                        // entropy

            NDArray loss = actorLoss.add(criticLoss).add(entropy);

            collector.backward(loss);
            trainer.step();

            float al = actorLoss.getFloat();
            float cl = criticLoss.getFloat();
            float ent = entropy.getFloat();

            sub.close();
            return new TrainingMetrics(al, cl, ent);
        }
    }

    public static class TrainingMetrics {
        public float actorLoss;
        public float criticLoss;
        public float entropy;

        public TrainingMetrics(float a, float c, float e) {
            this.actorLoss = a;
            this.criticLoss = c;
            this.entropy = e;
        }
    }

    public void save(String path) {
        try {
            model.save(Paths.get(path), "zelda-agent");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void load(String path) {
        try {
            model.load(Paths.get(path), "zelda-agent");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static class StepResult {
        public int bitmask;
        public int actionIdx;
        public float logProb;
        public float value;

        public StepResult(int b, int a, float l, float v) {
            bitmask = b;
            actionIdx = a;
            logProb = l;
            value = v;
        }
    }

    public static class ZeldaExperience {
        public float[] state;
        public int actionIdx;
        public float reward;
        public float value;
        public float logProb;
        public float returnTarget;
        public float advantage;

        public ZeldaExperience(float[] s, int a, float r, float v, float lp) {
            state = s;
            actionIdx = a;
            reward = r;
            value = v;
            logProb = lp;
        }
    }
}
