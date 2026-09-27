package com.securityexpert.nexus.ui2.worker.compliance.evaluator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.securityexpert.nexus.ui2.worker.compliance.catalog.FortinetAsaComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.DisplayStatus;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationItem;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationResult;
import com.securityexpert.nexus.ui2.worker.compliance.model.ReasonCode;
import com.securityexpert.nexus.ui2.worker.compliance.model.Verdict;

/** Evaluates configuration intent only; it never interprets a missing or withheld setting as a default. */
public final class FortinetAsaComplianceEvaluator {
    private FortinetAsaComplianceEvaluator() {}

    private record Block(String section, String scope, String edit, Map<String, String> settings) {
        String get(String key) { return settings.get(key); }
    }

    private record Parsed(List<Block> blocks, boolean complete) {
        List<Block> section(String name) { return blocks.stream().filter(b -> b.section().equals(name)).toList(); }
    }

    public static EvaluationResult evaluate(String deviceId, String vendor, String text) {
        List<ComplianceControl> controls = FortinetAsaComplianceCatalog.getControls(vendor);
        Parsed forti = "fortinet".equals(vendor) ? parseForti(text) : null;
        List<String> asa = "cisco_asa".equals(vendor) && text != null
                ? text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList() : List.of();
        boolean complete = "fortinet".equals(vendor) ? forti.complete()
                : asa.stream().anyMatch(s -> s.equals("end") || s.equals(": end"))
                        && (asa.getLast().equals("end") || asa.getLast().equals(": end"));
        List<EvaluationItem> items = new ArrayList<>();
        for (ComplianceControl c : controls) {
            Verdict verdict = !complete ? Verdict.UNKNOWN : "fortinet".equals(vendor)
                    ? forti(c.id(), forti) : asa(c.id(), asa);
            DisplayStatus display = switch (verdict) {
                case PASS -> DisplayStatus.PASS;
                case FAIL -> DisplayStatus.FAIL;
                case NOT_APPLICABLE -> DisplayStatus.NOT_APPLICABLE;
                case UNKNOWN -> DisplayStatus.DATA_UNAVAILABLE;
            };
            items.add(new EvaluationItem(c.id(), c.title(), c.severity(), c.frameworks(), verdict,
                    switch (verdict) {
                        case PASS -> ReasonCode.ASSERTION_SATISFIED;
                        case FAIL -> ReasonCode.ASSERTION_FAILED;
                        case NOT_APPLICABLE -> ReasonCode.PROVEN_UNSUPPORTED;
                        case UNKNOWN -> ReasonCode.EVIDENCE_MISSING;
                    }, display, verdict == Verdict.UNKNOWN ? c.id() : null, null,
                    verdict == Verdict.UNKNOWN ? "Configuration evidence is missing, masked, partial, or ambiguous."
                            : verdict == Verdict.NOT_APPLICABLE ? "Configuration explicitly disables HA or failover."
                            : verdict == Verdict.PASS ? "Explicit configuration satisfies this control."
                            : "Explicit configuration does not satisfy this control.", null));
        }
        int pass = (int) items.stream().filter(i -> i.verdict() == Verdict.PASS).count();
        int fail = (int) items.stream().filter(i -> i.verdict() == Verdict.FAIL).count();
        int unknown = (int) items.stream().filter(i -> i.verdict() == Verdict.UNKNOWN).count();
        int total = items.size();
        return new EvaluationResult(deviceId, vendor, total, pass, fail, unknown,
                pct(pass, pass + fail), pct(pass + fail, total), pct(pass, total), items);
    }

    private static double pct(int numerator, int denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    private static Parsed parseForti(String text) {
        if (text == null || text.isBlank()) return new Parsed(List.of(), false);
        List<Block> blocks = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        // The block each open statement created: after next/end the enclosing block is simply the new top. (A key
        // lookup by section/scope/edit missed a config nested inside an edit: real FortiOS text, 2026-09-27.)
        List<Block> open = new ArrayList<>();
        Block current = null;
        boolean valid = true;
        boolean closed = false;
        boolean inQuotedValue = false;
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            // A quoted value can span lines (replacement-message buffers); its continuation lines are not statements.
            if (inQuotedValue) {
                if (unescapedQuotes(raw) % 2 == 1) inQuotedValue = false;
                continue;
            }
            if ((line.startsWith("set ") || line.startsWith("unset ")) && unescapedQuotes(line) % 2 == 1) inQuotedValue = true;
            if (line.startsWith("config ")) {
                String name = line.substring(7);
                stack.add("config " + name);
                String scope = scope(stack);
                current = new Block(name, scope, "", new HashMap<>());
                blocks.add(current);
                open.add(current);
                closed = false;
            } else if (line.startsWith("edit ")) {
                if (stack.isEmpty()) { valid = false; continue; }
                stack.add(line);
                current = new Block(section(stack), scope(stack), line.substring(5), new HashMap<>());
                blocks.add(current);
                open.add(current);
                closed = false;
            } else if (line.startsWith("set ") || line.startsWith("unset ")) {
                if (current == null || stack.isEmpty()) { valid = false; continue; }
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 2) { valid = false; continue; }
                String value = parts.length == 3 ? parts[2] : "";
                String previous = current.settings().get(parts[1]);
                current.settings().put(parts[1], previous != null && !previous.equals(value) ? "[withheld]" : value);
                closed = false;
            } else if (line.equals("next") || line.equals("end")) {
                if (stack.isEmpty()) { valid = false; continue; }
                String top = stack.removeLast();
                open.removeLast();
                if ((line.equals("next") && !top.startsWith("edit ")) || (line.equals("end") && !top.startsWith("config "))) valid = false;
                current = open.isEmpty() ? null : open.getLast();
                closed = stack.isEmpty() && line.equals("end");
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                valid = false;
            }
        }
        return new Parsed(blocks, valid && !inQuotedValue && stack.isEmpty() && closed && !blocks.isEmpty());
    }

    private static int unescapedQuotes(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '\\') { i++; continue; }
            if (line.charAt(i) == '"') count++;
        }
        return count;
    }

    private static String section(List<String> stack) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).startsWith("config ") && !Set.of("config global", "config vdom").contains(stack.get(i)))
                return stack.get(i).substring(7);
        }
        return "";
    }

    private static String scope(List<String> stack) {
        for (int i = 0; i + 1 < stack.size(); i++) {
            if (stack.get(i).equals("config vdom") && stack.get(i + 1).startsWith("edit ")) return stack.get(i + 1);
        }
        return "global";
    }


    private static Verdict forti(String id, Parsed parsed) {
        List<Block> blocks = switch (id) {
            case "fg_admin_no_cleartext" -> parsed.section("system interface").stream().filter(b -> !b.edit().isEmpty()).toList();
            case "fg_remote_logging" -> parsed.blocks().stream().filter(b -> Set.of("log syslogd setting", "log fortianalyzer setting").contains(b.section())).toList();
            case "fg_ntp_configured" -> parsed.section("system ntp");
            case "fg_ha_heartbeat_defined" -> parsed.section("system ha");
            default -> parsed.section("system global");
        };
        if (blocks.isEmpty()) return Verdict.UNKNOWN;
        if ((id.equals("fg_admin_no_cleartext") || id.equals("fg_remote_logging"))
                && blocks.stream().map(Block::scope).distinct().count() > 1) return Verdict.UNKNOWN;
        return switch (id) {
            case "fg_admin_no_cleartext" -> blocks.stream().anyMatch(b -> containsWord(b.get("allowaccess"), "http")
                    || containsWord(b.get("allowaccess"), "telnet")) ? Verdict.FAIL
                    : blocks.stream().allMatch(b -> visible(b.get("allowaccess"))) ? Verdict.PASS : Verdict.UNKNOWN;
            case "fg_remote_logging" -> blocks.stream().anyMatch(b -> "enable".equals(b.get("status"))
                    && visible(b.get("server"))) ? Verdict.PASS
                    : blocks.stream().anyMatch(b -> b.get("server") != null && b.get("server").contains("[withheld]"))
                            ? Verdict.UNKNOWN
                    : blocks.stream().allMatch(b -> b.get("status") != null &&
                            ("disable".equals(b.get("status")) || ("enable".equals(b.get("status")) && b.get("server") != null)))
                            ? Verdict.FAIL : Verdict.UNKNOWN;
            case "fg_ntp_configured" -> single(blocks, "ntpsync", "enable", "disable", b -> {
                if ("fortiguard".equals(b.get("type"))) return Verdict.PASS;
                return parsed.blocks().stream().anyMatch(x -> x.section().equals("ntpserver") && x.scope().equals(b.scope())
                        && visible(x.get("server")))
                        ? Verdict.PASS : Verdict.UNKNOWN;
            });
            case "fg_ssh_v1_disabled" -> single(blocks, "admin-ssh-v1", "disable", "enable", b -> Verdict.PASS);
            case "fg_global_telnet_disabled" -> single(blocks, "admin-telnet", "disable", "enable", b -> Verdict.PASS);
            case "fg_admin_tls_minimum" -> single(blocks, "admin-https-ssl-versions", "", "", b -> {
                String value = b.get("admin-https-ssl-versions");
                if (value == null || value.contains("[withheld]")) return Verdict.UNKNOWN;
                List<String> tokens = List.of(value.split("\\s+"));
                if (tokens.stream().anyMatch(t -> Set.of("tlsv1-0", "tlsv1-1", "sslv3").contains(t))) return Verdict.FAIL;
                return tokens.stream().allMatch(t -> Set.of("tlsv1-2", "tlsv1-3").contains(t)) ? Verdict.PASS : Verdict.UNKNOWN;
            });
            case "fg_ha_heartbeat_defined" -> single(blocks, "mode", "", "", b -> {
                String mode = b.get("mode");
                if ("standalone".equals(mode)) return Verdict.NOT_APPLICABLE;
                if (mode == null || !Set.of("a-p", "a-a").contains(mode)) return Verdict.UNKNOWN;
                String heartbeat = b.get("hbdev");
                if (heartbeat == null) return Verdict.UNKNOWN;
                if (heartbeat.isBlank() || heartbeat.equals("\"\"")) return Verdict.FAIL;
                return heartbeat.matches("(?:\\\"[^\\\"]+\\\"|\\S+) \\d+(?: (?:\\\"[^\\\"]+\\\"|\\S+) \\d+)*")
                        ? Verdict.PASS : Verdict.UNKNOWN;
            });
            default -> Verdict.UNKNOWN;
        };
    }

    private static Verdict single(List<Block> blocks, String key, String pass, String fail,
            java.util.function.Function<Block, Verdict> onPass) {
        if (blocks.size() != 1 || !blocks.getFirst().scope().equals("global")) return Verdict.UNKNOWN;
        String value = blocks.getFirst().get(key);
        if (value == null || value.contains("[withheld]")) return Verdict.UNKNOWN;
        if (!fail.isEmpty() && value.equals(fail)) return Verdict.FAIL;
        if (!pass.isEmpty() && !value.equals(pass)) return Verdict.UNKNOWN;
        return onPass.apply(blocks.getFirst());
    }

    private static boolean visible(String value) { return value != null && !value.isBlank() && !value.equals("\"\"") && !value.contains("[withheld]"); }
    private static boolean containsWord(String value, String word) {
        return value != null && List.of(value.split("\\s+")).contains(word);
    }

    private static Verdict asa(String id, List<String> lines) {
        if (lines.stream().anyMatch(s -> s.contains("[withheld]") && relevantMasked(id, s))) return Verdict.UNKNOWN;
        return switch (id) {
            case "asa_http_sources_restricted" -> {
                if (lines.contains("no http server enable") && lines.stream().anyMatch(s -> s.startsWith("http server enable"))) yield Verdict.UNKNOWN;
                if (lines.contains("no http server enable")) yield Verdict.PASS;
                if (!lines.contains("http server enable") && lines.stream().noneMatch(s -> s.startsWith("http server enable "))) yield Verdict.UNKNOWN;
                // `http redirect <if> <port>` is an HTTP-to-HTTPS redirect, not a source rule.
                List<String> rules = lines.stream().filter(s -> s.startsWith("http ") && !s.startsWith("http server ")
                        && !s.startsWith("http redirect ")).toList();
                if (rules.isEmpty()) yield Verdict.UNKNOWN;
                if (rules.stream().anyMatch(s -> s.matches("http (?:0\\.0\\.0\\.0 0\\.0\\.0\\.0|::/0) \\S+"))) yield Verdict.FAIL;
                yield rules.stream().allMatch(FortinetAsaComplianceEvaluator::restrictedHttpRule) ? Verdict.PASS : Verdict.UNKNOWN;
            }
            // `telnet timeout <n>` is in every running-config and grants no access; only source rules count.
            case "asa_telnet_absent" -> lines.stream().anyMatch(s -> s.matches("telnet \\S+ \\S+ \\S+")) ? Verdict.FAIL
                    : lines.stream().anyMatch(s -> s.startsWith("telnet ") && !s.matches("telnet timeout \\d+"))
                            ? Verdict.UNKNOWN : Verdict.PASS;
            case "asa_ssh_aaa" -> aaa(lines, "ssh");
            case "asa_http_aaa" -> {
                if (lines.contains("no http server enable") && lines.stream().anyMatch(s -> s.startsWith("http server enable"))) yield Verdict.UNKNOWN;
                if (lines.contains("no http server enable")) yield Verdict.NOT_APPLICABLE;
                if (!lines.contains("http server enable") && lines.stream().noneMatch(s -> s.startsWith("http server enable "))) yield Verdict.UNKNOWN;
                yield aaa(lines, "http");
            }
            case "asa_logging_enabled" -> explicit(lines, "logging enable", "no logging enable");
            case "asa_remote_syslog" -> {
                Verdict logging = explicit(lines, "logging enable", "no logging enable");
                if (logging != Verdict.PASS) yield Verdict.UNKNOWN;
                if (lines.stream().anyMatch(s -> s.matches("logging host \\S+ \\S+(?: \\S+)?"))) yield Verdict.PASS;
                if (lines.stream().anyMatch(s -> s.startsWith("logging host ") || s.startsWith("logging asdm")
                        || s.startsWith("logging buffered"))) yield Verdict.UNKNOWN;
                yield Verdict.FAIL;
            }
            case "asa_log_timestamps" -> explicit(lines, "logging timestamp", "no logging timestamp");
            case "asa_ntp_server" -> {
                if (lines.stream().anyMatch(s -> s.matches("ntp server \\S+(?: \\S+)*"))) yield Verdict.PASS;
                if (lines.stream().anyMatch(s -> s.startsWith("ntp server ") || s.startsWith("ntp authenticate"))) yield Verdict.UNKNOWN;
                yield Verdict.FAIL;
            }
            case "asa_failover_link" -> {
                if (lines.contains("no failover") && lines.contains("failover")) yield Verdict.UNKNOWN;
                if (lines.contains("no failover")) yield Verdict.NOT_APPLICABLE;
                if (!lines.contains("failover")) yield Verdict.UNKNOWN;
                if (lines.contains("no failover lan interface")) yield Verdict.FAIL;
                // A single-context projection cannot prove system context and independent peer observation.
                yield Verdict.UNKNOWN;
            }
            default -> Verdict.UNKNOWN;
        };
    }

    private static Verdict explicit(List<String> lines, String positive, String negative) {
        if (lines.contains(positive) && !lines.contains(negative)) return Verdict.PASS;
        if (lines.contains(negative) && !lines.contains(positive)) return Verdict.FAIL;
        return Verdict.UNKNOWN;
    }

    private static boolean restrictedHttpRule(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length == 4) return ipv4(parts[1]) && ipv4(parts[2]) && !parts[2].equals("0.0.0.0");
        if (parts.length == 3 && parts[1].matches("[0-9a-fA-F:]+/\\d{1,3}")) {
            int prefix = Integer.parseInt(parts[1].substring(parts[1].lastIndexOf('/') + 1));
            return prefix > 0 && prefix <= 128;
        }
        return false;
    }

    private static boolean ipv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (!octet.matches("\\d{1,3}") || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }

    private static Verdict aaa(List<String> lines, String service) {
        String prefix = "aaa authentication " + service + " console ";
        List<String> methods = lines.stream().filter(s -> s.startsWith(prefix)).toList();
        if (!methods.isEmpty() && lines.contains("no aaa authentication " + service + " console")) return Verdict.UNKNOWN;
        if (methods.size() != 1) return lines.contains("no aaa authentication " + service + " console") ? Verdict.FAIL : Verdict.UNKNOWN;
        String method = methods.getFirst().substring(prefix.length());
        if (method.equalsIgnoreCase("NONE")) return Verdict.FAIL;
        if (method.equals("LOCAL")) return lines.stream().anyMatch(s -> s.startsWith("username ")) ? Verdict.PASS : Verdict.UNKNOWN;
        String group = method.split("\\s+")[0];
        return lines.stream().anyMatch(s -> s.startsWith("aaa-server " + group + " protocol "))
                && lines.stream().anyMatch(s -> s.startsWith("aaa-server " + group + " (") && s.contains(") host "))
                ? Verdict.PASS : Verdict.UNKNOWN;
    }

    private static boolean relevantMasked(String id, String line) {
        String first = line.split("\\s+")[0].toLowerCase(Locale.ROOT);
        return switch (id) {
            case "asa_http_sources_restricted", "asa_http_aaa" -> Set.of("http", "aaa").contains(first);
            case "asa_telnet_absent" -> first.equals("telnet");
            // `username` lines carry a password and are masked whole; a masked line still proves the account exists.
            case "asa_ssh_aaa" -> Set.of("aaa", "aaa-server").contains(first);
            case "asa_logging_enabled", "asa_remote_syslog", "asa_log_timestamps" -> first.equals("logging");
            case "asa_ntp_server" -> first.equals("ntp");
            case "asa_failover_link" -> first.equals("failover");
            default -> false;
        };
    }
}
