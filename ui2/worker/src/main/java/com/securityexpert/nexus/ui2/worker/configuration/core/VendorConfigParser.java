package com.securityexpert.nexus.ui2.worker.configuration.core;

import java.io.InputStream;

public interface VendorConfigParser {
    ConfigVendor vendor();
    ConfigFormat format();

    ConfigParseResult parse(ConfigParseContext context, InputStream content);
}
