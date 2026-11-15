package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The approved upload targets (PO 2026-11-15/16): Cisco CXD (HTTPS PUT, SFTP or SCP), Fortinet (SFTP, port 2222), Palo Alto
 * (SCP) and a generic SFTP server whose host, port, user and password the administrator types per upload. The vendor hosts
 * and ports are fixed here and never come from the browser. Check Point has no destination: it stays a manual upload.
 */
public enum UploadDestination {
    CISCO_CXD_HTTPS("cisco_cxd_https", "cxd.cisco.com", 443, Method.HTTPS_PUT, "cisco_asa", "Cisco CXD (HTTPS)"),
    CISCO_CXD_SFTP("cisco_cxd_sftp", "cxd.cisco.com", 22, Method.SFTP, "cisco_asa", "Cisco CXD (SFTP)"),
    CISCO_CXD_SCP("cisco_cxd_scp", "cxd.cisco.com", 22, Method.SCP, "cisco_asa", "Cisco CXD (SCP)"),
    FORTINET_SFTP("fortinet_sftp", "support.fortinet.com", 2222, Method.SFTP, "fortinet", "Fortinet support (SFTP)"),
    PALOALTO_SCP("paloalto_scp", "tacupload.paloaltonetworks.com", 22, Method.SCP, "palo_alto", "Palo Alto TAC upload (SCP)"),
    GENERIC_SFTP("generic_sftp", null, 22, Method.SFTP, null, "Generic SFTP server");

    public enum Method { HTTPS_PUT, SFTP, SCP }

    private static final Pattern HOSTNAME = Pattern.compile(
            "(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})(\\.(25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final String id; private final String host; private final int port; private final Method method;
    private final String vendor; private final String label;

    UploadDestination(String id, String host, int port, Method method, String vendor, String label) {
        this.id = id; this.host = host; this.port = port; this.method = method; this.vendor = vendor; this.label = label;
    }

    public String id() { return id; }
    /** Null for the generic server (the administrator types it). */
    public String fixedHost() { return host; }
    public int defaultPort() { return port; }
    public Method method() { return method; }
    /** The vendor whose bundles may go here; null for the generic server. */
    public String vendor() { return vendor; }
    public String label() { return label; }
    public boolean generic() { return host == null; }

    public static Optional<UploadDestination> fromId(String id) {
        for (UploadDestination d : values()) if (d.id.equals(id)) return Optional.of(d);
        return Optional.empty();
    }

    /** A hostname (letters, digits, dots, hyphens) or an IP literal; nothing else, so no scheme, path, user info or space. */
    public static boolean validHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) return false;
        if (IPV4.matcher(host).matches()) return true;
        if (host.indexOf(':') >= 0) {
            if (!IPV6.matcher(host).matches()) return false;
            try {
                return java.net.InetAddress.getByName(host) != null; // a literal only: the pattern admits no name, so no lookup
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        }
        return HOSTNAME.matcher(host).matches();
    }

    public static boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }
}
