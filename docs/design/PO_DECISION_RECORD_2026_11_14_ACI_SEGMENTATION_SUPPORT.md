# PO decision record -- 2026-11-14: ACI segmentation support, answers to the design questions

## Status

RATIFIED -- PO answers given on 2026-11-14 and relayed by the PO session, quoted below. Recorded by the engineering
session that wrote `ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md` (DRAFT). These answers authorize step 1 of that
design's phase plan (pre-cutover impact analysis on stored policies and uploaded logs, no new reads). They approve no
device, APIC or other API command.

## Decisions (PO's words quoted)

1. **Q2, segment record: "Ayrı kayıt".** A cutover segment is its own record, separate from zone-matrix zones.
   Zones may later be an import source for a segment's blocks; a segment never edits a zone.
2. **Q5, first hours after a cutover: "Önce loglar".** Upload-based evidence comes first. Counter reads on target
   firewalls (design 5.3, path 2b) are decided after one real cutover has been watched with uploads; no counter
   literal is proposed before that.
3. **Q8, Cisco ASA and FortiGate as redirect targets: "Evet, ASA/Forti de var".** ASA and FortiGate firewalls are
   redirect targets too. They are already enrolled. Their policy collection is built in a separate lane
   (`asa-forti-policy`). Until it lands, the pre-cutover analysis treats them as `NOT_EVALUABLE`
   (`VENDOR_POLICY_NOT_COLLECTED`) for every flow; afterwards they are evaluated through the same lookup interface
   (the analysis's flow-policy seam), without a second engine.
4. **Q6, reframed by the PO.** The APIC is not about ASA. The APIC, and also the Citrix ADC and F5 load balancers,
   are **topology sources only**: neXus does not manage them and does not inventory them; it only reads routing, IP
   and VIP tables to draw paths. That work is a separate lane (`topology-sources`). The read list R0-R13 of the design
   is therefore input to that lane's own proposal, not approved here; every literal still needs the PO's exact-literal
   approval before it runs.
5. **Q3, Q4, Q7, Q9, Q10: the design's recommendations stand** (the PO did not object):
   - Q3: the network team enters the Palo Alto zones of the redirected interfaces until a topology source can derive
     them.
   - Q4: old-path traffic-log exports are uploaded by an authorized operator inside neXus; the first vendor preset
     follows whichever sanitized sample arrives first.
   - Q7: 90 days for flows and watch records; verdicts kept with their segment.
   - Q9: Trace results for addresses in a redirected segment are to carry "fabric redirect not modelled" (design step
     2a).
   - Q10: a vendor-semantics freeze for ACI PBR gates the Trace redirect overlay.

6. **Target firewall from path computation (PO clarification, relayed the same day): "ACI may send traffic
   anywhere; the next hop comes from IP, routes and contracts".** There is no fixed list of target firewalls. In
   step 1 the firewall a segment's flow crosses comes from path computation over the held topology and stored
   policies (routes today; the topology-sources overlay of contracts and service graph / PBR later). An
   operator-supplied target is only an explicit override and is labelled `OPERATOR_OVERRIDE` on every result.
7. **The step 1 screen is built by Carbon (Antigravity), not Fable**, from the backend's UI brief.

## Not decided by this record

- Q1 (start with step 1) is implied by the instruction to implement step 1 now; the choice of the first real
  phase-2 segment is the network team's.
- Retention enforcement (Q7) is not implemented in step 1; it is a follow-up.
- Anything in the design's sections 5.3 (2b, 2c) and 6 beyond what decision 4 says.
