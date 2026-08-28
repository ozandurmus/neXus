// F-Buddy Phase 0.5 Final UI Closure
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


function safe(value) {
    if (value === null || value === undefined) {
        return "";
    }

    return String(value);
}


function escapeHtml(value) {
    return safe(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}


function normalizedSource(value) {
    const source = safe(value).trim().toLowerCase();

    if (
        source === "panorama-runtime" ||
        source === "panorama_runtime" ||
        source === "pan"
    ) {
        return "panorama";
    }

    if (
        source === "checkpoint" ||
        source === "check point"
    ) {
        return "cp";
    }

    return source;
}


function vendorLabel(source) {
    if (source === "panorama") {
        return "PAN";
    }

    return safe(source).toUpperCase();
}


function vendorTitle(source) {
    if (source === "panorama") {
        return "Palo Alto";
    }

    if (source === "vsx") {
        return "VSX";
    }

    if (source === "cp") {
        return "CP";
    }

    return safe(source).toUpperCase();
}


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


function formatInventoryTimestamp(value) {
    if (!value) {
        return "No successful collection timestamp";
    }

    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
        return value;
    }

    return parsed.toLocaleString(undefined, {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
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


function preferredTheme() {
    let saved = "";
    try {
        saved = localStorage.getItem("securityexpert-theme") || localStorage.getItem("fbuddy-theme") || "";
    } catch (error) {
        saved = "";
    }
    if (saved === "light" || saved === "dark") {
        return saved;
    }
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches
        ? "light"
        : "dark";
}


function applyTheme(theme) {
    const normalized = theme === "light" ? "light" : "dark";
    document.documentElement.dataset.theme = normalized;
    const button = document.getElementById("themeToggle");
    if (button) {
        button.setAttribute("aria-label", normalized === "dark" ? "Switch to light mode" : "Switch to dark mode");
        button.title = normalized === "dark" ? "Light mode" : "Dark mode";
        button.innerHTML = normalized === "dark"
            ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 18a6 6 0 1 1 0-12 6 6 0 0 1 0 12Zm0-16v2m0 16v2M4.93 4.93l1.42 1.42m11.3 11.3 1.42 1.42M2 12h2m16 0h2M4.93 19.07l1.42-1.42m11.3-11.3 1.42-1.42"/></svg>'
            : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.2 15.3A8.5 8.5 0 0 1 8.7 3.8 8.5 8.5 0 1 0 20.2 15.3Z"/></svg>';
    }
}


function toggleTheme() {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    try {
        localStorage.setItem("securityexpert-theme", next);
        localStorage.setItem("fbuddy-theme", next); // backward-compatible migration key
    } catch (error) {
        // Standalone file:// exports may restrict storage; theme still changes for the session.
    }
    applyTheme(next);
}


applyTheme(preferredTheme());
document.getElementById("themeToggle")?.addEventListener("click", toggleTheme);


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


// SecurityExpert Phase 0.6.0A4.3.3 — Configuration UI refinement
let activeModule = "overview";
let activeConfigTab = "overview";
let configSelectedId = "__fleet__";
let configFleetFilter = "all";
let configHeaderExpanded = null;
let configSidebarOpen = false;
let complianceSelectedSubjectId = "__fleet__";
let complianceVendorFilter = "all";
let complianceStatusFilter = "all";

const configDevices = Array.isArray(configUiData?.devices)
    ? configUiData.devices
    : [];
const complianceSubjects = Array.isArray(complianceUiData?.subjects)
    ? complianceUiData.subjects
    : [];


function formatNumber(value) {
    const numeric = Number(value || 0);
    return Number.isFinite(numeric) ? numeric.toLocaleString() : "0";
}


function formatPercent(value) {
    const numeric = Number(value || 0);
    if (!Number.isFinite(numeric)) return "0%";
    return numeric.toLocaleString(undefined, {
        minimumFractionDigits: numeric > 0 && numeric < 100 ? 1 : 0,
        maximumFractionDigits: 1
    }) + "%";
}


function formatBytes(value) {
    let numeric = Number(value || 0);
    if (!Number.isFinite(numeric) || numeric <= 0) return "—";
    const units = ["B", "KB", "MB", "GB"];
    let unit = 0;
    while (numeric >= 1024 && unit < units.length - 1) {
        numeric /= 1024;
        unit += 1;
    }
    return numeric.toLocaleString(undefined, {
        minimumFractionDigits: unit ? 1 : 0,
        maximumFractionDigits: 1
    }) + " " + units[unit];
}


function formatConfigTimestamp(value) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return safe(value);
    return date.toLocaleString(undefined, {
        year: "numeric",
        month: "short",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
}


function statusTone(classification) {
    const value = safe(classification).toUpperCase();
    if (["EFFECTIVE_DRIFT", "PANORAMA_OUT_OF_SYNC", "COLLECTION_FAILURE", "FAILED"].includes(value)) {
        return "danger";
    }
    if (value === "LOCAL_OVERRIDE" || value === "DIFFERENCE_OBSERVED") return "warning";
    if (value === "ALIGNED" || value === "SUCCESS" || value === "CURRENT" || value === "IN_SYNC") {
        return "success";
    }
    if (value === "MEMBER_SPECIFIC") return "info";
    if (["PROVENANCE_UNVERIFIED", "IDENTITY_TRANSLATION_REQUIRED", "EXPECTED_ONLY", "ACTUAL_ONLY", "LOCAL_ONLY", "INSUFFICIENT_EVIDENCE", "UNKNOWN"].includes(value)) {
        return "muted";
    }
    return "neutral";
}


function statusPill(label, tone = "neutral") {
    return `<span class="status-pill ${escapeHtml(tone)}"><span class="status-pill-dot"></span>${escapeHtml(label)}</span>`;
}


function classificationLabel(value) {
    return safe(configUiData?.classification_labels?.[value]) || safe(value).replaceAll("_", " ").toLowerCase().replace(/\b\w/g, char => char.toUpperCase());
}


function categoryLabel(value) {
    return safe(configUiData?.category_labels?.[value]) || safe(value).replaceAll("_", " ").replace(/\b\w/g, char => char.toUpperCase());
}


function metricCard(label, value, detail = "", tone = "neutral") {
    return `
        <div class="metric-card ${escapeHtml(tone)}">
            <div class="metric-label">${escapeHtml(label)}</div>
            <div class="metric-value">${escapeHtml(value)}</div>
            ${detail ? `<div class="metric-detail">${escapeHtml(detail)}</div>` : ""}
        </div>
    `;
}


function inventoryOverviewStats() {
    const total = inventory.length;
    const live = inventory.filter(entry => entry.inventoryStatus?.fresh).length;
    return {
        total,
        live,
        notLive: Math.max(0, total - live)
    };
}


function currentConfigurationFleet() {
    return configUiData?.available ? (configUiData.fleet || {}) : {};
}


function renderOverviewModule() {
    const inventoryStats = inventoryOverviewStats();
    const fleet = currentConfigurationFleet();
    const available = Boolean(configUiData?.available);
    const workflow = configUiData?.workflow || {};
    const hero = document.getElementById("overviewHeroCards");
    const runBadge = document.getElementById("overviewRunBadge");

    if (runBadge) {
        if (workflow && workflow.checkpoint === false && workflow.mode) {
            runBadge.innerHTML = `
                <span>Development view · ${escapeHtml(workflow.label || workflow.mode)}</span>
                <strong>${workflow.mixed_cycle ? "Mixed-cycle artifacts · not a checkpoint" : "Not a checkpoint"}</strong>
            `;
        } else {
            runBadge.innerHTML = fleet.run_id
                ? `<span>Observation cycle</span><strong>${escapeHtml(fleet.run_id)}</strong>`
                : `<span>Inventory export</span><strong>Configuration not attached</strong>`;
        }
    }

    if (hero) {
        hero.innerHTML = [
            metricCard(
                "Network Inventory",
                `${formatNumber(inventoryStats.live)} live`,
                `${formatNumber(inventoryStats.total)} logical views · ${formatNumber(inventoryStats.notLive)} old/unavailable`,
                inventoryStats.notLive ? "warning" : "success"
            ),
            metricCard(
                "Current Configuration",
                available ? `${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)}` : "Not collected",
                available ? "Devices with current primary configuration evidence" : "Run full main.py to attach configuration evidence",
                available && fleet.primary_evidence_success === fleet.selected ? "success" : "muted"
            ),
            metricCard(
                "Devices with Overrides",
                available ? formatNumber(fleet.devices_with_local_override) : "—",
                available ? `${formatNumber(fleet.local_override)} settings · operator attention, not automatic failure` : "Configuration plane unavailable",
                available && Number(fleet.local_override) > 0 ? "warning" : "success"
            ),
            metricCard(
                "Effective Drift",
                available ? `${formatNumber(fleet.devices_with_effective_drift)} devices` : "—",
                available ? `${formatNumber(fleet.effective_drift)} settings · unexplained effective divergence` : "Configuration plane unavailable",
                available && Number(fleet.effective_drift) > 0 ? "danger" : "success"
            )
        ].join("");
    }

    const alignment = document.getElementById("overviewAlignmentSummary");
    if (alignment) {
        alignment.innerHTML = available
            ? `
                <div class="alignment-summary-strip">
                    ${metricCard("Current devices", `${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)}`, "Primary effective configuration", "success")}
                    ${metricCard("Override impact", `${formatNumber(fleet.devices_with_local_override)} devices`, `${formatNumber(fleet.local_override)} settings`, fleet.devices_with_local_override ? "warning" : "success")}
                    ${metricCard("Unexplained drift", `${formatNumber(fleet.devices_with_effective_drift)} devices`, `${formatNumber(fleet.effective_drift)} settings`, fleet.devices_with_effective_drift ? "danger" : "success")}
                    ${metricCard("Evidence gaps", `${formatNumber(fleet.devices_with_coverage_gaps)} devices`, "Drill down in Configuration → Alignment", "muted")}
                </div>
                <div class="posture-note">
                    <strong>Executive interpretation:</strong> this view is device-impact first. Setting-level engine telemetry stays in Configuration → Alignment.
                </div>
            `
            : `<div class="empty-state"><strong>Configuration evidence is not attached to this HTML export.</strong><span>Run the normal full workflow to populate Configuration.</span></div>`;
    }

    const evidence = document.getElementById("overviewEvidenceSummary");
    if (evidence) {
        evidence.innerHTML = available
            ? `
                <div class="summary-list">
                    <div><span>Primary config evidence</span><strong>${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)}</strong></div>
                    <div><span>Devices with coverage gaps</span><strong>${formatNumber(fleet.devices_with_coverage_gaps)}</strong></div>
                    <div><span>Devices with provenance gaps</span><strong>${formatNumber(fleet.devices_with_provenance_unverified)}</strong></div>
                    <div><span>Method failures</span><strong>${formatNumber(fleet.method_failures)}</strong></div>
                </div>
                ${fleet.tls_verify
                    ? `<div class="inline-message success">TLS peer verification enabled.</div>`
                    : `<div class="inline-message warning">TLS peer verification is still disabled for PAN configuration collection. CA trust remains a production hardening item.</div>`}
            `
            : `<div class="empty-state compact"><span>No configuration evidence in this export.</span></div>`;
    }

    const backup = document.getElementById("overviewBackupSummary");
    if (backup) {
        backup.innerHTML = `
            <div class="backup-overview">
                <div class="backup-icon" aria-hidden="true">↻</div>
                <div>
                    <strong>Native recovery backup not configured yet</strong>
                    <p>Phase ${escapeHtml(configUiData?.backup?.phase || "0.6.0B")} will add PAN device-state recovery artifacts with integrity verification.</p>
                </div>
            </div>
        `;
    }
}


function savedModule() {
    const hashModule = safe(window.location.hash).replace("#", "");
    if (["overview", "inventory", "configuration", "compliance", "project-plan"].includes(hashModule)) {
        return hashModule;
    }
    try {
        const value = localStorage.getItem("securityexpert-module");
        return ["overview", "inventory", "configuration", "compliance", "discovery", "project-plan"].includes(value) ? value : "overview";
    } catch (error) {
        return "overview";
    }
}


function switchModule(nextModule) {
    activeModule = ["overview", "inventory", "configuration", "compliance", "discovery", "project-plan"].includes(nextModule)
        ? nextModule
        : "overview";

    document.querySelectorAll("[data-module-panel]").forEach(panel => {
        panel.classList.toggle("active", panel.dataset.modulePanel === activeModule);
    });
    document.querySelectorAll(".module-nav-item").forEach(button => {
        button.classList.toggle("active", button.dataset.module === activeModule);
    });

    const inventoryControls = document.getElementById("inventoryTopControls");
    const configurationControls = document.getElementById("configurationTopControls");
    if (inventoryControls) inventoryControls.hidden = activeModule !== "inventory";
    if (configurationControls) configurationControls.hidden = activeModule !== "configuration";

    try {
        localStorage.setItem("securityexpert-module", activeModule);
    } catch (error) {
        // Standalone file exports can restrict storage.
    }
    try {
        if (window.location.hash !== `#${activeModule}`) {
            history.replaceState(null, "", `#${activeModule}`);
        }
    } catch (error) {
        // file:// history can be restricted; module switching still works.
    }

    if (activeModule === "overview") renderOverviewModule();
    if (activeModule === "inventory") renderDeviceList();
    if (activeModule === "configuration") {
        renderConfigDeviceList();
        renderConfigSelected();
    }
    if (activeModule === "compliance") renderComplianceModule();
    if (activeModule === "discovery") renderDiscoveryModule();
    if (activeModule === "project-plan") renderProjectPlan();
}


function configDeviceCounts(device) {
    return device?.alignment?.counts || {};
}


function configDeviceStatusHtml(device) {
    const tone = safe(device?.tone || "neutral");
    return statusPill(device?.status_label || "Unknown", tone === "coverage" ? "muted" : tone);
}


function configFleetVendorLabel(filter = configFleetFilter) {
    if (filter === "check_point") return "Check Point";
    if (filter === "palo_alto") return "Palo Alto";
    return "All";
}


function configVendorDevices(filter = configFleetFilter) {
    if (filter === "all") return configDevices.slice();
    return configDevices.filter(device => safe(device.vendor_key) === filter);
}


function configScopedFleet() {
    const globalFleet = currentConfigurationFleet();
    if (configFleetFilter === "all") return globalFleet;
    const devices = configVendorDevices();
    const counts = key => devices.reduce((sum, device) => sum + Number(configDeviceCounts(device)[key] || 0), 0);
    const success = devices.filter(device => device.connected).length;
    const changed = devices.filter(device => safe(device.history?.actual_change_state) === "changed").length;
    const first = devices.filter(device => safe(device.history?.actual_change_state) === "first").length;
    const same = devices.filter(device => safe(device.history?.actual_change_state) === "same").length;
    const coverageGaps = devices.filter(device => !device.connected || safe(device.status_label).toLowerCase().includes("coverage")).length;
    const operationalFailures = devices.filter(device => !device.connected && safe(device.failure_family) !== "capability_gap").length;
    const capabilityGaps = devices.filter(device => safe(device.failure_family) === "capability_gap").length;
    return {
        ...globalFleet,
        selected: devices.length,
        success,
        failed: Math.max(0, devices.length - success),
        primary_evidence_success: success,
        first,
        same,
        changed,
        local_override: counts("LOCAL_OVERRIDE"),
        effective_drift: counts("EFFECTIVE_DRIFT"),
        member_specific: counts("MEMBER_SPECIFIC"),
        devices_with_local_override: devices.filter(device => Number(configDeviceCounts(device).LOCAL_OVERRIDE || 0) > 0).length,
        devices_with_effective_drift: devices.filter(device => Number(configDeviceCounts(device).EFFECTIVE_DRIFT || 0) > 0).length,
        devices_with_coverage_gaps: coverageGaps,
        method_failures: operationalFailures,
        checkpoint_capability_gaps: configFleetFilter === "check_point" ? capabilityGaps : 0,
    };
}


function renderConfigFleetFilters() {
    const host = document.getElementById("configFleetFilters");
    if (!host) return;
    const all = configDevices.length;
    const cp = configDevices.filter(device => device.vendor_key === "check_point").length;
    const pan = configDevices.filter(device => device.vendor_key === "palo_alto").length;
    host.innerHTML = [
        ["all", "All", all],
        ["check_point", "Check Point", cp],
        ["palo_alto", "Palo Alto", pan],
    ].map(([key, label, count]) => `
        <button type="button" class="config-fleet-filter ${configFleetFilter === key ? "active" : ""}" data-config-fleet-filter="${key}">
            <span>${escapeHtml(label)}</span><strong>${formatNumber(count)}</strong>
        </button>
    `).join("");
    host.querySelectorAll("[data-config-fleet-filter]").forEach(button => {
        button.addEventListener("click", () => {
            configFleetFilter = button.dataset.configFleetFilter || "all";
            configSelectedId = "__fleet__";
            renderConfigFleetFilters();
            renderConfigDeviceList();
            renderConfigSelected();
            switchConfigTab("overview");
        });
    });
}


function configDeviceItemHtml(device, {depth = 0, displayName = null} = {}) {
    const counts = configDeviceCounts(device);
    const platform = device.vendor_key === "check_point" && device.platform_label ? device.platform_label : null;
    const meta = [device.vendor, platform && platform !== "Gaia" ? platform : null, device.model, device.sw_version, device.management_ip]
        .filter(Boolean).join(" · ");
    const depthClass = depth ? ` config-device-depth-${Math.min(depth, 3)}` : "";
    return `
        <div class="config-device-item ${device.id === configSelectedId ? "active" : ""}${depthClass}" data-config-device="${escapeHtml(device.id)}">
            <div class="config-device-title-row">
                <div class="config-device-name">${escapeHtml(displayName || device.name || "Unnamed device")}</div>
                ${configDeviceStatusHtml(device)}
            </div>
            <div class="config-device-meta">${escapeHtml(meta)}</div>
            <div class="config-device-meta secondary">${device.parent_name ? `↳ ${escapeHtml(device.parent_name)} · ` : ""}SN ${escapeHtml(device.serial || "—")}</div>
            <div class="config-device-tags compact">
                ${Number(counts.LOCAL_OVERRIDE || 0) ? statusPill(`${formatNumber(counts.LOCAL_OVERRIDE)} override`, "warning") : ""}
                ${Number(counts.EFFECTIVE_DRIFT || 0) ? statusPill(`${formatNumber(counts.EFFECTIVE_DRIFT)} drift`, "danger") : ""}
                ${Number(counts.MEMBER_SPECIFIC || 0) ? statusPill(`${formatNumber(counts.MEMBER_SPECIFIC)} member-specific`, "info") : ""}
                ${device.failure_family === "capability_gap" ? statusPill("platform capability", "muted") : ""}
                ${Number(counts.PROVENANCE_UNVERIFIED || 0) ? statusPill(`${formatNumber(counts.PROVENANCE_UNVERIFIED)} provenance gap`, "muted") : ""}
            </div>
        </div>
    `;
}


function configDeviceHierarchyHtml(devices) {
    const consumed = new Set();
    const chunks = [];
    const vsxGroups = new Map();
    devices.forEach(device => {
        if (device.vendor_key !== "check_point") return;
        if (!["vsx_host", "virtual_system"].includes(device.entity_type)) return;
        const key = safe(device.cluster_group_id) || safe(device.presentation_group_id) || (device.entity_type === "vsx_host" ? `host:${device.id}` : "");
        if (!key) return;
        if (!vsxGroups.has(key)) vsxGroups.set(key, []);
        vsxGroups.get(key).push(device);
    });

    for (const [groupKey, groupDevices] of vsxGroups.entries()) {
        const hosts = groupDevices.filter(device => device.entity_type === "vsx_host");
        const contexts = groupDevices.filter(device => device.entity_type === "virtual_system");
        // A single physical host without shared cluster identity is left in the
        // normal list; grouping is valuable only when there is a VS hierarchy.
        if (!contexts.length) continue;
        const authoritativeLabel = groupDevices.find(device => device.cluster_display_name)?.cluster_display_name;
        const presentationLabel = groupDevices.find(device => device.presentation_group_label)?.presentation_group_label;
        const label = authoritativeLabel || presentationLabel || hosts[0]?.parent_name || hosts[0]?.name || "VSX";
        const groupKind = authoritativeLabel ? "VSX Cluster" : (presentationLabel ? "VSX Pair" : "VSX Host");
        const logicalVsCount = new Set(contexts.map(ctx => `${safe(ctx.vs_id)}|${safe(ctx.name)}`)).size;
        const roleCounts = hosts.reduce((acc, host) => {
            const role = safe(host.ha_role).toUpperCase();
            if (role) acc[role] = (acc[role] || 0) + 1;
            return acc;
        }, {});
        const roleSummary = Object.entries(roleCounts).map(([role, count]) => `${role} ${count}`).join(" · ");
        const aggregateMeta = [
            `${hosts.length} member${hosts.length === 1 ? "" : "s"}`,
            `${logicalVsCount} virtual system${logicalVsCount === 1 ? "" : "s"}`,
            roleSummary || null
        ].filter(Boolean).join(" · ");
        chunks.push(`
            <div class="config-tree-group">
                <div class="config-tree-group-header"><span>${escapeHtml(groupKind)}</span><strong>${escapeHtml(label)}</strong><small>${escapeHtml(aggregateMeta)}</small></div>
                ${hosts.length ? `<div class="config-tree-section-label">Members</div>${hosts.map(host => {
                    consumed.add(host.id);
                    return configDeviceItemHtml(host, {depth: 1});
                }).join("")}` : ""}
                ${contexts.length ? `<div class="config-tree-section-label">Virtual Systems</div>` : ""}
                ${(() => {
                    const logical = new Map();
                    contexts.forEach(ctx => {
                        const logicalKey = `${safe(ctx.vs_id)}|${safe(ctx.name)}`;
                        if (!logical.has(logicalKey)) logical.set(logicalKey, []);
                        logical.get(logicalKey).push(ctx);
                    });
                    return [...logical.values()].sort((a, b) => safe(a[0]?.name).localeCompare(safe(b[0]?.name))).map(items => {
                        const logicalName = items[0]?.name || `VSID ${items[0]?.vs_id || "—"}`;
                        items.forEach(item => consumed.add(item.id));
                        return `
                            <div class="config-logical-vs">
                                <div class="config-logical-vs-header"><strong>${escapeHtml(logicalName)}</strong><span>${formatNumber(items.length)} member view${items.length === 1 ? "" : "s"}</span></div>
                                ${items.sort((a, b) => safe(a.device_name).localeCompare(safe(b.device_name))).map(item => configDeviceItemHtml(item, {
                                    depth: 2,
                                    displayName: `${item.device_name || "Member"} · VSID ${item.vs_id || "—"}`
                                })).join("")}
                            </div>
                        `;
                    }).join("");
                })()}
            </div>
        `);
    }

    devices.filter(device => !consumed.has(device.id)).forEach(device => {
        const depth = device.entity_type === "clusterxl_member" || device.entity_type === "virtual_system" ? 1 : 0;
        chunks.push(configDeviceItemHtml(device, {depth}));
    });
    return chunks.join("");
}


function renderConfigDeviceList() {
    const list = document.getElementById("configDeviceList");
    if (!list) return;
    const fleet = configScopedFleet();
    const query = safe(document.getElementById("configSearch")?.value).trim().toLowerCase();

    renderConfigFleetFilters();
    if (!configUiData?.available) {
        list.innerHTML = `<div class="empty-list">Configuration evidence is not available in this export.</div>`;
        return;
    }

    const devices = configVendorDevices().filter(device => {
        if (!query) return true;
        return [device.name, device.device_name, device.parent_name, device.cluster_display_name, device.serial, device.management_ip, device.model, device.platform_label, device.entity_type]
            .join(" ")
            .toLowerCase()
            .includes(query);
    });

    const fleetLabel = `${configFleetVendorLabel()} Fleet`;
    const fleetItem = `
        <div class="config-device-item fleet ${configSelectedId === "__fleet__" ? "active" : ""}" data-config-device="__fleet__">
            <div class="config-device-title-row">
                <div>
                    <div class="config-device-name">${escapeHtml(fleetLabel)}</div>
                    <div class="config-device-meta">${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)} current configuration entities</div>
                </div>
                <span class="config-device-count">${formatNumber(fleet.selected)}</span>
            </div>
            <div class="config-device-tags">
                ${Number(fleet.local_override || 0) ? statusPill(`${formatNumber(fleet.local_override)} overrides`, "warning") : ""}
                ${Number(fleet.effective_drift || 0) ? statusPill(`${formatNumber(fleet.effective_drift)} drift`, "danger") : ""}
                ${Number(fleet.checkpoint_capability_gaps || 0) ? statusPill(`${formatNumber(fleet.checkpoint_capability_gaps)} capability gaps`, "muted") : ""}
                ${Number(fleet.method_failures || 0) ? statusPill(`${formatNumber(fleet.method_failures)} ${configFleetFilter === "check_point" ? "operational unavailable" : "method / operational gaps"}`, "danger") : ""}
            </div>
        </div>
    `;

    list.innerHTML = fleetItem + configDeviceHierarchyHtml(devices);
    list.querySelectorAll("[data-config-device]").forEach(item => {
        item.addEventListener("click", () => {
            configSelectedId = item.dataset.configDevice;
            renderConfigDeviceList();
            renderConfigSelected();
            switchConfigTab(configSelectedId === "__fleet__" ? "overview" : "current");
            setConfigSidebarOpen(false);
        });
    });
}


function selectedConfigDevice() {
    return configDevices.find(device => safe(device.id) === safe(configSelectedId)) || null;
}


function panoramaSyncLabel(value) {
    const normalized = safe(value).toLowerCase();
    if (["in sync", "in_sync", "insync", "synchronized", "sync", "yes", "ok"].includes(normalized)) return "IN SYNC";
    if (["out of sync", "out_of_sync", "outofsync", "no"].includes(normalized)) return "OUT OF SYNC";
    return normalized ? normalized.toUpperCase() : "UNKNOWN";
}


function ensureConfigHeaderPreference() {
    if (configHeaderExpanded !== null) return;
    try {
        const saved = localStorage.getItem("securityexpert-config-header-expanded");
        if (saved === "true" || saved === "false") {
            configHeaderExpanded = saved === "true";
            return;
        }
    } catch (error) {
        // Standalone exports can restrict localStorage.
    }
    configHeaderExpanded = !window.matchMedia?.("(max-width: 1100px)")?.matches;
}


function setConfigHeaderExpanded(expanded) {
    configHeaderExpanded = Boolean(expanded);
    try {
        localStorage.setItem("securityexpert-config-header-expanded", String(configHeaderExpanded));
    } catch (error) {
        // Optional preference only.
    }
    renderConfigHeader(selectedConfigDevice());
}


function setConfigSidebarOpen(open) {
    configSidebarOpen = Boolean(open);
    document.querySelector(".config-workspace")?.classList.toggle("sidebar-open", configSidebarOpen);
    const toggle = document.getElementById("configSidebarToggle");
    if (toggle) toggle.setAttribute("aria-expanded", String(configSidebarOpen));
}


function renderConfigHeader(device) {
    ensureConfigHeaderPreference();
    const fleet = configScopedFleet();
    const title = document.getElementById("configDetailTitle");
    const subtitle = document.getElementById("configDetailSubtitle");
    const eyebrow = document.getElementById("configDetailEyebrow");
    const status = document.getElementById("configDetailStatus");
    const facts = document.getElementById("configHeaderFacts");
    const compact = document.getElementById("configHeaderCompact");
    const toggle = document.getElementById("configHeaderToggle");
    const header = document.getElementById("configDetailHeader");

    if (!configUiData?.available) {
        if (title) title.textContent = "Configuration unavailable";
        if (subtitle) subtitle.textContent = "This HTML export does not contain a configuration observation cycle.";
        if (status) status.innerHTML = statusPill("Not collected", "muted");
        if (facts) facts.innerHTML = "";
        if (compact) compact.hidden = true;
        if (toggle) toggle.hidden = true;
        return;
    }

    if (!device) {
        if (eyebrow) eyebrow.textContent = `Configuration · ${configFleetVendorLabel()} estate`;
        if (title) title.textContent = `${configFleetVendorLabel()} Fleet`;
        if (subtitle) subtitle.textContent = `${formatNumber(fleet.selected)} configuration entities · current-state first, alignment separate`;
        if (facts) facts.innerHTML = "";
        if (compact) compact.hidden = true;
        if (toggle) toggle.hidden = true;
        if (header) header.classList.remove("compact");
        if (status) {
            status.innerHTML = [
                statusPill(`${formatNumber(fleet.primary_evidence_success)}/${formatNumber(fleet.selected)} current`, fleet.primary_evidence_success === fleet.selected ? "success" : "warning"),
                Number(fleet.local_override || 0) ? statusPill(`${formatNumber(fleet.local_override)} local overrides`, "warning") : "",
                Number(fleet.effective_drift || 0) ? statusPill(`${formatNumber(fleet.effective_drift)} drift`, "danger") : "",
                Number(fleet.checkpoint_capability_gaps || 0) ? statusPill(`${formatNumber(fleet.checkpoint_capability_gaps)} capability gaps`, "muted") : "",
                configFleetFilter === "check_point" && Number(fleet.checkpoint_operational_failures || 0) ? statusPill(`${formatNumber(fleet.checkpoint_operational_failures)} operational unavailable`, "danger") : "",
            ].join("");
        }
        return;
    }

    if (toggle) {
        toggle.hidden = false;
        toggle.textContent = configHeaderExpanded ? "Collapse details" : "Expand details";
        toggle.setAttribute("aria-expanded", String(configHeaderExpanded));
    }
    if (header) header.classList.toggle("compact", !configHeaderExpanded);
    if (eyebrow) eyebrow.textContent = `Configuration · ${device.vendor || "Managed device"}`;
    if (title) title.textContent = device.name || "Managed device";
    if (subtitle) subtitle.textContent = `Collected ${formatConfigTimestamp(device.completed_at)} · Primary source: ${device.current_configuration?.source_plane || "current evidence"}`;

    const platformSummary = device.vendor_key === "check_point" ? device.platform_label : null;
    if (compact) {
        const compactFacts = [device.vendor, platformSummary && platformSummary !== "Gaia" ? platformSummary : null, device.model, device.sw_version, device.management_ip, device.ha_role]
            .filter(Boolean);
        compact.innerHTML = compactFacts.map(value => `<span>${escapeHtml(value)}</span>`).join("");
        compact.hidden = configHeaderExpanded;
    }
    if (facts) {
        const headerFacts = [
            ["Vendor", device.vendor || "—"],
            ["Model", device.model || "—"],
            ["Software", device.sw_version || "—"],
            ["Management IP", device.management_ip || "—"],
            ["Serial Number", device.serial || "—"],
            ["HA / Role", device.ha_role || "—"],
            ["VSYS / VS", formatNumber(device.vsys_count || 0)],
            [device.policy_scope_label || "Policy scope", device.policy_scope || "—"],
            ["Config freshness", formatConfigTimestamp(device.completed_at)],
            ["Current source", device.current_configuration?.source_plane || "—"]
        ];
        facts.innerHTML = headerFacts.map(([label, value]) => `
            <div class="config-header-fact"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>
        `).join("");
        facts.hidden = !configHeaderExpanded;
    }
    if (status) status.innerHTML = configDeviceStatusHtml(device);
}


function assignmentChips(device) {
    const assignment = device?.assignment || {};
    const stack = assignment.primary_template_stack;
    const deviceGroups = Array.isArray(assignment.device_groups) ? assignment.device_groups : [];
    return `
        <div class="assignment-grid">
            <div class="assignment-block">
                <span class="assignment-label">Template Stack</span>
                <strong>${escapeHtml(stack || "Not mapped")}</strong>
                <small>${escapeHtml(assignment.status || "unknown")}</small>
            </div>
            <div class="assignment-block">
                <span class="assignment-label">Device Group scopes</span>
                <strong>${formatNumber(assignment.policy_scope_count || deviceGroups.length)}</strong>
                <small>${assignment.policy_lineage_complete ? "Lineage complete" : "Lineage incomplete"}</small>
            </div>
            <div class="assignment-block">
                <span class="assignment-label">Template variables unresolved</span>
                <strong>${formatNumber(assignment.unresolved_variable_settings)}</strong>
                <small>${assignment.unresolved_variable_settings ? "Held out of drift claims" : "No unresolved variable settings"}</small>
            </div>
        </div>
    `;
}


function checkpointCoverageHtml(fleet) {
    if (!Number(fleet?.checkpoint_selected || 0) || configFleetFilter === "palo_alto") return "";
    const platforms = fleet.checkpoint_platform_counts || {};
    const gaia = platforms.gaia || {};
    const embedded = platforms.gaia_embedded || {};
    const unknown = platforms.unknown || {};
    const entity = fleet.checkpoint_entity_type_counts || {};
    const reasons = Object.entries(fleet.checkpoint_failure_reason_counts || {}).sort((a, b) => Number(b[1]) - Number(a[1]));
    const reasonRows = reasons.length
        ? reasons.map(([reason, count]) => `<div><span>${escapeHtml(reason.replaceAll("_", " "))}</span><strong>${formatNumber(count)}</strong></div>`).join("")
        : `<div><span>Failure reasons</span><strong>none</strong></div>`;
    const entityLabel = key => ({
        standalone_gateway: "Standalone",
        clusterxl_member: "ClusterXL members",
        vsx_host: "VSX hosts",
        virtual_system: "VSX virtual systems",
    }[key] || key);
    return `
        <section class="config-section-card span-2 checkpoint-coverage-card">
            <div class="section-heading">
                <div><div class="eyebrow">Check Point coverage</div><h2>Platform-aware collection diagnostics</h2></div>
                <div class="section-note">Unsupported capability ≠ transport failure</div>
            </div>
            <div class="config-kpi-grid compact-kpis">
                ${metricCard("Observed / planned", `${formatNumber(fleet.checkpoint_selected)} / ${formatNumber(fleet.checkpoint_planned_entities || fleet.checkpoint_selected)}`, `${formatNumber(fleet.checkpoint_unmaterialized_entities || 0)} planned context(s) not materialized`, fleet.checkpoint_unmaterialized_entities ? "warning" : "success")}
                ${metricCard("Operational failures", formatNumber(fleet.checkpoint_operational_failures), "SSH/auth/identity/command/context failures", fleet.checkpoint_operational_failures ? "danger" : "success")}
                ${metricCard("Capability gaps", formatNumber(fleet.checkpoint_capability_gaps), "Platform command capability held separate", fleet.checkpoint_capability_gaps ? "neutral" : "success")}
                ${metricCard("Mgmt-reported down", formatNumber(fleet.checkpoint_management_reported_down_hosts || 0), "Physical hosts already reported down by management", fleet.checkpoint_management_reported_down_hosts ? "neutral" : "success")}
                ${metricCard("Quantum Spark / Embedded", `${formatNumber(fleet.checkpoint_gaia_embedded_success)} / ${formatNumber(fleet.checkpoint_gaia_embedded_entities)}`, "Current config entities classified from direct evidence", embedded.unavailable ? "warning" : "success")}
                ${metricCard("Model / Serial / HA", `${formatNumber(fleet.checkpoint_model_covered)} / ${formatNumber(fleet.checkpoint_serial_covered)} / ${formatNumber(fleet.checkpoint_ha_role_covered)}`, "Physical metadata coverage counts", "info")}
            </div>
            <div class="checkpoint-coverage-grid">
                <div>
                    <div class="eyebrow">Platform family</div>
                    <div class="summary-list">
                        <div><span>Gaia</span><strong>${formatNumber(gaia.success)} / ${formatNumber(gaia.selected)}</strong></div>
                        <div><span>Quantum Spark / Gaia Embedded</span><strong>${formatNumber(embedded.success)} / ${formatNumber(embedded.selected)}</strong></div>
                        <div><span>Unclassified</span><strong>${formatNumber(unknown.success)} / ${formatNumber(unknown.selected)}</strong></div>
                    </div>
                </div>
                <div>
                    <div class="eyebrow">Entity coverage</div>
                    <div class="summary-list">
                        ${Object.entries(entity).map(([key, row]) => `<div><span>${escapeHtml(entityLabel(key))}</span><strong>${formatNumber(row?.success)} / ${formatNumber(row?.selected)}</strong></div>`).join("")}
                    </div>
                </div>
                <div>
                    <div class="eyebrow">Unavailable reason breakdown</div>
                    <div class="summary-list">${reasonRows}</div>
                </div>
            </div>
        </section>
    `;
}


function renderConfigOverviewPanel(device) {
    const panel = document.getElementById("configOverviewPanel");
    if (!panel) return;
    const fleet = configScopedFleet();

    if (!configUiData?.available) {
        panel.innerHTML = `<div class="empty-state large"><strong>Configuration evidence not available</strong><span>Run <code>py.exe .\\main.py</code> to generate inventory and configuration in one observation cycle.</span></div>`;
        return;
    }

    if (!device) {
        panel.innerHTML = `
            <div class="config-kpi-grid">
                ${metricCard("Current configurations", `${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)}`, "Current primary device configuration", fleet.primary_evidence_success === fleet.selected ? "success" : "warning")}
                ${metricCard("Devices with overrides", formatNumber(fleet.devices_with_local_override), `${formatNumber(fleet.local_override)} settings`, fleet.devices_with_local_override ? "warning" : "success")}
                ${metricCard("Devices with drift", formatNumber(fleet.devices_with_effective_drift), `${formatNumber(fleet.effective_drift)} settings`, fleet.devices_with_effective_drift ? "danger" : "success")}
                ${metricCard("Coverage gaps", formatNumber(fleet.devices_with_coverage_gaps), "Evidence or representation gaps", fleet.devices_with_coverage_gaps ? "neutral" : "success")}
                ${metricCard("Changed this cycle", formatNumber(fleet.changed), "Effective configuration snapshots", fleet.changed ? "warning" : "success")}
                ${metricCard("Native backup", "NOT CONFIGURED", "Target phase 0.6.0B", "muted")}
            </div>

            <div class="config-section-grid">
                <section class="config-section-card span-2">
                    <div class="section-heading"><div><div class="eyebrow">Module contract</div><h2>Configuration means current actual device state</h2></div></div>
                    <div class="contract-grid vendor-neutral-contract">
                        <div>${statusPill("CONFIGURATION", "success")}<p>What is configured on the device now: system, management, DNS/NTP, HA and later broader device/network configuration.</p></div>
                        <div>${statusPill("ALIGNMENT", "warning")}<p>Expected versus current reconciliation is a separate view. Local override and drift findings live there.</p></div>
                        <div>${statusPill("POLICY & OBJECTS", "info")}<p>Security policy, NAT and object analysis are a separate management plane and expand later for PAN, Check Point and VSX.</p></div>
                    </div>
                </section>
                <section class="config-section-card">
                    <div class="section-heading"><div><div class="eyebrow">Vendor coverage</div><h2>Current adapters</h2></div></div>
                    <div class="summary-list">
                        <div><span>Palo Alto Networks</span><strong>${formatNumber(fleet.vendor_counts?.palo_alto?.success || fleet.pan_success)} / ${formatNumber(fleet.vendor_counts?.palo_alto?.selected || fleet.pan_selected)} current</strong></div>
                        <div><span>Check Point Gaia</span><strong>${formatNumber(fleet.vendor_counts?.check_point?.success || fleet.checkpoint_success)} / ${formatNumber(fleet.vendor_counts?.check_point?.selected || fleet.checkpoint_selected)} current</strong></div>
                        <div><span>Check Point SSH trust</span><strong>${escapeHtml(fleet.checkpoint_host_key_policy || "not collected")}</strong></div>
                    </div>
                </section>
            </div>
            ${checkpointCoverageHtml(fleet)}
        `;
        return;
    }

    const counts = configDeviceCounts(device);
    const current = device.current_configuration || {};
    panel.innerHTML = `
        <div class="config-kpi-grid">
            ${metricCard("Current configuration", current.status === "available" ? "AVAILABLE" : "UNAVAILABLE", current.status === "available" ? "Structured from primary effective evidence" : "Primary structured view unavailable", current.status === "available" ? "success" : "danger")}
            ${metricCard("Last collected", formatConfigTimestamp(device.completed_at), "Same observation cycle", "neutral")}
            ${metricCard("Change state", safe(device.history?.effective_change_state || "unknown").toUpperCase(), "Effective configuration snapshot", safe(device.history?.effective_change_state).toUpperCase() === "CHANGED" ? "warning" : "success")}
            ${metricCard("Local overrides", formatNumber(counts.LOCAL_OVERRIDE), "See Alignment for expected ↔ current", counts.LOCAL_OVERRIDE ? "warning" : "success")}
            ${metricCard("Effective drift", formatNumber(counts.EFFECTIVE_DRIFT), "Unexplained effective difference", counts.EFFECTIVE_DRIFT ? "danger" : "success")}
            ${metricCard("Configured VSYS / VS", formatNumber(device.vsys_count || 0), "Current effective configuration", "info")}
        </div>
        <div class="config-section-grid">
            <section class="config-section-card span-2">
                <div class="section-heading"><div><div class="eyebrow">Quick orientation</div><h2>Where to look next</h2></div></div>
                <div class="contract-grid">
                    <div>${statusPill("CURRENT", "success")}<p><strong>Configuration</strong> shows actual values with vendor-neutral LOCAL / MEMBER / override/provenance semantics.</p></div>
                    <div>${statusPill("VERIFY", "warning")}<p><strong>Alignment</strong> explains centralized intent, local override, drift and evidence coverage.</p></div>
                    <div>${statusPill("TRACE", "info")}<p><strong>History</strong> tracks change state. Full time selection and diff remain the next history increment.</p></div>
                </div>
            </section>
        </div>
    `;
}


function currentOriginLabel(origin, vendorKey = "") {
    const value = safe(origin).toLowerCase();
    if (value === "central") return safe(vendorKey) === "check_point" ? "MANAGEMENT" : "PAN";
    if (value === "management") return "MANAGEMENT";
    if (value === "local_override") return "OVERRIDE";
    if (value === "local") return "LOCAL";
    if (value === "member_specific") return "MEMBER";
    if (value === "effective") return "EFFECTIVE";
    return "UNKNOWN";
}


function currentOriginTone(origin) {
    const value = safe(origin).toLowerCase();
    if (value === "central") return "success";
    if (value === "local_override") return "warning";
    if (value === "member_specific") return "info";
    if (value === "local") return "neutral";
    return "muted";
}


function renderCurrentHighlights(current, device) {
    const highlights = Array.isArray(current?.highlights) ? current.highlights : [];
    if (!highlights.length) return "";
    return `
        <section class="basic-config-block">
            <div class="section-heading basic-config-heading">
                <div><div class="eyebrow">Basic configuration</div><h3>Operator snapshot</h3></div>
                <div class="section-note">Selected current values</div>
            </div>
            <div class="current-config-highlights">
                ${highlights.map(item => `
                    <div class="config-highlight-card">
                        <div class="config-highlight-topline">
                            <span>${escapeHtml(item.section_label || "Configuration")}</span>
                            ${statusPill(currentOriginLabel(item.origin, device?.vendor_key), currentOriginTone(item.origin))}
                        </div>
                        <strong>${escapeHtml(item.label || "Setting")}</strong>
                        <code>${escapeHtml(item.value ?? "—")}</code>
                        ${item.context ? `<small>${escapeHtml(item.context)}</small>` : ""}
                    </div>
                `).join("")}
            </div>
        </section>
    `;
}


function filteredCurrentSections(device) {
    const sections = Array.isArray(device?.current_configuration?.sections)
        ? device.current_configuration.sections
        : [];
    const query = safe(document.getElementById("configCurrentSearch")?.value).trim().toLowerCase();
    if (!query) return sections;
    return sections.map(section => ({
        ...section,
        settings: (section.settings || []).filter(row => [
            row.setting, row.value, row.context, row.origin, row.alignment_classification
        ].join(" ").toLowerCase().includes(query))
    })).filter(section => section.settings.length);
}


function renderConfigCurrentPanel(device) {
    const body = document.getElementById("configCurrentBody");
    if (!body) return;
    if (!configUiData?.available) {
        body.innerHTML = `<div class="empty-state large"><span>Current configuration is not available.</span></div>`;
        return;
    }
    if (!device) {
        body.innerHTML = `<div class="empty-state large"><strong>Select a device</strong><span>Configuration is a per-device current-state view. Fleet posture stays in Overview; expected-versus-current findings stay in Alignment.</span></div>`;
        return;
    }

    const current = device.current_configuration || {};
    if (current.status !== "available") {
        body.innerHTML = `<div class="empty-state large"><strong>Structured current configuration unavailable</strong><span>${escapeHtml(current.reason || "Primary effective configuration could not be projected.")}</span></div>`;
        return;
    }

    const sections = filteredCurrentSections(device);
    body.innerHTML = `
        <div class="current-config-intro">
            <div>
                ${statusPill("CURRENT ACTUAL", "success")}
                <strong>${formatNumber(current.setting_count)} projected settings</strong>
                <span>Source plane: ${escapeHtml(current.source_plane || "effective-running")}</span>
            </div>
            <div class="current-config-legend">
                ${device.vendor_key === "palo_alto" ? statusPill("PAN", "success") : ""}
                ${statusPill("LOCAL", "neutral")}
                ${device.vendor_key === "palo_alto" ? statusPill("OVERRIDE", "warning") : ""}
                ${statusPill("MEMBER", "info")}
            </div>
        </div>
        <div class="inline-message neutral">This local operator view shows selected non-secret current values from ${escapeHtml(current.source_plane || "current evidence")}. Raw configuration blobs and secret-bearing settings are not embedded in the HTML. ${formatNumber(current.redacted_secret_setting_count || 0)} secret-bearing setting(s) were withheld. ${device.vendor_key === "check_point" ? "Check Point expected-versus-actual alignment is intentionally deferred; ClusterXL member-specific differences shown here are intra-cluster current-state semantics." : "Alignment details remain a separate tab."}</div>
        ${renderCurrentHighlights(current, device)}
        <div class="current-config-sections">
            ${sections.length ? sections.map(section => `
                <section class="current-config-section">
                    <div class="section-heading">
                        <div><div class="eyebrow">Current configuration</div><h3>${escapeHtml(section.label)}</h3></div>
                        <div class="section-note">${formatNumber(section.settings?.length || 0)} visible</div>
                    </div>
                    <div class="current-config-table-wrap">
                        <table class="current-config-table">
                            <thead><tr><th>Setting</th><th>Current value</th><th>Origin</th><th>Context</th></tr></thead>
                            <tbody>
                                ${(section.settings || []).map(row => `
                                    <tr>
                                        <td><strong>${escapeHtml(row.setting || "Setting")}</strong>${row.member_specific ? `<small>Expected member-specific value</small>` : ""}</td>
                                        <td><code class="current-config-value">${escapeHtml(row.value ?? "—")}</code></td>
                                        <td>${statusPill(currentOriginLabel(row.origin, device?.vendor_key), currentOriginTone(row.origin))}</td>
                                        <td>${escapeHtml(row.context || "—")}</td>
                                    </tr>
                                `).join("")}
                            </tbody>
                        </table>
                    </div>
                </section>
            `).join("") : `<div class="empty-state compact"><span>No current settings match this filter.</span></div>`}
        </div>
        <section class="config-section-card native-config-backlog">
            <div class="section-heading"><div><div class="eyebrow">Native view</div><h3>Vendor-native configuration</h3></div><div class="section-note">Backlog</div></div>
            <p>A secret-aware native view (PAN effective XML, Check Point <code>show configuration</code>, VSX host/VS context) is part of the Configuration contract but is intentionally deferred until authorization/redaction behavior is hardened.</p>
        </section>
    `;
}


function renderCategoryMatrix(rawCategoryCounts) {
    const rows = Object.entries(rawCategoryCounts || {}).map(([category, counts]) => ({
        category,
        label: categoryLabel(category),
        counts: counts || {}
    }));
    return renderCategoryRows(rows);
}


function renderCategoryRows(rows) {
    if (!Array.isArray(rows) || !rows.length) {
        return `<div class="empty-state compact"><span>No category telemetry available.</span></div>`;
    }
    return `
        <div class="category-table-wrap">
            <table class="category-table">
                <thead><tr><th>Category</th><th>Aligned</th><th>Override</th><th>Drift</th><th>Member diff</th><th>Provenance gap</th><th>Expected only</th><th>Local only</th><th>Unknown</th></tr></thead>
                <tbody>
                ${rows.map(row => {
                    const counts = row.counts || {};
                    return `<tr>
                        <td><strong>${escapeHtml(row.label || categoryLabel(row.category))}</strong></td>
                        <td class="count-success">${formatNumber(counts.ALIGNED)}</td>
                        <td class="count-warning">${formatNumber(counts.LOCAL_OVERRIDE)}</td>
                        <td class="count-danger">${formatNumber(counts.EFFECTIVE_DRIFT)}</td>
                        <td class="count-info">${formatNumber(counts.MEMBER_SPECIFIC)}</td>
                        <td>${formatNumber(counts.PROVENANCE_UNVERIFIED)}</td>
                        <td>${formatNumber(counts.EXPECTED_ONLY)}</td>
                        <td>${formatNumber(counts.LOCAL_ONLY)}</td>
                        <td>${formatNumber(counts.UNKNOWN)}</td>
                    </tr>`;
                }).join("")}
                </tbody>
            </table>
        </div>
    `;
}


function humanSettingLabel(path) {
    const text = safe(path);
    if (!text) return "Setting path unavailable";
    const cleaned = text
        .replace("/config/devices/entry[@name='__DEVICE__']/", "")
        .replaceAll("/entry[@name='", " / ")
        .replaceAll("']", "")
        .replaceAll("/", " › ")
        .replaceAll("-", " ");
    const parts = cleaned.split(" › ").filter(Boolean);
    const tail = parts.slice(-6).map(part => part.trim()).filter(Boolean);
    return tail.join(" › ");
}


function findingTone(classification) {
    return statusTone(classification);
}


function allConfigFindings(device) {
    if (device) return Array.isArray(device.alignment?.findings) ? device.alignment.findings : [];
    return configDevices.flatMap(item => (item.alignment?.findings || []).map(finding => ({
        ...finding,
        device_name: item.name,
        device_id: item.id
    })));
}


function filteredConfigFindings(device) {
    const classification = safe(document.getElementById("configClassificationFilter")?.value);
    const query = safe(document.getElementById("configAlignmentSearch")?.value).trim().toLowerCase();
    return allConfigFindings(device).filter(finding => {
        if (classification && finding.classification !== classification) return false;
        if (!query) return true;
        return [
            finding.setting,
            finding.expected_source_name,
            finding.expected_source_kind,
            finding.category,
            finding.classification,
            finding.device_name
        ].join(" ").toLowerCase().includes(query);
    });
}


function renderFindingsTable(findings, fleetMode) {
    if (!findings.length) {
        return `<div class="empty-state compact"><strong>No operator-facing findings match this filter.</strong><span>Coverage-only EXPECTED_ONLY/LOCAL_ONLY rows stay aggregated to avoid flooding the console.</span></div>`;
    }
    return `
        <div class="findings-list">
            ${findings.map(finding => `
                <article class="finding-card ${escapeHtml(findingTone(finding.classification))}">
                    <div class="finding-status-column">
                        ${statusPill(finding.classification_label || classificationLabel(finding.classification), findingTone(finding.classification))}
                        <span class="finding-confidence">${escapeHtml(finding.confidence ? `Confidence: ${finding.confidence}` : "")}</span>
                    </div>
                    <div class="finding-main">
                        <div class="finding-title">${escapeHtml(humanSettingLabel(finding.setting))}</div>
                        <div class="finding-meta">
                            ${fleetMode && finding.device_name ? `<span>Device: <strong>${escapeHtml(finding.device_name)}</strong></span>` : ""}
                            <span>Category: <strong>${escapeHtml(finding.category_label || categoryLabel(finding.category))}</strong></span>
                            ${finding.expected_source_name ? `<span>Expected source: <strong>${escapeHtml(finding.expected_source_name)}</strong></span>` : ""}
                            ${finding.expected_source_kind ? `<span>Source type: <strong>${escapeHtml(finding.expected_source_kind)}</strong></span>` : ""}
                        </div>
                        <div class="finding-reason">${escapeHtml(finding.reason || "Evidence classified by the semantic alignment engine.")}</div>
                        ${finding.setting ? `<code class="setting-path" title="${escapeHtml(finding.setting)}">${escapeHtml(finding.setting)}</code>` : ""}
                    </div>
                </article>
            `).join("")}
        </div>
    `;
}


function renderConfigAlignmentPanel(device) {
    const body = document.getElementById("configAlignmentBody");
    if (!body) return;
    if (!configUiData?.available) {
        body.innerHTML = `<div class="empty-state large"><span>Configuration alignment is not available.</span></div>`;
        return;
    }
    if (device?.vendor_key === "check_point") {
        const alignment = device.alignment || {};
        const counts = alignment.counts || {};
        const findings = filteredConfigFindings(device);
        const state = alignment.device_status || "INSUFFICIENT_EVIDENCE";
        body.innerHTML = `
            <section class="config-section-card">
                <div class="section-heading">
                    <div><div class="eyebrow">Check Point alignment</div><h3>Management intent ↔ direct actual</h3></div>
                    <div class="section-note">${statusPill(classificationLabel(state), statusTone(state))}</div>
                </div>
                <div class="inline-message ${alignment.available ? "info" : "neutral"}">${escapeHtml(alignment.message || "Alignment evidence is unavailable.")}</div>
                ${alignment.peer_evidence_incomplete ? `<div class="inline-message neutral">Peer evidence is incomplete. The available member remains independently evaluated; member agreement is not inferred.</div>` : ""}
                <div class="config-metric-grid compact">
                    ${metricCard("Aligned", formatNumber(counts.ALIGNED), "Trusted comparable matches", "success")}
                    ${metricCard("Difference observed", formatNumber(counts.DIFFERENCE_OBSERVED), "Not an EFFECTIVE_DRIFT claim", "warning")}
                    ${metricCard("Member-specific", formatNumber(counts.MEMBER_SPECIFIC), "Semantic exclusion", "info")}
                    ${metricCard("Coverage gaps", formatNumber(Number(counts.EXPECTED_ONLY || 0) + Number(counts.ACTUAL_ONLY || 0) + Number(counts.UNKNOWN || 0)), "Expected/actual/identity gaps", "neutral")}
                </div>
            </section>
            <section class="config-section-card alignment-findings-section">
                <div class="section-heading">
                    <div><div class="eyebrow">Bounded semantic evidence</div><h3>Differences & exclusions</h3></div>
                    <div class="section-note">${formatNumber(findings.length)} visible rows</div>
                </div>
                ${renderFindingsTable(findings, false)}
            </section>`;
        return;
    }
    const fleet = configScopedFleet();
    const categorySection = device
        ? renderCategoryRows(device.alignment?.categories || [])
        : renderCategoryMatrix(fleet.category_counts || {});
    const findings = filteredConfigFindings(device);
    body.innerHTML = `
        ${device ? `
        <section class="config-section-card alignment-source-section">
            <div class="section-heading">
                <div><div class="eyebrow">Central intent</div><h3>PAN management assignment</h3></div>
                <div class="section-note">Alignment-only context</div>
            </div>
            ${assignmentChips(device)}
        </section>` : ""}
        <section class="config-section-card alignment-category-section">
            <div class="section-heading">
                <div><div class="eyebrow">Coverage by domain</div><h3>Semantic categories</h3></div>
                <div class="section-note">EXPECTED_ONLY and LOCAL_ONLY are coverage states, not drift.</div>
            </div>
            ${categorySection}
        </section>
        <section class="config-section-card alignment-findings-section">
            <div class="section-heading">
                <div><div class="eyebrow">Operator-facing evidence</div><h3>Findings & semantic exclusions</h3></div>
                <div class="section-note">${formatNumber(findings.length)} visible rows</div>
            </div>
            ${renderFindingsTable(findings, !device)}
        </section>
    `;
}


function renderConfigPolicyPanel(device) {
    const panel = document.getElementById("configPolicyPanel");
    if (!panel) return;
    const scope = device?.policy_scope;
    panel.innerHTML = `
        <div class="policy-placeholder">
            <div class="eyebrow">Separate management plane</div>
            <h2>Policy &amp; Objects</h2>
            <p>Security policy, NAT, objects, groups and future policy analysis are intentionally separate from device configuration.</p>
            <div class="contract-grid">
                <div>${statusPill("PAN", "info")}<p>Panorama Device Group / Shared policy and object lineage.</p></div>
                <div>${statusPill("CHECK POINT", "info")}<p>Management API policy packages, rules, objects and install targets.</p></div>
                <div>${statusPill("FUTURE", "muted")}<p>Policy analysis, bulk operations and controlled write-plane workflows.</p></div>
            </div>
            ${device ? `<div class="policy-device-context">Current management scope: <strong>${escapeHtml(scope || "Not resolved")}</strong></div>` : ""}
        </div>
    `;
}


function evidenceCard(label, artifact, preferred = false) {
    artifact = artifact || {};
    const status = safe(artifact.status || "unavailable");
    return `
        <article class="evidence-card ${preferred ? "primary" : ""}">
            <div class="evidence-card-header">
                <div>
                    <div class="eyebrow">${preferred ? "Primary evidence" : "Supporting evidence"}</div>
                    <h3>${escapeHtml(label)}</h3>
                </div>
                ${statusPill(status.toUpperCase(), status === "success" ? "success" : "danger")}
            </div>
            <p>${escapeHtml(artifact.role || "")}</p>
            <dl class="evidence-definition-list">
                <div><dt>Method</dt><dd>${escapeHtml(artifact.method || "—")}</dd></div>
                <div><dt>Transport</dt><dd>${escapeHtml(artifact.transport || "—")}</dd></div>
                <div><dt>Change state</dt><dd>${escapeHtml((artifact.change_state || "—").toUpperCase())}</dd></div>
                <div><dt>Artifact size</dt><dd>${escapeHtml(formatBytes(artifact.size_bytes))}</dd></div>
                <div><dt>Schema</dt><dd>${escapeHtml(artifact.schema_status || "—")}</dd></div>
            </dl>
        </article>
    `;
}


function renderConfigEvidencePanel(device) {
    const panel = document.getElementById("configEvidencePanel");
    if (!panel) return;
    const fleet = configScopedFleet();
    if (!configUiData?.available) {
        panel.innerHTML = `<div class="empty-state large"><span>Evidence is not available.</span></div>`;
        return;
    }
    if (!device) {
        panel.innerHTML = `
            <div class="config-kpi-grid evidence-kpis">
                ${metricCard("Primary evidence", `${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)}`, "PAN effective-running + Check Point Gaia actual", "success")}
                ${metricCard("PAN alignment", `${formatNumber(fleet.alignment_supported_success || fleet.alignment_evidence_complete)} / ${formatNumber(fleet.alignment_supported_selected || fleet.pan_selected)}`, "Expected ↔ actual currently implemented for PAN", "success")}
                ${metricCard("Method failures", formatNumber(fleet.method_failures), "Collection method diagnostics", fleet.method_failures ? "danger" : "success")}
                ${metricCard("PAN TLS verification", fleet.tls_verify ? "ENABLED" : "DISABLED", fleet.ca_bundle_configured ? "CA bundle configured" : "CA bundle not configured", fleet.tls_verify ? "success" : "warning")}
                ${fleet.checkpoint_selected ? metricCard("CP SSH trust", fleet.checkpoint_production_trust_ready ? "STRICT" : "COMPATIBILITY", fleet.checkpoint_host_key_policy || "not collected", fleet.checkpoint_production_trust_ready ? "success" : "warning") : ""}
            </div>
            <section class="config-section-card">
                <div class="section-heading"><div><div class="eyebrow">Evidence model</div><h2>Read-only collection plane</h2></div></div>
                <div class="evidence-model-grid">
                    <div><strong>PAN</strong><span>Panorama intent + direct firewall effective-running with semantic alignment.</span></div>
                    <div class="flow-arrow">+</div>
                    <div><strong>Check Point</strong><span>Management-selected endpoint + interactive SSH capability handshake; direct Clish or Expert→explicit-Clish actual evidence, with VSX context preserved.</span></div>
                    <div class="flow-arrow">→</div>
                    <div><strong>Configuration</strong><span>Vendor-neutral current-state UI; alignment remains vendor-specific.</span></div>
                </div>
                ${fleet.tls_verify ? "" : `<div class="inline-message warning">PAN configuration collection currently runs without TLS certificate verification. Treat this as production hardening debt before rollout.</div>`}
                ${fleet.checkpoint_selected && !fleet.checkpoint_production_trust_ready ? `<div class="inline-message warning">Check Point configuration SSH host-key trust is still compatibility mode. Enable trusted known_hosts/pinned host keys before production rollout.</div>` : ""}
            </section>
        `;
        return;
    }
    if (device.vendor_key === "check_point") {
        panel.innerHTML = `
            <div class="evidence-card-grid">
                ${evidenceCard("Gaia current configuration", device.evidence?.actual, true)}
            </div>
            <section class="config-section-card">
                <div class="section-heading"><div><div class="eyebrow">Evidence contract</div><h2>Check Point actual configuration</h2></div></div>
                <div class="contract-grid">
                    <div>${statusPill("PRIMARY", "success")}<p>Direct SSH to the management-selected gateway endpoint using a PTY-backed interactive session; capability evidence selects direct Clish or Expert→explicit-Clish <code>show configuration</code>.</p></div>
                    <div>${statusPill("SECRET-AWARE", "info")}<p>Secret-bearing lines are withheld before CAS/history and browser projection. Raw Gaia configuration is never persisted by this collector.</p></div>
                    <div>${statusPill("READ ONLY", "muted")}<p>VSX contexts use the validated numeric <code>vsenv</code> context before Clish. No set/save/install operation is performed.</p></div>
                </div>
                ${device.host_key_policy === "strict_known_hosts" ? "" : `<div class="inline-message warning">SSH host-key verification is still in compatibility mode for this POC. Production deployment must enable trusted known_hosts or pinned fingerprints.</div>`}
            </section>`;
        return;
    }
    panel.innerHTML = `
        <div class="evidence-card-grid">
            ${evidenceCard("Effective-running", device.evidence?.effective, true)}
            ${evidenceCard("Merged configuration", device.evidence?.merged, false)}
            ${evidenceCard("Local active configuration", device.evidence?.active, false)}
            ${evidenceCard("Panorama control-plane view", device.evidence?.panorama_control, false)}
        </div>
        <section class="config-section-card">
            <div class="section-heading"><div><div class="eyebrow">Evidence contract</div><h2>What SecurityExpert trusts</h2></div></div>
            <div class="contract-grid">
                <div>${statusPill("PRIMARY", "success")}<p>Effective-running is the authoritative actual configuration plane for compliance and alignment.</p></div>
                <div>${statusPill("PROVENANCE", "info")}<p>Merged and local-active explain source and local override behavior; they do not replace effective-running.</p></div>
                <div>${statusPill("READ ONLY", "muted")}<p>This phase creates local evidence only. No push, commit, save, or firewall configuration change is performed.</p></div>
            </div>
        </section>
    `;
}


function changeStatePill(value) {
    const normalized = safe(value || "unknown").toUpperCase();
    const tone = normalized === "CHANGED" ? "warning" : normalized === "SAME" ? "success" : "muted";
    return statusPill(normalized, tone);
}


// Phase 0.6.3 — History timeline + safe normalized diff helpers

function historyChangePill(state) {
    const s = safe(state || "unknown").toUpperCase();
    const tone = s === "CHANGED" ? "warning" : s === "SAME" ? "success" : s === "FIRST" ? "info" : "muted";
    return statusPill(s, tone);
}

function diffChangeBadge(change) {
    const labels = { added: "Added", removed: "Removed", modified: "Changed" };
    const tones  = { added: "success", removed: "danger", modified: "warning" };
    const label  = labels[change] || escapeHtml(change);
    const tone   = tones[change] || "muted";
    return `<span class="status-pill ${escapeHtml(tone)}">${label}</span>`;
}

function renderHistoryTimeline(art) {
    if (!art || !Array.isArray(art.events) || art.events.length === 0) {
        return `<div class="inline-message neutral">No history snapshots recorded for ${escapeHtml(art?.artifact_label || "this artifact")} yet.</div>`;
    }
    const rows = art.events.map(ev => `
        <div class="history-timeline-row">
            <span class="history-timeline-ts">${escapeHtml(formatConfigTimestamp(ev.collected_at))}</span>
            ${historyChangePill(ev.change_state)}
        </div>`).join("");
    const trunc = art.truncated ? `<div class="inline-message neutral">Timeline shows the most recent ${art.events.length} events.</div>` : "";
    return `<div class="history-timeline">${rows}</div>${trunc}`;
}

function renderDiffSummary(pair) {
    if (!pair) return "";
    if (pair.status === "insufficient_evidence") {
        const reason = pair.reason || "diff_not_supported";
        const readable = {
            "cp_raw_text_diff_not_supported_in_0_6_3": "Structured configuration diff for Check Point is not supported in this release. Timeline change signals are available.",
            "previous_snapshot_metadata_not_readable": "The earlier snapshot could not be read; diff is unavailable.",
            "historical_object_not_readable": "A comparison snapshot object could not be resolved; diff is unavailable.",
        }[reason] || "Configuration diff is not available for this snapshot pair.";
        return `<div class="inline-message neutral">${escapeHtml(readable)}</div>`;
    }
    if (pair.status === "unavailable") {
        return `<div class="inline-message neutral">Configuration diff could not be computed for this snapshot pair.</div>`;
    }
    if (!Array.isArray(pair.diff_rows) || pair.diff_rows.length === 0) {
        return `<div class="inline-message success">No allowlisted configuration changes detected between these snapshots.</div>`;
    }

    const sectionOrder = ["system","dns","ntp","management","high_availability","network_summary"];
    const bySection = {};
    for (const row of pair.diff_rows) {
        const s = row.section || "other";
        if (!bySection[s]) bySection[s] = [];
        bySection[s].push(row);
    }
    const sectionLabels = {
        system: "System", dns: "DNS", ntp: "NTP", management: "Management",
        high_availability: "High Availability", network_summary: "Network Summary"
    };
    let html = `<div class="diff-summary-grid">`;
    for (const section of [...sectionOrder, ...Object.keys(bySection).filter(s => !sectionOrder.includes(s))]) {
        const rows = bySection[section];
        if (!rows || rows.length === 0) continue;
        html += `<div class="diff-section">
            <div class="diff-section-label">${escapeHtml(sectionLabels[section] || section)}</div>
            <table class="diff-table"><tbody>`;
        for (const row of rows) {
            html += `<tr class="diff-row diff-${escapeHtml(row.change || "modified")}">
                <td>${diffChangeBadge(row.change)}</td>
                <td class="diff-setting">${escapeHtml(row.setting || "")}</td>
                <td class="diff-before">${row.before != null ? escapeHtml(row.before) : "<em>—</em>"}</td>
                <td class="diff-after">${row.after  != null ? escapeHtml(row.after)  : "<em>—</em>"}</td>
            </tr>`;
        }
        html += `</tbody></table></div>`;
    }
    html += `</div>`;
    if (pair.truncated) {
        html += `<div class="inline-message neutral">Showing first ${pair.diff_rows.length} changes; additional changes exist.</div>`;
    }
    return html;
}

function renderConfigHistoryPanel(device) {
    const panel = document.getElementById("configHistoryPanel");
    if (!panel) return;
    const fleet = configScopedFleet();
    if (!configUiData?.available) {
        panel.innerHTML = `<div class="empty-state large"><span>Configuration history is not available.</span></div>`;
        return;
    }
    if (!device) {
        panel.innerHTML = `
            <div class="config-kpi-grid history-kpis">
                ${metricCard("First snapshot", formatNumber(fleet.first), "New history baseline", "info")}
                ${metricCard("Same", formatNumber(fleet.same), "No configuration content change", "success")}
                ${metricCard("Changed", formatNumber(fleet.changed), "New content hash observed", fleet.changed ? "warning" : "success")}
            </div>
            <section class="config-section-card">
                <div class="section-heading"><div><div class="eyebrow">Current cycle</div><h2>Effective snapshot state</h2></div></div>
                <div class="history-device-grid">
                    ${configDevices.map(item => `<div class="history-device-row"><strong>${escapeHtml(item.name || "")}</strong>${changeStatePill(item.history?.effective_change_state)}</div>`).join("")}
                </div>
            </section>
        `;
        return;
    }

    // 0.6.3: use history_v1 when available; fall back to legacy change-state display.
    const hv1 = device.history_v1;

    if (hv1 && hv1.status !== "unavailable" && Array.isArray(hv1.artifacts) && hv1.artifacts.length > 0) {
        const art = hv1.artifacts[0];
        const pair = Array.isArray(hv1.pair_results) && hv1.pair_results.length > 0 ? hv1.pair_results[0] : null;
        const headerNote = art.event_count > 0
            ? `${art.event_count} snapshot${art.event_count > 1 ? "s" : ""} recorded`
            : "No snapshots yet";
        panel.innerHTML = `
            <section class="config-section-card">
                <div class="section-heading">
                    <div><div class="eyebrow">History · ${escapeHtml(art.artifact_label || "")}</div><h2>Snapshot timeline</h2></div>
                    <div class="section-note">${escapeHtml(headerNote)}</div>
                </div>
                ${renderHistoryTimeline(art)}
            </section>
            ${pair ? `<section class="config-section-card">
                <div class="section-heading">
                    <div><div class="eyebrow">Latest change</div><h2>Configuration change summary</h2></div>
                    <div class="section-note">${escapeHtml(formatConfigTimestamp(pair.newer_collected_at))} vs ${escapeHtml(formatConfigTimestamp(pair.older_collected_at))}</div>
                </div>
                ${renderDiffSummary(pair)}
                <div class="inline-message neutral">This view shows safe allowlisted configuration fields only. Secret-bearing settings, raw configuration and value hashes are not embedded.</div>
            </section>` : ""}
        `;
        return;
    }

    // Legacy fallback: current-run change-state signals only.
    if (device.vendor_key === "check_point") {
        panel.innerHTML = `
            <section class="config-section-card">
                <div class="section-heading"><div><div class="eyebrow">Current collection</div><h2>Snapshot history signal</h2></div><div class="section-note">Collected ${escapeHtml(formatConfigTimestamp(device.completed_at))}</div></div>
                <div class="history-artifact-grid">
                    <div><span>Gaia redacted actual</span>${changeStatePill(device.history?.actual_change_state)}</div>
                </div>
                <div class="inline-message neutral">The content-addressed snapshot contains only secret-aware redacted Gaia configuration. A full raw canonical fingerprint participates in change detection without persisting the raw configuration.</div>
            </section>`;
        return;
    }
    panel.innerHTML = `
        <section class="config-section-card">
            <div class="section-heading"><div><div class="eyebrow">Current collection</div><h2>Snapshot history signals</h2></div><div class="section-note">Collected ${escapeHtml(formatConfigTimestamp(device.completed_at))}</div></div>
            <div class="history-artifact-grid">
                <div><span>Local active</span>${changeStatePill(device.history?.active_change_state)}</div>
                <div><span>Merged</span>${changeStatePill(device.history?.merged_change_state)}</div>
                <div><span>Effective-running</span>${changeStatePill(device.history?.effective_change_state)}</div>
            </div>
            <div class="inline-message neutral">Immutable snapshots and change-state detection are active. Full timeline and safe configuration diff are available when a device is selected and local history is attached.</div>
        </section>
    `;
}


function renderConfigBackupPanel(device) {
    const panel = document.getElementById("configBackupPanel");
    if (!panel) return;
    panel.innerHTML = `
        <div class="backup-placeholder">
            <div class="backup-placeholder-icon" aria-hidden="true">↻</div>
            <div class="eyebrow">Phase ${escapeHtml(configUiData?.backup?.phase || "0.6.0B")}</div>
            <h2>${device?.vendor_key === "check_point" ? "Check Point Native Recovery Backup" : "PAN Native Device-State Backup"}</h2>
            <p>${device?.vendor_key === "check_point" ? "Check Point vendor-native recovery/backup is not configured in 0.6.1B.1. Current Gaia configuration evidence is analysis/history evidence, not a recovery artifact." : escapeHtml(configUiData?.backup?.message || "Native recovery artifacts are not configured yet.")}</p>
            <div class="backup-capability-grid">
                <div><strong>Native recovery artifact</strong><span>Vendor-native device-state export, separate from diffable config evidence.</span></div>
                <div><strong>Integrity</strong><span>SHA-256, size/content validation and immutable history.</span></div>
                <div><strong>Security</strong><span>Local sensitive storage, RBAC and retention controls before production rollout.</span></div>
            </div>
            ${device ? `<div class="backup-device-context">Target device: <strong>${escapeHtml(device.name || "")}</strong></div>` : ""}
        </div>
    `;
}


function renderConfigSelected() {
    const device = configSelectedId === "__fleet__" ? null : selectedConfigDevice();
    if (configSelectedId !== "__fleet__" && !device) configSelectedId = "__fleet__";
    renderConfigHeader(device);
    renderConfigOverviewPanel(device);
    renderConfigCurrentPanel(device);
    renderConfigAlignmentPanel(device);
    renderConfigPolicyPanel(device);
    renderConfigEvidencePanel(device);
    renderConfigHistoryPanel(device);
    renderConfigBackupPanel(device);

    const fleet = configScopedFleet();
    const topStats = document.getElementById("configTopStats");
    if (topStats) {
        topStats.textContent = configUiData?.available
            ? `${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)} current | ${formatNumber(fleet.devices_with_local_override)} devices with overrides | ${formatNumber(fleet.devices_with_effective_drift)} devices with drift`
            : "Configuration not available";
    }
}


function switchConfigTab(nextTab) {
    activeConfigTab = ["overview", "current", "alignment", "policy", "history", "evidence", "backup"].includes(nextTab)
        ? nextTab
        : "overview";
    document.querySelectorAll(".config-tab").forEach(tab => {
        tab.classList.toggle("active", tab.dataset.configTab === activeConfigTab);
    });
    document.querySelectorAll(".config-panel").forEach(panel => panel.classList.remove("active"));
    const selected = document.getElementById(
        activeConfigTab === "overview" ? "configOverviewPanel" :
        activeConfigTab === "current" ? "configCurrentPanel" :
        activeConfigTab === "alignment" ? "configAlignmentPanel" :
        activeConfigTab === "policy" ? "configPolicyPanel" :
        activeConfigTab === "evidence" ? "configEvidencePanel" :
        activeConfigTab === "history" ? "configHistoryPanel" :
        "configBackupPanel"
    );
    selected?.classList.add("active");
    if (activeConfigTab === "current") renderConfigCurrentPanel(selectedConfigDevice());
    if (activeConfigTab === "alignment") renderConfigAlignmentPanel(selectedConfigDevice());
    if (activeConfigTab === "policy") renderConfigPolicyPanel(selectedConfigDevice());
}


function complianceStatusTone(status) {
    const value = safe(status).toUpperCase();
    if (value === "PASS") return "success";
    if (value === "FINDING") return "danger";
    if (value === "UNKNOWN") return "muted";
    if (value === "NOT_APPLICABLE") return "info";
    if (value === "PLANNED") return "warning";
    return "neutral";
}


function complianceStatusMeaning(status) {
    const value = safe(status).toUpperCase();
    if (value === "PASS") return "Observed evidence supports this control area.";
    if (value === "FINDING") return "Observed evidence indicates a gap or risk signal.";
    if (value === "UNKNOWN") return "Evidence is missing or insufficient for a conclusion.";
    if (value === "NOT_APPLICABLE") return "This control does not apply for the selected vendor context.";
    if (value === "PLANNED") return "This control area is intentionally roadmap-planned.";
    return "Status meaning unavailable.";
}


function renderComplianceLegend() {
    const host = document.getElementById("complianceLegend");
    if (!host) return;
    const statuses = ["PASS", "FINDING", "UNKNOWN", "PLANNED"];
    host.innerHTML = `
        <div class="compliance-legend-title">How to read statuses</div>
        <div class="compliance-legend-grid">
            ${statuses.map(status => `
                <article class="compliance-legend-item">
                    <div class="compliance-legend-pill">${statusPill(status, complianceStatusTone(status))}</div>
                    <p>${escapeHtml(complianceStatusMeaning(status))}</p>
                </article>
            `).join("")}
        </div>
    `;
}


function compliancePayload() {
    return complianceUiData?.available ? (complianceUiData || {}) : {};
}


function complianceVendorLabel(vendorKey) {
    return safe(vendorKey) === "check_point" ? "Check Point" : "Palo Alto";
}


function complianceSubjectOrdinal(subject) {
    const subjectId = safe(subject?.subject_id);
    const parts = subjectId.split("-");
    const numeric = Number(parts[1] || "0");
    return Number.isFinite(numeric) && numeric > 0 ? numeric : 0;
}


function complianceSourceDevice(subject) {
    const sourceIndex = Number(subject?.source_config_index);
    if (Number.isInteger(sourceIndex) && sourceIndex >= 0 && configDevices[sourceIndex]) {
        return configDevices[sourceIndex];
    }
    const vendorKey = safe(subject?.vendor_key);
    const ordinal = complianceSubjectOrdinal(subject);
    if (!ordinal) return null;
    const pool = configDevices.filter(device => safe(device.vendor_key) === vendorKey);
    return pool[ordinal - 1] || null;
}


function complianceVendorCode(subject) {
    const source = complianceSourceDevice(subject);
    const entityType = safe(source?.entity_type);
    if (entityType === "vsx_host" || entityType === "virtual_system") return "VSX";
    return safe(subject?.vendor_key) === "check_point" ? "CP" : "PAN";
}


function complianceSourceName(subject) {
    const source = complianceSourceDevice(subject);
    if (!source) return "";
    return safe(source.display_name || source.name || source.device_name || source.device || "");
}


function complianceRenderableControls(controls, includeNotApplicable = false) {
    const rows = Array.isArray(controls) ? controls : [];
    return includeNotApplicable
        ? rows
        : rows.filter(control => safe(control?.status).toUpperCase() !== "NOT_APPLICABLE");
}


function complianceSubjectEligible(subject) {
    const vendorKey = safe(subject?.vendor_key);
    if (vendorKey !== "check_point") return true;
    const source = complianceSourceDevice(subject);
    const entityType = safe(source?.entity_type);
    if (entityType === "vsx_host" || entityType === "virtual_system") return false;
    return true;
}


function complianceStatusCount(controls, status) {
    return controls.filter(control => safe(control?.status).toUpperCase() === status).length;
}


function complianceScopedSubjects() {
    let rows = complianceSubjects.filter(subject => safe(subject.availability || "AVAILABLE") === "AVAILABLE");
    rows = rows.filter(complianceSubjectEligible);
    if (complianceVendorFilter !== "all") {
        rows = rows.filter(subject => safe(subject.vendor_key) === complianceVendorFilter);
    }
    if (complianceStatusFilter !== "all") {
        rows = rows.filter(subject => {
            const subjectStatus = safe(subject.status).toUpperCase();
            if (subjectStatus === complianceStatusFilter) return true;
            return (subject.controls || []).some(control => safe(control.status).toUpperCase() === complianceStatusFilter);
        });
    }
    return rows;
}


function selectedComplianceSubject() {
    return complianceScopedSubjects().find(subject => safe(subject.subject_id) === safe(complianceSelectedSubjectId)) || null;
}


function complianceControlCard(control, options = {}) {
    const showFramework = options.showFramework !== false;
    const showRoadmap = options.showRoadmap !== false;
    const showControlId = options.showControlId !== false;
    const showTraceability = options.showTraceability !== false;
    const compact = options.compact === true;
    const status = safe(control?.status || "UNKNOWN").toUpperCase();
    const mappings = control?.framework_mappings || {};
    const roadmap = Array.isArray(control?.roadmap_links) ? control.roadmap_links : [];
    const evidenceFields = Array.isArray(control?.evidence_fields) ? control.evidence_fields.filter(Boolean) : [];
    const benchmark = safe(control?.benchmark);
    const benchmarkReference = safe(control?.benchmark_reference);
    const lifecycle = safe(control?.control_lifecycle);
    const plannedReason = safe(control?.planned_reason);
    const futureEvidence = safe(control?.future_evidence_requirement);
    const scope = safe(control?.scope || "");
    const evidencePlane = safe(control?.evidence_plane || "").replaceAll("_", " ");
    const evidenceCoverage = safe(control?.evidence_coverage || "").replaceAll("_", " ");
    const benchmarkLabel = benchmark && benchmarkReference ? `${benchmark} · ${benchmarkReference}` : (benchmark || benchmarkReference);
    return `
        <article class="compliance-control-card ${escapeHtml(complianceStatusTone(status))}${compact ? " compact" : ""}">
            <div class="compliance-control-head">
                <div>
                    <h3>${escapeHtml(control?.title || "Control")}</h3>
                    ${benchmarkLabel ? `<div class="compliance-control-benchmark">${escapeHtml(benchmarkLabel)}</div>` : ""}
                    ${showControlId ? `<div class="compliance-control-id">${escapeHtml(control?.control_id || "")}</div>` : ""}
                </div>
                ${statusPill(status, complianceStatusTone(status))}
            </div>
            <p>${escapeHtml(control?.evidence_summary || "No summary available.")}</p>
            ${showTraceability ? `<div class="compliance-traceability-grid">
                ${scope ? `<div><strong>Scope</strong><span>${escapeHtml(scope)}</span></div>` : ""}
                ${lifecycle ? `<div><strong>Lifecycle</strong><span>${escapeHtml(lifecycle.replaceAll("_", " "))}</span></div>` : ""}
                ${evidencePlane ? `<div><strong>Evidence plane</strong><span>${escapeHtml(evidencePlane)}</span></div>` : ""}
                ${evidenceCoverage ? `<div><strong>Coverage</strong><span>${escapeHtml(evidenceCoverage)}</span></div>` : ""}
            </div>` : ""}
            ${showTraceability && evidenceFields.length ? `<div class="compliance-evidence-fields"><strong>Evidence checked</strong><span>${escapeHtml(evidenceFields.join(", "))}</span></div>` : ""}
            ${status === "PLANNED" && plannedReason ? `<div class="compliance-planned-note"><strong>Evidence gap</strong><span>${escapeHtml(plannedReason)}</span>${futureEvidence ? `<span class="future-evidence">Required: ${escapeHtml(futureEvidence)}</span>` : ""}</div>` : ""}
            ${showFramework ? `<div class="compliance-mapping-grid">
                ${["cis", "pci_dss", "bddk"].map(key => {
                    const row = mappings[key] || {};
                    const mappingType = safe(row.mapping_type || "").replaceAll("_", " ");
                    const area = safe(row.control_area || "evidence-backed control area");
                    const ref = safe(row.framework_reference || "");
                    const line = ref ? `${area} (${ref})` : area;
                    return `<div><strong>${escapeHtml(key.toUpperCase())}</strong><span>${escapeHtml(line)}</span>${mappingType ? `<small>${escapeHtml(mappingType)}</small>` : ""}</div>`;
                }).join("")}
            </div>` : ""}
            ${showRoadmap && roadmap.length ? `<div class="compliance-roadmap-links">${roadmap.map(item => `<button type="button" class="compliance-roadmap-link" data-open-plan="${escapeHtml(item.feature_id || "")}">${escapeHtml(item.title || item.feature_id || "roadmap item")}</button>`).join("")}</div>` : ""}
        </article>
    `;
}


function renderComplianceSubjectList() {
    const host = document.getElementById("complianceSubjectList");
    const stats = document.getElementById("complianceFleetStats");
    if (!host) return;
    if (!complianceUiData?.available) {
        host.innerHTML = `<div class="empty-list">Compliance posture is not attached to this export.</div>`;
        if (stats) stats.textContent = "No compliance payload";
        return;
    }
    const rows = complianceScopedSubjects();
    if (stats) {
        const fleet = compliancePayload()?.fleet || {};
        const unavailable = Number(fleet.unavailable_subjects || 0);
        stats.textContent = `${formatNumber(rows.length)} assessed device${rows.length === 1 ? "" : "s"}${unavailable ? ` · ${formatNumber(unavailable)} not assessed` : ""}`;
    }

    host.innerHTML = `
        <div class="compliance-subject-item ${complianceSelectedSubjectId === "__fleet__" ? "active" : ""}" data-compliance-subject="__fleet__">
            <div class="compliance-subject-title">Fleet controls</div>
            <div class="compliance-subject-meta">Global safeguards, platform signals, and roadmap-planned controls</div>
        </div>
        ${rows.map(subject => {
            const status = safe(subject.status).toUpperCase() || "UNKNOWN";
            const sourceName = complianceSourceName(subject);
            const scopedControls = complianceRenderableControls(subject.controls);
            const findingCount = complianceStatusCount(scopedControls, "FINDING");
            return `
                <div class="compliance-subject-item ${safe(subject.subject_id) === safe(complianceSelectedSubjectId) ? "active" : ""}" data-compliance-subject="${escapeHtml(subject.subject_id)}">
                    <div class="compliance-subject-title-row">
                        <div class="compliance-subject-title">${escapeHtml(sourceName || subject.subject_id || "Device")} · ${escapeHtml(complianceVendorCode(subject))}</div>
                        ${findingCount ? `<span class="compliance-finding-badge">${formatNumber(findingCount)} finding${findingCount === 1 ? "" : "s"}</span>` : ""}
                    </div>
                    <div class="compliance-subject-meta">${formatNumber(scopedControls.length)} evaluated controls</div>
                </div>
            `;
        }).join("")}
    `;

    host.querySelectorAll("[data-compliance-subject]").forEach(node => {
        node.addEventListener("click", () => {
            complianceSelectedSubjectId = node.dataset.complianceSubject || "__fleet__";
            renderComplianceSubjectList();
            renderComplianceContent();
        });
    });
}


function renderComplianceFleetCards() {
    const cards = document.getElementById("complianceFleetCards");
    if (!cards) return;
    if (!complianceUiData?.available) {
        cards.innerHTML = "";
        return;
    }
    const fleet = compliancePayload()?.fleet || {};
    const subjectCounts = fleet.subject_status_counts || {};
    cards.innerHTML = [
        metricCard("✓ ASSESSED", formatNumber(fleet.evaluated_subjects), "Devices with current-state evidence", "success"),
        metricCard("! ACTION NEEDED", formatNumber(subjectCounts.FINDING), "Devices with one or more findings", "danger"),
        metricCard("? EVIDENCE GAP", formatNumber(subjectCounts.UNKNOWN), "Assessed devices with incomplete evidence", "muted"),
        metricCard("○ NOT ASSESSED", formatNumber(fleet.unavailable_subjects), "Current-state evidence was not collected", "neutral"),
    ].join("");
}


function complianceSubjectStatusCounts(subject) {
    const rows = complianceRenderableControls(subject?.controls);
    return {
        total: rows.length,
        pass: complianceStatusCount(rows, "PASS"),
        finding: complianceStatusCount(rows, "FINDING"),
        unknown: complianceStatusCount(rows, "UNKNOWN"),
    };
}


function renderComplianceHeader(subject) {
    const eyebrow = document.getElementById("complianceEyebrow");
    const title = document.getElementById("complianceTitle");
    const subtitle = document.getElementById("complianceSubtitle");
    const status = document.getElementById("complianceDetailStatus");
    if (!eyebrow || !title || !subtitle || !status) return;

    if (!complianceUiData?.available) {
        eyebrow.textContent = "Compliance Posture";
        title.textContent = "Compliance posture unavailable";
        subtitle.textContent = "This export does not include a compliance evidence cycle.";
        status.innerHTML = statusPill("Not collected", "muted");
        return;
    }

    if (!subject) {
        const fleet = compliancePayload()?.fleet || {};
        eyebrow.textContent = "Compliance · Fleet";
        title.textContent = "Evidence-backed control areas";
        subtitle.textContent = "No compliance certification claim. Results are bounded to observed evidence areas.";
        status.innerHTML = [
            statusPill(`${formatNumber(fleet.evaluated_subjects)} assessed`, "success"),
            Number(fleet.unavailable_subjects || 0) ? statusPill(`${formatNumber(fleet.unavailable_subjects)} not assessed`, "neutral") : "",
        ].join("");
        return;
    }

    const sourceName = complianceSourceName(subject);
    const counts = complianceSubjectStatusCounts(subject);
    eyebrow.textContent = `Compliance · ${complianceVendorCode(subject)}`;
    title.textContent = sourceName || subject.subject_id || "Device";
    subtitle.textContent = `${formatNumber(counts.total)} evaluated control${counts.total === 1 ? "" : "s"} for this device`;
    status.innerHTML = counts.finding
        ? statusPill(`${formatNumber(counts.finding)} finding${counts.finding === 1 ? "" : "s"}`, "danger")
        : (counts.unknown ? statusPill(`${formatNumber(counts.unknown)} unknown`, "muted") : statusPill("No findings", "success"));
}


function renderComplianceFleetView() {
    const disclaimer = document.getElementById("complianceDisclaimer");
    if (disclaimer) {
        const message = safe(complianceUiData?.disclaimer) || "Not a compliance certification or complete framework assessment.";
        disclaimer.innerHTML = `<strong>Disclaimer:</strong> ${escapeHtml(message)} Framework mappings are informational evidence areas only.`;
    }

    renderComplianceLegend();
    renderComplianceFleetCards();

    const payload = compliancePayload();
    const fleetControls = Array.isArray(payload.fleet_controls) ? payload.fleet_controls : [];
    const platformControls = Array.isArray(payload.platform_controls) ? payload.platform_controls : [];
    const fleetHost = document.getElementById("complianceFleetControls");
    const platformHost = document.getElementById("compliancePlatformControls");

    if (fleetHost) {
        fleetHost.innerHTML = fleetControls.length
            ? `<div class="compliance-control-grid">${fleetControls.map(control => complianceControlCard(control, { showFramework: true, showRoadmap: true, showControlId: true })).join("")}</div>`
            : `<div class="empty-state compact"><span>No fleet control rows available.</span></div>`;
        fleetHost.querySelectorAll("[data-open-plan]").forEach(button => {
            button.addEventListener("click", () => switchModule("project-plan"));
        });
    }
    if (platformHost) {
        platformHost.innerHTML = platformControls.length
            ? `<div class="compliance-control-grid">${platformControls.map(control => complianceControlCard(control, { showFramework: true, showRoadmap: true, showControlId: true })).join("")}</div>`
            : `<div class="empty-state compact"><span>No platform control rows available.</span></div>`;
        platformHost.querySelectorAll("[data-open-plan]").forEach(button => {
            button.addEventListener("click", () => switchModule("project-plan"));
        });
    }

    renderCryptoPostureCard();
}


function renderCryptoPostureCard() {
    const host = document.getElementById("cryptoPostureCard");
    if (!host) return;
    const data = (typeof cryptoUiData === "object" && cryptoUiData) ? cryptoUiData : {};
    if (!data.available) {
        host.innerHTML = `<div class="empty-state compact"><span>No cryptographic evidence yet. Run a configuration collection (PAN effective-running) to populate IKE/IPsec/TLS/certificate posture.</span></div>`;
        return;
    }
    const counts = (data.fleet && data.fleet.status_counts) || {};
    const chip = (label, key, tone) =>
        `<span class="statuspill ${tone}">${escapeHtml(label)} ${Number(counts[key] || 0)}</span>`;
    const subjects = Array.isArray(data.subjects) ? data.subjects : [];
    const notable = [];
    subjects.forEach(subject => {
        (subject.findings || []).forEach(f => {
            if (f.status === "FINDING" || f.status === "INFORMATIONAL") {
                notable.push({ subject: subject.subject_id, ...f });
            }
        });
    });
    notable.sort((a, b) => (a.status === "FINDING" ? 0 : 1) - (b.status === "FINDING" ? 0 : 1));
    const pack = data.rule_pack || {};
    const rows = notable.slice(0, 12).map(f => `
        <article class="compliance-control-card ${f.status === "FINDING" ? "danger" : "neutral"} compact">
            <div class="compliance-control-head">
                <div>
                    <div class="compliance-control-benchmark">${escapeHtml(f.category || "")}</div>
                    <div class="compliance-control-id">${escapeHtml(f.subject)} · ${escapeHtml(f.control_id || "")}</div>
                </div>
                <span class="statuspill ${f.status === "FINDING" ? "danger" : "neutral"}">${escapeHtml(f.status)}</span>
            </div>
            <p>${escapeHtml(f.summary || "")}</p>
            <p class="detail-subtitle">basis: ${escapeHtml(f.evidence_basis || "")}${(f.framework_refs && f.framework_refs.length) ? " · " + escapeHtml(f.framework_refs.join(", ")) : ""}</p>
        </article>`).join("");
    host.innerHTML = `
        <div class="detail-subtitle">Pack ${escapeHtml(pack.pack_id || "")} @ ${escapeHtml(pack.pack_version || "")} · no certification claim · ${subjects.length} subject(s)</div>
        <div class="compliance-kpi-grid compact" style="margin:8px 0">
            ${chip("Findings", "FINDING", "danger")}
            ${chip("Informational", "INFORMATIONAL", "neutral")}
            ${chip("Pass", "PASS", "success")}
            ${chip("Insufficient evidence", "INSUFFICIENT_EVIDENCE", "neutral")}
        </div>
        ${rows ? `<div class="compliance-control-grid">${rows}</div>` : `<div class="empty-state compact"><span>No crypto findings — all evaluated rules pass or lack evidence.</span></div>`}
        <p class="detail-subtitle">PQC readiness: ${escapeHtml((data.pqc && data.pqc.status) || "INFORMATIONAL")} — platform capability only, not configured posture.</p>`;
}


function renderComplianceSubjectView(subject) {
    const summaryHost = document.getElementById("complianceSubjectSummary");
    const subjectHost = document.getElementById("complianceSubjectControls");
    if (!summaryHost || !subjectHost) return;

    if (!subject) {
        summaryHost.innerHTML = "";
        subjectHost.innerHTML = `<div class="empty-state compact"><span>Select an assessed device on the left to inspect control results.</span></div>`;
        return;
    }

    const counts = complianceSubjectStatusCounts(subject);
    summaryHost.innerHTML = [
        metricCard("✓ PASS", formatNumber(counts.pass), "Control areas passing", "success"),
        metricCard("! FINDING", formatNumber(counts.finding), "Requires attention", "danger"),
        metricCard("? UNKNOWN", formatNumber(counts.unknown), "Insufficient evidence", "muted"),
    ].join("");

    const rows = complianceRenderableControls(subject.controls);
    subjectHost.innerHTML = rows.length
        ? `<div class="compliance-control-grid subject-grid">${rows.map(control => complianceControlCard(control, { showFramework: true, showRoadmap: false, showControlId: true, showTraceability: true, compact: true })).join("")}</div>`
        : `<div class="empty-state compact"><span>No control results available for this device.</span></div>`;
}


function renderComplianceContent() {
    const fleetView = document.getElementById("complianceFleetView");
    const subjectView = document.getElementById("complianceSubjectView");
    const subject = complianceUiData?.available && complianceSelectedSubjectId !== "__fleet__" ? selectedComplianceSubject() : null;

    renderComplianceHeader(subject);

    if (fleetView) fleetView.hidden = Boolean(subject);
    if (subjectView) subjectView.hidden = !subject;

    if (subject) {
        renderComplianceSubjectView(subject);
    } else {
        renderComplianceFleetView();
    }
}


function renderComplianceModule() {
    renderComplianceSubjectList();
    renderComplianceContent();
}


function roadmapStatusTone(status) {
    const value = safe(status).toLowerCase();
    if (value === "done" || value === "complete") return "success";
    if (value === "in_progress" || value === "complete_with_followup") return "info";
    if (value === "blocked") return "danger";
    if (value === "deferred") return "warning";
    return "muted";
}


function roadmapStatusLabel(status) {
    const value = safe(status || "planned").replaceAll("_", " ");
    return value.replace(/\b\w/g, char => char.toUpperCase());
}


function roadmapProgress(value, compact = false) {
    const numeric = Math.max(0, Math.min(100, Number(value || 0)));
    return `
        <div class="roadmap-progress ${compact ? "compact" : ""}" aria-label="${escapeHtml(formatPercent(numeric))} complete">
            <div class="roadmap-progress-track"><span style="width:${numeric}%"></span></div>
            <strong>${escapeHtml(formatPercent(numeric))}</strong>
        </div>
    `;
}


function lifecycleStateTone(state) {
    const value = safe(state).toUpperCase();
    if (value === "STABLE") return "success";
    if (value === "VALIDATED") return "info";
    if (value === "DISCOVERED") return "neutral";
    if (value === "EXCLUDED") return "muted";
    if (value === "REMOVED") return "danger";
    return "neutral";
}


function jobStatusTone(status) {
    const value = safe(status).toLowerCase();
    if (value === "completed" || value === "running") return "success";
    if (value === "coalesced") return "info";
    if (value === "failed") return "danger";
    if (value === "cancelled") return "muted";
    return "neutral";
}


function renderDiscoveryModule() {
    const payload = discoveryUiData || {};
    const fleet = payload.fleet_summary || {};
    const entities = Array.isArray(payload.entities) ? payload.entities : [];
    const lifecycleLabels = payload.lifecycle_state_labels || {};
    const modeLabels = payload.collection_mode_labels || {};
    const jobLabels = payload.job_status_labels || {};

    const summaryHost = document.getElementById("discoveryFleetSummary");
    if (summaryHost) {
        const stateCounts = fleet.lifecycle_state_counts || {};
        summaryHost.innerHTML = `
            <article class="project-progress-card primary">
                <div class="eyebrow">Discovered entities</div>
                <div class="project-progress-value">${escapeHtml(formatNumber(fleet.total_entities))}</div>
                <p>${escapeHtml(formatNumber(fleet.deferred_count))} deferred from collection this cycle (lifecycle or safety reason).</p>
            </article>
            <article class="project-progress-card">
                <div class="eyebrow">Lifecycle states</div>
                <div class="project-status-summary">
                    ${Object.entries(stateCounts).length
                        ? Object.entries(stateCounts).map(([key, value]) => `<span>${escapeHtml(lifecycleLabels[key] || key)}<strong>${escapeHtml(formatNumber(value))}</strong></span>`).join("")
                        : `<span>No lifecycle records yet<strong>0</strong></span>`}
                </div>
                <p>Discovered → Validated → Stable is confidence growth; Excluded/Removed retain a safe reason code.</p>
            </article>
        `;
    }

    const coordinatorHost = document.getElementById("discoveryCoordinator");
    if (coordinatorHost) {
        const coordinator = payload.coordinator || {};
        if (!coordinator.available) {
            coordinatorHost.innerHTML = `<div class="empty-state compact"><span>Coordinator not yet wired into this run.</span></div>`;
        } else {
            const budgets = coordinator.budgets || {};
            coordinatorHost.innerHTML = `
                <div class="stats">
                    <span>Active jobs<strong>${escapeHtml(formatNumber(coordinator.active_job_count))}</strong></span>
                    ${Object.entries(budgets).map(([key, row]) => `<span>${escapeHtml(key)} budget<strong>${escapeHtml(formatNumber(row.available))}/${escapeHtml(formatNumber(row.capacity))}</strong></span>`).join("")}
                </div>
                <p>Per-physical-endpoint lock with fixed, conservative vendor/context concurrency budgets. Lock conflicts coalesce onto the active job — a second device session is never opened.</p>
            `;
        }
    }

    const schedulerHost = document.getElementById("discoveryScheduler");
    if (schedulerHost) {
        const scheduler = payload.scheduler || {};
        if (!scheduler.configured) {
            schedulerHost.innerHTML = `<div class="empty-state compact"><span>No RuntimeRoot scheduler policy present — scheduler is disabled by default.</span></div>`;
        } else {
            schedulerHost.innerHTML = `
                <div class="stats">
                    <span>Enabled${statusPill(scheduler.enabled ? "Yes" : "No", scheduler.enabled ? "success" : "muted")}</span>
                    <span>Allowlisted workflows<strong>${escapeHtml(formatNumber(scheduler.workflow_count))}</strong></span>
                </div>
                <p>Scheduler only triggers existing, allowlisted read-only workflows. Manual and scheduled jobs share the same coordinator admission and coalescing safety.</p>
            `;
        }
    }

    const entityHost = document.getElementById("discoveryEntityTable");
    if (entityHost) {
        entityHost.innerHTML = entities.length
            ? `<div class="table-wrap"><table class="data-table"><thead><tr>
                <th>Vendor</th><th>Entity</th><th>Lifecycle</th><th>Confidence</th>
                <th>Shell</th><th>Planned mode</th><th>Allowed</th><th>Reason</th>
            </tr></thead><tbody>${entities.map(row => `
                <tr>
                    <td>${escapeHtml(row.vendor)}</td>
                    <td>${escapeHtml(row.canonical_id)}</td>
                    <td>${statusPill(lifecycleLabels[row.lifecycle_state] || row.lifecycle_state, lifecycleStateTone(row.lifecycle_state))}</td>
                    <td>${escapeHtml(formatNumber(row.confidence))}%</td>
                    <td>${escapeHtml(row.shell_type)}</td>
                    <td>${escapeHtml(modeLabels[row.planned_mode] || row.planned_mode)}</td>
                    <td>${statusPill(row.plan_allowed ? "Allowed" : "Deferred", row.plan_allowed ? "success" : "muted")}</td>
                    <td>${escapeHtml(row.plan_reason_code)}</td>
                </tr>
            `).join("")}</tbody></table></div>`
            : `<div class="empty-state compact"><span>No discovery lifecycle records available yet. Populated once Phase 4 wires collectors through the coordinator.</span></div>`;
    }

    const jobsHost = document.getElementById("discoveryRecentJobs");
    if (jobsHost) {
        const jobs = (payload.coordinator || {}).recent_jobs || [];
        jobsHost.innerHTML = jobs.length
            ? `<div class="table-wrap"><table class="data-table"><thead><tr>
                <th>Job</th><th>Vendor</th><th>Scope</th><th>Provenance</th><th>Status</th><th>Reason</th>
            </tr></thead><tbody>${jobs.map(job => `
                <tr>
                    <td>${escapeHtml(job.job_id)}</td>
                    <td>${escapeHtml(job.vendor)}</td>
                    <td>${escapeHtml(job.workflow_scope)}</td>
                    <td>${escapeHtml(job.provenance)}</td>
                    <td>${statusPill(jobLabels[job.status] || job.status, jobStatusTone(job.status))}</td>
                    <td>${escapeHtml(job.reason || "—")}</td>
                </tr>
            `).join("")}</tbody></table></div>`
            : `<div class="empty-state compact"><span>No coordinator job history yet.</span></div>`;
    }
}


function renderProjectPlan() {
    const plan = projectPlanData || {};
    const badge = document.getElementById("projectPlanBuildBadge");
    if (badge) {
        badge.innerHTML = `<span>Current build</span><strong>${escapeHtml(plan.current_build || "Unknown")}</strong>`;
    }

    const hero = document.getElementById("projectPlanHero");
    if (hero) {
        hero.innerHTML = `
            <article class="project-progress-card primary">
                <div class="eyebrow">Declared roadmap completion</div>
                <div class="project-progress-value">${escapeHtml(formatPercent(plan.overall_progress_percent))}</div>
                ${roadmapProgress(plan.overall_progress_percent)}
                <p>Across the declared 0.5 → 1.0 product roadmap. This is acceptance-criterion completion, not a time estimate.</p>
            </article>
            <article class="project-progress-card">
                <div class="eyebrow">Current major track</div>
                <div class="project-progress-title">${escapeHtml(plan.current_track || "—")}</div>
                <div class="project-progress-value small">${escapeHtml(formatPercent(plan.current_track_progress_percent))}</div>
                ${roadmapProgress(plan.current_track_progress_percent)}
                <p>${escapeHtml(plan.progress_contract || "Roadmap progress is computed from completed acceptance criteria.")}</p>
            </article>
            <article class="project-progress-card">
                <div class="eyebrow">Open backlog</div>
                <div class="project-progress-value small">${escapeHtml(formatNumber((plan.backlog || []).filter(item => item.status !== "done").length))}</div>
                <div class="project-status-summary">
                    ${Object.entries(plan.backlog_counts || {}).map(([key, value]) => `<span>${escapeHtml(roadmapStatusLabel(key))}<strong>${escapeHtml(formatNumber(value))}</strong></span>`).join("")}
                </div>
                <p>Security, collection, architecture, reliability and UX debt stays visible until explicitly closed or deferred.</p>
            </article>
        `;
    }

    const nowNext = document.getElementById("projectNowNext");
    if (nowNext) {
        const horizon = plan.now_next || {};
        const now = horizon.now || {};
        const next = horizon.next || {};
        const upcoming = Array.isArray(horizon.upcoming) ? horizon.upcoming : [];
        nowNext.innerHTML = `
            <article class="horizon-card now">
                <div class="horizon-label">NOW</div>
                <strong>${escapeHtml(now.build || "—")}</strong>
                <h3>${escapeHtml(now.title || "Current build")}</h3>
                <p>${escapeHtml(now.goal || "")}</p>
            </article>
            <article class="horizon-card next">
                <div class="horizon-label">NEXT</div>
                <strong>${escapeHtml(next.build || "—")}</strong>
                <h3>${escapeHtml(next.title || "Next milestone")}</h3>
                <p>${escapeHtml(next.goal || "")}</p>
            </article>
            <article class="horizon-card upcoming">
                <div class="horizon-label">UPCOMING</div>
                <div class="upcoming-list">
                    ${upcoming.map(item => `<div><span>${escapeHtml(item.build || "")}</span><strong>${escapeHtml(item.title || "")}</strong>${statusPill(roadmapStatusLabel(item.status), roadmapStatusTone(item.status))}</div>`).join("")}
                </div>
            </article>
        `;
    }

    const tracksHost = document.getElementById("projectRoadmapTracks");
    if (tracksHost) {
        const tracks = Array.isArray(plan.tracks) ? plan.tracks : [];
        tracksHost.innerHTML = `<div class="roadmap-track-list">${tracks.map(track => `
            <article class="roadmap-track ${safe(track.id) === safe(plan.current_track) ? "current" : ""}">
                <div class="roadmap-track-head">
                    <div>
                        <div class="roadmap-track-id">${escapeHtml(track.id || "")}${track.theme ? ` · ${escapeHtml(track.theme)}` : ""}</div>
                        <h3>${escapeHtml(track.title || "")}</h3>
                    </div>
                    ${statusPill(roadmapStatusLabel(track.status), roadmapStatusTone(track.status))}
                </div>
                ${roadmapProgress(track.progress_percent, true)}
                <div class="roadmap-track-meta">${escapeHtml(formatNumber(track.done_features))} complete features · ${escapeHtml(formatNumber(track.feature_count))} tracked</div>
                <details class="roadmap-track-details">
                    <summary>Show feature map</summary>
                    <div class="roadmap-feature-map">
                        ${(track.features || []).map(feature => `
                            <div class="roadmap-feature-row">
                                <div>
                                    <strong>${escapeHtml(feature.title || "")}</strong>
                                    <span>${escapeHtml(feature.target || feature.introduced || "")}</span>
                                </div>
                                <div class="roadmap-feature-state">${statusPill(roadmapStatusLabel(feature.status), roadmapStatusTone(feature.status))}${roadmapProgress(feature.progress_percent, true)}</div>
                            </div>
                        `).join("")}
                    </div>
                </details>
            </article>
        `).join("")}</div>`;
    }

    const backlogHost = document.getElementById("projectBacklog");
    if (backlogHost) {
        const items = Array.isArray(plan.backlog) ? plan.backlog : [];
        const categories = new Map();
        items.forEach(item => {
            const category = safe(item.category || "Other");
            if (!categories.has(category)) categories.set(category, []);
            categories.get(category).push(item);
        });
        backlogHost.innerHTML = `<div class="backlog-groups">${Array.from(categories.entries()).map(([category, rows]) => `
            <details class="backlog-group" ${rows.some(row => row.priority === "P0" || row.status === "in_progress") ? "open" : ""}>
                <summary><span>${escapeHtml(category)}</span><strong>${escapeHtml(formatNumber(rows.length))}</strong></summary>
                <div class="backlog-items">
                    ${rows.map(item => `
                        <article class="backlog-item">
                            <div class="backlog-item-head">
                                <strong>${escapeHtml(item.title || "")}</strong>
                                <div>${item.priority ? `<span class="priority-badge">${escapeHtml(item.priority)}</span>` : ""}${statusPill(roadmapStatusLabel(item.status), roadmapStatusTone(item.status))}</div>
                            </div>
                            <div class="backlog-target">Target: ${escapeHtml(item.target || "Unscheduled")}</div>
                            ${item.note ? `<p>${escapeHtml(item.note)}</p>` : ""}
                        </article>
                    `).join("")}
                </div>
            </details>
        `).join("")}</div>`;
    }

    const completedHost = document.getElementById("projectCompletedFeatures");
    if (completedHost) {
        const features = Array.isArray(plan.completed_features) ? plan.completed_features : [];
        completedHost.innerHTML = features.map(feature => `
            <article class="completed-feature-card">
                <div class="completed-feature-head">
                    <div><div class="eyebrow">${escapeHtml(feature.introduced || "Delivered")}</div><h3>${escapeHtml(feature.title || "")}</h3></div>
                    ${statusPill("COMPLETE", "success")}
                </div>
                <p>${escapeHtml(feature.summary || "")}</p>
                <div class="feature-why"><strong>Why it matters</strong><span>${escapeHtml(feature.why || "")}</span></div>
                ${feature.evidence ? `<div class="feature-evidence"><strong>Validated evidence</strong><span>${escapeHtml(feature.evidence)}</span></div>` : ""}
            </article>
        `).join("");
    }

    const historyHost = document.getElementById("projectBuildHistory");
    if (historyHost) {
        const builds = Array.isArray(plan.build_history) ? plan.build_history : [];
        historyHost.innerHTML = `<div class="build-history-list">${builds.map(item => `
            <article class="build-history-row">
                <div class="build-history-version">${escapeHtml(item.build || "")}</div>
                <div class="build-history-copy"><strong>${escapeHtml(item.title || "")}</strong><span>${escapeHtml(item.summary || "")}</span></div>
                <div>${statusPill(roadmapStatusLabel(item.status), roadmapStatusTone(item.status))}</div>
            </article>
        `).join("")}</div>`;
    }

    const notesHost = document.getElementById("projectRoadmapNotes");
    if (notesHost) {
        const metadataWarnings = Array.isArray(plan.metadata_warnings) ? plan.metadata_warnings : [];
        notesHost.innerHTML = `
            ${metadataWarnings.length ? `<div class="project-metadata-warning"><strong>Roadmap metadata warning</strong>${metadataWarnings.map(note => `<span>${escapeHtml(note)}</span>`).join("")}</div>` : ""}
            <div class="roadmap-note-list">${(plan.roadmap_notes || []).map(note => `<div><span>•</span><p>${escapeHtml(note)}</p></div>`).join("")}</div>
        `;
    }
}




document.querySelectorAll(".module-nav-item").forEach(button => {
    button.addEventListener("click", () => switchModule(button.dataset.module));
});

document.getElementById("overviewOpenConfiguration")?.addEventListener("click", () => switchModule("configuration"));
document.getElementById("configSearch")?.addEventListener("input", renderConfigDeviceList);
document.getElementById("configHeaderToggle")?.addEventListener("click", () => setConfigHeaderExpanded(!configHeaderExpanded));
document.getElementById("configSidebarToggle")?.addEventListener("click", () => setConfigSidebarOpen(!configSidebarOpen));
window.addEventListener("resize", () => {
    if (window.innerWidth > 900 && configSidebarOpen) setConfigSidebarOpen(false);
});
document.getElementById("configCurrentSearch")?.addEventListener("input", () => renderConfigCurrentPanel(selectedConfigDevice()));
document.getElementById("configClassificationFilter")?.addEventListener("change", () => renderConfigAlignmentPanel(selectedConfigDevice()));
document.getElementById("configAlignmentSearch")?.addEventListener("input", () => renderConfigAlignmentPanel(selectedConfigDevice()));
document.getElementById("complianceVendorFilter")?.addEventListener("change", event => {
    complianceVendorFilter = safe(event?.target?.value || "all");
    complianceSelectedSubjectId = "__fleet__";
    renderComplianceSubjectList();
    renderComplianceContent();
});
document.getElementById("complianceStatusFilter")?.addEventListener("change", event => {
    complianceStatusFilter = safe(event?.target?.value || "all");
    complianceSelectedSubjectId = "__fleet__";
    renderComplianceSubjectList();
    renderComplianceContent();
});

document.querySelectorAll(".config-tab").forEach(tab => {
    tab.addEventListener("click", () => switchConfigTab(tab.dataset.configTab));
});

renderOverviewModule();
renderComplianceModule();
renderDiscoveryModule();
renderProjectPlan();
renderConfigDeviceList();
renderConfigSelected();
switchConfigTab(activeConfigTab);
switchModule(savedModule());
