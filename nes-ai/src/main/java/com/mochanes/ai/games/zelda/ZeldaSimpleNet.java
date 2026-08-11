package com.mochanes.ai.games.zelda;

import ai.djl.nn.Block;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.core.Linear;
import ai.djl.nn.Activation;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.training.ParameterStore;
import ai.djl.util.PairList;
import ai.djl.nn.AbstractBlock;

/**
 * A lightweight Multi-Layer Perceptron (MLP) for Zelda.
 * Inputs: ~57 floats (ZeldaFeatureExtractor)
 * Outputs:
 * - Policy Logits (8 actions)
 * - Value Estimate (1 float)
 */
public class ZeldaSimpleNet extends AbstractBlock {

    private static final byte VERSION = 1;

    private Block sharedBody;
    private Block actorHead;
    private Block criticHead;

    public ZeldaSimpleNet() {
        super(VERSION);

        // Shared trunk
        this.sharedBody = new SequentialBlock()
                .add(Linear.builder().setUnits(512).build())
                .add(Activation::relu)
                .add(Linear.builder().setUnits(512).build())
                .add(Activation::relu);

        // Actor Head
        this.actorHead = new SequentialBlock()
                .add(Linear.builder().setUnits(256).build())
                .add(Activation::relu)
                .add(Linear.builder().setUnits(8).build()); // 8 Actions

        // Critic Head
        this.criticHead = new SequentialBlock()
                .add(Linear.builder().setUnits(256).build())
                .add(Activation::relu)
                .add(Linear.builder().setUnits(1).build());

        addChildBlock("sharedBody", sharedBody);
        addChildBlock("actorHead", actorHead);
        addChildBlock("criticHead", criticHead);
    }

    @Override
    protected NDList forwardInternal(ParameterStore parameterStore, NDList inputs, boolean training,
            PairList<String, Object> params) {
        NDList bodyOut = sharedBody.forward(parameterStore, inputs, training, params);

        NDList actorOut = actorHead.forward(parameterStore, bodyOut, training, params);
        NDList criticOut = criticHead.forward(parameterStore, bodyOut, training, params);

        // Return [PolicyLogits, Value]
        return new NDList(actorOut.singletonOrThrow(), criticOut.singletonOrThrow());
    }

    @Override
    public ai.djl.ndarray.types.Shape[] getOutputShapes(ai.djl.ndarray.types.Shape[] inputShapes) {
        // Assume input is [Batch, 57]
        long batchSize = inputShapes[0].get(0);
        return new ai.djl.ndarray.types.Shape[] {
                new ai.djl.ndarray.types.Shape(batchSize, 8),
                new ai.djl.ndarray.types.Shape(batchSize, 1)
        };
    }

    @Override
    public void initializeChildBlocks(NDManager manager, ai.djl.ndarray.types.DataType dataType,
            ai.djl.ndarray.types.Shape... inputShapes) {
        sharedBody.initialize(manager, dataType, inputShapes);

        ai.djl.ndarray.types.Shape[] bodyOutputShapes = sharedBody.getOutputShapes(inputShapes);

        actorHead.initialize(manager, dataType, bodyOutputShapes);
        criticHead.initialize(manager, dataType, bodyOutputShapes);
    }
}
