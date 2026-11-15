package com.securityexpert.nexus.ui2.service.systemlog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqEstateNameRepository;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.service.privacy.EstateDictionarySource;
import com.securityexpert.nexus.ui2.service.privacy.FreeTextEstate;
import com.securityexpert.nexus.ui2.service.privacy.HmacKeyProvider;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * Wires the serials and administrator names the aiview free-text masking uses ({@link FreeTextEstate}). Only those two
 * kinds: policy, rule and object names are ordinary words often enough that matching them in every response would
 * over-mask, and addresses and hostnames already have their own rules. The device read is the system-log view's own
 * ({@code SystemLogReadConfiguration#estateIdentities}); the administrator names come from the same column, through
 * the same shape and generic-word filters as {@link EstateNameCatalog}, plus a stop list of month and weekday names.
 */
@Configuration(proxyBeanMethods = false)
public class FreeTextEstateConfiguration {

    private static final Set<String> CALENDAR_WORDS = Set.of("january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december", "monday", "tuesday", "wednesday", "thursday",
            "friday", "saturday", "sunday");

    @Bean
    public FreeTextEstate freeTextEstate(HmacKeyProvider keys, DeviceRepository devices,
            DevicePlatformFactsRepository platformFacts, TopologyNamePseudonymizer names, TransactionBoundary transactions) {
        return create(keys.getSecretKey(),
                SystemLogReadConfiguration.estateIdentities(devices, platformFacts, names),
                () -> new JooqEstateNameRepository(transactions).flatNames().stream()
                        .filter(f -> f.category() == JooqEstateNameRepository.Category.USER)
                        .map(JooqEstateNameRepository.FlatName::value).toList());
    }

    /** Serials from the device identities and eligible administrator names, as one keyed dictionary source. */
    static FreeTextEstate create(byte[] privacyKey, Supplier<List<EstateIdentity>> deviceIdentities,
            Supplier<List<String>> administratorNames) {
        Set<String> generic = EstateNameCatalog.genericVocabulary();
        Supplier<List<EstateIdentity>> identities = () -> {
            List<EstateIdentity> out = new ArrayList<>();
            for (EstateIdentity identity : deviceIdentities.get()) {
                if (identity.kind() == EstateIdentity.Kind.SERIAL) out.add(identity);
            }
            EstateNameCatalog.Collector users = new EstateNameCatalog.Collector(generic);
            for (String name : administratorNames.get()) users.add(name, EstateNameCatalog.Kind.USER);
            for (EstateIdentity user : users.names) {
                if (!CALENDAR_WORDS.contains(user.value().toLowerCase(Locale.ROOT))) out.add(user);
            }
            return out;
        };
        return new FreeTextEstate(new EstateDictionarySource(privacyKey, identities, Duration.ofSeconds(30)));
    }
}
