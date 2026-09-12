package com.securityexpert.nexus.ui2.service.device;

/** Wraps a {@link java.sql.SQLException} from {@link DeviceWorkspaceReader}; never carries a raw SQL string or DSN. */
public final class DeviceWorkspaceReadException extends RuntimeException {

    public DeviceWorkspaceReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
