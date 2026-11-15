package com.securityexpert.nexus.ui2.persistence.identity;

/** Insert-only break-glass sign-in events (migration plan Step 3, V231); each insert is one audited row under its own action id. */
public interface LocalBreakGlassEventRepository {
    String LOGIN_SUCCEEDED_ACTION = "local_break_glass_login_succeeded";
    String REFUSED_ACTION = "local_non_break_glass_refused";

    /** A break-glass sign-in succeeded; {@code alert} marks the ones that raise the real-time alert. */
    void recordLoginSucceeded(String actorFingerprint, String kind, boolean alert);

    /** A correct password was refused because the mode only admits break-glass identities. */
    void recordRefused(String actorFingerprint);
}
