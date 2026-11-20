package com.securityexpert.nexus.ui2.service.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.service.script.ScriptRunDispatchService;
import com.securityexpert.nexus.ui2.service.script.ScriptRunDispatchService.FinishReport;
import com.securityexpert.nexus.ui2.service.script.ScriptRunDispatchService.Refusal;
import com.securityexpert.nexus.ui2.service.script.ScriptRunDispatchService.StartOutcome;

/**
 * The broker's routes ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b) item 4). Served on the internal
 * port 8086 only ({@code MachinePortConfiguration}); the main port answers 404 for every {@code /internal/**} path.
 * Authenticated by the broker's own token in {@code X-Nexus-Script-Runner-Token}, compared by SHA-256 digest in constant
 * time against {@code UI2_SCRIPT_RUNNER_TOKEN_SHA256} (the {@code AdvisoryIngestController} pattern); with that variable
 * unset or malformed every route answers 404. The token authorises nothing else, and nothing else authorises these
 * routes: the job lease (state, epoch and {@code script_runner} owner) is checked on every call by
 * {@link ScriptRunDispatchService}. Paths are fixed (the job id travels in the body) so the ungated route list stays an
 * exact list. Responses never echo a request; a refusal is a status and a fixed code word.
 */
@RestController
public final class ScriptRunnerInternalController {

    public static final String TOKEN_HEADER = "X-Nexus-Script-Runner-Token";
    public static final String START = "/internal/script-runs/start";
    public static final String FINISH = "/internal/script-runs/finish";
    public static final String ABORT = "/internal/script-runs/abort";
    public static final List<String> PATHS = List.of(START, FINISH, ABORT);

    /** Captured output arrives at most 2 x 4 MiB of log plus JSON overhead. */
    static final int MAX_FINISH_BYTES = 12 * 1024 * 1024;
    static final int MAX_SMALL_BYTES = 4 * 1024;
    private static final Pattern JOB_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final byte[] expectedDigest;
    private final ScriptRunDispatchService dispatch;
    private final ObjectMapper json = new ObjectMapper();

    public ScriptRunnerInternalController(@Value("${UI2_SCRIPT_RUNNER_TOKEN_SHA256:}") String digest,
            ScriptRunDispatchService dispatch) {
        this.expectedDigest = parseDigest(digest);
        this.dispatch = dispatch;
    }

    /** Returns {@code true} when {@code digest} is a valid lowercase 64-character SHA-256 hex digest. */
    public static boolean isValidTokenDigest(String digest) {
        return parseDigest(digest) != null;
    }

    /** Returns {@code true} when this service instance has a configured broker token digest. */
    public boolean isTokenConfigured() {
        return expectedDigest != null;
    }

    private static byte[] parseDigest(String digest) {
        try {
            String trimmed = digest == null ? null : digest.strip();
            return trimmed != null && trimmed.matches("[0-9a-f]{64}") ? HexFormat.of().parseHex(trimmed) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @PostMapping(START)
    public ResponseEntity<Map<String, Object>> start(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            HttpServletRequest request) throws IOException {
        ResponseEntity<Map<String, Object>> gate = gate(token);
        if (gate != null) return gate;
        JsonNode body = readJson(request, MAX_SMALL_BYTES);
        if (body == null) return refused(400, "SCRIPT_RUNNER_REQUEST_INVALID");
        String jobId = jobId(body);
        if (jobId == null || !body.path("lease_epoch").canConvertToLong()) return refused(400, "SCRIPT_RUNNER_REQUEST_INVALID");
        StartOutcome outcome = dispatch.start(jobId, body.path("lease_epoch").asLong());
        if (outcome instanceof StartOutcome.Refused r) return refused(r.status(), r.code());
        ScriptRunDispatchService.RunBundle bundle = ((StartOutcome.Started) outcome).bundle();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job_id", bundle.jobId());
        out.put("script_id", bundle.scriptId());
        out.put("version", bundle.version());
        out.put("kind", bundle.kind());
        out.put("timeout_s", bundle.timeoutSeconds());
        out.put("egress", bundle.egress());
        out.put("body_base64", Base64.getEncoder().encodeToString(bundle.body()));
        out.put("credentials", bundle.credentials().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref_id", c.refId());
            m.put("alias", c.alias());
            m.put("username", c.username());
            m.put("secret", c.secret());
            return m;
        }).toList());
        out.put("session_cookie", bundle.sessionCookie());
        out.put("session_csrf", bundle.sessionCsrf());
        out.put("warnings", bundle.warnings());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    @PostMapping(FINISH)
    public ResponseEntity<Map<String, Object>> finish(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            HttpServletRequest request) throws IOException {
        ResponseEntity<Map<String, Object>> gate = gate(token);
        if (gate != null) return gate;
        if (request.getContentLengthLong() > MAX_FINISH_BYTES) return refused(413, "SCRIPT_RUNNER_BODY_TOO_LARGE");
        JsonNode body = readJson(request, MAX_FINISH_BYTES);
        if (body == null) return refused(400, "SCRIPT_RUNNER_REQUEST_INVALID");
        String jobId = jobId(body);
        if (jobId == null || !body.path("lease_epoch").canConvertToLong()) return refused(400, "SCRIPT_RUNNER_REQUEST_INVALID");
        JsonNode exit = body.path("exit_code");
        FinishReport report = new FinishReport(
                body.path("outcome").asText(""),
                exit.isInt() ? exit.asInt() : null,
                body.path("duration_ms").asLong(0L),
                body.path("stdout").asText(""),
                body.path("stderr").asText(""),
                body.path("stdout_cut").asBoolean(false),
                body.path("stderr_cut").asBoolean(false),
                body.path("failure_reason").isTextual() ? body.path("failure_reason").asText() : null);
        return answer(dispatch.finish(jobId, body.path("lease_epoch").asLong(), report));
    }

    @PostMapping(ABORT)
    public ResponseEntity<Map<String, Object>> abort(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            HttpServletRequest request) throws IOException {
        ResponseEntity<Map<String, Object>> gate = gate(token);
        if (gate != null) return gate;
        JsonNode body = readJson(request, MAX_SMALL_BYTES);
        if (body == null) return refused(400, "SCRIPT_RUNNER_REQUEST_INVALID");
        String jobId = jobId(body);
        if (jobId == null || !body.path("lease_epoch").canConvertToLong()) return refused(400, "SCRIPT_RUNNER_REQUEST_INVALID");
        return answer(dispatch.abort(jobId, body.path("lease_epoch").asLong()));
    }

    private ResponseEntity<Map<String, Object>> gate(String token) {
        if (expectedDigest == null) return ResponseEntity.notFound().build();
        byte[] actual = token == null ? new byte[32] : sha256(token);
        if (!(MessageDigest.isEqual(expectedDigest, actual) && token != null)) {
            return refused(403, "SCRIPT_RUNNER_TOKEN_REFUSED");
        }
        return null;
    }

    private static String jobId(JsonNode body) {
        String id = body.path("job_id").asText(null);
        return id != null && JOB_ID.matcher(id).matches() ? id : null;
    }

    private JsonNode readJson(HttpServletRequest request, int max) throws IOException {
        try (var in = request.getInputStream()) {
            byte[] data = in.readNBytes(max + 1);
            if (data.length > max || data.length == 0) return null;
            JsonNode node = json.readTree(data);
            return node != null && node.isObject() ? node : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> answer(Optional<Refusal> refusal) {
        return refusal.map(r -> refused(r.status(), r.code())).orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static ResponseEntity<Map<String, Object>> refused(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
