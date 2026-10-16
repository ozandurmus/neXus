package com.securityexpert.nexus.ui2.packetanalysis.ingest;

/** The reference {@code InterruptedError}: a cancelled ingest; committed frames stay and can be resumed. */
public final class IngestCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IngestCancelledException(String message) {
        super(message);
    }
}
