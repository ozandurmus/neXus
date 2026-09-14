package com.securityexpert.nexus.ui2.jobs.transport;

/** {@link DeviceTransport#xmlApiCallStreaming} result shape -- mirrors {@link XmlApiResult}'s Completed/Failed split. */
public sealed interface XmlApiStreamOutcome<T> {

    record Completed<T>(int httpStatus, T handled) implements XmlApiStreamOutcome<T> {
    }

    record Failed<T>(String reason) implements XmlApiStreamOutcome<T> {
    }
}
