// SecurityExpert report UI — inventory_ui: Network Inventory module (state, hierarchy, render)

let inventory = [];
let selectedId = null;
let activeTab = "interfaces";
let activeRouteViewByEntry = new Map();

const interfaceSort = {
    key: "interface",
    direction: 1
};

const routeSort = {
    key: "type",
    direction: 1
};


function prefixToMask(prefix) {
    const value = Number(prefix);

    if (
        !Number.isInteger(value) ||
        value < 0 ||
        value > 32
    ) {
        return "";
    }

    const binary = "1".repeat(value).padEnd(32, "0");
    const octets = [];

    for (let index = 0; index < 32; index += 8) {
        octets.push(
            parseInt(binary.slice(index, index + 8), 2)
        );
    }

    return octets.join(".");
}


function dottedMaskToPrefix(mask) {
    const parts = safe(mask).split(".").map(Number);

    if (
        parts.length !== 4 ||
        parts.some(part =>
            !Number.isInteger(part) ||
            part < 0 ||
            part > 255
        )
    ) {
        return null;
    }

    const binary = parts
        .map(part => part.toString(2).padStart(8, "0"))
        .join("");

    if (!/^1*0*$/.test(binary)) {
        return null;
    }

    return binary.replaceAll("0", "").length;
}


function parsePrefix(value) {
    if (
        value === null ||
        value === undefined ||
        value === ""
    ) {
        return null;
    }

    const text = safe(value).replace("/", "").trim();

    if (/^\d+$/.test(text)) {
        const prefix = Number(text);

        if (prefix >= 0 && prefix <= 32) {
            return prefix;
        }
    }

    if (/^\d+\.\d+\.\d+\.\d+$/.test(text)) {
        return dottedMaskToPrefix(text);
    }

    return null;
}


function ipToInt(ip) {
    const parts = safe(ip).split(".").map(Number);

    if (
        parts.length !== 4 ||
        parts.some(part =>
            !Number.isInteger(part) ||
            part < 0 ||
            part > 255
        )
    ) {
        return null;
    }

    return (
        ((parts[0] << 24) >>> 0) +
        (parts[1] << 16) +
        (parts[2] << 8) +
        parts[3]
    ) >>> 0;
}


function intToIp(value) {
    const number = Number(value) >>> 0;

    return [
        (number >>> 24) & 255,
        (number >>> 16) & 255,
        (number >>> 8) & 255,
        number & 255
    ].join(".");
}


function calculateNetwork(ip, prefix) {
    const ipNumber = ipToInt(ip);
    const prefixNumber = Number(prefix);

    if (
        ipNumber === null ||
        !Number.isInteger(prefixNumber) ||
        prefixNumber < 0 ||
        prefixNumber > 32
    ) {
        return "";
    }

    const mask = prefixNumber === 0
        ? 0
        : (0xffffffff << (32 - prefixNumber)) >>> 0;

    return intToIp(ipNumber & mask) + "/" + prefixNumber;
}


function normalizeInterfaceRow(row, context = {}) {
    const interfaceName = safe(
        row.interface ??
        row.name ??
        row.ifname ??
        row["interface-name"] ??
        ""
    );

    let ip = safe(
        row.ip ??
        row.address ??
        row.ip_address ??
        row["ip-address"] ??
        ""
    );

    let prefix = parsePrefix(
        row.prefix ??
        row.mask ??
        row.subnet ??
        row.netmask ??
        ""
    );

    if (ip.includes("/")) {
        const [address, cidr] = ip.split("/");
        ip = address;

        if (prefix === null) {
            prefix = parsePrefix(cidr);
        }
    }

    let network = safe(
        row.network ??
        row.subnet_network ??
        ""
    );

    if (!network && ip && prefix !== null) {
        network = calculateNetwork(ip, prefix);
    }

    return {
        interface: interfaceName,
        ip,
        prefix,
        mask:
            prefix !== null
                ? prefixToMask(prefix)
                : safe(row.mask ?? row.netmask),
        network,
        vsys: safe(
            row.vsys ??
            row.virtual_system ??
            context.vsys ??
            ""
        ),
        vr: safe(
            row.vr ??
            row.virtual_router ??
            context.vr ??
            ""
        ),
        zone: safe(row.zone ?? ""),
        state: safe(row.state ?? ""),
        type: safe(row.type ?? ""),
        source: safe(context.source),
        member: safe(row.member ?? context.member ?? ""),
        addressRole: safe(row.address_role ?? row.role ?? context.addressRole ?? "")
    };
}


function flattenInterfaces(item) {
    const source = normalizedSource(item.source);
    const result = [];

    /*
     * Panorama'nın bazı eski çıktıları:
     * vr_data -> VR -> interfaces
     */
    if (
        item.vr_data &&
        typeof item.vr_data === "object"
    ) {
        Object.entries(item.vr_data).forEach(
            ([vrName, vrObject]) => {
                const interfaces = Array.isArray(
                    vrObject?.interfaces
                )
                    ? vrObject.interfaces
                    : [];

                interfaces.forEach(interfaceObject => {
                    if (
                        Array.isArray(interfaceObject?.ips) &&
                        interfaceObject.ips.length
                    ) {
                        interfaceObject.ips.forEach(ipObject => {
                            result.push(
                                normalizeInterfaceRow(
                                    {
                                        ...interfaceObject,
                                        ...ipObject,
                                        interface:
                                            interfaceObject.interface ??
                                            interfaceObject.name
                                    },
                                    {
                                        source,
                                        vr: vrName,
                                        vsys:
                                            interfaceObject.vsys ??
                                            vrObject.vsys ??
                                            ""
                                    }
                                )
                            );
                        });
                    } else {
                        result.push(
                            normalizeInterfaceRow(
                                interfaceObject,
                                {
                                    source,
                                    vr: vrName,
                                    vsys:
                                        interfaceObject.vsys ??
                                        vrObject.vsys ??
                                        ""
                                }
                            )
                        );
                    }
                });
            }
        );
    } else {
        /*
         * CP:
         * {
         *   name: "eth1",
         *   ips: [{ip, prefix, network}]
         * }
         *
         * VSX/PAN:
         * {
         *   name/interface, ip, prefix, network, vr, vsys, zone
         * }
         */
        const interfaces = Array.isArray(item.interfaces)
            ? item.interfaces
            : [];

        interfaces.forEach(interfaceObject => {
            if (
                Array.isArray(interfaceObject?.ips) &&
                interfaceObject.ips.length
            ) {
                interfaceObject.ips.forEach(ipObject => {
                    result.push(
                        normalizeInterfaceRow(
                            {
                                ...interfaceObject,
                                ...ipObject,
                                interface:
                                    interfaceObject.interface ??
                                    interfaceObject.name
                            },
                            {
                                source,
                                vsys:
                                    interfaceObject.vsys ??
                                    item.vsys ??
                                    "",
                                vr:
                                    interfaceObject.vr ??
                                    item.vr ??
                                    ""
                            }
                        )
                    );
                });
            } else {
                result.push(
                    normalizeInterfaceRow(
                        interfaceObject,
                        {
                            source,
                            vsys:
                                interfaceObject.vsys ??
                                item.vsys ??
                                "",
                            vr:
                                interfaceObject.vr ??
                                item.vr ??
                                ""
                        }
                    )
                );
            }
        });
    }

    return result.filter(row => {
        const name = safe(row.interface).toLowerCase();

        return (
            name &&
            name !== "lo" &&
            name !== "loopback" &&
            row.ip
        );
    });
}


function normalizeRouteRow(row, context = {}) {
    let network = safe(
        row.network ??
        row.destination ??
        row.dest ??
        ""
    );

    if (network === "default") {
        network = "0.0.0.0/0";
    }

    const nextHop = safe(
        row.next_hop ??
        row.nexthop ??
        row.gateway ??
        ""
    );

    const interfaceName = safe(
        row.interface ??
        row.iface ??
        row.device ??
        ""
    );

    let type = safe(row.type).toLowerCase();

    if (network === "0.0.0.0/0") {
        type = "default";
    } else if (!type && nextHop) {
        type = "static";
    } else if (!type && interfaceName) {
        type = "connected";
    } else if (!type) {
        type = "unknown";
    }

    return {
        network,
        next_hop: nextHop,
        interface: interfaceName,
        type,
        protocol: safe(
            row.protocol ??
            row.raw_flags ??
            ""
        ),
        vr: safe(
            row.vr ??
            row.virtual_router ??
            context.vr ??
            ""
        ),
        warning: safe(row.warning ?? ""),
        member: safe(row.member ?? context.member ?? "")
    };
}


function routePriority(type) {
    const priority = {
        connected: 0,
        static: 1,
        default: 2,
        unknown: 3
    };

    return priority[safe(type).toLowerCase()] ?? 99;
}


function sortRoutesDefault(routes) {
    return [...routes].sort((left, right) => {
        const priorityDifference =
            routePriority(left.type) -
            routePriority(right.type);

        if (priorityDifference !== 0) {
            return priorityDifference;
        }

        return safe(left.network).localeCompare(
            safe(right.network),
            undefined,
            {
                numeric: true,
                sensitivity: "base"
            }
        );
    });
}


function flattenRoutes(item) {
    const result = [];

    /*
     * Eski Panorama vr_data formatı.
     */
    if (
        item.vr_data &&
        typeof item.vr_data === "object"
    ) {
        Object.entries(item.vr_data).forEach(
            ([vrName, vrObject]) => {
                const routes =
                    vrObject?.routes ??
                    vrObject?.routing ??
                    [];

                if (Array.isArray(routes)) {
                    routes.forEach(routeObject => {
                        result.push(
                            normalizeRouteRow(
                                routeObject,
                                {vr: vrName}
                            )
                        );
                    });
                }
            }
        );
    } else {
        /*
         * CP çoğunlukla routes,
         * VSX çoğunlukla routing,
         * PAN runtime doğrudan routes kullanıyor.
         */
        const routes = Array.isArray(item.routes)
            ? item.routes
            : Array.isArray(item.routing)
                ? item.routing
                : [];

        routes.forEach(routeObject => {
            result.push(
                normalizeRouteRow(routeObject)
            );
        });
    }

    return sortRoutesDefault(result);
}


function collectPanoramaVirtualRouters(item) {
    const routers = new Set();

    if (
        item.vr_data &&
        typeof item.vr_data === "object"
    ) {
        Object.keys(item.vr_data).forEach(vr => {
            if (vr) {
                routers.add(vr);
            }
        });
    }

    (item.interfaces || []).forEach(interfaceObject => {
        if (interfaceObject?.vr) {
            routers.add(String(interfaceObject.vr));
        }
    });

    (item.routes || item.routing || []).forEach(routeObject => {
        if (routeObject?.vr) {
            routers.add(String(routeObject.vr));
        }
    });

    return Array.from(routers);
}


function normalizeInventoryStatus(item) {
    const raw = item?.inventory_status || {};
    const dataState = safe(raw.data_state || (raw.fresh ? "live" : "unknown")).toLowerCase();
    const fresh = raw.fresh === true || dataState === "live";

    return {
        fresh,
        dataState,
        availabilityState: safe(raw.availability_state || "unknown"),
        collectedAt: safe(raw.collected_at || ""),
        lastSuccessfulCollection: safe(raw.last_successful_collection || ""),
        staleReason: safe(raw.stale_reason || "")
    };
}


function inventoryStatusLabel(status) {
    if (status.fresh) {
        return "LIVE";
    }
    if (status.dataState === "last_known_good") {
        return "OLD DATA";
    }
    if (status.dataState === "partial") {
        return "PARTIAL DATA";
    }
    if (status.dataState === "no_data") {
        return "NO LIVE DATA";
    }
    return "UNKNOWN";
}


function inventoryStatusClass(status) {
    if (status.fresh) {
        return "live";
    }
    if (status.dataState === "last_known_good") {
        return "stale";
    }
    return "unavailable";
}


function combineInventoryStatus(entries) {
    const statuses = entries.map(entry => entry.inventoryStatus);
    const nonLive = statuses.filter(status => !status.fresh);
    if (!nonLive.length) {
        return statuses[0] || normalizeInventoryStatus({});
    }

    const lkg = nonLive.find(status => status.dataState === "last_known_good");
    return lkg || nonLive[0];
}


function buildEntry(item, index) {
    const source = normalizedSource(item.source);

    const vsys = safe(
        item.vsys ??
        item.vs_name ??
        item.virtual_system ??
        ""
    );

    const cluster = safe(
        item.cluster ??
        item.parent ??
        ""
    );

    let displayName = safe(item.device);

    if (source === "vsx" && vsys) {
        displayName = vsys;
    }

    let subtitle = "";

    if (source === "vsx") {
        subtitle = cluster || safe(item.device);
    } else if (source === "panorama") {
        const routers =
            collectPanoramaVirtualRouters(item);

        subtitle = routers.length
            ? "VR: " + routers.join(", ")
            : "Palo Alto";
    } else if (
        vsys &&
        vsys !== "default"
    ) {
        subtitle = "VSYS: " + vsys;
    }

    const entry = {
        id:
            source +
            "::" +
            displayName +
            "::" +
            index,
        source,
        device: safe(item.device),
        displayName,
        subtitle,
        vsys,
        cluster,
        interfaces: flattenInterfaces(item),
        routes: flattenRoutes(item),
        managementIp: safe(item.management_ip ?? item.managementIp ?? ""),
        inventoryStatus: normalizeInventoryStatus(item),
        raw: item
    };

    entry.searchText =
        JSON.stringify(entry).toLowerCase();

    return entry;
}


function scoreEntry(entry) {
    return (
        entry.interfaces.length * 1000 +
        entry.routes.length
    );
}


function mergeUniqueRows(rows, keyBuilder) {
    const map = new Map();

    rows.forEach(row => {
        const key = keyBuilder(row);

        if (!map.has(key)) {
            map.set(key, row);
        }
    });

    return Array.from(map.values());
}


function mergeLogicalMemberRows(rows, keyBuilder, members = []) {
    const map = new Map();
    const expectedMembers = uniqueStrings(members);

    rows.forEach(row => {
        const key = keyBuilder(row);
        if (!map.has(key)) {
            map.set(key, {
                row: {...row},
                members: new Set()
            });
        }

        const bucket = map.get(key);
        if (row.member) {
            bucket.members.add(row.member);
        }
    });

    return Array.from(map.values()).map(bucket => {
        const seenMembers = Array.from(bucket.members).sort();
        const shared = expectedMembers.length > 1 &&
            expectedMembers.every(member =>
                seenMembers.some(seen =>
                    normalizedMemberToken(seen) === normalizedMemberToken(member)
                )
            );

        return {
            ...bucket.row,
            member: shared ? "" : (seenMembers.length === 1 ? seenMembers[0] : seenMembers.join(", ")),
            memberScope: shared ? "shared" : (seenMembers.join(", ") || ""),
            memberCount: seenMembers.length,
            sharedAcrossMembers: shared
        };
    });
}


function collapseLogicalInterfaces(rows, members = []) {
    return mergeLogicalMemberRows(
        rows,
        row => [
            row.interface, row.ip, row.prefix, row.mask, row.network,
            row.vsys, row.vr, row.zone, row.state, row.type
        ].join("|"),
        members
    );
}


function collapseLogicalRoutes(rows, members = []) {
    return sortRoutesDefault(mergeLogicalMemberRows(
        rows,
        row => [
            row.type, row.network, row.next_hop, row.interface,
            row.vr, row.protocol
        ].join("|"),
        members
    ));
}


function hasMemberDivergence(rows, members = []) {
    if (members.length < 2) {
        return false;
    }
    return rows.some(row => !row.sharedAcrossMembers && row.memberScope);
}


function aggregateCpClusters(entries) {
    const result = [];
    const groups = new Map();

    entries.forEach(entry => {
        const topology = entry.raw?.cluster_topology;
        const groupId = safe(topology?.group_id);
        if (entry.source !== "cp" || !groupId) {
            result.push(entry);
            return;
        }
        if (!groups.has(groupId)) groups.set(groupId, []);
        groups.get(groupId).push(entry);
    });

    groups.forEach(group => {
        const base = {...group[0]};
        const topology = group.map(item => item.raw?.cluster_topology).find(Boolean) || {};
        const members = Array.from(new Set(group.map(item => item.device).filter(Boolean))).sort();
        const memberInterfaces = group.flatMap(item => item.interfaces.map(row => ({
            ...row,
            member: item.device,
            addressRole: "member"
        })));
        const virtualInterfaces = Array.isArray(topology.virtual_interfaces)
            ? topology.virtual_interfaces : [];
        const vipRows = virtualInterfaces.map(vip => {
            const matchingMember = memberInterfaces.find(row =>
                row.interface === safe(vip.name) && row.prefix !== null
            );
            const prefix = matchingMember?.prefix ?? null;
            return normalizeInterfaceRow({
                interface: vip.name,
                ip: vip.ip,
                prefix,
                network: prefix !== null ? calculateNetwork(vip.ip, prefix) : "",
                role: "cluster_virtual",
                member: "Cluster VIP"
            }, {source: "cp", member: "Cluster VIP", addressRole: "cluster_virtual"});
        });

        base.interfaces = mergeUniqueRows(
            [...vipRows, ...memberInterfaces],
            row => [row.interface, row.ip, row.member, row.addressRole].join("|")
        );
        const memberRoutes = group.flatMap(item =>
            item.routes.map(row => ({...row, member: item.device}))
        );
        base.routes = collapseLogicalRoutes(memberRoutes, members);
        base.routeDivergence = hasMemberDivergence(base.routes, members);
        base.inventoryStatus = combineInventoryStatus(group);
        base.members = members;
        base.entityType = "cp_cluster";
        base.cluster = safe(topology.display_name) || "ClusterXL";
        base.displayName = base.cluster;
        base.device = base.cluster;
        base.subtitle = "ClusterXL | Members: " + members.join(", ");
        base.clusterNameSource = safe(topology.name_source);
        base.id = "cp-cluster::" + safe(topology.group_id);
        base.raw = {...base.raw, cluster_topology: topology, cluster_members: group.map(item => item.raw)};
        base.searchText = JSON.stringify(base).toLowerCase();
        result.push(base);
    });

    return result;
}


function normalizedMemberToken(value) {
    return safe(value)
        .trim()
        .replace(/[-_.]+$/, "")
        .toLowerCase();
}


function inferPairDescriptor(value) {
    const original = safe(value).trim();
    const text = original.replace(/[-_.]+$/, "");

    let match = text.match(/^(.*?)([-_.])0?([12])$/);
    if (match) {
        return {
            base: match[1].replace(/[-_.]+$/, ""),
            index: Number(match[3])
        };
    }

    match = text.match(/^(.*?)([12])$/);
    if (match && match[1].length >= 4) {
        return {
            base: match[1].replace(/[-_.]+$/, ""),
            index: Number(match[2])
        };
    }

    return null;
}


function clusterDisplayName(value) {
    let base = safe(value).trim().replace(/[-_.]+$/, "");
    const descriptor = inferPairDescriptor(base);
    if (descriptor) {
        base = descriptor.base;
    }

    if (!base) {
        return "Cluster";
    }

    if (/(?:^|[-_.])cls$/i.test(base)) {
        return base;
    }

    return base + "-CLS";
}


function clusterKey(value) {
    let text = safe(value).trim().replace(/[-_.]+$/, "");
    text = text.replace(/(?:[-_.]CLS)$/i, "");
    const descriptor = inferPairDescriptor(text);
    if (descriptor) {
        text = descriptor.base;
    }

    return text
        .replace(/[^a-zA-Z0-9]/g, "")
        .toLowerCase();
}


function uniqueStrings(values) {
    return Array.from(
        new Set(values.map(safe).filter(Boolean))
    );
}


function normalizedMemberSet(values) {
    return new Set(
        values.map(normalizedMemberToken).filter(Boolean)
    );
}


function memberSetsOverlap(left, right) {
    const leftSet = normalizedMemberSet(left);
    const rightSet = normalizedMemberSet(right);
    let hits = 0;

    leftSet.forEach(value => {
        if (rightSet.has(value)) {
            hits += 1;
        }
    });

    return hits;
}


function deduplicateInventory(entries) {
    const result = [];
    const vsxGroups = new Map();

    entries.forEach(entry => {
        if (entry.source !== "vsx") {
            result.push(entry);
            return;
        }

        const physicalKey = clusterKey(entry.cluster || entry.device);
        const key = [
            physicalKey,
            entry.vsys || entry.displayName,
            safe(entry.raw?.vs_id)
        ].join("|");

        if (!vsxGroups.has(key)) {
            vsxGroups.set(key, []);
        }
        vsxGroups.get(key).push(entry);
    });

    vsxGroups.forEach((group, groupKey) => {
        group.sort((left, right) => scoreEntry(right) - scoreEntry(left));

        const base = {...group[0]};
        const members = uniqueStrings(group.map(item => item.device)).sort();
        const memberInterfaces = group.flatMap(item =>
            item.interfaces.map(row => ({...row, member: item.device}))
        );
        const memberRoutes = group.flatMap(item =>
            item.routes.map(row => ({...row, member: item.device}))
        );

        base.interfaces = collapseLogicalInterfaces(memberInterfaces, members);
        base.routes = collapseLogicalRoutes(memberRoutes, members);
        base.interfaceDivergence = hasMemberDivergence(base.interfaces, members);
        base.routeDivergence = hasMemberDivergence(base.routes, members);
        base.inventoryStatus = combineInventoryStatus(group);
        base.members = members;

        const inferred = inferPairDescriptor(members[0] || "");
        base.cluster =
            group.find(item => item.cluster)?.cluster ||
            base.cluster ||
            inferred?.base ||
            "";

        base.parentClusterKey = clusterKey(base.cluster || members[0]);
        base.entityType = "vsx_context";
        base.subtitle = base.cluster
            ? "VSX | " + clusterDisplayName(base.cluster)
            : "VSX | Members: " + members.join(", ");
        base.id =
            "vsx::" +
            base.parentClusterKey +
            "::" +
            safe(base.vsys || base.displayName) +
            "::" +
            safe(base.raw?.vs_id || groupKey);
        base.searchText = JSON.stringify(base).toLowerCase();

        result.push(base);
    });

    return result;
}


function setSimilarity(left, right) {
    const a = new Set(left);
    const b = new Set(right);
    if (!a.size && !b.size) {
        return 1;
    }
    const union = new Set([...a, ...b]);
    let intersection = 0;
    a.forEach(value => {
        if (b.has(value)) {
            intersection += 1;
        }
    });
    return union.size ? intersection / union.size : 0;
}


function panoramaRuntimeSignature(entry) {
    const vsys = uniqueStrings(
        entry.interfaces
            .map(row => row.vsys)
            .filter(value => value !== "0")
    );
    const routers = uniqueStrings([
        ...entry.interfaces.map(row => row.vr),
        ...entry.routes.map(row => row.vr)
    ]);

    return {vsys, routers};
}


function panoramaPairCompatible(left, right) {
    if (!left.interfaces.length || !right.interfaces.length) {
        return false;
    }

    const a = panoramaRuntimeSignature(left);
    const b = panoramaRuntimeSignature(right);
    const vsysSimilarity = setSimilarity(a.vsys, b.vsys);
    const routerSimilarity = setSimilarity(a.routers, b.routers);

    return (
        vsysSimilarity >= 0.75 &&
        routerSimilarity >= 0.60
    );
}


function tagMemberRows(entry) {
    return {
        interfaces: entry.interfaces.map(row => ({
            ...row,
            member: entry.device
        })),
        routes: entry.routes.map(row => ({
            ...row,
            member: entry.device
        }))
    };
}


function makeClusterParent(memberEntries, options = {}) {
    const members = uniqueStrings(
        options.members || memberEntries.map(entry => entry.device)
    ).sort();
    const tagged = memberEntries.map(tagMemberRows);
    const displayName = safe(options.displayName) || clusterDisplayName(members[0] || "Cluster");
    const source = safe(options.source) || safe(memberEntries[0]?.source) || "cp";

    const base = memberEntries.length
        ? {...memberEntries[0]}
        : {
            interfaces: [],
            routes: [],
            inventoryStatus: normalizeInventoryStatus({})
        };

    base.source = source;
    base.entityType = safe(options.entityType) || "cluster";
    base.displayName = displayName;
    base.device = displayName;
    base.cluster = displayName;
    base.vsys = "";
    base.members = members;
    base.memberManagement = memberEntries
        .map(item => ({member: item.device, managementIp: safe(item.managementIp || item.raw?.management_ip)}))
        .filter(item => item.managementIp);
    base.interfaces = mergeUniqueRows(
        tagged.flatMap(item => item.interfaces),
        row => [row.interface, row.ip, row.member, row.vsys, row.vr].join("|")
    );
    base.routes = sortRoutesDefault(
        mergeUniqueRows(
            tagged.flatMap(item => item.routes),
            row => [
                row.network,
                row.next_hop,
                row.interface,
                row.vr,
                row.member
            ].join("|")
        )
    );
    base.inventoryStatus = memberEntries.length
        ? combineInventoryStatus(memberEntries)
        : combineInventoryStatus(options.statusEntries || []);
    base.children = options.children || [];
    base.clusterNameSource = safe(options.clusterNameSource || "inferred_runtime_pair");
    base.subtitle = safe(options.subtitle) || (
        "Cluster | Members: " + members.join(", ")
    );
    base.id = safe(options.id) || (
        source + "-cluster::" + clusterKey(displayName)
    );

    base.children.forEach(child => {
        child.parentId = base.id;
        child.parentDisplayName = base.displayName;
    });

    base.searchText = JSON.stringify(base).toLowerCase();
    return base;
}


function attachChildren(parent, children) {
    parent.children = children;
    children.forEach(child => {
        child.parentId = parent.id;
        child.parentDisplayName = parent.displayName;
        child.cluster = parent.displayName;
        child.subtitle = child.source === "vsx"
            ? "VSX | Parent: " + parent.displayName
            : child.subtitle;
        child.searchText = JSON.stringify(child).toLowerCase();
    });
    parent.searchText = JSON.stringify(parent).toLowerCase();
    return parent;
}


function panoramaVsysChildren(memberEntries, parent) {
    const vsysValues = uniqueStrings(
        memberEntries.flatMap(entry =>
            entry.interfaces
                .map(row => row.vsys)
                .filter(value => value && value !== "0")
        )
    ).sort((left, right) =>
        left.localeCompare(right, undefined, {numeric: true})
    );

    return vsysValues.map(vsys => {
        const interfaces = [];
        const routes = [];

        memberEntries.forEach(entry => {
            entry.interfaces
                .filter(row => row.vsys === vsys)
                .forEach(row => interfaces.push({...row, member: entry.device}));

            const vrOwners = new Map();
            entry.interfaces.forEach(row => {
                if (!row.vr || !row.vsys || row.vsys === "0") {
                    return;
                }
                if (!vrOwners.has(row.vr)) {
                    vrOwners.set(row.vr, new Set());
                }
                vrOwners.get(row.vr).add(row.vsys);
            });

            const nonSystemVsys = uniqueStrings(
                entry.interfaces
                    .map(row => row.vsys)
                    .filter(value => value && value !== "0")
            );

            entry.routes.forEach(row => {
                const owners = vrOwners.get(row.vr);
                const uniquelyOwned = owners && owners.size === 1 && owners.has(vsys);
                const singleVsysDevice = nonSystemVsys.length === 1 && nonSystemVsys[0] === vsys;

                if (uniquelyOwned || singleVsysDevice) {
                    routes.push({...row, member: entry.device});
                }
            });
        });

        const members = [...parent.members];
        const logicalInterfaces = collapseLogicalInterfaces(interfaces, members);
        const logicalRoutes = collapseLogicalRoutes(routes, members);
        const routers = uniqueStrings([
            ...logicalInterfaces.map(row => row.vr),
            ...logicalRoutes.map(row => row.vr)
        ]).sort((left, right) => left.localeCompare(right, undefined, {numeric: true}));

        const routerDisplay = routers.length === 0
            ? ""
            : routers.length <= 2
                ? routers.join(", ")
                : routers[0] + " +" + (routers.length - 1);

        const child = {
            source: "panorama",
            entityType: "pan_vsys",
            device: parent.displayName,
            displayName: "VSYS " + vsys + (routerDisplay ? " | " + routerDisplay : ""),
            subtitle: "Palo Alto VSYS | Parent: " + parent.displayName,
            vsys,
            virtualRouters: routers,
            cluster: parent.displayName,
            interfaces: logicalInterfaces,
            routes: logicalRoutes,
            interfaceDivergence: hasMemberDivergence(logicalInterfaces, members),
            routeDivergence: hasMemberDivergence(logicalRoutes, members),
            members,
            memberManagement: [...(parent.memberManagement || [])],
            inventoryStatus: combineInventoryStatus(memberEntries),
            raw: {derived_from_runtime_vsys: true},
            id: parent.id + "::vsys::" + vsys,
            parentId: parent.id,
            parentDisplayName: parent.displayName
        };
        child.searchText = JSON.stringify(child).toLowerCase();
        return child;
    });
}


function buildInventoryHierarchy(entries) {
    const roots = [];
    const used = new Set();
    const vsxEntries = entries.filter(entry => entry.source === "vsx");
    const cpEntries = entries.filter(entry => entry.source === "cp");
    const panEntries = entries.filter(entry => entry.source === "panorama");
    const otherEntries = entries.filter(entry => !["cp", "vsx", "panorama"].includes(entry.source));

    const vsxByCluster = new Map();
    vsxEntries.forEach(entry => {
        const key = entry.parentClusterKey || clusterKey(entry.cluster || entry.device);
        if (!vsxByCluster.has(key)) {
            vsxByCluster.set(key, []);
        }
        vsxByCluster.get(key).push(entry);
    });

    /*
     * First keep runtime-proven ClusterXL parents. VSX contexts are attached by
     * member overlap, so physical host names disappear from the top-level view.
     */
    cpEntries
        .filter(entry => entry.entityType === "cp_cluster")
        .forEach(parent => {
            const children = [];
            vsxByCluster.forEach(group => {
                if (
                    !group.some(child => used.has(child.id)) &&
                    memberSetsOverlap(parent.members || [], group.flatMap(child => child.members || [])) > 0
                ) {
                    children.push(...group);
                }
            });

            attachChildren(parent, children);
            roots.push(parent);
            used.add(parent.id);
            children.forEach(child => used.add(child.id));
        });

    /*
     * VSX physical clusters that did not expose a usable cphaprob VIP set are
     * still presented as one parent only when the VSX collector itself proves
     * that two physical members back the same logical VS contexts.
     */
    vsxByCluster.forEach(group => {
        const remainingChildren = group.filter(child => !used.has(child.id));
        if (!remainingChildren.length) {
            return;
        }

        const memberNames = uniqueStrings(
            remainingChildren.flatMap(child => child.members || [])
        );
        const memberTokens = normalizedMemberSet(memberNames);
        const physicalEntries = cpEntries.filter(entry =>
            entry.entityType !== "cp_cluster" &&
            !used.has(entry.id) &&
            memberTokens.has(normalizedMemberToken(entry.device))
        );

        if (memberNames.length >= 2 && physicalEntries.length >= 2) {
            const baseName =
                remainingChildren.find(child => child.cluster)?.cluster ||
                inferPairDescriptor(memberNames[0])?.base ||
                memberNames[0];
            const parent = makeClusterParent(physicalEntries, {
                source: "cp",
                entityType: "cp_vsx_cluster",
                displayName: clusterDisplayName(baseName),
                members: memberNames,
                children: remainingChildren,
                clusterNameSource: "vsx_runtime_members",
                subtitle: "VSX Cluster | Members: " + memberNames.join(", ")
            });

            parent.routes = collapseLogicalRoutes(parent.routes, parent.members);
            parent.routeDivergence = hasMemberDivergence(parent.routes, parent.members);

            roots.push(parent);
            physicalEntries.forEach(entry => used.add(entry.id));
            remainingChildren.forEach(child => used.add(child.id));
            return;
        }

        remainingChildren.forEach(child => {
            roots.push(child);
            used.add(child.id);
        });
    });

    cpEntries.forEach(entry => {
        if (!used.has(entry.id)) {
            roots.push(entry);
            used.add(entry.id);
        }
    });

    /*
     * Panorama runtime does not currently return an authoritative HA object.
     * Pairing is therefore deliberately conservative: member names must form
     * a 1/2 pair AND the live VSYS/VR signatures must substantially match.
     * 0.6 Management/API work can replace this inferred relation later.
     */
    const panGroups = new Map();
    panEntries.forEach(entry => {
        const descriptor = inferPairDescriptor(entry.device);
        if (!descriptor) {
            return;
        }
        const key = clusterKey(descriptor.base);
        if (!panGroups.has(key)) {
            panGroups.set(key, new Map());
        }
        panGroups.get(key).set(descriptor.index, {
            entry,
            base: descriptor.base
        });
    });

    panGroups.forEach(pair => {
        const left = pair.get(1);
        const right = pair.get(2);
        if (!left || !right) {
            return;
        }
        if (used.has(left.entry.id) || used.has(right.entry.id)) {
            return;
        }
        if (!panoramaPairCompatible(left.entry, right.entry)) {
            return;
        }

        const memberEntries = [left.entry, right.entry];
        const displayName = clusterDisplayName(left.base);
        const parent = makeClusterParent(memberEntries, {
            source: "panorama",
            entityType: "pan_cluster",
            displayName,
            clusterNameSource: "inferred_ha_runtime_pair",
            subtitle: "Palo Alto HA | Members: " + memberEntries.map(entry => entry.device).join(", ")
        });
        parent.interfaces = collapseLogicalInterfaces(parent.interfaces, parent.members);
        parent.routes = collapseLogicalRoutes(parent.routes, parent.members);
        parent.interfaceDivergence = hasMemberDivergence(parent.interfaces, parent.members);
        parent.routeDivergence = hasMemberDivergence(parent.routes, parent.members);
        const children = panoramaVsysChildren(memberEntries, parent);
        attachChildren(parent, children);
        roots.push(parent);
        memberEntries.forEach(entry => used.add(entry.id));
    });

    panEntries.forEach(entry => {
        if (!used.has(entry.id)) {
            roots.push(entry);
            used.add(entry.id);
        }
    });

    otherEntries.forEach(entry => roots.push(entry));

    roots.forEach(root => {
        root.children = root.children || [];
        root.children.sort((left, right) =>
            left.displayName.localeCompare(right.displayName, undefined, {
                sensitivity: "base",
                numeric: true
            })
        );
        root.searchText = JSON.stringify(root).toLowerCase();
    });

    roots.sort((left, right) =>
        left.displayName.localeCompare(right.displayName, undefined, {
            sensitivity: "base",
            numeric: true
        })
    );

    return roots;
}


function flattenHierarchy(roots) {
    return roots.flatMap(root => [root, ...(root.children || [])]);
}


const logicalEntries = deduplicateInventory(
    aggregateCpClusters(rawData.map(buildEntry))
);
const inventoryRoots = buildInventoryHierarchy(logicalEntries);
inventory = flattenHierarchy(inventoryRoots);

const expandedGroups = new Set();
const activeRouteMemberByEntry = new Map();


function routeTypeBadge(type) {
    const normalized =
        safe(type).toLowerCase() ||
        "unknown";

    return (
        '<span class="route-type ' +
        escapeHtml(normalized) +
        '">' +
        escapeHtml(normalized) +
        "</span>"
    );
}


function interfaceMatchesSubnet(entry, subnet) {
    return entry.interfaces.some(row =>
        [row.ip, row.network, row.mask, row.prefix]
            .join(" ")
            .toLowerCase()
            .includes(subnet)
    );
}


function routeMatchesSubnet(entry, subnet) {
    return entry.routes.some(row =>
        [row.network, row.next_hop, row.interface]
            .join(" ")
            .toLowerCase()
            .includes(subnet)
    );
}


function entryMatches(entry, filters) {
    if (
        filters.vendor &&
        entry.source !== filters.vendor
    ) {
        return false;
    }

    if (
        filters.query &&
        !entry.searchText.includes(filters.query)
    ) {
        return false;
    }

    if (
        filters.subnet &&
        !interfaceMatchesSubnet(entry, filters.subnet) &&
        !routeMatchesSubnet(entry, filters.subnet)
    ) {
        return false;
    }

    return true;
}


function currentFilters() {
    return {
        query: safe(document.getElementById("globalSearch").value).trim().toLowerCase(),
        subnet: safe(document.getElementById("subnetSearch").value).trim().toLowerCase(),
        vendor: safe(document.getElementById("vendorFilter").value).toLowerCase()
    };
}


function filteredInventory() {
    return filteredHierarchy().flatMap(node => [
        node.root,
        ...node.children
    ]);
}


function filteredHierarchy() {
    const filters = currentFilters();
    const hasFilters = Boolean(filters.query || filters.subnet || filters.vendor);
    const visible = [];

    inventoryRoots.forEach(root => {
        const children = root.children || [];
        const selfMatches = entryMatches(root, filters);
        const matchingChildren = children.filter(child => entryMatches(child, filters));

        if (!selfMatches && !matchingChildren.length) {
            return;
        }

        let visibleChildren = [];
        if (!hasFilters) {
            visibleChildren = children;
        } else if (matchingChildren.length) {
            visibleChildren = matchingChildren;
        } else if (selfMatches && (filters.query || filters.subnet) && !filters.vendor) {
            visibleChildren = children;
        }

        visible.push({
            root,
            children: visibleChildren,
            autoExpand: hasFilters && visibleChildren.length > 0
        });
    });

    return visible;
}


function isMatrixEntry(entry) {
    return (
        entry.source === "cp" &&
        ["cp_cluster", "cp_vsx_cluster"].includes(entry.entityType) &&
        Array.isArray(entry.members) &&
        entry.members.length > 1 &&
        entry.interfaces.some(row => row.member)
    );
}


function displayInterfaceCount(entry) {
    if (!isMatrixEntry(entry)) {
        return entry.interfaces.length;
    }
    return new Set(entry.interfaces.map(row => row.interface).filter(Boolean)).size;
}


function routeMembers(entry) {
    const routeMemberTokens = new Set(
        entry.routes
            .flatMap(row => {
                const values = [];
                if (row.member) values.push(row.member);
                if (row.memberScope && row.memberScope !== "shared") {
                    values.push(...row.memberScope.split(",").map(value => value.trim()));
                }
                return values;
            })
            .map(normalizedMemberToken)
            .filter(Boolean)
    );
    return (entry.members || []).filter(member =>
        routeMemberTokens.has(normalizedMemberToken(member))
    );
}


function displayRouteCount(entry) {
    return entry.routes.length;
}


function memberSpecificRows(rows, member) {
    const memberToken = normalizedMemberToken(member);
    return rows.filter(row => {
        if (row.sharedAcrossMembers) return true;
        const scoped = safe(row.memberScope || row.member)
            .split(",")
            .map(value => normalizedMemberToken(value.trim()))
            .filter(Boolean);
        return scoped.includes(memberToken);
    });
}


function routeDiffRows(entry) {
    return entry.routes.filter(row => !row.sharedAcrossMembers && (row.memberScope || row.member));
}



function deviceCardHtml(entry, depth, hasChildren, expanded) {
    const toggle = hasChildren
        ? `<button class="tree-toggle ${expanded ? "expanded" : ""}" type="button" aria-label="${expanded ? "Collapse" : "Expand"} ${escapeHtml(entry.displayName)}">›</button>`
        : '<span class="tree-toggle-spacer"></span>';

    return `
        <div class="device-name-row">
            ${toggle}
            <div class="device-name">${escapeHtml(entry.displayName)}</div>
        </div>
        ${entry.subtitle ? `<div class="device-meta tree-depth-${depth}">${escapeHtml(entry.subtitle)}</div>` : ""}
        <div class="inventory-health-row tree-depth-${depth}">
            <span class="inventory-health ${escapeHtml(inventoryStatusClass(entry.inventoryStatus))}">
                <span class="inventory-health-dot"></span>
                ${escapeHtml(inventoryStatusLabel(entry.inventoryStatus))}
            </span>
            <span class="inventory-updated">
                ${escapeHtml(
                    entry.inventoryStatus.fresh
                        ? "Updated: " + formatInventoryTimestamp(entry.inventoryStatus.collectedAt)
                        : "Last live: " + formatInventoryTimestamp(entry.inventoryStatus.lastSuccessfulCollection)
                )}
            </span>
        </div>
        <div class="tree-depth-${depth}">
            <span class="badge ${escapeHtml(entry.source)}">${escapeHtml(vendorLabel(entry.source))}</span>
            ${hasChildren ? '<span class="badge cluster-badge">CLUSTER</span>' : ""}
            <span class="badge">${displayInterfaceCount(entry)} interfaces</span>
            <span class="badge">${displayRouteCount(entry)} routes</span>
        </div>
    `;
}


function renderDeviceList() {
    const list = document.getElementById("deviceList");
    const tree = filteredHierarchy();
    const visibleEntries = tree.flatMap(node => [node.root, ...node.children]);

    list.innerHTML = "";

    const liveCount = visibleEntries.filter(entry => entry.inventoryStatus.fresh).length;
    const staleCount = visibleEntries.length - liveCount;

    document.getElementById("stats").textContent =
        visibleEntries.length +
        " / " +
        inventory.length +
        " logical views | " +
        liveCount +
        " live" +
        (staleCount ? " | " + staleCount + " old/unavailable" : "");

    if (!tree.length) {
        list.innerHTML = '<div class="empty-list">No matching device found.</div>';
        selectedId = null;
        return;
    }

    if (!selectedId || !visibleEntries.some(entry => entry.id === selectedId)) {
        selectedId = tree[0].root.id;
    }

    const appendEntry = (entry, depth, hasChildren, expanded) => {
        const item = document.createElement("div");
        item.className =
            "device-item status-" +
            inventoryStatusClass(entry.inventoryStatus) +
            (entry.id === selectedId ? " active" : "") +
            (depth ? " device-child" : " device-parent");
        item.dataset.depth = String(depth);
        item.innerHTML = deviceCardHtml(entry, depth, hasChildren, expanded);

        const toggle = item.querySelector(".tree-toggle");
        if (toggle) {
            toggle.addEventListener("click", event => {
                event.stopPropagation();
                if (expandedGroups.has(entry.id)) {
                    expandedGroups.delete(entry.id);
                    const selected = inventory.find(item => item.id === selectedId);
                    if (selected?.parentId === entry.id) {
                        selectedId = entry.id;
                    }
                } else {
                    expandedGroups.add(entry.id);
                }
                renderDeviceList();
            });
        }

        item.addEventListener("click", () => {
            selectedId = entry.id;
            renderDeviceList();
            renderSelected();
        });
        list.appendChild(item);
    };

    tree.forEach(node => {
        const hasChildren = (node.root.children || []).length > 0;
        const expanded = node.autoExpand || expandedGroups.has(node.root.id);
        appendEntry(node.root, 0, hasChildren, expanded);

        if (hasChildren && expanded) {
            node.children.forEach(child =>
                appendEntry(child, 1, false, false)
            );
        }
    });

    renderSelected();
}


function renderSelected() {
    const entry = inventory.find(item => item.id === selectedId);
    if (!entry) {
        return;
    }

    document.getElementById("detailTitle").textContent = entry.displayName;

    const subtitleParts = [vendorTitle(entry.source)];
    if (entry.parentDisplayName) {
        subtitleParts.push("Cluster: " + entry.parentDisplayName);
    } else if (entry.entityType && entry.entityType.includes("cluster")) {
        subtitleParts.push("Members: " + (entry.members || []).join(", "));
    } else if (entry.cluster) {
        subtitleParts.push("Physical: " + entry.cluster);
    } else if (entry.device && entry.device !== entry.displayName) {
        subtitleParts.push("Device: " + entry.device);
    }

    if (entry.vsys && !entry.displayName.startsWith("VSYS ")) {
        subtitleParts.push("VSYS: " + entry.vsys);
    }
    if (Array.isArray(entry.virtualRouters) && entry.virtualRouters.length) {
        subtitleParts.push("VR: " + entry.virtualRouters.join(", "));
    }

    document.getElementById("detailSubtitle").textContent = subtitleParts.join(" | ");

    const managementRows = Array.isArray(entry.memberManagement) && entry.memberManagement.length
        ? entry.memberManagement
        : entry.managementIp
            ? [{member: entry.device || entry.displayName, managementIp: entry.managementIp}]
            : [];
    const management = document.getElementById("detailManagement");
    if (management) {
        management.innerHTML = managementRows.length
            ? `<span class="management-label">Management</span>${managementRows.map(row => `
                <span class="management-chip">
                    <span class="management-member">${escapeHtml(row.member)}</span>
                    <span class="management-ip">${escapeHtml(row.managementIp)}</span>
                </span>
            `).join("")}`
            : "";
        management.hidden = !managementRows.length;
    }

    const statusTimestamp = entry.inventoryStatus.fresh
        ? entry.inventoryStatus.collectedAt
        : entry.inventoryStatus.lastSuccessfulCollection;

    document.getElementById("detailCounts").innerHTML = `
        <div class="detail-health">
            <span class="inventory-health ${escapeHtml(inventoryStatusClass(entry.inventoryStatus))}">
                <span class="inventory-health-dot"></span>
                ${escapeHtml(inventoryStatusLabel(entry.inventoryStatus))}
            </span>
            <span class="detail-health-time">
                ${escapeHtml(entry.inventoryStatus.fresh ? "Updated" : "Last successful")}:
                ${escapeHtml(formatInventoryTimestamp(statusTimestamp))}
            </span>
            ${
                !entry.inventoryStatus.fresh && entry.inventoryStatus.availabilityState
                    ? `<span class="detail-health-reason">State: ${escapeHtml(entry.inventoryStatus.availabilityState)}</span>`
                    : ""
            }
        </div>
        ${entry.members?.length > 1 ? `<span class="badge">${entry.members.length} members</span>` : ""}
        ${entry.interfaceDivergence ? '<span class="badge divergence-badge">Interface diff</span>' : ""}
        ${entry.routeDivergence ? '<span class="badge divergence-badge">Route diff</span>' : ""}
        <span class="badge">${displayInterfaceCount(entry)} interfaces</span>
        <span class="badge">${displayRouteCount(entry)} routes</span>
    `;

    renderInterfaceTable(entry);
    renderRouteTable(entry);
}


function sortRows(rows, key, direction) {
    return [...rows].sort((left, right) => {
        const leftValue = safe(left[key]);
        const rightValue = safe(right[key]);
        return leftValue.localeCompare(rightValue, undefined, {
            sensitivity: "base",
            numeric: true
        }) * direction;
    });
}


function matrixAddressHtml(rows) {
    const addresses = mergeUniqueRows(
        rows.filter(row => row.ip),
        row => [row.ip, row.prefix, row.mask].join("|")
    );

    if (!addresses.length) {
        return '<span class="matrix-empty">—</span>';
    }

    return addresses.map(row => `
        <div class="matrix-address">
            <span class="matrix-ip">${escapeHtml(row.ip)}</span>
            ${row.prefix !== null && row.prefix !== undefined ? `<span class="matrix-prefix">/${escapeHtml(row.prefix)}</span>` : ""}
        </div>
    `).join("");
}


function renderClusterInterfaceMatrix(entry) {
    const search = safe(document.getElementById("interfaceSearch").value).toLowerCase();
    const groups = new Map();

    entry.interfaces.forEach(row => {
        const key = safe(row.interface);
        if (!key) {
            return;
        }
        if (!groups.has(key)) {
            groups.set(key, []);
        }
        groups.get(key).push(row);
    });

    let matrixRows = Array.from(groups.entries())
        .map(([interfaceName, rows]) => ({interfaceName, rows}))
        .filter(group =>
            !search ||
            group.interfaceName.toLowerCase().includes(search) ||
            JSON.stringify(group.rows).toLowerCase().includes(search)
        )
        .sort((left, right) =>
            left.interfaceName.localeCompare(right.interfaceName, undefined, {
                sensitivity: "base",
                numeric: true
            }) * interfaceSort.direction
        );

    const members = entry.members || [];
    const hasVip = entry.interfaces.some(row => row.addressRole === "cluster_virtual");
    const head = document.querySelector("#interfaceTable thead");
    const body = document.querySelector("#interfaceTable tbody");

    head.innerHTML = `
        <tr>
            <th data-key="interface" class="matrix-interface-header">Interface</th>
            ${hasVip ? '<th class="matrix-vip-header">Cluster VIP</th>' : ""}
            ${members.map(member => `<th class="matrix-member-header">${escapeHtml(member)}</th>`).join("")}
            <th>Network</th>
        </tr>
    `;

    if (!matrixRows.length) {
        body.innerHTML = `
            <tr>
                <td colspan="${2 + members.length + (hasVip ? 1 : 0)}" class="no-data">No interface data.</td>
            </tr>
        `;
    } else {
        body.innerHTML = matrixRows.map(group => {
            const networks = uniqueStrings(group.rows.map(row => row.network));
            const vipRows = group.rows.filter(row => row.addressRole === "cluster_virtual");
            return `
                <tr>
                    <td class="matrix-interface-name">${escapeHtml(group.interfaceName)}</td>
                    ${hasVip ? `<td class="matrix-vip-cell">${matrixAddressHtml(vipRows)}</td>` : ""}
                    ${members.map(member =>
                        `<td>${matrixAddressHtml(group.rows.filter(row => normalizedMemberToken(row.member) === normalizedMemberToken(member) && row.addressRole !== "cluster_virtual"))}</td>`
                    ).join("")}
                    <td class="matrix-network-cell">${networks.length ? networks.map(escapeHtml).join("<br>") : '<span class="matrix-empty">—</span>'}</td>
                </tr>
            `;
        }).join("");
    }

    const interfaceHeader = head.querySelector('th[data-key="interface"]');
    if (interfaceHeader) {
        interfaceHeader.addEventListener("click", () => {
            interfaceSort.direction *= -1;
            renderInterfaceTable(entry);
        });
    }
}


function renderInterfaceTable(entry) {
    if (isMatrixEntry(entry)) {
        renderClusterInterfaceMatrix(entry);
        return;
    }

    const search = safe(document.getElementById("interfaceSearch").value).toLowerCase();
    let rows = entry.interfaces.filter(row =>
        !search || JSON.stringify(row).toLowerCase().includes(search)
    );
    rows = sortRows(rows, interfaceSort.key, interfaceSort.direction);

    const showMemberScope = rows.some(row =>
        !row.sharedAcrossMembers && (row.memberScope || row.member)
    );
    const head = document.querySelector("#interfaceTable thead");
    const body = document.querySelector("#interfaceTable tbody");

    head.innerHTML = `
        <tr>
            <th data-key="interface">Interface</th>
            <th data-key="ip">IP Address</th>
            <th data-key="mask">Subnet Mask</th>
            <th data-key="network">Network</th>
            <th data-key="vsys">VSYS</th>
            <th data-key="vr">Virtual Router</th>
            <th data-key="zone">Zone</th>
            ${showMemberScope ? '<th>Member Scope</th>' : ""}
        </tr>
    `;

    if (!rows.length) {
        body.innerHTML = `<tr><td colspan="${showMemberScope ? 8 : 7}" class="no-data">No interface data.</td></tr>`;
    } else {
        body.innerHTML = rows.map(row => `
            <tr class="${!row.sharedAcrossMembers && row.memberScope ? "difference-row" : ""}">
                <td>${escapeHtml(row.interface)}</td>
                <td>${escapeHtml(row.ip)}</td>
                <td>${row.mask ? escapeHtml(row.mask) + (row.prefix !== null ? " /" + escapeHtml(row.prefix) : "") : ""}</td>
                <td>${escapeHtml(row.network)}</td>
                <td>${escapeHtml(row.vsys)}</td>
                <td>${escapeHtml(row.vr)}</td>
                <td>${escapeHtml(row.zone)}</td>
                ${showMemberScope ? `<td>${row.sharedAcrossMembers ? '<span class="scope-chip shared">Shared</span>' : `<span class="scope-chip diff">${escapeHtml(row.memberScope || row.member)}</span>`}</td>` : ""}
            </tr>
        `).join("");
    }

    head.querySelectorAll("th[data-key]").forEach(header => {
        header.addEventListener("click", () => {
            const key = header.dataset.key;
            if (interfaceSort.key === key) {
                interfaceSort.direction *= -1;
            } else {
                interfaceSort.key = key;
                interfaceSort.direction = 1;
            }
            renderInterfaceTable(entry);
        });
    });
}


function renderRouteMemberTabs(entry, members) {
    const container = document.getElementById("routeMemberTabs");
    const divergentLogicalView = Boolean(entry.routeDivergence && members.length >= 2);

    if (!divergentLogicalView) {
        container.hidden = true;
        container.innerHTML = "";
        activeRouteViewByEntry.delete(entry.id);
        return "logical";
    }

    const views = [
        {id: "logical", label: "Logical"},
        ...members.map(member => ({id: member, label: member})),
        {id: "diff", label: "Diff only"}
    ];

    let selected = activeRouteViewByEntry.get(entry.id);
    if (!views.some(view => view.id === selected)) {
        selected = "logical";
        activeRouteViewByEntry.set(entry.id, selected);
    }

    container.hidden = false;
    container.innerHTML = `
        <span class="member-tabs-label">Route comparison</span>
        ${views.map(view => `
            <button
                type="button"
                class="member-tab ${view.id === selected ? "active" : ""}"
                data-member="${escapeHtml(view.id)}"
            >${escapeHtml(view.label)}</button>
        `).join("")}
    `;

    container.querySelectorAll(".member-tab").forEach(button => {
        button.addEventListener("click", () => {
            activeRouteViewByEntry.set(entry.id, button.dataset.member);
            renderRouteTable(entry);
        });
    });

    return selected;
}


function renderRouteTable(entry) {
    const search = safe(document.getElementById("routeSearch").value).toLowerCase();
    const members = entry.members || [];
    const selectedView = renderRouteMemberTabs(entry, members);

    let rows = entry.routes;
    if (selectedView === "diff") {
        rows = routeDiffRows(entry);
    } else if (selectedView !== "logical") {
        rows = memberSpecificRows(entry.routes, selectedView);
    }

    rows = rows.filter(row =>
        !search || JSON.stringify(row).toLowerCase().includes(search)
    );

    if (routeSort.key === "type") {
        rows = sortRoutesDefault(rows);
        if (routeSort.direction < 0) rows.reverse();
    } else {
        rows = sortRows(rows, routeSort.key, routeSort.direction);
    }

    const showMemberScope = rows.some(row =>
        !row.sharedAcrossMembers && (row.memberScope || row.member)
    );
    const head = document.querySelector("#routeTable thead");
    const body = document.querySelector("#routeTable tbody");

    head.innerHTML = `
        <tr>
            <th data-key="type">Type</th>
            <th data-key="network">Network</th>
            <th data-key="next_hop">Next Hop</th>
            <th data-key="interface">Interface</th>
            <th data-key="vr">Virtual Router</th>
            <th data-key="protocol">Protocol</th>
            ${showMemberScope ? '<th>Member Scope</th>' : ""}
        </tr>
    `;

    if (!rows.length) {
        body.innerHTML = `<tr><td colspan="${showMemberScope ? 7 : 6}" class="no-data">No routing data.</td></tr>`;
    } else {
        body.innerHTML = rows.map(row => `
            <tr class="${row.warning ? "warning-row" : ""} ${!row.sharedAcrossMembers && row.memberScope ? "difference-row" : ""}">
                <td>${routeTypeBadge(row.type)}</td>
                <td>${escapeHtml(row.network)}</td>
                <td>${escapeHtml(row.next_hop)}</td>
                <td>${escapeHtml(row.interface)}</td>
                <td>${escapeHtml(row.vr)}</td>
                <td>${escapeHtml(row.protocol)}</td>
                ${showMemberScope ? `<td>${row.sharedAcrossMembers ? '<span class="scope-chip shared">Shared</span>' : `<span class="scope-chip diff">${escapeHtml(row.memberScope || row.member)}</span>`}</td>` : ""}
            </tr>
        `).join("");
    }

    head.querySelectorAll("th[data-key]").forEach(header => {
        header.addEventListener("click", () => {
            const key = header.dataset.key;
            if (routeSort.key === key) {
                routeSort.direction *= -1;
            } else {
                routeSort.key = key;
                routeSort.direction = 1;
            }
            renderRouteTable(entry);
        });
    });
}


function switchTab(nextTab) {
    activeTab = nextTab;

    document.querySelectorAll(".tab").forEach(tab => {
        tab.classList.toggle("active", tab.dataset.tab === nextTab);
    });

    document.getElementById("interfacesPanel").classList.toggle("active", nextTab === "interfaces");
    document.getElementById("routingPanel").classList.toggle("active", nextTab === "routing");
}


document.getElementById("globalSearch").addEventListener("input", renderDeviceList);
document.getElementById("subnetSearch").addEventListener("input", renderDeviceList);
document.getElementById("vendorFilter").addEventListener("change", renderDeviceList);
document.getElementById("interfaceSearch").addEventListener("input", renderSelected);
document.getElementById("routeSearch").addEventListener("input", renderSelected);

document.querySelectorAll(".tab").forEach(tab => {
    tab.addEventListener("click", () => switchTab(tab.dataset.tab));
});

renderDeviceList();
switchTab(activeTab);

