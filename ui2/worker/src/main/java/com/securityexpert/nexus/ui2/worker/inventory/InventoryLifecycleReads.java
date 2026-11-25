package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

/**
 * The lifecycle reads of the inventory-collect device contact, group A (Check Point, PO 2026-11-10) and group B
 * (Palo Alto, PO 2026-11-10), split out of {@link InventoryCapabilityExecutor} as one concern: each read is gated by
 * {@link LifecycleReadSwitch} (checked before any transport call), each answer goes to {@link LifecycleResultSink},
 * and each outcome is emitted as one masked line. Behaviour is unchanged by the split; the executor delegates here.
 */
final class InventoryLifecycleReads {

    private final DeviceTransport transport;
    /** Lifecycle reads, group A (PO 2026-11-10): off unless the worker wires a switch in; see {@link LifecycleReadSwitch}. */
    private LifecycleReadSwitch reads = LifecycleReadSwitch.OFF;
    private java.util.function.Consumer<String> sink = LifecycleReadShape::log;
    /** Where the parsed-and-stored result of each lifecycle read goes (default: nowhere); see {@link LifecycleResultSink}. */
    private LifecycleResultSink results = LifecycleResultSink.NONE;

    InventoryLifecycleReads(DeviceTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    InventoryLifecycleReads withReads(LifecycleReadSwitch reads) {
        this.reads = Objects.requireNonNull(reads, "lifecycleReads");
        return this;
    }

    InventoryLifecycleReads withResults(LifecycleResultSink sink) {
        this.results = LifecycleResultSink.guarded(sink);
        return this;
    }

    InventoryLifecycleReads withSink(java.util.function.Consumer<String> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
        return this;
    }

    boolean anyFor(String deviceId) {
        return reads.anyFor(deviceId);
    }

    boolean allowsPanExtended(String deviceId) {
        return reads.allowsPanExtended(deviceId);
    }

    /**
     * Lifecycle reads, group A (PO 2026-11-10): the approved form for where this run landed, each read alone in its own
     * try/catch, 60 s, no retry, no fallback channel. A management server gets the bare literal in the interactive Expert
     * shell; a Quantum Spark identified in this run only {@code show license}; a device in clish that is not a Spark gets
     * none; a Gaia gateway the login-shell form (the {@code vsenv 0} form on a VSX host). The answer is reduced to a masked
     * shape and logged; it is never returned, stored or recorded in a transcript.
     */
    void checkPointReads(TransportSession session, String deviceId, boolean managementServer, boolean vsxHost,
            boolean sparkIdentified, boolean clishLanding) {
        LifecycleReadSwitch.Kind cplic = LifecycleReadSwitch.Kind.CP_CPLIC_PRINT;
        LifecycleReadSwitch.Kind cplp = LifecycleReadSwitch.Kind.CP_CPLP_LIST;
        LifecycleReadSwitch.Kind spark = LifecycleReadSwitch.Kind.CP_SPARK_SHOW_LICENSE;
        if (managementServer) {
            skip(spark, deviceId, "management_server");
            cliRead(session, cplic, deviceId, InventoryReadPlan.CP_CPLIC_PRINT_MDS, true);
            cliRead(session, cplp, deviceId, InventoryReadPlan.CP_CPLP_LIST_MDS, true);
        } else if (sparkIdentified) {
            skip(cplic, deviceId, "spark_gets_show_license_only");
            skip(cplp, deviceId, "spark_gets_show_license_only");
            cliRead(session, spark, deviceId, InventoryReadPlan.SPARK_SHOW_LICENSE, true);
        } else if (clishLanding) {
            skip(cplic, deviceId, "no_approved_form_for_landing_shell");
            skip(cplp, deviceId, "no_approved_form_for_landing_shell");
            skip(spark, deviceId, "no_approved_form_for_landing_shell");
        } else {
            cliRead(session, cplic, deviceId, InventoryReadPlan.cplicPrintGateway(vsxHost), false);
            cliRead(session, cplp, deviceId, InventoryReadPlan.cplpListGateway(vsxHost), false);
        }
    }

    void skip(LifecycleReadSwitch.Kind kind, String deviceId, String reason) {
        if (reads.allows(kind, deviceId)) {
            results.notCollected(kind, deviceId, "SKIPPED", reason);
            emit(LifecycleReadShape.skippedLine(kind, deviceId, reason));
        }
    }

    void cliRead(TransportSession session, LifecycleReadSwitch.Kind kind, String deviceId, String command,
            boolean interactive) {
        if (!reads.allows(kind, deviceId)) {
            return;
        }
        String line;
        try {
            ExecSpec spec = new ExecSpec(command);
            // Nothing from these reads is kept in a job transcript: the scope, if one is open on this thread, is lifted.
            ExecResult result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(
                    () -> interactive ? transport.execInteractive(session, spec, InventoryReadPlan.LIFECYCLE_READ_TIMEOUT)
                            : transport.exec(session, spec, InventoryReadPlan.LIFECYCLE_READ_TIMEOUT));
            line = switch (result) {
                case ExecResult.Completed completed -> {
                    results.answer(kind, deviceId, null, completed.output());
                    yield LifecycleReadShape.cliLine(kind, deviceId, completed.output());
                }
                case ExecResult.TimedOut timedOut -> {
                    results.notCollected(kind, deviceId, "FAILED", "TimedOut");
                    yield LifecycleReadShape.errorLine(kind, deviceId, "TimedOut");
                }
                case ExecResult.ChannelFailed failed -> {
                    results.notCollected(kind, deviceId, "FAILED", "ChannelFailed");
                    yield LifecycleReadShape.errorLine(kind, deviceId, "ChannelFailed");
                }
            };
        } catch (RuntimeException e) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(e);
            results.notCollected(kind, deviceId, "FAILED", e.getClass().getSimpleName());
            line = LifecycleReadShape.errorLine(kind, deviceId, e.getClass().getSimpleName());
        }
        emit(line);
    }

    void emit(String line) {
        try {
            sink.accept(line);
        } catch (RuntimeException ignored) {
            // A measurement sink never changes an inventory result.
        }
    }

    /**
     * Lifecycle reads, group B (PO 2026-11-10, D2): {@code request license info} and {@code request support info}, sent to a
     * firewall whose PAN-OS major version, observed by the {@code type=version} read of THIS run, is known and below 12
     * (the rule of {@code PanFirewallCommandSwitches.Mode.BELOW_12}: an unknown or unparseable version is treated like 12 or
     * later). A firewall on PAN-OS 12 or later, or of unknown version, gets them only when the extended-scope setting
     * lists it (amendment C, PO 2026-11-10, "Evet, aç"). 60 s, no retry, outside any job transcript; the answer is reduced
     * to an XML shape and logged.
     */
    void paloAltoFirewallReads(ApiTarget target, Map<String, String> headers, String deviceId, String swVersion) {
        LifecycleReadSwitch.Kind license = LifecycleReadSwitch.Kind.PAN_LICENSE_INFO;
        LifecycleReadSwitch.Kind support = LifecycleReadSwitch.Kind.PAN_SUPPORT_INFO;
        if (!reads.allows(license, deviceId) && !reads.allows(support, deviceId)) {
            return;
        }
        boolean belowTwelve;
        try {
            belowTwelve = com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.decide(
                    com.securityexpert.nexus.ui2.jobs.pan.PanFirewallCommandSwitches.Mode.BELOW_12, swVersion).send();
        } catch (RuntimeException e) {
            belowTwelve = false;
        }
        if (!belowTwelve && !reads.allowsPanExtended(deviceId)) {
            skip(license, deviceId, "pan_os_12_or_unknown");
            skip(support, deviceId, "pan_os_12_or_unknown");
            return;
        }
        panXmlRead(target, headers, license, deviceId, InventoryReadPlan.PAN_REQUEST_LICENSE_INFO);
        panXmlRead(target, headers, support, deviceId, InventoryReadPlan.PAN_REQUEST_SUPPORT_INFO);
    }

    void panXmlRead(ApiTarget target, Map<String, String> headers, LifecycleReadSwitch.Kind kind, String deviceId,
            String cmd) {
        if (!reads.allows(kind, deviceId)) {
            return;
        }
        String line;
        try {
            XmlApiResult result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(
                    () -> transport.xmlApiCall(target,
                            new XmlApiSpec("GET", "op", "", "direct_firewall", Map.of("cmd", cmd), headers),
                            InventoryReadPlan.LIFECYCLE_READ_TIMEOUT));
            line = switch (result) {
                case XmlApiResult.Completed completed -> {
                    results.answer(kind, deviceId, null, completed.body());
                    yield LifecycleReadShape.xmlLine(kind, deviceId, completed.body());
                }
                case XmlApiResult.Failed failed -> {
                    results.notCollected(kind, deviceId, "FAILED", "XmlApiFailed");
                    yield LifecycleReadShape.errorLine(kind, deviceId, "XmlApiFailed");
                }
            };
        } catch (RuntimeException e) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(e);
            results.notCollected(kind, deviceId, "FAILED", e.getClass().getSimpleName());
            line = LifecycleReadShape.errorLine(kind, deviceId, e.getClass().getSimpleName());
        }
        emit(line);
    }
}
