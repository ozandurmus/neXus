#!/usr/bin/env python3
"""Palo Alto inventory measurement (PO_DECISION_RECORD_2026_09_14C section 5, M-3 and M-4).

Usage: python3 pan_inventory_shape.py https://<firewall-mgmt-address> [--vsys vsys2] [--config] [--panorama] [--ca bundle.pem | --insecure]
Prompts for user and password (never on the command line, never in a URL).
Prints only element paths, attribute names, counts and value SHAPES; no value
is printed. Paste the whole output back.
"""
import argparse, getpass, re, ssl, sys, urllib.parse, urllib.request
import xml.etree.ElementTree as ET
from collections import Counter

def shape(v):
    if v is None or not v.strip(): return "empty"
    v=v.strip()
    if re.fullmatch(r"(\d{1,3}\.){3}\d{1,3}(/\d{1,2})?", v): return "ip4" + ("/prefix" if "/" in v else "")
    if ":" in v and re.fullmatch(r"[0-9A-Fa-f:./]+", v): return "ip6"
    if re.fullmatch(r"-?\d+", v): return "int"
    return "text"

def post(base, body, ctx):
    req=urllib.request.Request(base.rstrip("/")+"/api/", data=urllib.parse.urlencode(body).encode(), method="POST")
    req.add_header("Content-Type","application/x-www-form-urlencoded")
    with urllib.request.urlopen(req, timeout=30, context=ctx) as r: return r.read()

def describe(name, raw):
    print(f"\n### {name}: bytes={len(raw)}")
    root=ET.fromstring(raw)
    print(f"### status={root.get('status')} code={root.get('code')}")
    paths=Counter(); shapes={}; attrs=Counter()
    def walk(el, p):
        q=p+"/"+el.tag; paths[q]+=1
        for a in el.attrib: attrs[q+"@"+a]+=1
        if len(el)==0: shapes.setdefault(q, Counter())[shape(el.text)]+=1
        for c in el: walk(c, q)
    walk(root, "")
    for p,n in sorted(paths.items()): print(f"{n:5d}  {p}  {dict(shapes.get(p,{})) if p in shapes else ''}")
    for a,n in sorted(attrs.items()): print(f"{n:5d}  {a}")
    # per-interface address multiplicity (M-3)
    ent=[e for e in root.iter("entry") if e.find("ip") is not None or e.find("name") is not None]
    if ent:
        multi=Counter(len([c for c in e if c.tag in ("ip","ipv6","address") or c.tag.startswith("ip")]) for e in ent)
        print(f"### entries={len(ent)} address_elements_per_entry_histogram={dict(multi)}")
    flags=Counter(f.text.strip() for f in root.iter("flags") if f.text)
    if flags: print(f"### route flag tokens seen={sorted(flags)}")

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("base"); ap.add_argument("--vsys"); ap.add_argument("--ca"); ap.add_argument("--config", action="store_true", help="also measure the configuration reads (sizes, top-level categories, hash; no content)"); ap.add_argument("--panorama", action="store_true", help="the target is a Panorama: measure its own config read for Template/Device Group provenance"); ap.add_argument("--insecure", action="store_true", help="measurement only: do not verify the firewall certificate (self-signed)")
    a=ap.parse_args()
    ctx=ssl.create_default_context(cafile=a.ca) if a.ca else ssl.create_default_context()
    if a.insecure:
        ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE; print("### TLS verification disabled for this measurement run")
    user=input("user: "); pw=getpass.getpass("password: ")
    key_xml=post(a.base, {"type":"keygen","user":user,"password":pw}, ctx); del pw
    key=ET.fromstring(key_xml).findtext(".//key")
    if not key: print("keygen failed: status", ET.fromstring(key_xml).get("status")); sys.exit(1)
    print("### keygen ok, key_len=", len(key))
    reads={"R-4 show interface all":"<show><interface>all</interface></show>",
           "R-5 show routing route":"<show><routing><route></route></routing></show>",
           "R-3 show system info":"<show><system><info></info></system></show>",
           "HA state":"<show><high-availability><state></state></high-availability></show>"}
    for name,cmd in reads.items():
        body={"type":"op","cmd":cmd,"key":key}
        try: describe(name, post(a.base, body, ctx))
        except Exception as e: print(f"\n### {name}: error {type(e).__name__}")
    if a.config:
        import hashlib
        def cfg(name, body):
            try: raw=post(a.base, body, ctx)
            except Exception as e: print(f"\n### {name}: error {type(e).__name__}"); return
            root=ET.fromstring(raw); print(f"\n### {name}: bytes={len(raw)} status={root.get('status')} sha256_16={hashlib.sha256(raw).hexdigest()[:16]}")
            cats=Counter()
            for top in root.iter("config"):
                for c in top: cats[c.tag]+=1
                for dev in top.iter("devices"):
                    for e in dev.findall("entry"):
                        for c in e: cats["devices/entry/"+c.tag]+=1
                        for v in e.findall("vsys/entry"):
                            for c in v: cats["devices/entry/vsys/entry/"+c.tag]+=1
                break
            for k,n in sorted(cats.items()): print(f"{n:5d}  {k}")
            if a.panorama:
                tpl=len(root.findall(".//template/entry")); ts=len(root.findall(".//template-stack/entry")); dg=len(root.findall(".//device-group/entry"))
                print(f"### panorama: template_entries={tpl} template_stack_entries={ts} device_group_entries={dg}")
        cfg("R-6 active config (type=config action=show xpath=/config)", {"type":"config","action":"show","xpath":"/config","key":key})
        if not a.panorama:
            cfg("R-7 effective-running", {"type":"op","cmd":"<show><config><effective-running></effective-running></config></show>","key":key})
            cfg("R-7 effective-running (second read, Q-16 stability)", {"type":"op","cmd":"<show><config><effective-running></effective-running></config></show>","key":key})
            cfg("R-8 merged", {"type":"op","cmd":"<show><config><merged></merged></config></show>","key":key})
    if a.vsys:
        for name,cmd in list(reads.items())[:2]:
            body={"type":"op","cmd":cmd,"key":key,"vsys":a.vsys}
            try: describe(name+f" vsys={a.vsys} (M-4)", post(a.base, body, ctx))
            except Exception as e: print(f"\n### {name} vsys: error {type(e).__name__}")
main()
