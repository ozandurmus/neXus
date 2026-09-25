package com.securityexpert.nexus.ui2.worker.backup.https;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.DownloadResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.TextResponse;

/**
 * Confirm and backup for vendors reached over HTTPS: Infoblox Grid Manager (WAPI) and Radware DefensePro
 * (VENDOR_BACKUP_CONTRACTS_2026_09_22.md §1 and §4, measurements addendum 2026-09-24). Nothing the device answered is
 * persisted outside the artefact; credentials and the Radware export passphrase come from the credential store.
 */
public final class HttpsVendorExecutor {

    private static final System.Logger LOG = System.getLogger(HttpsVendorExecutor.class.getName());
    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Duration LONG = Duration.ofSeconds(600);
    private static final int TEXT_MAX = 1024 * 1024;
    private static final long MAX_ARCHIVE = 20L * 1024 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The identity a confirm read returns; every field optional. */
    public record Identity(Optional<String> name, Optional<String> model, Optional<String> version) {
    }

    public sealed interface ConfirmOutcome {
        record Confirmed(Identity identity) implements ConfirmOutcome {
        }

        record AuthenticationFailed(String reason) implements ConfirmOutcome {
        }

        record Failed(String reason) implements ConfirmOutcome {
        }
    }

    private final com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls client;
    private final ArtefactStore artefactStore;
    private final Function<String, Credentials> credentials;

    public HttpsVendorExecutor(com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls client, ArtefactStore artefactStore, Function<String, Credentials> credentials) {
        this.client = Objects.requireNonNull(client, "client");
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    // ------------------------------------------------------------------------------------------------ confirm

    public ConfirmOutcome confirm(String vendor, Target target, String credentialRef) {
        Credentials creds;
        try {
            creds = credentials.apply(credentialRef);
        } catch (RuntimeException e) {
            return new ConfirmOutcome.Failed("credential reference not resolvable");
        }
        try {
            return switch (vendor) {
                case "infoblox" -> confirmInfoblox(target, creds);
                case "radware" -> confirmRadware(target, creds);
                case "radware_cyber_controller" -> confirmCyberController(target, creds);
                default -> new ConfirmOutcome.Failed("no HTTPS confirm for vendor " + vendor);
            };
        } catch (IOException e) {
            return new ConfirmOutcome.Failed("https: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConfirmOutcome.Failed("interrupted");
        }
    }

    private ConfirmOutcome confirmInfoblox(Target target, Credentials creds) throws IOException, InterruptedException {
        TextResponse doc = client.get(target, HttpsVendorPlan.INFOBLOX_WAPIDOC, creds, SHORT, TEXT_MAX);
        Optional<String> version = HttpsVendorPlan.parseWapiVersion(doc.body());
        if (version.isEmpty()) {
            return new ConfirmOutcome.Failed("the WAPI version could not be read from /wapidoc/ (HTTP " + doc.status() + ")");
        }
        TextResponse grid = client.get(target, HttpsVendorPlan.withVersion(HttpsVendorPlan.INFOBLOX_GRID, version.get()), creds, SHORT, TEXT_MAX);
        if (grid.status() == 401 || grid.status() == 403) {
            return new ConfirmOutcome.AuthenticationFailed("HTTP " + grid.status());
        }
        if (!grid.ok()) {
            return new ConfirmOutcome.Failed("grid read answered HTTP " + grid.status());
        }
        Optional<String> name = Optional.empty();
        JsonNode node = JSON.readTree(grid.body());
        if (node.isArray() && node.size() > 0 && node.get(0).hasNonNull("name")) {
            name = Optional.of(node.get(0).get("name").asText());
        }
        return new ConfirmOutcome.Confirmed(new Identity(name, Optional.of("Infoblox Grid Manager"), Optional.of("WAPI " + version.get())));
    }

    // ------------------------------------------------------------------------------------------------ Cyber Controller

    /** A logged-in Cyber Controller session; {@link #close} always logs out. */
    private final class CcSession implements AutoCloseable {
        final Target target;
        final Credentials session;

        CcSession(Target target, Credentials session) {
            this.target = target;
            this.session = session;
        }

        @Override
        public void close() {
            try {
                TextResponse r = client.postJson(target, HttpsVendorPlan.CC_LOGOUT, "{}", session, SHORT, 64 * 1024);
                LOG.log(System.Logger.Level.INFO, "[CYBER_CONTROLLER] logout HTTP {0}", r.status());
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "[CYBER_CONTROLLER] logout failed: {0}", e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Either a session, or why there is none ("authentication_failed: ..." when the Cyber Controller refused the login). */
    private record CcLogin(Optional<CcSession> session, String reason) {
    }

    private CcLogin ccLogin(Target target, Credentials creds) throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(Map.of("username", creds.username(), "password", new String(creds.password())));
        HttpsDeviceClient.SessionLogin login = client.login(target, HttpsVendorPlan.CC_LOGIN, body, SHORT);
        LOG.log(System.Logger.Level.INFO, "[CYBER_CONTROLLER] login HTTP {0}, session={1}", login.status(), login.cookie().isPresent());
        if (login.status() == 401 || login.status() == 403) {
            return new CcLogin(Optional.empty(), "authentication_failed: HTTP " + login.status());
        }
        if (login.status() < 200 || login.status() >= 300 || login.cookie().isEmpty()) {
            return new CcLogin(Optional.empty(), "the Cyber Controller login answered HTTP " + login.status()
                    + (login.cookie().isEmpty() ? " without a session cookie" : ""));
        }
        return new CcLogin(Optional.of(new CcSession(target, Credentials.session(login.cookie().get()))), "");
    }

    /** The device list, or empty when it could not be read; logs field names and size only (MEASURE FIRST). */
    private Optional<JsonNode> ccDevices(CcSession s) throws IOException, InterruptedException {
        TextResponse list = client.get(s.target, HttpsVendorPlan.CC_ALLDEVICES, s.session, Duration.ofSeconds(60), 8 * 1024 * 1024);
        if (!list.ok()) {
            LOG.log(System.Logger.Level.WARNING, "[CYBER_CONTROLLER] alldevices HTTP {0}", list.status());
            return Optional.empty();
        }
        JsonNode node = JSON.readTree(list.body());
        LOG.log(System.Logger.Level.INFO, "[CYBER_CONTROLLER] alldevices HTTP {0}, {1} bytes, field names {2}", list.status(),
                list.body().length(), HttpsVendorPlan.fieldNames(node));
        return Optional.of(node);
    }

    private ConfirmOutcome confirmCyberController(Target target, Credentials creds) throws IOException, InterruptedException {
        CcLogin login = ccLogin(target, creds);
        if (login.session().isEmpty()) {
            return login.reason().startsWith("authentication_failed")
                    ? new ConfirmOutcome.AuthenticationFailed(login.reason().substring("authentication_failed: ".length()))
                    : new ConfirmOutcome.Failed(login.reason());
        }
        try (CcSession s = login.session().get()) {
            if (ccDevices(s).isEmpty()) {
                return new ConfirmOutcome.Failed("the Cyber Controller device list could not be read");
            }
            return new ConfirmOutcome.Confirmed(new Identity(Optional.empty(), Optional.of("Radware Cyber Controller"), Optional.empty()));
        }
    }

    /** A Cyber Controller's device list, or why there is none ({@code failureClass}: AUTH_FAILED, UNREACHABLE, LIST_FAILED). */
    public record CcDeviceList(Optional<JsonNode> list, String failureClass) {
    }

    /** Login, the device list, logout (V67 calls 1, 2, 4) -- for discovery. */
    public CcDeviceList ccDeviceList(Target cc, String ccCredentialRef) {
        try {
            CcLogin login = ccLogin(cc, credentials.apply(ccCredentialRef));
            if (login.session().isEmpty()) {
                return new CcDeviceList(Optional.empty(), login.reason().startsWith("authentication_failed") ? "AUTH_FAILED" : "LOGIN_FAILED");
            }
            try (CcSession s = login.session().get()) {
                Optional<JsonNode> list = ccDevices(s);
                return new CcDeviceList(list, list.isPresent() ? "" : "LIST_FAILED");
            }
        } catch (RuntimeException e) {
            return new CcDeviceList(Optional.empty(), "CREDENTIAL_UNRESOLVABLE");
        } catch (IOException e) {
            return new CcDeviceList(Optional.empty(), "UNREACHABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CcDeviceList(Optional.empty(), "INTERRUPTED");
        }
    }

    /**
     * A DefensePro confirmed by the Cyber Controller that lists its address -- management-plane evidence, not a read
     * from the device itself, and the identity says so. Empty when that Cyber Controller does not list it or cannot be
     * read (the caller then reads the device directly).
     */
    public Optional<ConfirmOutcome> confirmViaCyberController(Target cc, String ccCredentialRef, String deviceAddress) {
        try {
            CcLogin login = ccLogin(cc, credentials.apply(ccCredentialRef));
            if (login.session().isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "[CYBER_CONTROLLER] confirm via Cyber Controller not possible: {0}", login.reason());
                return Optional.empty();
            }
            try (CcSession s = login.session().get()) {
                Optional<JsonNode> devices = ccDevices(s);
                if (devices.isEmpty() || !HttpsVendorPlan.listsAddress(devices.get(), deviceAddress)) {
                    return Optional.empty();
                }
                return Optional.of(new ConfirmOutcome.Confirmed(new Identity(Optional.empty(),
                        Optional.of("Radware DefensePro (listed by Cyber Controller)"), Optional.empty())));
            }
        } catch (RuntimeException | IOException e) {
            LOG.log(System.Logger.Level.WARNING, "[CYBER_CONTROLLER] confirm via Cyber Controller failed: {0}", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * A DefensePro backup through the Cyber Controller that manages it (PO, 2026-09-24): its {@code getcfg}, keys
     * included and encrypted with the device's export passphrase, streamed here ({@code saveToDb=false}: nothing is
     * written to the Cyber Controller's own backup slots). Empty when the Cyber Controller does not list the device --
     * the caller then takes the direct path.
     */
    public Optional<BackupResult> backupViaCyberController(Target cc, String ccCredentialRef, String deviceAddress,
            Optional<String> passphraseRef, String deviceId, String jobId) {
        if (passphraseRef.isEmpty()) {
            return Optional.of(new BackupResult.CredentialUnresolvable("no export passphrase credential is set for this Radware device: "
                    + "the export carries the private keys encrypted with it -- refused rather than a backup without keys"));
        }
        Credentials creds;
        String passphrase;
        try {
            creds = credentials.apply(ccCredentialRef);
            passphrase = new String(credentials.apply(passphraseRef.get()).password());
        } catch (RuntimeException e) {
            return Optional.of(new BackupResult.CredentialUnresolvable("Cyber Controller or passphrase credential not resolvable"));
        }
        try {
            CcLogin login = ccLogin(cc, creds);
            if (login.session().isEmpty()) {
                return Optional.of(new BackupResult.ConnectFailed("cyber controller: " + login.reason()));
            }
            try (CcSession s = login.session().get()) {
                Optional<JsonNode> devices = ccDevices(s);
                if (devices.isEmpty()) {
                    return Optional.of(new BackupResult.ConnectFailed("cyber controller: the device list could not be read"));
                }
                if (!HttpsVendorPlan.listsAddress(devices.get(), deviceAddress)) {
                    LOG.log(System.Logger.Level.INFO, "[CYBER_CONTROLLER] device {0} not listed; direct path", deviceId);
                    return Optional.empty();
                }
                BackupResult r = fetch(cc, "GET", HttpsVendorPlan.ccGetcfg(deviceAddress, passphrase), null, s.session, null, deviceId, jobId,
                        "radware", "DefensePro_configuration_via_cyber_controller", 256);
                return Optional.of(r);
            }
        } catch (IOException e) {
            return Optional.of(new BackupResult.ConnectFailed("cyber controller https: " + e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(new BackupResult.ConnectFailed("interrupted"));
        }
    }

    private ConfirmOutcome confirmRadware(Target target, Credentials creds) throws IOException, InterruptedException {
        // MEASURE FIRST (contract §1): no read is measured for model/version; the confirm proves reachability and the
        // credential only. Identity stays UNKNOWN.
        TextResponse root = client.get(target, HttpsVendorPlan.RADWARE_ROOT, creds, SHORT, 64 * 1024);
        if (root.status() == 401 || root.status() == 403) {
            return new ConfirmOutcome.AuthenticationFailed("HTTP " + root.status());
        }
        if (root.status() >= 500 || root.status() == 0) {
            return new ConfirmOutcome.Failed("the device answered HTTP " + root.status());
        }
        return new ConfirmOutcome.Confirmed(new Identity(Optional.empty(), Optional.of("Radware DefensePro"), Optional.empty()));
    }

    // ------------------------------------------------------------------------------------------------ backup

    /** @param passphraseRef Radware only: the credential whose password encrypts the private keys in the export */
    public BackupResult backup(String vendor, Target target, String credentialRef, Optional<String> passphraseRef,
            String deviceId, String jobId) {
        Credentials creds;
        try {
            creds = credentials.apply(credentialRef);
        } catch (RuntimeException e) {
            return new BackupResult.CredentialUnresolvable("credential reference not resolvable -- refused before any device contact");
        }
        try {
            return switch (vendor) {
                case "infoblox" -> backupInfoblox(target, creds, deviceId, jobId);
                case "radware" -> backupRadware(target, creds, passphraseRef, deviceId, jobId);
                default -> new BackupResult.ConnectFailed("no HTTPS backup for vendor " + vendor);
            };
        } catch (IOException e) {
            return new BackupResult.ConnectFailed("https: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BackupResult.ConnectFailed("interrupted");
        }
    }

    private BackupResult backupInfoblox(Target target, Credentials creds, String deviceId, String jobId)
            throws IOException, InterruptedException {
        TextResponse doc = client.get(target, HttpsVendorPlan.INFOBLOX_WAPIDOC, creds, SHORT, TEXT_MAX);
        Optional<String> version = HttpsVendorPlan.parseWapiVersion(doc.body());
        if (version.isEmpty()) {
            return new BackupResult.ConnectFailed("the WAPI version could not be read from /wapidoc/ (HTTP " + doc.status() + ")");
        }
        String ver = version.get();
        TextResponse start = client.postJson(target, HttpsVendorPlan.withVersion(HttpsVendorPlan.INFOBLOX_GETGRIDDATA, ver),
                HttpsVendorPlan.INFOBLOX_GETGRIDDATA_BODY, creds, LONG, TEXT_MAX);
        if (start.status() == 401 || start.status() == 403) {
            return new BackupResult.ConnectFailed("authentication_failed: HTTP " + start.status());
        }
        if (!start.ok()) {
            return new BackupResult.SubmitRefused("getgriddata answered HTTP " + start.status());
        }
        JsonNode node = JSON.readTree(start.body());
        String token = node.path("token").asText("");
        String url = node.path("url").asText("");
        if (token.isEmpty() || url.isEmpty()) {
            return new BackupResult.SubmitOutputUnparseable("getgriddata named no token or url");
        }
        try {
            Optional<String> path = HttpsDeviceClient.sameHostPath(target, url);
            if (path.isEmpty()) {
                return new BackupResult.SubmitRefused("the download url is not on the same appliance; refused");
            }
            return fetch(target, "GET", path.get(), null, creds, HttpsVendorPlan.INFOBLOX_DOWNLOAD_CONTENT_TYPE, deviceId, jobId,
                    "infoblox", "database.bak", 1);
        } finally {
            // Always -- the appliance holds the file until told (Backbox sends this without a version and fails).
            try {
                TextResponse done = client.postJson(target, HttpsVendorPlan.withVersion(HttpsVendorPlan.INFOBLOX_DOWNLOADCOMPLETE, ver),
                        JSON.writeValueAsString(Map.of("token", token)), creds, SHORT, 64 * 1024);
                LOG.log(System.Logger.Level.INFO, "[HTTPS_BACKUP] infoblox downloadcomplete HTTP {0}", done.status());
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "[HTTPS_BACKUP] infoblox downloadcomplete failed: {0}", e.getClass().getSimpleName());
            }
        }
    }

    private BackupResult backupRadware(Target target, Credentials creds, Optional<String> passphraseRef, String deviceId, String jobId) {
        if (passphraseRef.isEmpty()) {
            return new BackupResult.CredentialUnresolvable("no export passphrase credential is set for this Radware device: the "
                    + "export carries the private keys encrypted with it (IncludePKeys=on) -- refused rather than a backup without keys");
        }
        String passphrase;
        try {
            passphrase = new String(credentials.apply(passphraseRef.get()).password());
        } catch (RuntimeException e) {
            return new BackupResult.CredentialUnresolvable("export passphrase credential not resolvable");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("DownloadFormat", "cli");
        form.put("IncludePKeys", "on");
        form.put("passphrase", passphrase);
        return fetch(target, "POST", HttpsVendorPlan.RADWARE_RECEIVE_CONFIGURATION, form, creds, null, deviceId, jobId, "radware",
                "DefensePro_backup_configuration.txt", 1024);
    }

    private BackupResult fetch(Target target, String method, String path, Map<String, String> form, Credentials creds,
            String requestContentType, String deviceId, String jobId, String vendor, String memberName, long minBytes) {
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, vendor, false);
        } catch (IOException e) {
            return new BackupResult.ArtefactStoreFailed("artefact store open failed: " + e.getMessage());
        }
        HeadCapture head = new HeadCapture(handle.sink());
        DownloadResult r = client.download(target, method, path, form, creds, requestContentType, head, MAX_ARCHIVE, LONG);
        if (!(r instanceof DownloadResult.Downloaded d)) {
            closeQuietly(handle);
            return r instanceof DownloadResult.Refused refused && (refused.status() == 401 || refused.status() == 403)
                    ? new BackupResult.ConnectFailed("authentication_failed: HTTP " + refused.status())
                    : new BackupResult.SubmitRefused("download: " + (r instanceof DownloadResult.Refused x ? x.reason()
                            // an exception's text may echo the request (the getcfg query holds the passphrase): class only
                            : ((DownloadResult.Failed) r).reason().split(":", 2)[0]));
        }
        if (d.bytes() < minBytes) {
            closeQuietly(handle);
            return new BackupResult.SubmitOutputUnparseable("the export was " + d.bytes() + " bytes; not a backup");
        }
        ArtefactStore.ArtefactMetadata metadata;
        try {
            metadata = handle.finish();
        } catch (IOException e) {
            closeQuietly(handle);
            return new BackupResult.ArtefactStoreFailed("artefact store finish failed: " + e.getMessage());
        }
        LOG.log(System.Logger.Level.INFO, "[HTTPS_BACKUP] {0} {1} bytes, content-type={2}, gzip_magic={3}", vendor, d.bytes(),
                d.contentType().orElse("-"), head.gzip());
        return new BackupResult.Completed(metadata, memberName, Optional.empty(), Optional.empty());
    }

    /** Passes bytes through, remembering the first two for a format check (gzip magic) -- nothing else is kept. */
    private static final class HeadCapture extends OutputStream {
        private final OutputStream out;
        private final ByteArrayOutputStream head = new ByteArrayOutputStream(2);

        HeadCapture(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            if (head.size() < 2) {
                head.write(b);
            }
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len && head.size() < 2; i++) {
                head.write(b[off + i]);
            }
            out.write(b, off, len);
        }

        boolean gzip() {
            byte[] h = head.toByteArray();
            return h.length == 2 && (h[0] & 0xff) == 0x1f && (h[1] & 0xff) == 0x8b;
        }
    }

    private static void closeQuietly(ArtefactStore.ArtefactHandle h) {
        try {
            h.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
