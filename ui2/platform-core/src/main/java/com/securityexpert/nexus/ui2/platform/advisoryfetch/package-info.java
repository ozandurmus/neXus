/**
 * The outbound advisory fetch workload, {@code ui2-advisory-fetch} (docs/design/ADVISORY_MATCHING_CONTRACT.md section 3).
 * It fetches fixed public documents through the configured proxy and hands them to the service's internal ingest
 * endpoint. It lives in {@code platform-core} because that module may depend on no other neXus module (DIR-1): this
 * package imports JDK classes only, so it cannot reach a repository, a credential or a device transport, and its pod
 * holds no neXus key other than its own ingest token.
 */
package com.securityexpert.nexus.ui2.platform.advisoryfetch;
