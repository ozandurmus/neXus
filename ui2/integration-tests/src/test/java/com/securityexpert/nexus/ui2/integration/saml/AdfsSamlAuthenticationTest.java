package com.securityexpert.nexus.ui2.integration.saml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.platform.PrincipalFingerprint;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;

/**
 * Migration plan Step 4, end to end: the real application (Tomcat, filter chain, gate chain, PostgreSQL 16, Flyway) with SAML
 * switched on through its own admin API, and {@link TestAdfsSamlIdp} standing in for ADFS. Covers a valid sign-in (both groups),
 * every refusal the plan names (bad signature, expired, wrong audience, replay, unknown group), the clock skew bound, the
 * encrypted-assertion path with a stored SP key, the single-active-session takeover, and that nothing of the assertion is stored.
 */
class AdfsSamlAuthenticationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_GROUP = "nexus-admins";
    private static final String USERS_GROUP = "nexus-users";

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static Path work;
    private static int port;
    private static String base;
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final TestAdfsSamlIdp IDP = new TestAdfsSamlIdp();
    private static String adminCookie;
    private static String adminCsrf;
    private static KeyPair spKeys;
    private static String acs;
    private static String spEntityId;

    record Resp(int status, Map<String, List<String>> headers, String body) {
        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        String cookie() {
            List<String> cookies = headers.get("set-cookie");
            if (cookies == null) return null;
            for (String cookie : cookies) {
                if (cookie.startsWith("ui2_session=")) {
                    String value = cookie.substring("ui2_session=".length());
                    int end = value.indexOf(';');
                    value = end < 0 ? value : value.substring(0, end);
                    return value.isEmpty() ? null : value;
                }
            }
            return null;
        }

        JsonNode json() throws Exception {
            return JSON.readTree(body);
        }
    }

    @BeforeAll
    static void boot() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("saml_adfs");
        work = Files.createTempDirectory("ui2-saml-");
        context = new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(AdfsSamlAuthenticationTest::dropMachineConnector))
                .resourceLoader(new org.springframework.core.io.DefaultResourceLoader(consoleBundleLoader()))
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--ui2.db.url=" + fixture.databaseJdbcUrl(),
                        "--ui2.db.user-file=" + fixture.appUserSecretFile(),
                        "--ui2.db.password-file=" + fixture.appPasswordSecretFile(),
                        "--ui2.db.migrate-user-file=" + fixture.migrateUserSecretFile(),
                        "--ui2.db.migrate-password-file=" + fixture.migratePasswordSecretFile(),
                        "--ui2.role-binding.group-reference-key-file=" + keyFile("group_ref.key"),
                        "--ui2.credential-store.key-file=" + keyFile("credential.key"),
                        "--ui2.failover.master-key-file=" + work.resolve("failover.key"),
                        "--ui2.packet-analysis.root=" + Files.createDirectory(work.resolve("pa")),
                        "--ui2.sanitizer.work-dir=" + Files.createDirectory(work.resolve("sanitizer")),
                        "--ui2.scheduling.enabled=false", "--ui2.saml.requests-per-minute=100000");
        port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
        base = "http://127.0.0.1:" + port;
        acs = base + "/login/saml2/sso/adfs";
        spEntityId = base + "/saml2/service-provider-metadata/adfs";
        Resp login = send("POST", "/login", Map.of("Content-Type", "application/json"),
                JSON.writeValueAsString(Map.of("username", "nexusadmin", "password", "nexusadmin")));
        adminCookie = login.cookie();
        assertNotNull(adminCookie, "the bootstrap administrator signs in");
        adminCsrf = send("GET", "/session/status", Map.of("Cookie", "ui2_session=" + adminCookie), null).json().path("csrf_token").asText();
        spKeys = TestAdfsSamlIdp.generateKeys();
        Resp saved = saveSettings(settings(60));
        assertEquals(200, saved.status(), saved.body());
    }

    @AfterAll
    static void shutDown() throws Exception {
        if (context != null) context.close();
        if (fixture != null) fixture.close();
    }

    // ------------------------------------------------------------------ the scenarios

    @Test
    void aValidSignInForTheUsersGroupCreatesASessionAndAnActorWithTheViewerRoleOnly() throws Exception {
        TestAdfsSamlIdp.Options o = IDP.options().inGroups(USERS_GROUP);
        o.nameId = "carol@corp.test";
        Resp done = signIn(o);
        assertEquals(303, done.status(), done.body());
        assertEquals("/", done.header("location"));
        String cookie = done.cookie();
        assertNotNull(cookie, "the session cookie is set");
        JsonNode status = authed("GET", "/session/status", cookie).json();
        assertTrue(status.path("authenticated").asBoolean());
        assertEquals(List.of("role:viewer"), strings(status.path("role_tokens")));
        assertEquals("Alice Example", status.path("display_name").asText());
        // D2: the actor is the mechanism-prefixed fingerprint, not the bare NameID's and not an LDAP-shaped one.
        String fingerprint = PrincipalFingerprint.of("saml:adfs:carol@corp.test");
        try (Connection c = fixture.migrateConnection(); Statement st = c.createStatement()) {
            assertEquals(1, count(st, "select count(*) from sessions where actor_fingerprint = '" + fingerprint + "' and state = 'ACTIVE'"));
            assertEquals(1, count(st, "select count(*) from actor_authz_state where actor_fingerprint = '" + fingerprint
                    + "' and directory_profile_id = 'saml:adfs'"));
            assertEquals(0, count(st, "select count(*) from sessions where actor_fingerprint = '" + PrincipalFingerprint.of("carol@corp.test") + "'"));
        }
        // A viewer cannot read the SAML settings.
        assertEquals(403, authed("GET", "/api/v2/config/saml", cookie).status());
    }

    @Test
    void theAdministratorGroupGetsTheAdministratorRolesAndNoRawAssertionIsStoredAnywhere() throws Exception {
        TestAdfsSamlIdp.Options o = IDP.options().inGroups(ADMIN_GROUP, "some-other-group");
        o.nameId = "Dave@Corp.Test";
        o.assertionId = "_assertion-id-that-must-not-be-stored";
        Resp done = signIn(o);
        assertEquals(303, done.status(), done.body());
        String cookie = done.cookie();
        List<String> roles = strings(authed("GET", "/session/status", cookie).json().path("role_tokens"));
        assertTrue(roles.contains("role:security_admin"), roles.toString());
        assertTrue(roles.contains("role:viewer"), roles.toString());
        assertEquals(200, authed("GET", "/api/v2/config/saml", cookie).status(), "the SAML administrator reads the SAML settings");

        // The same person with the NameID in another case is the same actor (normalized), so a second sign-in conflicts.
        TestAdfsSamlIdp.Options again = IDP.options().inGroups(ADMIN_GROUP);
        again.nameId = "dave@corp.test";
        Resp second = signIn(again);
        assertEquals(303, second.status());
        assertTrue(second.header("location").startsWith("/?saml_conflict_token="), second.header("location"));

        // Nothing of the assertion is stored: no row of any table holds the assertion id, the NameID or SAML XML.
        try (Connection c = fixture.migrateConnection(); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("select table_name from information_schema.tables where table_schema = 'public' "
                    + "and table_type = 'BASE TABLE'")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            for (String table : tables) {
                try (ResultSet rs = st.executeQuery("select t::text from \"" + table + "\" t")) {
                    while (rs.next()) {
                        String row = rs.getString(1).toLowerCase(Locale.ROOT);
                        assertFalse(row.contains("assertion-id-that-must-not-be-stored"), table);
                        assertFalse(row.contains("dave@corp.test"), table);
                        assertFalse(row.contains("samlp:response") || row.contains("<saml:assertion") || row.contains("signaturevalue"), table);
                    }
                }
            }
        }
    }

    @Test
    void aUserInNoMappedGroupIsRefusedWithAClearMessageAndGetsNoSession() throws Exception {
        TestAdfsSamlIdp.Options o = IDP.options().inGroups("some-other-group");
        o.nameId = "erin@corp.test";
        Resp refused = signIn(o);
        assertEquals(401, refused.status());
        assertNull(refused.cookie());
        assertTrue(refused.body().contains("SAML_NO_AUTHORIZED_GROUP"), refused.body());
        assertTrue(refused.body().contains("none of the groups"), refused.body());
        try (Connection c = fixture.migrateConnection(); Statement st = c.createStatement()) {
            assertEquals(0, count(st, "select count(*) from sessions where actor_fingerprint = '"
                    + PrincipalFingerprint.of("saml:adfs:erin@corp.test") + "'"));
        }
        // No groups claim at all is the same refusal.
        TestAdfsSamlIdp.Options none = IDP.options();
        none.nameId = "erin@corp.test";
        assertTrue(signIn(none).body().contains("SAML_NO_AUTHORIZED_GROUP"));
        // A different claim name than the configured one carries no groups either.
        TestAdfsSamlIdp.Options other = IDP.options().inGroups(USERS_GROUP);
        other.groupClaim = "http://schemas.microsoft.com/ws/2008/06/identity/claims/role";
        assertTrue(signIn(other).body().contains("SAML_NO_AUTHORIZED_GROUP"));
    }

    @Test
    void aTamperedOrRogueOrMissingSignatureIsRefused() throws Exception {
        TestAdfsSamlIdp.Options tampered = IDP.options().inGroups(USERS_GROUP);
        tampered.tamperNameIdAfterSigning = "mallory@corp.test";
        Resp a = signIn(tampered);
        assertEquals(401, a.status());
        assertTrue(a.body().contains("SAML_SIGNATURE_INVALID"), a.body());
        assertNull(a.cookie());

        TestAdfsSamlIdp.Options rogue = IDP.options().inGroups(USERS_GROUP);
        rogue.signWith = TestAdfsSamlIdp.generateKeys();
        rogue.signWithCertificate = TestAdfsSamlIdp.selfSigned(rogue.signWith, "CN=Rogue");
        assertTrue(signIn(rogue).body().contains("SAML_SIGNATURE_INVALID"));

        TestAdfsSamlIdp.Options unsigned = IDP.options().inGroups(USERS_GROUP);
        unsigned.signAssertion = false;
        Resp c = signIn(unsigned);
        assertEquals(401, c.status());
        assertNull(c.cookie());
    }

    @Test
    void anExpiredAssertionAndAWrongAudienceAndAWrongRecipientAreRefused() throws Exception {
        TestAdfsSamlIdp.Options expired = IDP.options().inGroups(USERS_GROUP);
        expired.notBefore = Instant.now().minusSeconds(900);
        expired.notOnOrAfter = Instant.now().minusSeconds(600);
        expired.subjectNotOnOrAfter = Instant.now().minusSeconds(600);
        Resp a = signIn(expired);
        assertEquals(401, a.status());
        assertTrue(a.body().contains("SAML_ASSERTION_EXPIRED"), a.body());

        TestAdfsSamlIdp.Options audience = IDP.options().inGroups(USERS_GROUP);
        String requestId = startAndReadRequestId();
        audience.answering(requestId, acs, "https://some-other-service.test/sp");
        Resp b = acs(IDP.response(audience));
        assertEquals(401, b.status());
        assertTrue(b.body().contains("SAML_WRONG_AUDIENCE"), b.body());

        TestAdfsSamlIdp.Options recipient = IDP.options().inGroups(USERS_GROUP);
        recipient.answering(startAndReadRequestId(), "https://elsewhere.test/login/saml2/sso/adfs", spEntityId);
        Resp c = acs(IDP.response(recipient));
        assertEquals(401, c.status());
        assertNull(c.cookie());

        TestAdfsSamlIdp.Options noExpiry = IDP.options().inGroups(USERS_GROUP);
        noExpiry.omitConditionsExpiry = true;
        assertEquals(401, signIn(noExpiry).status());
    }

    @Test
    void aResponseCannotBeReplayedAndOnlyAnswersARequestMadeHere() throws Exception {
        TestAdfsSamlIdp.Options o = IDP.options().inGroups(USERS_GROUP);
        o.nameId = "frank@corp.test";
        String requestId = startAndReadRequestId();
        o.answering(requestId, acs, spEntityId);
        String response = IDP.response(o);
        Resp first = acs(response);
        assertEquals(303, first.status(), first.body());
        // Same response again: the request it answers was consumed.
        Resp replay = acs(response);
        assertEquals(401, replay.status());
        assertTrue(replay.body().contains("SAML_UNSOLICITED_OR_REPLAYED"), replay.body());

        // A new request answered with the same assertion id (a captured assertion re-wrapped): refused.
        TestAdfsSamlIdp.Options reuse = IDP.options().inGroups(USERS_GROUP);
        reuse.nameId = "frank@corp.test";
        reuse.assertionId = o.assertionId;
        Resp rewrapped = signIn(reuse);
        assertEquals(401, rewrapped.status());
        assertTrue(rewrapped.body().contains("SAML_UNSOLICITED_OR_REPLAYED"), rewrapped.body());

        // An unsolicited response (no InResponseTo, or a request id never issued) is refused.
        TestAdfsSamlIdp.Options unsolicited = IDP.options().inGroups(USERS_GROUP);
        unsolicited.answering(null, acs, spEntityId);
        assertTrue(acs(IDP.response(unsolicited)).body().contains("SAML_UNSOLICITED_OR_REPLAYED"));
        TestAdfsSamlIdp.Options invented = IDP.options().inGroups(USERS_GROUP);
        invented.answering("_never-issued", acs, spEntityId);
        assertTrue(acs(IDP.response(invented)).body().contains("SAML_UNSOLICITED_OR_REPLAYED"));
    }

    @Test
    void theClockSkewBoundDecidesWhatAlmostExpiredAndAlmostNotYetValidAssertionsAreAccepted() throws Exception {
        assertEquals(200, saveSettings(settings(60)).status());
        TestAdfsSamlIdp.Options late = IDP.options().inGroups(USERS_GROUP);
        late.nameId = "gina@corp.test";
        late.notOnOrAfter = Instant.now().minusSeconds(30);
        late.subjectNotOnOrAfter = Instant.now().minusSeconds(30);
        assertEquals(303, signIn(late).status(), "30 s past expiry is inside the 60 s bound");
        TestAdfsSamlIdp.Options tooLate = IDP.options().inGroups(USERS_GROUP);
        tooLate.nameId = "gina@corp.test";
        tooLate.notOnOrAfter = Instant.now().minusSeconds(120);
        tooLate.subjectNotOnOrAfter = Instant.now().minusSeconds(120);
        Resp tl = signIn(tooLate);
        assertEquals(401, tl.status());
        assertTrue(tl.body().contains("SAML_ASSERTION_EXPIRED"), tl.body());
        TestAdfsSamlIdp.Options early = IDP.options().inGroups(USERS_GROUP);
        early.nameId = "hank@corp.test";
        early.notBefore = Instant.now().plusSeconds(120);
        Resp e = signIn(early);
        assertEquals(401, e.status());
        assertTrue(e.body().contains("SAML_ASSERTION_NOT_YET_VALID"), e.body());

        // A tighter bound refuses the 30 s case.
        assertEquals(200, saveSettings(settings(0)).status());
        TestAdfsSamlIdp.Options late2 = IDP.options().inGroups(USERS_GROUP);
        late2.nameId = "ivan@corp.test";
        late2.notOnOrAfter = Instant.now().minusSeconds(30);
        late2.subjectNotOnOrAfter = Instant.now().minusSeconds(30);
        assertEquals(401, signIn(late2).status());
        assertEquals(200, saveSettings(settings(60)).status());
        // The bound itself is bounded.
        Map<String, Object> tooBig = settings(60);
        tooBig.put("clockSkewSeconds", 3600);
        Resp invalid = saveSettings(tooBig);
        assertEquals(400, invalid.status());
        assertTrue(invalid.body().contains("clockSkewSeconds"), invalid.body());
    }

    @Test
    void aSecondSignInOfTheSameActorOffersTakeoverAndResolvesThroughLoginResolve() throws Exception {
        TestAdfsSamlIdp.Options o = IDP.options().inGroups(USERS_GROUP);
        o.nameId = "judy@corp.test";
        Resp first = signIn(o);
        String firstCookie = first.cookie();
        assertNotNull(firstCookie);

        TestAdfsSamlIdp.Options second = IDP.options().inGroups(USERS_GROUP);
        second.nameId = "judy@corp.test";
        Resp conflict = signIn(second);
        assertEquals(303, conflict.status());
        assertNull(conflict.cookie());
        String token = conflict.header("location").substring("/?saml_conflict_token=".length());
        assertEquals("no-referrer", conflict.header("referrer-policy"));
        assertEquals(200, authed("GET", "/session/status", firstCookie).status(), "the first session stays until the choice is made");

        // Refusing leaves the first session alone; the token is spent.
        Resp refuse = send("POST", "/login/resolve", Map.of("Content-Type", "application/json"),
                JSON.writeValueAsString(Map.of("conflictToken", token, "action", "refuse")));
        assertEquals(409, refuse.status());

        TestAdfsSamlIdp.Options third = IDP.options().inGroups(USERS_GROUP);
        third.nameId = "judy@corp.test";
        String token2 = signIn(third).header("location").substring("/?saml_conflict_token=".length());
        Resp takeover = send("POST", "/login/resolve", Map.of("Content-Type", "application/json"),
                JSON.writeValueAsString(Map.of("conflictToken", token2, "action", "takeover")));
        assertEquals(200, takeover.status(), takeover.body());
        String newCookie = takeover.cookie();
        assertNotNull(newCookie);
        assertEquals(401, authed("GET", "/session/status", firstCookie).status(), "the takeover ended the first session");
        JsonNode status = authed("GET", "/session/status", newCookie).json();
        assertEquals(List.of("role:viewer"), strings(status.path("role_tokens")));
    }

    @Test
    void anEncryptedAssertionIsReadWithTheStoredServiceProviderKeyAndRequestsCanBeSigned() throws Exception {
        TestAdfsSamlIdp.Options o = IDP.options().inGroups(USERS_GROUP);
        o.nameId = "kim@corp.test";
        o.encryptTo = TestAdfsSamlIdp.selfSigned(spKeys, "CN=Test SP");
        // Spring Security decrypts only a response that is itself signed (ADFS: sign message and assertion).
        o.signResponse = true;
        o.answering(startAndReadRequestId(), acs, spEntityId);
        String encryptedResponse = IDP.response(o);
        Resp done = acs(encryptedResponse);
        assertEquals(303, done.status(), done.body());
        assertNotNull(done.cookie());
        // An encrypted assertion inside a response that is not signed is refused before anything is decrypted.
        TestAdfsSamlIdp.Options unsignedResponse = IDP.options().inGroups(USERS_GROUP);
        unsignedResponse.nameId = "kim@corp.test";
        unsignedResponse.encryptTo = TestAdfsSamlIdp.selfSigned(spKeys, "CN=Test SP");
        assertEquals(401, signIn(unsignedResponse).status());
        // The metadata published for the ADFS relying party trust carries the SP certificate and the ACS address.
        Resp metadata = send("GET", "/saml2/service-provider-metadata/adfs", Map.of(), null);
        assertEquals(200, metadata.status());
        assertTrue(metadata.body().contains("entityID=\"" + spEntityId + "\""), metadata.body());
        assertTrue(metadata.body().contains(acs), metadata.body());
        assertTrue(metadata.body().contains("X509Certificate"), metadata.body());
        // With signing on, the redirect carries a signature over the request.
        Map<String, Object> signing = settings(60);
        signing.put("signAuthnRequests", true);
        assertEquals(200, saveSettings(signing).status());
        String location = send("GET", "/saml2/authenticate/adfs", Map.of(), null).header("location");
        assertTrue(location.contains("Signature=") && location.contains("SigAlg="), location);
        assertEquals(200, saveSettings(settings(60)).status());
        // The key itself never leaves the server.
        String view = authedAdmin("GET", "/api/v2/config/saml", null).body();
        assertFalse(view.contains("PRIVATE"), view);
        assertEquals("SETTINGS", JSON.readTree(view).path("spKey").path("source").asText());
    }

    @Test
    void theHandshakeRoutesAreOffUntilSamlIsSwitchedOnAndTurningItOffTakesTheRolesAway() throws Exception {
        TestAdfsSamlIdp.Options o = IDP.options().inGroups(USERS_GROUP);
        o.nameId = "lena@corp.test";
        Resp done = signIn(o);
        String cookie = done.cookie();
        assertNotNull(cookie);
        assertTrue(send("GET", "/session/status", Map.of(), null).json().path("saml_enabled").asBoolean(), "signed-out status advertises SSO");

        Resp off = saveSettingsRaw("{\"enabled\":false}");
        assertEquals(200, off.status(), off.body());
        assertFalse(off.json().path("enabled").asBoolean());
        assertEquals("adfs-kept", off.json().path("idpEntityId").asText().isEmpty() ? "adfs-lost" : "adfs-kept", "the settings are kept");
        assertEquals(404, send("GET", "/saml2/authenticate/adfs", Map.of(), null).status());
        assertEquals(404, send("GET", "/saml2/service-provider-metadata/adfs", Map.of(), null).status());
        assertEquals(404, acs("anything").status());
        assertNull(send("GET", "/session/status", Map.of(), null).json().get("saml_enabled"), "off: the status answer is as before");
        // The open SAML session lost its SAML role bindings at once.
        assertTrue(strings(authed("GET", "/session/status", cookie).json().path("role_tokens")).isEmpty());
        assertEquals(403, authed("GET", "/api/v2/devices", cookie).status());
        assertEquals(200, saveSettings(settings(60)).status());
        assertEquals(303, signIn(IDP.options().inGroups(USERS_GROUP)).status(), "switching it on again works without a restart");
    }

    // ------------------------------------------------------------------ the flow

    /** Start a sign-in, let the test ADFS answer the request it issued, and post the answer to the ACS. */
    private static Resp signIn(TestAdfsSamlIdp.Options options) throws Exception {
        if (options.inResponseTo == null && options.acs == null) options.answering(startAndReadRequestId(), acs, spEntityId);
        return acs(IDP.response(options));
    }

    private static String startAndReadRequestId() throws Exception {
        Resp start = send("GET", "/saml2/authenticate/adfs", Map.of(), null);
        assertEquals(302, start.status(), start.body());
        String location = start.header("location");
        assertTrue(location.startsWith(IDP.ssoUrl + "?SAMLRequest="), location);
        String xml = TestAdfsSamlIdp.authnRequestXml(location);
        assertTrue(xml.contains("AssertionConsumerServiceURL=\"" + acs + "\""), xml);
        assertTrue(xml.contains(spEntityId), xml);
        return TestAdfsSamlIdp.authnRequestId(location);
    }

    private static Resp acs(String samlResponse) throws Exception {
        return send("POST", "/login/saml2/sso/adfs", Map.of("Content-Type", "application/x-www-form-urlencoded"),
                "SAMLResponse=" + URLEncoder.encode(samlResponse, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> settings(int skew) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        body.put("spBaseUrl", base);
        body.put("idpMetadataXml", IDP.metadataXml());
        body.put("adminGroupValue", ADMIN_GROUP);
        body.put("usersGroupValue", USERS_GROUP);
        body.put("clockSkewSeconds", skew);
        body.put("spCertPem", TestAdfsSamlIdp.pem(TestAdfsSamlIdp.selfSigned(spKeys, "CN=Test SP")));
        body.put("spPrivateKeyPem", TestAdfsSamlIdp.privateKeyPem(spKeys));
        return body;
    }

    private static Resp saveSettings(Map<String, Object> body) throws Exception {
        return saveSettingsRaw(JSON.writeValueAsString(body));
    }

    private static Resp saveSettingsRaw(String json) throws Exception {
        return authedAdmin("PUT", "/api/v2/config/saml", json);
    }

    private static Resp authedAdmin(String method, String path, String body) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", "ui2_session=" + adminCookie);
        if (!"GET".equals(method)) {
            headers.put("X-CSRF-Token", adminCsrf);
            headers.put("Origin", base);
            headers.put("Content-Type", "application/json");
        }
        return send(method, path, headers, body);
    }

    private static Resp authed(String method, String path, String cookie) throws Exception {
        return send(method, path, Map.of("Cookie", "ui2_session=" + cookie), null);
    }

    private static Resp send(String method, String path, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, List<String>> lower = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        return new Resp(response.statusCode(), lower, response.body());
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Path keyFile(String name) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Path file = work.resolve(name);
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        return file;
    }

    /** A stand-in static bundle, so the service starts without a built console (as the characterization suite does). */
    /**
     * The internal machine port is a fixed 8086 and HttpSurfaceCharacterizationTest owns it; this test reads only the main
     * port. Without this, two forks booting the application at once make the later one fail to start (found on the HOST-A
     * runner 2026-11-25, where the forks overlap differently). Same remedy as AiviewRawVersusMaskedSweepTest.
     */
    private static void dropMachineConnector(
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        ((org.springframework.beans.factory.support.BeanDefinitionRegistry) beanFactory)
                .removeBeanDefinition("machineConnector");
    }

    private static ClassLoader consoleBundleLoader() throws Exception {
        ClassLoader app = AdfsSamlAuthenticationTest.class.getClassLoader();
        if (app.getResource("static/index.html") != null) return app;
        Path root = Files.createDirectories(work.resolve("console-bundle/static/assets"));
        Files.writeString(root.getParent().resolve("index.html"),
                "<!doctype html><html><head><title>fixture</title></head><body><div id=\"root\"></div></body></html>\n");
        Files.writeString(root.resolve("index-fixture0.js"), "console.log('fixture bundle');\n");
        return new java.net.URLClassLoader(new java.net.URL[] {work.resolve("console-bundle").toUri().toURL()}, app);
    }
}
