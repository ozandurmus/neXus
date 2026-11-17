package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Kind;
import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Observed;

/**
 * Parses the answer of {@code InventoryReadPlan.CP_INSTALL_HISTORY} (V246): one line per install-log file,
 * {@code <local time> <file name> [Bundle_T<take>]}. A base install is {@code install_fw1_R81.20.elg}; a Jumbo install is
 * {@code install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN[_n].log}. Any other line is ignored; at most 20 events.
 */
public final class CheckPointInstallLogParser {

    static final int MAX_EVENTS = 20;
    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}) (install_fw1_(R\\d+(?:\\.\\d+)*)\\.elg"
                    + "|install_SecurePlatform_HOTFIX_(R\\d+(?:_\\d+)*)_JUMBO\\w*\\.log)(?: Bundle_T(\\d+))?\\s*$");

    private CheckPointInstallLogParser() {
    }

    public static List<Observed> parse(String output) {
        List<Observed> events = new ArrayList<>();
        if (output == null) {
            return events;
        }
        for (String line : output.split("\\R")) {
            if (events.size() >= MAX_EVENTS) {
                break;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            LocalDateTime at;
            try {
                at = LocalDateTime.parse(m.group(1));
            } catch (java.time.format.DateTimeParseException notADate) {
                continue;
            }
            String fileName = m.group(2);
            if (m.group(3) != null) {
                events.add(new Observed(Kind.BASE_INSTALL, m.group(3), Optional.empty(), at, fileName));
            } else {
                Optional<Integer> take = Optional.empty();
                if (m.group(5) != null) {
                    try {
                        take = Optional.of(Integer.parseInt(m.group(5)));
                    } catch (NumberFormatException tooLong) {
                        take = Optional.empty();
                    }
                }
                events.add(new Observed(Kind.JUMBO_INSTALL, m.group(4).replace('_', '.'), take, at, fileName));
            }
        }
        return events;
    }
}
