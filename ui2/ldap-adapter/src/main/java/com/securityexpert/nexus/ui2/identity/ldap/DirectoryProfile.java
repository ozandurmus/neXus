package com.securityexpert.nexus.ui2.identity.ldap;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.unboundid.ldap.sdk.DN;

/**
 * Server-owned Active Directory settings (design LDAP_SERVICE_BIND_DESIGN_2026_11_11 §2). Never carries the
 * service account password: the adapters take it from a supplier at the moment of the bind.
 */
public record DirectoryProfile(String profileId, List<String> servers, int port, String domainDn,
        String adminGroupDn, String usersGroupDn, String bindDn, int timeoutSeconds, boolean ldaps,
        TrustMode trustMode, String trustPem) {

    /** The one profile id: the id the role-binding screen already uses. */
    public static final String DEFAULT_PROFILE_ID = "default";

    public enum TrustMode { TRUST_ANY, TRUST_PEM }

    /** One save-time rejection: the form field and a sentence the UI shows next to it. */
    public record Problem(String field, String message) { }

    public static final int MAX_SERVERS = 5;
    public static final int MAX_CERTIFICATES = 10;
    private static final Pattern HOST_NAME = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    private static final Pattern IPV4 = Pattern.compile(
            "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern UPN = Pattern.compile("[^@\\s=,]{1,256}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\r\\n]+)-----END CERTIFICATE-----");

    public DirectoryProfile {
        servers = servers == null ? List.of() : List.copyOf(servers);
        if (!problems(servers, port, domainDn, adminGroupDn, usersGroupDn, bindDn, timeoutSeconds, ldaps, trustMode,
                trustPem, Instant.now(), false).isEmpty() || profileId == null || profileId.isBlank()) {
            throw new LdapStartupException("settings_invalid");
        }
    }

    /**
     * Save-time validation. Every rejection names its field. {@code checkValidityWindow} is true at save time
     * (an expired certificate is refused with its dates) and false at load time (an anchor expiring later must
     * not make the stored settings unloadable; the TLS handshake then reports {@code tls_trust_failed}).
     */
    public static List<Problem> problems(List<String> servers, int port, String domainDn, String adminGroupDn,
            String usersGroupDn, String bindDn, int timeoutSeconds, boolean ldaps, TrustMode trustMode, String trustPem,
            Instant now, boolean checkValidityWindow) {
        List<Problem> problems = new ArrayList<>();
        if (servers == null || servers.isEmpty()) {
            problems.add(new Problem("servers", "Enter at least one LDAP server name or IP address."));
        } else if (servers.size() > MAX_SERVERS) {
            problems.add(new Problem("servers", "Enter at most " + MAX_SERVERS + " LDAP servers."));
        } else {
            var seen = new HashSet<String>();
            for (String server : servers) {
                if (!validServer(server)) {
                    problems.add(new Problem("servers", "\"" + printable(server) + "\" is not a host name or IP address "
                            + "(no scheme, port or spaces)."));
                } else if (!seen.add(server.toLowerCase(Locale.ROOT))) {
                    problems.add(new Problem("servers", "\"" + server + "\" is listed twice."));
                }
            }
        }
        if (port < 1 || port > 65535) problems.add(new Problem("port", "Port must be between 1 and 65535."));
        dn(problems, "domainDn", "Domain DN", domainDn);
        dn(problems, "adminGroupDn", "Administrators group DN", adminGroupDn);
        dn(problems, "usersGroupDn", "Users group DN", usersGroupDn);
        if (adminGroupDn != null && usersGroupDn != null && DN.isValidDN(adminGroupDn) && DN.isValidDN(usersGroupDn)
                && !adminGroupDn.isBlank() && sameDn(adminGroupDn, usersGroupDn)) {
            problems.add(new Problem("usersGroupDn", "The users group must differ from the administrators group."));
        }
        if (bindDn == null || bindDn.isBlank()) {
            problems.add(new Problem("bindDn", "Enter the LDAP bind DN of the service account."));
        } else if (!UPN.matcher(bindDn).matches() && !(DN.isValidDN(bindDn) && bindDn.contains("="))) {
            problems.add(new Problem("bindDn", "The bind DN must be a DN (CN=svc,OU=...,DC=...) or name@domain."));
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 60) {
            problems.add(new Problem("connectTimeoutSeconds", "Connection timeout must be between 1 and 60 seconds."));
        }
        if (ldaps) {
            if (trustMode == null) {
                problems.add(new Problem("trustMode", "Choose how the server certificate is trusted."));
            } else if (trustMode == TrustMode.TRUST_PEM) {
                String pemProblem = pemProblem(trustPem, now, checkValidityWindow);
                if (pemProblem != null) problems.add(new Problem("trustPem", pemProblem));
            }
        }
        return problems;
    }

    static boolean validServer(String server) {
        if (server == null || server.isEmpty() || server.length() > 253) return false;
        if (IPV4.matcher(server).matches()) return true;
        if (server.indexOf(':') >= 0) {
            if (!IPV6.matcher(server).matches()) return false;
            try {
                return java.net.InetAddress.getByName(server) instanceof java.net.Inet6Address;
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        }
        // A dotted all-numeric name that is not a valid IPv4 address (e.g. 10.0.0.300) is refused, not resolved.
        if (server.matches("[0-9.]+")) return false;
        return HOST_NAME.matcher(server).matches();
    }

    private static void dn(List<Problem> problems, String field, String label, String value) {
        if (value == null || value.isBlank()) {
            problems.add(new Problem(field, "Enter the " + label + "."));
        } else if (!DN.isValidDN(value) || !value.contains("=")) {
            problems.add(new Problem(field, label + " is not a valid DN (example: DC=example,DC=local)."));
        }
    }

    static boolean sameDn(String a, String b) {
        try {
            return new DN(a).equals(new DN(b));
        } catch (com.unboundid.ldap.sdk.LDAPException e) {
            return false;
        }
    }

    private static String printable(String value) {
        if (value == null) return "";
        String clipped = value.length() > 40 ? value.substring(0, 40) + "…" : value;
        return clipped.replaceAll("[\\p{Cntrl}\"]", "?");
    }

    /** Null when the PEM text is acceptable; otherwise the sentence the UI shows. */
    static String pemProblem(String pem, Instant now, boolean checkValidityWindow) {
        if (pem == null || pem.isBlank()) {
            return "Paste the server or CA certificate (PEM), or choose \"Trust any certificate\".";
        }
        try {
            List<X509Certificate> certificates = parsePem(pem);
            if (certificates.size() > MAX_CERTIFICATES) {
                return "Paste at most " + MAX_CERTIFICATES + " certificates.";
            }
            if (checkValidityWindow) {
                for (X509Certificate certificate : certificates) {
                    try {
                        certificate.checkValidity(java.util.Date.from(now));
                    } catch (CertificateExpiredException | CertificateNotYetValidException e) {
                        return "A pasted certificate is outside its validity period ("
                                + certificate.getNotBefore().toInstant() + " to " + certificate.getNotAfter().toInstant() + ").";
                    }
                }
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "The certificate text must contain only PEM CERTIFICATE blocks (no private key, no other text).";
        }
    }

    /** Strict PEM: only CERTIFICATE blocks separated by whitespace; each block must parse as exactly one X.509. */
    static List<X509Certificate> parsePem(String pem) {
        if (pem.length() > 512 * 1024) throw new IllegalArgumentException("pem_too_large");
        Matcher matcher = PEM_BLOCK.matcher(pem);
        List<X509Certificate> certificates = new ArrayList<>();
        int end = 0;
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            while (matcher.find()) {
                if (!pem.substring(end, matcher.start()).isBlank()) throw new IllegalArgumentException("pem_text");
                byte[] der = Base64.getMimeDecoder().decode(matcher.group(1));
                ByteArrayInputStream encoded = new ByteArrayInputStream(der);
                certificates.add((X509Certificate) factory.generateCertificate(encoded));
                if (encoded.available() != 0) throw new IllegalArgumentException("pem_trailing");
                end = matcher.end();
            }
        } catch (CertificateException | ClassCastException e) {
            throw new IllegalArgumentException("pem_parse");
        }
        if (certificates.isEmpty() || !pem.substring(end).isBlank()) throw new IllegalArgumentException("pem_empty");
        return certificates;
    }

    @Override
    public String toString() {
        return "DirectoryProfile[redacted]";
    }
}
