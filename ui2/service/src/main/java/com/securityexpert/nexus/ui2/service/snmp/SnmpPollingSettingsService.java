package com.securityexpert.nexus.ui2.service.snmp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Configuration;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Device;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Override;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Settings;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPollingRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialView;

/**
 * Administration > SNMP polling (SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 11, option B): the fleet switch, the
 * interval, the fleet default credential, the scope mode, and per-vendor and per-device rows. Credentials are picked from
 * the credential store by opaque reference; only SNMPv3 credentials are offered. A body naming an unknown key or a value
 * outside its bounds is refused as a whole and nothing is written. A credential the poller will not use (not authPriv
 * with SHA-2 and AES) is accepted with a warning (product rule: warn, do not refuse).
 */
public final class SnmpPollingSettingsService {
    static final Set<String> KEYS = Set.of("enabled", "interval_s", "default_credential_reference_id", "scope_mode",
            "vendors", "devices");
    static final Set<String> STRONG_AUTH = Set.of("SHA-224", "SHA-256", "SHA-384", "SHA-512");
    static final Set<String> STRONG_PRIV = Set.of("AES-128", "AES-192", "AES-256");

    public record Refusal(int status, String code, String message, List<String> problems) {
        public Refusal {
            problems = List.copyOf(problems);
        }
    }

    public record Result(Map<String, Object> body, Refusal refusal) {
        public boolean isOk() {
            return refusal == null;
        }
    }

    private final SnmpPollingRepository repository;
    private final Supplier<List<CredentialView>> credentials;

    public SnmpPollingSettingsService(SnmpPollingRepository repository, Supplier<List<CredentialView>> credentials) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /** The view for the caller. {@code masked}: the aiview persona; credential names and references are replaced. */
    public Map<String, Object> view(boolean canWrite, boolean masked) {
        return view(repository.read(), canWrite, masked, List.of());
    }

    public Result update(String actorFingerprint, Map<String, Object> body) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return refused(403, "SNMP_POLLING_SETTINGS_NOT_PERMITTED", "No authenticated session.", List.of());
        }
        if (body == null || body.isEmpty()) {
            return refused(400, "SNMP_POLLING_SETTINGS_EMPTY", "The body names no setting.", List.of());
        }
        Configuration current = repository.read();
        Map<String, CredentialView> snmpCredentials = snmpCredentials();
        Map<String, Device> eligible = new HashMap<>();
        for (Device d : repository.eligibleDevices()) eligible.put(d.deviceId(), d);
        List<String> problems = new ArrayList<>();
        for (String key : body.keySet()) if (!KEYS.contains(key)) problems.add(key + ": not an SNMP polling setting");

        Settings s = current.settings();
        boolean enabled = s.enabled();
        if (body.containsKey("enabled")) {
            if (body.get("enabled") instanceof Boolean b) enabled = b;
            else problems.add("enabled: must be true or false");
        }
        int interval = s.intervalS();
        if (body.containsKey("interval_s")) {
            Long n = integral(body.get("interval_s"));
            if (n == null || n < SnmpPolling.MIN_INTERVAL_S || n > SnmpPolling.MAX_INTERVAL_S) {
                problems.add("interval_s: must be a whole number of seconds between " + SnmpPolling.MIN_INTERVAL_S + " and "
                        + SnmpPolling.MAX_INTERVAL_S);
            } else {
                interval = n.intValue();
            }
        }
        String scopeMode = s.scopeMode();
        if (body.containsKey("scope_mode")) {
            if (body.get("scope_mode") instanceof String m && SnmpPolling.SCOPE_MODES.contains(m)) scopeMode = m;
            else problems.add("scope_mode: must be VENDORS or DEVICES");
        }
        String fleetCredential = s.defaultCredentialReferenceId();
        if (body.containsKey("default_credential_reference_id")) {
            fleetCredential = credentialRef(body.get("default_credential_reference_id"), "default_credential_reference_id",
                    snmpCredentials, problems);
        }
        List<Override> vendors = new ArrayList<>(current.overrides().stream().filter(o -> o.scopeKind().equals("VENDOR")).toList());
        if (body.containsKey("vendors")) {
            vendors.clear();
            Set<String> seen = new java.util.HashSet<>();
            for (Map<String, Object> row : rows(body.get("vendors"), "vendors", problems)) {
                Object v = row.get("vendor");
                if (!(v instanceof String vendor) || !SnmpPolling.PILOT_VENDORS.contains(vendor)) {
                    problems.add("vendors: vendor must be one of " + SnmpPolling.PILOT_VENDORS);
                    continue;
                }
                if (!seen.add(vendor)) {
                    problems.add("vendors: " + vendor + " is listed twice");
                    continue;
                }
                Boolean inScope = row.get("in_scope") instanceof Boolean b ? b : null;
                if (inScope == null) problems.add("vendors: " + vendor + ".in_scope must be true or false");
                String ref = credentialRef(row.get("credential_reference_id"), "vendors: " + vendor + ".credential_reference_id",
                        snmpCredentials, problems);
                if (inScope != null) vendors.add(Override.vendor(vendor, inScope, ref));
            }
        }
        List<Override> devices = new ArrayList<>(current.overrides().stream().filter(o -> o.scopeKind().equals("DEVICE")).toList());
        if (body.containsKey("devices")) {
            devices.clear();
            Set<String> seen = new java.util.HashSet<>();
            for (Map<String, Object> row : rows(body.get("devices"), "devices", problems)) {
                Object id = row.get("device_id");
                if (!(id instanceof String deviceId) || !eligible.containsKey(deviceId)) {
                    problems.add("devices: device_id must be an enrolled Check Point or Palo Alto gateway");
                    continue;
                }
                if (!seen.add(deviceId)) {
                    problems.add("devices: a device is listed twice");
                    continue;
                }
                Boolean inScope = row.get("in_scope") instanceof Boolean b ? b : null;
                if (inScope == null) problems.add("devices: in_scope must be true or false");
                String ref = credentialRef(row.get("credential_reference_id"), "devices: credential_reference_id",
                        snmpCredentials, problems);
                if (inScope != null) devices.add(Override.device(deviceId, inScope, ref));
            }
        }
        if (!problems.isEmpty()) {
            return refused(400, "SNMP_POLLING_SETTINGS_INVALID", "Nothing was changed: " + String.join("; ", problems) + ".",
                    problems);
        }
        Settings next = new Settings(enabled, interval, fleetCredential, scopeMode, s.rawRetentionDays(),
                s.hourlyRetentionDays());
        List<Override> overrides = new ArrayList<>(vendors);
        overrides.addAll(devices);
        Configuration stored = repository.write(actorFingerprint, next, overrides);
        return new Result(view(stored, true, false, warnings(stored, snmpCredentials)), null);
    }

    /** Warnings that do not stop the write: the switch on with no credential, or a credential the poller will not use. */
    static List<String> warnings(Configuration c, Map<String, CredentialView> snmpCredentials) {
        List<String> out = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        if (c.settings().defaultCredentialReferenceId() != null) refs.add(c.settings().defaultCredentialReferenceId());
        c.overrides().stream().map(Override::credentialReferenceId).filter(Objects::nonNull).forEach(refs::add);
        if (c.settings().enabled() && refs.isEmpty()) {
            out.add("Polling is on but no SNMPv3 credential is chosen: devices are reported SNMP_NO_CREDENTIAL and nothing is sent.");
        }
        for (String ref : refs.stream().distinct().toList()) {
            CredentialView v = snmpCredentials.get(ref);
            if (v != null && !suitable(v)) {
                out.add("Credential \"" + v.displayName() + "\" is not authPriv with SHA-2 authentication and AES privacy; "
                        + "the poller will not use it (SNMP_CREDENTIAL_UNSUITABLE).");
            }
        }
        return out;
    }

    static boolean suitable(CredentialView v) {
        return v.snmp() != null && "authPriv".equals(v.snmp().securityLevel()) && STRONG_AUTH.contains(v.snmp().authProtocol())
                && STRONG_PRIV.contains(v.snmp().privProtocol());
    }

    private Map<String, CredentialView> snmpCredentials() {
        Map<String, CredentialView> out = new LinkedHashMap<>();
        for (CredentialView v : credentials.get()) {
            if (v.kind() == CredentialKind.SNMP_V3) out.put(v.credentialReferenceId(), v);
        }
        return out;
    }

    private Map<String, Object> view(Configuration c, boolean canWrite, boolean masked, List<String> warnings) {
        Map<String, CredentialView> snmp = snmpCredentials();
        Map<String, String> alias = new HashMap<>();
        int n = 0;
        for (String ref : snmp.keySet()) alias.put(ref, "CRED-" + (++n));
        java.util.function.Function<String, String> ref = id -> id == null ? null
                : masked ? alias.getOrDefault(id, "CRED-UNKNOWN") : id;

        Map<String, Object> body = new LinkedHashMap<>();
        Settings s = c.settings();
        body.put("enabled", s.enabled());
        body.put("interval_s", s.intervalS());
        body.put("scope_mode", s.scopeMode());
        body.put("default_credential_reference_id", ref.apply(s.defaultCredentialReferenceId()));
        body.put("raw_retention_days", s.rawRetentionDays());
        body.put("hourly_retention_days", s.hourlyRetentionDays());
        List<Map<String, Object>> vendors = new ArrayList<>();
        List<Map<String, Object>> devices = new ArrayList<>();
        for (Override o : c.overrides()) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (o.scopeKind().equals("VENDOR")) row.put("vendor", o.vendor());
            else row.put("device_id", o.deviceId());
            row.put("in_scope", o.inScope());
            row.put("credential_reference_id", ref.apply(o.credentialReferenceId()));
            (o.scopeKind().equals("VENDOR") ? vendors : devices).add(row);
        }
        body.put("vendors", vendors);
        body.put("devices", devices);
        List<Map<String, Object>> creds = new ArrayList<>();
        for (CredentialView v : snmp.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("credential_reference_id", ref.apply(v.credentialReferenceId()));
            row.put("display_name", masked ? alias.get(v.credentialReferenceId()) : v.displayName());
            row.put("security_level", v.snmp() == null ? null : v.snmp().securityLevel());
            row.put("auth_protocol", v.snmp() == null ? null : v.snmp().authProtocol());
            row.put("priv_protocol", v.snmp() == null ? null : v.snmp().privProtocol());
            row.put("suitable", suitable(v));
            creds.add(row);
        }
        body.put("credentials", creds);
        List<Device> eligible = repository.eligibleDevices();
        List<Map<String, Object>> eligibleRows = new ArrayList<>();
        for (Device d : eligible) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("device_id", d.deviceId());
            row.put("vendor", d.vendor());
            eligibleRows.add(row);
        }
        body.put("eligible_devices", eligibleRows);
        body.put("last_outcome_counts", new TreeMap<>(repository.lastOutcomeCounts(Instant.now().minus(Duration.ofDays(1)))));
        body.put("pilot_vendors", SnmpPolling.PILOT_VENDORS);
        body.put("interval_min_s", SnmpPolling.MIN_INTERVAL_S);
        body.put("interval_max_s", SnmpPolling.MAX_INTERVAL_S);
        body.put("warnings", masked ? List.of() : warnings.isEmpty() ? warnings(c, snmp) : warnings);
        body.put("updated_at", c.updatedAt() == null ? null : c.updatedAt().toString());
        body.put("updated_by", c.updatedBy());
        body.put("can_write", canWrite && !masked);
        return body;
    }

    private static String credentialRef(Object value, String field, Map<String, CredentialView> snmp, List<String> problems) {
        if (value == null) return null;
        if (value instanceof String id && snmp.containsKey(id)) return id;
        problems.add(field + ": must be an SNMPv3 credential from Administration > Credentials, or null");
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value, String field, List<String> problems) {
        if (!(value instanceof List<?> list)) {
            problems.add(field + ": must be a list");
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            else problems.add(field + ": every entry must be an object");
        }
        return out;
    }

    private static Long integral(Object value) {
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Long l) return l;
        if (value instanceof java.math.BigInteger b && b.bitLength() < 63) return b.longValue();
        if (value instanceof Number num && num.doubleValue() == Math.rint(num.doubleValue())
                && Math.abs(num.doubleValue()) < 1e15) {
            return (long) num.doubleValue();
        }
        return null;
    }

    private static Result refused(int status, String code, String message, List<String> problems) {
        return new Result(null, new Refusal(status, code, message, problems));
    }
}
