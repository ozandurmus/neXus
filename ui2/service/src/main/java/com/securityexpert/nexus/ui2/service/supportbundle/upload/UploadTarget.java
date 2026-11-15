package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import java.time.Duration;

/** One destination for one upload: where, as whom, under which remote file name. The password is passed separately. */
public record UploadTarget(UploadDestination destination, String host, int port, String user, String remoteName) {
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    /** A transfer that moves no byte for this long is given up. */
    public static final Duration STALL_TIMEOUT = Duration.ofSeconds(120);
}
