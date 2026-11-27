package com.securityexpert.nexus.ui2.service.eventtrigger;

import static org.assertj.core.api.Assertions.assertThat;

import com.securityexpert.nexus.ui2.service.eventtrigger.EventTriggerKillSwitch.Checkpoint;
import com.securityexpert.nexus.ui2.service.eventtrigger.EventTriggerKillSwitch.Decision;
import com.securityexpert.nexus.ui2.service.eventtrigger.EventTriggerKillSwitch.SettingsSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EventTriggerKillSwitchTest {

    private static final String VENDOR_CP = "checkpoint";
    private static final String SOURCE_ID = "00000000-0000-4000-8000-000000000101";

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void nullSnapshotFailsClosedToUnavailable(Checkpoint checkpoint) {
        Decision decision = EventTriggerKillSwitch.evaluate((SettingsSnapshot) null, checkpoint, VENDOR_CP, SOURCE_ID);

        assertThat(decision).isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
        assertThat(decision.isPermitted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void incompleteSnapshotWithNullMapsFailsClosedToUnavailable(Checkpoint checkpoint) {
        SettingsSnapshot nullVendorMap = new SettingsSnapshot(true, null, Map.of(SOURCE_ID, true));
        SettingsSnapshot nullSourceMap = new SettingsSnapshot(true, Map.of(VENDOR_CP, true), null);

        assertThat(EventTriggerKillSwitch.evaluate(nullVendorMap, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
        assertThat(EventTriggerKillSwitch.evaluate(nullSourceMap, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void throwingOrNullSupplierFailsClosedToUnavailable(Checkpoint checkpoint) {
        Supplier<SettingsSnapshot> throwing = () -> {
            throw new IllegalStateException("database unavailable");
        };

        assertThat(EventTriggerKillSwitch.evaluate(throwing, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
        assertThat(EventTriggerKillSwitch.evaluate((Supplier<SettingsSnapshot>) null, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
        assertThat(EventTriggerKillSwitch.evaluate(() -> null, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
    }

    @Test
    void nullCheckpointFailsClosedToUnavailable() {
        SettingsSnapshot enabledAll = new SettingsSnapshot(
                true,
                Map.of(VENDOR_CP, true),
                Map.of(SOURCE_ID, true));

        assertThat(EventTriggerKillSwitch.evaluate(enabledAll, null, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void missingOrBlankVendorOrEventSourceIdFailsClosedToUnavailable(Checkpoint checkpoint) {
        SettingsSnapshot enabledAll = new SettingsSnapshot(
                true,
                Map.of(VENDOR_CP, true),
                Map.of(SOURCE_ID, true));

        assertThat(EventTriggerKillSwitch.evaluate(enabledAll, checkpoint, null, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
        assertThat(EventTriggerKillSwitch.evaluate(enabledAll, checkpoint, "   ", SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
        assertThat(EventTriggerKillSwitch.evaluate(enabledAll, checkpoint, VENDOR_CP, null))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
        assertThat(EventTriggerKillSwitch.evaluate(enabledAll, checkpoint, VENDOR_CP, "\t "))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
    }

    @Test
    void unavailableTakesPrecedenceOverGlobalVendorAndSourceOff() {
        SettingsSnapshot allOff = new SettingsSnapshot(
                false,
                Map.of(VENDOR_CP, false),
                Map.of(SOURCE_ID, false));

        assertThat(EventTriggerKillSwitch.evaluate(allOff, Checkpoint.NEW_SUBMISSION, "", SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
        assertThat(EventTriggerKillSwitch.evaluate(allOff, Checkpoint.UNCLAIMED_EXEMPTION_CLAIM, VENDOR_CP, " "))
                .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void globalOffOverridesEnabledVendorAndSource(Checkpoint checkpoint) {
        SettingsSnapshot globalOff = new SettingsSnapshot(
                false,
                Map.of(VENDOR_CP, true),
                Map.of(SOURCE_ID, true));

        Decision decision = EventTriggerKillSwitch.evaluate(globalOff, checkpoint, VENDOR_CP, SOURCE_ID);

        assertThat(decision).isEqualTo(Decision.KILL_SWITCH_GLOBAL_OFF);
        assertThat(decision.isPermitted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void vendorOffOverridesEnabledSource(Checkpoint checkpoint) {
        SettingsSnapshot vendorOff = new SettingsSnapshot(
                true,
                Map.of(VENDOR_CP, false),
                Map.of(SOURCE_ID, true));

        Decision decision = EventTriggerKillSwitch.evaluate(vendorOff, checkpoint, VENDOR_CP, SOURCE_ID);

        assertThat(decision).isEqualTo(Decision.KILL_SWITCH_VENDOR_OFF);
        assertThat(decision.isPermitted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void missingVendorEntryFailsClosedToVendorOff(Checkpoint checkpoint) {
        SettingsSnapshot missingVendor = new SettingsSnapshot(
                true,
                Map.of("paloalto", true),
                Map.of(SOURCE_ID, true));

        assertThat(EventTriggerKillSwitch.evaluate(missingVendor, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_VENDOR_OFF);
    }

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void nullBooleanValueInVendorOrSourceMapFailsClosed(Checkpoint checkpoint) {
        Map<String, Boolean> vendorMap = new HashMap<>();
        vendorMap.put(VENDOR_CP, null);
        SettingsSnapshot nullVendorFlag = new SettingsSnapshot(true, vendorMap, Map.of(SOURCE_ID, true));

        Map<String, Boolean> sourceMap = new HashMap<>();
        sourceMap.put(SOURCE_ID, null);
        SettingsSnapshot nullSourceFlag = new SettingsSnapshot(true, Map.of(VENDOR_CP, true), sourceMap);

        assertThat(EventTriggerKillSwitch.evaluate(nullVendorFlag, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_VENDOR_OFF);
        assertThat(EventTriggerKillSwitch.evaluate(nullSourceFlag, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SOURCE_OFF);
    }

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void sourceOffAndMissingSourceFailClosedToSourceOff(Checkpoint checkpoint) {
        SettingsSnapshot sourceOff = new SettingsSnapshot(
                true,
                Map.of(VENDOR_CP, true),
                Map.of(SOURCE_ID, false));
        SettingsSnapshot missingSource = new SettingsSnapshot(
                true,
                Map.of(VENDOR_CP, true),
                Map.of("00000000-0000-4000-8000-000000000999", true));

        assertThat(EventTriggerKillSwitch.evaluate(sourceOff, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SOURCE_OFF);
        assertThat(EventTriggerKillSwitch.evaluate(missingSource, checkpoint, VENDOR_CP, SOURCE_ID))
                .isEqualTo(Decision.KILL_SWITCH_SOURCE_OFF);
    }

    @Test
    void allThreeEnabledPermitsBothNewSubmissionAndUnclaimedExemptionClaim() {
        SettingsSnapshot enabledAll = new SettingsSnapshot(
                true,
                Map.of(VENDOR_CP, true),
                Map.of(SOURCE_ID, true));

        Decision submission = EventTriggerKillSwitch.evaluateNewSubmission(enabledAll, VENDOR_CP, SOURCE_ID);
        Decision claim = EventTriggerKillSwitch.evaluateUnclaimedExemptionClaim(enabledAll, VENDOR_CP, SOURCE_ID);

        assertThat(submission).isEqualTo(Decision.PERMITTED);
        assertThat(submission.isPermitted()).isTrue();
        assertThat(claim).isEqualTo(Decision.PERMITTED);
        assertThat(claim.isPermitted()).isTrue();
    }

    @Test
    void identifiersAreOpaqueAndNeverCaseFolded() {
        SettingsSnapshot enabledAll = new SettingsSnapshot(
                true,
                Map.of(VENDOR_CP, true),
                Map.of("src-Opaque-01", true));

        assertThat(EventTriggerKillSwitch.evaluateNewSubmission(enabledAll, "CHECKPOINT", "src-Opaque-01"))
                .isEqualTo(Decision.KILL_SWITCH_VENDOR_OFF);
        assertThat(EventTriggerKillSwitch.evaluateNewSubmission(enabledAll, VENDOR_CP, "src-opaque-01"))
                .isEqualTo(Decision.KILL_SWITCH_SOURCE_OFF);
    }

    @Test
    void throwingSupplierLogsWarningWithExceptionContextAndDistinguishesFromNullOrBlankInputs() {
        Logger logger = Logger.getLogger(EventTriggerKillSwitch.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            IllegalStateException failure = new IllegalStateException("database unavailable");
            Supplier<SettingsSnapshot> throwing = () -> {
                throw failure;
            };

            assertThat(EventTriggerKillSwitch.evaluate(throwing, Checkpoint.NEW_SUBMISSION, VENDOR_CP, SOURCE_ID))
                    .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
            assertThat(EventTriggerKillSwitch.evaluate(
                    (Supplier<SettingsSnapshot>) null, Checkpoint.NEW_SUBMISSION, VENDOR_CP, SOURCE_ID))
                    .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);
            SettingsSnapshot enabledAll = new SettingsSnapshot(
                    true, Map.of(VENDOR_CP, true), Map.of(SOURCE_ID, true));
            assertThat(EventTriggerKillSwitch.evaluate(enabledAll, Checkpoint.UNCLAIMED_EXEMPTION_CLAIM, " ", SOURCE_ID))
                    .isEqualTo(Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE);

            assertThat(records).hasSize(3);

            LogRecord thrownRecord = records.get(0);
            assertThat(thrownRecord.getLevel()).isEqualTo(Level.WARNING);
            assertThat(thrownRecord.getMessage())
                    .contains("settings load failed")
                    .contains("IllegalStateException")
                    .contains("NEW_SUBMISSION");
            assertThat(thrownRecord.getThrown()).isSameAs(failure);

            LogRecord nullSupplierRecord = records.get(1);
            assertThat(nullSupplierRecord.getLevel()).isEqualTo(Level.WARNING);
            assertThat(nullSupplierRecord.getMessage()).contains("settings supplier is null");
            assertThat(nullSupplierRecord.getThrown()).isNull();

            LogRecord blankInputRecord = records.get(2);
            assertThat(blankInputRecord.getLevel()).isEqualTo(Level.WARNING);
            assertThat(blankInputRecord.getMessage()).contains("missing or blank evaluation input");
            assertThat(blankInputRecord.getThrown()).isNull();
        } finally {
            logger.removeHandler(capture);
        }
    }
}
