package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Synthetic text only. */
class PanLogRedactorTest {
    private static Map<String, String> fields(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void aSecretBearingPathHidesTheWholeChangeBodyAndCountsEachField() {
        for (String path : new String[] {"/config/mgt-config/users/entry[@name='a']/phash", "/config/shared/certificate/entry[@name='c']/private-key",
                "/config/devices/entry/deviceconfig/system/snmp-setting/snmp-community-string", "/config/x/radius/server/entry/secret",
                "/config/x/y/key"}) {
            var result = PanLogRedactor.redact("pan_config", fields("path", path, "before-change-preview", "<a>b</a>",
                    "after-change-preview", "<a>c</a>", "before-change-detail", "d", "cmd", "set"));
            assertEquals("<redacted>", result.fields().get("before-change-preview"), path);
            assertEquals("<redacted>", result.fields().get("after-change-preview"), path);
            assertEquals("<redacted>", result.fields().get("before-change-detail"), path);
            assertEquals("set", result.fields().get("cmd"));
            assertEquals(3, result.redactions(), path);
        }
    }

    @Test
    void anOrdinaryPathKeepsItsChangeBodyButSecretElementsAndValuesInsideAreMasked() {
        var result = PanLogRedactor.redact("pan_config", fields("full-path", "vsys vsys1 rulebase security rules r1",
                "before-change-preview", "<entry name=\"r1\"><to><member>z</member></to></entry>",
                "after-change-preview", "<entry name=\"r1\"><password>P1</password><auth-key>K1</auth-key><key>K2</key>"
                        + "<pre-shared-key>K3</pre-shared-key><description>fine</description></entry>"));
        assertEquals("<entry name=\"r1\"><to><member>z</member></to></entry>", result.fields().get("before-change-preview"));
        String after = result.fields().get("after-change-preview");
        for (String secret : new String[] {"P1", "K1", "K2", "K3"}) assertFalse(after.contains(secret), secret);
        assertTrue(after.contains("<description>fine</description>"));
        assertEquals(4, result.redactions());
    }

    @Test
    void setStyleTextMasksTheValueAfterASecretKeyword() {
        var result = PanLogRedactor.redact("pan_config", fields("full-path",
                "set mgt-config users admin phash $1$SYNTH$abc; set shared snmp-community-string SYNTHSTR"));
        String text = result.fields().get("full-path");
        assertFalse(text.contains("SYNTH"));
        assertTrue(text.contains("phash <redacted>") || text.contains("phash <redacted>;"));
        assertEquals(2, result.redactions());
    }

    @Test
    void theGenericAssignmentRuleAppliesToEverySourceButNeedsAnExplicitSeparator() {
        var system = PanLogRedactor.redact("pan_system", fields("opaque", "Login failed for user u1, password=SYNTHPW key: SYNTHK token=\"SYNTH T\""));
        assertEquals("Login failed for user u1, password=<redacted> key: <redacted> token=<redacted>", system.fields().get("opaque"));
        assertEquals(3, system.redactions());
        var prose = PanLogRedactor.redact("pan_system", fields("opaque", "password policy applied; secret rotation due; key exchange done"));
        assertEquals("password policy applied; secret rotation due; key exchange done", prose.fields().get("opaque"));
        assertEquals(0, prose.redactions());
    }

    @Test
    void theInputIsNeverModifiedAndAnEmptyEntryCostsNothing() {
        var in = fields("path", "/a/phash", "before-change-preview", "<phash>x</phash>");
        var copy = new LinkedHashMap<>(in);
        PanLogRedactor.redact("pan_config", in);
        assertEquals(copy, in);
        assertEquals(0, PanLogRedactor.redact("pan_config", fields("cmd", "")).redactions());
    }

    @Test
    void configFieldRulesDoNotTouchASystemLogEntryThatHappensToUseThoseNames() {
        var result = PanLogRedactor.redact("pan_system", fields("path", "/config/mgt-config/users/phash", "before-change-preview", "<phash>x</phash>"));
        assertEquals("<phash>x</phash>", result.fields().get("before-change-preview"));
        assertEquals(0, result.redactions());
    }

    // ---------------------------------------------------------------- hardening (rule set pan-config-v2)

    private static final String R = "<redacted>";

    private static String config(String field, String value) {
        return PanLogRedactor.redact("pan_config", fields(field, value)).fields().get(field);
    }

    private static String preview(String value) {
        return config("after-change-preview", value);
    }

    private static String system(String value) {
        return PanLogRedactor.redact("pan_system", fields("opaque", value)).fields().get("opaque");
    }

    private static int countOf(String source, String field, String value) {
        return PanLogRedactor.redact(source, fields(field, value)).redactions();
    }

    private static void unchanged(String source, String field, String value) {
        var result = PanLogRedactor.redact(source, fields(field, value));
        assertEquals(value, result.fields().get(field), value);
        assertEquals(0, result.redactions(), value);
    }

    @Test
    void redactingTwiceEqualsRedactingOnceCountsIncluded() {
        List<String> corpus = List.of(
                "<entry name=\"a\"><password>P1</password><private-key><data>D</data></private-key></entry>",
                "&lt;entry&gt;&lt;password&gt;P1&lt;/password&gt;&lt;pwd&gt;x&lt;/pwd&gt;&lt;/entry&gt;",
                "set mgt-config users u phash $1$SYNTH$abcdef; set x password ab,cd",
                "Authorization: Basic QUJDREVGR0hJSktMTU5PUA== and key 0123456789abcdef0123 and -AQ==SYNTHETICBASE64+/ab=",
                "<d>password=ab,cd efg</d><e secret='x y' token=ab>t</e>",
                "community[@name='x']/entry[@name='y'] password='z'",
                "<password>P1", "<password></password>", "password=", "key: ", "Bearer abcdefghijklmnopqrstuvwx");
        for (String source : new String[] {"pan_config", "pan_system"}) {
            for (String text : corpus) {
                for (String field : new String[] {"after-change-preview", "path", "full-path", "before-change-detail", "opaque"}) {
                    var once = PanLogRedactor.redact(source, fields(field, text, "before-change-preview", "<a>b</a>", "path",
                            "/config/x/community[@name='p']"));
                    var twice = PanLogRedactor.redact(source, once.fields());
                    assertEquals(once.fields(), twice.fields(), source + " " + field + " " + text);
                    assertEquals(0, twice.redactions(), "second pass must count nothing: " + text);
                    assertEquals(Map.of(), twice.countsByRule());
                }
            }
        }
    }

    @Test
    void anAlreadyRedactedBodyUnderASecretPathIsNotCountedAgain() {
        var in = fields("path", "/config/mgt-config/users/entry[@name='a']/phash", "before-change-preview", "<redacted>",
                "after-change-preview", "&lt;redacted&gt;");
        var result = PanLogRedactor.redact("pan_config", in);
        assertEquals("<redacted>", result.fields().get("before-change-preview"));
        assertEquals("&lt;redacted&gt;", result.fields().get("after-change-preview"));
        assertEquals(0, result.redactions());
        var first = PanLogRedactor.redact("pan_config", fields("path", "/config/x/phash", "before-change-preview", "<phash>x</phash>"));
        assertEquals(1, first.redactions());
        assertEquals(0, PanLogRedactor.redact("pan_config", first.fields()).redactions());
    }

    @Test
    void underscoreAndVariantNamesAreSecretNames() {
        for (String name : new String[] {"private_key", "api_key", "auth_key", "shared_key", "preshared-key", "pre_shared_key",
                "pass_word", "Pre-Shared-Key", "PRIVATE_KEY", "privateKey", "sharedKey", "ssh-key", "key", "Password"}) {
            String xml = "<entry><" + name + ">SYNTHSECRET</" + name + "><ok>fine</ok></entry>";
            String out = preview(xml);
            assertFalse(out.contains("SYNTHSECRET"), name + " -> " + out);
            assertTrue(out.contains("<ok>fine</ok>"), name);
            assertFalse(system(name + "=SYNTHSECRET").contains("SYNTHSECRET"), name);
            assertEquals(name + "=" + R, system(name + "=SYNTHSECRET"));
        }
        assertEquals(R, config("before-change-preview", "x").equals("x") ? R : "bad"); // no secret path: body kept
        assertEquals("<redacted>", PanLogRedactor.redact("pan_config", fields("path", "/config/x/pre_shared_key",
                "before-change-preview", "v")).fields().get("before-change-preview"));
        assertEquals("<redacted>", PanLogRedactor.redact("pan_config", fields("path", "/config/x/auth-key",
                "before-change-preview", "v")).fields().get("before-change-preview"));
    }

    @Test
    void secretNamesThatContainNoKeywordAreRedacted() {
        for (String name : new String[] {"pwd", "authpwd", "privpwd", "pkcs12", "keytab", "key-data", "keydata", "key-material",
                "auth-code", "auth_code", "Key_Material"}) {
            String out = preview("<e><" + name + ">SYNTHSECRET</" + name + "></e>");
            assertEquals("<e><" + name + ">" + R + "</" + name + "></e>", out, name);
            assertEquals(name + "=" + R, system(name + "=SYNTHSECRET"), name);
            assertEquals("v1 " + name + ": " + R, system("v1 " + name + ": SYNTHSECRET"), name);
            assertEquals(R, PanLogRedactor.redact("pan_config", fields("path", "/config/x/" + name, "before-change-preview", "v"))
                    .fields().get("before-change-preview"), name);
        }
        assertEquals("snmp authpwd " + R + " privpwd " + R,
                config("full-path", "snmp authpwd SYNTHA privpwd SYNTHB"));
    }

    @Test
    void aSecretElementWithChildElementsLosesAllItsContent() {
        String out = preview("<entry name=\"c\"><private-key><data>SYNTHDATA</data><format>pem</format></private-key>"
                + "<description>keep</description></entry>");
        assertEquals("<entry name=\"c\"><private-key><redacted></private-key><description>keep</description></entry>", out);
        assertEquals(1, countOf("pan_config", "after-change-preview", "<private-key><data>SYNTHDATA</data></private-key>"));
        assertEquals("<snmp-setting><community><redacted></community></snmp-setting>",
                preview("<snmp-setting><community><entry name=\"c1\"><type><v2c/></type></entry><member>SYNTHM</member></community></snmp-setting>"));
        assertEquals("<password><redacted>", preview("<password>SYNTH and the preview was cut off"), "unterminated: to the end");
        assertEquals("<k><password a=\"1\"><redacted></password></k>", preview("<k><password a=\"1\"><x>S</x></password></k>"));
        assertEquals("<a><password></password></a>", preview("<a><password></password></a>"));
        assertEquals("<a><password/></a>", preview("<a><password/></a>"));
    }

    @Test
    void escapedMarkupIsHandledLikeRawMarkup() {
        String out = preview("&lt;entry name=&quot;a&quot;&gt;&lt;password&gt;SYNTHPW&lt;/password&gt;"
                + "&lt;private-key&gt;&lt;data&gt;SYNTHDATA&lt;/data&gt;&lt;/private-key&gt;&lt;description&gt;keep&lt;/description&gt;&lt;/entry&gt;");
        assertFalse(out.contains("SYNTH"), out);
        assertTrue(out.contains("&lt;description&gt;keep&lt;/description&gt;"));
        assertTrue(out.contains("&lt;password&gt;<redacted>&lt;/password&gt;"));
        assertTrue(out.contains("&lt;private-key&gt;<redacted>&lt;/private-key&gt;"));
        assertEquals(2, countOf("pan_config", "after-change-preview",
                "&lt;password&gt;a&lt;/password&gt;&lt;private-key&gt;&lt;d&gt;x&lt;/d&gt;&lt;/private-key&gt;"));
        assertFalse(preview("&lt;entry password=&quot;SYNTHPW&quot;&gt;x&lt;/entry&gt;").contains("SYNTHPW"));
        String mixed = preview("<a>&lt;password&gt;SYNTHPW&lt;/password&gt;</a>");
        assertFalse(mixed.contains("SYNTHPW"), mixed);
    }

    @Test
    void valuesAreNotCutAtPunctuation() {
        assertEquals("password=" + R, system("password=ab,cd"));
        assertEquals("password=" + R, system("password=ab;cd"));
        assertEquals("password=" + R, system("password=a'b\"c<d>e"));
        assertEquals("x password=" + R + " tail", system("x password=ab,cd;ef tail"));
        assertEquals("token=" + R + " after", system("token=\"ab, cd; 'ef'\" after"));
        assertEquals("token=" + R + " after", system("token='ab \"cd\" ef' after"));
        assertEquals("secret=" + R, system("secret=\"unterminated quote leaks nothing"));
        assertEquals("password=" + R + " ok", system("password=&quot;ab cd&quot; ok"));
        assertFalse(system("password=ab,cd").contains("cd"));
        // unquoted inside XML text: to the end of the element text
        assertEquals("<d>note password=" + R + "</d><e>keep</e>", preview("<d>note password=ab,cd efg 'h'</d><e>keep</e>"));
        assertEquals("&lt;d&gt;password=" + R + "&lt;/d&gt;", preview("&lt;d&gt;password=ab cd&lt;/d&gt;"));
        // inside a tag an attribute value is cut at whitespace or the tag end only; quoted ones keep their quotes
        assertEquals("<entry password=\"" + R + "\" name=\"n\">", preview("<entry password=\"ab,cd\" name=\"n\">"));
        assertEquals("<entry token=" + R + " name=\"n\">", preview("<entry token=ab,cd name=\"n\">"));
        // plain config text uses the whitespace boundary
        assertEquals("set a b phash " + R + " next", config("full-path", "set a b phash $x,y;z next"));
        // a secret placeholder earlier in the text does not turn plain text into element text
        assertEquals("password=" + R + " key: " + R + " tail", system("password=<redacted> key: SYNTHK tail"));
    }

    @Test
    void shapeRulesCatchSecretsWithoutAKeyword() {
        assertEquals("v=" + R + " end", system("v=-AQ==SYNTHETICENCRYPTED+/value== end".replace("v=-AQ", "v=-AQ")),
                "PAN encrypted value");
        assertEquals("<k>" + R + "</k>", preview("<k>-AQ==SYNTHETIC/base64+value=</k>"));
        assertEquals("hash " + R, system("hash $1$SYNTH$abcdefghij"));
        assertEquals("hash " + R + " and " + R, system("hash $5$SYNTHSALT$abcdefghij and $6$rounds=5000$salt$abcdef"));
        assertEquals("Authorization: Basic " + R, system("Authorization: Basic QUJDREVGR0hJSktMTU5PUA=="));
        assertEquals("Authorization: Bearer " + R + " next", system("Authorization: Bearer eyJhbGciOi.eyJzdWIiOiJ4.c2lnbmF0dXJl next"));
        assertEquals("sent Bearer " + R, system("sent Bearer abcdefghijklmnopqrstuvwxyz0123"));
        assertEquals("hdr Basic " + R, system("hdr Basic QWxhZGRpbjpvcGVu"));
        assertEquals("secret " + R + " end", system("secret 0123456789abcdef0123456789abcdef end"));
        assertEquals("shared-key is " + R, system("shared-key is 00112233445566778899aabbccddeeff"));
        assertEquals("pwd: " + R, system("pwd: 0x00112233445566778899"));
        // prose and short or non-matching values stay
        for (String text : new String[] {"basic configuration complete", "Basic Authentication required", "Bearer token expired",
                "Authorization failed for user u1", "Authorization: denied", "cost $5 and $6 dollars", "key 12345678",
                "secret rotation due", "serial 0123456789abcdef0123456789abcdef", "file id deadbeefdeadbeefdeadbeef"}) {
            assertEquals(text, system(text), text);
        }
    }

    @Test
    void predicateLiteralsUnderASecretPathStepAreRedactedAndTheStructureStays() {
        assertEquals("/config/devices/entry/snmp-setting/community[@name='" + R + "']/type",
                config("path", "/config/devices/entry/snmp-setting/community[@name='SYNTHCOMM']/type"));
        assertEquals("/config/x/password='" + R + "'", config("path", "/config/x/password='SYNTHPW'"));
        assertEquals("/a/community[@name='" + R + "']/entry[@name=\"" + R + "\"]/b",
                config("full-path", "/a/community[@name='S1']/entry[@name=\"S2\"]/b"));
        assertEquals("/a/community[@name=&apos;" + R + "&apos;]", config("path", "/a/community[@name=&apos;S1&apos;]"));
        // literals before the secret step are ordinary names
        assertEquals("/config/shared/certificate/entry[@name='cert1']/private-key",
                config("path", "/config/shared/certificate/entry[@name='cert1']/private-key"));
        assertEquals("/config/mgt-config/users/entry[@name='admin1']/phash",
                config("path", "/config/mgt-config/users/entry[@name='admin1']/phash"));
        // a rule whose name merely contains the word is not a secret step
        assertEquals("/rules/entry[@name='my-password-rule']", config("path", "/rules/entry[@name='my-password-rule']"));
        assertEquals(1, countOf("pan_config", "path", "/a/community[@name='S1']"));
    }

    @Test
    void fieldNamesAreMatchedIgnoringCaseAndSeparators() {
        for (String body : new String[] {"Before-Change-Preview", "after_change_preview", "AFTERCHANGEPREVIEW", "afterChangePreview",
                "Before-Change-Detail", "after-change-detail", "pre-change-detail", "BEFORE_CHANGE_DETAIL"}) {
            var result = PanLogRedactor.redact("pan_config", fields("Path", "/config/x/phash", body, "<a>b</a>"));
            assertEquals("<redacted>", result.fields().get(body), body);
            assertEquals(1, result.redactions(), body);
        }
        for (String path : new String[] {"PATH", "Full-Path", "full_path", "FullPath"}) {
            var result = PanLogRedactor.redact("pan_config", fields(path, "set a phash SYNTHHASH"));
            assertEquals("set a phash " + R, result.fields().get(path), path);
        }
        assertEquals("<a><password><redacted></password></a>",
                PanLogRedactor.redact("pan_config", fields("After_Change_Preview", "<a><password>x</password></a>"))
                        .fields().get("After_Change_Preview"));
        // not a config field: the config rules do not apply to it
        unchanged("pan_config", "cmd", "set a phash SYNTHHASH");
        unchanged("pan_config", "result", "<password-profile>pp1</password-profile>");
    }

    @Test
    void theResultCarriesTheRuleSetIdAndPerRuleCounts() {
        var result = PanLogRedactor.redact("pan_config", fields("path", "/a/community[@name='S1']/x",
                "before-change-preview", "<a><password>P</password><key>K</key></a>", "after-change-preview", "x",
                "full-path", "set a phash H", "result", "Succeeded password=PW token=T"));
        assertEquals("pan-config-v2", result.ruleSetId());
        assertEquals("pan-config-v2", PanLogRedactor.RULE_SET_ID);
        assertEquals(Map.of("secret_path_body", 2), PanLogRedactor.redact("pan_config",
                fields("path", "/x/phash", "before-change-preview", "a", "after-change-preview", "b")).countsByRule());
        var detailed = PanLogRedactor.redact("pan_config", fields("path", "/q/entry", "after-change-preview",
                "<a><password>P</password><key>K</key></a>", "full-path", "set a b", "result", "ok password=PW token=T"));
        assertEquals(Map.of("secret_element", 2, "secret_assignment", 2), detailed.countsByRule());
        assertEquals(4, detailed.redactions());
        var withSetText = PanLogRedactor.redact("pan_config", fields("full-path", "set a phash H", "result", "password=PW"));
        assertEquals(Map.of("keyword_value", 1, "secret_assignment", 1), withSetText.countsByRule());
        assertEquals(detailed.redactions(), detailed.countsByRule().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals("pan-config-v2", PanLogRedactor.redact("pan_system", fields("a", "b")).ruleSetId());
        assertEquals(Map.of(), PanLogRedactor.redact("pan_system", fields("a", "b")).countsByRule());
        assertTrue(PanLogRedactor.ruleIds().containsAll(List.of("secret_path_body", "secret_element", "path_predicate",
                "keyword_value", "secret_assignment", "auth_blob", "hex_key", "crypt_hash", "pan_encrypted")));
    }

    @Test
    void ordinaryProseAndSettingsAboutSecretsStayReadable() {
        for (String text : new String[] {"password policy applied", "key exchange done", "secret rotation due",
                "Login failed: password complexity not met", "password: required", "key: format invalid",
                "token expired for user u1", "community string changed", "pam_unix(passwd:chauthtok): password changed",
                "key = value is not a secret? no: key==x", "the pre-shared key was rotated", "authentication succeeded"}) {
            String out = system(text);
            if (text.startsWith("key = value")) continue;
            assertEquals(text, out, text);
        }
        unchanged("pan_config", "full-path", "set deviceconfig setting management password-complexity enabled yes");
        unchanged("pan_config", "after-change-preview",
                "<entry><password-profile>pp1</password-profile><key-exchange>dh14</key-exchange><description>password policy</description></entry>");
        unchanged("pan_config", "full-path", "set mgt-config password-profile pp1");
        unchanged("pan_config", "path", "/config/devices/entry/deviceconfig/setting/management/password-complexity");
    }

    @Test
    void escapedAndRawUnterminatedAndNullInputsDoNotBreak() {
        var in = new LinkedHashMap<String, String>();
        in.put("path", null);
        in.put("after-change-preview", "<");
        in.put("before-change-preview", "&lt;");
        in.put("full-path", "&lt;pass&gt;");
        var result = PanLogRedactor.redact("pan_config", in);
        assertNull(result.fields().get("path"));
        assertEquals("<", result.fields().get("after-change-preview"));
        assertEquals("&lt;pass&gt;", result.fields().get("full-path"));
        assertEquals(0, result.redactions());
    }

    @Test
    void aRandomMixOfFragmentsIsIdempotentAndNeverLeaksTheSyntheticSecrets() {
        String[] fragments = {"<password>SYNTHPW", "</password>", "&lt;key&gt;SYNTHK&lt;/key&gt;", "<a b=\"c\">", "</a>", " password=SYNTHP,Q ",
                " token='SYNTH T' ", " phash $1$SYNTH$abcd ", "/community[@name='SYNTHC']", " Authorization: Basic SYNTHBLOB1234== ",
                " -AQ==SYNTHENC+/= ", " key 0123456789abcdef0123 ", "<private-key><d>SYNTHD</d></private-key>", " plain words ",
                " secret: SYNTHS ", "<redacted>", "&lt;redacted&gt;", " pwd=SYNTHW ", " \"password\": \"SYNTHJ\" "};
        java.util.Random random = new java.util.Random(17);
        for (int round = 0; round < 3000; round++) {
            StringBuilder text = new StringBuilder();
            for (int i = 1 + random.nextInt(6); i > 0; i--) text.append(fragments[random.nextInt(fragments.length)]);
            for (String field : new String[] {"after-change-preview", "full-path", "path"}) {
                var once = PanLogRedactor.redact("pan_config", fields(field, text.toString()));
                var twice = PanLogRedactor.redact("pan_config", once.fields());
                assertEquals(once.fields(), twice.fields(), text.toString());
                assertEquals(0, twice.redactions(), text.toString());
                String out = once.fields().get(field);
                assertFalse(out.contains("SYNTHPW") || out.contains("SYNTHK") || out.contains("SYNTHP,Q") || out.contains("SYNTH T")
                        || out.contains("SYNTHD") || out.contains("SYNTHS") || out.contains("SYNTHW") || out.contains("SYNTHJ")
                        || out.contains("SYNTHENC") || out.contains("SYNTHBLOB") || (!field.equals("after-change-preview") && out.contains("SYNTHC'"))
                        || out.contains("$1$SYNTH") || out.contains("0123456789abcdef"), field + ": " + text + " -> " + out);
            }
        }
    }
}
