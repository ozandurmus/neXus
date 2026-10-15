package com.securityexpert.nexus.ui2.platform;

/**
 * The closed identity of the Packet Analysis job (PO decision record 2026-10-15, PacketBreaker ported into neXus):
 * one job type, one target kind, one owning module. V154 maps {@link #CAPABILITY} to {@link #MODULE} and to nothing
 * else; no other capability is ever mapped to {@link #MODULE}.
 */
public final class PacketAnalysisJob {
    /** Job type and capability id (they are always equal: the claim fence refuses a job whose two differ). */
    public static final String CAPABILITY = "packet_analysis_run";
    /** {@code jobs.target_kind}; {@code jobs.target_ref} is the case id. */
    public static final String TARGET_KIND = "packet_analysis_case";
    /** {@code module_runtime_control.module} and the worker role; also the prefix of the worker instance id. */
    public static final String MODULE = "packet_analysis";

    private PacketAnalysisJob() {
    }
}
