# ASA / FortiGate policy shape measurement (step 0)

Counts-only measurement over the stored ASA `more system:running-config` and FortiGate `show` configuration artefacts
(PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md items 1-2). Result:
`docs/design/ASA_FGT_POLICY_SHAPE_MEASUREMENT_2026_11_14.md`.

Run (engineer, outside ship/e2e windows; no device contact):

    SHAPE_WORK_DIR=<empty scratch dir> bash scripts/measurement/asa_fgt_policy_shape/measure_run.sh <tag>

The script copies the pod's `service.jar`, compiles `AsaFgtShapeMeasure.java` against it, stages it in
`ui2-service:/app/tmp`, runs it, prints only `show_safe.sh`-filtered lines (`[a-z0-9_.]+` per field) into
`shape-<tag>.tsv` and removes the staged directory. Devices and VDOMs are ordinals; names, addresses and configuration
text never leave the JVM. `cisco_port_literals.tsv` / `cisco_protocol_literals.tsv` are Cisco's Table 7 / Table 6
(ASA 9.22 "Addresses, Protocols, and Ports", read 2026-11-14); a port word is emitted only when it is in Table 7.
