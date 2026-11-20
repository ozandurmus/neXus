package com.securityexpert.nexus.ui2.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/** V260: the decision over the closed standing words, and the shape of a recordable grant. */
class FailoverActorAuthorityTest {
    @Test void roleKeptOrPreV260RecordPassesEveryOtherStandingRefusesWithItsParty() {
        for (String ok:List.of(FailoverActorAuthority.HELD,FailoverActorAuthority.NOT_RECORDED)) {
            assertNull(FailoverActorAuthority.decide(ok,null,false));
            assertNull(FailoverActorAuthority.decide(ok,ok,false));
        }
        for (String lost:List.of("IDENTITY_DISABLED","ROLE_NOT_HELD","IDENTITY_UNKNOWN","SOMETHING_NEW")) {
            assertEquals(FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED,FailoverActorAuthority.decide(lost,"HELD",false));
            assertEquals(FailoverActorAuthority.APPROVER_NOT_AUTHORIZED,FailoverActorAuthority.decide("HELD",lost,false));
            // The initiator is reported first when both lost their standing.
            assertEquals(FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED,FailoverActorAuthority.decide(lost,lost,true));
        }
        for (String stale:List.of(FailoverActorAuthority.DIRECTORY_STALE,"UNKNOWN")) {
            assertEquals(FailoverActorAuthority.DIRECTORY_AUTHORITY_STALE,FailoverActorAuthority.decide(stale,"HELD",false));
            assertEquals(FailoverActorAuthority.DIRECTORY_AUTHORITY_STALE,FailoverActorAuthority.decide("HELD",stale,false));
        }
        assertEquals(FailoverActorAuthority.APPROVER_IS_INITIATOR,FailoverActorAuthority.decide("HELD","HELD",true));
        assertEquals(FailoverActorAuthority.REVOKED_BINDING,FailoverActorAuthority.revokedDirectoryGrant().bindingId());
    }

    @Test void aGrantNamesAKnownAuthorityAnAdministratorRoleAndABindingUnlessRoot() {
        assertDoesNotThrow(() -> new FailoverActorAuthority.Grant("ui2_root",null,"role:security_admin"));
        assertDoesNotThrow(() -> new FailoverActorAuthority.Grant("ui2_ldap","binding-a","role:operation_admin"));
        assertThrows(IllegalArgumentException.class, () -> new FailoverActorAuthority.Grant("ui2_root","binding-a","role:security_admin"));
        assertThrows(IllegalArgumentException.class, () -> new FailoverActorAuthority.Grant("ui2_local",null,"role:security_admin"));
        assertThrows(IllegalArgumentException.class, () -> new FailoverActorAuthority.Grant("other","binding-a","role:security_admin"));
        assertThrows(IllegalArgumentException.class, () -> new FailoverActorAuthority.Grant("ui2_local","binding-a","role:operator"));
        assertFalse(new FailoverActorAuthority.Grant("ui2_local","binding-a","role:security_admin").toString().contains("binding-a"));
    }
}
