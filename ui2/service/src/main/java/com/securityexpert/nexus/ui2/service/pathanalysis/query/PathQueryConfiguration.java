package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.policy.CutoverRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.InterfaceNameSource;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyService;
import com.securityexpert.nexus.ui2.service.policy.AsaInterfaceNames;
import com.securityexpert.nexus.ui2.service.policy.LocalFirewallPolicyService;
import com.securityexpert.nexus.ui2.service.policy.PolicyFirewallNames;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/** Path analysis stage A5a composition: stored policy binding, the aiview reverse map holder and the query service. */
@Configuration
public class PathQueryConfiguration {

    @Bean
    public PathPolicyBinder pathPolicyBinder(PolicyQueryService policyQueryService, TransactionBoundary transactionBoundary,
            DeviceRepository deviceRepository) {
        return new PathPolicyBinder(new StoredPathPolicyCatalog(policyQueryService,
                new PolicyCollectionRepository(transactionBoundary), new PolicyFirewallNames(deviceRepository)));
    }

    /** Cisco ASA interface names for the topology build, from the stored configurations (ASA policy step 8). */
    @Bean
    public InterfaceNameSource asaInterfaceNameSource(LocalFirewallPolicyService localFirewallPolicyService) {
        return new AsaInterfaceNames(localFirewallPolicyService::asaSnapshots);
    }

    @Bean
    public PathPseudonymResolver pathPseudonymResolver(SubnetPreservingIpMasker ips, TopologyNamePseudonymizer names) {
        return new PathPseudonymResolver(PathPseudonymMap.over(ips, names));
    }

    /** Routed-path targets for the pre-cutover impact analysis (ACI segmentation step 1, PO 2026-11-14). */
    @Bean
    public CutoverPathTargets cutoverPathTargets(TopologyService topologyService, PathPolicyBinder pathPolicyBinder) {
        return new CutoverPathTargets(topologyService, pathPolicyBinder, Clock.systemUTC());
    }

    @Bean
    public PathQueryService pathQueryService(TopologyService topologyService, PathPolicyBinder pathPolicyBinder,
            PathPseudonymResolver pathPseudonymResolver, SubnetPreservingIpMasker ips, TopologyNamePseudonymizer names,
            TransactionBoundary transactionBoundary) {
        // Cutover segments are read as stored (the existing listing); a failed read is a warning, never "no segment".
        CutoverRepository cutover = new CutoverRepository(transactionBoundary);
        return new PathQueryService(topologyService, pathPolicyBinder, pathPseudonymResolver, ips, names,
                Clock.systemUTC(), cutover::listSegments);
    }
}
