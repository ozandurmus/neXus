package com.securityexpert.nexus.ui2.sanitizer.format;

/**
 * The input is not something the sanitizer can process safely. The message is a closed-vocabulary code plus a
 * reason that never quotes the input.
 */
public final class UnsupportedInputException extends java.io.IOException {

    private final String code;

    public UnsupportedInputException(String code, String reason) {
        super(code + ": " + reason);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
