/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.repositories.gcs;

import fixture.gcs.GoogleCloudStorageHttpFixture;
import fixture.gcs.TestUtils;

import com.google.cloud.storage.StorageException;

import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.SecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.Booleans;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.AbstractThirdPartyRepositoryTestCase;
import org.elasticsearch.repositories.blobstore.BlobStoreRepository;
import org.elasticsearch.rest.RestStatus;
import org.junit.ClassRule;

import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.Base64;
import java.util.Collection;

import static org.elasticsearch.common.io.Streams.readFully;
import static org.elasticsearch.repositories.blobstore.BlobStoreTestUtil.randomPurpose;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class GoogleCloudStorageThirdPartyTests extends AbstractThirdPartyRepositoryTestCase {
    private static final boolean USE_FIXTURE = Booleans.parseBoolean(System.getProperty("test.google.fixture", "true"));

    @ClassRule
    public static GoogleCloudStorageHttpFixture fixture = new GoogleCloudStorageHttpFixture(USE_FIXTURE, "bucket", "o/oauth2/token");

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return pluginList(GoogleCloudStoragePlugin.class);
    }

    @Override
    protected Settings nodeSettings() {
        Settings.Builder builder = Settings.builder().put(super.nodeSettings());

        if (USE_FIXTURE) {
            builder.put("gcs.client.default.endpoint", fixture.getAddress());
            builder.put("gcs.client.default.token_uri", fixture.getAddress() + "/o/oauth2/token");
        }

        return builder.build();
    }

    @Override
    protected SecureSettings credentials() {
        if (USE_FIXTURE == false) {
            assertThat(System.getProperty("test.google.account"), not(blankOrNullString()));
        }
        assertThat(System.getProperty("test.google.bucket"), not(blankOrNullString()));

        MockSecureSettings secureSettings = new MockSecureSettings();
        if (USE_FIXTURE) {
            secureSettings.setFile("gcs.client.default.credentials_file", TestUtils.createServiceAccount(random()));
        } else {
            secureSettings.setFile(
                "gcs.client.default.credentials_file",
                Base64.getDecoder().decode(System.getProperty("test.google.account"))
            );
        }
        return secureSettings;
    }

    @Override
    protected void createRepository(final String repoName) {
        AcknowledgedResponse putRepositoryResponse = clusterAdmin().preparePutRepository(
            TEST_REQUEST_TIMEOUT,
            TEST_REQUEST_TIMEOUT,
            repoName
        )
            .setType("gcs")
            .setSettings(
                Settings.builder()
                    .put("bucket", System.getProperty("test.google.bucket"))
                    .put("base_path", System.getProperty("test.google.base", "/"))
            )
            .get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));
    }

    public void testReadFromPositionLargerThanBlobLength() {
        testReadFromPositionLargerThanBlobLength(
            e -> asInstanceOf(StorageException.class, e.getCause()).getCode() == RestStatus.REQUESTED_RANGE_NOT_SATISFIED.getStatus()
        );
    }

    public void testResumeAfterUpdate() {

        // The blob needs to be large enough that it won't be entirely buffered on the first request
        final int enoughBytesToNotBeEntirelyBuffered = Math.toIntExact(ByteSizeValue.ofMb(5).getBytes());

        final BlobStoreRepository repo = getRepository();
        final String blobKey = randomIdentifier();
        final byte[] initialValue = randomByteArrayOfLength(enoughBytesToNotBeEntirelyBuffered);
        executeOnBlobStore(repo, container -> {
            container.writeBlob(randomPurpose(), blobKey, new BytesArray(initialValue), true);

            try (InputStream inputStream = container.readBlob(randomPurpose(), blobKey)) {
                // Trigger the first request for the blob, partially read it
                int read = inputStream.read();
                assert read != -1;

                // Close the current underlying stream (this will force a resume)
                asInstanceOf(GoogleCloudStorageRetryingInputStream.class, inputStream).closeCurrentStream();

                // Update the file
                byte[] updatedValue = randomByteArrayOfLength(enoughBytesToNotBeEntirelyBuffered);
                container.writeBlob(randomPurpose(), blobKey, new BytesArray(updatedValue), false);

                // Read the rest of the stream, it should throw because the contents changed
                String message = assertThrows(NoSuchFileException.class, () -> readFully(inputStream)).getMessage();
                assertThat(message, containsString("unavailable on resume (contents changed, or object deleted):"));
            } catch (Exception e) {
                fail(e);
            }
            return null;
        });
    }
}
