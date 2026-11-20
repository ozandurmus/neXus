package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.service.api.NotificationSettingsController;

class NotificationMailTemplateTest {

    @TempDir
    Path tempDir;

    private static NotificationSettings settings(int port) {
        List<NotificationRoute> routes = NotificationRoute.TYPES.stream()
                .map(t -> new NotificationRoute(t, true, "soc@example.test", null, null))
                .toList();
        return new NotificationSettings(false, null, 514, "udp", 16, true, "127.0.0.1", port, false,
                "alerts@example.test", "soc@example.test", false, false, null, routes, NotificationSettings.IDENTITY_MASKED);
    }

    @Test
    void everyNotificationRouteTypeRendersItsTemplateWithBandSummaryLinkAndFooter() {
        for (String type : NotificationRoute.TYPES) {
            NotificationMailTemplate.RenderedMail withLink = NotificationMailTemplate.renderSample(
                    type, "Overwatch", "https://overwatch.example.test/", "prod-eu-1");
            NotificationMailTemplate.TypeTemplateSpec spec = NotificationMailTemplate.spec(type);

            assertEquals("Overwatch – " + NotificationRoute.TITLES.get(type) + ": "
                    + NotificationMailTemplate.sampleBatch(type).lines().size() + " event(s)", withLink.subject());

            String html = withLink.html();
            assertTrue(html.startsWith("<!DOCTYPE html><html lang=\"en\">"), type);
            assertFalse(html.contains("Notification Digest"), type);
            assertFalse(html.contains("event(s)"), type + " severity band must not repeat the count");
            assertTrue(html.contains(spec.title()), type);
            assertTrue(html.contains("background-color:" + withLink.band().barHex), type);
            assertTrue(html.contains("What to do"), type);
            assertTrue(html.contains(NotificationMailTemplate.escapeHtml(spec.whatToDo())), type);
            assertTrue(html.contains("href=\"https://overwatch.example.test"
                    + NotificationMailTemplate.escapeHtml(spec.screenPath()) + "\""), type);
            assertTrue(html.contains("Overwatch · prod-eu-1 · manage in Settings &gt; Notifications"), type);
            assertFalse(html.contains("sent by"), type);
            assertFalse(html.contains("(UTC)"), type);
            assertFalse(html.contains("Recorded "), type);
            assertFalse(html.contains("Z</td>"), type + " times must be local, not ISO");
            assertTrue(html.contains("18.11.2026 12:15") || "security_scan".equals(type), type);

            // Outlook / security invariants: inline styles only, no external CSS, no remote images, no scripts.
            assertFalse(html.toLowerCase().contains("<script"), type);
            assertFalse(html.toLowerCase().contains("<style"), type);
            assertFalse(html.toLowerCase().contains("<link"), type);
            assertFalse(html.toLowerCase().contains("<img"), type);

            // Plain text mirrors the reduced content.
            String text = withLink.text();
            assertTrue(text.contains(spec.title()), type);
            assertTrue(text.contains("What to do: " + spec.whatToDo()), type);
            assertTrue(text.contains("Overwatch · prod-eu-1 · manage in Settings > Notifications"), type);
            assertFalse(text.contains("Z "), type);

            // When base URL is unset, every link (screen and job) is omitted.
            NotificationMailTemplate.RenderedMail withoutLink = NotificationMailTemplate.renderSample(
                    type, "Overwatch", null, "prod-eu-1");
            assertFalse(withoutLink.html().contains("<a href="), type + " should omit link when base URL is unset");
            assertFalse(withoutLink.html().contains(">Job<"), type + " should omit the job column without a base URL");
            assertFalse(withoutLink.text().contains("http"), type);
        }

        assertEquals(NotificationMailTemplate.Band.NEUTRAL,
                NotificationMailTemplate.renderSample("admin_event").band());
        assertEquals(NotificationMailTemplate.Band.AMBER,
                NotificationMailTemplate.renderSample("backup_failure").band());
        assertEquals(NotificationMailTemplate.Band.AMBER,
                NotificationMailTemplate.renderSample("job_failure").band());
        assertEquals(NotificationMailTemplate.Band.RED,
                NotificationMailTemplate.renderSample("security_scan").band());
        assertEquals(NotificationMailTemplate.Band.RED,
                NotificationMailTemplate.renderSample("device_crash_file").band());
        assertTrue(NotificationMailTemplate.renderSample("job_failure").html().contains(">FAILED<"));
        assertTrue(NotificationMailTemplate.renderSample("security_scan").html().contains(">HIGH<"));
        assertTrue(NotificationMailTemplate.renderSample("admin_event").html().contains(">INFO<"));
    }

    @Test
    void jobFailureSampleReadsLikeAPersonWouldSayItWithPlainWordsAndLinks() {
        NotificationMailTemplate.RenderedMail mail = NotificationMailTemplate.renderSample(
                "job_failure", "Overwatch", "https://overwatch.example.test", "production");
        assertTrue(mail.text().contains("2 jobs failed: 1 inventory collection, 1 failover (outcome unknown)."));
        assertFalse(mail.html().contains("cp_cluster_failover"));
        assertFalse(mail.html().contains("job-inv-204</td>"), "the job id is a link, not a raw string");
        assertTrue(mail.html().contains(
                "<a href=\"https://overwatch.example.test/?screen=operations&amp;tab=jobs&amp;job=job-inv-204\""));
        assertTrue(mail.html().contains(">Open job</a>"));
        assertTrue(mail.html().contains("FW-BRAVO-02"), "real device names stay");
        assertTrue(NotificationMailTemplate.renderSample("admin_event").text()
                .contains("2 administrative changes: 1 device registration, 1 role binding creation."));
    }

    @Test
    void backupFailureMailNamesMissingCredentialSlotAndLinksToAdministrationCredentials() {
        Instant t1 = Instant.parse("2026-11-20T02:04:00Z");
        Instant t2 = Instant.parse("2026-11-20T02:05:00Z");
        NotificationMailRouter.Batch batch = new NotificationMailRouter.Batch(
                List.of(
                        t1 + "  FW-TANGO-04  cp_spark_sftp_backup: credential_unresolvable: Spark backup or receiver credential is missing  /operations/jobs/job-sp-1",
                        t2 + "  FW-BRAVO-02  cp_spark_sftp_backup: credential_unresolvable: Spark backup receiver credential (backup_receiver) is missing (Administration > Credentials)  /operations/jobs/job-sp-2"),
                List.of(
                        List.of(t1.toString(), "FW-TANGO-04", "cp_spark_sftp_backup",
                                "credential_unresolvable: Spark backup or receiver credential is missing",
                                "/operations/jobs/job-sp-1"),
                        List.of(t2.toString(), "FW-BRAVO-02", "cp_spark_sftp_backup",
                                "credential_unresolvable: Spark backup receiver credential (backup_receiver) is missing (Administration > Credentials)",
                                "/operations/jobs/job-sp-2")),
                0L, t2, Map.of());

        NotificationMailTemplate.RenderedMail mail = NotificationMailTemplate.render(
                "backup_failure", batch, "Overwatch", "https://overwatch.example.test", "production");

        assertTrue(mail.html().contains("Missing Spark backup credential or backup receiver credential (backup_receiver)"),
                mail.html());
        assertTrue(mail.html().contains("Missing backup receiver credential (backup_receiver)"), mail.html());
        assertTrue(mail.html().contains(
                "href=\"https://overwatch.example.test/?screen=administration&amp;tab=registry&amp;section=credentials\""),
                mail.html());
        assertTrue(mail.html().contains(">Administration &gt; Credentials</a>"), mail.html());

        assertTrue(mail.text().contains("Missing Spark backup credential or backup receiver credential (backup_receiver) — "
                + "Administration > Credentials (https://overwatch.example.test/?screen=administration&tab=registry&section=credentials)"),
                mail.text());
        assertTrue(mail.text().contains("Missing backup receiver credential (backup_receiver) — "
                + "Administration > Credentials (https://overwatch.example.test/?screen=administration&tab=registry&section=credentials)"),
                mail.text());

        NotificationMailTemplate.RenderedMail withoutBaseUrl = NotificationMailTemplate.render(
                "backup_failure", batch, "Overwatch", null, "production");
        assertFalse(withoutBaseUrl.html().contains("<a href="));
        assertTrue(withoutBaseUrl.text().contains("Missing backup receiver credential (backup_receiver) — Administration > Credentials"));
    }

    @Test
    void timesAreEuropeIstanbulLocalWithoutSecondsOrZone() {
        assertEquals("18.11.2026 12:15", NotificationMailTemplate.localTime("2026-11-18T09:15:00Z"));
        assertEquals("01.01.2027 02:59", NotificationMailTemplate.localTime("2026-12-31T23:59:30Z"));
        assertEquals("not a time", NotificationMailTemplate.localTime("not a time"));
        assertEquals("since 18.11.2026 05:05; held",
                NotificationMailTemplate.localizeTimes("since 2026-11-18T02:05:00Z; held"));
    }

    @Test
    void writeSamplesProducesOneSelfContainedHtmlFilePerNotificationType() throws Exception {
        Path outDir = tempDir.resolve("samples");
        NotificationMailTemplate.writeSamples(outDir, "Overwatch", "https://overwatch.example.test", "production");
        for (String type : NotificationRoute.TYPES) {
            Path file = outDir.resolve(type + ".html");
            assertTrue(Files.isRegularFile(file), type);
            String html = Files.readString(file);
            assertTrue(html.contains(NotificationRoute.TITLES.get(type)), type);
            assertTrue(html.contains("Overwatch · production · manage in Settings &gt; Notifications"), type);
        }
        String externalDir = System.getenv("NEXUS_MAIL_SAMPLES_DIR");
        if (externalDir != null && !externalDir.isBlank()) {
            NotificationMailTemplate.writeSamples(
                    Path.of(externalDir), "Overwatch", "https://overwatch.example.test", "production");
        }
    }

    @Test
    void everyTypeEscapesHtmlInValuesAndPreventsScriptInjection() {
        String payload = "<script>alert(\"xss\" & '1')</script>";
        String expectedEscaped = "&lt;script&gt;alert(&quot;xss&quot; &amp; &#39;1&#39;)&lt;/script&gt;";

        for (String type : NotificationRoute.TYPES) {
            // The payload sits in every column of the raw row so whichever columns a template keeps carry it.
            List<String> injectedRow = new ArrayList<>(List.of("2026-11-18T10:00:00Z", payload, payload, payload,
                    payload, payload));
            if ("security_scan".equals(type)) {
                injectedRow = new ArrayList<>(List.of("semgrep", payload, "HIGH", payload, "new", "yes"));
            }
            String rawLine = "2026-11-18T10:00:00Z  " + payload;
            NotificationMailRouter.Batch batch = new NotificationMailRouter.Batch(
                    List.of(rawLine),
                    List.of(injectedRow),
                    "security_scan".equals(type) ? NotificationMailTemplate.sampleBatch("security_scan").scans() : List.of(),
                    1L, Instant.parse("2026-11-18T10:00:00Z"), Map.of());

            NotificationMailTemplate.RenderedMail mail = NotificationMailTemplate.render(
                    type, batch, "Overwatch", "https://overwatch.example.test", "production");

            assertFalse(mail.html().contains("<script>"), type + " leaked unescaped <script>");
            assertFalse(mail.html().contains("</script>"), type + " leaked unescaped </script>");
            assertTrue(mail.html().contains(expectedEscaped), type + " did not escape injected payload");
        }
    }

    @Test
    void plainTextPartCarriesTheSameContentAndHeadersAreValidMultipartAlternativeForEveryType() {
        NotificationSettings smtp = settings(25);
        for (String type : NotificationRoute.TYPES) {
            NotificationMailRouter.Batch batch = NotificationMailTemplate.sampleBatch(type);
            NotificationMailTemplate.RenderedMail mail = NotificationMailTemplate.render(
                    type, batch, "Overwatch", "https://overwatch.example.test", "production");

            // Parity: every device name and the same summary sentence appear in both parts.
            String htmlText = mail.html().replaceAll("<[^>]+>", " ");
            for (List<String> row : batch.rows()) {
                if ("security_scan".equals(type)) break;
                String device = row.get(1);
                if (device.startsWith("FW-") || device.startsWith("CLS-")) {
                    assertTrue(mail.text().contains(device), type + " text lacks " + device);
                    assertTrue(htmlText.contains(device), type + " html lacks " + device);
                }
            }
            String summary = mail.text().lines().skip(2).findFirst().orElseThrow();
            assertTrue(htmlText.contains(NotificationMailTemplate.escapeHtml(summary).replace("&#39;", "'")), type);

            String mime = SmtpRelaySender.formatMessage(
                    smtp, List.of("soc@example.test"), mail.subject(), mail.text(), mail.html());

            assertTrue(mime.contains("From: " + SmtpRelaySender.encodeHeader(MailBrand.name()) + " <alerts@example.test>\r\n"));
            assertTrue(mime.contains("To: soc@example.test\r\n"));
            assertTrue(mime.contains("MIME-Version: 1.0\r\n"));
            assertTrue(mime.contains("Content-Type: multipart/alternative; boundary=\"=_nexus_"));

            String subjectHeader = mime.lines()
                    .filter(l -> l.startsWith("Subject: "))
                    .findFirst()
                    .orElseThrow()
                    .substring("Subject: ".length());
            assertTrue(subjectHeader.startsWith("=?UTF-8?B?"), subjectHeader);
            assertEquals(mail.subject(), SmtpRelaySender.decodeHeader(subjectHeader));

            assertEquals(mail.text(), SmtpRelaySender.extractTextPart(mime), type + " text/plain part mismatch");
            assertEquals(mail.html(), SmtpRelaySender.extractHtmlPart(mime), type + " text/html part mismatch");
        }
    }

    @Test
    void everyTypeCapsRowsWithAndNMoreInBothPlainTextAndHtml() {
        for (String type : NotificationRoute.TYPES) {
            boolean scan = "security_scan".equals(type);
            int cap = scan ? NotificationMailTemplate.MAX_FINDING_ROWS : NotificationMailTemplate.MAX_ROWS;
            List<String> lines = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            for (int i = 0; i < cap + 5; i++) {
                String token = "row-marker-" + i + "-end";
                lines.add("2026-11-18T10:00:00Z  " + token);
                rows.add(scan
                        ? List.of("semgrep", token, "HIGH", "where", "new", "no")
                        : List.of("2026-11-18T10:00:00Z", token, token, token, token, token));
            }
            NotificationMailRouter.Batch batch = new NotificationMailRouter.Batch(
                    lines, rows,
                    scan ? NotificationMailTemplate.sampleBatch("security_scan").scans() : List.of(),
                    205L, Instant.parse("2026-11-18T10:00:00Z"), Map.of());

            NotificationMailTemplate.RenderedMail mail = NotificationMailTemplate.render(
                    type, batch, "Overwatch", null, "production");

            String last = "row-marker-" + (cap - 1) + "-end";
            String over = "row-marker-" + cap + "-end";
            assertTrue(mail.text().contains(last), type);
            assertFalse(mail.text().contains(over), type);
            assertTrue(mail.text().contains("and 5 more\n"), type);
            assertTrue(mail.html().contains(last), type);
            assertFalse(mail.html().contains(over), type);
            assertTrue(mail.html().contains("and 5 more"), type);
        }
    }

    @Test
    void securityScanRendersCountsOnlyWhenFindingsAbsentAndFindingsTableWhenPresent() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var root = json.createObjectNode();
        root.put("schema_version", 1)
                .put("generated_at", "2026-11-18T03:00:00Z")
                .put("scan_errors", 0)
                .put("blocking", 1)
                .put("passed", false);
        var counts = root.putObject("counts");
        for (String tool : SecurityScanSummary.TOOLS) {
            var severities = counts.putObject(tool);
            for (String severity : SecurityScanSummary.SEVERITIES) {
                severities.putObject(severity)
                        .put("new", "semgrep".equals(tool) && "HIGH".equals(severity) ? 1 : 0)
                        .put("fixed", 0)
                        .put("accepted", 0)
                        .put("existing", "trivy".equals(tool) && "MEDIUM".equals(severity) ? 1 : 0);
            }
        }
        Path summaryFile = tempDir.resolve("scan.json");
        Files.writeString(summaryFile, root.toString());

        // 1. Counts-only summary (no findings list)
        NotificationMailRouter.Batch countsOnlyBatch = SecurityScanSummary.collect(tempDir, Instant.EPOCH);
        NotificationMailTemplate.RenderedMail countsOnlyMail = NotificationMailTemplate.render(
                "security_scan", countsOnlyBatch, "Overwatch", "https://overwatch.example.test", "production");
        assertTrue(countsOnlyMail.html().contains(">Tool<"));
        assertTrue(countsOnlyMail.html().contains("1 (+1 new)"), "semgrep HIGH: one open, one new");
        assertTrue(countsOnlyMail.html().contains(">1<"), "trivy MEDIUM: one open, none new");
        assertFalse(countsOnlyMail.html().contains("new=1"));
        assertFalse(countsOnlyMail.html().contains("accepted="));
        assertFalse(countsOnlyMail.html().contains(">LOW<"), "all-zero severity columns are dropped");
        assertFalse(countsOnlyMail.html().contains(">gitleaks<"), "all-zero tool rows are dropped");
        assertFalse(countsOnlyMail.html().contains(">Finding<"));

        // 2. Summary with findings list (including HTML special chars in location)
        var findings = root.putArray("findings");
        findings.addObject()
                .put("tool", "semgrep")
                .put("rule", "java.tls.weak-cipher")
                .put("severity", "HIGH")
                .put("location", "src/Tls.java:42")
                .put("status", "new")
                .put("fix_available", false);
        findings.addObject()
                .put("tool", "trivy")
                .put("rule", "CVE-2026-41002")
                .put("severity", "MEDIUM")
                .put("location", "nexus-ui2-service: libexpat")
                .put("status", "existing")
                .put("fix_available", true);
        root.put("findings_total", 2);
        Files.writeString(summaryFile, root.toString());

        NotificationMailRouter.Batch withFindingsBatch = SecurityScanSummary.collect(tempDir, Instant.EPOCH);
        assertTrue(withFindingsBatch.lines().getFirst().startsWith(countsOnlyBatch.lines().getFirst()),
                "the counts part of the plain-text line stays identical when findings are present");
        assertEquals(2, withFindingsBatch.rows().size(), "the findings table shows the validated HIGH/MEDIUM list");

        NotificationMailTemplate.RenderedMail withFindingsMail = NotificationMailTemplate.render(
                "security_scan", withFindingsBatch, "Overwatch", "https://overwatch.example.test", "production");
        assertTrue(withFindingsMail.html().contains(">Finding<"));
        assertTrue(withFindingsMail.html().contains(">Fix available<"));
        assertTrue(withFindingsMail.html().indexOf("java.tls.weak-cipher") < withFindingsMail.html().indexOf("CVE-2026-41002"), "HIGH first");
        assertTrue(withFindingsMail.html().contains("java.tls.weak-cipher"));
        assertTrue(withFindingsMail.html().contains("src/Tls.java:42"));
        assertTrue(withFindingsMail.html().contains("CVE-2026-41002"));

        // 3. Prohibited field inside findings is rejected
        ((com.fasterxml.jackson.databind.node.ObjectNode) findings.get(0)).put("snippet", "raw secret");
        Files.writeString(summaryFile, root.toString());
        assertThrows(IOException.class, () -> SecurityScanSummary.collect(tempDir, Instant.EPOCH));
    }

    @Test
    void securityScanSampleUsesPlainWordsForAcceptedReviewAndLinksToPlatformStatus() {
        NotificationMailTemplate.RenderedMail mail = NotificationMailTemplate.renderSample(
                "security_scan", "Overwatch", "https://overwatch.example.test", "production");
        assertTrue(mail.html().contains("1 accepted finding now has a fix available: CVE-2026-30999 (openssl-libs)."));
        assertFalse(mail.html().contains("attention="));
        assertFalse(mail.html().contains("fix_available="));
        assertTrue(mail.html().contains("href=\"https://overwatch.example.test/?screen=administration&amp;tab=platform-status\""));
        assertTrue(mail.html().contains("Open Administration &gt; Platform status"));
        assertFalse(mail.html().contains("lifecycle"));
        assertTrue(mail.text().contains("1 accepted finding now has a fix available: CVE-2026-30999 (openssl-libs)."));
    }

    @Test
    void testMailControllerSendsRenderedMultipartTemplateForRequestedType() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received = relay(server);

            NotificationSettingsStore store = mock(NotificationSettingsStore.class);
            NotificationSettings cfg = settings(server.getLocalPort());
            when(store.read()).thenReturn(cfg);
            NotificationSettingsController controller = new NotificationSettingsController(store);

            var response = controller.testMail(
                    new NotificationSettingsController.TestMailRequest("backup_size_anomaly", cfg));
            assertEquals(200, response.getStatusCode().value());
            assertEquals(Boolean.TRUE, response.getBody().get("sent"));

            String rawMime = received.join();
            String textPart = SmtpRelaySender.extractTextPart(rawMime);
            String htmlPart = SmtpRelaySender.extractHtmlPart(rawMime);
            assertTrue(textPart.contains("FW-TANGO-04 | Size jump | Backup size anomaly"));
            assertTrue(htmlPart.contains("Backup size deviations"));
            assertTrue(htmlPart.contains("FW-TANGO-04"));
            assertTrue(htmlPart.contains("What to do"));
            assertEquals("sample", response.getBody().get("data"));
            assertTrue(subject(rawMime).startsWith("[TEST, sample data] "));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMailControllerPrefersRecentRealEventsOverTheSample() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received = relay(server);
            NotificationSettingsStore store = mock(NotificationSettingsStore.class);
            NotificationSettings cfg = settings(server.getLocalPort());
            when(store.read()).thenReturn(cfg);
            Instant at = Instant.parse("2026-11-19T08:00:00Z");
            NotificationMailRouter.Batch batch = new NotificationMailRouter.Batch(
                    List.of(at + "  device_register  edge-fw-real-01"),
                    List.of(List.of(at.toString(), "device_register", "edge-fw-real-01")), 7, at, Map.of());
            NotificationMailTemplate.RenderedMail real = NotificationMailTemplate.render("admin_event", batch);
            NotificationForwarder forwarder = mock(NotificationForwarder.class);
            when(forwarder.recentEventsMail("admin_event")).thenReturn(java.util.Optional.of(real));
            org.springframework.beans.factory.ObjectProvider<NotificationForwarder> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(forwarder);
            NotificationSettingsController controller = new NotificationSettingsController(store, provider);

            var response = controller.testMail(new NotificationSettingsController.TestMailRequest("admin_event", cfg));
            assertEquals(Boolean.TRUE, response.getBody().get("sent"));
            assertEquals("recent_events", response.getBody().get("data"));
            String rawMime = received.join();
            assertTrue(subject(rawMime).startsWith("[TEST] "));
            assertTrue(SmtpRelaySender.extractHtmlPart(rawMime).contains("edge-fw-real-01"));
            assertFalse(SmtpRelaySender.extractHtmlPart(rawMime).contains("FW-TANGO-04"));
        }
    }

    private static String subject(String rawMime) {
        return SmtpRelaySender.decodeHeader(rawMime.lines().filter(l -> l.startsWith("Subject: ")).findFirst().orElse("Subject: ").substring(9));
    }

    private static CompletableFuture<String> relay(ServerSocket server) {
    return CompletableFuture.supplyAsync(() -> {
            try (var socket = server.accept();
                    var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                out.write("220 synthetic relay\r\n");
                out.flush();
                StringBuilder message = new StringBuilder();
                boolean data = false;
                for (String line; (line = in.readLine()) != null;) {
                    if (data) {
                        if (line.equals(".")) {
                            out.write("250 accepted\r\n");
                            out.flush();
                            data = false;
                        } else {
                            message.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                        }
                    } else if (line.startsWith("EHLO") || line.startsWith("MAIL FROM") || line.startsWith("RCPT TO")) {
                        out.write("250 ok\r\n");
                        out.flush();
                    } else if (line.equals("DATA")) {
                        data = true;
                        out.write("354 send data\r\n");
                        out.flush();
                    } else if (line.equals("QUIT")) {
                        out.write("221 bye\r\n");
                        out.flush();
                        break;
                    }
                }
                return message.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
