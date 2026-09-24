package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * One question an interactive command asks mid-run and the reply to send (a password from the credential store, a
 * "y" to a confirmation). {@code promptSuffix} is matched, case-insensitively, against the end of the output; each
 * answer is sent at most once. The reply is never logged.
 */
public record PromptAnswer(String promptSuffix, char[] reply) {

    @Override
    public String toString() {
        return "PromptAnswer[promptSuffix=" + promptSuffix + ", reply=<redacted>]";
    }
}
