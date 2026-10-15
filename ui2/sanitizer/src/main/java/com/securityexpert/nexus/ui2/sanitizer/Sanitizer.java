package com.securityexpert.nexus.ui2.sanitizer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.core.TextReverter;
import com.securityexpert.nexus.ui2.sanitizer.core.TextSanitizer;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenResolver;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;
import com.securityexpert.nexus.ui2.sanitizer.format.CsvFormat;
import com.securityexpert.nexus.ui2.sanitizer.format.Detection;
import com.securityexpert.nexus.ui2.sanitizer.format.FileType;
import com.securityexpert.nexus.ui2.sanitizer.format.JsonFormat;
import com.securityexpert.nexus.ui2.sanitizer.format.TextEncoding;
import com.securityexpert.nexus.ui2.sanitizer.format.TextFormat;
import com.securityexpert.nexus.ui2.sanitizer.format.ValueRewriter;
import com.securityexpert.nexus.ui2.sanitizer.format.XlsxFormat;
import com.securityexpert.nexus.ui2.sanitizer.pcap.PcapSanitizer;

/**
 * The Data Sanitizer engine's entry point (docs/design/DATA_SANITIZER_CONTRACT.md): one engine, the input kinds of
 * §1, one keyed mapping. {@link #sanitize} decides the kind from the content, streams the input to the output and
 * offers every token it emits to a {@link TokenSink} (the mapping vault); {@link #revert} streams a file that came
 * back and replaces the tokens a {@link TokenResolver} knows. Neither keeps any input in memory beyond one batch,
 * and neither writes anywhere but the given output.
 */
public final class Sanitizer {

    /** What a run did: the detected kind, the units it walked (lines, records, values, packets) and the counts. */
    public record Result(FileType type, long units, Map<String, Long> counts) {
    }

    private final Mappers mappers;

    public Sanitizer(Mappers mappers) {
        this.mappers = Objects.requireNonNull(mappers, "mappers");
    }

    public Sanitizer(byte[] privacyKey, KnownNames knownNames) {
        this(new Mappers(SanitizerKey.of(privacyKey), knownNames));
    }

    public Result sanitize(Path input, Path output, TokenSink sink) throws IOException {
        Detection detection = Detection.of(input);
        Counts counts = new Counts();
        TextSanitizer text = new TextSanitizer(mappers, sink, counts);
        long units = run(detection, input, output, ValueRewriter.sanitizing(text), true, sink, null, counts, text::learn);
        return new Result(detection.type(), units, counts.asMap());
    }

    public Result revert(Path input, Path output, TokenResolver resolver) throws IOException {
        Detection detection = Detection.of(input);
        Counts counts = new Counts();
        TextReverter reverter = new TextReverter(resolver, counts);
        long units = run(detection, input, output, ValueRewriter.reverting(reverter), false, null, resolver, counts, name -> { });
        return new Result(detection.type(), units, counts.asMap());
    }

    /**
     * The text path on a stream (system-log export, DATA_SANITIZER_CONTRACT.md §3.3): UTF-8 lines in, sanitized
     * UTF-8 lines out, tokens to {@code sink}.
     */
    public Result sanitizeText(InputStream in, OutputStream out, TokenSink sink) throws IOException {
        Counts counts = new Counts();
        long lines = TextFormat.process(in, out, new TextEncoding(StandardCharsets.UTF_8, 0),
                ValueRewriter.sanitizing(new TextSanitizer(mappers, sink, counts)));
        out.flush();
        return new Result(FileType.TEXT, lines, counts.asMap());
    }

    private long run(Detection d, Path input, Path output, ValueRewriter rewriter, boolean sanitize, TokenSink sink,
            TokenResolver resolver, Counts counts, java.util.function.Consumer<String> learner) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(output), 1 << 16)) {
            if (d.type() == FileType.XLSX) {
                return XlsxFormat.process(input, out, rewriter, learner);
            }
            try (InputStream in = new BufferedInputStream(Files.newInputStream(input), 1 << 16)) {
                return switch (d.type()) {
                    case PCAP, PCAPNG -> sanitize ? PcapSanitizer.sanitize(in, out, mappers, sink, counts)
                            : PcapSanitizer.revert(in, out, resolver, counts);
                    case JSON -> JsonFormat.process(in, out, d.encoding(), rewriter);
                    case CSV -> CsvFormat.process(in, out, d.encoding(), d.delimiter(), rewriter);
                    default -> TextFormat.process(in, out, d.encoding(), rewriter);
                };
            }
        }
    }
}
