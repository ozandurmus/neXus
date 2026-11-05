package com.securityexpert.nexus.ui2.service.notification;

import java.util.Objects;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * What a device or a free-text field looks like in an outbound notification (e-mail digest or syslog line).
 *
 * <p>{@code masked} (the default, V193): the same pseudonyms the AIView masked projection shows --
 * {@link TopologyNamePseudonymizer#maskDeviceName(String, String, String)} for a device's hostname,
 * {@link TopologyNamePseudonymizer#maskManagedDeviceName} for a device with no observed hostname (so the raw
 * {@code device_id} is never printed), and {@code maskText} (names, then IPv4 addresses) for free text. Fail
 * closed: when the pseudonymizer is absent or throws, a fixed label is sent, never the raw value.
 * {@code raw}: today's behaviour, chosen by a security_admin.
 */
final class NotificationIdentity {

    static final String DEVICE_UNAVAILABLE = "[device masked]";
    static final String UNKNOWN_DEVICE = "unknown device";
    static final String TEXT_UNAVAILABLE = "[details withheld]";

    private static final System.Logger LOG = System.getLogger(NotificationIdentity.class.getName());
    private static final NotificationIdentity RAW = new NotificationIdentity(true, null, null);

    private final boolean raw;
    private final TopologyNamePseudonymizer names;
    private final SubnetPreservingIpMasker ips;
    private volatile boolean degraded;

    private NotificationIdentity(boolean raw, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        this.raw = raw;
        this.names = names;
        this.ips = ips;
    }

    static NotificationIdentity raw() {
        return RAW;
    }

    /** Either collaborator may be null (unavailable): the result then only ever sends fixed labels. */
    static NotificationIdentity masked(TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        return new NotificationIdentity(false, names, ips);
    }

    static NotificationIdentity of(NotificationSettings settings, TopologyNamePseudonymizer names,
            SubnetPreservingIpMasker ips) {
        Objects.requireNonNull(settings, "settings");
        return settings.rawIdentity() ? raw() : masked(names, ips);
    }

    boolean isRaw() {
        return raw;
    }

    /**
     * Masked mode: registers every known device hostname with the pseudonymizer, so {@code maskText} recognises a
     * device name inside free text even on a freshly started process. A failure degrades free text to a fixed label.
     */
    void warm(DSLContext dsl) {
        if (raw || names == null) return;
        try {
            for (Record r : dsl.fetch("select device_id, observed_hostname from devices where observed_hostname is not null")) {
                String host = r.get("observed_hostname", String.class);
                if (host != null && !host.isBlank()) {
                    names.maskDeviceName(host, null, r.get("device_id", String.class));
                }
            }
        } catch (RuntimeException e) {
            degraded = true;
            LOG.log(System.Logger.Level.WARNING, "[NOTIFY_IDENTITY_WARM_FAILED] free text will be withheld: {0}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * The device label for a result row. Raw: the row's {@code device_name} column (hostname, else device id) as
     * before. Masked: built from the row's {@code observed_hostname} and the id in {@code idColumn}.
     */
    String device(Record r, String idColumn) {
        if (raw) return r.get("device_name", String.class);
        if (names == null) return DEVICE_UNAVAILABLE;
        String host = column(r, "observed_hostname");
        String id = column(r, idColumn);
        try {
            if (host != null && !host.isBlank()) {
                String masked = names.maskDeviceName(host, null, id);
                return masked == null || masked.isBlank() ? DEVICE_UNAVAILABLE : masked;
            }
            if (id != null && !id.isBlank()) {
                String masked = names.maskManagedDeviceName(id);
                return masked == null || masked.isBlank() ? DEVICE_UNAVAILABLE : masked;
            }
            return UNKNOWN_DEVICE;
        } catch (RuntimeException e) {
            return DEVICE_UNAVAILABLE;
        }
    }

    /** A device id carried as an audit row key: a pseudonym derived from it, never the id. */
    String deviceKey(String deviceId) {
        if (raw) return deviceId;
        if (names == null || deviceId == null || deviceId.isBlank()) return DEVICE_UNAVAILABLE;
        try {
            String masked = names.maskManagedDeviceName(deviceId);
            return masked == null || masked.isBlank() ? DEVICE_UNAVAILABLE : masked;
        } catch (RuntimeException e) {
            return DEVICE_UNAVAILABLE;
        }
    }

    /** Free text (terminal reason, summary, row key): unchanged in raw mode; names and IPv4 addresses masked otherwise. */
    String text(String text) {
        if (raw || text == null || text.isBlank()) return text;
        if (names == null || ips == null || degraded) return TEXT_UNAVAILABLE;
        try {
            return names.maskText(ips.maskText(text));
        } catch (RuntimeException e) {
            return TEXT_UNAVAILABLE;
        }
    }

    private static String column(Record r, String name) {
        return r.field(name) == null ? null : r.get(name, String.class);
    }
}
