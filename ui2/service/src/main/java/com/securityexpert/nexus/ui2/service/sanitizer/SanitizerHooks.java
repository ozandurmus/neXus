package com.securityexpert.nexus.ui2.service.sanitizer;

/**
 * The two places other features call the sanitizer (DATA_SANITIZER_CONTRACT.md §3.2/§3.3), each off by default:
 *
 * <ul>
 *   <li>{@code ui2.sanitizer.packet-analysis-anonymize-on-upload} -- when true, the Packet Analysis upload path calls
 *       {@link SanitizerService#sanitizeFile(java.nio.file.Path, java.nio.file.Path, String)} on the streamed capture
 *       before it is stored, and stores the sanitized file instead (the Packet Analysis shell is not on main yet; this
 *       flag and that call are its contract).</li>
 *   <li>{@code ui2.sanitizer.log-export} -- when true, the system-log export (not built yet) streams every exported
 *       file through {@link SanitizerService#sanitizeLogExport(java.io.InputStream, java.io.OutputStream, String)}.</li>
 * </ul>
 */
public record SanitizerHooks(boolean packetAnalysisOnUpload, boolean logExport) {
}
