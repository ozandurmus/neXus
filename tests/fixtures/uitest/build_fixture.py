"""Regenerate the `uitest` render-harness fixture bundle.

    py -V:3.12 tests/fixtures/uitest/build_fixture.py

Writes the JSON payloads consumed by `scripts/render_uitest.py`. All data is
hand-authored: obviously-fake device names, RFC 5737 documentation IP ranges
(192.0.2/198.51.100/203.0.113), no secrets, no real identities. The repository
privacy gate is lenient under `tests/`, and these values pass everywhere.

**Growth rule:** when a build adds or changes a `configuration_ui` /
`compliance_overview` / `crypto` / `discovery` payload field or a UI module,
extend the matching fixture here in the same change and re-run this script so the
render harness exercises the new path.
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _iface(name, ip, prefix, *, itype="physical", state="up", vr=None, vsys=None, zone=None):
    net = f"{'.'.join(ip.split('.')[:3])}.0/{prefix}"
    row = {"name": name, "type": itype, "state": state,
           "ips": [{"ip": ip, "prefix": prefix, "network": net}]}
    if vr is not None:
        row.update({"vr": vr, "vsys": vsys, "zone": zone, "ip": ip, "prefix": prefix, "network": net})
    return row


def _route(network, next_hop, interface, rtype="static", **extra):
    return {"network": network, "next_hop": next_hop, "interface": interface, "type": rtype, **extra}


def _status(data_state, availability, *, fresh, reason=None):
    now = datetime.now(timezone.utc).isoformat()
    return {"fresh": fresh, "data_state": data_state, "availability_state": availability,
            "current_run": "uitest", "current_run_observed": True,
            "collected_at": now if fresh else None, "last_successful_collection": now,
            "stale_reason": reason}


def unified():
    live = _status("live", "available", fresh=True)
    lkg = _status("last_known_good", "communicating", fresh=False, reason="collection_failed")
    return [
        {"source": "cp", "device": "cp-edge-01", "vsys": "default",
         "interfaces": [_iface("eth0", "192.0.2.11", 24), _iface("eth1", "198.51.100.11", 24)],
         "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth0", "default"),
                    _route("203.0.113.0/24", "198.51.100.254", "eth1")],
         "inventory_status": live},
        {"source": "cp", "device": "cp-core-01", "vsys": "default", "cluster": "cp-core",
         "cluster_topology": {"group_id": "uitest01", "display_name": "cp-core-CLS",
                              "name_source": "inferred_member_pattern",
                              "members": ["cp-core-01", "cp-core-02"], "cma": "CMA-EU",
                              "virtual_interfaces": [{"name": "eth0", "ip": "192.0.2.20", "role": "cluster_virtual"}]},
         "interfaces": [_iface("eth0", "192.0.2.21", 24), _iface("eth1", "203.0.113.21", 24)],
         "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth0", "default")],
         "inventory_status": live},
        {"source": "cp", "device": "cp-core-02", "vsys": "default", "cluster": "cp-core",
         "interfaces": [_iface("eth0", "192.0.2.22", 24), _iface("eth1", "203.0.113.22", 24)],
         "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth0", "default")],
         "inventory_status": lkg},
        {"source": "vsx", "device": "vsx-host-01", "vsys": "VS-PAYMENTS", "vs_id": "2", "cluster": "vsx-host",
         "interfaces": [_iface("wrp64", "198.51.100.30", 24),
                        _iface("eth2.100", "203.0.113.30", 28, itype="vlan")],
         "routes": [_route("0.0.0.0/0", "198.51.100.254", "wrp64", "default"),
                    _route("198.51.100.128/25", "203.0.113.40", "eth2.100")],
         "inventory_status": live},
        {"source": "panorama", "device": "pan-edge-01", "serial": "UITEST00000001",
         "management_ip": "192.0.2.40",
         "interfaces": [_iface("ethernet1/1", "203.0.113.51", 24, vr="default", vsys="vsys1", zone="untrust"),
                        _iface("ethernet1/2", "198.51.100.51", 24, vr="default", vsys="vsys1", zone="trust")],
         "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="default"),
                    _route("203.0.113.0/25", "198.51.100.254", "ethernet1/2", vr="default")],
         "inventory_status": live},
        {"source": "panorama", "device": "pan-edge-02", "serial": "UITEST00000002",
         "management_ip": "192.0.2.41",
         "interfaces": [_iface("ethernet1/1", "203.0.113.52", 24, vr="default", vsys="vsys1", zone="untrust")],
         "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="default")],
         "inventory_status": _status("no_data", "disconnected", fresh=False, reason="management_disconnected")},
    ]


def _cp_sections():
    return [
        {"id": "system", "settings": [{"setting": "Hostname", "value": "CP-EDGE-01"},
                                      {"setting": "Timezone", "value": "Europe/Istanbul"}]},
        {"id": "dns", "settings": [{"setting": "Primary DNS", "value": "203.0.113.53"},
                                   {"setting": "Search Domain", "value": "corp.example"}]},
        {"id": "ntp", "settings": [{"setting": "Primary NTP Server", "value": "203.0.113.10"},
                                   {"setting": "Secondary NTP Server", "value": "203.0.113.11"}]},
        {"id": "authentication", "settings": [{"setting": "AAA Type", "value": "radius"},
                                              {"setting": "Failed Attempts Lockout", "value": "5"}]},
        {"id": "management", "settings": [{"setting": "Telnet Disabled", "value": "yes"},
                                         {"setting": "Inactivity Timeout", "value": "10"},
                                         {"setting": "SSH Version", "value": "v2"},
                                         {"setting": "SSH Ciphers", "value": "aes256-ctr,aes128-ctr"}]},
        {"id": "password_policy", "settings": [{"setting": "Minimum Password Length", "value": "12"},
                                              {"setting": "Password Complexity", "value": "enabled"},
                                              {"setting": "Password History Depth", "value": "8"}]},
        {"id": "banner", "settings": [{"setting": "Login Banner Present", "value": "yes"},
                                     {"setting": "Login Banner Length", "value": "medium"}]},
        {"id": "services", "settings": [{"setting": "Telnet", "value": "disabled"},
                                       {"setting": "HTTP", "value": "disabled"}]},
        {"id": "logging", "settings": [{"setting": "Logging Audit", "value": "enabled"},
                                      {"setting": "Syslog Server", "value": "203.0.113.90"}]},
    ]


def _pan_sections():
    return [
        {"id": "system", "settings": [{"setting": "Hostname", "value": "PAN-EDGE-01"},
                                      {"setting": "Domain", "value": "corp.example"},
                                      {"setting": "Timezone", "value": "Europe/Istanbul"}]},
        {"id": "dns", "settings": [{"setting": "Primary DNS", "value": "203.0.113.53"},
                                   {"setting": "Secondary DNS", "value": "203.0.113.54"}]},
        {"id": "ntp", "settings": [{"setting": "Primary NTP Server", "value": "203.0.113.10"}]},
        {"id": "management", "settings": [{"setting": "Permitted IP", "value": "192.0.2.0/24"},
                                         {"setting": "Idle Timeout", "value": "10"}]},
        {"id": "password_policy", "settings": [{"setting": "Minimum Password Length", "value": "8"},
                                              {"setting": "Password Complexity", "value": "disabled"}]},
        {"id": "banner", "settings": [{"setting": "Login Banner Present", "value": "no"}]},
        {"id": "services", "settings": [{"setting": "SNMP", "value": "v3"},
                                       {"setting": "HTTP", "value": "disabled"}]},
    ]


def _alignment_findings():
    return [
        {"alignment_key": "deviceconfig/system/hostname", "classification": "ALIGNED", "category": "system"},
        {"alignment_key": "deviceconfig/system/dns-setting/servers/primary", "classification": "ALIGNED", "category": "dns"},
        {"alignment_key": "deviceconfig/system/permitted-ip/entry/description", "classification": "LOCAL_OVERRIDE",
         "category": "system", "reason": "effective_differs_from_expected_and_matches_local_active_scalar"},
        {"alignment_key": "deviceconfig/high-availability/peer-ip", "classification": "MEMBER_SPECIFIC",
         "category": "ha", "reason": "member_relative_ha_setting"},
    ]


def configuration_ui():
    return {
        "schema_version": "0.6.1B",
        "available": True,
        "local_only": True,
        "raw_configuration_blob_included": False,
        "value_hashes_included": False,
        "structured_current_values_included": True,
        "workflow": {"mode": "uitest", "label": "UI test bundle", "checkpoint": False, "mixed_cycle": True},
        "privacy": {"raw_configuration_blob_included": False, "credentials_included": False,
                    "secret_values_redacted": True},
        "fleet": {"tls_verify": True, "selected": 3, "success": 3, "failed": 0,
                  "primary_evidence_success": 3, "first": 0, "same": 3, "changed": 0,
                  "checkpoint_host_key_policy": "strict_known_hosts",
                  "checkpoint_production_trust_ready": True},
        "backup": {"status": "not_configured", "phase": "0.6.0B"},
        "devices": [
            {"vendor": "check_point", "vendor_key": "check_point", "name": "cp-edge-01",
             "display_name": "cp-edge-01", "device_name": "cp-edge-01",
             "entity_type": "gateway", "platform_family": "gaia",
             "serial": "SN-CP-UITEST-1", "model": "CP-6900", "sw_version": "R81.20",
             "ha_role": "active", "management_ip": "192.0.2.11", "connected": True,
             "host_key_policy": "strict_known_hosts",
             "current_configuration": {"status": "available", "sections": _cp_sections()},
             "alignment": {"counts": {"ALIGNED": 2, "LOCAL_OVERRIDE": 1, "MEMBER_SPECIFIC": 1},
                           "findings": _alignment_findings()}},
            {"vendor": "check_point", "vendor_key": "check_point", "name": "cp-core-01",
             "display_name": "cp-core-CLS", "device_name": "cp-core-01",
             "parent_name": "cp-core", "entity_type": "cluster_member", "platform_family": "gaia",
             "serial": "SN-CP-UITEST-2", "model": "CP-6900", "sw_version": "R81.20",
             "ha_role": "active", "management_ip": "192.0.2.21", "connected": True,
             "host_key_policy": "strict_known_hosts",
             "current_configuration": {"status": "available", "sections": _cp_sections()},
             "alignment": {"counts": {"ALIGNED": 3}, "findings": _alignment_findings()[:2]}},
            {"vendor": "palo_alto", "vendor_key": "palo_alto", "name": "pan-edge-01",
             "display_name": "pan-edge-01", "device_name": "pan-edge-01",
             "entity_type": "firewall", "platform_family": "pan_os",
             "serial": "UITEST00000001", "model": "PA-5220", "sw_version": "11.1.3",
             "ha_role": "active", "management_ip": "192.0.2.40", "connected": True,
             "current_configuration": {"status": "available", "sections": _pan_sections()},
             "alignment": {"counts": {"ALIGNED": 4, "LOCAL_OVERRIDE": 1}, "findings": _alignment_findings()}},
        ],
    }


def crypto_ui():
    return {
        "schema_version": "0.7.0",
        "available": True,
        "classification": "evidence_backed_crypto_posture",
        "disclaimer": "Evidence-backed crypto-area evaluation only. Not a certification or complete cryptographic assessment.",
        "rule_pack": {"pack_id": "securityexpert.crypto.cp-pan", "pack_version": "0.7.0",
                      "schema_version": "1.0", "title": "SecurityExpert CP/PAN cryptographic posture pack",
                      "source": "in_repository_static", "certification_claim": False, "rule_count": 14},
        "evidence_confidence_model": {
            "configured": "read from stored device configuration",
            "negotiated": "from a live security association - future runtime-evidence layer, not in this build",
            "inferred": "derived from platform/OS facts",
            "insufficient": "the relevant configuration section was not observed"},
        "subjects": [
            {"subject_id": "pan-001", "subject_label": "Configuration subject pan-001",
             "vendor_key": "palo_alto", "status": "FINDING",
             "facts": {"ike_crypto_profiles": [{"name": "default", "encryption": ["aes-256-cbc", "aes-256-gcm"],
                                                "hash": ["sha256"], "dh_group": ["group14"]}],
                       "ipsec_crypto_profiles": [{"name": "default", "encryption": ["aes-256-gcm"]}],
                       "tls_service_profiles": [{"name": "mgmt", "min_version": "tls1-2"}],
                       "certificates": [{"name": "mgmt-cert", "algorithm": "RSA", "key_size": 2048}]},
             "findings": [
                 {"rule_id": "ike_no_cbc", "category": "weak_algorithm", "status": "FINDING",
                  "severity": "high", "evidence_basis": "configured",
                  "summary": "An IKE crypto profile offers CBC-mode encryption."},
                 {"rule_id": "tls_min_version", "category": "crypto_agility", "status": "PASS",
                  "severity": "medium", "evidence_basis": "configured",
                  "summary": "Management TLS minimum version is 1.2."},
                 {"rule_id": "pqc_readiness", "category": "pqc_readiness", "status": "UNKNOWN",
                  "severity": "informational", "evidence_basis": "inferred",
                  "summary": "No post-quantum key exchange configured (expected at this platform level)."}]},
            {"subject_id": "cp-001", "subject_label": "Configuration subject cp-001",
             "vendor_key": "check_point", "status": "PASS",
             "facts": {"tls_service_profiles": [{"name": "gaia-portal", "min_version": "tls1-2"}]},
             "findings": [
                 {"rule_id": "ssh_no_cbc", "category": "weak_algorithm", "status": "PASS",
                  "severity": "high", "evidence_basis": "configured",
                  "summary": "SSH management offers no CBC ciphers."}]},
        ],
        "fleet": {"subjects": 2, "evaluated_subjects": 2,
                  "status_counts": {"PASS": 1, "FINDING": 1},
                  "category_counts": {"weak_algorithm": 2, "crypto_agility": 1, "pqc_readiness": 1}},
        "pqc": {"status": "INFORMATIONAL", "platform_capability": []},
        "privacy": {"contains_secrets": False, "contains_key_material": False,
                    "contains_certificate_content": False, "contains_real_identity": False},
    }


def discovery_ui():
    now = datetime.now(timezone.utc).isoformat()
    return {
        "schema_version": "0.6.1C",
        "generated_at": now,
        "fleet_summary": {"total_entities": 3, "deferred_count": 1,
                          "lifecycle_state_counts": {"STABLE": 2, "VALIDATED": 1},
                          "vendor_counts": {"checkpoint": 2, "paloalto": 1}},
        "entities": [
            {"entity_id": "cp:uitest-1", "canonical_id": "cp-edge-01", "vendor": "checkpoint",
             "lifecycle_state": "STABLE", "collection_mode": "expert_explicit_clish",
             "deferred": False, "confidence": 92, "last_transition_reason": "repeated_success",
             "observed_runs": 6},
            {"entity_id": "cp:uitest-2", "canonical_id": "cp-core-01", "vendor": "checkpoint",
             "lifecycle_state": "VALIDATED", "collection_mode": "expert_explicit_clish",
             "deferred": True, "confidence": 71, "last_transition_reason": "standby_member_deferred",
             "observed_runs": 3},
            {"entity_id": "pan:uitest-1", "canonical_id": "pan-edge-01", "vendor": "paloalto",
             "lifecycle_state": "STABLE", "collection_mode": "pan_api",
             "deferred": False, "confidence": 88, "last_transition_reason": "repeated_success",
             "observed_runs": 5},
        ],
        "coordinator": {"available": True, "active_job_count": 0,
                        "budgets": {"checkpoint": 1, "checkpoint_vsx": 1, "paloalto": 1},
                        "recent_jobs": [
                            {"job_id": "job-uitest-1", "vendor": "checkpoint", "status": "completed",
                             "scope": "inventory", "started_at": now, "finished_at": now},
                            {"job_id": "job-uitest-2", "vendor": "paloalto", "status": "completed",
                             "scope": "inventory", "started_at": now, "finished_at": now}]},
        "scheduler": {"configured": True, "enabled": False, "workflow_count": 1,
                      "workflows": [{"name": "nightly-inventory", "vendor": "checkpoint",
                                     "enabled": False, "interval_minutes": 1440,
                                     "provenance": "runtime_root_policy"}]},
        "default_concurrency_budgets": {"checkpoint": 1, "checkpoint_vsx": 1, "paloalto": 1, "_default": 1},
        "lifecycle_state_labels": {"DISCOVERED": "Discovered", "VALIDATED": "Validated",
                                   "STABLE": "Stable", "EXCLUDED": "Excluded", "REMOVED": "Removed"},
        "collection_mode_labels": {"expert_explicit_clish": "Expert + explicit Clish",
                                   "direct_clish_capable": "Direct Clish (capability only)",
                                   "vsx_vsenv": "VSX vsenv context", "pan_api": "Palo Alto API",
                                   "deferred_standby": "Deferred — standby member",
                                   "deferred_lifecycle": "Deferred — lifecycle state", "unknown": "Unknown"},
        "job_status_labels": {"pending": "Pending", "running": "Running", "completed": "Completed",
                              "failed": "Failed", "cancelled": "Cancelled", "coalesced": "Coalesced"},
    }


def compliance_checks():
    frameworks = [{"framework": "CIS", "reference": "5.1", "applies": True},
                  {"framework": "PCI-DSS", "reference": "1.2.1", "applies": True},
                  {"framework": "BDDK", "reference": "Ağ Güvenliği", "applies": True}]
    return {
        "version": 1,
        "pack_id": "uitest.local",
        "pack_version": "1",
        "checks": [
            {"id": "x_ssh_no_cbc", "title": "SSH management offers no CBC ciphers",
             "rationale": "CBC-mode SSH ciphers are plaintext-recovery vulnerable.",
             "severity": "high", "applies_to": {"vendor": ["check_point"]},
             "frameworks": frameworks,
             "evidence": {"combine": "all", "steps": [
                 {"source": "current_configuration.sections[id=management].settings",
                  "select": "value", "assert": {"op": "none_match", "pattern": "(?i)-cbc"}}]},
             "verdict": {"on_pass": "PASS", "on_fail": "FINDING", "on_no_evidence": "UNKNOWN"}},
            {"id": "x_min_two_interfaces", "title": "Device exposes at least two interfaces",
             "rationale": "A single-interface production firewall is usually a discovery gap.",
             "severity": "low", "mode": "advisory", "applies_to": {},
             "frameworks": frameworks,
             "evidence": {"steps": [
                 {"source": "unified.interfaces", "assert": {"op": "count_gte", "value": 2}}]},
             "verdict": {"on_pass": "PASS", "on_fail": "FINDING", "on_no_evidence": "UNKNOWN"}},
        ],
    }


def compliance_history():
    base = datetime(2026, 6, 1, tzinfo=timezone.utc)
    rows = []
    for i, aligned in enumerate((71.0, 76.5, 80.0)):
        at = (base + timedelta(days=30 * i)).strftime("%Y-%m-%dT%H:%M:%SZ")
        rows.append({
            "run_id": f"uitest-{i+1}", "collected_at": at,
            "compliance_schema_version": "0.6.6B", "catalog_version": "0.7.2",
            "framework_catalog_version": "0.7.4",
            "cells": {"aligned": int(aligned // 5), "finding": 3 - i, "unknown": 2, "planned": 1, "waived": 0},
            "aligned_percent": aligned, "risk_weighted_alignment_percent": round(aligned - 3.0, 1),
            "monitored_controls": 12 + i, "total_controls": 18, "subjects": 3,
            "by_framework": {"CIS": {"aligned": 4 + i, "finding": 2 - i, "coverage": "PARTIALLY_COVERED"},
                            "PCI-DSS": {"aligned": 3 + i, "finding": 1, "coverage": "PARTIALLY_COVERED"},
                            "BDDK": {"aligned": 2, "finding": 1, "coverage": "UNCOVERED"}},
        })
    return {"schema_version": "0.7.5",
            "updated_at": rows[-1]["collected_at"], "records": rows}


def main():
    (HERE / "state").mkdir(parents=True, exist_ok=True)
    writes = {
        "unified.json": unified(),
        "configuration_ui.json": configuration_ui(),
        "crypto_ui.json": crypto_ui(),
        "discovery_ui.json": discovery_ui(),
        "state/compliance_checks.json": compliance_checks(),
        "state/compliance_history.json": compliance_history(),
    }
    for rel, payload in writes.items():
        path = HERE / rel
        path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {rel} ({path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
