# Data Sanitizer — product-wide anonymization of pcaps, logs and tables

## Status

**DECIDED — implementation authority**, under the Product Owner's delegation of 2026-10-15 23:55 ("tüm kararları
verip ... uygula"). Product Owner request, 2026-10-15: the anonymization used for pcaps and logs must be a product
feature usable everywhere — "log mu verdim anonimize etsin, pcap mi koydum anonimize etsin", for taking logs out of the
product later, inside the Packet Analysis analysis, at pcap upload; "kesip biçme değil" (no truncation): for pcaps no one
needs the payload; IPs and names standardized with HMAC; example: an Excel with IPs and hostnames goes through the
product and comes out sanitized.

## 1. What it does

One engine, four input kinds, one consistent mapping:

| Input | Output |
|---|---|
| pcap / pcapng | Same capture structure (frames, timing, lengths, TCP/UDP/ICMP headers, flags, seq/ack, IP IDs kept). Addresses rewritten (IPv4, IPv6, ARP, inner tunnel headers where decoded), checksums recomputed, **payload removed**: bytes after the L4 header zeroed (captured length kept, so loss/latency analysis is unaffected). DNS names in DNS packets replaced by their pseudonyms. |
| Text logs (any line format) | Every IPv4/IPv6 address and CIDR, MAC, FQDN/hostname, e-mail and URL host replaced in place; the rest of the line untouched. |
| CSV / XLSX | Cell by cell with the text rules; sheet structure, formulas' literal values and formatting kept. |
| JSON | String values with the text rules; keys and structure kept. |

## 2. Mapping rules (deterministic, keyed, one-way)

- **Key:** the product's existing privacy HMAC key (`ui2-privacy-hmac-key`, `HmacKeyProvider`) — the same key the aiview
  masking uses, so a device's pseudonym is identical in the UI, in exports and in sanitized files.
- **IP addresses:** prefix-preserving keyed permutation (Crypto-PAn construction with HMAC-SHA256 as the PRF), same
  address family. Two addresses sharing an n-bit prefix map to addresses sharing exactly an n-bit prefix, so subnets,
  routes and "same /24" relationships survive. Loopback, unspecified, multicast and limited broadcast stay as they are.
- **Known device names** (neXus inventory): the existing `TopologyNamePseudonymizer` pseudonym (`FW-TANGO-04`).
- **Other hostnames / FQDNs:** each label replaced by `h-<10 hex>` from HMAC of the label (public suffix kept), so
  `a.b.example.com`-style hierarchy survives; same label -> same token everywhere.
- **MAC:** OUI kept (vendor stays visible), NIC half from HMAC.
- **E-mail:** local part and domain labels by the hostname rule.
- **Reversible on request (PO 2026-10-16):** "milyon IP hostname ... sanitize oldu, cloudda ... AI tooluna ... geri
  geldi, tekrar koyup eski haline revert edebilmeliyim ... veri çıkışı olmadan". Every token a sanitize job emits is
  recorded in a **mapping vault** inside neXus (token -> original, encrypted at rest with the artefact-store envelope
  key; originals never leave neXus). A **revert** job takes any file that came back (text, CSV, XLSX, JSON, pcap) and
  replaces every vault token it finds with its original — also inside new text written by the external tool around
  the tokens. Only tokens present in the vault are reverted, so an IP the external tool invented is never mapped to a
  real address. Name tokens use a recognizable form (`h-<10 hex>`, device pseudonyms like `FW-TANGO-04`) so they survive
  editing; IP tokens are the prefix-preserved addresses and are reverted by exact vault lookup.
- **Who:** sanitize — operator, operation_admin, security_admin (`DATA_SANITIZE_RUN`); revert —
  security_admin and the user who ran the sanitize job (`DATA_SANITIZE_REVERT`); aiview neither. Every sanitize and
  revert is audited with counts only. Vault entries expire after 90 days by default (configurable), pruned and
  ledgered like other retention.
- A sanitize job's summary reports counts per type, never originals.

## 3. Where it is used

1. **Sanitize / revert screen + API + CLI:** upload a file (pcap/pcapng, log/txt, csv, xlsx, json; 2 GB limit, type by content),
   download the sanitized file plus a summary (counts per type). Input processed in a temporary area and deleted when
   the job ends; never retained. Action `DATA_SANITIZE_RUN` (operator, operation_admin, security_admin); audited.
2. **Packet Analysis upload:** option "anonymize on upload" runs the pcap path before the capture is stored.
3. **Log export (system-log programme):** exported logs go through the text path.
4. **Engine reuse:** the Packet Analysis engine may run on sanitized captures with no code change, because structure,
   timing and header fields are kept.

## 4. Implementation

- New plain-Java Gradle module `ui2/sanitizer` (detectors, mappers, format handlers), used by service and worker.
- pcap path: the PacketBreaker anonymizer (`anonymize.py`, frozen reference 5b608e6) is ported with byte-identical
  output on its own test fixtures for its existing modes; the HMAC prefix-preserving mapping and DNS-name rewriting are
  neXus extensions on top, tested separately.
- XLSX via Apache POI pinned in the version catalog (shared with the Packet Analysis export lane).
- Tests: detectors on adversarial strings (IPv4-mapped IPv6, zone ids, ports, CIDRs, version strings that look like IPs
  — `1.2.3.4` inside `v1.2.3.4` stays), determinism, prefix preservation property tests, round-trip structure checks
  for every format, and "no original value survives" scans of every output.
