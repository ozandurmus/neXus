package com.securityexpert.nexus.ui2.packetanalysis.numeric;

/** A Python {@code int} (or {@code bool}) held in a {@code long}. */
public record PyInt(long value) implements PyNum {

    @Override
    public double toDouble() {
        // JLS 5.1.2: long -> double rounds to nearest, ties to even; identical to CPython's PyLong_AsDouble.
        return (double) value;
    }

    @Override
    public boolean isInt() {
        return true;
    }

    @Override
    public String repr() {
        return Long.toString(value);
    }
}
