/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.receiver.envoy.als.tcp.mx;

import com.google.protobuf.Any;
import com.google.protobuf.TextFormat;
import io.envoyproxy.envoy.data.accesslog.v3.AccessLogCommon;
import io.envoyproxy.envoy.data.accesslog.v3.TCPAccessLogEntry;
import io.envoyproxy.envoy.service.accesslog.v3.StreamAccessLogsMessage;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.apm.network.servicemesh.v3.ServiceMeshMetrics;
import org.apache.skywalking.apm.network.servicemesh.v3.TCPServiceMeshMetric;
import org.apache.skywalking.apm.network.servicemesh.v3.TCPServiceMeshMetrics;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.receiver.envoy.EnvoyMetricReceiverConfig;
import org.apache.skywalking.oap.server.receiver.envoy.als.Role;
import org.apache.skywalking.oap.server.receiver.envoy.als.ServiceMetaInfo;
import org.apache.skywalking.oap.server.receiver.envoy.als.mx.FieldsHelper;
import org.apache.skywalking.oap.server.receiver.envoy.als.mx.ServiceMetaInfoAdapter;
import org.apache.skywalking.oap.server.receiver.envoy.als.tcp.AbstractTCPAccessLogAnalyzer;

import static org.apache.skywalking.oap.server.core.Const.TLS_MODE.NON_TLS;
import static org.apache.skywalking.oap.server.receiver.envoy.als.mx.MetaExchangeALSHTTPAnalyzer.DOWNSTREAM_KEY;
import static org.apache.skywalking.oap.server.receiver.envoy.als.mx.MetaExchangeALSHTTPAnalyzer.UPSTREAM_KEY;

@Slf4j
public class MetaExchangeTCPAccessLogAnalyzer extends AbstractTCPAccessLogAnalyzer {
    protected String fieldMappingFile = "metadata-service-mapping.yaml";

    protected EnvoyMetricReceiverConfig config;

    @Override
    public String name() {
        return "mx-mesh";
    }

    @Override
    public void init(ModuleManager manager, EnvoyMetricReceiverConfig config) throws ModuleStartException {
        this.config = config;
        try {
            FieldsHelper.SINGLETON.init(fieldMappingFile, config.serviceMetaInfoFactory().clazz());
        } catch (final Exception e) {
            throw new ModuleStartException("Failed to load metadata-service-mapping.yaml", e);
        }
    }

    @Override
    public Result analysis(
        final Result previousResult,
        final StreamAccessLogsMessage.Identifier identifier,
        final TCPAccessLogEntry entry,
        final Role role
    ) {
        if (previousResult.hasResult()) {
            return previousResult;
        }
        if (!entry.hasCommonProperties()) {
            return previousResult;
        }
        final ServiceMetaInfo currSvc;
        try {
            currSvc = adaptToServiceMetaInfo(identifier);
        } catch (Exception e) {
            log.error("Failed to inflate the ServiceMetaInfo from identifier.node.metadata. ", e);
            return previousResult;
        }
        final AccessLogCommon properties = entry.getCommonProperties();
        final Map<String, Any> stateMap = properties.getFilterStateObjectsMap();
        if (stateMap.isEmpty()) {
            return Result.builder().service(currSvc).build();
        }

        final TCPServiceMeshMetrics.Builder result = TCPServiceMeshMetrics.newBuilder();
        final AtomicBoolean downstreamExists = new AtomicBoolean();
        stateMap.forEach((key, value) -> {
            if (!key.equals(UPSTREAM_KEY) && !key.equals(DOWNSTREAM_KEY)) {
                return;
            }
            final ServiceMetaInfo svc;
            try {
                svc = adaptToServiceMetaInfo(value);
            } catch (Exception e) {
                log.error("Fail to parse metadata {} to FlatNode", Base64.getEncoder().encode(value.toByteArray()));
                return;
            }
            final TCPServiceMeshMetric.Builder metrics;
            switch (key) {
                case UPSTREAM_KEY:
                    metrics = newAdapter(entry, currSvc, svc).adaptToUpstreamMetrics().setTlsMode(NON_TLS);
                    if (log.isDebugEnabled()) {
                        log.debug("Transformed a {} outbound mesh metrics {}", role, TextFormat.shortDebugString(metrics));
                    }
                    result.addMetrics(metrics);
                    break;
                case DOWNSTREAM_KEY:
                    metrics = newAdapter(entry, svc, currSvc).adaptToDownstreamMetrics();
                    if (log.isDebugEnabled()) {
                        log.debug("Transformed a {} inbound mesh metrics {}", role, TextFormat.shortDebugString(metrics));
                    }
                    result.addMetrics(metrics);
                    downstreamExists.set(true);
                    break;
            }
        });
        if (role.equals(Role.PROXY) && !downstreamExists.get()) {
            final TCPServiceMeshMetric.Builder metric = newAdapter(entry, config.serviceMetaInfoFactory().unknown(), currSvc).adaptToDownstreamMetrics();
            if (log.isDebugEnabled()) {
                log.debug("Transformed a {} inbound mesh metric {}", role, TextFormat.shortDebugString(metric));
            }
            result.addMetrics(metric);
        }
        return Result.builder().metrics(ServiceMeshMetrics.newBuilder().setTcpMetrics(result)).service(currSvc).build();
    }

    protected ServiceMetaInfo adaptToServiceMetaInfo(final Any value) throws Exception {
        return new ServiceMetaInfoAdapter(value);
    }

    protected ServiceMetaInfo adaptToServiceMetaInfo(final StreamAccessLogsMessage.Identifier identifier) throws Exception {
        return config.serviceMetaInfoFactory().fromStruct(identifier.getNode().getMetadata());
    }

}
