package com.securityexpert.nexus.ui2.discovery.pan;

/**
 * T-5/SB-12/SB-16: what a run needs and nothing secret -- an opaque {@code
 * credentialRef} and {@code trustRuleRef}, and Panorama's own address. No
 * {@code target=} parameter is ever accepted here (T-5): this request names
 * only the one host discovery ever dials (T-4).
 */
public record PanoramaEnumerationRequest(
        String panoramaHost,
        int panoramaPort,
        String credentialRef,
        String trustRuleRef) {
}
