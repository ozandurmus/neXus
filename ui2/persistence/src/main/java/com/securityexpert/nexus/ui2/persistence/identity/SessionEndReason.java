package com.securityexpert.nexus.ui2.persistence.identity;

/**
 * {@code sessions.end_reason}'s closed vocabulary (C3 §3.1). No other
 * transition/reason exists (C3 §3.3).
 */
public enum SessionEndReason {
    LOGIN_ELSEWHERE("login_elsewhere"),
    IDLE_TIMEOUT("idle_timeout"),
    ABSOLUTE_LIFETIME("absolute_lifetime"),
    REVOKED_BY_ADMIN("revoked_by_admin"),
    ACCESS_GROUP_LOST("access_group_lost");

    private final String column;

    SessionEndReason(String column) {
        this.column = column;
    }

    public String column() {
        return column;
    }
}
