package com.securityexpert.nexus.ui2.jobs.transport;

import java.io.IOException;
import java.io.InputStream;

/**
 * Consumes an {@code xml_api_call} response body as a stream (14G CG-5:
 * Palo Alto's {@code effective-running}, 11.8 MB measured, is never held
 * whole in memory). The transport guarantees {@code body} is closed when
 * {@link DeviceTransport#xmlApiCallStreaming} returns, whether or not this
 * handler consumed it fully.
 */
@FunctionalInterface
public interface XmlApiStreamHandler<T> {

    T handle(InputStream body) throws IOException;
}
