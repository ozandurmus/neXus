package com.securityexpert.nexus.ui2.packetanalysis.numeric;

/**
 * A Python number as the frozen PacketBreaker engine sees it: an {@code int} or a {@code float}.
 *
 * <p>The distinction is load-bearing for parity: {@code statistics.median([1, 3])} is the float {@code 2.0} but
 * {@code median([1, 2, 3])} is the int {@code 2}, and {@code json.dumps} renders them as {@code 2.0} and {@code 2}.
 * Python ints are unbounded; this model holds them in a {@code long} and throws {@link ArithmeticException} where
 * CPython would have produced a larger int (never observed in PacketBreaker data, see NUMERIC_INVENTORY.md).
 * {@code bool} is an int subclass in Python and maps to {@link PyInt} 0/1.
 */
public sealed interface PyNum permits PyInt, PyFloat {

    static PyInt of(long value) {
        return new PyInt(value);
    }

    static PyFloat of(double value) {
        return new PyFloat(value);
    }

    /** {@code float(x)}: exact for floats, correctly rounded (half-even) for ints. */
    double toDouble();

    boolean isInt();

    /** {@code repr(x)}, which is also {@code str(x)} and the {@code json.dumps} text for finite values. */
    String repr();
}
