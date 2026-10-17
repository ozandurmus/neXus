/**
 * Check Point incremental system-log read planning (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md, section 4).
 *
 * <p>Everything in this package is a pure function over values: no I/O, no clock, no Spring, no database, no
 * transport. The executor lane feeds it the C1 stat output, the stored cursors and the per-run feedback (anchor
 * results, bytes already planned), and carries out the read actions and gap records it returns. Depending on
 * nothing but the JDK keeps the planner testable row by row against the decision table and keeps the device-facing
 * code free of rotation logic.</p>
 */
package com.securityexpert.nexus.ui2.worker.systemlog;
