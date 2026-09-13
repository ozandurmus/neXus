package com.securityexpert.nexus.ui2.discovery.pan;

/**
 * T-1: "closed when the run ends" for a vendor key with no logout call means
 * the key is discarded from memory (zeroed) and the run reports that
 * disposal explicitly, rather than the result claiming a session was
 * "closed" the way a Check Point session is. Mirrors cp's {@code
 * SessionDisconnectOutcome} in spirit, not in shape — there is no
 * disconnect() call to fail here, only a memory-zeroing step that cannot
 * itself throw.
 */
public enum KeyDisposalOutcome {

    /** The key-generation call never produced a key; there was nothing to discard. */
    NOT_OBTAINED,

    /** A key was obtained and has been zeroed from memory at the end of the run. */
    DISCARDED
}
