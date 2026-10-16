# ARGON Independent Security Review — Data Sanitizer

- **Status:** `READ_ONLY_AUDIT` (Independent Adversarial Security Review — Do Not Freeze As Implementation Contract)
- **Date:** 2026-10-16
- **Branch / Commit Reviewed:** `review/argon-sanitizer` (`feature/data-sanitizer` @ `838e1807`)
- **Scope:** `docs/design/DATA_SANITIZER_CONTRACT.md`, `ui2/sanitizer/**`, `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/sanitizer/**`, `ui2/service/src/main/resources/db/migration/V156__data_sanitizer_vault.sql`, `ui2/cli/src/main/java/com/securityexpert/nexus/ui2/cli/SanitizerCli.java`, `ui2/frontend/src/screens/SanitizerScreen.tsx`
- **Reviewer Role:** `REVIEWER` (Argon independent security review)

---

## Executive Summary

The Data Sanitizer (`ui2/sanitizer` + `ui2/service/.../sanitizer` + `V156__data_sanitizer_vault.sql`) has a strong architectural foundation:
- The module fence (`SanitizerModuleFenceTest`) cleanly isolates `com.securityexpert.nexus.ui2.sanitizer` from Spring, JDBI, and network clients.
- `SanitizerKey` enforces domain-separated HMAC-SHA256 sub-key derivation (`"nexus-sanitizer/v1/" + family`) across `"ip"`, `"mac"`, `"name"`, `"dns"`, and `"vault"`.
- The reversing vault (`VaultCrypto` + `V156__data_sanitizer_vault.sql`) uses per-job AES-256-GCM data keys wrapped with HKDF-SHA256 (`"nexus-sanitizer/v1/vault-kek"`) and binds each ciphertext via AAD to `jobId | 0x00 | kind | 0x00 | lookupKey`.
- Audit events, error logs, and `SanitizerRepository` SQL parameters record only counts, job metadata, and encrypted blobs—never raw originals or replacement tokens.

However, an adversarial code audit across the five review dimensions uncovered **21 concrete security and reliability findings** (**7 High**, **9 Medium**, **5 Low**):

| ID | Category | Severity | Title | Location |
|---|---|---|---|---|
| **F-01** | 1. Leaks | **HIGH** | JSON object keys are emitted verbatim without sanitization | `JsonFormat.java:188-189` |
| **F-02** | 1. Leaks | **HIGH** | Non-XML XLSX package parts (including `.vml` legacy notes/comments and embedded objects) are copied verbatim | `XlsxFormat.java:115-123` |
| **F-03** | 1. Leaks | **MEDIUM** | Temporary `.out` files and `workDir` lack restrictive POSIX `0600`/`0700` permissions, and failed synchronous `sanitizeFile` leaves partial output on disk | `Sanitizer.java:85`, `SanitizerService.java:117,147,263-266,314` |
| **F-04** | 1. Leaks | **MEDIUM** | Failed or interrupted sanitize jobs retain unpruned vault rows and wrapped DEKs in PostgreSQL for 90 days and remain queryable in `ScopedResolver` | `SanitizerService.java:155-158`, `SanitizerRepository.java:170-183,224-234` |
| **F-05** | 1. Leaks | **LOW** | `TextFormat` 1 MiB chunk splitter cuts mid-token when a long line has no whitespace in its upper half | `TextFormat.java:81-86,109-114` |
| **F-06** | 2. IP Permutation | **HIGH** | `IpPermutation.forced()` prefix-preservation logic leaks high-order prefix bits of every IPv4 and IPv6 address (`10/8` always maps into `8/5`; `192.168/16` into `192/3`; `2000::/3` into `2000::/3`) | `IpPermutation.java:34-61,142,159-168` |
| **F-07** | 2. IP Permutation | **LOW** | Mixed-case DNS labels hash `"label-exact:" + label` instead of lowercase punycode, breaking DNS case-insensitivity correlation and leaking capitalization patterns | `NameMapper.java:41-44` |
| **F-08** | 3. Detector Misses | **HIGH** | PCAP DNS compression pointers into non-name RR bytes (e.g., SOA 20-byte trailer, MX preference, SRV header) bypass in-place label overwriting and leak plaintext hostnames | `DnsRewriter.java:63-69,214-219,245-261` |
| **F-09** | 3. Detector Misses | **HIGH** | PCAP IPv4 header options (Record Route, Loose/Strict Source Route, Internet Timestamp), IPv6 Hop-by-Hop/Destination Options, and non-IPv4 ARP payloads are copied verbatim in cleartext | `Layers.java:172-182,188-190,213-228`, `FrameRewriter.java:306-322` |
| **F-10** | 3. Detector Misses | **MEDIUM** | `FILE_LIKE_TLDS` heuristic suppresses 2-label domains under real ccTLDs (`.ai`, `.rs`, `.pl`, `.md`, `.sh`, `.so`, `.cc`, `.ms`) in free text | `Patterns.java:67-68`, `TextSanitizer.java:335-337` |
| **F-11** | 3. Detector Misses | **MEDIUM** | Hostnames starting with a digit (valid per RFC 1123 §2.1) are missed by `CONTEXT_HOST`, `USER_AT_HOST`, `SYSLOG_3164`, and `SYSLOG_5424`; `CONTEXT_HOST` also fails to `learn()` discovered hostnames | `Patterns.java:39,47,52,53`, `TextSanitizer.java:246` |
| **F-12** | 3. Detector Misses | **MEDIUM** | `urlCandidate` consumes closing `]` on bracketed non-IPv6 URLs without a path (e.g., `[http://fw-tango-04]`), causing single-label URL host sanitization to fail | `TextSanitizer.java:209-210,369-372` |
| **F-13** | 3. Detector Misses | **MEDIUM** | XLSX `SheetPolicy` and `WorkbookPolicy` miss cell types other than `t="str"`/`<is>` (such as `t="e"` error strings, `<text>` in legacy `<commentList>`, and `workbook.xml` external path attributes) | `XlsxFormat.java:108-114,392-397,450-454` |
| **F-14** | 3. Detector Misses | **LOW** | CSV files without a header row fail to sanitize bare hostnames in row 1, and `HostHeaders` only matches exact tokens after stripping non-alphanumerics | `CsvFormat.java:98-106`, `HostHeaders.java:25-27` |
| **F-15** | 3. Detector Misses | **LOW** | BSD/macOS/Solaris `arp -a` single-hex-digit MAC octets (`0:1a:2b:3c:4d:5e`) are not matched by `Patterns.MAC` | `Patterns.java:25-28` |
| **F-16** | 4. Revert Safety | **MEDIUM** | IPv6 addresses with zone identifiers and CIDR prefixes (e.g., `fe80::1%eth0/64`) or hex-formatted IPv4-mapped IPv6 addresses (`::ffff:c000:0201`) fail to revert or lose CIDR suffix | `TextReverter.java:187-210`, `TextSanitizer.java:311-317` |
| **F-17** | 4. Revert Safety | **MEDIUM** | Sub-token resolution in `TextReverter`: an external tool that synthesizes `::ffff:<vault-ipv4>` or `<invented-label>.<vault-fqdn>` partially resolves the embedded vault token | `TextReverter.java:168-210`, `Patterns.java:17` |
| **F-18** | 4. Revert Safety | **LOW** | First-seen style wins per job in `JobVault.recent` deduplication (`on conflict (job_id, lookup_key) do nothing`), causing mixed-format IPv6/MAC occurrences in the same file to revert to the first occurrence's style | `JobVault.java:52`, `SanitizerRepository.java:87` |
| **F-19** | 5. Resource Exhaustion | **HIGH** | `JsonFormat` and `XlsxFormat` suffer unbounded heap accumulation (`pending` list in `JsonFormat`, `items` list in `XlsxFormat`) when a single string is followed by millions of non-string tokens/cells, plus >1.1 GiB allocation for a single 64M-char JSON string | `JsonFormat.java:29,108,260-320`, `XlsxFormat.java:545-549` |
| **F-20** | 5. Resource Exhaustion | **HIGH** | Multi-pass full-file reads on 2 GiB inputs (up to 3 full scans in `Detection` + `JsonFormat.isValid` + `Sanitizer.run`) and unbounded byte buffering in `PcapContainer` (`512 * 64 MiB = 32 GiB` worst case) | `Detection.java:36,46`, `TextEncoding.java:34`, `PcapContainer.java:25-26,165-171` |
| **F-21** | 5. Resource Exhaustion | **MEDIUM** | XLSX zip-bomb / multi-entry amplification: no cap on total uncompressed bytes or entry count across the ZIP archive, and StAX `coalescing=true` allows large text allocations per element | `XlsxFormat.java:86-127,139` |

---

## 1. Leaks (Original Values Surviving in Output, Temp Files, Logs, Audit Trail, or DB)

### Verified Controls
- **Database & Vault Storage (`V156__data_sanitizer_vault.sql:25-60`, `VaultCrypto.java:50-75`, `SanitizerRepository.java:74-96`):** `sanitize_vault_entry` stores only `lookup_key` (32-byte HMAC-SHA256), `nonce` (12-byte random IV), and `ciphertext` (AES-256-GCM encrypted UTF-8 bytes of the original value with AAD `jobId | 0x00 | kind | 0x00 | lookupKey`). Neither plaintext originals nor plaintext tokens are persisted in `sanitize_job` or `sanitize_vault_entry`.
- **Exception & Log Hygiene (`SanitizerService.java:156-163,234-241`, `JsonFormat.java:272,279`, `CsvFormat.java:185`, `XlsxFormat.java:126`, `PcapContainer.java:82`):** Parser exceptions report only byte/character offsets, record indices, or structural reasons (e.g., `"Invalid JSON at offset " + pos`), never echoing input slices. `SanitizerService` logs only `jobId` and records generic failure text `"Sanitization failed; see server log."` in `sanitize_job.error_message`.
- **Audit Trail (`SanitizerService.java:279-301`):** `audit.record()` emits only `jobId`, `mode`, `fileType`, `inputBytes`, `outputBytes`, and category `Counts` (`ipv4`, `ipv6`, `cidr`, `mac`, `hostname`, `fqdn`, `url`, `email`, `dnsPacket`).

### Findings

#### F-01 — JSON object keys are emitted verbatim without sanitization
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/JsonFormat.java:188-189`
- **Severity:** **HIGH**
- **Analysis:** In `JsonFormat.Lexer.object()`, object member keys are parsed via `Decoded key = string()`, and then immediately written out raw via `emit("\"" + key.raw() + "\"");` without passing through `ValueRewriter` / `TextSanitizer`. While `DATA_SANITIZER_CONTRACT.md` §1 states *"JSON: String values with the text rules; keys and structure kept"*, real-world network/firewall diagnostic exports, telemetry dumps, and route tables routinely key JSON maps by IP address, CIDR, MAC address, or FQDN (e.g., `{"192.0.2.10": {"status": "up"}, "fw-tango-04.corp.example.com": {"vsid": 0}}`). Leaving JSON keys unsanitized leaks every device identity used as a map key directly into the sanitized output file.
- **Concrete Failing Input:**
  ```json
  {"peers": {"192.0.2.10": "active", "fw-tango-04.corp.example.com": "standby", "00:1a:2b:3c:4d:5e": "eth0"}}
  ```
  Current output preserves `"192.0.2.10"`, `"fw-tango-04.corp.example.com"`, and `"00:1a:2b:3c:4d:5e"` in cleartext.
- **Fix:** Pass JSON object keys through `TextSanitizer.sanitize(key.value(), false)` (with `hostContext = false` so structural keys like `"hostname"` or `"status"` are never touched, while embedded IPs, CIDRs, MACs, FQDNs, URLs, emails, and previously learned hostnames inside keys ARE sanitized and reversible via `TextReverter`). Update `DATA_SANITIZER_CONTRACT.md` §1 and `RoundTripTest.jsonPreservesFormattingAndEscapes` accordingly.

#### F-02 — Non-XML XLSX package parts (including `.vml` legacy notes/comments and embedded objects) are copied verbatim
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/XlsxFormat.java:115-123`
- **Severity:** **HIGH**
- **Analysis:** `XlsxFormat.process()` decides whether to inspect an archive entry solely via:
  ```java
  if (policy != null || contentType.endsWith("xml") || name.endsWith(".xml") || name.endsWith(".rels")) {
      ...
  } else {
      try (InputStream in = zip.getInputStream(entry)) {
          in.transferTo(zipOut);
      }
  }
  ```
  In OOXML (`.xlsx`), legacy cell notes/comments and shapes are stored in `/xl/drawings/vmlDrawing1.vml` with content type `application/vnd.openxmlformats-officedocument.vmlDrawing`. Neither the entry name (`.vml`) nor the content type (`...vmlDrawing`) ends with `"xml"`, so `XlsxFormat` copies every `.vml` drawing part verbatim into the output archive without sanitization! Because Excel writes legacy comment text and author usernames into both `/xl/comments1.xml` AND `/xl/drawings/vmlDrawing1.vml` (or when a drawing textbox is used), any IP, hostname, or email inside a VML shape/comment survives in plaintext. Furthermore, embedded `.bin` (OLE objects, ActiveX controls, `printerSettings*.bin` containing UNC print-server paths like `\\print01.corp.example.com\hp`), `.txt`, or `.csv` parts packaged inside the `.xlsx` ZIP are copied through untouched.
- **Concrete Failing Input:**
  An `.xlsx` file containing `/xl/drawings/vmlDrawing1.vml` with `[Content_Types].xml` entry `<Default Extension="vml" ContentType="application/vnd.openxmlformats-officedocument.vmlDrawing"/>` and body:
  ```xml
  <xml xmlns:v="urn:schemas-microsoft-com:vml">
    <v:shape><v:textbox><div> Firewall management IP 192.0.2.55 on fw-tango-04.corp.example.com </div></v:textbox></v:shape>
  </xml>
  ```
  `XlsxFormat.process()` copies `/xl/drawings/vmlDrawing1.vml` byte-for-byte into the sanitized `.xlsx` via `in.transferTo(zipOut)`.
- **Fix:**
  1. Treat `.vml` entries (`name.endsWith(".vml") || contentType.endsWith("vmlDrawing")`) as XML/text parts to be rewritten, or strip/redact VML textboxes.
  2. Apply an explicit allowlist of safe binary part types (e.g., raster images `.png`/`.jpeg` if stripped of EXIF, or reject/strip `printerSettings*.bin`, embedded OLE packages `/xl/embeddings/*`, and unknown non-XML parts).

#### F-03 — Temporary `.out` files and `workDir` lack POSIX `0600`/`0700` permissions, and failed synchronous `sanitizeFile` leaves partial output on disk
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/Sanitizer.java:85`, `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/sanitizer/SanitizerService.java:117,147,263-266,314`
- **Severity:** **MEDIUM**
- **Analysis:**
  1. While `SanitizerService.receive()` (`SanitizerService.java:316-320`) creates `<id>.in` with explicit `PosixFilePermissions.fromString("rw-------")`, the work directory itself (`Files.createDirectories(workDir)` at lines 117 and 314—defaulting to `${java.io.tmpdir}/nexus-sanitizer`, i.e., `/tmp/nexus-sanitizer`) and the output file (`Files.newOutputStream(output)` in `Sanitizer.java:85`) are created with the process's default `umask` (commonly `0755` for directories and `0644` for files). On a multi-user host, local processes can list `/tmp/nexus-sanitizer` and read `<id>.out` while a job is running or waiting for download.
  2. In `SanitizerService.sanitizeFile(Path input, Path output, String actor)` (`SanitizerService.java:250-267`), if `Sanitizer.sanitize` or `Sanitizer.revert` throws mid-stream, the `catch (RuntimeException | IOException e)` block marks the job failed and prunes the vault, but **does not delete `output`** (unlike `runSanitize()` at line 156 which calls `deleteQuietly(out)`), leaving a truncated output file on disk.
- **Concrete Failing Input:**
  Run `SanitizerService.startSanitize()` on a POSIX host with standard `umask 0022`: `/tmp/nexus-sanitizer/<jobId>.out` is created with mode `-rw-r--r--` (`0644`). Or invoke `sanitizeFile(inputWithMalformedTail, output, "system")`: when `UnsupportedInputException` is thrown mid-file, partial `output` remains on disk.
- **Fix:** Create `workDir` with `0700` (`rwx------`), pre-create `<id>.out` (or open `OutputStream` in `Sanitizer.java:85`) with `0600` (`rw-------`) on POSIX filesystems, and call `deleteQuietly(output)` inside the `catch` block of `SanitizerService.sanitizeFile()`.

#### F-04 — Failed or interrupted sanitize jobs retain unpruned vault rows and wrapped DEKs in PostgreSQL for 90 days and remain queryable in `ScopedResolver`
- **Location:** `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/sanitizer/SanitizerService.java:155-158`, `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/sanitizer/SanitizerRepository.java:170-183,224-234`
- **Severity:** **MEDIUM**
- **Analysis:**
  During an asynchronous sanitize job (`SanitizerService.runSanitize()`), `JobVault` flushes batches of 500 encrypted original values into `sanitize_vault_entry` as the file streams (`JobVault.java:54`). If the job fails after writing one or more batches (e.g., malformed record at row 10,000) or is interrupted by a service restart (`SanitizerRepository.closeInterrupted()`), `SanitizerRepository.completeFailure()` (`SanitizerRepository.java:99-109`) sets `state = 'FAILED'`, **leaving `wrapped_data_key` non-null and leaving all flushed `sanitize_vault_entry` rows in PostgreSQL** for up to 90 days!
  Furthermore, `SanitizerRepository.findScopedEntries()` (`SanitizerRepository.java:170-183`) queries:
  ```sql
  select e.job_id, e.kind, e.lookup_key, e.nonce, e.ciphertext, j.wrapped_data_key
  from sanitize_vault_entry e
  join sanitize_job j on j.job_id = e.job_id
  where j.mode = 'SANITIZE'
    and j.vault_pruned_at is null
    and j.vault_expires_at > now()
  ```
  It does **not** filter by `and j.state = 'SUCCEEDED'`! Consequently, encrypted originals from aborted/failed jobs remain live in the vault and can be reverted via `OWN_JOBS` / `ALL_JOBS` scope even though the sanitize job failed and produced no valid output.
- **Concrete Failing Input:**
  Upload a CSV with 600 valid rows containing `198.51.100.77` followed by an unclosed quote at EOF. `JobVault` flushes the first 500 entries to `sanitize_vault_entry`; the job transitions to `FAILED`. A subsequent revert with `scope = OWN_JOBS` resolves `198.51.100.77`'s token from the failed job's vault entries.
- **Fix:**
  1. Call `repository.pruneJobVault(jobId)` inside `runSanitize()`'s `catch` block (`SanitizerService.java:157`, matching `sanitizeFile()` at line 264) and inside `closeInterrupted()` (`SanitizerRepository.java:224-234`).
  2. Add `and j.state = 'SUCCEEDED'` to `findScopedEntries()` (`SanitizerRepository.java:175`) and `visibleJob()` (`SanitizerService.java:272`).

#### F-05 — `TextFormat` 1 MiB chunk splitter cuts mid-token when a long line has no whitespace in its upper half
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/TextFormat.java:81-86,109-114`
- **Severity:** **LOW**
- **Analysis:** `TextFormat.process()` buffers up to `MAX_LINE = 1024 * 1024` (1 MiB) when a line contains no newline (`\n` or `\r`), and then splits at `lastWhitespace(current)`:
  ```java
  private static int lastWhitespace(StringBuilder s) {
      for (int i = s.length() - 1; i > s.length() / 2; i--) {
          char c = s.charAt(i);
          if (c == ' ' || c == '\t') return i + 1;
      }
      return s.length();
  }
  ```
  If a minified single-line log, semicolon/comma-separated dump, or URL query string has no space (`' '`) or tab (`'\t'`) between offset `524,288` and `1,048,576`, `lastWhitespace()` returns `s.length()` (`1,048,576`), slicing the string at the exact 1 MiB mark. Any IPv4, IPv6, MAC, FQDN, email, or URL spanning offset `1,048,576` is cut into two halves (e.g., `fw-tango-04.corp.` and `example.com`, or `192.0.` and `2.10`), causing the detector to miss or mis-map the split identifier.
- **Concrete Failing Input:**
  A single-line log file with `1,048,570` `'x'` characters (or `;`-delimited tokens without spaces) followed by `;192.0.2.10;`: the chunk boundary falls between `192.0.` and `2.10`, leaving both fragments unsanitized in the output.
- **Fix:** Broaden the safe split delimiter search in `lastWhitespace()` to include punctuation delimiters (`;`, `,`, `|`, `"`, `'`, `)`, `]`, `}`) that cannot appear inside an IP/MAC/FQDN, or retain a 256-character lookahead overlap across chunk boundaries.

---

## 2. Keyed IP Permutation (Crypto-PAn Construction, Prefix Preservation, Key Separation)

### Verified Controls
- **Sub-Key Separation (`SanitizerKey.java:30-38`, `VaultCrypto.java:84-99`):** `SanitizerKey` derives independent 256-bit sub-keys via `HMAC-SHA256(privacyKey, "nexus-sanitizer/v1/" + family)` for `"ip"`, `"mac"`, `"name"`, `"dns"`, and `"vault"`, while `VaultCrypto` derives the vault KEK via HKDF-SHA256 with info `"nexus-sanitizer/v1/vault-kek"`. None of these collide with `TopologyNamePseudonymizer` (`"DEVICE:..."`) or `SubnetPreservingIpMasker`.
- **Bijectivity & Determinism (`IpPermutation.java:105-157`, `IpPermutationTest.java`):** Because `forced(prefix, n, ranges)` depends only on the first `n` bits (`0 .. n-1`) already shared between `input` and `output`, `permute(permute(x))` with the inverted round bit (`invert = true`) is an exact involution (`invert4(map4(x)) == x` and `invert6(map6(x)) == x`) and preserves common prefix lengths (`commonPrefix(a, b) == commonPrefix(map(a), map(b))`).

### Findings

#### F-06 — `IpPermutation.forced()` prefix-preservation logic leaks high-order prefix bits of every IPv4 and IPv6 address
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/IpPermutation.java:34-61,142,159-168`
- **Severity:** **HIGH**
- **Analysis:**
  In `IpPermutation.permute()`, bit `n` (`0 <= n < totalBits`) is NOT flipped (`flip = 0`) whenever `forced(prefix, n, ranges)` returns `true`:
  ```java
  private static boolean forced(byte[] prefix, int n, List<Range> ranges) {
      for (Range range : ranges) {
          if (n < range.bits() && matches(prefix, range.prefix(), n)) {
              return true;
          }
      }
      return false;
  }
  ```
  Consider what this does for every bit `n` along the binary tree path to **any** reserved range in `NEXUS_V4` (`0.0.0.0/8`, `127.0.0.0/8`, `169.254/16`, `224/4`, `240/4`) and `NEXUS_V6` (`::/128`, `::1/128`, `::ffff:0:0/96`, `fe80::/10`, `ff00::/8`):
  1. **At `n = 0` (the MSB of every IPv4 and IPv6 address):** `matches(prefix, range.prefix(), 0)` is **vacuously `true`** for every range with `range.bits() > 0`! Therefore, **bit 0 is NEVER flipped** for any IPv4 or IPv6 address (`0.0.0.0/1` always stays in `0.0.0.0/1`; `128.0.0.0/1` always stays in `128.0.0.0/1`).
  2. **For RFC 1918 `10/8` (`00001010...`):** Its first 4 bits (`0000`) match `0.0.0.0/8` (`00000000`, `bits = 8`). Therefore, `forced()` returns `true` for `n = 0, 1, 2, 3, 4`! Neither the first 4 bits (`0000`) nor the 5th bit (`1`, at `n = 4` where the prefix is still `0000`) can ever be flipped! As a result, **every `10.x.x.x` address is guaranteed to map into `8/5` (`8.x.x.x` – `15.x.x.x`)**, regardless of the secret key!
  3. **For RFC 1918 `172.16/12` (`10101100...`):** Its first 5 bits (`10101`) match `169.254/16` (`10101001...`, `bits = 16`). Therefore, `forced()` returns `true` for `n = 0, 1, 2, 3, 4, 5`! **Every `172.16.x.x` address is guaranteed to map into `172/6` (`101011xx`, i.e., `172.x.x.x` – `175.x.x.x`)!**
  4. **For RFC 1918 `192.168/16` (`11000000...`):** Its first 2 bits (`11`) match `224/4` (`1110...`, `bits = 4`). Therefore, `forced()` returns `true` for `n = 0, 1, 2`, so **every `192.168.x.x` address is guaranteed to map into `192/3` (`192.x.x.x` – `223.x.x.x`)**!
  5. **For IPv6 Global Unicast `2000::/3` (`001...`) and Unique Local `fc00::/7` (`1111110...`):** Because `::/128` (`128` zero bits) is in `NEXUS_V6` with `bits = 128`, any IPv6 address starting with `k` zero bits has `forced() == true` for `n = 0 .. k`! For `2000::/3` (`001...`), `n = 0, 1, 2` are never flipped, so `2000::/3` always maps into `2000::/3`. For `fc00::/7` (`1111110...`), its first 6 bits (`111111`) match `ff00::/8` (`11111111`), so `n = 0 .. 6` are never flipped and **`fc00::/7` always maps into `fc00::/7`**!

  An adversary inspecting a sanitized file can immediately distinguish `10/8` (always `8/5`), `172.16/12` (always `172/6`), `192.168/16` (always `192/3`), `126/8` (always `126/8` because its first 7 bits `0111111` match `127.0.0.0/8`!), and IPv6 ULA `fc00::/7` (always `fc00::/7`) without knowing the key.
- **Concrete Failing Input:**
  Under **any** `SanitizerKey`:
  - `192.0.2.1` (`11000000...`) has its first 2 bits match `224/4` (`1110`), so `n = 0, 1, 2` are forced to `0` and it always maps into `192/3` (`192.x.x.x` – `223.x.x.x`).
  - `126.45.67.89` (`01111110...`) has its first 7 bits match `127.0.0.0/8` (`01111111`), so `n = 0..7` are forced to `0` and **every `126.x.x.x` address always maps to `126.y.y.y`** (leaking the exact `/8`!).
  - `::2` (`126` leading zero bits matching `::/128`) has `n = 0..126` forced to `0`, so **`::2` can only map to `::2` or `::3`** (1 bit of entropy!).
- **Fix:**
  Do not freeze tree branches above reserved prefixes across the entire address space. Instead, either:
  1. Run standard unrestricted 32-bit / 128-bit Crypto-PAn (or cycle-walking / prefix-cipher adjustment at the reserved prefix depth `n == range.bits() - 1` using an involution swap between the reserved sibling subtree and a keyed non-reserved sibling subtree only when the lower prefixes match), OR
  2. For single-address passthroughs (`::/128`, `::1/128`), handle them purely via the `passthrough()` pre-check (`IpPermutation.java:93`) and remove `keep("::", 128)` and `keep("::1", 128)` from `NEXUS_V6` (replacing them with cycle-walking if a non-passthrough address lands on `::` or `::1`), and use cycle-walking/swapping at the `/4` or `/8` boundary rather than forcing `flip = 0` at every ancestor bit `0 <= n < range.bits()`.

#### F-07 — Mixed-case DNS labels hash `"label-exact:" + label` instead of lowercase punycode, breaking DNS case-insensitivity correlation and leaking capitalization patterns
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/NameMapper.java:41-44`
- **Severity:** **LOW**
- **Analysis:**
  `DATA_SANITIZER_CONTRACT.md` §2 states *"same label -> same token everywhere"*, and `NameMapper`'s class javadoc (`NameMapper.java:12-13`) notes *"DNS labels are case-insensitive, so the digest is taken over the lower-case punycode form."* However, lines 37–44 implement:
  ```java
  String canonical = punycode(label).toLowerCase(Locale.ROOT);
  if (isAllLower(label)) {
      return "h-" + hex(name.get(), "label:" + canonical);
  }
  if (isAllUpper(label)) {
      return "H-" + hex(name.get(), "label:" + canonical).toUpperCase(Locale.ROOT);
  }
  return "h-" + hex(name.get(), "label-exact:" + label);
  ```
  Because mixed-case labels (e.g., `Fw-Tango-04.Corp.Example.Com`) hash `"label-exact:" + label` (case-sensitive!) instead of `"label:" + canonical`, `Fw-Tango-04.corp.example.com` and `fw-tango-04.corp.example.com` produce **completely different `h-<10 hex>` tokens**, breaking cross-log hostname correlation and revealing to an observer which log lines used mixed case. Moreover, both all-lowercase and mixed-case labels emit lowercase `"h-" + 10 hex`, so the token prefix does not even distinguish them without vault lookup.
- **Concrete Failing Input:**
  Sanitize `"host=Fw-Tango-04.example.com and host=fw-tango-04.example.com"`: the first label maps to `h-<digestA>` while the second maps to `h-<digestB>` (`digestA != digestB`).
- **Fix:** Always derive the label token digest from `"label:" + canonical` so DNS labels correlate case-insensitively across the job/estate; if exact case restoration on revert is needed, rely on the vault entry (or only use vault restoration for mixed-case labels while keeping the deterministic pseudonym digest case-insensitive).

---

## 3. Detector Misses (Identities Slipping Through Unsanitized)

### Verified Controls
- **IPv4/IPv6 & CIDR Validation (`Addresses.java`, `TextSanitizer.java:254-327`):** Rejects 4-part date/version strings (where the first group is a 4-digit year), validates octets `0..255` without leading zeroes, supports RFC 5952 compressed/expanded IPv6, preserves passthrough ranges (`0.0.0.0`, `127.0.0.1`, all-ones broadcast, `::`, `::1`, `fe80::`, `ff00::/8`), and zeros host bits on network CIDRs while keeping `/prefix` intact.
- **XLSX Shared Strings, Inline Strings, Formulas, Hyperlinks, Properties (`XlsxFormat.java:363-472`):** Rewrites `sharedStrings.xml` (`<t>`), worksheet inline strings (`<is><t>`), cached formula string results (`<c t="str"><v>`), string literals inside `<f>` formulas, external `.rels` hyperlink targets, comments (`<text><t>`), and core/extended/custom document properties.
- **CSV RFC 4180 Quoting & Streaming (`CsvFormat.java:69-192`):** Handles multiline quoted cells, escaped double quotes (`""`), `;`/`,`/`\t` delimiters, UTF-8 BOM, and header-driven `hostContext` column detection.

### Findings

#### F-08 — PCAP DNS compression pointers into non-name RR bytes (e.g., SOA 20-byte trailer, MX preference, SRV header) bypass in-place label overwriting and leak plaintext hostnames
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/pcap/DnsRewriter.java:63-69,214-219,245-261`
- **Severity:** **HIGH**
- **Analysis:**
  In `DnsRewriter.name()` (`DnsRewriter.java:231-266`), when a DNS name encounters a compression pointer (`(length & 0xc0) == 0xc0`), it validates `target < cursor` (backward pointer) and sets `direct = false` (`DnsRewriter.java:248`). For every label read after following the compression pointer, `positions.add(direct ? cursor : -1)` appends `-1` (`DnsRewriter.java:261`).
  Then in `SanitizeVisitor.name()` (`DnsRewriter.java:63-69`):
  ```java
  for (int i = 0; i < labels.size() - suffix; i++) {
      int at = positions.get(i);
      ...
      if (at >= 0) {
          System.arraycopy(repBytes, 0, message, at + 1, repBytes.length);
      }
  }
  ```
  `DnsRewriter` assumes that any backward compression pointer `target < cursor` must point to a label that was *already* visited as `direct = true` in an earlier DNS name and therefore already overwritten in `message`!
  That assumption is false whenever a backward pointer points into **non-name bytes that `walk()` preserves in `message`**:
  - In `SOA` records (`case 6`, `DnsRewriter.java:214-219`), the 20-byte trailer (`SERIAL`, `REFRESH`, `RETRY`, `EXPIRE`, `MINIMUM`) after `MNAME` and `RNAME` is **kept verbatim in `message`** (`next + 20 == rdata + rdlength`).
  - In `SRV` records (`case 33`, line 213), the 6-byte header (`priority`, `weight`, `port`) before target name is kept verbatim.
  - In `MX` records (`case 15`, line 212), the 2-byte `preference` is kept verbatim.
  - Even within an earlier DNS name, if an earlier QNAME was a 2-label public suffix like `ac.uk` (`labels.size() == 2, suffix == 2`), `ac` at offset 12 is **not** overwritten because `i < labels.size() - suffix` is `0 < 0` (`false`), or if a pointer jumps to a non-label boundary inside an earlier label!
  If a subsequent RR owner name or RDATA name uses a compression pointer pointing into the 20-byte `SOA` trailer (which can hold a valid length-prefixed label up to 18 bytes plus `\x00`, e.g., `\x0afw-tango04\x00`), `DnsRewriter` reads `"fw-tango04"`, records the pseudonym in the vault, sees `at == -1`, and **never overwrites the bytes in `message`**! When `tshark` or Wireshark dissects the sanitized PCAP, it follows the compression pointer into the SOA trailer and displays `fw-tango04` in cleartext.
- **Concrete Failing Input:**
  A DNS response packet with 1 Question (`example.com`) and 2 Answer RRs:
  - Answer 1 (`SOA`, type 6): `MNAME = ns.example.com`, `RNAME = hostmaster.example.com`, and 20-byte SOA trailer starting at offset `T` containing bytes `0a 66 77 2d 74 61 6e 67 6f 30 34 00 ...` (`\x0afw-tango04\x00` + 8 padding bytes).
  - Answer 2 (`PTR` or `CNAME`, type 5): RDATA is a 2-byte compression pointer `0xc000 | T` pointing to the SOA trailer.
  `DnsRewriter.sanitize()` returns `true` and leaves `fw-tango04` intact at offset `T`; `tshark -r sanitized.pcap` prints `CNAME fw-tango04`.
- **Fix:**
  Track the exact set of byte offsets in `message` where a label length byte was visited with `direct == true` during the current packet walk (`BitSet validLabelOffsets`). In `DnsRewriter.name()`, reject any compression pointer whose `target` (or any subsequent label offset reached after jumping) is not in `validLabelOffsets` (returning `false` so `FrameRewriter` falls back to zeroing the entire DNS payload). Additionally, verify that the suffix calculation at the target matches the jumping name's suffix depth, or overwrite at `cursor` even when `direct == false`.

#### F-09 — PCAP IPv4 header options (Record Route, Loose/Strict Source Route, Internet Timestamp), IPv6 Hop-by-Hop/Destination Options, and non-IPv4 ARP payloads are copied verbatim in cleartext
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/pcap/Layers.java:172-182,188-190,213-228`, `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/pcap/FrameRewriter.java:306-322`
- **Severity:** **HIGH**
- **Analysis:**
  1. **IPv4 Options (`Layers.java:188-190`, `FrameRewriter.java:306-308`):** `Layers.ip()` sets `l.hdrLen = ihl; l.size = ihl;` (up to 60 bytes when `IHL > 5`), and `FrameRewriter.model()` preserves the entire `p.hdrLen` slice in `keep` without inspecting or zeroing bytes `pos + 20 .. pos + ihl`. IPv4 Options **Record Route (type 7)**, **Loose Source Route (type 131)**, **Strict Source Route (type 137)**, and **Internet Timestamp (type 68, flags 1 or 3)** store arrays of 32-bit IPv4 addresses of intermediate hops and endpoints directly inside `[pos + 20, pos + ihl)`. All of those router/host IPs survive in the sanitized PCAP in plaintext!
  2. **IPv6 Hop-by-Hop (`next = 0`) and Destination Options (`next = 60`) (`Layers.java:213-216`, `FrameRewriter.java:320`):** `Layers.ipv6()` adds `ipv6.hopopts` and `ipv6.dstopts` with `extLen = (u8(at + 1) + 1) * 8`, and `FrameRewriter.model()` preserves their entire byte range in `keep` (whereas `ipv6.routing` is zeroed from `pos + 4` at line 310). RFC 6275 Home Address Destination Option (`0xc9`) and custom telemetry options embed 128-bit IPv6 addresses in `ipv6.dstopts`/`ipv6.hopopts` that survive in cleartext.
  3. **Non-Ethernet/IPv4 ARP (`Layers.java:172-182`, `FrameRewriter.java:313`):** In `Layers.arp()`, `Layer l = new Layer("arp", pos, 8 + 2 * h + 2 * p)` is added unconditionally, and `FrameRewriter.model()` keeps all `l.size` bytes (`keep.add(new int[]{from, to})`). However, `Layers.arp()` only adds `eth` and `ip` rewrite fields when `h == 6 && p == 4 && ptype == 0x0800`! If an ARP/RARP frame has `ptype == 0x8035` (RARP, where `h == 6, p == 4`) or `ptype == 0x86dd` (`p == 16`), the sender/target MAC and protocol addresses are preserved in `output` completely unsanitized.
- **Concrete Failing Input:**
  - An IPv4 ICMP Echo packet with `IHL = 15` (40 bytes of IPv4 Record Route option `0x07 0x27 0x08` containing hop IPs `192.0.2.1`, `192.0.2.2`, `192.0.2.3`): `PcapSanitizer.sanitize()` rewrites `ip.src` and `ip.dst` in the base 20-byte header but copies all 40 option bytes verbatim, leaking `192.0.2.1`, `192.0.2.2`, and `192.0.2.3`.
  - A RARP frame (`ethertype = 0x0806`, `htype = 1`, `ptype = 0x8035`, `hlen = 6`, `plen = 4`): sender and target MACs and IPv4s inside the 28-byte payload are copied verbatim.
- **Fix:**
  1. In `FrameRewriter.model()` (`FrameRewriter.java:306-308`), keep only the base 20-byte IPv4 header (`size = Math.min(20, p.hdrLen)`) and zero option bytes `[pos + 20, pos + p.hdrLen)` with `0x00` (`NOP`/End-of-Option-List) while updating the IPv4 header checksum via `adjustChecksum` (which already covers `npos .. npos + net.hdrLen`).
  2. For `ipv6.hopopts` and `ipv6.dstopts`, keep only the 2-byte extension header (`Next Header` + `Hdr Ext Len`, `size = 2`) and zero the option payload bytes `[pos + 2, pos + extLen)` (which in IPv6 options encodes `Pad1` = `0x00` bytes, valid RFC 8200 padding!).
  3. In `FrameRewriter.model()`, for `arp`, when `h != 6 || p != 4 || ptype != 0x0800`, keep only the 8-byte ARP fixed header (`size = 8`) so unmapped hardware/protocol addresses are zeroed.

#### F-10 — `FILE_LIKE_TLDS` heuristic suppresses 2-label domains under real ccTLDs (`.ai`, `.rs`, `.pl`, `.md`, `.sh`, `.so`, `.cc`, `.ms`) in free text
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/Patterns.java:67-68`, `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/TextSanitizer.java:335-337`
- **Severity:** **MEDIUM**
- **Analysis:**
  To avoid false positives on filenames like `config.json` or `script.sh`, `TextSanitizer.fqdn()` skips any 2-label candidate whose TLD is in `Patterns.FILE_LIKE_TLDS`:
  ```java
  if (labels.length == 2 && suffix == 1 && Patterns.FILE_LIKE_TLDS.contains(labels[1])) {
      return null;
  }
  ```
  Because `FILE_LIKE_TLDS` contains `"ai"`, `"rs"`, `"pl"`, `"md"`, `"sh"`, `"so"`, `"cc"`, and `"ms"` (all active ICANN country-code TLDs heavily used for corporate domains), any 2-label root domain under those ccTLDs (e.g., `acme-corp.ai`, `banka.rs`, `firma.pl`, `vendor.sh`) in free text is completely ignored by `TextSanitizer.fqdn()` and survives in plaintext! Even worse, if an email or URL is absent and a firewall log says `dst_domain=acme-corp.ai` or `lookup bank.pl failed`, the customer's domain name leaks in full.
- **Concrete Failing Input:**
  Sanitize free text: `"DNS query for acme-security.ai and portal-bank.pl timed out"`.
  Output: `"DNS query for acme-security.ai and portal-bank.pl timed out"` (zero tokens sanitized).
- **Fix:**
  Do not blanket-suppress real ccTLDs (`.ai`, `.pl`, `.rs`, `.md`, `.sh`, `.so`, `.cc`, `.ms`) when preceded by domain/network context or when the first label contains hyphens (file basenames rarely look like `acme-security.ai` vs code artifacts), or restrict `FILE_LIKE_TLDS` strictly to non-delegated/rare extensions while sanitizing `.ai`, `.pl`, `.rs`, `.cc` when not preceded by path separators (`/`, `\`). Additionally, allow multi-label internal FQDNs (3+ labels like `fw01.prod.corp-net`) when preceded by host/domain context even if the final label is a custom enterprise internal TLD not in `PublicSuffixes.SPECIAL`.

#### F-11 — Hostnames starting with a digit (valid per RFC 1123 §2.1) are missed by `CONTEXT_HOST`, `USER_AT_HOST`, `SYSLOG_3164`, and `SYSLOG_5424`; `CONTEXT_HOST` also fails to `learn()` discovered hostnames
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/Patterns.java:39,47,52,53`, `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/TextSanitizer.java:246`
- **Severity:** **MEDIUM**
- **Analysis:**
  1. RFC 1123 §2.1 relaxed RFC 952 to permit hostnames whose leading character is an ASCII digit (e.g., `3com-core01`, `10g-leaf-sw02`, `2fa-proxy`). While `Patterns.LABEL` (`Patterns.java:35`) allows a leading digit (`[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?`), four single-label hostname patterns in `Patterns.java` hardcode a leading letter `[A-Za-z]`:
     - `USER_AT_HOST` (line 39): `... @([A-Za-z][A-Za-z0-9-]{1,62}) ...`
     - `CONTEXT_HOST` (line 47): `... ([A-Za-z][A-Za-z0-9_-]{0,62}) ...`
     - `SYSLOG_3164` (line 52): `... ([A-Za-z][A-Za-z0-9_-]{0,62})\s+ ...`
     - `SYSLOG_5424` (line 53): `... ([A-Za-z][A-Za-z0-9_-]{0,62})\s+ ...`
     Consequently, single-label hostnames starting with a digit (such as `hostname=10g-sw01`, `admin@2fa-gw`, or `<134>Oct 16 10:00:00 3com-fw01 kernel:`) are missed.
  2. In `TextSanitizer.replace()` (`TextSanitizer.java:246`), `case CONTEXT_HOST -> hostLabel(p[0]);` does **not** call `learn(p[0])` (unlike `BARE_HOST` at line 248 and `knownCandidates` via `KnownNames` in CSV/XLSX). Thus, if a log line or JSON value contains `hostname=fw-tango-04`, `fw-tango-04` is sanitized at that position, but is **not** added to `learnedHosts`, so a subsequent bare mention `"connection to fw-tango-04 reset"` on the next line of the same file leaks `fw-tango-04` in plaintext!
- **Concrete Failing Input:**
  - Digit-leading hostname: `"<134>Oct 16 10:00:00 10g-sw01 sshd[100]: user admin@10g-sw01 from hostname=10g-sw01"` -> all three occurrences of `10g-sw01` remain in plaintext!
  - Unlearned context host: `"hostname=fw-tango-04\nStatus of fw-tango-04 is down"` -> first line becomes `"hostname=h-..."`, second line leaves `"fw-tango-04"` in plaintext!
- **Fix:**
  1. In `Patterns.USER_AT_HOST`, `CONTEXT_HOST`, `SYSLOG_3164`, and `SYSLOG_5424`, allow digit-leading hostnames that contain at least one ASCII letter (`(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9][A-Za-z0-9_-]{0,62}`), which still excludes pure numbers while matching `10g-sw01` and `3com-fw01`.
  2. Call `learn(p[0])` in `case CONTEXT_HOST`, `case SYSLOG_HOST`, and `case USER_AT_HOST` in `TextSanitizer.replace()` (`TextSanitizer.java:245-247`) so hostnames discovered via strong context in line $N$ are also sanitized when mentioned bare in line $N+1$.

#### F-12 — `urlCandidate` consumes closing `]` on bracketed non-IPv6 URLs without a path (e.g., `[http://fw-tango-04]`), causing single-label URL host sanitization to fail
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/TextSanitizer.java:209-210,369-372`
- **Severity:** **MEDIUM**
- **Analysis:**
  In `TextSanitizer.urlCandidate()` (`TextSanitizer.java:207-211`):
  ```java
  int end = start;
  while (end < text.length() && "/?#\"'<>`\\ \t\r\n,;)]}".indexOf(text.charAt(end)) < 0) end++;
  if (end < text.length() && text.charAt(end) == ']') end++;
  ```
  Line 210 unconditionally advances `end` past `']'` even when `authority` did **not** start with `'['` (i.e., when the URL host is not a bracketed IPv6 literal, but the URL itself is enclosed in square brackets in Markdown or log text, such as `[https://fw-tango-04]` or `[http://fw-tango-04:8443]`).
  - For `[https://fw-tango-04]`, `authority` becomes `"fw-tango-04]"`, `hostPort` becomes `"fw-tango-04]"`, and `url("host", "fw-tango-04]")` rejects it at line 370 (`!value.matches("[A-Za-z0-9_-]{1,63}")`) because of the trailing `']'`, returning `null`! Because no other detector matches a single-label hostname without `hostname=`, `fw-tango-04` is left in **plaintext**!
  - For `[http://fw-tango-04:8443]`, `portIndex` sees `:8443]`, `validPort("8443]")` returns `false`, and `urlCandidate` drops the candidate completely, again leaving `fw-tango-04` in **plaintext**!
- **Concrete Failing Input:**
  Sanitize text: `" Portal link: [https://fw-tango-04] and [http://fw-tango-04:8443] "`.
  Both `fw-tango-04` occurrences survive in plaintext in the output.
- **Fix:** Only advance `end` past `']'` (and any following `:port`) in `urlCandidate()` when the host portion of `authority` (after optional `userinfo@`) actually starts with `'['`.

#### F-13 — XLSX `SheetPolicy` and `WorkbookPolicy` miss cell types other than `t="str"`/`<is>` and external path attributes
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/XlsxFormat.java:108-114,392-397,450-454`
- **Severity:** **MEDIUM**
- **Analysis:**
  In `XlsxFormat.SheetPolicy.text()` (`XlsxFormat.java:450-454`):
  ```java
  if ((local.equals("v") && inCell && "str".equals(type)) || (local.equals("t") && inInline)) {
      return new TextAction(hostCol ? TextMode.HOST : TextMode.FREE);
  }
  ```
  1. In OOXML, a cell `<c>` with `t="inlineStr"` is permitted by some third-party spreadsheet writers to place `<v>` directly under `<c>` or `<t>` directly under `<c>` without an `<is>` wrapper, or use `t="d"` / omitted `t` on malformed exports. If `<t>` appears inside `<c>` without `<is>`, `inInline` is `false` and the text is not sanitized.
  2. In `XlsxFormat.policyFor()` (`XlsxFormat.java:108-114`), XML parts not matching the 6 hardcoded prefixes (`worksheets/sheet`, `sharedStrings.xml`, `comments`, `workbook.xml`, `docProps/`, `.rels`) return `policy = null` and fall back to `DefaultXmlPolicy` (`XlsxFormat.java:120,350-361`), which rewrites element text but **never** rewrites XML attributes! However, OOXML parts such as `/xl/externalLinks/externalLink1.xml`, `/xl/connections.xml` (`<dbPr connection="Server=192.0.2.10;Database=prod"/>`), `/xl/queryTables/queryTable1.xml`, and `/xl/tables/table1.xml` store connection strings, server IPs, and hostnames inside **XML attributes** (`connection="..."`, `url="..."`, `serverCommand="..."`) rather than element text! `DefaultXmlPolicy.attribute()` always returns `null` (`XlsxFormat.java:357`), so every database server IP/FQDN in `/xl/connections.xml` survives in plaintext!
- **Concrete Failing Input:**
  An `.xlsx` archive containing `/xl/connections.xml` with:
  ```xml
  <connections xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <connection id="1" name="ProdDB"><dbPr connection="Driver={SQL Server};Server=192.0.2.10;Database=netdb"/></connection>
  </connections>
  ```
  `DefaultXmlPolicy` skips all XML attributes, leaving `Server=192.0.2.10` in plaintext.
- **Fix:**
  1. In `SheetPolicy.text()`, also sanitize `<t>` when `inCell` is true (even if `inInline` is false).
  2. In `DefaultXmlPolicy.attribute()`, return `TextMode.FREE` for attributes whose value contains digits/dots/colons/`@`/`//` or whose name is in `{connection, command, serverCommand, url, ref, location, target, value}` so connection strings and external link attributes in auxiliary XML parts are sanitized.

#### F-14 — CSV files without a header row fail to sanitize bare hostnames in row 1, and `HostHeaders` only matches exact tokens after stripping non-alphanumerics
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/CsvFormat.java:98-106`, `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/HostHeaders.java:25-27`
- **Severity:** **LOW**
- **Analysis:**
  `CsvFormat` always treats row 0 as a header row (`if (hostColumns == null) hostColumns = detectHeaders(record);`), passing an empty `BitSet` when flushing row 0 (`CsvFormat.java:114`). Moreover, if a header cell uses a compound name not in `HostHeaders.EXACT`—such as `src_hostname`, `dst_hostname`, `source_hostname`, `destination_hostname`, `device_hostname`, `peer_hostname`, `node_hostname`, `member_hostname`, or `cluster` (`HostHeaders.EXACT` at lines 12–19 includes `"srchost"`, `"dsthost"`, `"hostname"`, `"devicename"`, `"clustername"`, but misses `"srchostname"`, `"dsthostname"`, `"devicehostname"`, `"cluster"`, etc.)—the entire column is NOT marked as a `hostContext` column, and every single-label hostname in that column across the entire CSV/XLSX file is left in **plaintext**!
- **Concrete Failing Input:**
  A CSV file with header `src_hostname,dst_hostname,action` and row `fw-tango-04,fw-juliet-06,drop`:
  `HostHeaders.isHostHeader("src_hostname")` normalizes `"src_hostname"` to `"srchostname"`, which is not in `HostHeaders.EXACT`! Both `fw-tango-04` and `fw-juliet-06` remain in plaintext!
- **Fix:** Add `"srchostname"`, `"dsthostname"`, `"sourcehostname"`, `"destinationhostname"`, `"devicehostname"`, `"peerhostname"`, `"nodehostname"`, `"memberhostname"`, `"cluster"`, `"hostid"`, `"assetname"` (or suffix-match `normalized.endsWith("hostname") || normalized.endsWith("devicename") || normalized.endsWith("fqdn")`) in `HostHeaders.isHostHeader()`.

#### F-15 — BSD/macOS/Solaris `arp -a` single-hex-digit MAC octets (`0:1a:2b:3c:4d:5e`) are not matched by `Patterns.MAC`
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/Patterns.java:25-28`
- **Severity:** **LOW**
- **Analysis:** `Patterns.MAC` requires exactly two hex digits per colon/hyphen-separated group (`(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}`). BSD, macOS, Solaris, and several network appliance CLI outputs (`arp -a`, `ndp -a`) omit leading zeroes on MAC octets (`0:1a:2b:3c:4d:e`), allowing those MAC addresses in CLI captures to bypass `Patterns.MAC`.
- **Concrete Failing Input:**
  Sanitize BSD `arp -a` output: `"? (192.0.2.10) at 0:1a:2b:3c:4d:5 on en0 ifscope [ethernet]"`.
  The IP `192.0.2.10` is sanitized, but the MAC `0:1a:2b:3c:4d:5` remains in plaintext.
- **Fix:** Extend `Patterns.MAC` (or add a colon-separated MAC branch when at least one octet is 1 hex digit and preceded by `at ` / `ether ` / `lladdr ` / `mac`) and normalize each octet to 1 byte in `MacText.parse()`.

---

## 4. Revert Safety (Vault-Only Resolution, Unforged Tokens, Authorization)

### Verified Controls
- **No Key-Based Algorithmic Fallback on Revert (`TextReverter.java:81-101`, `DnsRewriter.java:78-115`, `FrameRewriter.java:168-227`, `ScopedResolver.java:36-74`):** Revert never calls `IpPermutation.invert4()` or `IpPermutation.invert6()`. Every candidate IP, CIDR, MAC, hostname label, and DNS name is looked up exclusively through `TokenResolver.resolve(VaultKey)` against PostgreSQL `sanitize_vault_entry` rows decrypted via `VaultCrypto.decrypt()`. Any address or `h-xxxxxxxxxx` token not recorded in a matching vault row returns `null` from `resolver.resolve()` and is left untouched.
- **Per-Job and Per-User Authorization (`SanitizerController.java:155-182,265-285`, `SanitizerService.java:191-212,269-277`, `ActionRegistry.java:148-150,171-172`):**
  - `DATA_SANITIZE_RUN` is granted to `operator`, `operation_admin`, `security_admin` (not `aiview`).
  - `DATA_SANITIZE_REVERT` is granted to `operator`, `operation_admin`, `security_admin` (not `aiview`), and `SanitizerService.startRevert()` enforces that non-`security_admin` callers can only revert against `ONE_JOB` or `OWN_JOBS` where `created_by = actor`, rejecting `ALL_JOBS` with HTTP 403 (`ResponseStatusException(HttpStatus.FORBIDDEN)`).

### Findings

#### F-16 — IPv6 addresses with zone identifiers and CIDR prefixes (`fe80::1%eth0/64`) or hex-formatted IPv4-mapped IPv6 addresses (`::ffff:c000:0201`) fail to revert or lose CIDR suffix
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/TextReverter.java:187-210`, `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/TextSanitizer.java:311-317`
- **Severity:** **MEDIUM**
- **Analysis:**
  1. **Zone ID + CIDR (`fe80::1234%eth0/64`):** During sanitization, `TextSanitizer.ipv6Candidates()` (`TextSanitizer.java:192-203`) matches `Patterns.IPV6_RUN`, then `Patterns.ZONE` (`%eth0`), and then `Patterns.PREFIX` (`/64`), outputting `fe80::<mapped>%eth0/64`. During revert, however, `TextReverter.candidates()` (`TextReverter.java:187-205`) matches `Patterns.IPV6_RUN` and immediately checks `Patterns.PREFIX.matcher(text.substring(end))` **without matching `Patterns.ZONE` in between**! Because `%eth0` sits between the IPv6 address and `/64`, `TextReverter` does not see `/64`. If the original was a network CIDR with zero host bits (e.g. `2001:db8::%eth0/64`), `TextSanitizer.ipv6()` recorded `VaultKind.NETWORK` (`2001:db8::/64`) and did NOT record `VaultKind.ADDRESS`, so `TextReverter` looks up `VaultKind.ADDRESS` (`2001:db8::`), gets `null`, and **fails to revert the address at all**!
  2. **Hex-formatted IPv4-mapped IPv6 (`::ffff:c000:0201`):** `Addresses.parseV6("::ffff:c000:0201")` returns the 16-byte IPv4-mapped address (`IpPermutation.isV4Mapped(bytes) == true`), while `V6Style.of("::ffff:c000:0201").dottedTail()` is `false`. During `TextSanitizer.ipv6()` (`TextSanitizer.java:311-316`):
     - `sink.record(VaultKind.ADDRESS, "::ffff:c000:201", "::ffff:c000:0201")`
     - Because `IpPermutation.isV4Mapped(mapped)` is `true`, lines 313-315 ALSO record `sink.record(VaultKind.ADDRESS, "<mapped-v4>", "192.0.2.1")`!
     - `rendered` is formatted with `style` (`dottedTail == false`), so the sanitized output is hex `::ffff:xxxx:yyyy`.
     - If `::ffff:192.0.2.1%eth0/128` (dotted with zone and `/128`) is sanitized, `TextSanitizer.ipv6()` (`TextSanitizer.java:311-317`) returns `rendered + "%eth0/128"`, and on revert `TextReverter` fails to strip `%eth0/128` because `Patterns.ZONE` is not parsed in `TextReverter.candidates()`.
- **Concrete Failing Input:**
  Sanitize `"route 2001:db8:1234::%eth0/48 via fe80::1%eth0"` and revert the output: `fe80::1%eth0` reverts, but the network prefix before `%eth0/48` fails to revert because `TextReverter` does not parse `Patterns.ZONE` before `Patterns.PREFIX` and never looks up `VaultKind.NETWORK`.
- **Fix:** Update `TextReverter.candidates()` (`TextReverter.java:191-205`) to check for `Patterns.ZONE` between `Patterns.IPV6_RUN` and `Patterns.PREFIX` (mirroring `TextSanitizer.ipv6Candidates()` at lines 192-203) and preserve the `zone` string before `suffix` when reconstructing the reverted output.

#### F-17 — Sub-token resolution in `TextReverter`: an external tool that synthesizes `::ffff:<vault-ipv4>` or `<invented-label>.<vault-fqdn>` partially resolves the embedded vault token
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/TextReverter.java:168-210`, `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/Patterns.java:17`
- **Severity:** **MEDIUM**
- **Analysis:**
  `DATA_SANITIZER_CONTRACT.md` §4 requires: *"Tokens invented by an external tool that are not in the vault do not resolve and are left as-is."*
  However, `TextReverter.candidates()` emits overlapping sub-candidates at interior offsets of a compound token:
  1. When an external tool or LLM invents a compound hostname by prepending an invented label `h-0000000000` onto a real sanitized FQDN `h-1111111111.example.com` (producing `h-0000000000.h-1111111111.example.com`), `TextReverter` matches `h-1111111111` via `Patterns.LABEL_TOKEN` (`\b(?:h-[0-9a-f]{10}|H-[0-9A-F]{10})\b`, where `.` is a word boundary `\b`!) and reverts `h-1111111111` to the real internal subdomain while leaving `h-0000000000.` attached (`h-0000000000.secret-corp.example.com`).
  2. Similarly, if a sanitize job only contained IPv4 `192.0.2.10` (mapped to `<v4token>`, e.g., `198.51.100.99`), and an external tool writes an IPv4-mapped IPv6 address `::ffff:198.51.100.99`, `TextReverter.candidates()` creates both a full IPv6 candidate `::ffff:198.51.100.99` (which is NOT in the vault) AND a sub-candidate at offset 7 for `198.51.100.99` (`Patterns.IPV4` lookbehind `(?<![\p{L}\p{N}_.])` does not exclude `:`, plus `TextReverter.java:209` explicitly adds the dotted tail as a separate candidate). When the full `::ffff:198.51.100.99` lookup returns `null`, `TextReverter.revert()` advances to the next candidate (`198.51.100.99` at offset 7) and resolves it to `::ffff:192.0.2.10` even though no IPv6 address existed in the original job!
- **Concrete Failing Input:**
  1. Sanitize `"192.0.2.10"` (producing `"<v4token>"`).
  2. Feed `"::ffff:<v4token>"` (a synthetic IPv6 literal never present in the original input) to `Sanitizer.revert()`.
  3. `Sanitizer.revert()` outputs `"::ffff:192.0.2.10"` instead of leaving the un-vaulted IPv6 literal untouched.
- **Fix:**
  In `TextReverter.revert()`, when a maximal address run (such as an `IPV6_RUN` covering `[start, end)`) is parsed as a valid IPv6 address, do not fall through to interior sub-candidates within `[start, end)` unless `TextSanitizer.ipv6()` itself recorded a sub-entry for that exact run. Remove the separate tail candidate at `TextReverter.java:206-210` and instead resolve `::ffff:a.b.c.d` solely via its `VaultKind.ADDRESS` entry (`Addresses.formatV6(bytes, V6Style.CANONICAL)`).

#### F-18 — First-seen style wins per job in `JobVault.recent` deduplication (`on conflict (job_id, lookup_key) do nothing`), causing mixed-format IPv6/MAC occurrences in the same file to revert to the first occurrence's style
- **Location:** `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/sanitizer/JobVault.java:52`, `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/sanitizer/SanitizerRepository.java:87`
- **Severity:** **LOW**
- **Analysis:**
  `VaultCrypto.lookupKey()` keys vault entries by `(kind, canonicalToken)` where `canonicalToken` for `MAC` is colon-lowercase (`aa:bb:cc:dd:ee:ff`) and for `ADDRESS` is RFC 5952 canonical (`2001:db8::1`). Meanwhile, the encrypted `original` stored in `ciphertext` is the **formatted** original string from the first occurrence (`00-1A-2B-3C-4D-5E` or `2001:0db8:0000:0000:0000:0000:0000:0001`).
  - For MACs, `TextReverter.render()` (`TextReverter.java:108`) re-applies `MacText.format(MacText.parse(original), style)` from the token's separator/case, which preserves the second occurrence's MAC style.
  - However, for IPv6 (`TextReverter.restyle()`, `TextReverter.java:113-127`), if the first occurrence in the input was compressed `2001:db8::1` (stored in the vault as `"2001:db8::1"`) and the second occurrence in the same input used partial leading zeroes with `::` (`2001:0db8::0001`, where `expanded` is `false` because there is a `::`, so `V6Style` is `(false, false, false)`—identical to `2001:db8::1`'s `V6Style`), `JobVault` drops the second occurrence (`if (recent.put(...) != null) return;`), and `restyle()` returns `"2001:db8::1"` instead of `"2001:0db8::0001"`.
- **Concrete Failing Input:**
  Sanitize `"first=2001:db8::1 second=2001:0db8::0001"` and revert: the output is `"first=2001:db8::1 second=2001:db8::1"`.
- **Fix:** Document in `DATA_SANITIZER_CONTRACT.md` §1 that partially-padded IPv6 addresses containing `::` normalize to RFC 5952 compressed form on revert when mixed with compressed forms in the same job, or include `V6Style` / raw format metadata if byte-identical style preservation across mixed occurrences is required.

---

## 5. Resource Exhaustion (2 GB Inputs, XLSX Zip Bombs, Deeply Nested JSON)

### Verified Controls
- **JSON Nesting Depth Cap (`JsonFormat.java:28,159-162`):** `JsonFormat.Lexer` tracks `depth` up to `MAX_DEPTH = 512`, throwing `UnsupportedInputException("JSON nesting exceeds " + MAX_DEPTH)` before `StackOverflowError` can occur.
- **Upload Size Limit & Disk Streaming (`SanitizerController.java:122,307-314`, `SanitizerService.java:309-336`, `OfflineMultipartConfiguration.java`):** Enforces `MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024` (2 GiB) via `LimitedInputStream` while streaming multipart uploads directly to disk with an 8 KiB buffer (`transferTo`).
- **XML External Entity (XXE) & DTD Hardening (`XlsxFormat.java:135-140`):** `SUPPORT_DTD = false` and `IS_SUPPORTING_EXTERNAL_ENTITIES = false` on `XMLInputFactory`.

### Findings

#### F-19 — `JsonFormat` and `XlsxFormat` suffer unbounded heap accumulation (`pending` list in `JsonFormat`, `items` list in `XlsxFormat`) when a single string is followed by millions of non-string tokens/cells, plus >1.1 GiB allocation for a single 64M-char JSON string
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/JsonFormat.java:29,108,260-320`, `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/XlsxFormat.java:545-549`
- **Severity:** **HIGH**
- **Analysis:**
  `DATA_SANITIZER_CONTRACT.md` §1 & §4 mandate: *"Streaming: Constant-memory processing up to 2 GB; no whole-file DOM or materialisation"* and *"1 M-row CSV and 100k-row XLSX complete inside a 256 MB heap."*
  Three separate bugs violate this invariant and trigger `OutOfMemoryError` inside a 256 MiB (or even 2 GiB) heap:
  1. **`JsonFormat.Lexer.pending` Unbounded Accumulation (`JsonFormat.java:108,318-331`):**
     ```java
     private void emit(String raw) throws IOException {
         if (out == null) return;
         if (batch.isEmpty()) out.write(raw);
         else pending.add(raw);
     }
     ```
     `flush()` is only triggered inside `valueString()` when `batch.size() >= 2048 || batchChars >= 4 MiB` (`JsonFormat.java:328`). Once a JSON document has **one** string value (so `batch.size() == 1` and `batch.isEmpty()` is `false`), if the remainder of the 2 GiB JSON file consists of object keys (`"key":`), numbers, booleans, `null`s, arrays, and whitespace, `valueString()` is never called again until EOF! Every single structural token, key, whitespace run, and number across the 2 GiB file is appended as a `String` object to `List<String> pending` in heap, consuming multiple gigabytes of RAM and crashing the JVM with `OutOfMemoryError`!
  2. **`XlsxFormat.rewritePart` `items` Unbounded Accumulation (`XlsxFormat.java:545-549`):**
     ```java
     if (batch.size() >= ValueRewriter.BATCH_VALUES
         || chars >= ValueRewriter.BATCH_CHARS
         || (batch.isEmpty() && items.size() >= 4096)) {
         flush(items, batch, rewriter, mode, writer, eventFactory);
         chars = 0;
     }
     ```
     Look at the third condition: `(batch.isEmpty() && items.size() >= 4096)`. If a worksheet has **one** inline string or formula in row 1 (so `batch.size() == 1` and `batch.isEmpty()` is **`false`**!), and the remaining 500,000 rows contain only numeric cells (`<c r="A2"><v>42</v></c>`) or shared-string index cells (`<c r="B2" t="s"><v>0</v></c>` — note that `t="s"` cells in `sheet1.xml` are NOT added to `batch` because `sharedStrings.xml` is rewritten separately!), `batch.size()` remains `1` for the entire worksheet! Because `batch.isEmpty()` is `false`, `items.size() >= 4096` never triggers! All ~10,000,000 `XMLEvent` objects for the entire 500,000-row worksheet accumulate in `List<Item> items` in heap until `OutOfMemoryError`!
  3. **`JsonFormat.Lexer.string()` 64M-Char String Allocation (`JsonFormat.java:29,260-313`):**
     `MAX_STRING = 64 * 1024 * 1024` (64M characters). While lexing a single 64M-char string, `string()` simultaneously holds:
     - `StringBuilder raw` (64M chars = 128 MiB)
     - `StringBuilder value` (64M chars = 128 MiB)
     - `int[] starts` (64M `int`s = 256 MiB, which when growing past 32M/64M allocates a 512 MiB `int[]`, plus `Arrays.copyOf(starts, value.length())` at line 307 allocating another 256 MiB `int[]`!)
     A single 40–64 MiB JSON string allocates **over 1.1 GiB of heap** (and does so even during `JsonFormat.isValid()` when `out == null`!), immediately crashing a 256 MiB or 512 MiB heap.
- **Concrete Failing Input:**
  - **XLSX OOM:** An `.xlsx` with 100,000 rows where cell `A1` has `<c r="A1" t="inlineStr"><is><t>192.0.2.1</t></is></c>` and all remaining 99,999 rows use shared strings (`t="s"`) or numbers (`<v>123</v>`): `batch.size()` stays `1`, `batch.isEmpty()` is `false`, and `items` buffers all ~2,500,000 `XMLEvent`s in memory (`OutOfMemoryError` under `-Xmx256m`).
  - **JSON OOM:** `{"header": "192.0.2.1", "data": [1, 2, 3, ... 50000000 integers ...]}`: `batch.size()` stays `1`, and `pending` buffers 100,000,000 `String` objects in memory (`OutOfMemoryError`).
- **Fix:**
  1. In `XlsxFormat.java:547`, change `(batch.isEmpty() && items.size() >= 4096)` to `items.size() >= 4096` (unconditionally flush whenever `items.size() >= 4096`, regardless of whether `batch` has 0 or 1 items).
  2. In `JsonFormat.java:318-320`, track `pendingChars` across `pending` and call `flush()` inside `emit()` whenever `pending.size() >= 4096 || pendingChars >= ValueRewriter.BATCH_CHARS`.
  3. Lower `JsonFormat.MAX_STRING` from `64 * 1024 * 1024` to `4 * 1024 * 1024` (4M chars, matching `ValueRewriter.BATCH_CHARS`), and in `JsonFormat.Lexer.string()`, skip building `raw`, `value`, and `int[] starts` altogether when `out == null` (`JsonFormat.isValid()`).

#### F-20 — Multi-pass full-file reads on 2 GiB inputs (up to 3 full scans in `Detection` + `JsonFormat.isValid` + `Sanitizer.run`) and unbounded byte buffering in `PcapContainer` (`512 * 64 MiB = 32 GiB` worst case)
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/Detection.java:36,46`, `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/TextEncoding.java:34`, `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/pcap/PcapContainer.java:25-26,165-171`
- **Severity:** **HIGH**
- **Analysis:**
  1. **Three Full-File Reads on Every 2 GiB JSON/Text Upload (`Detection.java:36,46`):**
     When a 2 GiB JSON file is uploaded:
     - Pass 1: `TextEncoding.detect(file)` (`Detection.java:36`) calls `TextEncoding.isValidUtf8(file)` (`TextEncoding.java:34`), reading all **2 GiB** from disk to check UTF-8 validity.
     - Pass 2: `Detection.of(input)` sees `{` or `[` in the sniff buffer and calls `JsonFormat.isValid(in, encoding)` (`Detection.java:46`), reading and lexing all **2 GiB** from disk a second time (and allocating `Decoded` string buffers for every string in the 2 GiB file).
     - Pass 3: `Sanitizer.run()` calls `JsonFormat.process()`, reading and lexing all **2 GiB** a third time.
     With `SanitizerService` running 2 concurrent worker threads (`Executors.newFixedThreadPool(2)` at `SanitizerService.java:94`), two 2 GiB uploads cause 12 GiB of redundant disk reads.
  2. **`PcapContainer` Unbounded Byte Batching (`PcapContainer.java:25-26,165-171`):**
     `PcapContainer` defines `MAX_RECORD = 64 * 1024 * 1024` (64 MiB per packet/block) and `BATCH = 512`, and only flushes `batchFrames` when `batchFrames.size() >= BATCH` (`PcapContainer.java:171`). There is **no byte cap** on `batchFrames`! A crafted PCAP or pcapng file containing 512 frames of 4 MiB each (or 32 frames of 64 MiB each) buffers **2 GiB to 32 GiB of `byte[]` arrays in heap simultaneously** before calling `flush()`, immediately crashing the service with `OutOfMemoryError`.
- **Concrete Failing Input:**
  A valid 256 MiB PCAP file with `snaplen = 65535` containing 500 frames of 512 KiB each (e.g., super-jumbo/offload frames or `MAX_RECORD` up to 64 MiB): `PcapContainer` buffers all 500 frames (`250 MiB` of raw `byte[]` plus `250 MiB` of cloned output `byte[]` inside `flush()`) in heap at once, exceeding a 256 MiB heap. With 16 frames of 32 MiB (`capLen = 33,554,432`), heap usage exceeds 1 GiB before `batchFrames.size()` even reaches 17.
- **Fix:**
  1. In `PcapContainer`, track `batchBytes` alongside `batchFrames` and call `flush()` when `batchFrames.size() >= BATCH || batchBytes >= 4 * 1024 * 1024` (4 MiB), and reduce `MAX_RECORD` from 64 MiB to a realistic capture bound (e.g., 1 MiB or 4 MiB).
  2. In `JsonFormat.Lexer.string()`, when `out == null` (`isValid`), validate UTF-8 and JSON string escapes in-place without allocating `StringBuilder raw`, `StringBuilder value`, or `int[] starts`.

#### F-21 — XLSX zip-bomb / multi-entry amplification: no cap on total uncompressed bytes or entry count across the ZIP archive, and StAX `coalescing=true` allows large text allocations per element
- **Location:** `ui2/sanitizer/src/main/java/com/securityexpert/nexus/ui2/sanitizer/format/XlsxFormat.java:86-127,139`
- **Severity:** **MEDIUM**
- **Analysis:**
  `XlsxFormat.process()` opens the archive with Apache POI's `new ZipSecureFile(input.toFile())` (`XlsxFormat.java:86`), which checks per-entry compression ratio (`minInflateRatio = 0.01`, i.e. 100:1) and per-entry max size (POI default `MAX_ENTRY_SIZE = 0xFFFFFFFFL` = **4 GiB per entry**!). However:
  1. `XlsxFormat` imposes **no limit on the total number of ZIP entries** (an archive with 100,000 tiny XML entries iterates and initializes StAX parsers 100,000 times) and **no limit on cumulative uncompressed bytes** across all entries (`totalUncompressedBytes`). A 50 MiB `.xlsx` containing 50 entries that each inflate 99:1 (just above POI's `0.01` ratio threshold) expands to **4.95 GiB** of XML processing and disk output.
  2. In `XlsxFormat.xmlInputFactory()` (`XlsxFormat.java:139`), `f.setProperty(XMLInputFactory.IS_COALESCING, true)` forces the JDK StAX parser to coalesce adjacent character/CDATA chunks into a single `Characters` event in memory (up to POI's 10 MiB text limit per element).
- **Concrete Failing Input:**
  An `.xlsx` archive with 20 worksheet entries (`/xl/worksheets/sheet1.xml` .. `sheet20.xml`), each 5 MiB compressed with a 95:1 compression ratio (475 MiB uncompressed each, passing `ZipSecureFile`'s 100:1 `0.01` ratio check): processing expands 100 MiB of input into **9.5 GiB** of uncompressed XML streams.
- **Fix:** Wrap every `zip.getInputStream(entry)` in a cumulative byte-counting `InputStream` that enforces a strict per-job total uncompressed byte budget (e.g., `MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024`) and cap the maximum number of ZIP entries per `.xlsx` archive (e.g., `MAX_ZIP_ENTRIES = 4096`).

---

## Test Suite & Repository Gate Verification

All Data Sanitizer unit, API, CLI, architecture, and integration test suites were executed in `/Users/OzanDur/Codo/nexus-rev-sanitizer`:

| Suite / Gate | Command | Total | Passed | Skipped | Failed | Notes |
|---|---|---:|---:|---:|---:|---|
| `:sanitizer:test` (`IpPermutationTest`) | `./gradlew :sanitizer:test` | 5 | 5 | 0 | 0 | Involution, prefix preservation, reserved ranges |
| `:sanitizer:test` (`TextRulesTest`) | `./gradlew :sanitizer:test` | 17 | 17 | 0 | 0 | IPv4/v6, CIDR, MAC, FQDN, URL, email, syslog |
| `:sanitizer:test` (`RoundTripTest`) | `./gradlew :sanitizer:test` | 8 | 8 | 0 | 0 | TXT, CSV, JSON, XLSX, PCAP round-trip |
| `:sanitizer:test` (`MillionRowStreamingTest`) | `./gradlew :sanitizer:test` | 2 | 2 | 0 | 0 | 1M-row CSV & 100k-row XLSX streaming |
| `:sanitizer:test` (`NexusPcapTest`) | `./gradlew :sanitizer:test` | 6 | 6 | 0 | 0 | DNS compression, pcapng blocks, tunnels |
| `:sanitizer:test` (`PcapParityTest`) | `./gradlew :sanitizer:test` | 4 | 3 | 1 | 0 | 1 test skipped (`assumeTrue` when PacketBreaker reference checkout not present) |
| `:cli:test` (`SanitizerCliTest`) | `./gradlew :cli:test --tests "*Sanitizer*"` | 2 | 2 | 0 | 0 | Offline CLI sanitize & vault round-trip |
| `:service:test` (`SanitizerApiTest`) | `./gradlew :service:test --tests "*Sanitizer*"` | 8 | 8 | 0 | 0 | RBAC (`aiview` 403, `operator` own-job, `security_admin` all-jobs), preview, download |
| `:architecture-tests:architectureTest` (`SanitizerModuleFenceTest`) | `./gradlew :architecture-tests:architectureTest --tests "*SanitizerModuleFenceTest*"` | 2 | 2 | 0 | 0 | Module isolation fence |
| `:integration-tests:integrationTest` (`SanitizerVaultIntegrationTest`) | `./gradlew :integration-tests:test --tests "*Sanitizer*"` | 1 | 0 | 0 | 1* | *Requires live PostgreSQL 16 (`UI2_TEST_JDBC_URL` or local Docker daemon for Testcontainers; fails closed when neither is running on host) |
| Repository Privacy Gate | `python3 tools/privacy/repository_privacy_check.py` | 2163 files | PASS | — | 0 | Zero privacy/DLP findings |
