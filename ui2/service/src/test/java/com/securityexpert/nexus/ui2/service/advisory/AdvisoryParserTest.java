package com.securityexpert.nexus.ui2.service.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseResult;

/**
 * The parser contract (AdvisoryParser): a parser never throws, a document it cannot read yields an empty result with
 * {@code rejected} counted, and the default context-taking entry point ignores the context. Synthetic ids only.
 */
class AdvisoryParserTest {

    private static final String RECORD = "https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2099/00xxx/CVE-2099-0001.json";

    @Test
    void theDefaultContextEntryPointDelegatesAndIgnoresTheContext() {
        List<String> seenUrls = new java.util.ArrayList<>();
        AdvisoryParser parser = new AdvisoryParser() {
            @Override
            public String source() {
                return "test-source";
            }

            @Override
            public ParseResult parse(String url, byte[] document) {
                seenUrls.add(url);
                return new ParseResult(List.of(), List.of(), 7);
            }
        };
        AdvisoryParser.ParseContext context = cveId -> {
            throw new AssertionError("the default entry point must not read the context");
        };
        ParseResult result = parser.parse(RECORD, new byte[0], context);
        assertEquals(List.of(RECORD), seenUrls);
        assertEquals(7, result.rejected());
    }

    @Test
    void theEmptyContextReadsNothingForAnyIdentifier() {
        assertTrue(AdvisoryParser.ParseContext.EMPTY.jumboStatements("CVE-2099-0001").isEmpty());
        assertTrue(AdvisoryParser.ParseContext.EMPTY.jumboStatements("").isEmpty());
    }

    @Test
    void aNullContextIsTreatedAsEmptyAndNeverThrows() {
        byte[] document = "{}".getBytes(StandardCharsets.UTF_8);
        ParseResult result = new CheckPointAdvisoryParser().parse(RECORD, document, null);
        assertTrue(result.advisories().isEmpty());
        assertEquals(1, result.rejected());
    }

    @Test
    void aNullUrlOrDocumentNeverThrowsAndYieldsNoAdvisory() {
        AdvisoryParser parser = new CheckPointAdvisoryParser();
        ParseResult noUrl = parser.parse(null, "{}".getBytes(StandardCharsets.UTF_8));
        ParseResult noDocument = parser.parse(RECORD, null);
        assertTrue(noUrl.advisories().isEmpty());
        assertEquals(1, noUrl.rejected());
        assertTrue(noDocument.advisories().isEmpty());
        assertEquals(1, noDocument.rejected());
    }

    @Test
    void aDocumentFromAnUnknownSourceHostIsRejectedNotParsed() {
        ParseResult result = new CheckPointAdvisoryParser().parse("https://example.invalid/CVE-2099-0001.json",
                "{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(result.advisories().isEmpty());
        assertEquals(1, result.rejected());
    }
}
