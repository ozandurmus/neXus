package com.securityexpert.nexus.ui2.worker.discovery.cp;

import com.securityexpert.nexus.ui2.discovery.cp.Address;

/**
 * §7.4: one raw row of one connection-table observation, already parsed
 * through {@code ManagementApiFieldBinding} (FB-2) -- the connection table
 * "carries many rows per address" (CS-6a), so a single read produces a list
 * of these, reduced later by {@link ConnectionTableReducer}.
 */
record ConnectionTableRow(Address address, int port, boolean established) {
}
