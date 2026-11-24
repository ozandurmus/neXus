package com.securityexpert.nexus.ui2.worker.snmp;

/**
 * The OIDs of design section 4, exactly as gated by V269. Names follow the vendor MIBs; Check Point memory % is
 * memActiveReal64 (used) / memTotalReal64 (design section 4 as corrected 2026-11-24).
 */
public final class SnmpOids {
    private SnmpOids() {}

    /** SNMPv2-MIB sysUpTime.0, TimeTicks (hundredths of a second). */
    public static final String SYS_UPTIME = "1.3.6.1.2.1.1.3.0";

    /** CHECKPOINT-MIB procUsage.0, percent. */
    public static final String CP_PROC_USAGE = "1.3.6.1.4.1.2620.1.6.7.2.4.0";
    /** CHECKPOINT-MIB memTotalReal64.0 (Counter64 or a digits string, depending on the MIB copy). */
    public static final String CP_MEM_TOTAL_REAL64 = "1.3.6.1.4.1.2620.1.6.7.4.3.0";
    /** CHECKPOINT-MIB memActiveReal64.0. */
    public static final String CP_MEM_ACTIVE_REAL64 = "1.3.6.1.4.1.2620.1.6.7.4.4.0";
    /** CHECKPOINT-MIB fwNumConn.0, Unsigned32. */
    public static final String CP_FW_NUM_CONN = "1.3.6.1.4.1.2620.1.1.25.3.0";

    /** HOST-RESOURCES-MIB hrProcessorLoad column, one row per processor, percent. */
    public static final String HR_PROCESSOR_LOAD = "1.3.6.1.2.1.25.3.3.1.2";
    /** HOST-RESOURCES-MIB hrStorageEntry; columns .2 type, .5 size, .6 used (in allocation units). */
    public static final String HR_STORAGE_ENTRY = "1.3.6.1.2.1.25.2.3.1";
    public static final String HR_STORAGE_TYPE = HR_STORAGE_ENTRY + ".2";
    public static final String HR_STORAGE_SIZE = HR_STORAGE_ENTRY + ".5";
    public static final String HR_STORAGE_USED = HR_STORAGE_ENTRY + ".6";
    /** HOST-RESOURCES-TYPES hrStorageRam. */
    public static final String HR_STORAGE_RAM = "1.3.6.1.2.1.25.2.1.2";
    /** PAN-COMMON-MIB panSessionActive.0, Integer32. */
    public static final String PAN_SESSION_ACTIVE = "1.3.6.1.4.1.25461.2.1.2.3.3.0";

    /** True when {@code oid} is {@code root} or lies under it (dot-separated prefix). */
    public static boolean under(String oid, String root) {
        return oid.equals(root) || oid.startsWith(root + ".");
    }
}
