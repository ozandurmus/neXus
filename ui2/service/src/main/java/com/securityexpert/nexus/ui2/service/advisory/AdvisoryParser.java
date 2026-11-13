package com.securityexpert.nexus.ui2.service.advisory;

/**
 * Parses one fetched document of one source (contract sections 2 and 6). Never throws: a document it cannot read yields an
 * empty result with {@code rejected} counted; an advisory it can read only in part is returned as {@code NEEDS_REVIEW}.
 * The input is untrusted (contract rule 7): size-capped by the caller, parsed strictly, no external entities.
 */
public interface AdvisoryParser {

    /** The source key this parser serves. */
    String source();

    /** {@code url} is the address the document was read from (already checked against the source's host list). */
    AdvisoryModel.ParseResult parse(String url, byte[] document);

    /**
     * As {@link #parse(String, byte[])}, with read access to what earlier documents of the same source stored (Check Point:
     * the Jumbo statements a CVE record is joined with, amendment A-2026-11-13). The default ignores the context.
     */
    default AdvisoryModel.ParseResult parse(String url, byte[] document, ParseContext context) {
        return parse(url, document);
    }

    /** What the ingest lets a parser read; nothing that is not public advisory data. */
    interface ParseContext {
        ParseContext EMPTY = cveId -> java.util.List.of();

        /** The stored Jumbo statements that name this CVE id, every train. */
        java.util.List<AdvisoryModel.JumboStatement> jumboStatements(String cveId);
    }
}
