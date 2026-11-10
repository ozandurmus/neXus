package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdvisoryRuleCodecTest {

    private final AdvisoryCore core = new DefaultAdvisoryCore(List.of());

    @Test
    void roundTripsEveryRuleForm() {
        List<AdvisoryModel.VersionRule> rules = List.of(
                new AdvisoryModel.WholeTrain(AdvisoryModel.Status.UNAFFECTED),
                new AdvisoryModel.Range(AdvisoryModel.Status.AFFECTED, null, "11.1.4-h13", false,
                        List.of(new AdvisoryModel.ChangePoint("11.1.4-h13", AdvisoryModel.Status.UNAFFECTED))),
                new AdvisoryModel.Range(AdvisoryModel.Status.UNAFFECTED, "7.0.1", "7.0.5", true, List.of()),
                new AdvisoryModel.ExactVersions(AdvisoryModel.Status.AFFECTED, Set.of("9.18.4.50", "9.18.4")));
        String json = core.rulesToJson(rules);
        assertEquals(rules, core.rulesFromJson(json));
        assertTrue(json.contains("\"v\":1"));
    }

    @Test
    void emptyListRoundTrips() {
        assertEquals(List.of(), core.rulesFromJson(core.rulesToJson(List.of())));
    }

    @Test
    void anythingForeignReadsAsEmptyAndNeverThrows() {
        String[] bad = {null, "", "  ", "not json", "[]", "{}", "{\"v\":2,\"rules\":[]}", "{\"v\":1}", "{\"v\":1,\"rules\":{}}",
            "{\"v\":1,\"rules\":[{\"k\":\"nope\",\"status\":\"AFFECTED\"}]}",
            "{\"v\":1,\"rules\":[{\"k\":\"whole\",\"status\":\"MAYBE\"}]}",
            "{\"v\":1,\"rules\":[{\"k\":\"whole\"}]}",
            "{\"v\":1,\"rules\":[{\"k\":\"range\",\"status\":\"AFFECTED\",\"from\":5,\"to\":null,\"toInclusive\":false,\"changes\":[]}]}",
            "{\"v\":1,\"rules\":[{\"k\":\"range\",\"status\":\"AFFECTED\",\"from\":null,\"to\":null,\"changes\":[]}]}",
            "{\"@class\":\"java.lang.Runtime\",\"v\":1,\"rules\":[]}x",
            "{\"v\":1,\"rules\":[" + "[".repeat(5000)};
        for (String s : bad) {
            assertEquals(List.of(), core.rulesFromJson(s), String.valueOf(s));
        }
    }
}
