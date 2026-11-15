package com.securityexpert.nexus.ui2.worker.supportbundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.supportbundle.PanSupportBundle;
import com.securityexpert.nexus.ui2.jobs.supportbundle.PanSupportBundle.Cmd;
import com.securityexpert.nexus.ui2.jobs.supportbundle.SupportBundleKind;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamOutcome;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.SupportBundleRunStore;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * Support bundle collection, Palo Alto Tech Support File (step S3; design sections 5.8, 5.9; PO approval 2026-11-15, rows
 * P1-P6): the {@code pan_support_bundle} job on one firewall or Panorama, over the XML API with one key per run (the
 * existing keygen). One item, {@code pan_tsf}:
 *
 * <ol>
 *   <li>P1 and P2 are the prechecks: management plane CPU above 80% or root / {@code /opt/panlogs} above 90% (or either
 *       unreadable) refuses the file with the measured number, nothing is started.</li>
 *   <li>P3 starts the export, once, never retried; the device-issued job number must be digits only.</li>
 *   <li>P4 asks for the job every 15 seconds until FIN, at most 30 minutes (120 polls). On the deadline P6 finishes the job
 *       once and the item is {@code TIMED_OUT}.</li>
 *   <li>P5 streams the file into the vault (2 GiB bound): a gzip archive is stored as received (secret-bearing, never
 *       parsed or masked); an XML body is a refusal and nothing is stored.</li>
 * </ol>
 */
public final class PanSupportBundleJobExecutor extends VendorBundleExecutor {

    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }

    private static final Pattern KEY = Pattern.compile("<key>\\s*([^<\\s]+)\\s*</key>");
    private static final Pattern JOB = Pattern.compile("<job>\\s*([0-9]{1,12})\\s*</job>");
    private static final Pattern STATUS = Pattern.compile("<status>\\s*([A-Za-z]+)\\s*</status>");
    private static final Pattern JOB_RESULT = Pattern.compile("<result>\\s*([A-Za-z]+)\\s*</result>");
    private static final Pattern CPU_IDLE = Pattern.compile("(?m)^.*Cpu\\(s\\).*?([0-9]+(?:\\.[0-9]+)?)\\s*id\\b.*$");
    private static final Pattern DISK_LINE = Pattern.compile("(?m)^\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+([0-9]{1,3})%\\s+(\\S+)\\s*$");
    private static final Pattern SUCCESS = Pattern.compile("<response[^>]*\\bstatus\\s*=\\s*[\"']success[\"']");
    static final String ENTRY_NAME = "items/pan_tsf/techsupport.tgz";
    static final int HEAD_BYTES = 4096;

    private final DeviceTransport transport;
    private final PanCredentialResolver credentials;
    private final Pause pause;
    private ApiTarget target;
    private String apiKey;
    private String scope;

    public PanSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport transport, PanCredentialResolver credentials, GateRegistryPort gates, SupportBundleRunStore store,
            ArtefactStore artefacts, HostnameFingerprint fingerprint) {
        this(leases, attempts, devices, transport, credentials, gates, store, artefacts, fingerprint,
                d -> Thread.sleep(d.toMillis()), Clock.systemUTC());
    }

    PanSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport transport, PanCredentialResolver credentials, GateRegistryPort gates, SupportBundleRunStore store,
            ArtefactStore artefacts, HostnameFingerprint fingerprint, Pause pause, Clock clock) {
        super(leases, attempts, devices, gates, store, artefacts, fingerprint, clock);
        this.transport = transport; this.credentials = credentials; this.pause = pause;
    }

    @Override String vendor() { return PanSupportBundle.VENDOR; }

    @Override boolean targetAllowed(DeviceRecord device, Optional<EndpointRecord> endpoint) {
        return ("gateway".equals(device.role()) || "management_server".equals(device.role())) && endpoint.isPresent()
                && "pan_xml_api".equalsIgnoreCase(endpoint.get().transportKind()) && credentials != null;
    }

    static String scopeOf(DeviceRecord device) {
        return "management_server".equals(device.role()) ? PanSupportBundle.PANORAMA_SCOPE : PanSupportBundle.FIREWALL_SCOPE;
    }

    @Override boolean gatesKnown(DeviceRecord device) { return PanSupportBundle.allGatesKnown(scopeOf(device), gates); }

    @Override
    Optional<String> open(Run run, String host, int port, String credentialRef, Optional<EndpointRecord> endpoint) {
        scope = scopeOf(run.device);
        target = new ApiTarget(endpoint.get().endpointId(), endpoint.get().addressRef());
        try {
            var credential = credentials.resolve(credentialRef);
            var result = transport.xmlApiCall(target, new XmlApiSpec("POST", "keygen", "", "",
                    Map.of("user", credential.username(), "password", new String(credential.password())), Map.of()),
                    Duration.ofSeconds(30));
            String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 && c.body() != null ? c.body() : "";
            Matcher key = KEY.matcher(body);
            if (success(body) && key.find()) {
                apiKey = key.group(1);
                return Optional.empty();
            }
            return Optional.of("AUTH_UNAVAILABLE");
        } catch (RuntimeException unavailable) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unavailable);
            return Optional.of("AUTH_UNAVAILABLE");
        }
    }

    @Override
    void close(Run run) {
        apiKey = null;
        target = null;
    }

    @Override
    String item(Run run, ItemRow item, SupportBundleKind kind, RequestRow request) throws IOException {
        if (kind != SupportBundleKind.PAN_TSF) {
            store.finishItem(item.id(), "UNSUPPORTED", "KIND_NOT_AVAILABLE", null, List.of());
            return "UNSUPPORTED";
        }
        // P1 and P2: the prechecks. Unreadable is refused, never assumed fine.
        Integer cpu = cpuPercent(read(run, Cmd.SYSTEM_RESOURCES));
        Integer[] disk = diskPercent(read(run, Cmd.DISK_SPACE));
        run.precheck.put("pan_cpu_pct", cpu);
        run.precheck.put("pan_disk_root_pct", disk[0]);
        run.precheck.put("pan_disk_logs_pct", disk[1]);
        String refusal = null, code = null;
        if (cpu == null) { refusal = "management CPU unknown"; code = "PRECHECK_UNKNOWN"; }
        else if (cpu > PanSupportBundle.CPU_LIMIT_PERCENT) {
            refusal = "management CPU " + cpu + "% exceeds " + PanSupportBundle.CPU_LIMIT_PERCENT + "% limit"; code = "CPU_OVER_LIMIT";
        } else if (disk[0] == null) { refusal = "disk use of / unknown"; code = "PRECHECK_UNKNOWN"; }
        else if (disk[0] > PanSupportBundle.DISK_LIMIT_PERCENT) {
            refusal = "disk / at " + disk[0] + "% exceeds " + PanSupportBundle.DISK_LIMIT_PERCENT + "% limit"; code = "DISK_OVER_LIMIT";
        } else if (disk[1] != null && disk[1] > PanSupportBundle.DISK_LIMIT_PERCENT) {
            refusal = "disk /opt/panlogs at " + disk[1] + "% exceeds " + PanSupportBundle.DISK_LIMIT_PERCENT + "% limit"; code = "DISK_OVER_LIMIT";
        }
        if (refusal != null) {
            store.finishItem(item.id(), "REFUSED_PRECHECK", code, refusal, List.copyOf(run.gateIds));
            return "REFUSED_PRECHECK";
        }

        // P3: start, once, never retried.
        GateResolution.Known start = gate(Cmd.TSF_START);
        String attempt = beginStep(run, start, "recovery-write");
        XmlApiResult started = transport.xmlApiCall(target, spec("export", Map.of("category", "tech-support")),
                PanSupportBundle.timeout(start));
        String body = started instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : null;
        Matcher job = body == null ? null : JOB.matcher(body);
        boolean ok = body != null && success(body) && job.find();
        endStep(run, attempt, ok, body == null ? "TRANSPORT_FAILED" : success(body) ? "JOB_ID_UNREADABLE" : "API_ERROR",
                body == null ? 0 : body.length());
        if (!ok) {
            if (body != null && !success(body)) {
                store.finishItem(item.id(), "UNSUPPORTED", "DEVICE_REFUSED", null, List.copyOf(run.gateIds));
                return "UNSUPPORTED";
            }
            // The request may have reached the device: the outcome is unknown and nothing is sent again.
            store.finishItem(item.id(), "OUTCOME_UNKNOWN", body == null ? "START_OUTCOME_UNKNOWN" : "JOB_ID_UNREADABLE", null,
                    List.copyOf(run.gateIds));
            return "OUTCOME_UNKNOWN";
        }
        String jobId = job.group(1);
        if (!PanSupportBundle.JOB_ID.matcher(jobId).matches()) throw new Unknown("JOB_ID_UNREADABLE");

        // P4: poll every 15 s until FIN, at most 30 minutes.
        boolean finished = false;
        for (int poll = 1; poll <= PanSupportBundle.MAX_POLLS; poll++) {
            GateResolution.Known status = gate(Cmd.TSF_STATUS);
            String statusAttempt = beginStep(run, status, "read");
            XmlApiResult answer = transport.xmlApiCall(target, spec("export",
                    Map.of("category", "tech-support", "action", "status", "job-id", jobId)), PanSupportBundle.timeout(status));
            String text = answer instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : null;
            if (text == null || !success(text) || text.length() > PanSupportBundle.COMMAND_MAX_CHARS) {
                endStep(run, statusAttempt, false, text == null ? "TRANSPORT_FAILED" : "API_ERROR", text == null ? 0 : text.length());
                throw new Unknown(text == null ? "STATUS_TRANSPORT_FAILED" : "STATUS_API_ERROR");
            }
            endStep(run, statusAttempt, true, null, text.length());
            Matcher s = STATUS.matcher(text);
            if (s.find() && "FIN".equalsIgnoreCase(s.group(1))) {
                Matcher r = JOB_RESULT.matcher(text);
                boolean failed = false;
                while (r.find()) if ("FAIL".equalsIgnoreCase(r.group(1))) failed = true;
                if (failed) throw new Unknown("TSF_JOB_FAILED");
                finished = true;
                break;
            }
            if (poll < PanSupportBundle.MAX_POLLS) {
                try {
                    pause.sleep(PanSupportBundle.POLL_INTERVAL);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new Abort(com.securityexpert.nexus.ui2.jobs.JobState.OUTCOME_UNKNOWN, "INTERRUPTED");
                }
            }
        }
        if (!finished) {
            // The deadline: one finish, never repeated, then the item is TIMED_OUT.
            GateResolution.Known finish = gate(Cmd.TSF_FINISH);
            String finishAttempt = beginStep(run, finish, "recovery-write");
            XmlApiResult done = transport.xmlApiCall(target, spec("export",
                    Map.of("category", "tech-support", "action", "finish", "job-id", jobId)), PanSupportBundle.timeout(finish));
            boolean finishOk = done instanceof XmlApiResult.Completed c && c.httpStatus() == 200 && c.body() != null && success(c.body());
            endStep(run, finishAttempt, finishOk, "FINISH_NOT_CONFIRMED", 0);
            store.finishItem(item.id(), "TIMED_OUT", finishOk ? "TIMED_OUT" : "TIMED_OUT_FINISH_UNCONFIRMED",
                    "tech support job not finished after " + PanSupportBundle.MAX_POLLS + " polls", List.copyOf(run.gateIds));
            return "TIMED_OUT";
        }

        // P5: stream the file into the vault.
        GateResolution.Known get = gate(Cmd.TSF_GET);
        String getAttempt = beginStep(run, get, "read");
        ArtefactStore.ArtefactMetadata meta = null;
        Streamed streamed;
        XmlApiStreamOutcome<Streamed> outcome;
        try (ArtefactStore.ArtefactHandle handle = artefacts.open(run.deviceId, run.jobId, vendor(), false)) {
            outcome = transport.xmlApiCallStreaming(target, spec("export",
                    Map.of("category", "tech-support", "action", "get", "job-id", jobId)), PanSupportBundle.timeout(get),
                    in -> copy(in, handle.sink()));
            streamed = outcome instanceof XmlApiStreamOutcome.Completed<Streamed> completed && completed.httpStatus() == 200
                    ? completed.handled() : null;
            boolean good = streamed != null && streamed.gzip() && streamed.xml() == null && !streamed.tooLarge() && streamed.bytes() > 0;
            endStep(run, getAttempt, good, streamed == null ? "TRANSPORT_FAILED" : streamed.tooLarge() ? "TOO_LARGE"
                    : streamed.gzip() ? "EMPTY" : "NOT_GZIP", streamed == null ? 0 : streamed.bytes());
            if (good) meta = handle.finish();
        } catch (ArtefactStore.QuotaExceededException quota) {
            throw new Unknown("ARTEFACT_QUOTA");
        } catch (IOException e) {
            throw new Unknown("ARTEFACT_WRITE_FAILED");
        }
        if (streamed == null) {
            throw new Unknown(outcome instanceof XmlApiStreamOutcome.Completed<Streamed> ? "GET_HTTP_ERROR" : "GET_TRANSPORT_FAILED");
        }
        if (streamed.tooLarge()) {
            store.finishItem(item.id(), "TOO_LARGE", "TOO_LARGE", "received over " + PanSupportBundle.TSF_MAX_BYTES + " bytes",
                    List.copyOf(run.gateIds));
            return "TOO_LARGE";
        }
        if (streamed.xml() != null) {
            store.finishItem(item.id(), "UNSUPPORTED", "DEVICE_REFUSED", null, List.copyOf(run.gateIds));
            return "UNSUPPORTED";
        }
        if (meta == null) throw new Unknown(streamed.gzip() ? "EMPTY_FILE" : "NOT_GZIP");
        store.completeItem(item.id(), manifest(run, meta), "COMPLETED", "COMPLETED", ENTRY_NAME, "none", List.copyOf(run.gateIds));
        return "STORED";
    }

    // ------------------------------------------------------------------ requests

    private GateResolution.Known gate(Cmd cmd) {
        return PanSupportBundle.gate(cmd, scope, gates).orElseThrow(() -> new Unknown("GATE_NOT_KNOWN"));
    }

    private XmlApiSpec spec(String type, Map<String, String> params) {
        return new XmlApiSpec("POST", type, params.getOrDefault("category", ""), "", params, Map.of("X-PAN-KEY", apiKey));
    }

    /** P1 and P2: one gated read; the answer text, or null when it is not a successful, bounded XML answer. */
    private String read(Run run, Cmd cmd) {
        GateResolution.Known gate = gate(cmd);
        String attempt = beginStep(run, gate, "read");
        String xml = cmd == Cmd.SYSTEM_RESOURCES ? "<show><system><resources></resources></system></show>"
                : "<show><system><disk-space></disk-space></system></show>";
        XmlApiResult result = transport.xmlApiCall(target, new XmlApiSpec("POST", "op", "", "", Map.of("cmd", xml),
                Map.of("X-PAN-KEY", apiKey)), PanSupportBundle.timeout(gate));
        String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : null;
        boolean ok = body != null && success(body) && body.length() <= PanSupportBundle.COMMAND_MAX_CHARS;
        endStep(run, attempt, ok, body == null ? "TRANSPORT_FAILED" : "API_ERROR", body == null ? 0 : body.length());
        return ok ? body : null;
    }

    static boolean success(String body) {
        return body != null && SUCCESS.matcher(body).find();
    }

    // ------------------------------------------------------------------ the file stream

    /** What the get answered: a gzip archive of {@code bytes} (maybe over the bound), or an XML body (kept only as a refusal). */
    record Streamed(long bytes, boolean gzip, String xml, boolean tooLarge) {
    }

    static Streamed copy(InputStream from, java.io.OutputStream to) throws IOException {
        PushbackInputStream in = new PushbackInputStream(from, 2);
        int b1 = in.read();
        int b2 = b1 == -1 ? -1 : in.read();
        if (b1 == -1) return new Streamed(0, false, null, false);
        if (b1 == 0x1f && b2 == 0x8b) {
            in.unread(b2);
            in.unread(b1);
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > PanSupportBundle.TSF_MAX_BYTES) return new Streamed(total, true, null, true);
                to.write(buffer, 0, n);
            }
            return new Streamed(total, true, null, false);
        }
        if (b2 != -1) in.unread(b2);
        in.unread(b1);
        byte[] head = in.readNBytes(HEAD_BYTES);
        String text = new String(head, StandardCharsets.UTF_8).trim();
        return new Streamed(head.length, false, text.startsWith("<") ? "xml" : "other", false);
    }

    // ------------------------------------------------------------------ parsers

    /** Management plane CPU used, in percent, from {@code show system resources} (the top header's idle share). */
    static Integer cpuPercent(String body) {
        if (body == null) return null;
        Matcher m = CPU_IDLE.matcher(body);
        if (!m.find()) return null;
        double idle = Double.parseDouble(m.group(1));
        if (idle < 0 || idle > 100) return null;
        return (int) Math.ceil(100 - idle);
    }

    /** Use% of {@code /} and of {@code /opt/panlogs} from {@code show system disk-space}; a missing mount is null. */
    static Integer[] diskPercent(String body) {
        Integer[] result = new Integer[2];
        if (body == null) return result;
        Matcher m = DISK_LINE.matcher(body);
        while (m.find()) {
            int use = Integer.parseInt(m.group(1));
            if (use > 100) continue;
            if ("/".equals(m.group(2))) result[0] = result[0] == null ? use : Math.max(result[0], use);
            if ("/opt/panlogs".equals(m.group(2))) result[1] = result[1] == null ? use : Math.max(result[1], use);
        }
        return result;
    }
}
