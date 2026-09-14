package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;

/**
 * {@code cphaprob -a -m if} (14C §3), cluster members only. {@code
 * UNVERIFIED} against a real gateway. 14C §3: "only the section after a
 * line beginning 'virtual cluster interfaces:' (case-insensitive) up to
 * the next section is read; first token interface name, first IPv4
 * literal the address"; every address this class produces is {@link
 * InventoryAddress#ROLE_CLUSTER_VIRTUAL}, and this class never reads a
 * member address (that is {@link CheckPointIpAddrParser}'s read alone --
 * 14C §3: "the two never mix").
 */
public final class CheckPointClusterVirtualInterfaceParser {

    /** One cluster virtual address, named by the interface it belongs to (merged onto that interface by the caller). */
    public record VirtualInterfaceAddress(String interfaceName, String address) {
    }

    private static final Pattern SECTION_HEADER = Pattern.compile("(?i)^\\s*virtual cluster interfaces\\s*:\\s*$");
    private static final Pattern OTHER_SECTION_HEADER = Pattern.compile("^\\S.*:\\s*$");
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");

    private CheckPointClusterVirtualInterfaceParser() {
    }

    public static List<VirtualInterfaceAddress> parse(String output) {
        List<VirtualInterfaceAddress> result = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return result;
        }
        boolean inSection = false;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (!inSection) {
                if (SECTION_HEADER.matcher(line).matches()) {
                    inSection = true;
                }
                continue;
            }
            if (line.isEmpty() || (OTHER_SECTION_HEADER.matcher(line).matches() && !SECTION_HEADER.matcher(line).matches())) {
                break;
            }
            String[] tokens = line.split("\\s+");
            if (tokens.length == 0) {
                continue;
            }
            String interfaceName = tokens[0];
            Matcher ipv4 = IPV4_LITERAL.matcher(line);
            if (ipv4.find()) {
                result.add(new VirtualInterfaceAddress(interfaceName, ipv4.group(1)));
            }
        }
        return result;
    }
}
