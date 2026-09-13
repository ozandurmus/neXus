package com.securityexpert.nexus.ui2.discovery.pan;

/**
 * T-1/T-2: the transport port. One run-shaped operation performs the one
 * authorized session (the vendor's key-generation call) and the one
 * authorized managed-device enumeration, and returns everything §6-§9 need.
 * Declared here, in platform-core, so the domain core and its tests never
 * reference a transport type (dir1: platform-core has no dependency on
 * job-engine or worker) -- the adapter that implements this against a real
 * Panorama session lives in {@code worker}. Mirrors cp's {@code
 * ManagementPlaneEnumeration} in shape.
 */
public interface PanoramaEnumeration {

    PanoramaEnumerationResult run(PanoramaEnumerationRequest request);
}
