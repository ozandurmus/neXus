package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.securityexpert.nexus.ui2.discovery.cp.Address;

/**
 * §7.4 / record §4 items 6, 6a: parses one raw {@code netstat -an} reading
 * of the management server's own connection table into {@link
 * ConnectionTableRow} values -- reduction (CS-3/CS-6a/CS-6b) happens
 * afterwards, in {@link ConnectionTableReducer}, never here and never on the
 * server (T-7: no awk pipeline, the raw text is parsed in Java and
 * discarded). Column position is the record's own measured method (record
 * §4's awk indexing, {@code $5}/{@code $6}) -- not the classification
 * fragility {@link CpObjectDumpParser} avoids for named object fields.
 */
final class NetstatConnectionTableParser {

    private NetstatConnectionTableParser() {
    }

    static List<ConnectionTableRow> parse(String text) {
        List<ConnectionTableRow> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] columns = line.trim().split("\\s+");
            // record §4 item 6: only a TCP row can carry a real peer and a channel state; column 5 ($5) is
            // the Foreign Address, column 6 ($6) the state -- both 1-indexed in the record's own awk form.
            if (columns.length < 6 || !columns[0].toLowerCase(Locale.ROOT).startsWith("tcp")) {
                continue;
            }
            String foreignAddress = columns[4];
            int lastColon = foreignAddress.lastIndexOf(':');
            if (lastColon < 0) {
                continue;
            }
            String ip = foreignAddress.substring(0, lastColon);
            String portToken = foreignAddress.substring(lastColon + 1);
            // A listening socket has no real peer ("*" in either half) -- not a channel to any device.
            if (ip.equals("*") || portToken.equals("*")) {
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(portToken);
            } catch (NumberFormatException e) {
                continue;
            }
            boolean established = "ESTABLISHED".equals(columns[5]);
            rows.add(new ConnectionTableRow(Address.of(ip), port, established));
        }
        return rows;
    }
}
