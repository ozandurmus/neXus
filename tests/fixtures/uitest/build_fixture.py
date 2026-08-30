"""Regenerate the `uitest` render-harness fixture bundle.

    py -V:3.12 tests/fixtures/uitest/build_fixture.py

Writes the JSON payloads consumed by `scripts/render_uitest.py`. All data is
hand-authored: obviously-fake device names, RFC 5737 documentation IP ranges
(192.0.2 / 198.51.100 / 203.0.113), no secrets, no real identities. The
repository privacy gate is lenient under `tests/`, and these values pass
everywhere.

The bundle is a **topology matrix** -- it must exercise every device shape and
UI branch:

  Check Point   standalone gateway, ClusterXL (2 members, active/standby),
                VSX host (standalone) + its virtual systems, VSX cluster
                (2 hosts) + a shared virtual system, one UNAVAILABLE gateway.
  Palo Alto     single firewall, HA pair (active/passive), multi-vsys firewall,
                multi-vsys HA pair.
  edge cases    interface/route divergence between cluster members; stale
                (LAST_KNOWN_GOOD) and disconnected (NO_DATA) inventory; the full
                alignment classification set; SAME / CHANGED / FIRST /
                insufficient history; crypto PASS / FINDING / UNKNOWN across
                weak_algorithm / crypto_agility / pqc_readiness; enforced +
                advisory + WAIVED compliance; per-framework COVERED /
                PARTIALLY_COVERED / UNCOVERED.

**Growth rule:** when a build adds or changes a `configuration_ui` /
`compliance_overview` / `crypto` / `discovery` payload field or a UI module,
extend the matching structure here in the same change and re-run this script.
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
NOW = datetime(2026, 8, 30, 12, 0, tzinfo=timezone.utc)
_ISO = NOW.strftime("%Y-%m-%dT%H:%M:%SZ")


# --------------------------------------------------------------------------
# unified inventory (Network Inventory + Overview)
# --------------------------------------------------------------------------

def _iface(name, ip, prefix, *, itype="physical", state="up", vr=None, vsys=None, zone=None):
    net = f"{'.'.join(ip.split('.')[:3])}.0/{prefix}"
    row = {"name": name, "type": itype, "state": state,
           "ips": [{"ip": ip, "prefix": prefix, "network": net}]}
    if vr is not None:
        row.update({"vr": vr, "vsys": vsys, "zone": zone, "ip": ip, "prefix": prefix, "network": net})
    return row


def _route(network, next_hop, interface, rtype="static", **extra):
    return {"network": network, "next_hop": next_hop, "interface": interface, "type": rtype, **extra}


def _inv_status(data_state, availability, *, fresh, reason=None):
    return {"fresh": fresh, "data_state": data_state, "availability_state": availability,
            "current_run": "uitest", "current_run_observed": True,
            "collected_at": _ISO if fresh else None, "last_successful_collection": _ISO,
            "stale_reason": reason}


_LIVE = _inv_status("live", "available", fresh=True)
_LKG = _inv_status("last_known_good", "communicating", fresh=False, reason="collection_failed")
_NODATA = _inv_status("no_data", "disconnected", fresh=False, reason="management_disconnected")


def unified():
    return [
        # --- CP standalone gateway ---
        {"source": "cp", "device": "cp-edge-01", "vsys": "default",
         "interfaces": [_iface("eth0", "192.0.2.11", 24), _iface("eth1", "198.51.100.11", 24),
                        _iface("eth2", "203.0.113.11", 24)],
         "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth0", "default"),
                    _route("198.51.100.0/24", "0.0.0.0", "eth1", "connected"),
                    _route("10.20.0.0/16", "203.0.113.254", "eth2")],
         "inventory_status": _LIVE},

        # --- CP ClusterXL: member A vs member B with a real interface + route diff ---
        {"source": "cp", "device": "cp-core-01", "vsys": "default", "cluster": "cp-core",
         "cluster_topology": {"group_id": "uitest-cxl", "display_name": "cp-core-CLS",
                              "name_source": "inferred_member_pattern",
                              "members": ["cp-core-01", "cp-core-02"], "cma": "CMA-EU",
                              "virtual_interfaces": [{"name": "eth0", "ip": "192.0.2.20", "role": "cluster_virtual"}]},
         "interfaces": [_iface("eth0", "192.0.2.21", 24), _iface("eth1", "203.0.113.21", 24),
                        _iface("eth2", "198.51.100.21", 24)],
         "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth0", "default"),
                    _route("172.16.0.0/16", "203.0.113.250", "eth1")],
         "inventory_status": _LIVE},
        {"source": "cp", "device": "cp-core-02", "vsys": "default", "cluster": "cp-core",
         "interfaces": [_iface("eth0", "192.0.2.22", 24), _iface("eth1", "203.0.113.22", 24)],  # no eth2 -> interface diff
         "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth0", "default"),
                    _route("172.16.0.0/16", "203.0.113.251", "eth1"),                          # different next-hop -> route diff
                    _route("10.99.0.0/24", "203.0.113.9", "eth1")],                            # extra route -> route diff
         "inventory_status": _LKG},

        # --- CP VSX host (standalone) + two virtual systems ---
        {"source": "vsx", "device": "vsx-gw-01", "vsys": "", "cluster": "",
         "interfaces": [_iface("eth1-01", "192.0.2.31", 24), _iface("eth1-02", "198.51.100.31", 24)],
         "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth1-01", "default")],
         "inventory_status": _LIVE},
        {"source": "vsx", "device": "vsx-gw-01", "vsys": "VS-WEB", "vs_id": "10", "cluster": "",
         "interfaces": [_iface("wrp10", "198.51.100.40", 28), _iface("eth1-01.100", "203.0.113.40", 28, itype="vlan")],
         "routes": [_route("0.0.0.0/0", "198.51.100.33", "wrp10", "default"),
                    _route("203.0.113.64/28", "203.0.113.62", "eth1-01.100")],
         "inventory_status": _LIVE},
        {"source": "vsx", "device": "vsx-gw-01", "vsys": "VS-DB", "vs_id": "20", "cluster": "",
         "interfaces": [_iface("wrp20", "198.51.100.50", 28)],
         "routes": [_route("10.30.0.0/16", "198.51.100.49", "wrp20")],
         "inventory_status": _LIVE},

        # --- CP VSX cluster: 2 hosts + a shared virtual system ---
        {"source": "vsx", "device": "vsx-cls-01", "vsys": "", "cluster": "vsx-cls",
         "interfaces": [_iface("eth3-01", "192.0.2.61", 24), _iface("eth3-02", "198.51.100.61", 24)],
         "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth3-01", "default")],
         "inventory_status": _LIVE},
        {"source": "vsx", "device": "vsx-cls-02", "vsys": "", "cluster": "vsx-cls",
         "interfaces": [_iface("eth3-01", "192.0.2.62", 24), _iface("eth3-02", "198.51.100.62", 24)],
         "routes": [_route("0.0.0.0/0", "192.0.2.254", "eth3-01", "default")],
         "inventory_status": _LIVE},
        {"source": "vsx", "device": "vsx-cls-01", "vsys": "VS-PAYMENTS", "vs_id": "30", "cluster": "vsx-cls",
         "interfaces": [_iface("wrp30", "198.51.100.70", 28), _iface("eth3-02.200", "203.0.113.70", 28, itype="vlan")],
         "routes": [_route("0.0.0.0/0", "198.51.100.65", "wrp30", "default"),
                    _route("203.0.113.96/28", "203.0.113.94", "eth3-02.200")],
         "inventory_status": _LIVE},

        # --- PAN single firewall ---
        {"source": "panorama", "device": "pan-edge-01", "serial": "UITEST0EDGE01", "management_ip": "192.0.2.101",
         "interfaces": [_iface("ethernet1/1", "203.0.113.101", 24, vr="default", vsys="vsys1", zone="untrust")],
         "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="default")],
         "inventory_status": _LIVE},

        # --- PAN HA pair (active / passive) ---
        {"source": "panorama", "device": "pan-ha-01", "serial": "UITEST0HA0001", "management_ip": "192.0.2.111",
         "interfaces": [_iface("ethernet1/1", "203.0.113.111", 24, vr="default", vsys="vsys1", zone="untrust"),
                        _iface("ethernet1/2", "198.51.100.111", 24, vr="default", vsys="vsys1", zone="trust")],
         "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="default"),
                    _route("10.40.0.0/16", "198.51.100.110", "ethernet1/2", vr="default")],
         "inventory_status": _LIVE},
        {"source": "panorama", "device": "pan-ha-02", "serial": "UITEST0HA0002", "management_ip": "192.0.2.112",
         "interfaces": [_iface("ethernet1/1", "203.0.113.112", 24, vr="default", vsys="vsys1", zone="untrust"),
                        _iface("ethernet1/2", "198.51.100.112", 24, vr="default", vsys="vsys1", zone="trust")],
         "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="default"),
                    _route("10.40.0.0/16", "198.51.100.110", "ethernet1/2", vr="default")],
         "inventory_status": _LIVE},

        # --- PAN multi-vsys firewall ---
        {"source": "panorama", "device": "pan-mv-01", "serial": "UITEST0MV0001", "management_ip": "192.0.2.121",
         "interfaces": [_iface("ethernet1/1", "203.0.113.121", 24, vr="vr-edge", vsys="vsys1", zone="untrust"),
                        _iface("ethernet1/2", "198.51.100.121", 24, vr="vr-edge", vsys="vsys1", zone="trust"),
                        _iface("ethernet1/3", "198.51.100.129", 25, vr="vr-dmz", vsys="vsys2", zone="dmz"),
                        _iface("ethernet1/4", "203.0.113.129", 25, vr="vr-inet", vsys="vsys3", zone="internet")],
         "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="vr-edge"),
                    _route("0.0.0.0/0", "203.0.113.253", "ethernet1/4", "default", vr="vr-inet"),
                    _route("10.50.0.0/16", "198.51.100.120", "ethernet1/2", vr="vr-edge")],
         "inventory_status": _LIVE},

        # --- PAN multi-vsys HA pair ---
        {"source": "panorama", "device": "pan-mvha-01", "serial": "UITEST0MVHA01", "management_ip": "192.0.2.131",
         "interfaces": [_iface("ethernet1/1", "203.0.113.131", 24, vr="default", vsys="vsys1", zone="untrust"),
                        _iface("ethernet1/3", "198.51.100.131", 25, vr="vr-dmz", vsys="vsys2", zone="dmz")],
         "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="default")],
         "inventory_status": _LIVE},
        {"source": "panorama", "device": "pan-mvha-02", "serial": "UITEST0MVHA02", "management_ip": "192.0.2.132",
         "interfaces": [_iface("ethernet1/1", "203.0.113.132", 24, vr="default", vsys="vsys1", zone="untrust"),
                        _iface("ethernet1/3", "198.51.100.132", 25, vr="vr-dmz", vsys="vsys2", zone="dmz")],
         "routes": [_route("0.0.0.0/0", "203.0.113.254", "ethernet1/1", "default", vr="default")],
         "inventory_status": _NODATA},
    ]


# --------------------------------------------------------------------------
# configuration_ui (Configuration module + build_compliance_posture input)
# --------------------------------------------------------------------------

def _section(sid, *pairs):
    return {"id": sid, "label": sid.replace("_", " ").title(),
            "count": len(pairs),
            "settings": [{"setting": s, "value": v} for s, v in pairs]}


def _cp_sections(*, weak_ssh=False, missing_ntp=False):
    ssh = "aes128-cbc,aes256-ctr" if weak_ssh else "aes256-ctr,aes256-gcm"
    secs = [
        _section("system", ("Hostname", "CP-DEVICE"), ("Timezone", "Europe/Istanbul")),
        _section("dns", ("Primary DNS", "203.0.113.53"), ("Search Domain", "corp.example")),
        _section("management", ("Telnet Disabled", "yes"), ("Inactivity Timeout", "10"),
                 ("SSH Version", "v2"), ("SSH Ciphers", ssh)),
        _section("password_policy", ("Minimum Password Length", "12"),
                 ("Password Complexity", "enabled"), ("Password History Depth", "8")),
        _section("banner", ("Login Banner Present", "yes"), ("Login Banner Length", "medium")),
        _section("services", ("Telnet", "disabled"), ("HTTP", "disabled")),
        _section("logging", ("Logging Audit", "enabled"), ("Syslog Server", "203.0.113.90")),
    ]
    if not missing_ntp:
        secs.insert(2, _section("ntp", ("Primary NTP Server", "203.0.113.10"),
                                ("Secondary NTP Server", "203.0.113.11")))
    return secs


def _pan_sections(*, weak_pw=False, vsys_count=1):
    secs = [
        _section("system", ("Hostname", "PAN-DEVICE"), ("Domain", "corp.example"),
                 ("Timezone", "Europe/Istanbul")),
        _section("dns", ("Primary DNS", "203.0.113.53"), ("Secondary DNS", "203.0.113.54")),
        _section("ntp", ("Primary NTP Server", "203.0.113.10")),
        _section("management", ("Permitted IP", "192.0.2.0/24"), ("Idle Timeout", "10")),
        _section("password_policy",
                 ("Minimum Password Length", "8" if weak_pw else "14"),
                 ("Password Complexity", "disabled" if weak_pw else "enabled")),
        _section("banner", ("Login Banner Present", "no" if weak_pw else "yes")),
        _section("services", ("SNMP", "v3"), ("HTTP", "disabled")),
    ]
    if vsys_count > 1:
        secs.append(_section("vsys", ("Virtual System Count", str(vsys_count)),
                             ("Shared Gateway Count", "1")))
    return secs


_ALIGN_FINDINGS = [
    {"alignment_key": "deviceconfig/system/hostname", "classification": "ALIGNED", "category": "system"},
    {"alignment_key": "deviceconfig/system/dns-setting/servers/primary", "classification": "ALIGNED", "category": "dns"},
    {"alignment_key": "deviceconfig/system/permitted-ip/entry/description", "classification": "LOCAL_OVERRIDE",
     "category": "system", "reason": "effective_differs_from_expected_and_matches_local_active_scalar"},
    {"alignment_key": "deviceconfig/system/login-banner", "classification": "EFFECTIVE_DRIFT",
     "category": "system", "reason": "effective_differs_from_expected_no_local_scalar_match"},
    {"alignment_key": "network/virtual-router/entry/routing-table/ip/static-route", "classification": "LOCAL_ONLY",
     "category": "network", "reason": "present_only_in_local_active"},
    {"alignment_key": "deviceconfig/setting/management/idle-timeout", "classification": "EXPECTED_ONLY",
     "category": "management", "reason": "present_only_in_expected"},
    {"alignment_key": "deviceconfig/high-availability/group/peer-ip", "classification": "MEMBER_SPECIFIC",
     "category": "ha", "reason": "member_relative_ha_setting"},
    {"alignment_key": "deviceconfig/system/service/disable-telnet", "classification": "UNKNOWN",
     "category": "management", "reason": "expected_or_effective_value_not_resolvable"},
]

_ALIGN_COUNTS = {"ALIGNED": 6, "LOCAL_OVERRIDE": 2, "EFFECTIVE_DRIFT": 1, "MEMBER_SPECIFIC": 1,
                 "LOCAL_ONLY": 1, "EXPECTED_ONLY": 1, "UNKNOWN": 1}


def _cp_current(entity_type, sections):
    return {"schema_version": "0.6.1B", "status": "available", "vendor": "check_point",
            "source_plane": "gaia-clish-show-configuration", "entity_type": entity_type,
            "sections": sections,
            "section_index": [{"id": s["id"], "label": s["label"], "count": s["count"]} for s in sections],
            "highlights": [{"label": "Hostname", "value": "CP-DEVICE", "section": "system",
                            "section_label": "System", "origin": "local", "context": None}],
            "setting_count": sum(s["count"] for s in sections),
            "redacted_secret_setting_count": 2,
            "structured_values_included": True, "raw_config_included": False, "secrets_redacted": True}


def _pan_current(sections):
    cur = _cp_current("firewall", sections)
    cur.update({"vendor": "palo_alto", "source_plane": "pan-effective-running"})
    return cur


def _alignment_block(*, available=True, counts=None, findings=None, device_status="DIFFERENCE_OBSERVED"):
    counts = counts if counts is not None else dict(_ALIGN_COUNTS)
    findings = findings if findings is not None else list(_ALIGN_FINDINGS)
    return {"available": available,
            "status": "success" if available else "insufficient_evidence",
            "device_status": device_status if available else "INSUFFICIENT_EVIDENCE",
            "engine_status": "success" if available else "insufficient_evidence",
            "reason": "trusted_intent_compared_with_direct_actual" if available
            else "trusted_management_intent_projection_unavailable",
            "message": "Trusted intent was compared with direct actual evidence." if available
            else "Trusted intent is unavailable; direct actual evidence is not treated as expected state.",
            "counts": counts,
            "categories": [{"category": k, "count": v} for k, v in
                           {"system": 4, "network": 2, "management": 3, "ha": 1}.items()],
            "expected_settings": 40, "alignment_ready_settings": 34, "observed_settings": 36,
            "observed_percent": 90.0, "coverage_percent": 85.0, "value_comparison_coverage_percent": 80.0,
            "local_only_settings": 1, "semantic_exclusion_settings": 2,
            "findings": findings, "peer_evidence_incomplete": False}


def _cp_device(dev_id, name, entity_type, *, ha_role=None, connected=True, sections=None,
               parent_name=None, cluster_group_id=None, cluster_display_name=None,
               vs_id=None, vsys_count=0, change_state="same", align=None,
               failure_family=None, model="CP-6900", sw="R81.20"):
    sections = sections if sections is not None else _cp_sections()
    current = _cp_current(entity_type, sections) if connected else {"status": "unavailable"}
    scope_label, scope = {
        "clusterxl_member": ("ClusterXL", cluster_display_name or "Member"),
        "vsx_host": ("VSX", "Host"),
        "virtual_system": ("VSX Context", f"VSID {vs_id}" if vs_id else "Virtual System"),
    }.get(entity_type, ("Entity", "Standalone gateway"))
    return {
        "id": f"cp:{dev_id}", "name": name, "device_name": name,
        "vendor": "Check Point", "vendor_key": "check_point",
        "entity_type": entity_type, "parent_name": parent_name,
        "parent_entity_id": f"cp:{parent_name}" if parent_name else None,
        "cluster_group_id": cluster_group_id, "cluster_display_name": cluster_display_name,
        "presentation_group_id": cluster_group_id, "presentation_group_label": cluster_display_name,
        "presentation_group_source": "inferred_member_pattern" if cluster_group_id else None,
        "platform": {"family": "gaia", "label": "Check Point Gaia"},
        "platform_family": "gaia", "platform_label": "Check Point Gaia",
        "management_state": "communicating" if connected else "unreachable",
        "failure_reason": None if connected else "capability_gap",
        "failure_family": failure_family,
        "serial": f"SN-{dev_id.upper()}", "management_ip": None,
        "model": model, "sw_version": sw,
        "ha_role": ha_role, "ha_role_source": "cluster_topology" if ha_role else None,
        "vsys_count": vsys_count,
        "policy_scope_label": scope_label, "policy_scope": scope,
        "connected": connected, "status": "success" if connected else "failed",
        "primary_evidence_status": "success" if connected else "failed",
        "alignment_evidence_status": "success" if (connected and align is not False) else "insufficient_evidence",
        "started_at": _ISO, "completed_at": _ISO,
        "tone": "success" if connected else ("muted" if failure_family == "capability_gap" else "danger"),
        "status_label": "Current" if connected else "Capability gap",
        "assignment": {},
        "current_configuration": current,
        "alignment": _alignment_block(available=bool(connected) and align is not False,
                                      counts=(align or {}).get("counts") if isinstance(align, dict) else None,
                                      findings=(align or {}).get("findings") if isinstance(align, dict) else None),
        "history": {"actual_change_state": change_state, "effective_change_state": change_state},
        "history_v1": _history_v1(change_state, vendor="check_point"),
        "host_key_policy": "strict_known_hosts",
    }


def _pan_device(dev_id, name, *, ha_role="HA Disabled", connected=True, vsys_count=1,
                device_group=None, out_of_sync=False, change_state="same", weak_pw=False,
                model="PA-5220", sw="11.1.3"):
    sections = _pan_sections(weak_pw=weak_pw, vsys_count=vsys_count)
    current = _pan_current(sections) if connected else {"status": "unavailable"}
    return {
        "id": dev_id, "name": name,
        "vendor": "Palo Alto Networks", "vendor_key": "palo_alto",
        "serial": dev_id, "management_ip": None, "model": model, "sw_version": sw,
        "ha_role": ha_role, "ha_role_source": "panorama_target_ha_state",
        "vsys_count": vsys_count,
        "policy_scope_label": "Device Group", "policy_scope": device_group,
        "connected": connected, "status": "success" if connected else "failed",
        "primary_evidence_status": "success" if connected else "failed",
        "alignment_evidence_status": "success" if connected else "insufficient_evidence",
        "started_at": _ISO, "completed_at": _ISO,
        "tone": "success" if connected else "danger",
        "status_label": "Current" if connected else "Collection issue",
        "panorama_sync": {"template": "in-sync" if not out_of_sync else "out-of-sync",
                          "shared_policy": "in-sync",
                          "out_of_sync": bool(out_of_sync)},
        "assignment": {"device_groups": [{"name": device_group, "parent": "Shared"}] if device_group else []},
        "current_configuration": current,
        "alignment": _alignment_block(
            available=connected,
            counts={"ALIGNED": 7, "LOCAL_OVERRIDE": 1, "EFFECTIVE_DRIFT": 2, "UNKNOWN": 1}
            if connected else None,
            device_status="DIFFERENCE_OBSERVED"),
        "evidence": {
            "panorama_control": {"status": "success", "role": "Panorama control-plane active",
                                 "change_state": change_state},
            "active": {"status": "success", "role": "Local active / override evidence",
                       "change_state": change_state},
            "merged": {"status": "success", "role": "Merged / provenance evidence",
                       "change_state": change_state},
            "effective": {"status": "success", "role": "Effective-running",
                          "change_state": change_state, "method": "DIRECT_EFFECTIVE"},
        },
        "history": {"active_change_state": change_state, "merged_change_state": change_state,
                    "effective_change_state": change_state},
        "history_v1": _history_v1(change_state, vendor="palo_alto"),
    }


def _history_v1(change_state, *, vendor):
    st = change_state.upper()
    events = [
        {"collected_at": (NOW - timedelta(days=30)).strftime("%Y-%m-%dT%H:%M:%SZ"),
         "change": "FIRST", "artifact_type": "effective-running"},
        {"collected_at": (NOW - timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%SZ"),
         "change": "SAME", "artifact_type": "effective-running"},
        {"collected_at": _ISO, "change": st if st in ("CHANGED", "SAME", "FIRST") else "SAME",
         "artifact_type": "effective-running"},
    ]
    if st == "FIRST":
        events = events[-1:]
    if vendor == "check_point":
        return {"schema_version": "0.6.3", "status": "available", "scope": "entity",
                "artifacts": [{"artifact_type": "cp-show-configuration",
                               "artifact_label": "Gaia show configuration",
                               "event_count": len(events), "truncated": False,
                               "skipped_malformed": 0, "events": events}],
                "pair_results": [{"status": "insufficient_evidence",
                                  "reason": "cp_raw_text_diff_not_supported_in_0_6_3"}],
                "privacy": {"contains_secrets": False, "contains_raw_configuration": False}}
    return {"schema_version": "0.6.3", "status": "available", "scope": "device",
            "artifacts": [{"artifact_type": "effective-running",
                           "artifact_label": "PAN effective-running",
                           "event_count": len(events), "truncated": False,
                           "skipped_malformed": 0, "events": events}],
            "pair_results": [{
                "status": "available" if st == "CHANGED" else "same",
                "change": st,
                "rows": ([{"path": "deviceconfig/system/login-banner", "change": "MODIFIED",
                           "left": "old banner", "right": "new banner"},
                          {"path": "network/virtual-router/entry/routing-table/ip/static-route/entry[10.9.0.0]",
                           "change": "ADDED", "left": None, "right": "present"}]
                         if st == "CHANGED" else []),
            }],
            "privacy": {"contains_secrets": False, "contains_raw_configuration": False}}


def configuration_ui():
    weak_cp_align = {"counts": {"ALIGNED": 3, "MEMBER_SPECIFIC": 4, "LOCAL_OVERRIDE": 1, "UNKNOWN": 1},
                     "findings": [f for f in _ALIGN_FINDINGS if f["classification"] in
                                  ("MEMBER_SPECIFIC", "LOCAL_OVERRIDE", "UNKNOWN", "ALIGNED")]}
    devices = [
        _cp_device("cp-edge-01", "cp-edge-01", "gateway", change_state="same"),
        _cp_device("cp-edge-02", "cp-edge-02", "gateway", connected=False,
                   failure_family="capability_gap", align=False),
        _cp_device("cp-core-01", "cp-core-01", "clusterxl_member", ha_role="active",
                   parent_name="cp-core", cluster_group_id="uitest-cxl",
                   cluster_display_name="cp-core-CLS", change_state="changed",
                   sections=_cp_sections(weak_ssh=True)),
        _cp_device("cp-core-02", "cp-core-02", "clusterxl_member", ha_role="standby",
                   parent_name="cp-core", cluster_group_id="uitest-cxl",
                   cluster_display_name="cp-core-CLS", change_state="same", align=weak_cp_align),
        _cp_device("vsx-gw-01", "vsx-gw-01", "vsx_host", vsys_count=2, change_state="same"),
        _cp_device("vsx-gw-01-vs10", "vsx-gw-01 / VS-WEB", "virtual_system", vs_id="10",
                   parent_name="vsx-gw-01", cluster_group_id="host:cp:vsx-gw-01",
                   change_state="changed", sections=_cp_sections(missing_ntp=True)),
        _cp_device("vsx-gw-01-vs20", "vsx-gw-01 / VS-DB", "virtual_system", vs_id="20",
                   parent_name="vsx-gw-01", cluster_group_id="host:cp:vsx-gw-01", change_state="same"),
        _cp_device("vsx-cls-01", "vsx-cls-01", "vsx_host", ha_role="active", vsys_count=1,
                   parent_name="vsx-cls", cluster_group_id="uitest-vsx-cls",
                   cluster_display_name="vsx-cls-CLS", change_state="same"),
        _cp_device("vsx-cls-02", "vsx-cls-02", "vsx_host", ha_role="standby", vsys_count=1,
                   parent_name="vsx-cls", cluster_group_id="uitest-vsx-cls",
                   cluster_display_name="vsx-cls-CLS", change_state="same", align=weak_cp_align),
        _cp_device("vsx-cls-vs30", "vsx-cls / VS-PAYMENTS", "virtual_system", vs_id="30",
                   parent_name="vsx-cls", cluster_group_id="uitest-vsx-cls", change_state="same"),

        _pan_device("UITEST0EDGE01", "pan-edge-01", ha_role="HA Disabled", vsys_count=1,
                    device_group="DG-Branch", change_state="first"),
        _pan_device("UITEST0HA0001", "pan-ha-01", ha_role="Local Active", vsys_count=1,
                    device_group="DG-DMZ", out_of_sync=True, change_state="changed"),
        _pan_device("UITEST0HA0002", "pan-ha-02", ha_role="Local Passive", vsys_count=1,
                    device_group="DG-DMZ", change_state="same"),
        _pan_device("UITEST0MV0001", "pan-mv-01", ha_role="HA Disabled", vsys_count=3,
                    device_group="DG-Core", change_state="changed", weak_pw=True),
        _pan_device("UITEST0MVHA01", "pan-mvha-01", ha_role="Local Active", vsys_count=2,
                    device_group="DG-Core", change_state="same"),
        _pan_device("UITEST0MVHA02", "pan-mvha-02", ha_role="Local Passive", vsys_count=2,
                    device_group="DG-Core", connected=False, change_state="same"),
    ]
    return {
        "schema_version": "0.6.1B", "available": True, "local_only": True,
        "raw_configuration_blob_included": False, "value_hashes_included": False,
        "structured_current_values_included": True,
        "workflow": {"mode": "uitest", "label": "UI test bundle", "checkpoint": False, "mixed_cycle": True},
        "privacy": {"raw_configuration_blob_included": False, "credentials_included": False,
                    "secret_values_redacted": True},
        "fleet": {
            "tls_verify": True, "ca_bundle_configured": True,
            "selected": 16, "success": 14, "failed": 2, "primary_evidence_success": 14,
            "first": 1, "same": 10, "changed": 3, "method_failures": 1,
            "devices_with_coverage_gaps": 2,
            "pan_selected": 6, "pan_success": 5,
            "alignment_supported_selected": 6, "alignment_supported_success": 5,
            "alignment_evidence_complete": 5,
            "checkpoint_selected": 10, "checkpoint_success": 9,
            "checkpoint_planned_entities": 10, "checkpoint_unmaterialized_entities": 0,
            "checkpoint_host_key_policy": "strict_known_hosts",
            "checkpoint_production_trust_ready": True,
            "checkpoint_entity_type_counts": {"gateway": 2, "clusterxl_member": 2,
                                              "vsx_host": 3, "virtual_system": 3},
            "checkpoint_platform_counts": {"gaia": 10},
        },
        "classification_labels": {"ALIGNED": "Aligned", "LOCAL_OVERRIDE": "Local override",
                                  "EFFECTIVE_DRIFT": "Effective drift", "MEMBER_SPECIFIC": "Member specific",
                                  "LOCAL_ONLY": "Local only", "EXPECTED_ONLY": "Expected only",
                                  "UNKNOWN": "Unknown"},
        "category_labels": {"system": "System", "network": "Network", "management": "Management", "ha": "HA"},
        "backup": {"status": "not_configured", "phase": "0.6.0B",
                   "message": "Native recovery artifacts are not configured yet."},
        "devices": devices,
    }


# --------------------------------------------------------------------------
# crypto_ui — CP + PAN subjects, full status / category / evidence-basis range
# --------------------------------------------------------------------------

def crypto_ui():
    return {
        "schema_version": "0.7.0", "available": True,
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
                 {"rule_id": "cert_key_size", "category": "crypto_agility", "status": "UNKNOWN",
                  "severity": "medium", "evidence_basis": "insufficient",
                  "summary": "Certificate inventory is incomplete for this subject."},
                 {"rule_id": "pqc_readiness", "category": "pqc_readiness", "status": "UNKNOWN",
                  "severity": "informational", "evidence_basis": "inferred",
                  "summary": "No post-quantum key exchange configured (expected at this platform level)."}]},
            {"subject_id": "cp-001", "subject_label": "Configuration subject cp-001",
             "vendor_key": "check_point", "status": "PASS",
             "facts": {"tls_service_profiles": [{"name": "gaia-portal", "min_version": "tls1-2"}]},
             "findings": [
                 {"rule_id": "ssh_no_cbc", "category": "weak_algorithm", "status": "PASS",
                  "severity": "high", "evidence_basis": "configured",
                  "summary": "SSH management offers no CBC ciphers."},
                 {"rule_id": "pqc_readiness", "category": "pqc_readiness", "status": "UNKNOWN",
                  "severity": "informational", "evidence_basis": "inferred",
                  "summary": "PQC key exchange not available on this Gaia release."}]},
            {"subject_id": "cp-002", "subject_label": "Configuration subject cp-002",
             "vendor_key": "check_point", "status": "FINDING",
             "facts": {"tls_service_profiles": [{"name": "gaia-portal", "min_version": "tls1-0"}]},
             "findings": [
                 {"rule_id": "tls_min_version", "category": "crypto_agility", "status": "FINDING",
                  "severity": "high", "evidence_basis": "configured",
                  "summary": "Gaia portal TLS minimum version is 1.0."},
                 {"rule_id": "ssh_no_cbc", "category": "weak_algorithm", "status": "FINDING",
                  "severity": "high", "evidence_basis": "configured",
                  "summary": "SSH management still offers a CBC cipher."}]},
        ],
        "fleet": {"subjects": 3, "evaluated_subjects": 3,
                  "status_counts": {"PASS": 1, "FINDING": 2},
                  "category_counts": {"weak_algorithm": 4, "crypto_agility": 3, "pqc_readiness": 2}},
        "pqc": {"status": "INFORMATIONAL", "platform_capability": []},
        "privacy": {"contains_secrets": False, "contains_key_material": False,
                    "contains_certificate_content": False, "contains_real_identity": False},
    }


# --------------------------------------------------------------------------
# discovery_ui
# --------------------------------------------------------------------------

def discovery_ui():
    # discovery_fixture_shape_drift fix: field names below match the REAL
    # utils.discovery_capability_ui._entity_row() / _coordinator_section() /
    # _scheduler_section() output exactly (canonical_id/shell_type/
    # planned_mode/plan_allowed/plan_reason_code for entities;
    # workflow_scope/provenance/reason for jobs; workflow/interval_minutes
    # for workflows) -- NOT hand-guessed key names. Previously this fixture
    # used entity_id/collection_mode/deferred/last_transition_reason and
    # scope/started_at/finished_at for jobs, silently rendering `undefined`
    # for Shell/Planned mode/Allowed/Reason and every job-table column since
    # 0.7.6, because render_uitest.py injects this file directly and bypasses
    # the real builder entirely -- see project/backlog.json's
    # discovery_fixture_shape_drift entry.
    platform_labels = {"gaia_embedded": "Quantum Spark / Gaia Embedded", "gaia": "Gaia",
                        "unknown": "Check Point platform"}

    def ent(canonical_id, vendor, lifecycle_state, *, shell_type, confidence, capability_confidence,
            planned_mode, plan_allowed, plan_reason_code, standby_member=None, platform_family=None,
            evidence_plane="direct", transition_reason="direct_collection_ok"):
        return {
            "vendor": vendor,
            "canonical_id": canonical_id,
            "lifecycle_state": lifecycle_state,
            "confidence": confidence,
            "evidence_plane": evidence_plane,
            "last_observed": _ISO,
            "transition_reason": transition_reason,
            "shell_type": shell_type,
            "capability_confidence": capability_confidence,
            "standby_member": standby_member,
            # cp_unknown_platform (0.6.1C): platform identity is independent
            # of collection capability. cp-core-02 (unknown, plan_allowed
            # False via standby) and vsx-gw-01 / VS-WEB (unknown, plan_allowed
            # True via vsx_vsenv_capable) are the deliberate contrast pair --
            # same "unknown" platform family, one collecting, one not,
            # proving platform family never gates the plan.
            "platform_family": platform_family,
            "platform_confidence": ({"gaia_embedded": "HIGH", "gaia": "MEDIUM", "unknown": "LOW"}.get(platform_family)
                                     if platform_family else None),
            "platform_label": platform_labels.get(platform_family),
            "planned_mode": planned_mode,
            "plan_allowed": plan_allowed,
            "plan_reason_code": plan_reason_code,
            "plan_notes": [],
        }

    return {
        "schema_version": "0.6.1C", "generated_at": _ISO,
        "fleet_summary": {"total_entities": 6, "deferred_count": 2,
                          "lifecycle_state_counts": {"STABLE": 3, "VALIDATED": 2, "DISCOVERED": 1},
                          "vendor_counts": {"checkpoint": 4, "paloalto": 2}},
        "entities": [
            ent("cp-edge-01", "checkpoint", "STABLE", shell_type="expert", confidence=93,
                capability_confidence=90, planned_mode="expert_explicit_clish", plan_allowed=True,
                plan_reason_code="shell_expert_confirmed", standby_member=False, platform_family="gaia",
                transition_reason="multi_cycle_stable"),
            ent("cp-core-01", "checkpoint", "STABLE", shell_type="expert", confidence=90,
                capability_confidence=88, planned_mode="expert_explicit_clish", plan_allowed=True,
                plan_reason_code="shell_expert_confirmed", standby_member=False, platform_family="gaia_embedded",
                transition_reason="multi_cycle_stable"),
            # Deferred #1: a confirmed ClusterXL/VRRP standby member (real
            # plan_collection() rule 4) -- avoids an unnecessary login before
            # HA role is confirmed.
            ent("cp-core-02", "checkpoint", "VALIDATED", shell_type="expert", confidence=68,
                capability_confidence=60, planned_mode="deferred_standby", plan_allowed=False,
                plan_reason_code="standby_member", standby_member=True, platform_family="unknown"),
            ent("vsx-gw-01 / VS-WEB", "checkpoint", "VALIDATED", shell_type="expert", confidence=74,
                capability_confidence=70, planned_mode="vsx_vsenv", plan_allowed=True,
                plan_reason_code="vsx_vsenv_capable", standby_member=False, platform_family="unknown"),
            ent("pan-edge-01", "paloalto", "STABLE", shell_type="unknown", confidence=88,
                capability_confidence=85, planned_mode="pan_api", plan_allowed=True,
                plan_reason_code="pan_api_capable", transition_reason="multi_cycle_stable"),
            # Deferred #2: a newly discovered device with prior identity-gate
            # failure history (real plan_collection() rule 3) -- deferred
            # until re-validated, not yet a capability/platform judgement.
            ent("pan-mvha-02", "paloalto", "DISCOVERED", shell_type="unknown", confidence=40,
                capability_confidence=20, planned_mode="unknown", plan_allowed=False,
                plan_reason_code="identity_failure_history", evidence_plane="management",
                transition_reason="identity_failure"),
        ],
        "coordinator": {"available": True, "active_job_count": 0,
                        "budgets": {"checkpoint": 1, "checkpoint_vsx": 1, "paloalto": 1},
                        "recent_jobs": [
                            {"job_id": "job-uitest-1", "vendor": "checkpoint", "workflow_scope": "inventory",
                             "provenance": "manual", "status": "completed", "created_at": _ISO,
                             "admitted_at": _ISO, "completed_at": _ISO, "coalesced_to": None, "reason": None},
                            {"job_id": "job-uitest-2", "vendor": "paloalto", "workflow_scope": "inventory",
                             "provenance": "scheduled", "status": "failed", "created_at": _ISO,
                             "admitted_at": _ISO, "completed_at": _ISO, "coalesced_to": None,
                             "reason": "connect_timeout"}]},
        "scheduler": {"configured": True, "enabled": False, "workflow_count": 1,
                      "workflows": [{"workflow": "checkpoint", "interval_minutes": 1440}]},
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
        "platform_family_labels": platform_labels,
    }


# --------------------------------------------------------------------------
# compliance runtime state
# --------------------------------------------------------------------------

def compliance_checks():
    fw = [{"framework": "CIS", "reference": "5.1", "applies": True},
          {"framework": "PCI-DSS", "reference": "1.2.1", "applies": True},
          {"framework": "BDDK", "reference": "Ağ Güvenliği", "applies": True}]
    return {
        "version": 1, "pack_id": "uitest.local", "pack_version": "1",
        "checks": [
            {"id": "x_ssh_no_cbc", "title": "SSH management offers no CBC ciphers",
             "rationale": "CBC-mode SSH ciphers are plaintext-recovery vulnerable.",
             "severity": "high", "applies_to": {"vendor": ["check_point"]}, "frameworks": fw,
             "evidence": {"combine": "all", "steps": [
                 {"source": "current_configuration.sections[id=management].settings",
                  "select": "value", "assert": {"op": "none_match", "pattern": "(?i)-cbc"}}]},
             "verdict": {"on_pass": "PASS", "on_fail": "FINDING", "on_no_evidence": "UNKNOWN"}},
            {"id": "x_ntp_configured", "title": "At least one NTP server is configured",
             "rationale": "Time sync underpins log correlation and cert validation.",
             "severity": "medium", "applies_to": {}, "frameworks": fw,
             "evidence": {"steps": [
                 {"source": "current_configuration.sections[id=ntp].settings", "select": "value",
                  "assert": {"op": "present"}}]},
             "verdict": {"on_pass": "PASS", "on_fail": "FINDING", "on_no_evidence": "UNKNOWN"}},
            {"id": "x_min_two_interfaces", "title": "Device exposes at least two interfaces",
             "rationale": "A single-interface production firewall is usually a discovery gap.",
             "severity": "low", "mode": "advisory", "applies_to": {}, "frameworks": fw,
             "evidence": {"steps": [
                 {"source": "unified.interfaces", "assert": {"op": "count_gte", "value": 2}}]},
             "verdict": {"on_pass": "PASS", "on_fail": "FINDING", "on_no_evidence": "UNKNOWN"}},
        ],
    }


def control_assignments():
    return {
        "version": 1,
        "waivers": [
            {"control_id": "login_banner_text_present", "device_name": "cp-edge-01",
             "reason": "legacy jump host - banner change scheduled Q4", "approver": "netsec-lead",
             "expires": "2099-01-01"},
        ],
    }


def inventory_exclusions():
    # inventory_exclusions_ui (0.6.1C Inventory UX, phase 1): unlike
    # discovery_ui / configuration_ui / crypto_ui, this one is NOT injected
    # via a monkeypatched builder in render_uitest.py -- it is read for real
    # by the real load_inventory_exclusions() + build_inventory_exclusions_
    # payload(), the same as build_compliance_posture. Fake, vendor-neutral
    # identities only.
    return {
        "version": 1,
        "exclusions": [
            {"vendor": "checkpoint", "identity": "cp-decoy-jumphost-01", "reason": "not a firewall"},
            {"vendor": "checkpoint", "identity": "cp-lab-standby-09", "reason": "lab device, out of scope"},
            {"vendor": "paloalto", "identity": "pan-retired-fw-02", "reason": "decommissioned"},
        ],
    }


def compliance_history():
    base = datetime(2026, 6, 1, tzinfo=timezone.utc)
    rows = []
    for i, aligned in enumerate((68.0, 74.5, 81.0)):
        at = (base + timedelta(days=30 * i)).strftime("%Y-%m-%dT%H:%M:%SZ")
        rows.append({
            "run_id": f"uitest-{i+1}", "collected_at": at,
            "compliance_schema_version": "0.6.6B", "catalog_version": "0.7.2",
            "framework_catalog_version": "0.7.4",
            "cells": {"aligned": 10 + i * 2, "finding": 4 - i, "unknown": 3, "planned": 1, "waived": 1},
            "aligned_percent": aligned, "risk_weighted_alignment_percent": round(aligned - 3.0, 1),
            "monitored_controls": 14 + i, "total_controls": 20, "subjects": 12,
            "by_framework": {"CIS": {"aligned": 5 + i, "finding": 2, "coverage": "PARTIALLY_COVERED"},
                             "PCI-DSS": {"aligned": 4 + i, "finding": 1, "coverage": "COVERED"},
                             "BDDK": {"aligned": 2, "finding": 2, "coverage": "UNCOVERED"}},
        })
    return {"schema_version": "0.7.5", "updated_at": rows[-1]["collected_at"], "records": rows}


def main():
    (HERE / "state").mkdir(parents=True, exist_ok=True)
    writes = {
        "unified.json": unified(),
        "configuration_ui.json": configuration_ui(),
        "crypto_ui.json": crypto_ui(),
        "discovery_ui.json": discovery_ui(),
        "state/compliance_checks.json": compliance_checks(),
        "state/control_assignments.json": control_assignments(),
        "state/compliance_history.json": compliance_history(),
        "state/inventory_exclusions.json": inventory_exclusions(),
    }
    for rel, payload in writes.items():
        path = HERE / rel
        path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {rel} ({path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
