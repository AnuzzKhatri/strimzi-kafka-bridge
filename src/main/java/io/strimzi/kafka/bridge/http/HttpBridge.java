/*
 * Copyright 2018 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.strimzi.kafka.bridge.http;

import io.strimzi.kafka.bridge.SinkBridgeEndpoint;
import io.strimzi.kafka.bridge.SourceBridgeEndpoint;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Main bridge class listening for connections
 * and handling HTTP requests.
 */
public class HttpBridge extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(HttpBridge.class);

    private final HttpBridgeConfig httpBridgeConfig;

    private HttpServer httpServer;

    private Map<HttpConnection, SourceBridgeEndpoint> httpSourceEndpoints;

    private Map<String, SinkBridgeEndpoint> httpSinkEndpoints;

    // if the bridge is ready to handle requests
    private boolean isReady = false;

    /**
     * Constructor
     *
     * @param httpBridgeConfig bridge configuration for HTTP support
     */
    public HttpBridge(HttpBridgeConfig httpBridgeConfig) {
        this.httpBridgeConfig = httpBridgeConfig;
    }

    private void bindHttpServer(Future<Void> startFuture) {
        HttpServerOptions httpServerOptions = httpServerOptions();

        this.httpServer = this.vertx.createHttpServer(httpServerOptions)
                .connectionHandler(this::processConnection)
                .requestHandler(this::processRequests)
                .listen(httpServerAsyncResult -> {
                    if (httpServerAsyncResult.succeeded()) {
                        log.info("HTTP-Kafka Bridge started and listening on port {}", httpServerAsyncResult.result().actualPort());
                        log.info("Kafka bootstrap servers {}",
                                this.httpBridgeConfig.getKafkaConfig().getBootstrapServers());

                        this.isReady = true;
                        startFuture.complete();
                    } else {
                        log.error("Error starting HTTP-Kafka Bridge", httpServerAsyncResult.cause());
                        startFuture.fail(httpServerAsyncResult.cause());
                    }
                });
    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {

        log.info("Starting HTTP-Kafka bridge verticle...");
        this.httpSourceEndpoints = new HashMap<>();
        this.httpSinkEndpoints = new HashMap<>();
        this.bindHttpServer(startFuture);
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {

        log.info("Stopping HTTP-Kafka bridge verticle ...");

        this.isReady = false;

        //Consumers cleanup
        this.httpSinkEndpoints.forEach((consumerId, httpEndpoint) -> {
            if (httpEndpoint != null){
                httpEndpoint.close();
            }
        });
        this.httpSinkEndpoints.clear();

        //prducer cleanup
        // for each connection, we have to close the connection itself but before that
        // all the sink/source endpoints (so the related links inside each of them)
        this.httpSourceEndpoints.forEach((connection, endpoint) -> {

            if (endpoint != null) {
                endpoint.close();
            }
        });
        this.httpSourceEndpoints.clear();

        if (this.httpServer != null) {

            this.httpServer.close(done -> {

                if (done.succeeded()) {
                    log.info("HTTP-Kafka bridge has been shut down successfully");
                    stopFuture.complete();
                } else {
                    log.info("Error while shutting down HTTP-Kafka bridge", done.cause());
                    stopFuture.fail(done.cause());
                }
            });
        }
    }

    private HttpServerOptions httpServerOptions() {
        HttpServerOptions httpServerOptions = new HttpServerOptions();
        httpServerOptions.setHost(this.httpBridgeConfig.getEndpointConfig().getHost());
        httpServerOptions.setPort(this.httpBridgeConfig.getEndpointConfig().getPort());
        return httpServerOptions;
    }

    private void processRequests(HttpServerRequest httpServerRequest) {
        log.info("request method is {} and request path is {}", httpServerRequest.method(), httpServerRequest.path());

        RequestType requestType = RequestIdentifier.getRequestType(httpServerRequest);

        switch (requestType){
            case PRODUCE:
                SourceBridgeEndpoint source = this.httpSourceEndpoints.get(httpServerRequest.connection());

                if (source == null) {
                    source = new HttpSourceBridgeEndpoint(this.vertx, this.httpBridgeConfig);
                    source.closeHandler(s -> {
                        this.httpSourceEndpoints.remove(httpServerRequest.connection());
                    });
                    source.open();
                    this.httpSourceEndpoints.put(httpServerRequest.connection(), source);
                }
                source.handle(new HttpEndpoint(httpServerRequest));

                break;

            //create a sink endpoint and initialize consumer
            case CREATE:
                SinkBridgeEndpoint<?,?> sink = new HttpSinkBridgeEndpoint<>(this.vertx, this.httpBridgeConfig);
                sink.closeHandler(s -> {
                    this.httpSinkEndpoints.remove(sink);
                });

                sink.open();

                sink.handle(new HttpEndpoint(httpServerRequest), consumerId -> {
                    this.httpSinkEndpoints.put(consumerId.toString(), sink);
                });

                break;

            case SUBSCRIBE:
            case CONSUME:
            case OFFSETS:
                String instanceId = PathParamsExtractor.getConsumerSubscriptionParams(httpServerRequest).get("instance-id");

                final SinkBridgeEndpoint sinkEndpoint = this.httpSinkEndpoints.get(instanceId);

                if (sinkEndpoint != null) {
                    sinkEndpoint.handle(new HttpEndpoint(httpServerRequest));
                } else {
                    throw new RuntimeException("no conusmer instance found with this id");
                }
                break;

            case DELETE:
                String deleteInstanceID = PathParamsExtractor.getConsumerDeletionParams(httpServerRequest).get("instance-id");

                final SinkBridgeEndpoint deleteSinkEndpoint = this.httpSinkEndpoints.get(deleteInstanceID);

                if (deleteSinkEndpoint != null) {
                    deleteSinkEndpoint.handle(new HttpEndpoint(httpServerRequest));

                    this.httpSinkEndpoints.remove(deleteInstanceID);
                } else {
                    httpServerRequest.response()
                            .setStatusCode(404)
                            .end();
                }
                break;

            case INVALID:
                log.info("invalid request");
        }

    }

    private void processConnection(HttpConnection httpConnection) {
        httpConnection.closeHandler(close ->{
            closeConnectionEndpoint(httpConnection);
        });
    }

    /**
     * Close a connection endpoint and before that all the related sink/source endpoints
     *
     * @param connection	connection for which closing related endpoint
     */
    private void closeConnectionEndpoint(HttpConnection connection) {

        // closing connection, but before closing all sink/source endpoints
        if (this.httpSourceEndpoints.containsKey(connection)) {
            SourceBridgeEndpoint sourceEndpoint = this.httpSourceEndpoints.get(connection);
            if (sourceEndpoint != null) {
                sourceEndpoint.close();
            }
            this.httpSourceEndpoints.remove(connection);
        }
    }

}