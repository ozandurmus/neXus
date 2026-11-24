# MEASUREMENT RECORD: SNMP M0 reachability (2026-11-24)

Status: RECORDED. Design: `SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md` section 8.

**Approval.** The PO approved in chat on 2026-11-24 ("Yes, approve M0", answering the question that named the exact
literal):
- install net-snmp on HOST-A;
- run the literal once per device, on one enrolled gateway per vendor.

**Tool.** `apt-get install snmp` on HOST-A installed NET-SNMP 5.9.4.

**Command.** Run once per device from the HOST-A host network, with no credential:

    snmpget -v3 -l noAuthNoPriv -u overwatch-probe -r 0 -t 3 <management address> 1.3.6.1.2.1.1.3.0

**Target selection.** For each vendor, the enrolled, not disabled gateway with the lowest opaque device id that has an
endpoint. The address was read from `endpoints.address_ref` inside the measurement script and was never printed.

**Result.** Answer kinds only:

| Vendor | Answer | Time |
|---|---|---|
| Check Point | agent answered: unknown user (SNMPv3 report) | 12 ms |
| Palo Alto | agent answered: unknown user (SNMPv3 report) | 19 ms |
| Cisco ASA | no answer (timeout, 3 s) | 3.0 s |
| FortiGate | no answer (timeout, 3 s) | 3.0 s |

**Reading.**
- For Check Point and Palo Alto, UDP 161 is open from HOST-A and the SNMPv3 agent is running. Only a v3 user is missing
  (design section 3, item 1).
- For Cisco ASA and FortiGate the cause is UNKNOWN. It could be:
  - no network path;
  - the SNMP agent is off;
  - the agent accepts only listed manager addresses (`snmp-server host` on ASA, trusted hosts on FortiGate).

  One device per vendor was measured, so this is not a fleet result.

**Next.** M1 (design section 8) after the bank creates SNMPv3 users. For ASA and FortiGate, the network team checks the
path and the SNMP manager allow-list for HOST-A's address.
