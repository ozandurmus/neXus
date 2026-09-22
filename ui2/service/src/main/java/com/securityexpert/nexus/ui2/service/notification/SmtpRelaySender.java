package com.securityexpert.nexus.ui2.service.notification;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A plain SMTP client for an internal relay: EHLO, STARTTLS when configured
 * (the certificate is verified against the image's trust store, which carries
 * the corporate CA), MAIL FROM / RCPT TO / DATA, plain-text UTF-8 body. No
 * authentication in the first release -- an internal relay accepts by source
 * address; a relay that needs a login is refused with that reason.
 */
public final class SmtpRelaySender {

    private static final int TIMEOUT_MS = 15_000;

    private SmtpRelaySender() {
    }

    public static void send(NotificationSettings settings, String subject, String body) throws IOException {
        List<String> recipients = settings.recipients();
        if (settings.smtpHost() == null || settings.smtpHost().isBlank() || settings.smtpFrom() == null || recipients.isEmpty()) {
            throw new IOException("SMTP relay host, from address or recipients are not configured");
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(settings.smtpHost(), settings.smtpPort()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            Session session = new Session(socket);
            session.expect(220);
            session.command("EHLO nexus", 250);
            if (settings.smtpStarttls()) {
                session.command("STARTTLS", 220);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, settings.smtpHost(), settings.smtpPort(), true);
                javax.net.ssl.SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                socket = tls;
                session = new Session(tls);
                session.command("EHLO nexus", 250);
            }
            session.command("MAIL FROM:<" + settings.smtpFrom().strip() + ">", 250);
            for (String to : recipients) {
                session.command("RCPT TO:<" + to + ">", 250, 251);
            }
            session.command("DATA", 354);
            StringBuilder message = new StringBuilder();
            message.append("From: neXus <").append(settings.smtpFrom().strip()).append(">\r\n");
            message.append("To: ").append(String.join(", ", recipients)).append("\r\n");
            message.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
            message.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
            message.append("Message-ID: <").append(UUID.randomUUID()).append("@nexus>\r\n");
            message.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n");
            for (String line : body.replace("\r\n", "\n").split("\n", -1)) {
                message.append(line.startsWith(".") ? "." + line : line).append("\r\n");
            }
            message.append(".");
            session.command(message.toString(), 250);
            session.command("QUIT", 221);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing a finished session
            }
        }
    }

    private static String encodeHeader(String value) {
        boolean ascii = value.chars().allMatch(c -> c < 128);
        return ascii ? value : "=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static final class Session {
        private final BufferedReader in;
        private final OutputStream out;

        Session(Socket socket) throws IOException {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = socket.getOutputStream();
        }

        void command(String line, int... accepted) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            expect(accepted);
        }

        void expect(int... accepted) throws IOException {
            String line;
            String last;
            do {
                line = in.readLine();
                if (line == null) {
                    throw new IOException("the relay closed the connection");
                }
                last = line;
            } while (line.length() > 3 && line.charAt(3) == '-');
            int code;
            try {
                code = Integer.parseInt(last.substring(0, 3));
            } catch (RuntimeException e) {
                throw new IOException("unexpected relay reply");
            }
            for (int ok : accepted) {
                if (code == ok) {
                    return;
                }
            }
            if (code == 530 || code == 535) {
                throw new IOException("the relay requires authentication, which this release does not send (" + code + ")");
            }
            throw new IOException("the relay answered " + last.replaceAll("[\\r\\n]", " ").strip());
        }
    }
}
