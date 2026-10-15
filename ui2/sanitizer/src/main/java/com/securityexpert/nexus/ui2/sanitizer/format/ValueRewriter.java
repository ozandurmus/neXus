package com.securityexpert.nexus.ui2.sanitizer.format;

import java.util.ArrayList;
import java.util.List;

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
        return values -> {
            List<String> out = new ArrayList<>(values.size());
            for (Value v : values) out.add(sanitizer.sanitize(v.text(), v.hostContext()));
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
