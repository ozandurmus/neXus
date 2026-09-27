const VENDORS: Record<string, string> = {
  cp: "Check Point", mds: "Check Point MDS", pan: "Palo Alto", fgt: "FortiGate", fmg: "FortiManager", asa: "Cisco ASA",
  rdw_cc: "Radware Cyber Controller", rdw: "Radware", bcmc: "Symantec", proxysg: "Symantec",
  ib: "Infoblox", infoblox: "Infoblox", pulse: "Pulse Secure",
  check_point: "Check Point", palo_alto: "Palo Alto", fortinet: "FortiGate", fortimanager: "FortiManager", cisco_asa: "Cisco ASA",
};

export function jobTypeLabel(capabilityId: string, vendorHint?: string): string {
  const confirmVendor = capabilityId.startsWith("device_confirm_")
    ? ({ fortigate: "FortiGate", https: undefined } as Record<string, string | undefined>)[capabilityId.slice("device_confirm_".length)] ?? VENDORS[capabilityId.slice("device_confirm_".length)]
    : undefined;
  const vendor = VENDORS[vendorHint ?? ""] ?? confirmVendor
    ?? (capabilityId.startsWith("cp_mds_") ? VENDORS.mds : undefined)
    ?? Object.entries(VENDORS).find(([prefix]) => capabilityId.startsWith(`${prefix}_`))?.[1];
  const action = capabilityId.endsWith("_inventory_collect") ? "read inventory"
    : capabilityId.endsWith("_configuration_collect") ? "read configuration"
      : /_(config|gateway|device_state|gaia|vendor)_backup$/.test(capabilityId) || capabilityId.endsWith("_snapshot") || capabilityId.endsWith("_export") || capabilityId === "pan_set_config_read" ? "backup"
        : capabilityId.startsWith("device_confirm_") ? "identity check"
          : capabilityId.endsWith("_discovery_enumerate") ? "discovery"
            : capabilityId.includes("diagnostic") ? "diagnostic read" : undefined;
  return action ? (vendor ? `${vendor} · ${action}` : action) : capabilityId;
}
