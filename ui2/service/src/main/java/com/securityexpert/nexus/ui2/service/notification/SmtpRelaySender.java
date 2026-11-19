package com.securityexpert.nexus.ui2.service.notification;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A plain SMTP client for an internal relay: EHLO, STARTTLS when configured
 * (the certificate is verified against the image's trust store, which carries
 * the corporate CA), MAIL FROM / RCPT TO / DATA, multipart/alternative
 * (text/plain + text/html) UTF-8 quoted-printable bodies, and RFC 2047 headers.
 * No authentication in the first release -- an internal relay accepts by source
 * address; a relay that needs a login is refused with that reason.
 */
public final class SmtpRelaySender {

    private static final int TIMEOUT_MS = 15_000;
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private SmtpRelaySender() {
    }

    public static void send(NotificationSettings settings, String subject, String body) throws IOException {
        send(settings, settings.recipients(), subject, body);
    }

    public static void send(NotificationSettings settings, List<String> recipients, String subject, String body) throws IOException {
        send(settings, recipients, subject, body, NotificationMailTemplate.renderPlainFallback(subject, body));
    }

    public static void send(NotificationSettings settings, List<String> recipients, String subject, String textBody,
            String htmlBody) throws IOException {
        if (settings.smtpHost() == null || settings.smtpHost().isBlank() || settings.smtpFrom() == null || recipients.isEmpty()) {
            throw new IOException("SMTP relay host, from address or recipients are not configured");
        }
        String html = htmlBody == null || htmlBody.isBlank()
                ? NotificationMailTemplate.renderPlainFallback(subject, textBody)
                : htmlBody;
        String mime = formatMessage(settings, recipients, subject, textBody == null ? "" : textBody, html);
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
            StringBuilder data = new StringBuilder(mime.length() + 32);
            String[] lines = mime.split("\r\n", -1);
            int limit = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
            for (int i = 0; i < limit; i++) {
                String line = lines[i];
                data.append(line.startsWith(".") ? "." + line : line).append("\r\n");
            }
            data.append(".");
            session.command(data.toString(), 250);
            session.command("QUIT", 221);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing a finished session
            }
        }
    }

    static String formatMessage(NotificationSettings settings, List<String> recipients, String subject, String textBody,
            String htmlBody) {
        String boundary = "=_nexus_" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder message = new StringBuilder();
        message.append("From: ").append(encodeHeader(MailBrand.name())).append(" <").append(settings.smtpFrom().strip()).append(">\r\n");
        message.append("To: ").append(String.join(", ", recipients)).append("\r\n");
        message.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        message.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
        message.append("Message-ID: <").append(UUID.randomUUID()).append("@nexus>\r\n");
        message.append("MIME-Version: 1.0\r\n");
        message.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");
        message.append("--").append(boundary).append("\r\n");
        message.append("Content-Type: text/plain; charset=UTF-8\r\n");
        message.append("Content-Transfer-Encoding: quoted-printable\r\n\r\n");
        message.append(encodeQuotedPrintable(textBody)).append("\r\n");
        message.append("--").append(boundary).append("\r\n");
        message.append("Content-Type: text/html; charset=UTF-8\r\n");
        message.append("Content-Transfer-Encoding: quoted-printable\r\n\r\n");
        message.append(encodeQuotedPrintable(htmlBody)).append("\r\n");
        message.append("--").append(boundary).append("--\r\n");
        return message.toString();
    }

    static String encodeHeader(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\r\\n]", " ").strip();
        boolean ascii = clean.chars().allMatch(c -> c >= 32 && c < 127);
        return ascii ? clean : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(clean.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    static String decodeHeader(String encoded) {
        if (encoded == null) return "";
        String trimmed = encoded.strip();
        if (trimmed.startsWith("=?UTF-8?B?") && trimmed.endsWith("?=")) {
            String b64 = trimmed.substring("=?UTF-8?B?".length(), trimmed.length() - 2);
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        }
        return trimmed;
    }

    static String encodeQuotedPrintable(String text) {
        if (text == null || text.isEmpty()) return "";
        String normalized = text.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append("\r\n");
            byte[] bytes = lines[i].getBytes(StandardCharsets.UTF_8);
            int lineLen = 0;
            for (int j = 0; j < bytes.length; j++) {
                int b = bytes[j] & 0xFF;
                boolean lastByte = j == bytes.length - 1;
                boolean literal = (b >= 33 && b <= 126 && b != '=')
                        || ((b == ' ' || b == '\t') && !lastByte);
                String token = literal ? String.valueOf((char) b) : "=" + HEX.toHexDigits((byte) b);
                if (lineLen + token.length() > 75) {
                    out.append("=\r\n");
                    lineLen = 0;
                }
                out.append(token);
                lineLen += token.length();
            }
        }
        return out.toString();
    }

    static String decodeQuotedPrintable(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        String unfolded = encoded.replace("=\r\n", "").replace("=\n", "").replace("\r\n", "\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(unfolded.length());
        for (int i = 0; i < unfolded.length(); i++) {
            char c = unfolded.charAt(i);
            if (c == '=' && i + 2 < unfolded.length()) {
                int hi = Character.digit(unfolded.charAt(i + 1), 16);
                int lo = Character.digit(unfolded.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            bytes.write((byte) c);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    static String extractTextPart(String mimeMessage) {
        return extractPart(mimeMessage, "Content-Type: text/plain; charset=UTF-8");
    }

    static String extractHtmlPart(String mimeMessage) {
        return extractPart(mimeMessage, "Content-Type: text/html; charset=UTF-8");
    }

    private static String extractPart(String mimeMessage, String contentTypeHeader) {
        String normalized = mimeMessage.replace("\r\n", "\n");
        int headerStart = normalized.indexOf(contentTypeHeader);
        if (headerStart < 0) {
            throw new IllegalArgumentException("missing part: " + contentTypeHeader);
        }
        int bodyStart = normalized.indexOf("\n\n", headerStart);
        if (bodyStart < 0) {
            throw new IllegalArgumentException("missing part body: " + contentTypeHeader);
        }
        bodyStart += 2;
        int boundaryPos = normalized.indexOf("\n--=_nexus_", bodyStart);
        if (boundaryPos < 0) {
            throw new IllegalArgumentException("missing closing boundary for: " + contentTypeHeader);
        }
        return decodeQuotedPrintable(normalized.substring(bodyStart, boundaryPos));
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
