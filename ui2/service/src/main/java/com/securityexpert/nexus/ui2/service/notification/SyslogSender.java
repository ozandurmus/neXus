package com.securityexpert.nexus.ui2.service.notification;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * RFC 5424 syslog over UDP or TCP (octet-counted framing, RFC 6587 section 3.4.1).
 * App-name {@code nexus}; the host is this pod's name. Messages carry only the
 * fields the caller built -- never device output or configuration text.
 */
public final class SyslogSender {

    public enum Severity {
        EMERGENCY, ALERT, CRITICAL, ERROR, WARNING, NOTICE, INFORMATIONAL, DEBUG
    }

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private SyslogSender() {
    }

    static String format(int facility, Severity severity, Instant at, String hostname, String msgId, String message) {
        int pri = facility * 8 + severity.ordinal();
        String host = hostname == null || hostname.isBlank() ? "-" : hostname.replaceAll("\\s", "");
        String id = msgId == null || msgId.isBlank() ? "-" : msgId.replaceAll("\\s", "_");
        String text = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
        return "<" + pri + ">1 " + at.truncatedTo(ChronoUnit.MILLIS) + " " + host + " nexus - " + id + " - " + text;
    }

    public static void send(NotificationSettings settings, Severity severity, String msgId, String message) throws IOException {
        if (settings.syslogHost() == null || settings.syslogHost().isBlank()) {
            throw new IOException("no syslog host configured");
        }
        String line = format(settings.syslogFacility(), severity, Instant.now(), localHostname(), msgId, message);
        byte[] payload = line.getBytes(StandardCharsets.UTF_8);
        if ("tcp".equals(settings.syslogProtocol())) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(settings.syslogHost(), settings.syslogPort()), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                OutputStream out = socket.getOutputStream();
                out.write((payload.length + " ").getBytes(StandardCharsets.US_ASCII));
                out.write(payload);
                out.flush();
            }
        } else {
            try (DatagramSocket socket = new DatagramSocket()) {
                InetAddress address = InetAddress.getByName(settings.syslogHost());
                socket.send(new DatagramPacket(payload, payload.length, address, settings.syslogPort()));
            }
        }
    }

    private static String localHostname() {
        String pod = System.getenv("HOSTNAME");
        return pod == null || pod.isBlank() ? "nexus" : pod;
    }
}
