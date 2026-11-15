package com.securityexpert.nexus.ui2.service.supportbundle.upload;

/** A transfer failure with a closed code ({@code [A-Z0-9_]+}) and a short detail that never carries a credential. */
public final class UploadException extends Exception {
    private final String code;
    private final boolean retryable;

    public UploadException(String code, String detail, boolean retryable) {
        super(detail, null, false, false);
        this.code = code; this.retryable = retryable;
    }

    public String code() { return code; }
    /** True for a failure of the connection itself; the service retries it once, and only when no byte was sent. */
    public boolean retryable() { return retryable; }
}
