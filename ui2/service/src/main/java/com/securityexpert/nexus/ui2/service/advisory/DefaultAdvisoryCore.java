package com.securityexpert.nexus.ui2.service.advisory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The entry points of advisory matching (contract sections 4 to 7): the parsers of every source (injected, one per source key),
 * the four product comparators, the evaluator and the rule codec.
 */
@Component
public final class DefaultAdvisoryCore implements AdvisoryCore {

    private final Map<String, AdvisoryParser> parsers = new HashMap<>();
    private final Map<String, ProductComparator> comparators = new HashMap<>();
    private final AdvisoryEvaluator evaluator;

    public DefaultAdvisoryCore(List<AdvisoryParser> parserBeans) {
        for (AdvisoryParser p : parserBeans) {
            parsers.putIfAbsent(p.source(), p);
        }
        for (ProductComparator c : List.of(new PanOsComparator(),
                new FortinetComparator(AdvisoryModel.PRODUCT_FORTIOS),
                new FortinetComparator(AdvisoryModel.PRODUCT_FORTIMANAGER),
                new CiscoAsaComparator())) {
            comparators.put(c.productKey(), c);
        }
        this.evaluator = new DefaultAdvisoryEvaluator(comparators.values());
    }

    @Override
    public Optional<AdvisoryParser> parser(String source) {
        return Optional.ofNullable(source == null ? null : parsers.get(source));
    }

    @Override
    public Optional<ProductComparator> comparator(String productKey) {
        return Optional.ofNullable(productKey == null ? null : comparators.get(productKey));
    }

    @Override
    public Optional<String> productKey(String vendorHint, String role, String model) {
        if (vendorHint == null) {
            return Optional.empty();
        }
        switch (vendorHint) {
            case "palo_alto":
                return Optional.of(AdvisoryModel.PRODUCT_PAN_OS);
            case "fortinet": {
                String m = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
                boolean manager = "management_server".equals(role) || m.startsWith("fortimanager") || m.startsWith("fmg");
                return Optional.of(manager ? AdvisoryModel.PRODUCT_FORTIMANAGER : AdvisoryModel.PRODUCT_FORTIOS);
            }
            case "cisco_asa":
                return Optional.of(AdvisoryModel.PRODUCT_CISCO_ASA);
            default:
                return Optional.empty();
        }
    }

    @Override
    public AdvisoryEvaluator evaluator() {
        return evaluator;
    }

    @Override
    public String rulesToJson(List<AdvisoryModel.VersionRule> rules) {
        return AdvisoryRuleCodec.toJson(rules);
    }

    @Override
    public List<AdvisoryModel.VersionRule> rulesFromJson(String json) {
        return AdvisoryRuleCodec.fromJson(json);
    }
}
