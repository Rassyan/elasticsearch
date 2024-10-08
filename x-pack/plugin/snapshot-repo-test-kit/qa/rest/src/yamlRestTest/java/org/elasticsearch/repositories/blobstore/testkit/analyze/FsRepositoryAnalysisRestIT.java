/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.repositories.blobstore.testkit.analyze;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.repositories.fs.FsRepository;

public class FsRepositoryAnalysisRestIT extends AbstractRepositoryAnalysisRestTestCase {

    @Override
    protected String repositoryType() {
        return FsRepository.TYPE;
    }

    @Override
    protected Settings repositorySettings() {
        return Settings.builder().put("location", System.getProperty("tests.path.repo")).build();
    }
}
