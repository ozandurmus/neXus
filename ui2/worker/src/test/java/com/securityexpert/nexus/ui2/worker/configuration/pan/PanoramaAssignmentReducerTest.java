package com.securityexpert.nexus.ui2.worker.configuration.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationOverride;

/** AC-2a: the Panorama cross-check fixture names the template/device-group source for each override. */
class PanoramaAssignmentReducerTest {

    // Shaped like PAN_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md's own Panorama vocabulary
    // (template, template-stack, device-group entries each carrying a <devices> assignment list),
    // one level flattened -- see PanoramaAssignmentReducer's own class comment: a template's own
    // config subtree is counted from its entry's immediate children, matching
    // PaloAltoConfigStreamProcessor's own flattening of a vsys entry's categories.
    private static final String FIXTURE = "<config>"
            + "<devices><entry name=\"localhost.localdomain\">"
            + "<template><entry name=\"TPL-BASE\">"
            + "<address><entry name=\"addr-shared-1\"/></address>"
            + "</entry></template>"
            + "<template-stack><entry name=\"STACK-EU\">"
            + "<templates><member>TPL-BASE</member></templates>"
            + "<devices><entry name=\"SERIAL1\"/></devices>"
            + "</entry></template-stack>"
            + "<device-group><entry name=\"DG-EU\">"
            + "<address><entry name=\"addr-dg-1\"/></address>"
            + "<devices><entry name=\"SERIAL1\"/></devices>"
            + "</entry></device-group>"
            + "</entry></devices>"
            + "</config>";

    @Test
    void reducesToAnAssignmentIndexAndPerTemplateDefinedPaths() throws Exception {
        var reduced = PanoramaAssignmentReducer.reduce(stream());

        var assignment = reduced.assignmentsBySerial().get("SERIAL1");
        assertEquals(Optional.of("STACK-EU"), assignment.templateStack());
        assertEquals(List.of("TPL-BASE"), assignment.templates());
        assertEquals(List.of("DG-EU"), assignment.deviceGroups());

        assertTrue(reduced.definedPaths().get("template:TPL-BASE").contains("address/addr-shared-1"));
        assertTrue(reduced.definedPaths().get("device-group:DG-EU").contains("address/addr-dg-1"));
    }

    @Test
    void crossCheckNamesTheTemplateOrDeviceGroupSourceForAMatchingOverride() throws Exception {
        var reduced = PanoramaAssignmentReducer.reduce(stream());
        var crossCheck = PanoramaCrossCheckPort.fromReduced(reduced);

        List<ConfigurationOverride> candidates = List.of(
                new ConfigurationOverride("vsys1", "address", "address/addr-shared-1", Optional.empty()),
                new ConfigurationOverride("vsys1", "address", "address/addr-dg-1", Optional.empty()),
                new ConfigurationOverride("vsys1", "address", "address/addr-unrelated", Optional.empty()));

        List<ConfigurationOverride> named = crossCheck.nameOverrideSources("SERIAL1", candidates);

        assertEquals(Optional.of("template:TPL-BASE"), named.get(0).panoramaSource());
        assertEquals(Optional.of("device-group:DG-EU"), named.get(1).panoramaSource());
        assertEquals(Optional.empty(), named.get(2).panoramaSource(),
                "an override matching no template/device-group definition stays unnamed");
    }

    private static ByteArrayInputStream stream() {
        return new ByteArrayInputStream(FIXTURE.getBytes(StandardCharsets.UTF_8));
    }
}
