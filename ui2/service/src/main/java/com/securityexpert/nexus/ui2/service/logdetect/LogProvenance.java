package com.securityexpert.nexus.ui2.service.logdetect;

/**
 * Where the meaning of a marker or severity value comes from (design sections 3.2 and 7.1). Only {@link #MEASURED} and
 * {@link #VENDOR_DOC} may support a CRITICAL finding; {@link #PROVISIONAL} is a starting value for measurement.
 */
public enum LogProvenance {
    PROVISIONAL, MEASURED, VENDOR_DOC;

    public boolean supportsCritical() {
        return this != PROVISIONAL;
    }
}
