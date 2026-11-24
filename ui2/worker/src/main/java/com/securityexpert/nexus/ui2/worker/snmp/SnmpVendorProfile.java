package com.securityexpert.nexus.ui2.worker.snmp;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * The SNMPv3 reads of one pilot vendor (SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 4; V269 gate rows). Each read is
 * a GET of a fixed scalar OID or a bounded GETBULK walk of one fixed table subtree. Before any request the reads are
 * resolved against the live gate registry; a missing, unsigned or non-read row stops the vendor ({@link #gated}).
 */
public enum SnmpVendorProfile {
    CHECK_POINT("check_point", "cp_gaia_gateway", List.of(
            new Read("cp_snmp_sys_uptime", Op.GET, SnmpOids.SYS_UPTIME),
            new Read("cp_snmp_proc_usage", Op.GET, SnmpOids.CP_PROC_USAGE),
            new Read("cp_snmp_mem_total_real64", Op.GET, SnmpOids.CP_MEM_TOTAL_REAL64),
            new Read("cp_snmp_mem_active_real64", Op.GET, SnmpOids.CP_MEM_ACTIVE_REAL64),
            new Read("cp_snmp_fw_num_conn", Op.GET, SnmpOids.CP_FW_NUM_CONN))),
    PALO_ALTO("palo_alto", "pan_firewall", List.of(
            new Read("pan_snmp_sys_uptime", Op.GET, SnmpOids.SYS_UPTIME),
            new Read("pan_snmp_hr_processor_load", Op.WALK, SnmpOids.HR_PROCESSOR_LOAD),
            new Read("pan_snmp_hr_storage_table", Op.WALK, SnmpOids.HR_STORAGE_ENTRY),
            new Read("pan_snmp_session_active", Op.GET, SnmpOids.PAN_SESSION_ACTIVE)));

    public enum Op { GET, WALK }

    /** One gated read. {@code oid} is the scalar instance (GET) or the table subtree root (WALK). */
    public record Read(String gateId, Op op, String oid) {
        public String canonicalCommandKey() {
            return op.name() + " " + oid;
        }
    }

    public static final String TRANSPORT_KIND = "SNMP_V3";

    private final String vendor;
    private final String platformRoleScope;
    private final List<Read> reads;

    SnmpVendorProfile(String vendor, String platformRoleScope, List<Read> reads) {
        this.vendor = vendor;
        this.platformRoleScope = platformRoleScope;
        this.reads = reads;
    }

    public String vendor() { return vendor; }
    public String platformRoleScope() { return platformRoleScope; }
    public List<Read> reads() { return reads; }

    public List<String> getOids() {
        return reads.stream().filter(r -> r.op() == Op.GET).map(Read::oid).toList();
    }

    /**
     * The table columns walked in one GETBULK stream. Each lies under a WALK read's subtree; for hrStorageEntry only the
     * type, size and used columns are asked for.
     */
    public List<String> walkColumns() {
        return this == PALO_ALTO ? List.of(SnmpOids.HR_PROCESSOR_LOAD, SnmpOids.HR_STORAGE_TYPE, SnmpOids.HR_STORAGE_SIZE,
                SnmpOids.HR_STORAGE_USED) : List.of();
    }

    /** True when {@code oid} is a GET instance of this vendor or lies under one of its WALK subtrees. */
    public boolean permits(String oid) {
        for (Read r : reads) {
            if (r.op() == Op.GET ? r.oid().equals(oid) : SnmpOids.under(oid, r.oid())) return true;
        }
        return false;
    }

    public static Optional<SnmpVendorProfile> of(String vendor) {
        for (SnmpVendorProfile p : values()) if (p.vendor.equals(vendor)) return Optional.of(p);
        return Optional.empty();
    }

    /** True only when every read of this vendor resolves to its own signed-off CLASS_0 read gate row. */
    public boolean gated(GateRegistryPort registry) {
        if (registry == null) return false;
        for (Read read : reads) {
            try {
                var key = new CanonicalCommandKey(vendor, platformRoleScope, "not_applicable", TRANSPORT_KIND,
                        read.canonicalCommandKey());
                var resolved = GateResolver.resolve(key, Optional.of(ActionClass.CLASS_0_READ), registry);
                if (!(resolved instanceof GateResolution.Known known) || !read.gateId().equals(known.gateId())
                        || known.actionClass() != ActionClass.CLASS_0_READ) {
                    return false;
                }
            } catch (RuntimeException invalidGate) {
                return false;
            }
        }
        return true;
    }
}
