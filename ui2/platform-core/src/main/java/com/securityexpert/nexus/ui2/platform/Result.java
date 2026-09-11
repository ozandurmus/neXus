package com.securityexpert.nexus.ui2.platform;

import java.util.function.Function;

/**
 * Shared result/error type. Ports return {@link Result} instead of
 * throwing for expected failure paths, so that adapters never have to
 * parse exception messages to recover a domain outcome.
 */
public sealed interface Result<T> {

    record Ok<T>(T value) implements Result<T> {
    }

    record Err<T>(String code, String message) implements Result<T> {
    }

    static <T> Result<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Result<T> err(String code, String message) {
        return new Err<>(code, message);
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }

    default <R> Result<R> map(Function<T, R> mapper) {
        if (this instanceof Ok<T> ok) {
            return Result.ok(mapper.apply(ok.value()));
        }
        Err<T> err = (Err<T>) this;
        return Result.err(err.code(), err.message());
    }
}
