package com.securityexpert.nexus.ui2.packetanalysis.engine.report;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;

/**
 * A report key that the reference adds only on some paths ("absent" is not "null"): {@link #absent()} omits
 * the key, {@link #of} writes the value, which may itself be null. Fields carry
 * {@code @JsonInclude(value = CUSTOM, valueFilter = Maybe.AbsentFilter.class)}.
 */
@JsonSerialize(using = Maybe.Serializer.class)
public final class Maybe<T> {
    private static final Maybe<?> ABSENT = new Maybe<>(null, false);
    private final T value;
    private final boolean present;

    private Maybe(T value, boolean present) {
        this.value = value;
        this.present = present;
    }

    @SuppressWarnings("unchecked")
    public static <T> Maybe<T> absent() {
        return (Maybe<T>) ABSENT;
    }

    public static <T> Maybe<T> of(T value) {
        return new Maybe<>(value, true);
    }

    public boolean present() {
        return present;
    }

    public T value() {
        return value;
    }

    /** Jackson value filter: {@code equals} returning true excludes the property. */
    public static final class AbsentFilter {
        @Override
        public boolean equals(Object other) {
            return other == null || (other instanceof Maybe<?> m && !m.present);
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    @SuppressWarnings("rawtypes")
    static final class Serializer extends StdSerializer<Maybe> {
        Serializer() {
            super(Maybe.class);
        }

        @Override
        public void serialize(Maybe maybe, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (maybe.value == null) {
                gen.writeNull();
            } else {
                provider.defaultSerializeValue(maybe.value, gen);
            }
        }
    }
}
