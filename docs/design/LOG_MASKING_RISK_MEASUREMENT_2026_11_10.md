# Log masking risk measurement - 2026-11-10 (counts and token classes only)

Status: EVIDENCE RECORD (non-authoritative). Measurement for the PO decision record of 2026-11-10 on the log anomaly
agent, answer A2 and §2 item 2 ("Log risk analysis comes before the level decision"). Inputs:
[PO_DECISION_RECORD_2026_11_10_LOG_ANOMALY_AGENT.md](PO_DECISION_RECORD_2026_11_10_LOG_ANOMALY_AGENT.md),
[LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md](LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md) (DRAFT; §3.1 levels, §3.2 masked
classes, §3.3 egress profile, §10 measurement E5) and
[ARGON_LOG_ANOMALY_AGENT_REVIEW_2026_11_10.md](ARGON_LOG_ANOMALY_AGENT_REVIEW_2026_11_10.md) (findings 1 and 2). Masking under test: `LogMasking` rule set `logmask-v4` with the Data Sanitizer text rules and the
estate dictionary, as deployed on HOST-A on 2026-11-10 (the service image a concurrent ship rolled out during this session).

No raw log line, word, name, address or serial left HOST-A or appears here.

## 0. Answer in short

- **36.2%** of the 2.92 million sampled lines carry at least one identity token in raw form (address, host, user,
  serial, MAC, policy-domain name ...).
- **Today's masking (`logmask-v4`) catches the shaped identities almost completely**: private and public IPv4
  99.8% / 100%, host names 99.9%, syslog and elg header hosts 100%, serial shapes, MAC, FQDN, URL hosts and e-mail
  100%.
- **It does not catch names that are plain words**, because they have no shape and are not in its dictionary:
  administrator names only 57.4% caught, policy and device-group names 19.7%, virtual-system names 7.9%, object names
  87.7%, MDS domain names and zone names 0% (small counts).
- **If we sent today**:
  - **Level 2** (masked excerpts with today's masking): **2.03%** of all lines (59,284) would carry an identity, almost
    all of them a name (§4.1).
  - **Level 1** (templates, no egress profile yet): **1.94%** (56,758). Templates remove numbers, not words, so Level 1
    is barely safer than Level 2 with today's tools.
  - With the **drafted egress profile** (English word list): **0.40%** (11,594 lines). The survivors are names that are
    English words, exactly Argon finding 1.
  - With the **egress profile and Argon's fixes** (technical vocabulary only, typed numbers): **21 lines** (0.0007%),
    from one rule gap that the extended estate dictionary would also close (§4.2).
- **Recommendation**: the level matters less than the profile. Pilot at **Level 1**, and only after two changes:
  1. the estate dictionary is extended with administrator, policy, device-group, virtual-system, object, zone and
     domain names;
  2. `egress-v1` is built with Argon's fixes.
  Re-run this measurement after both. Level 2 becomes defensible when that re-run shows zero surviving identities
  (§6).

## 1. What was asked

The PO: "Burada zaten sanitizer modülümüz var. Logları önce bir göz analiz edip orada neler riskli bakıp ona göre veri
çıkışı düşünmek gerek." (We already have the sanitizer. First analyse the logs for what is risky, then decide on
egress.) Per source: lines and chunks scanned; per token class the occurrences and the share `logmask-v4` catches;
residual words after masking that are not in a small technical vocabulary, as counts and shapes.

This record also gives, on real chunks, the suppression-rate part of design measurement E5. The planted-identity part
of E5 (synthetic corpus through profile and guard) is still open.

## 2. Method

### 2.1 Where it ran and what left HOST-A

- A one-off Java program, `scripts/measurement/log_masking_risk/LogRiskMeasure.java`, ran as a second JVM inside the
  ui2-service pod on the deployed service jar (`PropertiesLauncher`, `loader.main`). It used the service's own keys
  (privacy HMAC key, artefact-store key) and the same classes the masked Logs view uses: `LogMasking` (`logmask-v4`)
  with the estate dictionary and the inventory pseudonyms, one `LogMasking.Session` per chunk.
- Database access was read-only at session level (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`); the
  artefact store was only read. No device command was sent. No row was written.
- Raw lines, masked lines and every name or value stayed in that JVM's memory. The program prints fixed labels and
  numbers only; each printed field must match `[a-z0-9_.]+`, library logging is off and `System.out` / `System.err`
  of libraries are discarded. A second filter on the pod (`show_safe.sh`) prints only lines whose every field matches
  the same pattern and counts the rest: it counted **0** filtered lines in every run.
- What reached the workstation and this record: the counts in §3. No log line, word, name, address or serial.

### 2.2 Sample

Per source, up to three chunks per device (newest, median and oldest stored chunk), devices taken round-robin, at most
2 MiB read from each chunk (cut at the last line feed) and at most 60 MiB per source. All ten stored sources were
scanned, including `pan_config`, `cp_spike_csv` and `cp_mq_mng_elg`, which the design already excludes from egress.

### 2.3 Detectors (on the raw line, independent of `LogMasking`)

| Class | Detector |
|---|---|
| `ipv4_private`, `ipv4_public`, `ipv4_special` | Dotted quad, octets <= 255. Special = 0/8, 127/8, 169.254/16, 224/4 and above (multicast, broadcast, netmasks): kept by the masking on purpose |
| `ipv6`, `ipv6_special` | Hex groups with `::` or 7 colons that parse as an IPv6 literal; special = link-local, loopback, multicast, unspecified, IPv4-mapped |
| `mac` | Six hex pairs (`:` or `-`) or Cisco dotted form; all-zero and broadcast excluded |
| `fqdn` | Two or more labels, two with a letter, last label a public TLD or a common internal suffix (`lan`, `local`, `corp` ...), not a file extension |
| `url_host`, `email` | Host part of a URL with a scheme; e-mail address |
| `host_known` | A token equal to a device host name, cluster name or management-server name from the device tables |
| `host_header` | The host field of a syslog header and of the Check Point elg header `]@HOST[` |
| `user_known` | A token equal to an administrator name from stored policy history (`changed_by`, `last-modifier`) |
| `user_mention` | Name after `user=`, `admin`, `account`, `for ... from`, `by`, JSON `admin`/`user` keys; vocabulary words excluded |
| `serial_known`, `serial_shape` | A recorded device serial; a serial-shaped token (Palo Alto 12-15 digits with leading 0, upper-case letters and 4+ digits) or a `serial=` value |
| `name_rule`, `name_policy`, `name_object`, `name_zone`, `name_vs`, `name_domain` | A token (or a multi-word phrase) equal to a name from the stored policy snapshots, object inventory, interface zones and contexts, installed policy names and MDS domains (§3.2) |
| `interface_known`, `interface_shape`, `interface_vlan_suffix` | A recorded interface name; a vendor interface shape; the same with a `.NNN` sub-interface (VLAN) suffix |
| `num_asn`, `num_vlan`, `num_vsid` | A number after `AS`/`ASN`/`remote-as`..., after `vlan`, after `vs`/`vsid`/`vs_`; value 0 not counted |
| `num_5digit` | A free-standing five-digit number (the design's "up to five digits" allowance) |
| `secret_marker`, `private_key_marker` | A value after `password=`, `token:`, `community`, `psk` ... that is not already `<redacted>`; a PEM private-key header |
| `high_entropy` | A 32+ hex run, or a 24+ base64-alphabet token with mixed case, digits and Shannon entropy >= 4.0 bits per character |

A known name is used only if it has three or more characters, contains a letter, is not numeric, an id or an address,
and is not made only of technical-vocabulary words ("Cleanup rule", "inside", "Any" are generic, not identities).
Classes overlap on purpose (a host name in a syslog header is both `host_known` and `host_header`).

### 2.4 Profiles (what each disclosure option would send)

| Profile | Meaning | Built as |
|---|---|---|
| p0 | Level 2 if we sent today: masked excerpts with the existing masking | The `logmask-v4` masked line |
| p1 | Level 1 if we sent today: templates from a plain template builder, no egress profile | p0 with every digit run replaced |
| p2 | `egress-v1` as drafted in design §3.3 | p0, then every word not in (technical vocabulary + English word list) becomes `<W>`; numbers up to 5 digits, interface names and versions kept |
| p3 | `egress-v1` with Argon finding 1 applied | p0, then every word not in the technical vocabulary becomes `<W>`; every number becomes `<N>` except up to 4 digits after `signal`/`exit`/`errno`/`status`/`code`; an interface's VLAN suffix becomes `<VLAN>` |

"Survives" means the raw value is still present, case-insensitively and as a whole token, in the profile's output for
that line. Masked tokens (`h-`, `USR-`, `SN-`, `FW-...` pseudonyms), permuted addresses and times are kept in p2 and
p3 because at egress they would become request aliases.

The technical vocabulary (`techvocab.txt`, 1,780 words with the masking's own stop-word lists) was written blind,
without reading any log text: English function words, log verbs and nouns, networking, security, operating-system,
Check Point and Palo Alto terms, and generic zone and rule names. The English word list is the workstation's
`/usr/share/dict/words` (234,428 lower-case words): a deliberately generous stand-in for the design's "English words".

## 3. Results

### 3.1 Coverage

| Source | Chunks | Devices | Lines | MB | Lines with an identity token |
|---|---:|---:|---:|---:|---:|
| `cp_cpd_elg` | 140 | 55 | 1,194,857 | 59.3 | 220,501 (18.5%) |
| `cp_cpwd_elg` | 122 | 54 | 136,047 | 60.0 | 136,026 (100.0%) |
| `cp_fwd_elg` | 155 | 52 | 332,243 | 49.5 | 53,617 (16.1%) |
| `cp_fwk_elg` | 119 | 55 | 443,552 | 59.9 | 271,316 (61.2%) |
| `cp_messages` | 165 | 55 | 362,483 | 44.3 | 282,308 (77.9%) |
| `cp_mq_mng_elg` | 93 | 55 | 210,036 | 60.0 | 0 (0.0%) |
| `cp_spike_csv` | 73 | 55 | 25,429 | 0.9 | 0 (0.0%) |
| `cp_spike_log` | 163 | 55 | 123,523 | 20.9 | 0 (0.0%) |
| `pan_config` | 114 | 38 | 3,891 | 2.2 | 3,891 (100.0%) |
| `pan_system` | 102 | 38 | 89,450 | 58.9 | 89,450 (100.0%) |
| **all** | 1246 | | 2,921,511 | 416.0 | 1,057,109 (36.2%) |

Meta: tech_vocab_words=1780, english_words=234428, dictionary_seconds=13, chunks_chosen=1246, total_seconds=378. Errors: none. Line-count mismatches after masking: 0.

### 3.2 Known-name dictionary built from stored snapshots

| Class | Entries | Dropped as generic (technical words only) | Entries that are English words | Multi-word entries | Dropped by shape (short, numeric, id, address) |
|---|---:|---:|---:|---:|---:|
| `host_known` | 168 | 0 | 0 | 3 | 159 |
| `user_known` | 49 | 2 | 0 | 0 | 0 |
| `serial_known` | 56 | 0 | 0 | 0 | 39 |
| `name_rule` | 5,808 | 7,623 | 2 | 624 | 13,668 |
| `name_policy` | 274 | 24 | 4 | 146 | 8 |
| `name_object` | 20,278 | 4,303 | 31 | 739 | 821,706 |
| `name_zone` | 316 | 23 | 0 | 1 | 1 |
| `name_vs` | 73 | 7 | 1 | 0 | 9 |
| `name_domain` | 2 | 0 | 0 | 0 | 0 |
| `interface_known` | 785 | 17 | 0 | 3 | 6 |

### 3.3 Token classes, all sources

Survival = the raw value is still present in the line after the profile. p0 = `logmask-v4` masked line (what Level 2 would send today); p1 = p0 with every digit run replaced (a naive template, what Level 1 would send today); p2 = `egress-v1` as drafted (technical + English vocabulary, numbers up to 5 digits and interface names kept); p3 = `egress-v1` with Argon's fixes (technical vocabulary only, numbers typed, VLAN suffix typed).

| Class | Occurrences | Distinct (per source, summed) | Caught by logmask-v4 | Survives p0 | Survives p1 | Survives p2 | Survives p3 | Value is an English word |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `ipv4_private` | 603,670 | 12,710 | 99.8% | 1,479 | 0 | 0 | 0 | 0 |
| `ipv4_public` | 7,868 | 36 | 100.0% | 0 | 0 | 0 | 0 | 0 |
| `ipv6` | 0 | | | | | | | |
| `mac` | 297 | 14 | 100.0% | 0 | 0 | 0 | 0 | 0 |
| `fqdn` | 7,358 | 11 | 100.0% | 0 | 0 | 0 | 0 | 0 |
| `url_host` | 2,726 | 9 | 100.0% | 0 | 0 | 0 | 0 | 0 |
| `email` | 13 | 3 | 100.0% | 0 | 0 | 0 | 0 | 0 |
| `host_known` | 735,864 | 313 | 99.9% | 957 | 957 | 0 | 0 | 0 |
| `host_header` | 377,544 | 166 | 100.0% | 0 | 0 | 0 | 0 | 0 |
| `user_known` | 99,985 | 13 | 57.4% | 42,590 | 42,590 | 0 | 0 | 0 |
| `user_mention` | 95,381 | 43 | 99.8% | 225 | 225 | 0 | 0 | 63 |
| `serial_known` | 0 | | | | | | | |
| `serial_shape` | 186,682 | 76 | 100.0% | 0 | 0 | 0 | 0 | 0 |
| `name_rule` | 7 | 3 | 0.0% | 7 | 7 | 3 | 0 | 0 |
| `name_policy` | 23,369 | 84 | 19.7% | 18,758 | 17,679 | 8,585 | 0 | 7,672 |
| `name_object` | 48,634 | 27 | 87.7% | 6,000 | 6,000 | 2,124 | 24 | 2,123 |
| `name_zone` | 40 | 1 | 0.0% | 40 | 40 | 0 | 0 | 0 |
| `name_vs` | 6,320 | 20 | 7.9% | 5,820 | 5,820 | 1,076 | 0 | 1 |
| `name_domain` | 498 | 2 | 0.0% | 498 | 498 | 0 | 0 | 0 |
| `secret_marker` | 0 | | | | | | | |
| `private_key_marker` | 0 | | | | | | | |
| `ipv4_special` | 18,833 | 18 | 0.0% | 18,833 | 0 | 17,895 | 17,827 | 0 |
| `ipv6_special` | 1,155 | 1 | 0.0% | 1,155 | 0 | 1,155 | 1,155 | 0 |
| `interface_known` | 154,694 | 310 | 0.0% | 154,694 | 0 | 154,118 | 143,605 | 0 |
| `interface_shape` | 235,258 | 425 | 7.0% | 218,878 | 41,506 | 218,878 | 208,769 | 22,619 |
| `interface_vlan_suffix` | 10,109 | 240 | 0.0% | 10,109 | 0 | 10,109 | 0 | 0 |
| `num_asn` | 0 | | | | | | | |
| `num_vlan` | 0 | | | | | | | |
| `num_vsid` | 10,700 | 8 | 0.0% | 10,696 | 0 | 10,696 | 0 | 0 |
| `num_5digit` | 728,787 | 34,343 | 0.0% | 728,787 | 0 | 728,769 | 0 | 0 |
| `high_entropy` | 28,242 | 285 | 97.9% | 581 | 0 | 120 | 0 | 0 |

### 3.4 Surviving identity tokens per source (occurrences surviving p0 / p1 / p2 / p3)

| Source | `ipv4_private` | `ipv4_public` | `mac` | `fqdn` | `url_host` | `email` | `host_known` | `host_header` | `user_known` | `user_mention` | `serial_shape` | `name_rule` | `name_policy` | `name_object` | `name_zone` | `name_vs` | `name_domain` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `cp_cpd_elg` | 12,921: 1,479/0/0/0 | 361: 0/0/0/0 | 266: 0/0/0/0 | 397: 0/0/0/0 | 397: 0/0/0/0 | 13: 0/0/0/0 | 207,372: 957/957/0/0 | 201,329: 0/0/0/0 |  |  |  |  | 5,309: 722/513/1/0 | 2,230: 2,230/2,230/38/0 | 40: 40/40/0/0 |  | 498: 498/498/0/0 |
| `cp_cpwd_elg` |  |  |  |  |  |  | 136,026: 0/0/0/0 | 136,026: 0/0/0/0 |  | 221: 221/221/0/0 |  |  |  | 10: 10/10/0/0 |  |  |  |
| `cp_fwd_elg` | 7,887: 0/0/0/0 |  |  |  | 2,022: 0/0/0/0 |  | 40,136: 0/0/0/0 | 40,136: 0/0/0/0 |  |  |  | 6: 6/6/3/0 | 7,653: 7,653/7,652/7,653/0 | 2,022: 0/0/0/0 |  |  |  |
| `cp_fwk_elg` | 542,443: 0/0/0/0 | 29: 0/0/0/0 |  |  |  |  | 51: 0/0/0/0 | 53: 0/0/0/0 |  | 27: 0/0/0/0 |  |  |  |  |  |  |  |
| `cp_messages` | 100: 0/0/0/0 |  |  | 96: 0/0/0/0 | 270: 0/0/0/0 |  | 258,938: 0/0/0/0 |  | 96,447: 42,590/42,590/0/0 | 83,841: 4/4/0/0 |  |  |  | 1,578: 1,578/1,578/1,578/24 |  | 1: 1/1/1/0 |  |
| `cp_mq_mng_elg` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| `cp_spike_csv` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| `cp_spike_log` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| `pan_config` | 3,892: 0/0/0/0 | 3: 0/0/0/0 |  | 30: 0/0/0/0 |  |  | 3,891: 0/0/0/0 |  | 41: 0/0/0/0 | 2,597: 0/0/0/0 | 7,782: 0/0/0/0 | 1: 1/1/0/0 |  |  |  |  |  |
| `pan_system` | 36,427: 0/0/0/0 | 7,475: 0/0/0/0 | 31: 0/0/0/0 | 6,835: 0/0/0/0 | 37: 0/0/0/0 |  | 89,450: 0/0/0/0 |  | 3,497: 0/0/0/0 | 8,695: 0/0/0/0 | 178,900: 0/0/0/0 |  | 10,407: 10,383/9,514/931/0 | 42,794: 2,182/2,182/508/0 |  | 6,319: 5,819/5,819/1,075/0 |  |

### 3.5 Lines that would carry at least one identity token

| Source | Lines | p0 (Level 2 today) | p1 (Level 1 today) | p2 (egress-v1 draft) | p3 (egress-v1 + Argon fixes) |
|---|---:|---:|---:|---:|---:|
| `cp_cpd_elg` | 1,194,857 | 5,926 (0.5%) | 4,238 (0.4%) | 39 (0.0%) | 0 (0.0%) |
| `cp_cpwd_elg` | 136,047 | 225 (0.2%) | 225 (0.2%) | 0 (0.0%) | 0 (0.0%) |
| `cp_fwd_elg` | 332,243 | 7,659 (2.3%) | 7,658 (2.3%) | 7,656 (2.3%) | 0 (0.0%) |
| `cp_fwk_elg` | 443,552 | 0 (0.0%) | 0 (0.0%) | 0 (0.0%) | 0 (0.0%) |
| `cp_messages` | 362,483 | 27,737 (7.7%) | 27,737 (7.7%) | 1,444 (0.4%) | 21 (0.0%) |
| `cp_mq_mng_elg` | 210,036 | 0 (0.0%) | 0 (0.0%) | 0 (0.0%) | 0 (0.0%) |
| `cp_spike_csv` | 25,429 | 0 (0.0%) | 0 (0.0%) | 0 (0.0%) | 0 (0.0%) |
| `cp_spike_log` | 123,523 | 0 (0.0%) | 0 (0.0%) | 0 (0.0%) | 0 (0.0%) |
| `pan_config` | 3,891 | 1 (0.0%) | 1 (0.0%) | 0 (0.0%) | 0 (0.0%) |
| `pan_system` | 89,450 | 17,736 (19.8%) | 16,899 (18.9%) | 2,455 (2.7%) | 0 (0.0%) |

### 3.6 Residual words after masking (not in the technical vocabulary)

| Source | Word tokens | Residual occurrences (p3 suppression) | Residual distinct | Residual under the drafted vocabulary (p2) | Distinct residual that are English words | Distinct residual that are known names | Distinct on 1 device / 2-5 / 6+ |
|---|---:|---:|---:|---:|---:|---:|---:|
| `cp_cpd_elg` | 4,072,773 | 1,593,967 (39.1%) | 30,351 | 1,096,828 (26.9%), 30,091 distinct | 51 | 59 | 28,560 / 945 / 846 |
| `cp_cpwd_elg` | 1,300,535 | 380,113 (29.2%) | 49 | 342,715 (26.4%), 40 distinct | 8 | 1 | 16 / 6 / 27 |
| `cp_fwd_elg` | 1,950,393 | 546,178 (28.0%) | 1,299 | 478,378 (24.5%), 1,216 distinct | 47 | 2 | 907 / 152 / 240 |
| `cp_fwk_elg` | 4,550,186 | 839,020 (18.4%) | 743 | 716,471 (15.7%), 670 distinct | 58 | 0 | 361 / 208 / 174 |
| `cp_messages` | 3,863,950 | 1,080,237 (28.0%) | 29,984 | 955,153 (24.7%), 19,609 distinct | 79 | 6 | 28,932 / 641 / 411 |
| `cp_mq_mng_elg` | 3,852,236 | 504,690 (13.1%) | 93 | 481,081 (12.5%), 88 distinct | 3 | 0 | 1 / 26 / 66 |
| `cp_spike_csv` | 76,589 | 10,619 (13.9%) | 32 | 10,364 (13.5%), 29 distinct | 1 | 0 | 10 / 14 / 8 |
| `cp_spike_log` | 2,718,292 | 395,433 (14.5%) | 74 | 184,386 (6.8%), 69 distinct | 3 | 0 | 29 / 26 / 19 |
| `pan_config` | 136,693 | 50,408 (36.9%) | 36 | 46,453 (34.0%), 30 distinct | 3 | 1 | 13 / 8 / 15 |
| `pan_system` | 4,193,550 | 1,534,785 (36.6%) | 10,859 | 1,323,559 (31.6%), 10,779 distinct | 52 | 58 | 10,621 / 131 / 107 |

### 3.7 Residual word shapes (occurrences, distinct)

| Source | `lowercase_word` | `capitalized_word` | `uppercase_word` | `camelcase_word` | `word_with_underscore` | `word_with_hyphen` | `dotted_name` | `path_like` | `letters_and_digits` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `cp_cpd_elg` | 263,460 (86) | 70,247 (18) | 80,022 (19) | 334,034 (101) | 628,549 (697) | 71,055 (1,122) | 727 (1) | 117,376 (28,302) | 28,497 (5) |
| `cp_cpwd_elg` | 115,873 (13) | 24 (2) | 2,395 (8) | 136,044 (2) | 88,588 (20) | 2,238 (1) |  | 34,951 (3) |  |
| `cp_fwd_elg` | 60,285 (84) | 23,355 (14) | 2,336 (5) | 137,123 (48) | 157,504 (211) | 1,499 (17) | 1,965 (3) | 131,763 (915) | 30,348 (2) |
| `cp_fwk_elg` | 59,081 (94) | 21,846 (18) | 17,477 (13) |  | 704,974 (183) | 30,914 (153) | 6 (6) | 2,974 (266) | 1,748 (10) |
| `cp_messages` | 643,524 (185) | 28,860 (23) | 2,740 (24) | 13,466 (14) | 138,556 (3,214) | 100,741 (66) | 13 (7) | 123,909 (228) | 28,428 (26,223) |
| `cp_mq_mng_elg` | 111,148 (19) | 23,271 (3) |  |  | 269,230 (23) | 37,104 (27) |  | 63,799 (9) | 138 (12) |
| `cp_spike_csv` | 9,705 (8) |  |  | 108 (1) | 798 (21) |  |  |  | 8 (2) |
| `cp_spike_log` | 389,269 (12) |  |  | 103 (1) | 4,124 (26) |  |  | 642 (32) | 1,295 (3) |
| `pan_config` | 15,614 (12) | 3,563 (1) |  | 7 (1) | 31,131 (9) | 70 (7) |  | 23 (6) |  |
| `pan_system` | 689,111 (69) | 9,957 (17) | 47,040 (6) | 9,773 (15) | 726,334 (8,364) | 45,050 (256) | 20 (1) | 3,085 (746) | 4,415 (1,385) |

### 3.8 Gaps ranked by surviving occurrences at p0 (identity classes)

| Rank | Class | Survives p0 | Survives p2 | Survives p3 | Sources |
|---:|---|---:|---:|---:|---|
| 1 | `user_known` | 42,590 | 0 | 0 | `cp_messages` |
| 2 | `name_policy` | 18,758 | 8,585 | 0 | `cp_cpd_elg`, `cp_fwd_elg`, `pan_system` |
| 3 | `name_object` | 6,000 | 2,124 | 24 | `cp_cpd_elg`, `cp_cpwd_elg`, `cp_messages`, `pan_system` |
| 4 | `name_vs` | 5,820 | 1,076 | 0 | `cp_messages`, `pan_system` |
| 5 | `ipv4_private` | 1,479 | 0 | 0 | `cp_cpd_elg` |
| 6 | `host_known` | 957 | 0 | 0 | `cp_cpd_elg` |
| 7 | `name_domain` | 498 | 0 | 0 | `cp_cpd_elg` |
| 8 | `user_mention` | 225 | 0 | 0 | `cp_cpwd_elg`, `cp_messages` |
| 9 | `name_zone` | 40 | 0 | 0 | `cp_cpd_elg` |
| 10 | `name_rule` | 7 | 3 | 0 | `cp_fwd_elg`, `pan_config` |

### 3.9 Where surviving identity tokens sit (top contexts at p0)

Labels only: the character before and after the surviving value (`sp` space, `dq` double quote, `lpar`/`rpar` parentheses, `us` underscore, `sl` slash, `eol` end of line, `bol` start of line) and the token before it (a technical-vocabulary word, `nonvocab`, `number`, `time`, or `none` when only separators precede it, as in a JSON value).

| Source | Class | Before | After | Token before | Occurrences |
|---|---|---|---|---|---:|
| `cp_cpd_elg` | `host_known` | `lpar` | `rpar` | `name` | 744 |
| `cp_cpd_elg` | `host_known` | `lpar` | `eol` | `found` | 135 |
| `cp_cpd_elg` | `host_known` | `lpar` | `rpar` | `nonvocab` | 78 |
| `cp_cpd_elg` | `ipv4_private` | `us` | `rpar` | `nonvocab` | 1,479 |
| `cp_cpd_elg` | `name_domain` | `lpar` | `rpar` | `name` | 435 |
| `cp_cpd_elg` | `name_domain` | `lpar` | `eol` | `found` | 63 |
| `cp_cpd_elg` | `name_object` | `sl` | `sl` | `nonvocab` | 1,155 |
| `cp_cpd_elg` | `name_object` | `lpar` | `rpar` | `nonvocab` | 522 |
| `cp_cpd_elg` | `name_object` | `lpar` | `rpar` | `name` | 414 |
| `cp_cpd_elg` | `name_object` | `dq` | `dq` | `nonvocab` | 135 |
| `cp_messages` | `name_object` | `sp` | `sp` | `nonvocab` | 1,248 |
| `cp_messages` | `name_object` | `sp` | `sp` | `number` | 111 |
| `cp_messages` | `name_object` | `sp` | `eol` | `nonvocab` | 60 |
| `cp_messages` | `name_object` | `other` | `sp` | `nonvocab` | 60 |
| `cp_messages` | `name_object` | `sp` | `sp` | `do` | 36 |
| `cp_messages` | `name_object` | `sp` | `sp` | `then` | 33 |
| `cp_messages` | `name_object` | `other` | `sp` | `usage` | 30 |
| `pan_system` | `name_object` | `lpar` | `rpar` | `nonvocab` | 1,658 |
| `pan_system` | `name_object` | `dq` | `dq` | `none` | 495 |
| `cp_cpd_elg` | `name_policy` | `lpar` | `rpar` | `name` | 238 |
| `cp_cpd_elg` | `name_policy` | `dq` | `dq` | `nonvocab` | 199 |
| `cp_cpd_elg` | `name_policy` | `hy` | `lbr` | `nonvocab` | 85 |
| `cp_cpd_elg` | `name_policy` | `eq` | `comma` | `nonvocab` | 73 |
| `cp_cpd_elg` | `name_policy` | `sp` | `sp` | `policy` | 69 |
| `cp_cpd_elg` | `name_policy` | `lpar` | `eol` | `none` | 49 |
| `cp_fwd_elg` | `name_policy` | `sp` | `sp` | `nonvocab` | 6,825 |
| `cp_fwd_elg` | `name_policy` | `sp` | `sp` | `and` | 747 |
| `cp_fwd_elg` | `name_policy` | `sp` | `sp` | `of` | 80 |
| `pan_system` | `name_policy` | `dq` | `dq` | `none` | 9,761 |
| `pan_system` | `name_policy` | `sp` | `comma` | `nonvocab` | 206 |
| `pan_system` | `name_policy` | `sp` | `comma` | `vocab_other` | 128 |
| `pan_system` | `name_policy` | `sp` | `sp` | `vocab_other` | 128 |
| `pan_system` | `name_policy` | `sp` | `sp` | `nonvocab` | 116 |
| `pan_system` | `name_policy` | `sp` | `sp` | `number` | 20 |
| `pan_system` | `name_vs` | `dq` | `dq` | `none` | 5,625 |
| `pan_system` | `name_vs` | `sp` | `comma` | `nonvocab` | 112 |
| `pan_system` | `name_vs` | `sp` | `comma` | `vocab_other` | 42 |
| `pan_system` | `name_vs` | `sp` | `sp` | `nonvocab` | 28 |
| `cp_cpd_elg` | `name_zone` | `lpar` | `rpar` | `name` | 22 |
| `cp_messages` | `user_known` | `sp` | `sp` | `none` | 33,420 |
| `cp_messages` | `user_known` | `sp` | `eol` | `nonvocab` | 7,559 |
| `cp_messages` | `user_known` | `sp` | `sp` | `nonvocab` | 1,600 |
| `cp_cpwd_elg` | `user_mention` | `lbr` | `sp` | `none` | 221 |


## 4. What would leak if we sent today

### 4.1 Level 2 (masked excerpts, `logmask-v4` only): p0

59,284 of 2,921,511 lines (2.03%) would carry an identity. By class, in occurrences:

| What leaks | Occurrences | Where | Context (§3.9) |
|---|---:|---|---|
| Administrator names | 42,590 | `cp_messages` only (5 distinct administrator names occur there) | The name stands without a trigger word: only separators before it (33,420), or after a non-vocabulary word (9,159). The principal rules need `user=`, `by`, `for ... from` and similar |
| Policy, policy-package and device-group names | 18,758 | `pan_system` 10,383, `cp_fwd_elg` 7,653, `cp_cpd_elg` 722 | `pan_system`: a whole JSON string value (9,761); `cp_fwd_elg`: free text; `cp_cpd_elg`: `name (X)`, quoted, `=X,` |
| Object names | 6,000 | `cp_cpd_elg` 2,230, `pan_system` 2,182, `cp_messages` 1,578 | In parentheses, inside `/` paths (1,155), as JSON values, in free text |
| Virtual-system names | 5,820 | `pan_system` | A whole JSON string value (5,625) |
| Private IPv4 addresses | 1,479 | `cp_cpd_elg` | Glued to an underscore before it and `)` after it, inside an object-name-like token: the sanitizer treats `_` as a word character, so the address rule does not fire |
| Device host names | 957 | `cp_cpd_elg` | `name (HOST)` and `found (HOST` |
| MDS domain names | 498 | `cp_cpd_elg` | `name (X)` |
| User mentions | 225 | `cp_cpwd_elg`, `cp_messages` | A name right after `[` |
| Zone names | 40 | `cp_cpd_elg` | `name (X)`, quoted |
| Rule names | 7 | `cp_fwd_elg`, `pan_config` | Free text |

Also leaving, by design rather than by a gap: special-range addresses (loopback, netmasks, multicast; 18,833), IPv6
link-local and loopback (1,155), interface names (154,694 recorded names, 310 distinct), interface VLAN suffixes
(10,109; 240 distinct), non-zero VSIDs after `vs`/`vsid` (10,696; 8 distinct), and free-standing five-digit numbers
(728,787; 34,343 distinct: process ids, ports, counters and possibly ASNs; no `AS <n>` or `vlan <n>` form was seen).

### 4.2 Level 1 (templates)

- **Today (p1, a template builder without an egress profile):** 56,758 lines (1.94%). Replacing numbers removes the
  addresses, the VSIDs and the five-digit numbers, but every name above still leaves: administrator names 42,590,
  policy names 17,679, object names 6,000, virtual-system names 5,820, domain names 498, host names 957.
- **With `egress-v1` as drafted (p2):** 11,594 lines (0.40%): policy names 8,585, object names 2,124, virtual-system
  names 1,076, rule names 3. 7,672 of the policy-name and 2,123 of the object-name occurrences are English words; the
  rest are compounds whose every part is a technical or English word. This is Argon finding 1, measured. The drafted
  number rule also keeps all 10,696 VSIDs, all 10,109 VLAN suffixes and 728,769 five-digit numbers.
- **With `egress-v1` and Argon's fixes (p3):** 21 lines, 24 occurrences, all in `cp_messages`: object names made of a
  technical word followed by digits. The profile's vocabulary check strips trailing digits from a word, so `word12`
  passes when `word` is a vocabulary word. Two fixes close it: keep digit-suffixed words out of that rule, and run the
  estate-dictionary guard G2 with object names in the dictionary.

### 4.3 What the stricter profile costs

p3 suppresses 26.0% of all word tokens (p2: 21.1%), from 13.1% (`cp_mq_mng_elg`) to 39.1% (`cp_cpd_elg`). Most
distinct suppressed words occur on one device only (69,450 of 73,520 distinct, summed per source): variable tokens.
- In `cp_cpd_elg` they are mostly paths: 28,302 distinct `path_like` tokens.
- In `cp_messages` they are letters-and-digits tokens: 26,223 distinct.
- In `pan_system` they are underscore words: 8,364 distinct.

The suppressed occurrences are dominated by a few repeated technical tokens that the blind vocabulary lacks. Examples
of the shapes involved: `CamelCase` in `cp_cpwd_elg`, underscore words in `cp_fwk_elg`. The real `egress-v1`
vocabulary, built from the E3 template dry run and reviewed by `security_admin`, should bring the occurrence rate down
without admitting names.

## 5. Masking gaps ranked by how often they occur

| Rank | Gap | Occurrences surviving `logmask-v4` | Fix | Where |
|---:|---|---:|---|---|
| 1 | Administrator names in free text without a trigger word | 42,590 | Add administrator names to the estate dictionary: policy history `changed_by` and `last-modifier`, plus the device admin lists when collected. The dictionary then masks them anywhere, as it already does for host names and serials | `LogMasking` estate dictionary (`logmask-v5`); also protects the Logs screen and aiview today (Argon finding 2) |
| 2 | Policy, package and device-group names | 18,758 | Add the policy names from `policy_snapshot`, `device_policy_install` and the policy metadata to the estate dictionary, with phrase matching for multi-word names (146 of 274) | same |
| 3 | Object names | 6,000 | Add the object names from stored policy snapshots and the object inventory (20,278, of which 739 are multi-word). Also split tokens on `/` and inside parentheses for the dictionary pass | same |
| 4 | Virtual-system names in Palo Alto JSON | 5,820 | Dictionary (73 names from interface contexts). Also mask by key: the JSON fields that carry vsys or device-group names (the key was not measured; take it from the vendor field list) | same, `IdentityFields` |
| 5 | IPv4 glued to `_` | 1,479 | Treat `_` as a delimiter for the address rule only | Data Sanitizer text rules |
| 6 | Host names in `name (HOST)` in cpd.elg | 957 | Covered by the dictionary already for most names; the miss is a name the dictionary does not hold. Add the cpd `name (X)` form as a host or identity context | `FreeTextIdentityRules` |
| 7 | MDS domain names | 498 | Dictionary (2 names) | estate dictionary |
| 8 | User mention after `[` | 225 | A principal trigger form in cpwd lines; check its shape on a sanitized copy | `FreeTextIdentityRules` |
| 9 | Zone names | 40 | Dictionary (316 names) | estate dictionary |
| 10 | Rule names | 7 | Dictionary (5,808 names, 624 multi-word) | estate dictionary |

For the egress profile, independent of the masking: drop the English word list; type numbers (`<N>`, with `<ASN>`,
`<VLAN>`, `<VSID>` where a keyword says so); replace an interface's VLAN suffix; keep digit-suffixed words out of the
vocabulary match.

Not gaps: IPv4 and IPv6 outside the kept ranges, MAC, FQDN, URL hosts, e-mail, serial shapes and syslog or elg header
hosts are caught at 99.8% to 100%. No unredacted secret marker (`password=`, `token:`, `community`, `psk` ...) and no
private-key header was found in the sample, consistent with redaction on write. Of 28,242 high-entropy tokens, 97.9%
are already masked (the serial-like rule catches most of them); 581 survive p0, 120 survive p2 and none survive p3.

## 6. Recommendation

1. **Send nothing with today's masking alone, at either level.** 2% of lines would carry a real name, and the
   administrator names alone are 42,590 occurrences in two weeks of `cp_messages` samples.
2. **Close gaps 1 to 4 first, in one change: extend the estate dictionary** with administrator, policy, device-group,
   virtual-system, object, zone and domain names from the stored snapshots, with phrase matching. This covers 73,168
   of the 76,374 surviving occurrences (95.8%); with the domain, zone and rule names the same change adds, 73,713
   (96.5%). It also protects the in-product Logs screen and aiview now, which Argon
   finding 2 asks for, before any agent work.
3. **Then gap 5** (IPv4 glued to `_`), a one-rule fix in the sanitizer text rules.
4. **Build `egress-v1` with Argon's fixes** (p3 here) and keep G2 with the extended dictionary. With both, the only
   measured residual was the 24 digit-suffixed vocabulary words, which G2 would also stop.
5. **Pilot at Level 1.** Re-run this measurement (same command, §8) after steps 2 to 4.
6. **Level 2 only if** the re-run shows zero surviving identity occurrences at p3, the planted-identity corpus (E5) shows
   zero escapes, and Level 1 explanations are measurably insufficient. At that point, what Level 2 adds is operational
   context, which design §3.7 asks the PO to accept explicitly. It is not identities.

What no masking removes, at any level (design §3.7): vendor, model family and software version; the events
themselves; interface names (310 distinct recorded names appear in the logs); and that some numbers are VSIDs or
process ids.

## 7. Limits of this measurement

- **Sample.** 416 MB of 22 GB stored (1.9%) and 1,246 of 26,376 chunks. Three chunks per device and source (newest,
  median, oldest), the first 2 MiB of each. Large chunks (`cp_fwk_elg`, `cp_mq_mng_elg`, `pan_system`) are
  represented by their beginnings. 55 Check Point and 38 Palo Alto devices.
- **Known names only.** The name classes count names that the stored snapshots know. A name that exists only in logs
  (an external partner, a deleted object, a local OS account) is not counted as a name. It shows up among the
  residual words of §3.6, where p3 suppresses it.
- **User mentions are heuristic.** Only 49 administrator names come from policy history.
- **Generic names are excluded.** A name made only of technical words ("Cleanup rule", "inside") is excluded as generic
  (7,623 rule names, for example); such a name would pass p3.
- **The vocabularies are stand-ins.** The technical vocabulary was written blind (1,780 words with the masking's
  stop-word lists). The English list is a generous 234,428 words, so p2 is an upper bound for an English-vocabulary
  design.
- **Survival is string presence.** A value equal to a word inside a pseudonym (`FW-TANGO-04`) would count as
  surviving, so leaks are slightly overstated, never understated.
- **Session span.** One masking session per chunk (up to 2 MiB) instead of per page (64 KiB). Names learned after a
  host keyword carry further here, which can only raise the catch rate of host names slightly.
- **Redaction on write.** The stored text is already secret-redacted on write. The secret counts measure the residue
  after that redaction, not the device output.
- **Repeatable.** Two full runs gave identical counts. The second only added the context labels of §3.9.

## 8. How it was run (reproduce)

Source in `scripts/measurement/log_masking_risk/`: `LogRiskMeasure.java` (the measurement), `techvocab.txt`,
`run_in_pod.sh`, `show_safe.sh` (pod-side output filter), `tables.py` (these tables).

```sh
# on the workstation: compile against the jar the pod runs
kubectl -n ui2 exec <ui2-service pod> -- cat /app/service.jar > service.jar   # code only
unzip -q service.jar 'BOOT-INF/lib/*' 'BOOT-INF/classes/com/*'
javac --release 21 -cp "BOOT-INF/classes:BOOT-INF/lib/*" -d payload/classes LogRiskMeasure.java
cp techvocab.txt run_in_pod.sh show_safe.sh payload/ && cp /usr/share/dict/words payload/english.txt
# into the pod (no gzip there), run, read back only the filtered aggregates
tar cf - -C payload . | kubectl -n ui2 exec -i <pod> -- sh -c 'mkdir -p /app/tmp/logrisk && tar xf - -C /app/tmp/logrisk'
kubectl -n ui2 exec <pod> -- sh /app/tmp/logrisk/run_in_pod.sh full2 60 2        # label, MiB per source, MiB per chunk
kubectl -n ui2 exec <pod> -- sh -c 'cd /app/tmp/logrisk && sh show_safe.sh full2.out | grep -v ^progress' > full2.tsv
python3 tables.py full2.tsv
kubectl -n ui2 exec <pod> -- rm -rf /app/tmp/logrisk
```

Run time: 13 s to build the dictionary, about 6 minutes for the scan. The JVM ran with `-Xmx3g` next to the service
in the same pod. Do not start it during a deploy e2e. A ship that rolls the pod deletes `/app/tmp`.

## 9. Incident during the measurement

At 11:12:58 UTC an exploratory query took ui2-db-0 over its 6 GiB memory limit and the pod was OOM-killed. The
query listed key paths of the policy snapshots with a recursive `jsonb_each` / `jsonb_array_elements` walk over all
99 rows: 260 MB compressed, up to 11 MB per row. PostgreSQL restarted and accepted connections again within about
13 s.

Impact:
- One system-log collection scheduler tick failed with `DataAccessException`; the next tick runs normally.
- No job was in flight (`jobs` had no rows submitted or finished in the 30 minutes around it).
- The e2e run of a concurrent ship had already passed (39 passed).

The measurement itself then streamed the snapshots one row at a time into the JVM. Rule taken: no recursive jsonb walk
on the live database; learn a shape from one small row.
