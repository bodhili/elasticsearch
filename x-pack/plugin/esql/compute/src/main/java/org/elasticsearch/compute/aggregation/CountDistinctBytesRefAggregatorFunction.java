/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.compute.aggregation;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.AggregatorStateVector;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;

/**
 * {@link AggregatorFunction} implementation for {@link CountDistinctBytesRefAggregator}.
 */
public final class CountDistinctBytesRefAggregatorFunction implements AggregatorFunction {
    private final HllStates.SingleState state;

    private final int channel;

    public CountDistinctBytesRefAggregatorFunction(int channel, HllStates.SingleState state) {
        this.channel = channel;
        this.state = state;
    }

    public static CountDistinctBytesRefAggregatorFunction create(int channel) {
        return new CountDistinctBytesRefAggregatorFunction(channel, CountDistinctBytesRefAggregator.initSingle());
    }

    @Override
    public void addRawInput(Page page) {
        assert channel >= 0;
        ElementType type = page.getBlock(channel).elementType();
        if (type == ElementType.NULL) {
            return;
        }
        BytesRefBlock block = page.getBlock(channel);
        BytesRefVector vector = block.asVector();
        if (vector != null) {
            addRawVector(vector);
        } else {
            addRawBlock(block);
        }
    }

    private void addRawVector(BytesRefVector vector) {
        var scratch = new BytesRef();
        for (int i = 0; i < vector.getPositionCount(); i++) {
            CountDistinctBytesRefAggregator.combine(state, vector.getBytesRef(i, scratch));
        }
    }

    private void addRawBlock(BytesRefBlock block) {
        var scratch = new BytesRef();
        for (int p = 0; p < block.getTotalValueCount(); p++) {
            if (block.isNull(p) == false) {
                int i = block.getFirstValueIndex(p);
                CountDistinctBytesRefAggregator.combine(state, block.getBytesRef(i, scratch));
            }
        }
    }

    @Override
    public void addIntermediateInput(Block block) {
        assert channel == -1;
        Vector vector = block.asVector();
        if (vector == null || vector instanceof AggregatorStateVector == false) {
            throw new RuntimeException("expected AggregatorStateBlock, got:" + block);
        }
        @SuppressWarnings("unchecked")
        AggregatorStateVector<HllStates.SingleState> blobVector = (AggregatorStateVector<HllStates.SingleState>) vector;
        HllStates.SingleState tmpState = new HllStates.SingleState();
        for (int i = 0; i < block.getPositionCount(); i++) {
            blobVector.get(i, tmpState);
            CountDistinctBytesRefAggregator.combineStates(state, tmpState);
        }
    }

    @Override
    public Block evaluateIntermediate() {
        AggregatorStateVector.Builder<AggregatorStateVector<HllStates.SingleState>, HllStates.SingleState> builder = AggregatorStateVector
            .builderOfAggregatorState(HllStates.SingleState.class, state.getEstimatedSize());
        builder.add(state, IntVector.range(0, 1));
        return builder.build().asBlock();
    }

    @Override
    public Block evaluateFinal() {
        return CountDistinctBytesRefAggregator.evaluateFinal(state);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName()).append("[");
        sb.append("channel=").append(channel);
        sb.append("]");
        return sb.toString();
    }
}
