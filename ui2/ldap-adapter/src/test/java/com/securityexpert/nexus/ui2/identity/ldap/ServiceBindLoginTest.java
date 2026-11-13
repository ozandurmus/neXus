package com.securityexpert.nexus.ui2.identity.ldap;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.PrincipalFingerprint;
import com.securityexpert.nexus.ui2.platform.Result;

/** Design LDAP_SERVICE_BIND_DESIGN_2026_11_11 §4 against a synthetic in-memory directory. */
class ServiceBindLoginTest {
    private ActiveDirectoryFixture ad;
    private DirectoryProfile profile;
    private DirectoryTrustPolicy trust;
    private UnboundIdOperatorBindAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        ad = ActiveDirectoryFixture.start(ActiveDirectoryFixture.Listener.PLAIN);
        profile = ad.profile(List.of("127.0.0.1"), false, DirectoryProfile.TrustMode.TRUST_ANY, null);
        trust = new DirectoryTrustPolicy(profile, () -> { });
        adapter = new UnboundIdOperatorBindAdapter(profile, trust, () -> ad.servicePassword.clone());
    }

    @AfterEach
    void stop() {
        ad.close();
    }

    private static String reason(Result<LdapOperatorBindPort.OperatorBindOutcome> result) {
        return ((Result.Err<?>) result).message();
    }

    private static String code(Result<LdapOperatorBindPort.OperatorBindOutcome> result) {
        return ((Result.Err<?>) result).code();
    }

    @Test
    void adminGroupMemberSignsInWithTheSearchedDn() throws Exception {
        String dn = ad.addUser("alice", "pw-alice-1", ActiveDirectoryFixture.ADMIN_GROUP);
        char[] password = "pw-alice-1".toCharArray();
        var result = adapter.bind("alice", password);
        assertTrue(result.isOk(), () -> reason(result));
        var outcome = ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) result).value();
        assertEquals(PrincipalFingerprint.of(dn), outcome.actorFingerprint());
        assertEquals(Set.of(ActiveDirectoryFixture.ADMIN_GROUP), outcome.groupReferences());
        assertEquals(dn, outcome.observation().principalReference());
        assertEquals(DirectoryProfile.DEFAULT_PROFILE_ID, outcome.observation().profileId());
        assertArrayEquals(new char[password.length], password, "the password buffer is zeroed");
    }

    @Test
    void displayNameIsTheSamAccountNameWhileTheIdentityStaysTheBindDn() throws Exception {
        // The entry's CN differs from its account name, as a real directory's usually does.
        String dn = "CN=Synthetic Person,OU=People,DC=example,DC=local";
        ad.server.add(new com.unboundid.ldap.sdk.Entry(dn,
                new com.unboundid.ldap.sdk.Attribute("objectClass", "top", "person", "organizationalPerson", "user"),
                new com.unboundid.ldap.sdk.Attribute("cn", "Synthetic Person"),
                new com.unboundid.ldap.sdk.Attribute("sAMAccountName", "sperson"),
                new com.unboundid.ldap.sdk.Attribute("objectCategory", "person"),
                new com.unboundid.ldap.sdk.Attribute("userAccountControl", "512"),
                new com.unboundid.ldap.sdk.Attribute("userPassword", "pw-sperson-1")));
        ad.addMember(ActiveDirectoryFixture.ADMIN_GROUP, dn);
        var outcome = ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) adapter.bind("sperson", "pw-sperson-1".toCharArray())).value();
        assertEquals("sperson", outcome.observation().displayName());
        assertNotEquals(dn, outcome.observation().displayName());
        // Identity is untouched: the fingerprint and the principal reference are still the matched entry's DN.
        assertEquals(PrincipalFingerprint.of(dn), outcome.actorFingerprint());
        assertEquals(dn, outcome.observation().principalReference());
    }

    @Test
    void usersGroupMemberGetsOnlyTheUsersGroup() throws Exception {
        ad.addUser("bob", "pw-bob-1", ActiveDirectoryFixture.USERS_GROUP);
        var outcome = ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) adapter.bind("bob", "pw-bob-1".toCharArray())).value();
        assertEquals(Set.of(ActiveDirectoryFixture.USERS_GROUP), outcome.groupReferences());
    }

    @Test
    void nestedMembershipCountsThroughInChain() throws Exception {
        String dn = ad.addUser("carol", "pw-carol-1");
        String team = ad.addGroup("firewall-team", dn);
        String inner = ad.addGroup("ops", team);
        ad.addMember(ActiveDirectoryFixture.ADMIN_GROUP, inner);
        var outcome = ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) adapter.bind("carol", "pw-carol-1".toCharArray())).value();
        assertEquals(Set.of(ActiveDirectoryFixture.ADMIN_GROUP), outcome.groupReferences());
    }

    @Test
    void wrongPasswordIsUserBindFailedEvenOutsideBothGroups() throws Exception {
        ad.addUser("dave", "pw-dave-1");
        var result = adapter.bind("dave", "wrong".toCharArray());
        assertEquals(LdapOperatorBindPort.FailureCodes.INVALID_CREDENTIALS, code(result));
        assertEquals("user_bind_failed", reason(result));
    }

    @Test
    void rightPasswordOutsideBothGroupsIsNotInGroup() throws Exception {
        ad.addUser("erin", "pw-erin-1");
        var result = adapter.bind("erin", "pw-erin-1".toCharArray());
        assertEquals(LdapOperatorBindPort.FailureCodes.INVALID_CREDENTIALS, code(result));
        assertEquals("not_in_group", reason(result));
    }

    @Test
    void unknownUserIsUserNotFound() {
        var result = adapter.bind("nobody", "x".toCharArray());
        assertEquals("user_not_found", reason(result));
    }

    @Test
    void twoEntriesWithOneNameAreAmbiguous() throws Exception {
        ad.addUser("frank", "pw-1", ActiveDirectoryFixture.ADMIN_GROUP);
        ad.server.add("dn: CN=frank-copy,OU=Groups,DC=example,DC=local", "objectClass: user", "objectCategory: person", "cn: frank-copy",
                "sAMAccountName: frank", "userPassword: pw-1");
        var result = adapter.bind("frank", "pw-1".toCharArray());
        assertEquals("user_ambiguous", reason(result));
    }

    @Test
    void filterMetacharactersAreEscapedAndMatchNothing() throws Exception {
        ad.addUser("grace", "pw-grace", ActiveDirectoryFixture.ADMIN_GROUP);
        for (String injected : List.of("*", "gr*", "grace)(sAMAccountName=*", "*)(objectClass=*", "grace\\2a", "\\", "grace\u0000")) {
            var result = adapter.bind(injected, "pw-grace".toCharArray());
            assertFalse(result.isOk(), injected);
            assertEquals("user_not_found", reason(result), injected);
        }
    }

    @Test
    void emptyPasswordIsRefusedBeforeAnyNetworkCall() throws Exception {
        ad.addUser("heidi", "pw-heidi", ActiveDirectoryFixture.ADMIN_GROUP);
        ad.close();
        var result = adapter.bind("heidi", new char[0]);
        assertEquals(LdapOperatorBindPort.FailureCodes.INVALID_CREDENTIALS, code(result));
        assertEquals("user_bind_failed", reason(result));
        assertEquals("user_bind_failed", reason(adapter.bind("  ", "x".toCharArray())));
        assertEquals("user_bind_failed", reason(adapter.bind("a".repeat(257), "x".toCharArray())));
    }

    @Test
    void wrongServicePasswordIsServiceBindFailedAndDirectoryUnavailable() throws Exception {
        ad.addUser("ivan", "pw-ivan", ActiveDirectoryFixture.ADMIN_GROUP);
        var wrong = new UnboundIdOperatorBindAdapter(profile, trust, "not-the-password"::toCharArray);
        var result = wrong.bind("ivan", "pw-ivan".toCharArray());
        assertEquals(LdapOperatorBindPort.FailureCodes.DIRECTORY_UNAVAILABLE, code(result));
        assertEquals("service_bind_failed", reason(result));
    }

    @Test
    void anUnreachableFirstServerFailsOverToTheNext() throws Exception {
        ad.addUser("judy", "pw-judy", ActiveDirectoryFixture.USERS_GROUP);
        int dead;
        try (var socket = new java.net.ServerSocket(0)) { dead = socket.getLocalPort(); }
        // Same port for every server in the model: the first server is a loopback address nothing listens on.
        DirectoryProfile twoServers = new DirectoryProfile(DirectoryProfile.DEFAULT_PROFILE_ID,
                List.of("127.0.0.2", "127.0.0.1"), ad.port(), ActiveDirectoryFixture.BASE_DN,
                ActiveDirectoryFixture.ADMIN_GROUP, ActiveDirectoryFixture.USERS_GROUP, ActiveDirectoryFixture.SERVICE_DN,
                2, false, DirectoryProfile.TrustMode.TRUST_ANY, null);
        assertTrue(dead > 0);
        var failover = new UnboundIdOperatorBindAdapter(twoServers, new DirectoryTrustPolicy(twoServers, () -> { }),
                () -> ad.servicePassword.clone());
        var result = failover.bind("judy", "pw-judy".toCharArray());
        assertTrue(result.isOk(), () -> reason(result));
    }

    @Test
    void noReachableServerIsConnectFailed() throws Exception {
        ad.close();
        var result = adapter.bind("anyone", "x".toCharArray());
        assertEquals(LdapOperatorBindPort.FailureCodes.DIRECTORY_UNAVAILABLE, code(result));
        assertTrue(Set.of("connect_failed", "timeout").contains(reason(result)), reason(result));
    }

    @Test
    void aRetiredTrustGenerationPublishesNothing() throws Exception {
        ad.addUser("kim", "pw-kim", ActiveDirectoryFixture.ADMIN_GROUP);
        var outcome = ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) adapter.bind("kim", "pw-kim".toCharArray())).value();
        trust.withdraw();
        assertFalse(outcome.observation().publication().ifCurrent(() -> fail("published after retirement")));
        assertEquals(LdapOperatorBindPort.FailureCodes.DIRECTORY_UNAVAILABLE, code(adapter.bind("kim", "pw-kim".toCharArray())));
    }

    @Test
    void reasonCodesNeverCarryTheUserNameOrDn() throws Exception {
        ad.addUser("leak-check-user", "pw-leak", ActiveDirectoryFixture.ADMIN_GROUP);
        for (var result : List.of(adapter.bind("leak-check-user", "bad".toCharArray()),
                adapter.bind("leak-check-missing", "bad".toCharArray()))) {
            String message = reason(result);
            assertFalse(message.contains("leak-check"), message);
            assertFalse(message.contains("DC="), message);
            assertTrue(DirectoryReason.fromCode(message) != DirectoryReason.DIRECTORY_ERROR, message);
        }
    }
}
