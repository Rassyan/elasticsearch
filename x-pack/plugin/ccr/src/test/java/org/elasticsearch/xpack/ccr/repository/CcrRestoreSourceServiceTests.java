/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ccr.repository;

import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.concurrent.DeterministicTaskQueue;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineTestCase;
import org.elasticsearch.index.shard.IllegalIndexShardStateException;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardTestCase;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.store.StoreFileMetadata;
import org.elasticsearch.xpack.ccr.CcrSettings;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsString;

public class CcrRestoreSourceServiceTests extends IndexShardTestCase {

    private CcrRestoreSourceService restoreSourceService;
    private DeterministicTaskQueue taskQueue;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        taskQueue = new DeterministicTaskQueue();
        ClusterSettings clusterSettings = new ClusterSettings(
            Settings.EMPTY,
            CcrSettings.getSettings().stream().filter(s -> s.hasNodeScope()).collect(Collectors.toSet())
        );
        restoreSourceService = new CcrRestoreSourceService(taskQueue.getThreadPool(), new CcrSettings(Settings.EMPTY, clusterSettings));
    }

    @After
    public void assertWarnings() {
        assertWarnings(
            "[indices.merge.scheduler.use_thread_pool] setting was deprecated in Elasticsearch and will be removed in a future release. "
                + "See the breaking changes documentation for the next major version."
        );
    }

    public void testOpenSession() throws IOException {
        IndexShard indexShard1 = newStartedShard(true);
        IndexShard indexShard2 = newStartedShard(true);
        final String sessionUUID1 = UUIDs.randomBase64UUID();
        final String sessionUUID2 = UUIDs.randomBase64UUID();
        final String sessionUUID3 = UUIDs.randomBase64UUID();

        restoreSourceService.openSession(sessionUUID1, indexShard1);
        restoreSourceService.openSession(sessionUUID2, indexShard1);

        try (
            CcrRestoreSourceService.SessionReader reader1 = restoreSourceService.getSessionReader(sessionUUID1);
            CcrRestoreSourceService.SessionReader reader2 = restoreSourceService.getSessionReader(sessionUUID2)
        ) {
            // Would throw exception if missing
        }

        restoreSourceService.openSession(sessionUUID3, indexShard2);

        try (
            CcrRestoreSourceService.SessionReader reader1 = restoreSourceService.getSessionReader(sessionUUID1);
            CcrRestoreSourceService.SessionReader reader2 = restoreSourceService.getSessionReader(sessionUUID2);
            CcrRestoreSourceService.SessionReader reader3 = restoreSourceService.getSessionReader(sessionUUID3)
        ) {
            // Would throw exception if missing
        }

        restoreSourceService.closeSession(sessionUUID1);
        restoreSourceService.closeSession(sessionUUID2);
        restoreSourceService.closeSession(sessionUUID3);

        closeShards(indexShard1, indexShard2);
    }

    public void testCannotOpenSessionForClosedShard() throws IOException {
        IndexShard indexShard = newStartedShard(true);
        closeShards(indexShard);
        String sessionUUID = UUIDs.randomBase64UUID();
        expectThrows(IllegalIndexShardStateException.class, () -> restoreSourceService.openSession(sessionUUID, indexShard));
    }

    public void testCloseSession() throws IOException {
        IndexShard indexShard1 = newStartedShard(true);
        IndexShard indexShard2 = newStartedShard(true);
        final String sessionUUID1 = UUIDs.randomBase64UUID();
        final String sessionUUID2 = UUIDs.randomBase64UUID();
        final String sessionUUID3 = UUIDs.randomBase64UUID();

        restoreSourceService.openSession(sessionUUID1, indexShard1);
        restoreSourceService.openSession(sessionUUID2, indexShard1);
        restoreSourceService.openSession(sessionUUID3, indexShard2);

        try (
            CcrRestoreSourceService.SessionReader reader1 = restoreSourceService.getSessionReader(sessionUUID1);
            CcrRestoreSourceService.SessionReader reader2 = restoreSourceService.getSessionReader(sessionUUID2);
            CcrRestoreSourceService.SessionReader reader3 = restoreSourceService.getSessionReader(sessionUUID3)
        ) {
            // Would throw exception if missing
        }

        assertTrue(taskQueue.hasDeferredTasks());

        restoreSourceService.closeSession(sessionUUID1);
        expectThrows(IllegalArgumentException.class, () -> restoreSourceService.getSessionReader(sessionUUID1));

        restoreSourceService.closeSession(sessionUUID2);
        expectThrows(IllegalArgumentException.class, () -> restoreSourceService.getSessionReader(sessionUUID2));

        restoreSourceService.closeSession(sessionUUID3);
        expectThrows(IllegalArgumentException.class, () -> restoreSourceService.getSessionReader(sessionUUID3));

        taskQueue.runAllTasks();
        // The tasks will not be rescheduled as the sessions are closed.
        assertFalse(taskQueue.hasDeferredTasks());

        closeShards(indexShard1, indexShard2);
    }

    public void testCloseShardListenerFunctionality() throws IOException {
        IndexShard indexShard1 = newStartedShard(true);
        IndexShard indexShard2 = newStartedShard(true);
        final String sessionUUID1 = UUIDs.randomBase64UUID();
        final String sessionUUID2 = UUIDs.randomBase64UUID();
        final String sessionUUID3 = UUIDs.randomBase64UUID();

        restoreSourceService.openSession(sessionUUID1, indexShard1);
        restoreSourceService.openSession(sessionUUID2, indexShard1);
        restoreSourceService.openSession(sessionUUID3, indexShard2);

        try (
            CcrRestoreSourceService.SessionReader reader1 = restoreSourceService.getSessionReader(sessionUUID1);
            CcrRestoreSourceService.SessionReader reader2 = restoreSourceService.getSessionReader(sessionUUID2);
            CcrRestoreSourceService.SessionReader reader3 = restoreSourceService.getSessionReader(sessionUUID3)
        ) {
            // Would throw exception if missing
        }

        restoreSourceService.afterIndexShardClosed(indexShard1.shardId(), indexShard1, Settings.EMPTY);

        expectThrows(IllegalArgumentException.class, () -> restoreSourceService.getSessionReader(sessionUUID1));
        expectThrows(IllegalArgumentException.class, () -> restoreSourceService.getSessionReader(sessionUUID2));

        try (CcrRestoreSourceService.SessionReader reader3 = restoreSourceService.getSessionReader(sessionUUID3)) {
            // Would throw exception if missing
        }

        restoreSourceService.closeSession(sessionUUID3);
        closeShards(indexShard1, indexShard2);
    }

    public void testGetSessionReader() throws IOException {
        IndexShard indexShard1 = newStartedShard(true);
        final String sessionUUID1 = UUIDs.randomBase64UUID();

        restoreSourceService.openSession(sessionUUID1, indexShard1);

        ArrayList<StoreFileMetadata> files = new ArrayList<>();
        indexShard1.snapshotStoreMetadata().forEach(files::add);

        StoreFileMetadata fileMetadata = files.get(0);
        String fileName = fileMetadata.name();

        byte[] expectedBytes = new byte[(int) fileMetadata.length()];
        var actualBytes = BigArrays.NON_RECYCLING_INSTANCE.newByteArray(fileMetadata.length(), false);
        try (
            Engine.IndexCommitRef indexCommitRef = indexShard1.acquireSafeIndexCommit();
            IndexInput indexInput = indexCommitRef.getIndexCommit().getDirectory().openInput(fileName, IOContext.READONCE)
        ) {
            indexInput.seek(0);
            indexInput.readBytes(expectedBytes, 0, (int) fileMetadata.length());
        }

        try (CcrRestoreSourceService.SessionReader sessionReader = restoreSourceService.getSessionReader(sessionUUID1)) {
            long offset = sessionReader.readFileBytes(fileName, actualBytes);
            assertEquals(offset, fileMetadata.length());
        }

        assertTrue(actualBytes.hasArray());
        assertArrayEquals(expectedBytes, actualBytes.array());
        restoreSourceService.closeSession(sessionUUID1);
        closeShards(indexShard1);
    }

    public void testGetSessionDoesNotLeakFileIfClosed() throws IOException {
        Settings settings = Settings.builder().put("index.merge.enabled", false).build();
        IndexShard indexShard = newStartedShard(true, settings);
        for (int i = 0; i < 5; i++) {
            indexDoc(indexShard, "_doc", Integer.toString(i));
            flushShard(indexShard, true);
        }
        final String sessionUUID = UUIDs.randomBase64UUID();

        restoreSourceService.openSession(sessionUUID, indexShard);

        ArrayList<StoreFileMetadata> files = new ArrayList<>();
        indexShard.snapshotStoreMetadata().forEach(files::add);

        try (CcrRestoreSourceService.SessionReader sessionReader = restoreSourceService.getSessionReader(sessionUUID)) {
            sessionReader.readFileBytes(files.get(0).name(), MockBigArrays.NON_RECYCLING_INSTANCE.newByteArray(10, false));
        }

        // Request a second file to ensure that original file is not leaked
        try (CcrRestoreSourceService.SessionReader sessionReader = restoreSourceService.getSessionReader(sessionUUID)) {
            sessionReader.readFileBytes(files.get(1).name(), MockBigArrays.NON_RECYCLING_INSTANCE.newByteArray(10, false));
        }

        assertTrue(EngineTestCase.hasAcquiredIndexCommitsForTesting(IndexShardTestCase.getEngine(indexShard)));
        restoreSourceService.closeSession(sessionUUID);
        assertFalse(EngineTestCase.hasAcquiredIndexCommitsForTesting(IndexShardTestCase.getEngine(indexShard)));

        closeShards(indexShard);
        // Exception will be thrown if file is not closed.
    }

    public void testSessionCanTimeout() throws Exception {
        IndexShard indexShard = newStartedShard(true);

        final String sessionUUID = UUIDs.randomBase64UUID();

        restoreSourceService.openSession(sessionUUID, indexShard);

        // Session starts as not idle. First task will mark it as idle
        assertTrue(taskQueue.hasDeferredTasks());
        taskQueue.advanceTime();
        taskQueue.runAllRunnableTasks();
        // Task is still scheduled
        assertTrue(taskQueue.hasDeferredTasks());

        // Accessing session marks it as not-idle
        try (CcrRestoreSourceService.SessionReader reader = restoreSourceService.getSessionReader(sessionUUID)) {
            // Check session exists
        }

        assertTrue(taskQueue.hasDeferredTasks());
        taskQueue.advanceTime();
        taskQueue.runAllRunnableTasks();
        // Task is still scheduled
        assertTrue(taskQueue.hasDeferredTasks());

        taskQueue.advanceTime();
        taskQueue.runAllRunnableTasks();
        // Task is cancelled when the session times out
        assertFalse(taskQueue.hasDeferredTasks());

        expectThrows(IllegalArgumentException.class, () -> restoreSourceService.getSessionReader(sessionUUID));

        closeShards(indexShard);
    }

    public void testConsistencyBetweenRequestAndSession() throws IOException {
        IndexShard indexShard = newStartedShard(true);

        final String sessionUUID = UUIDs.randomBase64UUID();
        final Store.MetadataSnapshot metadata = restoreSourceService.openSession(sessionUUID, indexShard);
        final Set<String> knownFileNames = metadata.fileMetadataMap().keySet();

        final String anotherSessionUUID = UUIDs.randomBase64UUID();

        final IllegalArgumentException e1 = expectThrows(
            IllegalArgumentException.class,
            () -> restoreSourceService.ensureSessionShardIdConsistency(anotherSessionUUID, indexShard.shardId())
        );
        assertThat(e1.getMessage(), containsString("session [" + anotherSessionUUID + "] not found"));

        final IllegalArgumentException e2 = expectThrows(
            IllegalArgumentException.class,
            () -> restoreSourceService.ensureFileNameIsKnownToSession(anotherSessionUUID, randomFrom(knownFileNames))
        );
        assertThat(e2.getMessage(), containsString("session [" + anotherSessionUUID + "] not found"));

        final ShardId anotherShardId = new ShardId(randomAlphaOfLengthBetween(3, 12), randomAlphaOfLength(20), randomIntBetween(0, 3));
        final IllegalArgumentException e3 = expectThrows(
            IllegalArgumentException.class,
            () -> restoreSourceService.ensureSessionShardIdConsistency(sessionUUID, anotherShardId)
        );
        assertThat(e3.getMessage(), containsString("does not match requested shardId [" + anotherShardId + "]"));

        final String anotherFileName = randomValueOtherThanMany(knownFileNames::contains, () -> randomAlphaOfLengthBetween(3, 12));
        final IllegalArgumentException e4 = expectThrows(
            IllegalArgumentException.class,
            () -> restoreSourceService.ensureFileNameIsKnownToSession(sessionUUID, anotherFileName)
        );
        assertThat(e4.getMessage(), containsString("invalid file name [" + anotherFileName + "]"));

        try {
            restoreSourceService.ensureSessionShardIdConsistency(sessionUUID, indexShard.shardId());
        } catch (Exception e) {
            fail("should have succeeded, but got [" + e + "]");
        }

        try {
            restoreSourceService.ensureFileNameIsKnownToSession(sessionUUID, randomFrom(knownFileNames));
        } catch (Exception e) {
            fail("should have succeeded, but got [" + e + "]");
        }

        restoreSourceService.closeSession(sessionUUID);
        closeShards(indexShard);
    }
}
