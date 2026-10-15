-- Range-against-prefix matching in search_policy_address (V127). inet ordering compares
-- prefix length after the common prefix bits, so 192.0.2.10/32 sorts AFTER 192.0.2.255/24:
-- "first_ip <= broadcast(query)" was false for every range inside a queried prefix and a
-- range never matched a network query (only a single-address query). Compare host addresses
-- (full-length masks) on both sides; the function is otherwise unchanged and backs no index.
CREATE OR REPLACE FUNCTION search_policy_address(object JSONB, query INET) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
DECLARE
    fields JSONB := '{}';
    value TEXT;
    label TEXT;
    first_ip INET;
    last_ip INET;
    subnet INET;
    prefix TEXT;
    mask INET;
    bits INTEGER;
    found BOOLEAN := false;
BEGIN
    IF coalesce(object->>'type', '') NOT IN ('host', 'network', 'address-range', 'address')
       OR coalesce(object->>'status', 'RESOLVED') <> 'RESOLVED' THEN RETURN false; END IF;
    FOR value IN SELECT jsonb_array_elements_text(coalesce(object->'values', '[]')) LOOP
        IF position(': ' IN value) > 0 THEN
            label := split_part(value, ': ', 1);
            value := substring(value FROM position(': ' IN value) + 2);
            fields := fields || jsonb_build_object(label, value);
        ELSE label := 'literal'; END IF;
        IF label IN ('literal', 'ipv4-address', 'ipv6-address', 'ip-netmask') THEN
            subnet := search_inet(value);
            IF subnet IS NOT NULL AND network(subnet) && network(query) THEN found := true; END IF;
        ELSIF label = 'ip-range' THEN
            first_ip := search_inet(trim(split_part(value, '-', 1)));
            last_ip := search_inet(trim(split_part(value, '-', 2)));
            IF first_ip IS NOT NULL AND last_ip IS NOT NULL AND family(first_ip) = family(query)
               AND family(last_ip) = family(query) AND first_ip <= last_ip
               AND host(first_ip)::inet <= host(broadcast(query))::inet
               AND host(last_ip)::inet >= host(network(query))::inet THEN found := true; END IF;
        END IF;
    END LOOP;
    FOR bits IN SELECT unnest(ARRAY[4, 6]) LOOP
        first_ip := search_inet(coalesce(fields->>('ipv' || bits || '-address-first'),
            CASE WHEN bits = 4 THEN fields->>'ip-address-first' END));
        last_ip := search_inet(coalesce(fields->>('ipv' || bits || '-address-last'),
            CASE WHEN bits = 4 THEN fields->>'ip-address-last' END));
        IF first_ip IS NOT NULL AND last_ip IS NOT NULL AND family(first_ip) = family(query)
           AND family(last_ip) = family(query) AND first_ip <= last_ip
           AND host(first_ip)::inet <= host(broadcast(query))::inet
               AND host(last_ip)::inet >= host(network(query))::inet THEN found := true; END IF;
        subnet := search_inet(fields->>('subnet' || bits));
        prefix := fields->>('mask-length' || bits);
        IF bits = 4 AND prefix IS NULL AND fields ? 'subnet-mask' THEN
            mask := search_inet(fields->>'subnet-mask');
            -- Only contiguous masks establish a network; unfamiliar shapes fail closed.
            IF mask IS NOT NULL AND family(mask) = 4 THEN
                FOR n IN 0..32 LOOP
                    IF host(mask) = host(netmask(set_masklen('0.0.0.0'::inet, n))) THEN
                        prefix := n::text; EXIT;
                    END IF;
                END LOOP;
            END IF;
        END IF;
        IF subnet IS NOT NULL AND prefix ~ '^[0-9]{1,3}$' AND prefix::int <= (CASE WHEN bits = 4 THEN 32 ELSE 128 END) THEN
            subnet := set_masklen(subnet, prefix::int);
            IF network(subnet) && network(query) THEN found := true; END IF;
        END IF;
    END LOOP;
    RETURN found;
END;
$$;
