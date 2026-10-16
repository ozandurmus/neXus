package com.securityexpert.nexus.ui2.packetanalysis.topology;

import java.util.List;

/** A reference tuple: dumps to JSON as a list, but its {@code repr} uses parentheses. */
public record PyTuple(List<Object> items) {

    public PyTuple {
        items = List.copyOf(items);
    }

    public static PyTuple of(Object... items) {
        return new PyTuple(java.util.Arrays.asList(items));
    }
}
