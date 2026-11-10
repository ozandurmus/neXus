package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

class LifecycleParsersRobustnessTest {

    private static List<Function<String, LicenseParse>> licenseParsers() {
        return List.of(
                raw -> CheckPointLicenseParser.parse("cp_cplic_print", raw),
                AsaSmartLicensingParser::parse,
                raw -> ManagementCenterLicenseParser.parse("member", raw),
                InfobloxGridwideLicenseParser::parse,
                PaloAltoLicenseParser::parseLicenseInfo,
                PaloAltoLicenseParser::parseSupportInfo,
                FortiGateContractParser::parse,
                FortiGateAutoupdateParser::parse);
    }

    private static List<String> inputs() {
        Random random = new Random(20261110L);
        List<String> out = new ArrayList<>();
        out.add(null);
        out.add("");
        out.add("\u0000\u0001\u0002￿\uD800");
        out.add("x".repeat(3_000_000));
        out.add(("192.0.2.1 never " + "A".repeat(10_000) + " CK-1\n").repeat(50));
        out.add("[".repeat(20_000));
        out.add("<a>".repeat(50_000));
        out.add("{\"components\":" + "[".repeat(5_000));
        out.add("Host Expiration Features\n" + "|".repeat(100_000) + "\nContract Coverage:\n" + "1 |".repeat(50_000));
        out.add("ID(PATCH:PROC) STATUS\n" + "a b c 1/1 2026-01-01 00:00:00 sk1\n".repeat(30_000));
        out.add("System contracts:\n" + ",".repeat(5000) + "\n" + "A,".repeat(5000));
        out.add("Registration:\nStatus: " + "(".repeat(5000) + "\n");
        for (int i = 0; i < 60; i++) {
            StringBuilder b = new StringBuilder();
            int n = random.nextInt(400);
            for (int j = 0; j < n; j++) {
                int kind = random.nextInt(10);
                if (kind == 0) {
                    b.append('\n');
                } else if (kind == 1) {
                    b.append("|");
                } else if (kind == 2) {
                    b.append((char) random.nextInt(0xD7FF));
                } else if (kind == 3) {
                    b.append(" CK-").append(random.nextInt());
                } else if (kind == 4) {
                    b.append("<").append((char) ('a' + random.nextInt(26))).append(">");
                } else {
                    b.append((char) (' ' + random.nextInt(95)));
                }
            }
            out.add(b.toString());
        }
        return out;
    }

    @Test
    void noParserThrowsOnAnyInputAndTheItemsAlwaysFitTheSchemaPatterns() {
        Pattern label = Pattern.compile("[A-Za-z0-9 _./+()&,:-]{1,120}");
        Pattern detail = Pattern.compile("[A-Za-z0-9 _./+()&,:=-]{1,160}");
        for (String input : inputs()) {
            for (Function<String, LicenseParse> parser : licenseParsers()) {
                LicenseParse result = parser.apply(input);
                assertNotNull(result);
                assertNotNull(result.outcome());
                assertEquals(result.outcome() == ParseOutcome.COLLECTED, !result.items().isEmpty(), "collected means at least one item");
                for (LicenseItem item : result.items()) {
                    assertTrue(label.matcher(item.label()).matches(), "label " + item.label());
                    assertTrue(item.detail() == null || detail.matcher(item.detail()).matches(), "detail " + item.detail());
                }
            }
            LivePatchParse patches = CheckPointLivePatchParser.parse(input);
            assertNotNull(patches.outcome());
            Pattern id = Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
            patches.items().forEach(p -> assertTrue(id.matcher(p.patchId()).matches(), "patch id " + p.patchId()));
        }
    }

    /** The label and detail checks are read from V200 itself, so the parsers and the schema cannot drift apart unnoticed. */
    @Test
    void parserPatternsAreTheOnesOfTheV200SchemaChecks() throws IOException {
        Path migration = Path.of("..", "service", "src", "main", "resources", "db", "migration",
                "V200__device_lifecycle_license_and_live_patch.sql");
        assumeTrue(Files.exists(migration), "V200 not reachable from this working directory");
        String sql = Files.readString(migration);
        Matcher label = Pattern.compile("label\\s+TEXT\\s+NOT NULL CHECK \\(label ~ '\\^(.+?)\\$'\\)").matcher(sql);
        Matcher detail = Pattern.compile("detail IS NULL OR detail ~ '\\^(.+?)\\$'").matcher(sql);
        Matcher patch = Pattern.compile("patch_id ~ '\\^(.+?)\\$'").matcher(sql);
        assertTrue(label.find() && detail.find() && patch.find());
        Pattern schemaLabel = Pattern.compile(label.group(1));
        Pattern schemaDetail = Pattern.compile(detail.group(1));
        Pattern schemaPatch = Pattern.compile(patch.group(1));

        for (char c = 0; c < 400; c++) {
            if (Character.isWhitespace(c)) {
                continue; // white space is collapsed to one space before the check, by design
            }
            String one = "a" + c + "b";
            String parsedLabel = LifecycleText.label(one);
            assertEquals(schemaLabel.matcher(one).matches(), parsedLabel != null, "label char U+" + Integer.toHexString(c));
            String parsedDetail = LifecycleText.detail(one);
            assertEquals(schemaDetail.matcher(one).matches(), parsedDetail != null, "detail char U+" + Integer.toHexString(c));
        }
        assertEquals(null, LifecycleText.label("   "));
        assertEquals(120, LifecycleText.label("A".repeat(300)).length());
        assertEquals(160, LifecycleText.detail("A".repeat(300)).length());
        assertTrue(schemaPatch.matcher(CheckPointLivePatchParser.parse(CheckPointParsersTest.CPLP).items().get(0).patchId()).matches());
        for (LicenseItem item : CheckPointLicenseParser.parse("cp_cplic_print", CheckPointParsersTest.CPLIC_VSX).items()) {
            assertTrue(schemaLabel.matcher(item.label()).matches());
        }
    }
}
