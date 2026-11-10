/**
 * Advisory matching, part 1 (docs/design/ADVISORY_MATCHING_CONTRACT.md, FROZEN 2026-11-10): parsing public vendor advisories
 * into normalised version rules and judging a device's stored version against them. Everything here is a pure function of
 * its inputs: no database, no network, no device contact.
 */
package com.securityexpert.nexus.ui2.service.advisory;
