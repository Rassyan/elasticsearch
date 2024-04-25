/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.utils;

public class StackUtil {
    public static  String getStack(int depth) {
        String name = Thread.currentThread().getName();
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        sb.append("thread_name:").append(name).append("\n");
        for (int i = 0; i < stackTrace.length && i < depth; i++) {
            sb.append(stackTrace[i]).append("\n");
        }

        return sb.toString();
    }
}
