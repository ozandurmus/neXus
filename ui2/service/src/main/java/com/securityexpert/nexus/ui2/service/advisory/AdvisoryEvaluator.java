package com.securityexpert.nexus.ui2.service.advisory;

import java.util.List;

/** Judges one stored device version against the rules of one advisory (contract section 7). Pure; never throws. */
public interface AdvisoryEvaluator {

    /**
     * @param productKey the device's product key
     * @param storedVersion the device's version as stored by the inventory (may be null or unparseable)
     * @param advisoryState the parse state of the advisory
     * @param rulesForProduct the advisory's rules whose product key is {@code productKey} (every train)
     */
    AdvisoryModel.Verdict evaluate(String productKey, String storedVersion, AdvisoryModel.ParseState advisoryState,
            List<AdvisoryModel.ProductRule> rulesForProduct);
}
