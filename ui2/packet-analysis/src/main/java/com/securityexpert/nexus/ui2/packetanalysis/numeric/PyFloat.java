package com.securityexpert.nexus.ui2.packetanalysis.numeric;

/**
 * A Python {@code float}. Equality is bit equality ({@link Double#equals}), so {@code -0.0} and {@code 0.0} differ
 * and NaN equals NaN; use {@link PyArith} for Python comparison semantics.
 */
public record PyFloat(double value) implements PyNum {

    @Override
    public double toDouble() {
        return value;
    }

    @Override
    public boolean isInt() {
        return false;
    }

    @Override
    public String repr() {
        return PyFloatRepr.repr(value);
    }
}
