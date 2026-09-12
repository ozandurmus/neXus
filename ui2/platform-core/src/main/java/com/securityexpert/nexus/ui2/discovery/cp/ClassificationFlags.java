package com.securityexpert.nexus.ui2.discovery.cp;

/**
 * The closed, three-flag set contract §4 classifies from (CL-4). A future
 * movement that needs a fourth flag adds it here, with its own measurement,
 * and re-runs contract §9 item 7 — it does not read one opportunistically.
 */
public record ClassificationFlags(boolean product, boolean virtHost, boolean virtSystem) {
}
