#!/usr/bin/env python3
"""Markdown tables from the output of PlantedIdentityMeasure (labels and numbers only).

Usage: tables.py <run.tsv> [<other-seed-run.tsv> ...]   (the first run gives the detailed tables)
Rows: cell <class> <shape> <known|unknown> <context> plants lines p0 p2 p3 blocked released blocked_lines
      false_blocked g2 g3 g4 words suppressed
"""
import collections
import sys

FIELDS = ["plants", "lines", "p0", "p2", "p3", "blocked", "released", "blocked_lines", "false_blocked", "g2", "g3", "g4",
          "words", "suppressed", "p0_blocked", "p0_released", "raw_p3", "raw_blocked", "raw_released"]


def load(path):
    cells = []
    meta = {}
    for line in open(path, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if f[0] == "cell":
            cells.append((f[1], f[2], f[3], f[4], dict(zip(FIELDS, map(int, f[5:])))))
        elif f[0] == "meta":
            meta[f[1]] = f[2]
        elif f[0] == "fatal":
            sys.exit("run failed: " + f[1])
    return cells, meta


def agg(cells, key):
    out = collections.defaultdict(lambda: collections.Counter())
    for c in cells:
        k = key(c)
        for name, v in c[4].items():
            out[k][name] += v
    return out


def pct(a, b):
    return "—" if not b else f"{100.0 * a / b:.1f}%"


def table(header, rows):
    print("| " + " | ".join(header) + " |")
    print("|" + "|".join("---" if i == 0 else "---:" for i in range(len(header))) + "|")
    for r in rows:
        print("| " + " | ".join(str(c) for c in r) + " |")
    print()


def survive_rows(groups, order=None, nonzero=False):
    rows = []
    for k in order or sorted(groups):
        a = groups[k]
        if not a["plants"]:
            continue
        if nonzero and not (a["p0"] or a["p2"] or a["p3"]):
            continue
        label = k if isinstance(k, str) else " / ".join(k)
        rows.append([label, a["plants"], a["p0"], pct(a["p0"], a["plants"]), a["p2"], a["p3"], pct(a["p3"], a["plants"]),
                     a["blocked"], a["released"], pct(a["released"], a["plants"])])
    return rows


HEAD = ["", "Planted", "Survive p0", "share", "Survive p2", "Survive p3", "share", "Blocked by guard", "Released", "share"]


def seed_summary(paths):
    rows = []
    for path in paths:
        cells, meta = load(path)
        plant = [c for c in cells if c[0] != "ctl" and c[0] != "secret"]
        words = [c for c in plant if c[1] == "vocab_word"]
        rest = [c for c in plant if c[1] != "vocab_word"]
        a = agg(rest, lambda c: "x")["x"]
        v = agg(words, lambda c: "x")["x"]
        ctl = agg([c for c in cells if c[0] == "ctl"], lambda c: "x")["x"]
        rows.append([meta.get("seed"), a["plants"], a["p0"], a["p3"], a["released"], v["plants"], v["released"],
                     pct(ctl["blocked_lines"], ctl["lines"]), pct(ctl["suppressed"], ctl["words"])])
    table(["Seed", "Planted (no vocabulary-word names)", "Survive p0", "Survive p3", "Released", "Vocabulary-word names planted",
           "Released", "Control lines blocked", "Control words replaced"], rows)


def main():
    cells, meta = load(sys.argv[1])
    plant_cells = [c for c in cells if c[0] != "ctl"]

    print("### Overall\n")
    groups = agg(plant_cells, lambda c: c[2])
    total = agg(plant_cells, lambda c: "all")
    ex = agg([c for c in plant_cells if c[1] != "vocab_word" and c[0] != "secret"], lambda c: "all except vocabulary-word names and secrets")
    table(HEAD, survive_rows({**total, **groups, **ex}, ["all", "known", "unknown", "all except vocabulary-word names and secrets"]))
    if len(sys.argv) > 2:
        print("### Three seeds (different synthetic values, same shapes)\n")
        seed_summary(sys.argv[1:])

    print("### By class, known to the dictionary\n")
    g = agg([c for c in plant_cells if c[2] == "known"], lambda c: c[0])
    table(HEAD, survive_rows(g))
    print("### By class, unknown to the dictionary\n")
    g = agg([c for c in plant_cells if c[2] == "unknown"], lambda c: c[0])
    table(HEAD, survive_rows(g))

    for known in ("known", "unknown"):
        print(f"### By class and shape, {known} (only rows with a survivor at p0, p2 or p3)\n")
        g = agg([c for c in plant_cells if c[2] == known], lambda c: (c[0], c[1]))
        table(HEAD, survive_rows(g, nonzero=True))
        quiet = sum(1 for a in g.values() if a["plants"] and not (a["p0"] or a["p2"] or a["p3"]))
        print(f"{quiet} other class and shape rows have no survivor at any stage.\n")

    print("### By context (all classes except secrets)\n")
    g = agg([c for c in plant_cells if c[0] != "secret"], lambda c: (c[3],))
    table(HEAD, survive_rows(g))

    print("### What each layer adds, for planted values that survive the masking (p0)\n")
    rows = []
    for label, sel in (("Unknown names (all classes)", [c for c in plant_cells if c[2] == "unknown" and c[0] not in ("net", "secret", "serial", "addr")]),
                       ("Unknown network and serial shapes", [c for c in plant_cells if c[2] == "unknown" and c[0] in ("net", "serial")]),
                       ("Unknown secrets", [c for c in plant_cells if c[0] == "secret"])):
        a = agg(sel, lambda c: "x")["x"]
        rows.append([label, a["p0"], a["p0_blocked"], a["p0_released"], a["p3"], a["blocked"], a["released"]])
    table(["Group", "Survive p0", "Guard alone blocks", "Guard alone releases", "Survive p3", "Profile + guard blocks",
           "Profile + guard releases"], rows)

    print("### If masking failed open: raw lines through the profile and the guard\n")
    rows = []
    for label, sel in (("Unknown or known names, not vocabulary words",
                        [c for c in plant_cells if c[0] in ("admin", "policy", "rule", "object", "zone", "vsys", "domain", "host", "device") and c[1] != "vocab_word"]),
                       ("Vocabulary-word names", [c for c in plant_cells if c[1] == "vocab_word"]),
                       ("Addresses and serials (known and unknown)", [c for c in plant_cells if c[0] in ("addr", "serial")]),
                       ("Network shapes and encodings", [c for c in plant_cells if c[0] == "net"]),
                       ("Secrets", [c for c in plant_cells if c[0] == "secret"])):
        a = agg(sel, lambda c: "x")["x"]
        rows.append([label, a["plants"], a["raw_p3"], pct(a["raw_p3"], a["plants"]), a["raw_blocked"], a["raw_released"],
                     pct(a["raw_released"], a["plants"])])
    table(["Group", "Planted", "Survive the profile (raw input)", "share", "Guard blocks", "Released", "share"], rows)
    print("### Raw-input survivors by shape\n")
    g = agg([c for c in plant_cells if c[0] in ("addr", "serial", "net", "secret")], lambda c: (c[0], c[1]))
    rows = [[" / ".join(k), a["plants"], a["raw_p3"], a["raw_blocked"], a["raw_released"]] for k, a in sorted(g.items()) if a["raw_p3"]]
    table(["Class / shape", "Planted", "Survive the profile", "Guard blocks", "Released"], rows)

    print("### Names with Turkish letters, by case of the text\n")
    tr = [c for c in plant_cells if c[1].startswith("turkish")]
    rows = []
    for known in ("known", "unknown"):
        for label, ctxs in (("as stored, no trigger word", None), ("upper case (root locale), no trigger word", ("upper_plain",)),
                            ("upper case (Turkish locale), no trigger word", ("upper_plain_tr",)),
                            ("upper case, after a trigger word (login by)", ("upper", "upper_tr"))):
            if ctxs is None:
                sel = [c for c in tr if c[2] == known and c[3] not in ("upper", "upper_tr", "upper_plain", "upper_plain_tr")
                       and c[3] not in ("kv", "kv_quoted", "after_for", "json_key")]
            else:
                sel = [c for c in tr if c[2] == known and c[3] in ctxs]
            a = agg(sel, lambda c: "x")["x"]
            rows.append([known, label, a["plants"], a["p0"], pct(a["p0"], a["plants"]), a["p3"], a["raw_p3"], a["raw_blocked"]])
    table(["Dictionary", "Text", "Planted", "Survive p0", "share", "Survive p3", "Survive profile (raw input)", "Guard blocks"], rows)

    print("### Guard\n")
    g = agg(plant_cells, lambda c: "planted lines")
    ctl = agg([c for c in cells if c[0] == "ctl"], lambda c: "control lines")
    rows = []
    for name, a in list(g.items()) + list(ctl.items()):
        rows.append([name, a["lines"], a["blocked_lines"], pct(a["blocked_lines"], a["lines"]), a["g2"], a["g3"], a["g4"],
                     a["false_blocked"]])
    table(["Lines", "Count", "Blocked", "share", "G2 known identity", "G3 shape", "G4 secret",
           "Blocked with no planted value left"], rows)
    print("### Profile cost on control lines\n")
    a = ctl["control lines"]
    table(["Measure", "Count"], [["Word tokens", a["words"]], ["Replaced by a placeholder", a["suppressed"]],
                                 ["Share", pct(a["suppressed"], a["words"])]])
    print("Meta: " + ", ".join(f"{k}={v}" for k, v in sorted(meta.items())))


if __name__ == "__main__":
    main()
