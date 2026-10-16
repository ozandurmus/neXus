package com.securityexpert.nexus.ui2.packetanalysis.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outputs of the path detectors (integrity, fragments, full proxies, TLS) that the reference
 * {@code _run_secondary_detectors} keeps in local variables and passes to the verdict and report assembly.
 */
public final class PathState {
    public List<Map<String, Object>> integrityFindings = new ArrayList<>();
    public List<Object> integrityNotes = new ArrayList<>();
    public List<Map<String, Object>> fragmentFindings = new ArrayList<>();
    public List<Db.Row> fragmentQuality = new ArrayList<>();
    /** {@code proxy_analysis.correlate} + {@code add_dwell} + the {@code reset_checks} leg report. */
    public Map<String, Object> proxies = new LinkedHashMap<>();
    public List<Map<String, Object>> proxyFindings = new ArrayList<>();
    public List<Map<String, Object>> tlsFindings = new ArrayList<>();
    public List<Object> tlsNotes = new ArrayList<>();
}
