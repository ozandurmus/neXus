package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.zonematrix.ServiceEntry;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Byte limit on every body of a zone matrix write or evaluate route (docs/design/ZONE_MATRIX_CONTRACT.md section 3.1
 * "a request size limit on every edit route. A request over a bound is refused, never truncated.", ZM-40).
 *
 * <p>It runs as a servlet filter because {@code @RequestBody} is read before the controller method runs. A declared
 * {@code Content-Length} over the limit is refused without reading a byte. A body with no declared length (chunked) is
 * read here, at most {@link #MAX_REQUEST_BYTES} + 1 bytes: the moment one byte passes the limit the request is refused
 * and the rest is never read; a body within the limit is replayed to the controller unchanged. Nothing of the body is
 * echoed in the refusal. The refusal is HTTP 413 with the shape the controller's own refusals use ({@code error},
 * {@code reason}). It only decides on size and
 * reveals nothing about the caller or the data. It is registered just after the Spring Security chain, so whatever that
 * chain refuses it still refuses first; the session and role gate ({@code GateChainInterceptor}) is an MVC interceptor and
 * runs after every servlet filter, so a caller with no session who sends an over-limit body gets the 413 (the limit is a
 * published constant) and one who sends an in-bound body gets the gate's 401/403 exactly as before. </p>
 *
 * <h2>Where {@link #MAX_REQUEST_BYTES} comes from</h2>
 * The limit is derived from the caps in {@link ZoneMatrixContent}, for the largest legal single request, with a margin
 * (see {@link #LARGEST_LEGAL_REQUEST_BYTES}). A character costs at most 6 bytes in a JSON body ({@code \}{@code uXXXX}).
 * <ul>
 *   <li>zone create/update: {@code MAX_BLOCKS_PER_ZONE} (10,000) block texts of at most 49 characters
 *       ({@code ZoneBlock} accepts no more), each with two quotes and a comma: 10,000 x 52 = 520,000; name 64 x 6 = 384 and
 *       description 500 x 6 = 3,000; about 523,400.</li>
 *   <li>cell set: {@code MAX_SERVICES_PER_CELL} (1,000) service entries; the longest entry is {@code app:pan:} (8) plus a
 *       128-character application name, 136 x 6 = 816 with two quotes and a comma 819; 1,000 x 819 = 819,000; rationale
 *       and regulation reference 2,000 x 6 = 12,000 each; about 843,000 with the pair, statement and severity fields.</li>
 *   <li>service set save: {@code MAX_ENTRIES_PER_SET} (1,000) entries of the same size, 819,000, plus a 64-character name.</li>
 *   <li>every other route carries a revision, ids or a digest and at most a 2,000-character rationale.</li>
 * </ul>
 * The largest is the cell request at about 843,000 bytes; a margin of about 24% on that sets the limit at 1 MiB
 * (1,048,576). {@code ZoneMatrixRequestSizeFilterTest} recomputes the figure from the constants and fails when a cap is
 * raised past the margin, so the limit is revisited with the cap.
 */
public final class ZoneMatrixRequestSizeFilter extends OncePerRequestFilter {
    /** Section 3.1: the byte limit of one zone matrix request body. One constant, derived above. */
    public static final int MAX_REQUEST_BYTES = 1_048_576;
    /** The refusal code. */
    public static final String CODE = "ZONE_MATRIX_REQUEST_TOO_LARGE";
    /** The URL patterns the registration covers: every zone matrix route and nothing else. */
    public static final String URL_PATTERN = "/api/v2/zone-matrix/*";

    private static final String PREFIX = "/api/v2/zone-matrix/";
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** The most bytes one character takes in a JSON body ({@code \}{@code uXXXX} escape). */
    private static final int MAX_JSON_BYTES_PER_CHAR = 6;
    /** The longest block text {@code ZoneBlock.parse} reads (it refuses a longer text before parsing it). */
    private static final int MAX_BLOCK_TEXT = 49;
    /** The longest service entry: {@code app:pan:} (8 characters) and an application name. */
    private static final int MAX_ENTRY_TEXT = 8 + ServiceEntry.MAX_APP_NAME;
    /** A JSON string element in an array: two quotes and a comma. */
    private static final int ARRAY_ELEMENT_OVERHEAD = 3;
    /** Field names, braces, the revision and the short fields of any one request, rounded up. */
    private static final int ENVELOPE_BYTES = 1_024;

    /**
     * The largest body any single edit request can legally carry, from the caps. See the class comment for the
     * arithmetic; the test pins {@code MAX_REQUEST_BYTES >= 1.2 x this}.
     */
    public static final int LARGEST_LEGAL_REQUEST_BYTES = Math.max(
            // zone create/update: blocks + name + description
            ZoneMatrixContent.MAX_BLOCKS_PER_ZONE * (MAX_BLOCK_TEXT + ARRAY_ELEMENT_OVERHEAD)
                    + (ZoneMatrixContent.MAX_NAME + ZoneMatrixContent.MAX_DESCRIPTION) * MAX_JSON_BYTES_PER_CHAR,
            // cell set / service set: entries + rationale + regulation reference (or the set name)
            Math.max(ZoneMatrixContent.MAX_SERVICES_PER_CELL, ZoneMatrixContent.MAX_ENTRIES_PER_SET)
                            * ((MAX_ENTRY_TEXT * MAX_JSON_BYTES_PER_CHAR) + ARRAY_ELEMENT_OVERHEAD)
                    + 2 * ZoneMatrixContent.MAX_TEXT * MAX_JSON_BYTES_PER_CHAR) + ENVELOPE_BYTES;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !BODY_METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_REQUEST_BYTES) {
            refuse(response);
            return;
        }
        if (declared >= 0) {
            // the container delivers no more than the declared length, so an in-bound declaration is an in-bound body
            chain.doFilter(request, response);
            return;
        }
        // no declared length: count while reading and stop the moment the limit is passed
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            refuse(response);
            return;
        }
        chain.doFilter(new ReplayedBody(request, body), response);
    }

    private static void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setHeader("Connection", "close");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(("{\"error\":\"" + CODE + "\",\"reason\":\"a zone matrix request body is at most "
                + MAX_REQUEST_BYTES + " bytes; it was refused, not cut\"}").getBytes(StandardCharsets.UTF_8));
    }

    /** The bytes already read, handed on as the request body. */
    private static final class ReplayedBody extends HttpServletRequestWrapper {
        private final byte[] body;
        private ServletInputStream stream;
        private BufferedReader reader;

        ReplayedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            if (reader != null) throw new IllegalStateException("getReader() was already called");
            if (stream == null) stream = new BytesInput(body);
            return stream;
        }

        @Override
        public BufferedReader getReader() {
            if (stream != null) throw new IllegalStateException("getInputStream() was already called");
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
            }
            return reader;
        }

        @Override
        public int getContentLength() { return body.length; }

        @Override
        public long getContentLengthLong() { return body.length; }
    }

    private static final class BytesInput extends ServletInputStream {
        private final ByteArrayInputStream in;
        BytesInput(byte[] body) { this.in = new ByteArrayInputStream(body); }
        @Override public int read() { return in.read(); }
        @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
        @Override public boolean isFinished() { return in.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("blocking reads only"); }
    }
}
