package com.securityexpert.nexus.ui2.worker.backup.https;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.FormReply;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.DownloadResult;

/**
 * Pulse Secure / Ivanti Connect Secure over its admin web session (VENDOR_BACKUP_CONTRACTS §2; the request sequence
 * of Backbox trail 34411066, 2026-09-22): form login, "continue the session" when another admin session exists, the
 * xsauth token, then sysinfo (identity), the XML export's network part (inventory, MEASURE FIRST: element names logged),
 * or the four exports (backup: system.cfg, users.cfg, ivs.cfg, export.xml -- users.cfg measured at 123 MB, so each
 * export goes through a temporary file). Logout always. The password travels only in the login form; nothing is logged
 * but counts, element names and shapes.
 */
public final class PulseSecureExecutor {

    private static final System.Logger LOG = System.getLogger(PulseSecureExecutor.class.getName());
    static final String LOGIN = "/dana-na/auth/url_admin/login.cgi";
    static final String CONFIG_SYSTEM = "/dana-admin/cached/config/config.cgi?type=system";
    static final String SYSINFO = "/dana-admin/sysinfo/sysinfo.cgi";
    static final String EXPORT_XML = "/dana-admin/cached/config/config.cgi?type=exportxml";
    static final String LOGOUT = "/dana-na/auth/logout.cgi?xsauth=";
    private static final Duration SHORT = Duration.ofSeconds(60);
    private static final Duration LONG = Duration.ofSeconds(600);
    private static final long MAX_EXPORT = 2L * 1024 * 1024 * 1024;

    private final HttpsDeviceCalls client;
    private final Function<String, Credentials> credentials;
    private final ArtefactStore artefactStore;

    public PulseSecureExecutor(HttpsDeviceCalls client, Function<String, Credentials> credentials, ArtefactStore artefactStore) {
        this.client = client;
        this.credentials = credentials;
        this.artefactStore = artefactStore;
    }

    /** A logged-in admin session: its cookie jar and xsauth token. */
    static final class Session {
        final Map<String, String> jar = new LinkedHashMap<>();
        String xsauth = "";

        void absorb(FormReply r) {
            for (String c : r.setCookies()) {
                int eq = c.indexOf('=');
                jar.put(c.substring(0, eq), c.substring(eq + 1));
            }
        }

        String header() {
            StringBuilder sb = new StringBuilder();
            jar.forEach((k, v) -> sb.append(sb.length() > 0 ? "; " : "").append(k).append('=').append(v));
            return sb.toString();
        }
    }

    sealed interface Login {
        record Ok(Session session) implements Login {
        }

        record Refused(String reason, boolean auth) implements Login {
        }
    }

    /** A hidden input's value, whichever order name and value come in. */
    static Optional<String> hidden(String html, String name) {
        if (html == null) {
            return Optional.empty();
        }
        Matcher a = Pattern.compile("<input[^>]*name=[\"']" + Pattern.quote(name) + "[\"'][^>]*value=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        if (a.find()) {
            return Optional.of(a.group(1));
        }
        Matcher b = Pattern.compile("<input[^>]*value=[\"']([^\"']*)[\"'][^>]*name=[\"']" + Pattern.quote(name) + "[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        return b.find() ? Optional.of(b.group(1)) : Optional.empty();
    }

    private String follow(Target t, Session s, FormReply r) throws IOException, InterruptedException {
        String body = r.body();
        Optional<String> loc = r.location();
        for (int hop = 0; hop < 4 && loc.isPresent(); hop++) {
            String path = relative(loc.get());
            if (path == null) {
                break;
            }
            FormReply next = client.formRequest(t, path, null, s.header(), SHORT, 1 << 20);
            s.absorb(next);
            body = next.body();
            loc = next.location();
        }
        return body;
    }

    /** A same-appliance path from a Location header (absolute URLs reduced to their path; other hosts refused). */
    static String relative(String location) {
        if (location.startsWith("/")) {
            return location;
        }
        Matcher m = Pattern.compile("^https?://[^/]+(/.*)$").matcher(location);
        return m.matches() ? m.group(1) : null;
    }

    Login login(Target t, String credentialRef) throws IOException, InterruptedException {
        Credentials c;
        try {
            c = credentials.apply(credentialRef);
        } catch (RuntimeException e) {
            return new Login.Refused("credential reference not resolvable", false);
        }
        Session s = new Session();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("tz_offset", "180");
        form.put("username", c.username());
        form.put("password", new String(c.password()));
        form.put("realm", "Admin Users");
        form.put("btnSubmit", "Sign In");
        FormReply r = client.formRequest(t, LOGIN, form, "", SHORT, 1 << 20);
        s.absorb(r);
        String body = follow(t, s, r);
        Optional<String> formData = hidden(body, "FormDataStr");
        if (formData.isPresent()) {
            // another admin session exists: continue it (the appliance allows one per admin)
            Map<String, String> cont = new LinkedHashMap<>();
            cont.put("btnContinue", "Continue the session");
            cont.put("FormDataStr", formData.get());
            hidden(body, "xsauth").ifPresent(x -> cont.put("xsauth", x));
            FormReply r2 = client.formRequest(t, LOGIN, cont, s.header(), SHORT, 1 << 20);
            s.absorb(r2);
            follow(t, s, r2);
        }
        if (s.jar.keySet().stream().noneMatch(k -> k.toUpperCase(java.util.Locale.ROOT).startsWith("DSID"))) {
            return new Login.Refused("login refused (no admin session cookie)", true);
        }
        FormReply sys = client.formRequest(t, CONFIG_SYSTEM, null, s.header(), SHORT, 1 << 20);
        s.absorb(sys);
        s.xsauth = hidden(sys.body(), "xsauth").orElse("");
        if (s.xsauth.isEmpty()) {
            logout(t, s);
            return new Login.Refused("no xsauth token on the configuration page (HTTP " + sys.status() + ")", false);
        }
        return new Login.Ok(s);
    }

    void logout(Target t, Session s) {
        try {
            client.formRequest(t, LOGOUT + java.net.URLEncoder.encode(s.xsauth, StandardCharsets.UTF_8), null, s.header(), SHORT, 1 << 16);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final Pattern VERSION = Pattern.compile("\\b(\\d{1,2}\\.\\d{1,2}R\\d+(?:\\.\\d+)*)(?:\\s*\\(build\\s*(\\d+)\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL = Pattern.compile("\\b((?:PSA|ISA|MAG|SA)[-\\s]?\\d{3,4}[A-Z-]*|PSA-V|ISA-V|Virtual\\s+Appliance)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOSTNAME = Pattern.compile("(?is)host\\s*name\\s*:?\\s*</t[dh]>\\s*<td[^>]*>\\s*([^<\\s]+)");

    static HttpsVendorExecutor.Identity identity(String sysinfo) {
        String text = sysinfo == null ? "" : sysinfo;
        Matcher v = VERSION.matcher(text);
        Optional<String> version = v.find() ? Optional.of(v.group(1) + (v.group(2) != null ? " (build " + v.group(2) + ")" : "")) : Optional.empty();
        Matcher m = MODEL.matcher(text);
        Optional<String> model = m.find() ? Optional.of(m.group(1).toUpperCase(java.util.Locale.ROOT)) : Optional.empty();
        Matcher h = HOSTNAME.matcher(text);
        Optional<String> host = h.find() ? Optional.of(h.group(1)) : Optional.empty();
        return new HttpsVendorExecutor.Identity(host, model.or(() -> Optional.of("Pulse Secure")), version);
    }

    public HttpsVendorExecutor.ConfirmOutcome confirm(Target t, String credentialRef) {
        try {
            Login l = login(t, credentialRef);
            if (l instanceof Login.Refused r) {
                return r.auth() ? new HttpsVendorExecutor.ConfirmOutcome.AuthenticationFailed(r.reason())
                        : new HttpsVendorExecutor.ConfirmOutcome.Failed(r.reason());
            }
            Session s = ((Login.Ok) l).session();
            try {
                FormReply info = client.formRequest(t, SYSINFO, null, s.header(), SHORT, 1 << 20);
                HttpsVendorExecutor.Identity id = identity(info.body());
                LOG.log(System.Logger.Level.INFO, "[PULSE] confirm: sysinfo HTTP {0}, version={1} model={2} hostname={3}", info.status(),
                        id.version().isPresent(), id.model().isPresent(), id.name().isPresent());
                if (id.version().isEmpty()) {
                    return new HttpsVendorExecutor.ConfirmOutcome.Failed("sysinfo names no Pulse/Ivanti version (HTTP " + info.status() + ")");
                }
                return new HttpsVendorExecutor.ConfirmOutcome.Confirmed(id);
            } finally {
                logout(t, s);
            }
        } catch (IOException e) {
            return new HttpsVendorExecutor.ConfirmOutcome.Failed("https: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HttpsVendorExecutor.ConfirmOutcome.Failed("interrupted");
        }
    }

    /** The XML export limited to the network sections: interfaces and routes. */
    static Map<String, String> networkExportForm(String xsauth) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("action", "Export");
        f.put("type", "exportxml");
        for (String k : List.of("chkNetGeneral", "chkNetInternal", "chkNetExternal", "chkNetManagement", "chkNetVLAN", "chkNetHosts")) {
            f.put(k, "ON");
        }
        f.put("xsauth", xsauth);
        return f;
    }

    public HttpsVendorExecutor.InventoryOutcome inventory(Target t, String credentialRef) {
        try {
            Login l = login(t, credentialRef);
            if (l instanceof Login.Refused r) {
                return r.auth() ? new HttpsVendorExecutor.InventoryOutcome.AuthenticationFailed(r.reason())
                        : new HttpsVendorExecutor.InventoryOutcome.Failed(r.reason());
            }
            Session s = ((Login.Ok) l).session();
            try {
                HttpsVendorExecutor.Identity id = identity(client.formRequest(t, SYSINFO, null, s.header(), SHORT, 1 << 20).body());
                FormReply xml = client.formRequest(t, EXPORT_XML, networkExportForm(s.xsauth), s.header(), LONG, 16 << 20);
                PulseNetwork.Parsed net = PulseNetwork.parse(xml.body());
                LOG.log(System.Logger.Level.INFO, "[PULSE] inventory: export HTTP {0}, {1} chars; interfaces={2} routes={3}; MEASURE elements: {4}",
                        xml.status(), xml.body().length(), net.interfaces().size(), net.routes().size(), net.elementCounts());
                return new HttpsVendorExecutor.InventoryOutcome.Completed(
                        List.of(new InventoryContext(InventoryContext.PHYSICAL, net.interfaces(), net.routes())), List.of(), Optional.empty(), id);
            } finally {
                logout(t, s);
            }
        } catch (IOException e) {
            return new HttpsVendorExecutor.InventoryOutcome.Failed("https: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HttpsVendorExecutor.InventoryOutcome.Failed("interrupted");
        }
    }

    /** The four exports of Backbox's run; the .cfg exports encrypted with the device's export passphrase. */
    public BackupResult backup(Target t, String credentialRef, Optional<String> passphraseRef, String deviceId, String jobId) {
        if (passphraseRef.isEmpty()) {
            return new BackupResult.CredentialUnresolvable("no export passphrase credential is set for this Pulse Secure device: "
                    + "the .cfg exports are protected with it (VENDOR_BACKUP_CONTRACTS §2) -- refused rather than an unprotected export");
        }
        String pass;
        try {
            pass = new String(credentials.apply(passphraseRef.get()).password());
        } catch (RuntimeException e) {
            return new BackupResult.CredentialUnresolvable("export passphrase credential not resolvable");
        }
        Map<String, java.nio.file.Path> files = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        try {
            Login l = login(t, credentialRef);
            if (l instanceof Login.Refused r) {
                return new BackupResult.ConnectFailed((r.auth() ? "authentication_failed: " : "") + r.reason());
            }
            Session s = ((Login.Ok) l).session();
            try {
                for (String[] e : List.of(new String[] {"system", "system.cfg"}, new String[] {"user", "users.cfg"}, new String[] {"ivs", "ivs.cfg"})) {
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("txtPassword", pass);
                    f.put("txtPasswordConfirm", pass);
                    f.put("op", "Export");
                    f.put("type", e[0]);
                    f.put("btnDnload", "Save Config As...");
                    f.put("xsauth", s.xsauth);
                    fetch(t, s, "/dana-admin/download/" + (e[0].equals("user") ? "user" : e[0]) + ".cfg?url=/dana-admin/cached/config/export.cgi",
                            f, e[1], files, missing);
                }
                Map<String, String> x = new LinkedHashMap<>();
                for (String pair : BACKBOX_XML_FLAGS.split("&")) {
                    int eq = pair.indexOf('=');
                    x.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
                x.put("xsauth", s.xsauth);
                fetch(t, s, EXPORT_XML, x, "export.xml", files, missing);
            } finally {
                logout(t, s);
            }
            if (files.isEmpty()) {
                return new BackupResult.SubmitRefused("no export succeeded: " + String.join("; ", missing));
            }
            ArtefactStore.ArtefactHandle handle = artefactStore.open(deviceId, jobId, "pulse_secure", false);
            try {
                try (com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter tar =
                        new com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter(new java.util.zip.GZIPOutputStream(handle.sink()))) {
                    StringBuilder manifest = new StringBuilder("# Pulse Secure / Ivanti backup (neXus): four exports\n");
                    for (var e : files.entrySet()) {
                        long size = java.nio.file.Files.size(e.getValue());
                        manifest.append(e.getKey()).append('\t').append(size).append(" bytes\n");
                        try (OutputStream o = tar.begin(e.getKey(), size)) {
                            java.nio.file.Files.copy(e.getValue(), o);
                        }
                    }
                    missing.forEach(m -> manifest.append("MISSING: ").append(m).append('\n'));
                    tar.file("manifest.txt", manifest.toString().getBytes(StandardCharsets.UTF_8));
                }
                ArtefactStore.ArtefactMetadata metadata = handle.finish();
                LOG.log(System.Logger.Level.INFO, "[PULSE_BACKUP] stored {0} exports, {1} bytes, missing {2}", files.size(), metadata.plaintextBytes(), missing.size());
                return missing.isEmpty() ? new BackupResult.Completed(metadata, "pulse-secure-backup.tgz", Optional.empty(), Optional.empty())
                        : new BackupResult.Partial(metadata, "pulse-secure-backup.tgz", String.join("; ", missing));
            } catch (IOException e) {
                try {
                    handle.close();
                } catch (IOException ignored) {
                    // best effort
                }
                return new BackupResult.ArtefactStoreFailed("bundle could not be stored: " + e.getClass().getSimpleName());
            }
        } catch (IOException e) {
            return new BackupResult.ConnectFailed("https: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BackupResult.ConnectFailed("interrupted");
        } finally {
            for (java.nio.file.Path p : files.values()) {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** One export into a temporary file; an empty answer or the login page is a missing part, never a stored export. */
    private void fetch(Target t, Session s, String path, Map<String, String> form, String member, Map<String, java.nio.file.Path> files,
            List<String> missing) throws IOException {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("pulse-", ".part");
        byte[] head = new byte[512];
        int[] headLen = {0};
        DownloadResult r;
        try (OutputStream out = java.nio.file.Files.newOutputStream(tmp)) {
            OutputStream capture = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    if (headLen[0] < head.length) {
                        head[headLen[0]++] = (byte) b;
                    }
                    out.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    for (int i = 0; i < len && headLen[0] < head.length; i++) {
                        head[headLen[0]++] = b[off + i];
                    }
                    out.write(b, off, len);
                }
            };
            r = client.download(t, "POST", path, form, Credentials.session(s.header()), capture, MAX_EXPORT, LONG);
        }
        String start = new String(head, 0, headLen[0], StandardCharsets.ISO_8859_1).toLowerCase(java.util.Locale.ROOT);
        boolean loginPage = start.contains("<html") && !member.endsWith(".xml");
        if (!(r instanceof DownloadResult.Downloaded d) || d.bytes() <= 0 || loginPage) {
            java.nio.file.Files.deleteIfExists(tmp);
            missing.add(member + " (" + (r instanceof DownloadResult.Downloaded ? loginPage ? "an HTML page, not an export" : "empty"
                    : r.getClass().getSimpleName()) + ")");
            return;
        }
        files.put(member, tmp);
    }

    /** Backbox trail 34411066's export.xml flags, verbatim (every section, every list "all"). */
    static final String BACKBOX_XML_FLAGS = "action=Export&type=exportxml&chkDateTime=ON&chkCockpit=ON&chkLicenses=ON&chkDMI=ON&chkNCP=ON"
            + "&chkSensors=ON&chkClientTypes=ON&chkCertificates=ON&chkMtg=ON&chkVDI=ON&chkBMSync=ON&chkIKE=ON&chkSAML=ON&chkSecurity=ON"
            + "&chkNetGeneral=ON&chkNetInternal=ON&chkNetExternal=ON&chkNetManagement=ON&chkNetVLAN=ON&chkNetHosts=ON&chkNetIPFilter=ON"
            + "&chkClustering=ON&chkIfmap=ON&chkLogsEvents=ON&chkLogsUserAccess=ON&chkLogsAdminAccess=ON&chkLogsSensors=ON&chkLogsFilters=ON"
            + "&chkLogsClient=ON&chkSnmp=ON&url_list=&optUrls=all&page_list=&optSigninPage=all&notif_list=&optSigninNotif=all&sp_list="
            + "&chkMdP=on&chkIdP=on&optSP=all&auth_servers_list=&optAuth=all&hcpolicies_list=&esaps_list=&selectAllEndpoint=on"
            + "&chkHCOptions=on&optHC=all&chkHCOtherOptions=on&chkHCAVUpdate=on&chkHCPatchUpdate=on&chkHCPatchRemediate=on&chkIMV=on"
            + "&chkESAP=on&optESAP=all&admin_realms=&optAdminRealm=all&user_realms=&optUserRealm=all&user_role_list=&admin_role_list="
            + "&optAdmin=all&optUsers=all&web_profile_list=&file_windows_profile_list=&file_unix_profile_list=&samapp_profile_list="
            + "&samnetwork_profile_list=&telnet_profile_list=&term_profile_list=&vdi_profile_list=&chkProfilesHostedApplets=ON"
            + "&opt_web_profile=all&opt_file_windows_profile=all&opt_file_unix_profile=all&opt_samapp_profile=all&opt_samnetwork_profile=all"
            + "&opt_telnet_profile=all&opt_term_profile=all&opt_vdi_profile=all&WEBURL.ACCESS=ON&WEBURL.CACHING=ON&HOSTPORT.JAVA_ACCESS=ON"
            + "&WEBURL.JAVA_CODE_SIGNING=ON&WEBURL.REWRITING=ON&WEBURL.PASSTHROUGH=ON&WEBURL.CUSTOM_HEADERS=ON&WEBURL.SSO_BASICNTLM=ON"
            + "&WEBURL.SSO_POST=ON&WEBURL.SSO_HEADERS=ON&WEBURL.SAML_SSO=ON&WEBURL.SAML_ACCESS=ON&WEBURL.WEB_PROXY=ON&WEBURL.LAUNCH_JSAM=ON"
            + "&WEBURL.ACTIVEX_PARAM_REWRITING=ON&WEBURL.COMPRESSION=ON&WEBURL.PROTOCOL=ON&WEBURL.ENCODING=ON&WEBURL.WEB_CROSSDOMAINACCESS=ON"
            + "&HOSTPORT.CLIENT_AUTH=ON&WEBURL.SAML_CLOUDSSO=ON&Web_policy_options=ON&FILEPATH_WINDOWS.ALL=ON&FILEPATH_WINCREDS.ALL=ON"
            + "&FILEPATH_WINDOWS.COMPRESSION=ON&FILEPATH_NFS.ALL=ON&FILEPATH_NFS.COMPRESSION=ON&Files_policy_options=ON&HOSTPORT.SAM=ON"
            + "&SAM_policy_options=ON&HOSTPORT.TELNET_SSH=ON&Telnet_policy_options=ON&HOSTPORT.NETWORKCONNECT=ON&HOSTPORT.IP_POOLS=ON"
            + "&HOSTPORT.NETWORKCONNECT_ST_ROUTES=ON&BANDWIDTH.NC_BANDWIDTH=ON&HOSTPORT.WINTERMSERV=ON&WinTermServ_policy_options=ON"
            + "&MAILPROXY.MAIL_SETTINGS=ON&jam_client_configs_list=&jam_client_preconfigs_list=&jam_client_versions_list=&optJamConfigs=all"
            + "&optJamPreconfigs=all&optJamClientVersions=all&local_auth_users_list=&optLocalUsers=all&chkSysOptions=ON&chkPushConfig=ON"
            + "&chkArchiving=ON&chkSnapshot=ON&chkNodeMonitoring=ON&ivs_list=&optIVS=all&chkIVSProfiles=ON&chkIVSConf=ON";
}
