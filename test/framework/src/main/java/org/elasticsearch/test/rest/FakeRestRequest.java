/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.rest;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.http.HttpBody;
import org.elasticsearch.http.HttpChannel;
import org.elasticsearch.http.HttpRequest;
import org.elasticsearch.http.HttpResponse;
import org.elasticsearch.rest.ChunkedRestResponseBodyPart;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeRestRequest extends RestRequest {

    public FakeRestRequest() {
        this(
            XContentParserConfiguration.EMPTY.withDeprecationHandler(LoggingDeprecationHandler.INSTANCE),
            new FakeHttpRequest(Method.GET, "", BytesArray.EMPTY, new HashMap<>()),
            new HashMap<>(),
            new FakeHttpChannel(null)
        );
    }

    private FakeRestRequest(
        XContentParserConfiguration config,
        HttpRequest httpRequest,
        Map<String, String> params,
        HttpChannel httpChannel
    ) {
        super(config, params, httpRequest.uri(), httpRequest.getHeaders(), httpRequest, httpChannel);
    }

    public static class FakeHttpRequest implements HttpRequest {

        private final Method method;
        private final String uri;
        private final Map<String, List<String>> headers;
        private HttpBody body;
        private final Exception inboundException;

        public FakeHttpRequest(Method method, String uri, BytesReference body, Map<String, List<String>> headers) {
            this(method, uri, body == null ? HttpBody.empty() : HttpBody.fromBytesReference(body), headers, null);
        }

        public FakeHttpRequest(Method method, String uri, Map<String, List<String>> headers, HttpBody body) {
            this(method, uri, body, headers, null);
        }

        private FakeHttpRequest(Method method, String uri, HttpBody body, Map<String, List<String>> headers, Exception inboundException) {
            this.method = method;
            this.uri = uri;
            this.body = body;
            this.headers = headers;
            this.inboundException = inboundException;
        }

        @Override
        public Method method() {
            return method;
        }

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public HttpBody body() {
            return body;
        }

        @Override
        public void setBody(HttpBody body) {
            this.body = body;
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return headers;
        }

        @Override
        public List<String> strictCookies() {
            return Collections.emptyList();
        }

        @Override
        public HttpVersion protocolVersion() {
            return HttpVersion.HTTP_1_1;
        }

        @Override
        public HttpRequest removeHeader(String header) {
            final var filteredHeaders = new HashMap<>(headers);
            filteredHeaders.remove(header);
            return new FakeHttpRequest(method, uri, body, filteredHeaders, inboundException);
        }

        @Override
        public boolean hasContent() {
            return body.isEmpty() == false;
        }

        @Override
        public HttpResponse createResponse(RestStatus status, BytesReference unused) {
            Map<String, String> responseHeaders = new HashMap<>();
            return new HttpResponse() {
                @Override
                public void addHeader(String name, String value) {
                    responseHeaders.put(name, value);
                }

                @Override
                public boolean containsHeader(String name) {
                    return responseHeaders.containsKey(name);
                }
            };
        }

        @Override
        public HttpResponse createResponse(RestStatus status, ChunkedRestResponseBodyPart firstBodyPart) {
            return createResponse(status, BytesArray.EMPTY);
        }

        @Override
        public void release() {}

        @Override
        public Exception getInboundException() {
            return inboundException;
        }
    }

    public static class FakeHttpChannel implements HttpChannel {

        private final InetSocketAddress remoteAddress;
        private final SubscribableListener<Void> closeFuture = new SubscribableListener<>();

        public FakeHttpChannel(InetSocketAddress remoteAddress) {
            this.remoteAddress = remoteAddress;
        }

        @Override
        public void sendResponse(HttpResponse response, ActionListener<Void> listener) {
            closeFuture.addListener(listener);
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public void addCloseListener(ActionListener<Void> listener) {
            closeFuture.addListener(listener);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            closeFuture.onResponse(null);
        }
    }

    public static class Builder {
        private final XContentParserConfiguration parserConfig;

        private Map<String, List<String>> headers = new HashMap<>();

        private Map<String, String> params = new HashMap<>();

        private HttpBody content = HttpBody.empty();

        private String path = "/";

        private Method method = Method.GET;

        private InetSocketAddress address = null;

        private Exception inboundException;

        public Builder(NamedXContentRegistry registry) {
            this.parserConfig = XContentParserConfiguration.EMPTY.withDeprecationHandler(LoggingDeprecationHandler.INSTANCE)
                .withRegistry(registry);
        }

        public Builder withHeaders(Map<String, List<String>> headers) {
            this.headers = headers;
            return this;
        }

        public Builder withParams(Map<String, String> params) {
            this.params = params;
            return this;
        }

        public Builder withContent(BytesReference contentBytes, XContentType xContentType) {
            this.content = HttpBody.fromBytesReference(contentBytes);
            if (xContentType != null) {
                headers.put("Content-Type", Collections.singletonList(xContentType.mediaType()));
            }
            return this;
        }

        public Builder withBody(HttpBody body) {
            this.content = body;
            return this;
        }

        public Builder withPath(String path) {
            this.path = path;
            return this;
        }

        public Builder withMethod(Method method) {
            this.method = method;
            return this;
        }

        public Builder withRemoteAddress(InetSocketAddress remoteAddress) {
            this.address = remoteAddress;
            return this;
        }

        public Builder withInboundException(Exception exception) {
            this.inboundException = exception;
            return this;
        }

        public FakeRestRequest build() {
            FakeHttpRequest fakeHttpRequest = new FakeHttpRequest(method, path, content, headers, inboundException);
            return new FakeRestRequest(parserConfig, fakeHttpRequest, params, new FakeHttpChannel(address));
        }
    }

    public static String requestToString(RestRequest restRequest) {
        return "method=" + restRequest.method() + ",path=" + restRequest.rawPath();
    }
}
