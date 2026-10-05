package com.securityexpert.nexus.ui2.service.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;

/** DB-side containment over the latest stored inventory in every device/context. */
public final class IpInventorySearch {
    private IpInventorySearch() {}
    public static final String LATEST = """
        with contexts as (
            select run_id, context from device_route union select run_id, context from device_interface
        ), latest as (
            select distinct on (i.device_id, c.context) i.device_id, c.context, i.run_id
            from device_inventory_run i join contexts c using (run_id)
            order by i.device_id, c.context, i.collected_at desc, i.run_id desc
        )
        """;
    public static final String ROUTES = LATEST + """
        , matching as (
            select r.*, network(search_inet(r.destination)) as destination_network,
                masklen(network(search_inet(r.destination))) = 0 as default_fallback, l.device_id,
                row_number() over (partition by l.device_id order by masklen(network(search_inet(r.destination))) desc,
                    r.context, coalesce(r.route_table, ''), r.route_id) as device_rank
            from device_route r join latest l on r.run_id = l.run_id and r.context = l.context
            where network(search_inet(r.destination)) && network({0}::inet)
        ), hits as (
            select * from matching where {1} or masklen(destination_network) > 0 or device_rank = 1
        ), page as (
            select * from hits order by masklen(destination_network) desc, device_id, context,
                coalesce(route_table, ''), route_id limit {2} offset {3}
        )
        select p.*, (select count(*) from hits) as total from (select 1) sentinel left join page p on true
        order by masklen(p.destination_network) desc, p.device_id, p.context, coalesce(p.route_table, ''), p.route_id
        """;
    public static final String INTERFACES = LATEST + """
        , hits as (
            select a.address_id, a.address, i.name as interface, i.context, l.device_id
            from device_interface_address a join device_interface i using (interface_id)
            join latest l on i.run_id = l.run_id and i.context = l.context
            where network(search_inet(a.address)) && network({0}::inet)
        ), page as (
            select * from hits order by device_id, context, interface, address_id limit {1} offset {2}
        )
        select p.*, (select count(*) from hits) as total from (select 1) sentinel left join page p on true
        order by p.device_id, p.context, p.interface, p.address_id
        """;

    public record Page(List<Map<String, Object>> items, long total) {}
    public static Page search(TransactionBoundary tx, IpQuery query, boolean routes, int limit, int offset,
            Map<String, Map<String, Object>> identities, PrivacyMaskingResponseBodyAdvice masking, boolean masked) {
        return tx.inTransaction(db -> {
            var records = routes ? db.fetch(ROUTES, query.value(), query.cidr(), limit, offset)
                : db.fetch(INTERFACES, query.value(), limit, offset);
            var out = new ArrayList<Map<String, Object>>();
            long total = 0;
            for (var record : records) {
                total = record.get("total", Long.class);
                String id = record.get("device_id", String.class);
                if (id == null) continue;
                var hit = new LinkedHashMap<String, Object>();
                hit.put("device_id", id);
                hit.put("device", identities.getOrDefault(id, Map.of()).get("hostname"));
                // Use the existing keyed privacy projection for context/address fields.
                hit.put("virtual_system", record.get("context", String.class));
                hit.put("interface", masked ? masking.maskSearchLabel("interface", record.get("interface", String.class)) : record.get("interface", String.class));
                if (routes) {
                    String destination = record.get("destination", String.class);
                    hit.put("destination", destination);
                    hit.put("next_hop", record.get("next_hop", String.class));
                    hit.put("protocol", record.get("protocol", String.class));
                    hit.put("route_table", masked ? masking.maskSearchLabel("vrf", record.get("route_table", String.class)) : record.get("route_table", String.class));
                    hit.put("default_fallback", record.get("default_fallback", Boolean.class));
                } else hit.put("address", record.get("address", String.class));
                hit.put("href", "?screen=inventory&device_id=" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                    + "&tab=" + (routes ? "routes" : "interfaces"));
                var visible = new LinkedHashMap<>(GlobalSearchService.visibleIdentity(hit, masking, masked));
                // Avoid a second keyed VS transformation in response advice; this value is already projected.
                visible.put("context", visible.remove("virtual_system"));
                out.add(visible);
            }
            return new Page(out, total);
        });
    }
}
