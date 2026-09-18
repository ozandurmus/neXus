package com.securityexpert.nexus.ui2.platform;

public final class RoleToken {
    public static final String VIEWER = "role:viewer";
    public static final String OPERATOR = "role:operator";
    public static final String ONBOARDING_ADMIN = "role:onboarding_admin";
    public static final String BACKUP_ADMIN = "role:backup_admin";
    public static final String COMPLIANCE_ADMIN = "role:compliance_admin";
    public static final String SECURITY_ADMIN = "role:security_admin";
    public static final String REPLAY_VIEWER = "role:replay_viewer";
    
    private RoleToken() {}

    public static String[] values() {
        return new String[] {
            VIEWER, OPERATOR, ONBOARDING_ADMIN, BACKUP_ADMIN, COMPLIANCE_ADMIN, SECURITY_ADMIN, REPLAY_VIEWER
        };
    }
}
