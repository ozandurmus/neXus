# PO decision record - 2026-11-10: advisory intake through the proxy, public registries, proposed fix rules

**Status: RATIFIED - PO DECISION (2026-11-10).** The four questions were asked in the PO session and the answers were
relayed by it to the engineering session, which recorded them here. They close decisions D7 and D14 and unknown U11
of `LIFECYCLE_SECURITY_CHECKS_DESIGN_2026_11_10.md` and complete decision D4 (online intake through the corporate
proxy, PO 2026-11-10).

## 1. Proxy authentication

Question: "Proxy kimlik doğrulama istiyor mu?" Answer (verbatim): "Kimlik doğrulama yok".

- The advisory fetcher sends no proxy credential and holds none.
- The PO gave no proxy address. The PO session's instruction: reuse the proxy the image build jobs already use
  (`deploy/ui2-image-build/30-build-job.yaml`, `31-build-job-proxy.yaml.template`). The address is deployment
  configuration: the fetcher reads it from a ConfigMap created on the host, and it is never written into a new
  manifest, a pull request, a commit message or chat.

## 2. TLS inspection

Question: "Proxy TLS denetimi yapıyor mu?" Answer (verbatim): "Hayır, denetim yok".

- The fetcher validates every source certificate against the public trust store of its runtime. No corporate CA is
  added, and certificate validation is never switched off for this workload.
- **Stop rule:** if a source ever presents a chain that does not validate (for example a re-signed chain), the
  fetch of that source fails, nothing is ingested from it, the feed run is recorded as failed with the reason, and
  it is reported to the PO. There is no fallback to an unvalidated connection.

## 3. Public registries as sources

Question: "CVE Programı ve CISA KEV kaynak olarak kullanılsın mı?" Answer (verbatim): "İkisi de kullanılsın
(Önerilen)".

- The CVE Program records (CVE JSON 5) and the CISA Known Exploited Vulnerabilities catalog are approved sources
  next to the vendors' own pages. They supply the Check Point, Infoblox, Broadcom and Radware CVE records and the
  "exploited in the wild" flag.
- Principle 1 of the design is unchanged: whole public sets or fixed public documents are downloaded and matched
  inside neXus; no request carries a device version, model, serial or name.

## 4. Fix rules where the vendor publishes no machine-readable fixed version

Question: "neXus eşleşmeyi önersin, bir yönetici tek tıkla onaylasın mı?" Answer (verbatim): "Önersin, yönetici
onaylasın (Önerilen)".

- neXus builds the fix rule from the vendor's official pages and shows it with the lines it was read from; a
  `security_admin` confirms it with one click, or rejects it.
- **Unconfirmed matches are pending.** A finding that depends on an unconfirmed rule is shown as pending
  (`NEEDS_REVIEW`) with the proposal next to it, and it stays out of reports and out of every summary count until
  the rule is confirmed.

## 5. First reach measurement (2026-11-10, after these answers)

One short-lived pod without any secret, in the neXus namespace, sent GET requests through the configured proxy with
normal certificate validation. No device and no estate data was involved.

| Source host | Result | Certificate chain |
| --- | --- | --- |
| `security.paloaltonetworks.com` (RSS and one CVE JSON) | 200 | public CA |
| `filestore.fortinet.com` (PSIRT RSS) | 200 | public CA |
| `www.cisco.com` (CSAF provider metadata, index, changes) | 200 with a named client string; 403 with an unnamed one | public CA |
| `support.checkpoint.com` (sk185114) | 200 | public CA |
| `sc1.checkpoint.com` (R81.20 Jumbo resolved-issues page) | 200 | public CA |
| `cveawg.mitre.org` (one CVE record) | 200 | public CA |
| `raw.githubusercontent.com` (the same record in `cvelistV5`) | 200 | public CA |
| `www.cisa.gov` (known-exploited catalog) | 200 | public CA |

- Every chain validated against the public trust store and ended at a public root: no re-signed chain was seen.
- The proxy needed no authentication.
- The Cisco site refuses a request without a recognisable client string, so the fetcher names itself in its
  `User-Agent`. That string identifies the software only; it carries no estate or contact detail.
- The R82 Jumbo resolved-issues address tried in this run returned 404; its real address is found when the Check
  Point lane is built.
