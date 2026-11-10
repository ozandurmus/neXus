package com.securityexpert.nexus.ui2.platform.advisoryfetch;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Entry point of the {@code advisory-fetch} role (contract section 3). Settings, all from the environment:
 * {@value #PROXY_VARIABLE} ({@code host:port}; without it nothing is fetched), {@value #TOKEN_VARIABLE} (the ingest
 * token), {@value #INGEST_VARIABLE} (the service's internal base address), {@value #SOURCES_VARIABLE} (source keys,
 * default all), {@value #CISCO_DAYS_VARIABLE} (how far back the Cisco change list is followed; default 7, the first
 * load is started by hand with a larger value). Exit code 0 when every source ended clean, 1 otherwise, 2 on a setting
 * that stops the run before any request.
 */
public final class AdvisoryFetchMain {

    public static final String PROXY_VARIABLE = "UI2_ADVISORY_PROXY";
    public static final String TOKEN_VARIABLE = "UI2_ADVISORY_FETCH_TOKEN";
    public static final String INGEST_VARIABLE = "UI2_ADVISORY_INGEST_URL";
    public static final String SOURCES_VARIABLE = "UI2_ADVISORY_SOURCES";
    public static final String CISCO_DAYS_VARIABLE = "UI2_ADVISORY_CISCO_DAYS";
    static final String DEFAULT_INGEST = "http://ui2-service-internal.ui2.svc.cluster.local:8086";
    static final int DEFAULT_CISCO_DAYS = 7;
    static final int MAX_CISCO_DAYS = 3660;

    private AdvisoryFetchMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System::getenv, System.out::println));
    }

    static int run(Function<String, String> env, java.util.function.Consumer<String> log) {
        // A JVM switch that weakens certificate checks would silently break the TLS stop rule: refuse to run with it.
        if (Boolean.getBoolean("jdk.internal.httpclient.disableHostnameVerification")) {
            log.accept("[ADVISORY_FETCH] refused: host name verification is switched off for this JVM");
            return 2;
        }
        Settings settings = Settings.from(env);
        if (settings.problem() != null) {
            log.accept("[ADVISORY_FETCH] not started: " + settings.problem());
            return 2;
        }
        HttpTransport transport = new HttpTransport(settings.proxyHost(), settings.proxyPort(), settings.ingest(), settings.token());
        AdvisoryFetchRun run = new AdvisoryFetchRun(transport, transport, log, Instant.now(), settings.ciscoDays());
        String runId = runId();
        boolean clean = true;
        for (AdvisorySources.Source source : settings.sources()) {
            clean &= run.run(source, runId).ok();
        }
        log.accept("[ADVISORY_FETCH] run=" + runId + " outcome=" + (clean ? "OK" : "NOT_CLEAN"));
        return clean ? 0 : 1;
    }

    static String runId() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return "run-" + HexFormat.of().formatHex(bytes);
    }

    /** The parsed settings; {@code problem} is a fixed sentence when the run must not start. */
    record Settings(String proxyHost, int proxyPort, URI ingest, String token, List<AdvisorySources.Source> sources, int ciscoDays,
            String problem) {

        static Settings from(Function<String, String> env) {
            String proxy = strip(env.apply(PROXY_VARIABLE));
            if (proxy == null) {
                return stop(PROXY_VARIABLE + " is not set");
            }
            proxy = proxy.replaceFirst("^https?://", "").replaceFirst("/$", "");
            int colon = proxy.lastIndexOf(':');
            int port;
            try {
                port = colon > 0 ? Integer.parseInt(proxy.substring(colon + 1)) : -1;
            } catch (NumberFormatException e) {
                port = -1;
            }
            if (port < 1 || port > 65535 || !proxy.substring(0, colon).matches("[A-Za-z0-9.-]{1,253}")) {
                return stop(PROXY_VARIABLE + " is not host:port");
            }
            String token = strip(env.apply(TOKEN_VARIABLE));
            if (token == null || token.length() < 24) {
                return stop(TOKEN_VARIABLE + " is not set or too short");
            }
            URI ingest;
            try {
                String raw = strip(env.apply(INGEST_VARIABLE));
                ingest = new URI(raw == null ? DEFAULT_INGEST : raw);
            } catch (java.net.URISyntaxException e) {
                return stop(INGEST_VARIABLE + " is not an address");
            }
            if (!"http".equals(ingest.getScheme()) || ingest.getHost() == null
                    || !(ingest.getHost().endsWith(".svc.cluster.local") || ingest.getHost().equals("localhost"))) {
                return stop(INGEST_VARIABLE + " must be an in-cluster service address");
            }
            List<AdvisorySources.Source> sources = new ArrayList<>();
            String rawSources = strip(env.apply(SOURCES_VARIABLE));
            if (rawSources == null) {
                sources.addAll(AdvisorySources.ALL);
            } else {
                for (String key : rawSources.split(",")) {
                    var source = AdvisorySources.byKey(key.strip());
                    if (source.isEmpty()) {
                        return stop(SOURCES_VARIABLE + " names an unknown source");
                    }
                    if (!sources.contains(source.get())) {
                        sources.add(source.get());
                    }
                }
            }
            int days = DEFAULT_CISCO_DAYS;
            String rawDays = strip(env.apply(CISCO_DAYS_VARIABLE));
            if (rawDays != null) {
                try {
                    days = Integer.parseInt(rawDays);
                } catch (NumberFormatException e) {
                    return stop(CISCO_DAYS_VARIABLE + " is not a number");
                }
                if (days < 1 || days > MAX_CISCO_DAYS) {
                    return stop(CISCO_DAYS_VARIABLE + " is out of range");
                }
            }
            return new Settings(proxy.substring(0, colon), port, ingest, token, List.copyOf(sources), days, null);
        }

        private static Settings stop(String problem) {
            return new Settings(null, 0, null, null, List.of(), 0, problem);
        }

        private static String strip(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}
