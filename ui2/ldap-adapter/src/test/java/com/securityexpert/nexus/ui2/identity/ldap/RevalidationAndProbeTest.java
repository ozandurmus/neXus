package com.securityexpert.nexus.ui2.identity.ldap;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.DirectoryObservation;
import com.securityexpert.nexus.ui2.platform.Result;

/** Design §6 (revalidation through the service account) and §7 (test connection). */
class RevalidationAndProbeTest {
    private ActiveDirectoryFixture ad;
    private DirectoryProfile profile;
    private UnboundIdRevalidationAdapter revalidation;

    @BeforeEach
    void start() throws Exception {
        ad = ActiveDirectoryFixture.start(ActiveDirectoryFixture.Listener.PLAIN);
        profile = ad.profile(List.of("127.0.0.1"), false, DirectoryProfile.TrustMode.TRUST_ANY, null);
        revalidation = new UnboundIdRevalidationAdapter(profile, new DirectoryTrustPolicy(profile, () -> { }),
                () -> ad.servicePassword.clone());
    }

    @AfterEach
    void stop() {
        ad.close();
    }

    @Test
    void aMemberIsRevalidatedWithTheConfiguredGroupStrings() throws Exception {
        String dn = ad.addUser("rita", "pw", ActiveDirectoryFixture.USERS_GROUP);
        var result = revalidation.revalidatePrincipal(dn);
        assertTrue(result.isOk());
        DirectoryObservation observation = ((Result.Ok<DirectoryObservation>) result).value();
        assertEquals(Set.of(ActiveDirectoryFixture.USERS_GROUP), observation.groupReferences());
        assertEquals(dn, observation.principalReference());
    }

    @Test
    void removedFromBothGroupsIsAccessGroupLost() throws Exception {
        String dn = ad.addUser("sam", "pw", ActiveDirectoryFixture.ADMIN_GROUP);
        ad.removeMember(ActiveDirectoryFixture.ADMIN_GROUP, dn);
        assertEquals(UnboundIdRevalidationAdapter.ACCESS_GROUP_LOST, ((Result.Err<?>) revalidation.revalidatePrincipal(dn)).code());
    }

    @Test
    void aDisabledAccountIsAccessGroupLost() throws Exception {
        String dn = ad.addUser("tina", "pw", ActiveDirectoryFixture.ADMIN_GROUP);
        ad.disable(dn);
        assertEquals(UnboundIdRevalidationAdapter.ACCESS_GROUP_LOST, ((Result.Err<?>) revalidation.revalidatePrincipal(dn)).code());
    }

    @Test
    void aDeletedAccountIsAccessGroupLost() throws Exception {
        String dn = ad.addUser("uma", "pw", ActiveDirectoryFixture.ADMIN_GROUP);
        ad.server.delete(dn);
        assertEquals(UnboundIdRevalidationAdapter.ACCESS_GROUP_LOST, ((Result.Err<?>) revalidation.revalidatePrincipal(dn)).code());
    }

    @Test
    void anUnreachableDirectoryIsUnavailableNotLost() throws Exception {
        String dn = ad.addUser("vic", "pw", ActiveDirectoryFixture.ADMIN_GROUP);
        ad.close();
        assertEquals("directory_unavailable", ((Result.Err<?>) revalidation.revalidatePrincipal(dn)).code());
    }

    @Test
    void probeReportsOutcomesOnly() throws Exception {
        ad.addUser("walt", "pw", ActiveDirectoryFixture.ADMIN_GROUP);
        var report = DirectoryProbe.run(profile, ad.servicePassword.clone(), "walt");
        assertTrue(report.serviceBindOk());
        assertEquals(List.of(new DirectoryProbe.ServerOutcome("127.0.0.1", "ok")), report.servers());
        assertEquals(Boolean.TRUE, report.domainDnExists());
        assertEquals(Boolean.TRUE, report.adminGroupExists());
        assertEquals(Boolean.TRUE, report.usersGroupExists());
        assertEquals("found", report.userMatch());
        assertEquals(Boolean.TRUE, report.inAdminGroup());
        assertEquals(Boolean.FALSE, report.inUsersGroup());
        assertFalse(report.toString().contains("CN=walt"), "no DN in the report");
        assertEquals("not_found", DirectoryProbe.run(profile, ad.servicePassword.clone(), "nobody").userMatch());
        assertEquals("not_run", DirectoryProbe.run(profile, ad.servicePassword.clone(), "").userMatch());
    }

    @Test
    void probeReportsAWrongServicePasswordPerServer() {
        char[] wrong = "nope".toCharArray();
        var report = DirectoryProbe.run(profile, wrong, "x");
        assertFalse(report.serviceBindOk());
        assertEquals("service_bind_failed", report.servers().get(0).outcome());
        assertArrayEquals(new char[4], wrong, "the service password buffer is zeroed");
    }

    @Test
    void probeReportsAMissingGroup() throws Exception {
        DirectoryProfile missing = new DirectoryProfile(DirectoryProfile.DEFAULT_PROFILE_ID, List.of("127.0.0.1"),
                ad.port(), ActiveDirectoryFixture.BASE_DN, "CN=absent,OU=Groups,DC=example,DC=local",
                ActiveDirectoryFixture.USERS_GROUP, ActiveDirectoryFixture.SERVICE_DN, 3, false,
                DirectoryProfile.TrustMode.TRUST_ANY, null);
        var report = DirectoryProbe.run(missing, ad.servicePassword.clone(), "");
        assertEquals(Boolean.FALSE, report.adminGroupExists());
        assertEquals(Boolean.TRUE, report.usersGroupExists());
    }
}
