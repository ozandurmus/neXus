package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture;

/**
 * A synthetic masked pool for layer-B tests: {@code devices} Check Point gateways ({@code dev-01} ...), each writing two
 * background templates every hour of every training day; {@code dev-01}..{@code dev-10} boot once; {@code dev-05} logs
 * one EDAC corrected-error line once; the device under test ({@code dev-tango}) also writes a one-device routing template
 * once an hour. The host name in every raw line is {@code edge-fw-01}, which the masking turns into {@code FW-TANGO-04}.
 */
final class LayerBTestPool {
    static final DateTimeFormatter GAIA = DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy", Locale.ENGLISH);
    static final String TANGO = "dev-tango";
    static final String SSHD = "sshd[812]: Accepted publickey for USR-0A1B2C3D4E from 192.0.2.17 port 50122 ssh2";
    static final String CROND = "crond[2201]: job 7 started";
    static final String ROUTED = "routed[4242]: neighbor 192.0.2.33 state changed to down on eth1";
    static final String EDAC = "kernel: EDAC MC0: 1 CE memory read error on CPU_SrcID#0_MC#0_Chan#1_DIMM#0 (channel:1 slot:0 page:0x4a1f2 offset:0x0 grain:32)";
    static final String MCE = "kernel: [Hardware Error]: Machine check events logged";
    static final String BOOT = "kernel: Linux version 3.10.0 (gcc version 4.8.5) #1 SMP";

    final LogMasking masking = new LogMasking(SystemLogReadFixture.PRIVACY_KEY, () -> KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04")));
    final LocalDate from;
    final int days;
    final int devices;
    /** Whether the boots and the EDAC line are written (off: the pool holds background templates only). */
    final boolean events;

    LayerBTestPool(LocalDate from, int days, int devices) {
        this(from, days, devices, true);
    }

    LayerBTestPool(LocalDate from, int days, int devices, boolean events) {
        this.from = from;
        this.days = days;
        this.devices = devices;
        this.events = events;
    }

    static String raw(LocalDateTime t, String text) {
        return GAIA.format(t) + " edge-fw-01 " + text;
    }

    static String device(int i) {
        return String.format(Locale.ROOT, "dev-%02d", i);
    }

    List<String> deviceIds() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= devices; i++) out.add(device(i));
        out.add(TANGO);
        return out;
    }

    /** The training lines, masked, in the order the build reads them (source, day, device). */
    List<LogCatalogBuilder.Line> trainingLines() {
        LogMasking.Session session = masking.session();
        List<LogCatalogBuilder.Line> out = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            LocalDate day = from.plusDays(d);
            for (String dev : deviceIds().stream().sorted().toList()) {
                for (int h = 0; h < 24; h++) {
                    LocalDateTime t = day.atTime(h, 5);
                    add(out, session, dev, t, SSHD);
                    add(out, session, dev, t.plusMinutes(20), CROND);
                    if (dev.equals(TANGO)) add(out, session, dev, t.plusMinutes(30), ROUTED);
                }
                int index = dev.equals(TANGO) ? 0 : Integer.parseInt(dev.substring(4));
                if (events && d == 2 && index >= 1 && index <= 10) add(out, session, dev, day.atTime(3, 0), BOOT);
                if (events && d == 3 && index == 5) add(out, session, dev, day.atTime(4, 0), EDAC);
            }
        }
        return out;
    }

    private static void add(List<LogCatalogBuilder.Line> out, LogMasking.Session session, String device, LocalDateTime t, String text) {
        String masked = session.maskLine(raw(t, text));
        out.add(new LogCatalogBuilder.Line(device, "cp_messages", t, masked));
    }

    LogCatalogBuilder builder(LogCatalogBuilder.Params params) {
        return new LogCatalogBuilder(new LogTemplateNormaliser(), new HardwareMarkers(new LogKeyToken(SystemLogReadFixture.PRIVACY_KEY)),
                new LogTemplateIds(SystemLogReadFixture.PRIVACY_KEY), new LogKeyToken(SystemLogReadFixture.PRIVACY_KEY), params, from,
                from.plusDays(days - 1L), Map.of());
    }

    LogCatalogBuilder.Draft build(String version) {
        LogCatalogBuilder b = builder(LogCatalogBuilder.Params.defaults());
        for (LogCatalogBuilder.Line l : trainingLines()) b.accept(l);
        return b.finish(version);
    }
}
