# UI2 Palo Alto transport: no credential ever appears in a URL, query string, log line or exception (successor to pan_auth_transport_convergence)

status: planned · target: PAN_DISCOVERY_CONTRACT.md T-1/T-5; the PAN discovery transport movement carries this as an invariant with a test

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): UI2 Palo Alto transport: no credential ever appears in a URL, query string, log line or exception (successor to pan_auth_transport_convergence)

Target (full): PAN_DISCOVERY_CONTRACT.md T-1/T-5; the PAN discovery transport movement carries this as an invariant with a test

2026-11-14 backlog truth: DONE. Evidence: PanXmlApiTransport: fixed URL base+API_PATH, key in X-PAN-KEY header, safeBody scrubs; JobTranscriptTest, PanoramaEnumerationAdapterTest; PR 442. Key never placed in URL; transcripts scrubbed
