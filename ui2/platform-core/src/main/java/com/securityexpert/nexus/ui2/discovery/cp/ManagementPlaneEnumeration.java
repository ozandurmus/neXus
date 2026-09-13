package com.securityexpert.nexus.ui2.discovery.cp;

/**
 * T-1/T-2: the transport port. One run-shaped operation performs the one
 * authenticated session, the domain enumeration, every per-domain object
 * query and the connection-table read (contract §3, §7.4), and returns
 * everything §6 needs. Declared here, in platform-core, so the domain core
 * and its tests never reference a transport type (dir1: platform-core has
 * no dependency on job-engine or worker) -- the adapter that implements this
 * against a real management-plane session lives in {@code worker}.
 */
public interface ManagementPlaneEnumeration {

    ManagementPlaneEnumerationResult run(ManagementPlaneEnumerationRequest request);
}
