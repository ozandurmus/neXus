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
}
