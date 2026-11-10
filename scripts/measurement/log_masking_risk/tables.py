"""Turn the aggregate TSV printed by LogRiskMeasure into Markdown tables.

Input holds only fixed labels and numbers (the pod-side filter keeps lines whose every field matches [a-z0-9_.]+).
Usage: python3 tables.py full.tsv > tables.md
"""
import collections
import sys

SRC_COLS = ["chunks", "devices", "lines", "bytes", "word_tokens", "resid_strict_occ", "resid_strict_distinct",
            "resid_drafted_occ", "resid_drafted_distinct", "resid_distinct_english", "resid_distinct_known_name",
            "resid_distinct_dev1", "resid_distinct_dev2_5", "resid_distinct_dev6p", "resid_occ_known_name",
            "leak_lines_p0", "leak_lines_p1", "leak_lines_p2", "leak_lines_p3", "mismatch_chunks", "errors",
            "identity_lines", "state"]
CLS_COLS = ["occ", "lines", "distinct", "surv_p0", "surv_p1", "surv_p2", "surv_p3", "english"]
IDENTITY = ["ipv4_private", "ipv4_public", "ipv6", "mac", "fqdn", "url_host", "email", "host_known", "host_header",
            "user_known", "user_mention", "serial_known", "serial_shape", "name_rule", "name_policy", "name_object",
            "name_zone", "name_vs", "name_domain", "secret_marker", "private_key_marker"]
OTHER = ["ipv4_special", "ipv6_special", "interface_known", "interface_shape", "interface_vlan_suffix", "num_asn",
         "num_vlan", "num_vsid", "num_5digit", "high_entropy"]


def pct(a, b):
    return "-" if b == 0 else f"{100.0 * a / b:.1f}%"


def main(path):
    src, cls, shape, dic, meta, err = {}, collections.defaultdict(dict), collections.defaultdict(dict), {}, {}, {}
    ctx = []
    for line in open(path, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if f[0] == "src":
            src[f[1]] = dict(zip(SRC_COLS, [int(x) if x.isdigit() else x for x in f[2:]]))
        elif f[0] == "cls":
            cls[f[1]][f[2]] = dict(zip(CLS_COLS, map(int, f[3:])))
        elif f[0] == "shape":
            shape[f[1]][f[2]] = (int(f[3]), int(f[4]))
        elif f[0] == "dict":
            dic[f[1]] = list(map(int, f[2:]))
        elif f[0] == "meta":
            meta[f[1]] = f[2]
        elif f[0] == "ctx":
            ctx.append(f[1:])
        elif f[0] == "err":
            err[f[1]] = f[2]
    out = []
    w = out.append
    w("### Coverage\n")
    w("| Source | Chunks | Devices | Lines | MB | Lines with an identity token |")
    w("|---|---:|---:|---:|---:|---:|")
    tot = collections.Counter()
    for s, v in src.items():
        w(f"| `{s}` | {v['chunks']} | {v['devices']} | {v['lines']:,} | {v['bytes'] / 1048576:.1f} | "
          f"{v['identity_lines']:,} ({pct(v['identity_lines'], v['lines'])}) |")
        for k in ("chunks", "lines", "bytes", "identity_lines"):
            tot[k] += v[k]
    w(f"| **all** | {tot['chunks']} | | {tot['lines']:,} | {tot['bytes'] / 1048576:.1f} | "
      f"{tot['identity_lines']:,} ({pct(tot['identity_lines'], tot['lines'])}) |")
    w(f"\nMeta: {', '.join(f'{k}={v}' for k, v in meta.items())}. Errors: {err or 'none'}. "
      f"Line-count mismatches after masking: {sum(v['mismatch_chunks'] for v in src.values())}.\n")

    w("### Known-name dictionary built from stored snapshots\n")
    w("| Class | Entries | Dropped as generic (technical words only) | Entries that are English words | Multi-word entries | Dropped by shape (short, numeric, id, address) |")
    w("|---|---:|---:|---:|---:|---:|")
    for k, v in dic.items():
        w(f"| `{k}` | {v[0]:,} | {v[1]:,} | {v[2]:,} | {v[3]:,} | {v[4]:,} |")

    totals = collections.defaultdict(collections.Counter)
    for s in cls:
        for k, v in cls[s].items():
            totals[k].update(v)
    w("\n### Token classes, all sources\n")
    w("Survival = the raw value is still present in the line after the profile. p0 = `logmask-v4` masked line (what "
      "Level 2 would send today); p1 = p0 with every digit run replaced (a naive template, what Level 1 would send "
      "today); p2 = `egress-v1` as drafted (technical + English vocabulary, numbers up to 5 digits and interface names "
      "kept); p3 = `egress-v1` with Argon's fixes (technical vocabulary only, numbers typed, VLAN suffix typed).\n")
    w("| Class | Occurrences | Distinct (per source, summed) | Caught by logmask-v4 | Survives p0 | Survives p1 | Survives p2 | Survives p3 | Value is an English word |")
    w("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for group in (IDENTITY, OTHER):
        for k in group:
            v = totals.get(k)
            if not v:
                w(f"| `{k}` | 0 | | | | | | | |")
                continue
            w(f"| `{k}` | {v['occ']:,} | {v['distinct']:,} | {pct(v['occ'] - v['surv_p0'], v['occ'])} | "
              f"{v['surv_p0']:,} | {v['surv_p1']:,} | {v['surv_p2']:,} | {v['surv_p3']:,} | {v['english']:,} |")

    w("\n### Surviving identity tokens per source (occurrences surviving p0 / p1 / p2 / p3)\n")
    present = [k for k in IDENTITY if totals.get(k)]
    w("| Source | " + " | ".join(f"`{k}`" for k in present) + " |")
    w("|---|" + "---:|" * len(present))
    for s in src:
        cells = []
        for k in present:
            v = cls[s].get(k)
            cells.append("" if not v else f"{v['occ']:,}: {v['surv_p0']:,}/{v['surv_p1']:,}/{v['surv_p2']:,}/{v['surv_p3']:,}")
        w(f"| `{s}` | " + " | ".join(cells) + " |")

    w("\n### Lines that would carry at least one identity token\n")
    w("| Source | Lines | p0 (Level 2 today) | p1 (Level 1 today) | p2 (egress-v1 draft) | p3 (egress-v1 + Argon fixes) |")
    w("|---|---:|---:|---:|---:|---:|")
    for s, v in src.items():
        w(f"| `{s}` | {v['lines']:,} | {v['leak_lines_p0']:,} ({pct(v['leak_lines_p0'], v['lines'])}) | "
          f"{v['leak_lines_p1']:,} ({pct(v['leak_lines_p1'], v['lines'])}) | {v['leak_lines_p2']:,} "
          f"({pct(v['leak_lines_p2'], v['lines'])}) | {v['leak_lines_p3']:,} ({pct(v['leak_lines_p3'], v['lines'])}) |")

    w("\n### Residual words after masking (not in the technical vocabulary)\n")
    w("| Source | Word tokens | Residual occurrences (p3 suppression) | Residual distinct | Residual under the drafted vocabulary (p2) | Distinct residual that are English words | Distinct residual that are known names | Distinct on 1 device / 2-5 / 6+ |")
    w("|---|---:|---:|---:|---:|---:|---:|---:|")
    for s, v in src.items():
        w(f"| `{s}` | {v['word_tokens']:,} | {v['resid_strict_occ']:,} ({pct(v['resid_strict_occ'], v['word_tokens'])}) | "
          f"{v['resid_strict_distinct']:,} | {v['resid_drafted_occ']:,} ({pct(v['resid_drafted_occ'], v['word_tokens'])}), "
          f"{v['resid_drafted_distinct']:,} distinct | {v['resid_distinct_english']:,} | {v['resid_distinct_known_name']:,} | "
          f"{v['resid_distinct_dev1']:,} / {v['resid_distinct_dev2_5']:,} / {v['resid_distinct_dev6p']:,} |")

    w("\n### Residual word shapes (occurrences, distinct)\n")
    shapes = ["lowercase_word", "capitalized_word", "uppercase_word", "camelcase_word", "word_with_underscore",
              "word_with_hyphen", "dotted_name", "path_like", "letters_and_digits", "other"]
    used = [x for x in shapes if any(x in shape[s] for s in shape)]
    w("| Source | " + " | ".join(f"`{x}`" for x in used) + " |")
    w("|---|" + "---:|" * len(used))
    for s in src:
        w(f"| `{s}` | " + " | ".join(
            "" if x not in shape[s] else f"{shape[s][x][0]:,} ({shape[s][x][1]:,})" for x in used) + " |")

    w("\n### Gaps ranked by surviving occurrences at p0 (identity classes)\n")
    w("| Rank | Class | Survives p0 | Survives p2 | Survives p3 | Sources |")
    w("|---:|---|---:|---:|---:|---|")
    ranked = sorted((k for k in IDENTITY if totals.get(k) and totals[k]["surv_p0"] > 0), key=lambda k: -totals[k]["surv_p0"])
    for i, k in enumerate(ranked, 1):
        srcs = ", ".join(f"`{s}`" for s in src if cls[s].get(k, {}).get("surv_p0", 0) > 0)
        w(f"| {i} | `{k}` | {totals[k]['surv_p0']:,} | {totals[k]['surv_p2']:,} | {totals[k]['surv_p3']:,} | {srcs} |")
    if ctx:
        w("\n### Where surviving identity tokens sit (top contexts at p0)\n")
        w("Labels only: the character before and after the surviving value (`sp` space, `dq` double quote, `lpar`/`rpar` "
          "parentheses, `us` underscore, `sl` slash, `eol` end of line, `bol` start of line) and the token before it "
          "(a technical-vocabulary word, `nonvocab`, `number`, `time`, or `none` when only separators precede it, as in "
          "a JSON value).\n")
        w("| Source | Class | Before | After | Token before | Occurrences |")
        w("|---|---|---|---|---|---:|")
        for c in sorted(ctx, key=lambda r: (r[1], r[0], -int(r[5]))):
            if int(c[5]) >= 20:
                w(f"| `{c[0]}` | `{c[1]}` | `{c[2]}` | `{c[3]}` | `{c[4]}` | {int(c[5]):,} |")
    print("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1])
