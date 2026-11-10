package com.securityexpert.nexus.ui2.service.advisory;

import java.util.List;
import java.util.Optional;

/**
 * The entry points the rest of the service uses (ingest, read interface). Implemented by the core lane; the store and
 * interface lane codes against this interface only.
 */
public interface AdvisoryCore {

    /** The parser of a source key, or empty for an unknown source. */
    Optional<AdvisoryParser> parser(String source);

    /** The comparator of a product key, or empty when the product has none in this part. */
    Optional<ProductComparator> comparator(String productKey);

    /**
     * The product key of a device, from its stored vendor hint ({@code palo_alto}, {@code fortinet}, {@code cisco_asa}, ...),
     * role ({@code gateway}, {@code management_server}, {@code appliance}) and model; empty = {@code NOT_COVERED}.
     */
    Optional<String> productKey(String vendorHint, String role, String model);

    AdvisoryEvaluator evaluator();

    /** The stored JSON form of a train's rules and back; {@code fromJson} of anything it did not write is an empty list. */
    String rulesToJson(List<AdvisoryModel.VersionRule> rules);

    List<AdvisoryModel.VersionRule> rulesFromJson(String json);
}
