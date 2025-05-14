/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.routing.allocation.allocator;

import org.apache.lucene.tests.util.LuceneTestCase;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;

import java.util.Random;
import java.util.TreeMap;

@LuceneTestCase.SuppressFileSystems(value = "HandleLimitFS")
@ESIntegTestCase.ClusterScope(maxNumDataNodes = 3)
public class DesiredBalanceConvergenceIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal, otherSettings)).put("cluster.max_shards_per_node", "20000").build();
    }

    @Override
    protected Settings.Builder setRandomIndexSettings(Random random, Settings.Builder builder) {
        return builder;
    }

    @TestLogging(reason = "nocommit", value = "org.elasticsearch.cluster.routing.allocation.allocator.DesiredBalanceComputer:DEBUG")
    public void test10kShardsGrowingFrom3To6Nodes() {
        internalCluster().ensureAtLeastNumDataNodes(3);
        int shardCount = 0;
        int indexCount = 0;
        while (shardCount < 50_000) {
//            final var shards = between(1, 1000);
//            final var replicas = between(0, 2);
            final var shards = 1024;
            final var replicas = 2;
            safeGet(
                indicesAdmin().prepareCreate("index-" + indexCount)
                    .setSettings(indexSettings(shards, replicas).build())
                    .setWaitForActiveShards(ActiveShardCount.NONE)
                    .execute()
            );
            indexCount += 1;
            shardCount += shards * (replicas + 1);
            logger.info("indexCount={}, shardCount={}", indexCount, shardCount);
        }
        final var finalShardCount = shardCount;
        ensureGreen(TimeValue.timeValueMinutes(10));
        internalCluster().startNodes(3);
        safeAwait(ClusterServiceUtils.addTemporaryStateListener(cs -> {
            final var routingNodes = cs.getRoutingNodes();
            final var nodeSizes = new TreeMap<String, Integer>();
            var minNodeSize = Integer.MAX_VALUE;
            for (final var routingNode : routingNodes) {
                nodeSizes.put(routingNode.node().getName(), routingNode.size());
                minNodeSize = Math.min(minNodeSize, routingNode.size());
            }
            final var threshold = finalShardCount / (routingNodes.size() * 2);
            logger.info("nodeSizes={}, minNodeSize={}, threshold={}", nodeSizes, minNodeSize, threshold);
            return minNodeSize >= threshold;
        }));
        ensureGreen(TimeValue.timeValueMinutes(100));
    }
}
