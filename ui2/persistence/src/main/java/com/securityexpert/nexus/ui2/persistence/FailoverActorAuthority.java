package com.securityexpert.nexus.ui2.persistence;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Who admitted a failover actor, and the recheck of that admission when the work starts (V260; backlog
 * {@code failover_approver_role_recheck_at_worker}).
 *
 * <p>The role decision stays the service's ({@code RbacEvaluator}): at a request or an approval the service records which
 * authority admitted the actor and which role binding it matched ({@link #record}), and refreshes a directory actor's
 * group membership whenever it creates or starts a failover job. Before any device contact the worker's admission asks the
 * database, through the SECURITY DEFINER function {@code ui2_failover_actor_standing}, whether that binding is still
 * active and the identity behind the actor still enabled ({@link #refusal}), and treats a directory authority row not
 * refreshed within {@link #DIRECTORY_MAX_AGE} as {@code UNKNOWN} ({@link #DIRECTORY_AUTHORITY_STALE}). No identity value,
 * no role binding row and no group reference ever reaches this class; it sees one closed word per actor.</p>
 *
 * <p>A record made before V260 carries no authority row: its actor is then checked for identity only (an existing local
 * identity must still be enabled), see {@link #NOT_RECORDED}.</p>
 */
public final class FailoverActorAuthority {

    /** The initiator no longer holds the role that allowed the request, or is disabled / gone. */
    public static final String INITIATOR_NOT_AUTHORIZED = "INITIATOR_NOT_AUTHORIZED_AT_START";
    /** The approver no longer holds the approving role, or is disabled / gone. */
    public static final String APPROVER_NOT_AUTHORIZED = "APPROVER_NOT_AUTHORIZED_AT_START";
    /** A four-eyes record whose initiator and approver are (now resolve to) the same person. */
    public static final String APPROVER_IS_INITIATOR = "APPROVER_IS_INITIATOR_AT_START";
    /** A directory actor's group membership was not refreshed within {@link #DIRECTORY_MAX_AGE} of start. */
    public static final String DIRECTORY_AUTHORITY_STALE = "DIRECTORY_AUTHORITY_STALE_AT_START";
    /** The directory could not be reached when the service refreshed a directory actor's group membership at start. */
    public static final String DIRECTORY_UNREACHABLE = "DIRECTORY_UNREACHABLE_AT_START";

    /** Maximum age of a recorded directory-authority refresh when the worker rechecks standing at start / first write. */
    public static final Duration DIRECTORY_MAX_AGE = Duration.ofMinutes(5);
    /** Standing word when a directory authority record is older than {@link #DIRECTORY_MAX_AGE} (treated as UNKNOWN). */
    public static final String DIRECTORY_STALE = "DIRECTORY_STALE";
    /** Binding marker recorded when a directory refresh at start finds the actor no longer holds an admin role. */
    public static final String REVOKED_BINDING = "revoked";

    public static final String KIND_APPROVAL = "failover_approval";
    public static final String KIND_RESUME = "failover_resume";
    public static final String KIND_CONFIG_SYNC = "pan_config_sync";
    public static final String INITIATOR = "INITIATOR";
    public static final String APPROVER = "APPROVER";

    public static final String HELD = "HELD";
    /** No authority row (a pre-V260 record): the identity was checked, the binding could not be. */
    public static final String NOT_RECORDED = "NOT_RECORDED";
    private static final Set<String> STANDING_OK = Set.of(HELD, NOT_RECORDED);
    private static final Set<String> STANDING_STALE = Set.of(DIRECTORY_STALE, "UNKNOWN");

    public static final String LOCAL = "ui2_local";
    public static final String LDAP = "ui2_ldap";
    public static final String ROOT = "ui2_root";
    private static final Set<String> AUTHORITIES = Set.of(LOCAL, LDAP, ROOT);
    private static final Set<String> ROLES = Set.of("role:security_admin", "role:operation_admin");

    /**
     * The authority that admitted one actor: {@code authority} is {@code ui2_local}, {@code ui2_ldap} or {@code ui2_root};
     * {@code bindingId} is the matched role binding (null only for the root authority); {@code role} is the role token
     * the decision was made for.
     */
    public record Grant(String authority, String bindingId, String role) {
        public Grant {
            if (!AUTHORITIES.contains(authority)) throw new IllegalArgumentException("INVALID_AUTHORITY");
            if (!ROLES.contains(role)) throw new IllegalArgumentException("INVALID_ROLE");
            if (ROOT.equals(authority) != (bindingId == null)) throw new IllegalArgumentException("INVALID_BINDING");
        }
        @Override public String toString() { return "Grant[" + authority + "," + role + "]"; }
    }

    /** Marker grant recorded when a directory refresh at start finds the actor no longer holds an administrator role. */
    public static Grant revokedDirectoryGrant() {
        return new Grant(LDAP, REVOKED_BINDING, "role:operation_admin");
    }

    private FailoverActorAuthority() {
    }

    /** Record one party's grant in the caller's transaction; a null grant records nothing. Insert-only (V260 guard). */
    static void record(DSLContext dsl, String kind, String recordId, String party, String actor, Grant grant) {
        if (grant == null) return;
        Objects.requireNonNull(actor, "actor");
        dsl.execute("insert into failover_actor_authority(authority_id,record_kind,record_id,party,actor,authority,"
            + "binding_id,role_name) values({0},{1},{2},{3},{4},{5},{6},{7})",
            UUID.randomUUID().toString(), kind, recordId, party, actor, grant.authority(), grant.bindingId(), grant.role());
    }

    /** The current standing of one party: one closed word from {@code ui2_failover_actor_standing} (or {@link #DIRECTORY_STALE}). */
    static String standing(DSLContext dsl, String kind, String recordId, String party, String actor) {
        Record row = dsl.fetchOne("select authority,binding_id from failover_actor_authority where record_kind={0} "
            + "and record_id={1} and party={2} and actor={3} order by recorded_at desc,authority_id desc limit 1",
            kind, recordId, party, actor);
        // A row recorded for another actor than the record names is not this actor's grant.
        if (row == null && !dsl.fetch("select 1 from failover_actor_authority where record_kind={0} and record_id={1} "
                + "and party={2}", kind, recordId, party).isEmpty()) return "ROLE_NOT_HELD";
        String authority = row == null ? null : row.get("authority", String.class);
        String bindingId = row == null ? null : row.get("binding_id", String.class);
        String standing = dsl.fetchOne("select ui2_failover_actor_standing({0},{1},{2}) as standing", actor,
            authority, bindingId).get("standing", String.class);
        if (HELD.equals(standing) && LDAP.equals(authority)) {
            Record fresh = dsl.fetchOne("select recorded_at>=clock_timestamp()-make_interval(secs => {4}) as fresh "
                + "from failover_actor_authority where record_kind={0} and record_id={1} and party={2} and actor={3} "
                + "order by recorded_at desc,authority_id desc limit 1",
                kind, recordId, party, actor, (double) DIRECTORY_MAX_AGE.toSeconds());
            if (fresh == null || !Boolean.TRUE.equals(fresh.get("fresh", Boolean.class))) return DIRECTORY_STALE;
        }
        return standing;
    }

    /**
     * The recheck at the start of work, in the caller's transaction: (a) the initiator still holds the role that allowed
     * the request and is enabled, (b) the approver still holds the approving role and is enabled, (c) a directory
     * actor's membership was refreshed within {@link #DIRECTORY_MAX_AGE}, (d) under four eyes the two are still different
     * people. Null when every check holds, otherwise the refusal code. {@code approver} null: nothing to check for (b)
     * and (d) (a config sync, or a request that has no approver yet).
     */
    static String refusal(DSLContext dsl, String kind, String recordId, String initiator, String approver,
            boolean fourEyes) {
        return decide(standing(dsl, kind, recordId, INITIATOR, initiator),
            approver == null ? null : standing(dsl, kind, recordId, APPROVER, approver),
            fourEyes && approver != null && Boolean.TRUE.equals(dsl.fetchOne(
                "select ui2_failover_same_person({0},{1}) as same", initiator, approver).get("same", Boolean.class)));
    }

    /** The pure decision over the standings (null standing: no approver to check). */
    public static String decide(String initiatorStanding, String approverStanding, boolean fourEyesSamePerson) {
        if (STANDING_STALE.contains(initiatorStanding)) return DIRECTORY_AUTHORITY_STALE;
        if (!STANDING_OK.contains(initiatorStanding)) return INITIATOR_NOT_AUTHORIZED;
        if (approverStanding != null && STANDING_STALE.contains(approverStanding)) return DIRECTORY_AUTHORITY_STALE;
        if (approverStanding != null && !STANDING_OK.contains(approverStanding)) return APPROVER_NOT_AUTHORIZED;
        if (fourEyesSamePerson) return APPROVER_IS_INITIATOR;
        return null;
    }
}
