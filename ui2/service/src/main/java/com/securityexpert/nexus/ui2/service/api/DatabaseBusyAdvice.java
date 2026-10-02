package com.securityexpert.nexus.ui2.service.api;

import java.sql.SQLTransientConnectionException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Spring also matches nested causes from jOOQ, JDBC and asynchronous sections. */
@RestControllerAdvice
public class DatabaseBusyAdvice {
    @ExceptionHandler(SQLTransientConnectionException.class)
    public ResponseEntity<Map<String, String>> databaseBusy(SQLTransientConnectionException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "DATABASE_BUSY", "message", "database busy"));
    }
}
