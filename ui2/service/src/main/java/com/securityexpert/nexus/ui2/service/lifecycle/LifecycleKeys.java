package com.securityexpert.nexus.ui2.service.lifecycle;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The match keys of the lifecycle catalog (docs/design/LIFECYCLE_CATALOG_SOURCES_CONTRACT.md section 5). The same function
 * turns a device's stored model or version and a catalog product into a key; a device matches a catalog entry when both
 * keys are equal. Each rule is a strict pattern over the whole text: a form it does not name gives no key (null), and a
 * device without a key is reported as not matched with that reason, never as a near match.
 *
 * <p>Hardware keys: Check Point {@code 6600}, {@code 1595R}, {@code SMART-1 6000-L}; Palo Alto {@code PA-3220},
 * {@code M-600}; Cisco {@code FPR4115}, {@code ASA5516}; Fortinet {@code FG600E}, {@code FMG400G}. Software keys carry the
 * product family, so that two families of one vendor never share a train: {@code R81.20}, {@code R81.10.XX} (Quantum Spark
 * firmware), {@code PAN-OS 11.1}, {@code ASA 9.18}, {@code FortiOS 7.4}, {@code FortiManager 7.4}.</p>
 *
 * <p>Vendors without a rule here (Infoblox, Radware, Broadcom, Pulse Secure) keep the V144 behaviour: the stored text,
 * trimmed, must equal the catalog product.</p>
 */
public final class LifecycleKeys {

    // Hardware
    private static final Pattern CP_MODEL = Pattern.compile(
            "^(?:Check ?Point\\s+)?(?:Quantum(?:\\s+(?:Spark|Force|Maestro))?\\s+)?(?:CPAP-SG)?(SMART-1\\s+\\d{2,5}(?:-[A-Z0-9]{1,4})?|\\d{3,5}[A-Z]{0,3}(?:-[A-Z0-9]{1,4})?)"
                    + "(?:\\s+(?:Appliances?|Security Gateway|Gateway|Chassis))?(?:\\s*\\([^()]{1,40}\\))?$", Pattern.CASE_INSENSITIVE);
    /** A model text that names no appliance: a virtual machine or an open server has no hardware lifecycle of its own. */
    private static final Pattern VIRTUAL_PLATFORM = Pattern.compile(
            "(?i)(?:^|[^a-z])(?:open\\s*server|virtual|vm\\d*|vm-?\\d+|vmware|kvm|hyper-?v|esxi?|azure|aws|gcp|cloudguard)(?:$|[^a-z])|^(?:PA|FG|FGT|FortiGate)-?VM|^ASAv|^FTDv|^FPR-?v");
    private static final Pattern PAN_VIRTUAL_PANORAMA = Pattern.compile(
            "^(?:(?:PAN-)?Panorama(?:(?:\\s+on)?[\\s-]+VM|\\s*\\(VM\\)|\\s+Virtual(?:\\s+Appliance)?)?|PAN-PRA-VM)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAN_MODEL = Pattern.compile(
            "^(?:PAN-|Panorama\\s+)?(PA-\\d{3,4}[A-Z]?|M-\\d{3,4})(?:-ZTP|-(?:AC|DC)(?:-SYS)?)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CISCO_MODEL = Pattern.compile(
            "^(?:Cisco\\s+)?(?:(ASA)\\s?-?(55\\d\\d)(?:-?X|-SSP-?\\d{1,2}|-K\\d)?(?:\\s+with\\s+.{1,60})?|(?:FPR|Firepower|Secure Firewall)\\s?-?(\\d{4}))"
                    + "(?:\\s+(?:Security Appliance|Appliance|Threat Defense|NGFW|Series))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORTI_MODEL = Pattern.compile(
            "^(FortiGate|FGT|FG|FortiManager|FMG)[-_ ]?(\\d{2,4}[A-Z]{1,2})(?:[-_ ](POE|DSL|LTE|SFP|3G4G|PLUS))?$",
            Pattern.CASE_INSENSITIVE);

    // Software
    private static final Pattern CP_TRAIN = Pattern.compile("^(?:Check Point\\s+)?(?:Gaia\\s+)?(R\\d{2}(?:\\.\\d{2})?)(?:\\.M\\d)?(?:$|[\\s+,(].*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CP_SPARK = Pattern.compile("^(?:Check Point\\s+)?(?:Gaia\\s+)?R(\\d{2}\\.\\d{2})\\.(?:\\d{2}|xx)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAN_TRAIN = Pattern.compile("^(?:(?:PAN-OS|Panorama)\\s+)?(\\d{1,2}\\.\\d{1,2})(?:\\.x|\\.\\d+(?:-h\\d+)?)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASA_TRAIN = Pattern.compile("^(?:(?:Cisco\\s+)?(?:Adaptive Security Appliance Software\\s+)?(?:ASA\\s+)?Version\\s+|ASA\\s+)?(\\d{1,2}\\.\\d{1,2})(?:\\(\\d{1,3}\\)\\d{0,4}|\\.\\d{1,3}(?:\\.\\d{1,4})?|\\(x\\)|\\.x)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORTI_VERSION = Pattern.compile("^(?:Forti(?:OS|Manager|Gate)\\s+)?v?(\\d{1,2}\\.\\d{1,2})(?:\\.\\d{1,3})?"
                    + "(?:[-, ]*build\\s?\\d{1,5}(?:[, ].{0,40})?|\\s*\\([^()]{1,40}\\))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORTI_TRAIN = Pattern.compile("^(FortiOS|FortiManager)\\s+v?(\\d{1,2}\\.\\d{1,2})(?:\\.x)?$",
            Pattern.CASE_INSENSITIVE);

    private LifecycleKeys() {
    }

    /** True when the vendor has key rules; the other vendors match by exact text. */
    public static boolean ruled(String vendor) {
        return switch (vendor == null ? "" : vendor) {
            case "CHECKPOINT", "PALOALTO", "CISCO_ASA", "FORTINET" -> true;
            default -> false;
        };
    }

    /** True when a model text names a virtual machine or an open server (no appliance model exists to look up). */
    public static boolean virtualPlatform(String text) {
        return text != null && VIRTUAL_PLATFORM.matcher(text.strip()).find();
    }

    /** The hardware key of a model text (device or catalog), or null. */
    public static String model(String vendor, String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.strip().replaceAll("\\s+", " ");
        if (!ruled(vendor)) return value;
        Matcher m;
        switch (vendor) {
            case "CHECKPOINT" -> {
                m = CP_MODEL.matcher(value);
                return m.matches() ? m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ") : null;
            }
            case "PALOALTO" -> {
                if (PAN_VIRTUAL_PANORAMA.matcher(value).matches()) return "Panorama";
                m = PAN_MODEL.matcher(value);
                return m.matches() ? m.group(1).toUpperCase(Locale.ROOT) : null;
            }
            case "CISCO_ASA" -> {
                m = CISCO_MODEL.matcher(value);
                if (!m.matches()) return null;
                return m.group(1) != null ? "ASA" + m.group(2) : "FPR" + m.group(3);
            }
            case "FORTINET" -> {
                m = FORTI_MODEL.matcher(value);
                if (!m.matches()) return null;
                String family = m.group(1).toUpperCase(Locale.ROOT);
                String prefix = family.startsWith("FORTIM") || family.equals("FMG") ? "FMG" : "FG";
                return prefix + m.group(2).toUpperCase(Locale.ROOT) + (m.group(3) == null ? "" : "-" + m.group(3).toUpperCase(Locale.ROOT));
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The software key of a device's stored version, or null. {@code manager} says the device is a management server; it
     * decides the family only where one vendor ships two families on one version scheme (Fortinet).
     */
    public static String deviceTrain(String vendor, boolean manager, String model, String version) {
        if (version == null || version.isBlank()) return null;
        String value = version.strip().replaceAll("\\s+", " ");
        if (!ruled(vendor)) return value;
        if ("FORTINET".equals(vendor)) {
            Matcher m = FORTI_VERSION.matcher(value);
            if (!m.matches()) return null;
            String lowerModel = model == null ? "" : model.strip().toLowerCase(Locale.ROOT);
            boolean fortiManager = manager || lowerModel.startsWith("fortimanager") || lowerModel.startsWith("fmg");
            return (fortiManager ? "FortiManager " : "FortiOS ") + m.group(1);
        }
        return train(vendor, value);
    }

    /** The software key of a catalog product text, or null (the family is part of the text for Fortinet). */
    public static String catalogTrain(String vendor, String product) {
        if (product == null || product.isBlank()) return null;
        String value = product.strip().replaceAll("\\s+", " ");
        if (!ruled(vendor)) return value;
        if ("FORTINET".equals(vendor)) {
            Matcher m = FORTI_TRAIN.matcher(value);
            if (!m.matches()) return null;
            return (m.group(1).equalsIgnoreCase("FortiManager") ? "FortiManager " : "FortiOS ") + m.group(2);
        }
        return train(vendor, value);
    }

    private static String train(String vendor, String value) {
        Matcher m;
        switch (vendor) {
            case "CHECKPOINT" -> {
                m = CP_SPARK.matcher(value);
                if (m.matches()) return "R" + m.group(1) + ".XX";
                m = CP_TRAIN.matcher(value);
                return m.matches() ? m.group(1).toUpperCase(Locale.ROOT) : null;
            }
            case "PALOALTO" -> {
                m = PAN_TRAIN.matcher(value);
                return m.matches() ? "PAN-OS " + m.group(1) : null;
            }
            case "CISCO_ASA" -> {
                m = ASA_TRAIN.matcher(value);
                return m.matches() ? "ASA " + m.group(1) : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** True when the hardware key represents a virtual Panorama appliance (no physical hardware lifecycle). */
    public static boolean virtualPanorama(String vendor, String key) {
        return "PALOALTO".equals(vendor) && "Panorama".equals(key);
    }

    /** What a catalog product of this vendor and kind must look like; shown when an import row is refused. */
    public static String expectedForm(String vendor, String kind) {
        boolean hardware = "HARDWARE".equals(kind);
        return switch (vendor) {
            case "CHECKPOINT" -> hardware ? "a model such as 6600, 3600 Appliance or Smart-1 6000-L" : "a version such as R81.20, R82 or R81.10.xx";
            case "PALOALTO" -> hardware ? "a model such as PA-3220, M-600 or Panorama" : "a train such as 11.1 or PAN-OS 11.1";
            case "CISCO_ASA" -> hardware ? "a model such as FPR-4115, Firepower 4115 or ASA5516-X" : "a train such as 9.18 or ASA 9.18";
            case "FORTINET" -> hardware ? "a model such as FortiGate-600E, FGT60F or FortiManager-400G" : "FortiOS 7.4 or FortiManager 7.4";
            default -> "any text";
        };
    }
}
