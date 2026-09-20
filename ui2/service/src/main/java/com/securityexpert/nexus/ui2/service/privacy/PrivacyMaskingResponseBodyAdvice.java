package com.securityexpert.nexus.ui2.service.privacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobEvent;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Server-side presentation funnel implementing C9 contract §4 and §6.
 * Intercepts outgoing HTTP responses and applies deterministic, relationship-preserving
 * HMAC pseudonymization for sessions holding {@code role:replay_viewer}.
 * Cached and persistent entities in PostgreSQL and background workers remain 100% operational on raw data.
 */
@ControllerAdvice
public class PrivacyMaskingResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final SubnetPreservingIpMasker ipMasker;
    private final TopologyNamePseudonymizer topologyPseudonymizer;

    public PrivacyMaskingResponseBodyAdvice(SubnetPreservingIpMasker ipMasker,
            TopologyNamePseudonymizer topologyPseudonymizer) {
        this.ipMasker = Objects.requireNonNull(ipMasker, "ipMasker");
        this.topologyPseudonymizer = Objects.requireNonNull(topologyPseudonymizer, "topologyPseudonymizer");
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body == null) {
            return null;
        }
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return body;
        }
        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        Boolean isReplayViewer = (Boolean) httpRequest.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE);
        if (!Boolean.TRUE.equals(isReplayViewer)) {
            return body;
        }

        preRegisterSubnets(body);
        return maskObject(body, null);
    }

    private void preRegisterSubnets(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object val = entry.getValue();
                if (("address".equals(key) || "destination".equals(key)) && val instanceof String s) {
                    ipMasker.registerSubnet(s);
                } else {
                    preRegisterSubnets(val);
                }
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                preRegisterSubnets(item);
            }
        }
    }

    /**
     * Entrypoint for deep-masking arbitrary objects without mutating the inputs.
     */
    public Object maskObject(Object obj, String parentContextRef) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String text) {
            return topologyPseudonymizer.maskText(ipMasker.maskText(text));
        }
        if (obj instanceof JobEvent jobEvent) {
            String maskedReason = jobEvent.terminalReason() != null
                    ? topologyPseudonymizer.maskText(ipMasker.maskText(jobEvent.terminalReason()))
                    : null;
            return new JobEvent(
                    jobEvent.jobId(),
                    jobEvent.jobType(),
                    jobEvent.targetDeviceId(),
                    jobEvent.state(),
                    maskedReason,
                    jobEvent.submittedAt(),
                    jobEvent.finishedAt(),
                    jobEvent.durationMs());
        }
        if (obj instanceof Map<?, ?> map) {
            return maskMap((Map<?, ?>) map, parentContextRef);
        }
        if (obj instanceof List<?> list) {
            return maskList((List<?>) list, parentContextRef);
        }
        return obj;
    }

    private Map<String, Object> maskMap(Map<?, ?> map, String parentContextRef) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Discover cluster ref in this object if present
        String detectedClusterRef = parentContextRef;
        if (map.get("cluster_member_ref") instanceof String s && !s.isBlank()) {
            detectedClusterRef = s;
        }
        final String clusterRef = detectedClusterRef;

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();

            if (value == null) {
                result.put(key, null);
                continue;
            }

            switch (key) {
                case "cluster_member_ref" -> {
                    if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskClusterName(s));
                    } else {
                        result.put(key, value);
                    }
                }
                case "hostname" -> {
                    if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskDeviceName(s, clusterRef));
                    } else {
                        result.put(key, value);
                    }
                }
                case "affected_devices", "target_devices" -> {
                    if (value instanceof List<?> devList) {
                        List<String> maskedList = new ArrayList<>();
                        for (Object devItem : devList) {
                            if (devItem instanceof String devStr) {
                                maskedList.add(topologyPseudonymizer.maskDeviceName(devStr, clusterRef));
                            } else {
                                maskedList.add(String.valueOf(devItem));
                            }
                        }
                        result.put(key, maskedList);
                    } else if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskDeviceName(s, clusterRef));
                    } else {
                        result.put(key, value);
                    }
                }
                case "virtual_systems" -> {
                    if (value instanceof String s) {
                        String masked = Arrays.stream(s.split(",\\s*"))
                                .filter(item -> !item.isBlank())
                                .map(vs -> topologyPseudonymizer.maskVirtualSystem(vs, clusterRef))
                                .collect(Collectors.joining(", "));
                        result.put(key, masked);
                    } else if (value instanceof List<?> vsList) {
                        List<String> maskedList = new ArrayList<>();
                        for (Object vsItem : vsList) {
                            if (vsItem instanceof String vsStr) {
                                maskedList.add(topologyPseudonymizer.maskVirtualSystem(vsStr, clusterRef));
                            } else {
                                maskedList.add(String.valueOf(vsItem));
                            }
                        }
                        result.put(key, maskedList);
                    } else {
                        result.put(key, value);
                    }
                }
                case "address" -> {
                    if (value instanceof String s) {
                        result.put(key, ipMasker.mask(s));
                    } else {
                        result.put(key, value);
                    }
                }
                case "destination", "next_hop" -> {
                    if (value instanceof String s) {
                        result.put(key, ipMasker.mask(s));
                    } else {
                        result.put(key, value);
                    }
                }
                case "terminal_reason", "latest_job_terminal_reason", "peer_follow_reason" -> {
                    if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskText(ipMasker.maskText(s)));
                    } else {
                        result.put(key, value);
                    }
                }
                case "device_id" -> {
                    // Opaque internal UUIDs preserved for seamless frontend routing
                    result.put(key, value);
                }
                default -> {
                    result.put(key, maskObject(value, clusterRef));
                }
            }
        }
        return result;
    }

    private List<Object> maskList(List<?> list, String parentContextRef) {
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(maskObject(item, parentContextRef));
        }
        return result;
    }
}
