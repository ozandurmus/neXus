package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.List;

/**
 * One Check Point incremental text source, a <em>file family</em>: the live file plus its rotated siblings
 * (contract section 2.1). The declaration order is the fixed run order of section 3.2 step 2.
 */
public enum SourceFamily {
    CP_MESSAGES("cp_messages", "messages"),
    CP_FWD_ELG("cp_fwd_elg", "fwd.elg"),
    CP_CPD_ELG("cp_cpd_elg", "cpd.elg"),
    CP_CPWD_ELG("cp_cpwd_elg", "cpwd.elg"),
    CP_FWK_ELG("cp_fwk_elg", "fwk.elg"),
    CP_MQ_MNG_ELG("cp_mq_mng_elg", "mq_mng.elg"),
    CP_SPIKE_LOG("cp_spike_log", "spike_detective.log"),
    CP_SPIKE_CSV("cp_spike_csv", "spike_detective.csv");

    private final String sourceKey;
    private final String baseName;

    SourceFamily(String sourceKey, String baseName) {
        this.sourceKey = sourceKey;
        this.baseName = baseName;
    }

    /** The {@code source_key} stored in the cursor, chunk, gap and run tables. */
    public String sourceKey() {
        return sourceKey;
    }

    /** File name of the live file; the family glob is this name followed by anything ({@code <base>*}). */
    public String baseName() {
        return baseName;
    }

    /** The families in the fixed run order. */
    public static List<SourceFamily> runOrder() {
        return List.of(values());
    }

    /** True when {@code fileName} is covered by this family's C1 glob ({@code <base>*}). */
    public boolean covers(String fileName) {
        return fileName.startsWith(baseName);
    }

    /** The family whose glob covers {@code fileName}, or null. The eight globs have distinct prefixes. */
    public static SourceFamily ofFileName(String fileName) {
        for (SourceFamily family : values()) {
            if (family.covers(fileName)) {
                return family;
            }
        }
        return null;
    }
}
