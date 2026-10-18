package com.securityexpert.nexus.ui2.service.policy;

import java.util.Optional;

/** The one name a policy source carries on every Policy view: its device's own hostname. */
public final class PolicySources {
    /** The name every view shows for a source whose device has no hostname yet. */
    public static final String UNNAMED = "Management server";

    private PolicySources() {}

    public static String displayName(Optional<String> deviceHostname) {
        return deviceHostname.filter(name -> !name.isBlank()).orElse(UNNAMED);
    }
}
