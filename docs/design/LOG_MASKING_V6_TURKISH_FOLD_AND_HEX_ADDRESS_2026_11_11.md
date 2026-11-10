# Log masking logmask-v6: Turkish case fold and hex-encoded addresses

Date: 2026-11-11. Status: decided by the PO (2026-11-11), implemented, shipped with the rule set `logmask-v6`.
Source: planted-identity test E5 (`LOG_PLANTED_IDENTITY_MEASUREMENT_2026_11_11.md`, findings 2 and 6). Synthetic data only.

## Decisions

1. **Turkish case fold.** `EstateDictionary` compared estate names by the digest of `value.toLowerCase(Locale.ROOT)`.
   That does not round-trip the Turkish letters: the dotted capital `İ` (U+0130) lower-cases to two characters and
   the dotless `ı` (U+0131) stays itself, so a known name written in the other case escaped (12 of 20 planted).
   The dictionary now folds with `EstateDictionary.fold`: `İ`, `ı` and `I` become `i`, every other character is
   lower-cased with `Character.toLowerCase`. The fold is one character to one character, so name lengths and the
   first/last-character shape filter stay valid, and it is applied on both sides (the stored name when the dictionary
   is built, the candidate text when it is matched). It only widens what is masked.
   **Unchanged:** device identities stay exact and case-sensitive (the identity law); the generic-word filter of
   `EstateNameCatalog` still lower-cases with the root locale (a generic English word is not a Turkish name).
2. **Hex-encoded addresses.** `0x` plus exactly eight hex digits can be an IPv4 address.
   - *Masked Logs view (live code):* `FreeTextIdentityRules` kept every `0x` literal. A literal of eight hex digits
     right after an address keyword (`ip`, `addr`, `address`, `src`, `dst`, `source`, `destination`, `peer`,
     `gateway`, `gw`, `remote`, `local`, `client`, `server`, `host`, `nexthop`, with an optional prefix such as
     `src_ip`) now becomes the identity token `ID-` + 10 hex; the same value in either letter case gives one token.
     Other hex literals stay readable: kernel masks (`gfp_mask=0x6200ca`), offsets, pointers, short error codes and
     eight-digit literals with no address keyword. Reason: a blanket rule would wreck register and mask values that
     the log reader needs; the keyword makes the hex literal an address by the line's own words.
   - *Egress profile:* it exists only in the DRAFT design (`LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md` §3.3) and in the
     E5 measurement model (`PlantedIdentityMeasure.egressToken`, `HEXLIT`). There is no egress code in the product
     yet. The draft now states: hex literals up to four hex digits after `0x` are kept, `0x` plus five or more digits
     (eight in particular) becomes `<HEX>`. The E5 measurement program is on the E5 branch and is not changed here;
     when the real profile is implemented it must use this rule, and the E5 re-run should use `HEXLIT = 0x[0-9a-f]{1,4}`.

## Residual risk (accepted by the PO)

- Estate names identical to a technical word are dropped from the dictionary as generic (E5 finding 5).
- An eight-digit hex literal with no address keyword stays visible in the Logs view (it may be a mask or a pointer).
- Other scripts (Greek final sigma, German sharp s, Cyrillic) are not folded specially.

## Tests

`EstateNamesTest.turkishCaseVariantsOfAnEstateNameAreOneName`, `theTurkishFoldIsOneToOneAndDoesNotMergeDifferentWords`;
`LogMaskingHexAddressTest` (masked after keywords, one token across letter case, ordinary literals unchanged, idempotent).
Fixtures are invented words and RFC 5737 addresses built at run time. Rule-set assertions in `EstateNameCatalogTest`
and `SystemLogReadServiceTest` now expect `logmask-v6`.
