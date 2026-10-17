package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.Objects;

/**
 * One data line of the C1 stat output ({@code %n %i %s %Y}): expanded absolute path, inode, size in bytes and mtime
 * in device epoch seconds. The path is the device's own printed path; it is kept in memory for the run and the
 * executor stores only keyed hashes of it (contract section 5.2).
 */
public record FileStat(String path, long inode, long size, long mtime) {

    public FileStat {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must be absolute");
        }
        if (inode < 0 || size < 0 || mtime < 0) {
            throw new IllegalArgumentException("inode, size and mtime must be non-negative");
        }
    }

    public String baseName() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public String directory() {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }
}
