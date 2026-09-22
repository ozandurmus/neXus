package com.securityexpert.nexus.ui2.service.security;

/** Package-private route map, exposed to tests in other packages. */
public final class SecurityWebMvcConfigTestAccess {

    private SecurityWebMvcConfigTestAccess() {
    }

    public static String actionIdFor(String routePattern) {
        return SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(routePattern);
    }
}
