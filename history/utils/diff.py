import json
import os


###############################################
# LOAD JSON
###############################################
def load_json(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return json.load(f)


###############################################
# FLATTEN (NEW MODEL FIX)
###############################################
def flatten(data):

    flat = []

    for item in data:

        device = item.get("device")
        vsys = item.get("vsys")
        source = item.get("source")

        for iface in item.get("interfaces", []):

            flat.append({
                "device": device,
                "vsys": vsys,
                "interface": iface.get("interface"),
                "ip": iface.get("ip"),
                "subnet": iface.get("subnet"),
                "source": source,
                "routing": item.get("routing", [])
            })

    return flat


###############################################
# KEY BUILDER
###############################################
def make_key(item):
    return (
        item.get("device"),
        item.get("vsys"),
        item.get("interface"),
        item.get("ip"),
        item.get("subnet")
    )


###############################################
# DIFF
###############################################
def diff(old_file, new_file):

    old = flatten(load_json(old_file))
    new = flatten(load_json(new_file))

    old_map = {make_key(i): i for i in old}
    new_map = {make_key(i): i for i in new}

    added = []
    removed = []
    changed = []

    ###############################################
    # ADDED
    ###############################################
    for k in new_map:
        if k not in old_map:
            added.append(new_map[k])

    ###############################################
    # REMOVED
    ###############################################
    for k in old_map:
        if k not in new_map:
            removed.append(old_map[k])

    ###############################################
    # CHANGED (ROUTING CHECK)
    ###############################################
    for k in new_map:
        if k in old_map:

            old_routes = old_map[k].get("routing", [])
            new_routes = new_map[k].get("routing", [])

            if old_routes != new_routes:
                changed.append({
                    "key": k,
                    "old": old_routes,
                    "new": new_routes
                })

    return added, removed, changed 