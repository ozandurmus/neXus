package com.securityexpert.nexus.ui2.sanitizer.format;

import java.util.ArrayList;
import java.util.List;

import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;
import com.securityexpert.nexus.ui2.sanitizer.core.TextReverter;
import com.securityexpert.nexus.ui2.sanitizer.core.TextSanitizer;

/**
 * What a format handler calls with a batch of values, in order; the answer has the same size and order. Batches
 * let the revert side ask the vault once per batch instead of once per token.
 */
public interface ValueRewriter {

    /** Formats buffer at most this many values (and about {@link #BATCH_CHARS} characters) before calling. */
    int BATCH_VALUES = 2048;
    int BATCH_CHARS = 4 * 1024 * 1024;

    List<String> rewrite(List<Value> values);

    static ValueRewriter sanitizing(TextSanitizer sanitizer) {
        return sanitizing(sanitizer, EstateDictionary.EMPTY);
    }

    /**
     * The text rules on every value (a value under a serial key: its serial token), then the known-identity pass of
     * {@code estate} on the result.
     */
    static ValueRewriter sanitizing(TextSanitizer sanitizer, EstateDictionary estate) {
        EstateDictionary.Replacer replacer = estate.replacer();
        Counts counts = sanitizer.counts();
        return values -> {
            List<String> out = new ArrayList<>(values.size());
            for (Value v : values) {
                String text = v.serialContext() ? sanitizer.serialValue(v.text()) : sanitizer.sanitize(v.text(), v.hostContext());
                out.add(replacer.replace(text, counts));
            }
            return out;
        };
    }

    static ValueRewriter reverting(TextReverter reverter) {
        return values -> {
            List<String> texts = new ArrayList<>(values.size());
            for (Value v : values) texts.add(v.text());
            return reverter.revert(texts);
        };
    }
}
