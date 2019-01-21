/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ccr.index.engine;

import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.seqno.SequenceNumbers;

/**
 * Moved preflight and assertPrimaryIncomingSequenceNumber check to its own class,
 * so that when testing writing directly into a follower index,
 * only these assertions here need to be disabled instead of all assertions in FollowingEngine class.
 */
final class FollowingEngineAssertions {

    static boolean preFlight(final Engine.Operation operation) {
        /*
         * We assert here so that this goes uncaught in unit tests and fails nodes in standalone tests (we want a harsh failure so that we
         * do not have a situation where a shard fails and is recovered elsewhere and a test subsequently passes). We throw an exception so
         * that we also prevent issues in production code.
         */
        assert operation.seqNo() != SequenceNumbers.UNASSIGNED_SEQ_NO;
        return true;
    }

    static boolean assertPrimaryIncomingSequenceNumber(final Engine.Operation.Origin origin, final long seqNo) {
        // sequence number should be set when operation origin is primary
        assert seqNo != SequenceNumbers.UNASSIGNED_SEQ_NO : "primary operations on a following index must have an assigned sequence number";
        return true;
    }

}