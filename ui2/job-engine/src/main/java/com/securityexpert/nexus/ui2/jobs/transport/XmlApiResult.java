package com.securityexpert.nexus.ui2.jobs.transport;

/** PAN XML API result shape -- declared, not implemented at this movement. */
public sealed interface XmlApiResult {

    record Completed(int httpStatus, String body) implements XmlApiResult {
    }

    record Failed(String reason) implements XmlApiResult {
    }
}
