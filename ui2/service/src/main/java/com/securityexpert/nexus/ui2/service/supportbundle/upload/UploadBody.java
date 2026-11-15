package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import java.io.IOException;
import java.io.OutputStream;

/** What a transfer sends: the exact length (SCP and HTTPS need it up front) and the writer that streams it from the vault. */
public interface UploadBody {
    long length();

    /** Writes the whole archive; the caller owns and closes the target. */
    void writeTo(OutputStream out) throws IOException;
}
