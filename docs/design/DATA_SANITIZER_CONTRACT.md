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
| pcap / pcapng | Same capture structure (frames, timing, lengths, TCP/UDP/ICMP headers, flags, seq/ack, IP IDs kept). Addresses rewritten (IPv4, IPv6, ARP, IPv4 header options and IPv6 extension header addresses, inner tunnel headers where decoded; unparseable or unknown IPv4/IPv6 options zeroed), checksums recomputed, **payload removed**: bytes after the L4 header zeroed (captured length kept, so loss/latency analysis is unaffected). DNS names in DNS packets (including compression targets) replaced by their pseudonyms, or the DNS payload zeroed fail-closed if unparseable. |
| Text logs (any line format) | Every IPv4/IPv6 address and CIDR, MAC, FQDN/hostname, e-mail and URL host replaced in place; the rest of the line untouched. |
| CSV / XLSX | Cell by cell with the text rules; sheet structure, formulas' literal values and formatting kept. In XLSX, VML comment/drawing text (`.vml`) is sanitized with the text rules, and embedded binary objects (OLE packages, images, printer settings, and other non-XML binary parts that cannot be inspected safely) are **removed** from the output archive and counted under `embedded_objects_removed` in the job summary. |
| JSON | Object keys and string values sanitized with the text rules; structure kept; revert restores both keys and values. |

## 2. Mapping rules (deterministic, keyed, one-way)

- **Key:** the product's existing privacy HMAC key (`ui2-privacy-hmac-key`, `HmacKeyProvider`) — the same key the aiview
  masking uses, so a device's pseudonym is identical in the UI, in exports and in sanitized files.
- **IP addresses:** prefix-preserving keyed permutation (Crypto-PAn construction with HMAC-SHA256 as the PRF), same
  address family. Two addresses sharing an n-bit prefix map to addresses sharing exactly an n-bit prefix, so subnets,
  routes and "same /24" relationships survive without leaking original high-order prefix bits. Loopback, unspecified,
  multicast and limited broadcast stay as they are.
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
  ledgered like other retention; failed or interrupted jobs delete their vault entries and wrapped data key immediately
  (ledgered as `VAULT_PRUNED`).
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

## 5. Implementation record (2026-10-16, branch `feature/data-sanitizer`)

Where the code lives and the choices made inside this contract's latitude. Nothing below widens §1–§4.

- **Module `ui2/sanitizer`** (plain Java; `SanitizerModuleFenceTest` keeps it free of other modules, Spring, JDBC
  and network APIs): `core` (key derivation, `IpPermutation`, `MacMapper`, `NameMapper`, `PublicSuffixes`,
  `TextSanitizer`, `TextReverter`), `format` (content detection, text/CSV/JSON/XLSX streaming handlers), `pcap`
  (native header decoder, PacketBreaker port, neXus mode, DNS rewriter, vault revert), and the `Sanitizer` facade.
- **Key.** One sub-key per family from the privacy key: `HMAC(key, "nexus-sanitizer/v1/" + family)` for `ip`,
  `mac`, `name`, `dns`, `vault`.
- **IP.** PacketBreaker's Crypto-PAn message (`"ip" || len || n || prefix`) under the `ip` sub-key. Kept point for
  point: `0.0.0.0/8`, `127/8`, `224/4`, `240/4` (covers limited broadcast and netmasks), `::`, `::1`, `ff00::/8`;
  kept as a block with the interior permuted: `169.254/16`, `fe80::/10`, `::ffff:0:0/96` (interior = the IPv4 map).
  In the neXus mode, top-prefix cycle walking permutes all non-reserved unicast prefixes across the full unicast space
  without fixing leading bits, so only prefix relationships (shared prefix length) are preserved while first-octet
  distributions are uniform across non-reserved `/8`s.
  The contiguous /1 and /2 netmasks (outside the kept ranges) are kept as text. A network (host bits zero) is one token
  (`a.b.c.0/24`); an interface address with a prefix maps the address and keeps the length.
- **Names.** `h-<10 hex>` of the lower-case punycode label (so a Unicode label and its punycode form agree); an
  all-upper label gives `H-<10 HEX>` with the same digest, a mixed-case label a case-exact digest, so revert is
  byte-exact. Public suffixes come from the JDK's own copy of the Public Suffix List (ICANN rules) plus special-use
  and common internal suffixes (`test`, `local`, `internal`, `corp`, …). Two-label names whose TLD is an unambiguous file
  extension (`setup.py`, `notes.md`, `archive.zip`) are not names. Single-label hosts are replaced after host keywords (`hostname`,
  `devname=`, `host=`, …), in RFC 3164/5424 headers, in `user@host`, in URLs, and under host-like CSV/XLSX headers or
  JSON keys (`hostname`, `device`, `server`, `cihaz`, …); a name learned there is replaced wherever it reappears in
  the same file. Known inventory names use the `TopologyNamePseudonymizer` pseudonym.
- **pcap.** The PacketBreaker modes run on a native decoder of exactly its header set (no tshark in the service
  image); parity: all 80 committed reference cases (`pcap-parity/manifest.json`, regenerated by
  `tools/sanitizer/pcap_parity_reference.py`) and the 64 scenario cases are byte-identical. The neXus mode never
  refuses a frame (non-first fragments, non-IP frames and unknown link types are zeroed after the last decoded
  header), maps addresses in IPv4 options (Record Route, Loose/Strict Source Route, Internet Timestamp) and IPv6
  extension headers (Routing Header, Destination/Hop-by-Hop Home Address option) while zeroing unparseable or unknown
  options, and replaces DNS names (including valid compression targets) by **same-length** label pseudonyms
  (`[a-z0-9]`, case per position) or zeroes the DNS payload fail-closed if any compression target or record is
  malformed.
- **XLSX.** POI's `ZipSecureFile` (zip-bomb limits, plus package-wide caps of 4,096 entries and 2 GiB uncompressed)
  opens the package; XML, `.rels`, and `.vml` drawing parts are rewritten through StAX (shared/inline strings, cached
  formula strings, formula string literals, defined names, comments, VML notes/textboxes, chart and pivot caches,
  document properties, external hyperlink/relationship targets, table column names). Non-XML binary parts (embedded OLE
  objects, images, printer settings) are removed from the output archive and counted as `embedded_objects_removed`.
- **Vault.** V156: `sanitize_job` (counts only; the per-job AES-256 data key wrapped by the artefact-store master
  key), `sanitize_vault_entry` (`lookup_key = HMAC(vault sub-key, kind || 0x00 || canonical token)`,
  `sealed_original = AES-256-GCM` with job id and lookup key as associated data), `sanitize_revert_job`,
  `sanitize_ledger`. The vault's audit trigger is statement-level (one `audit_log` row per batch and job with the
  entry count). Expiry (and immediate cleanup on failed or interrupted jobs) deletes the entries, clears the wrapped
  key and ledgers `VAULT_PRUNED`.
- **Revert scope.** `data_sanitize_revert` admits operator, operation_admin and security_admin; the service limits a
  revert to the vaults of the actor's own `SUCCEEDED` jobs (or one named job the actor owns); only security_admin may
  revert against every vault or another user's job.
- **Temporary files.** `ui2.sanitizer.work-dir` (default under the JVM temporary directory, created with `0700`
  permissions; temporary `.in` and `.out` files created with `0600` permissions): the input is deleted when the job
  ends, any partial output is deleted on every failure path, and the result is deleted after its single download or
  `output-ttl-minutes` (60); a service start empties the directory and closes interrupted jobs.
- **Hook points (off by default, `SanitizerHooks`).** Packet Analysis "anonymize on upload"
  (`ui2.sanitizer.packet-analysis-anonymize-on-upload`): the upload path calls
  `SanitizerService.sanitizeFile(input, output, actor)` on the streamed capture before storing it and stores the
  output instead. System-log export (`ui2.sanitizer.log-export`): the export streams each file through
  `SanitizerService.sanitizeLogExport(in, out, actor)`. Both record a vault job of the actor, so the files can be
  reverted here later.
