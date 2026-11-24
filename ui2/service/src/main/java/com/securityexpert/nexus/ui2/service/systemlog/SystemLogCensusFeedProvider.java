package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.function.BiFunction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.JooqDevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqEstateNameRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed;
import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed.Chunk;
import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed.Masked;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.privacy.EstateDictionarySource;
import com.securityexpert.nexus.ui2.service.privacy.HmacKeyProvider;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.sanitizer.SanitizerConfiguration;

/**
 * The service half of the system-log template census (docs/design/LOG_TEMPLATE_CENSUS_TOOL_2026_11_24.md): the
 * {@link SystemLogCensusFeed} that decrypts a stored chunk and applies the aiview masking. The census itself runs in
 * the worker module ({@code SystemLogTemplateCensusMain}); the two meet in {@link SystemLogCensusFeed}, which the worker
 * loads by this class's name because neither module may depend on the other. This is the only code with the privacy key,
 * {@link LogMasking} and the device pseudonyms, so it has to live in the service.
 *
 * <p><b>Read only.</b> The database session is read-only; the artefact store is only read; this class writes nothing.</p>
 *
 * <p><b>Privacy boundary.</b> {@link Feed#read} is the only place raw text exists: it decrypts one chunk through the
 * artefact store's envelope path ({@code ArtefactStore.retrieve}, the call {@code SystemLogReadService} makes), masks
 * every line with the current {@link LogMasking#RULE_SET}, drops (and counts) any line the caller's leak check flags
 * (an identity literal of the raw line survived), and returns masked lines only.</p>
 */
public final class SystemLogCensusFeedProvider implements SystemLogCensusFeed.Provider {

    @Override
    public SystemLogCensusFeed open(BiFunction<String, String, String> leakCheck, PrintStream progress) throws Exception {
        Properties props = new Properties();
        props.setProperty("user", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_USER_FILE")), "db_user").strip());
        props.setProperty("password", SecretFile.readRequired(Path.of(System.getenv("UI2_DB_APP_PASSWORD_FILE")), "db_password").strip());
        props.setProperty("ApplicationName", "log-template-census-readonly");
        Connection conn = new org.postgresql.Driver().connect(System.getenv("UI2_DB_URL"), props);
        try {
            conn.setAutoCommit(false);
            conn.setReadOnly(true);
            try (Statement s = conn.createStatement()) {
                s.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                s.execute("SET statement_timeout = '600s'");
            }
            Settings settings = new Settings().withExecuteLogging(false).withRenderSchema(true);
            TransactionBoundary tb = new TransactionBoundary() {
                @Override
                public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) {
                    return work.apply(DSL.using(conn, SQLDialect.POSTGRES, settings));
                }
            };
            JooqDeviceRepository devices = new JooqDeviceRepository(tb);
            JooqDevicePlatformFactsRepository facts = new JooqDevicePlatformFactsRepository(tb);
            byte[] key = new HmacKeyProvider().getSecretKey();
            TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(key);
            EstateDictionarySource estate = new EstateDictionarySource(key,
                    SystemLogReadConfiguration.estateIdentities(devices, facts, names), Duration.ofHours(4));
            KnownNames known = SanitizerConfiguration.knownNames(devices, names).get();
            Supplier<KnownNames> knownOnce = () -> known;
            EstateNameCatalog estateNames = new EstateNameCatalog(key,
                    SystemLogReadConfiguration.estateNameSource(new JooqEstateNameRepository(tb)), Duration.ofHours(4));
            estateNames.get();
            LogMasking masking = new LogMasking(key, knownOnce, estate, estateNames);
            masking.session(); // fail fast, before any chunk is read, when the identity dictionary is unavailable
            ArtefactStore store = new FileArtefactStore(Path.of(System.getenv("UI2_ARTEFACT_STORE_ROOT")),
                    ArtefactStoreCipher.fromBase64Key(SecretFile.readRequired(
                            Path.of(System.getenv("UI2_ARTEFACT_STORE_KEY_FILE")), "artefact_store_key")));
            Map<String, String> pseudonyms = new HashMap<>();
            for (DeviceSummaryRecord d : devices.listAll()) {
                pseudonyms.put(d.deviceId(), pseudonym(names, d));
            }
            progress.println("start rule_set=" + LogMasking.RULE_SET + " devices=" + pseudonyms.size());
            return new Feed(conn, store, masking, pseudonyms, leakCheck);
        } catch (Throwable t) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // the original failure is the one to report
            }
            throw t;
        }
    }

    /** The aiview pseudonym of a device: the inventory pseudonym, or a keyed unknown-host token; never a name. */
    static String pseudonym(TopologyNamePseudonymizer names, DeviceSummaryRecord d) {
        String host = d.observedHostname().filter(s -> !TopologyNamePseudonymizer.isNotAHostname(s)).orElse(null);
        String masked = host == null ? null
                : names.maskDeviceName(host, d.clusterMemberRef().filter(s -> !s.isBlank()).orElse(null), d.deviceId());
        if (masked == null || masked.isBlank() || TopologyNamePseudonymizer.isGap(masked)) {
            return names.maskUnknownHost(d.deviceId());
        }
        return masked;
    }

    /** The census feed over the live chunk index and the artefact store. */
    static final class Feed implements SystemLogCensusFeed {
        private static final String SELECT = "select c.chunk_id, c.device_id, c.source_key, cast(c.log_day as text), "
                + "a.recovery_volume_path, c.wrapped_data_key, c.bytes "
                + "from system_log_chunk c join backup_artefact a on a.artefact_id = c.artefact_ref "
                + "where c.pruned_at is null and a.recovery_volume_path is not null and c.wrapped_data_key is not null "
                + "and c.log_day is not null and c.log_day >= cast(? as date) and c.log_day <= cast(? as date) "
                + "and c.source_key = any(?) "
                + "order by c.source_key, c.device_id, c.log_day, c.collected_at, c.start_offset, c.chunk_id";

        private final Connection conn;
        private final ArtefactStore store;
        private final LogMasking masking;
        private final Map<String, String> pseudonyms;
        private final BiFunction<String, String, String> leakCheck;

        Feed(Connection conn, ArtefactStore store, LogMasking masking, Map<String, String> pseudonyms,
                BiFunction<String, String, String> leakCheck) {
            this.conn = conn;
            this.store = store;
            this.masking = masking;
            this.pseudonyms = pseudonyms;
            this.leakCheck = leakCheck;
        }

        @Override
        public void chunks(LocalDate from, LocalDate to, List<String> sources, Sink sink) throws Exception {
            try (PreparedStatement ps = conn.prepareStatement(SELECT)) {
                ps.setFetchSize(500);
                ps.setString(1, from.toString());
                ps.setString(2, to.toString());
                ps.setArray(3, conn.createArrayOf("text", sources.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Chunk meta = new Chunk(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getBytes(6), rs.getLong(7));
                        if (!sink.accept(meta)) break;
                    }
                }
            }
        }

        /** The only method that holds raw text: decrypt, mask, guard; returns masked lines only. */
        @Override
        public Masked read(Chunk chunk, int maxBytes) throws IOException {
            byte[] bytes;
            boolean truncated = false;
            try (InputStream in = store.retrieve(new ArtefactRef(chunk.artefactRef()), chunk.wrappedKey(), true)) {
                bytes = in.readNBytes(maxBytes);
                if (in.read() >= 0) {
                    truncated = true;
                    int lf = -1;
                    for (int i = bytes.length - 1; i >= 0; i--) {
                        if (bytes[i] == '\n') {
                            lf = i;
                            break;
                        }
                    }
                    if (lf > 0) bytes = java.util.Arrays.copyOf(bytes, lf + 1);
                }
            }
            Charset cs = isValidUtf8(bytes) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
            LogMasking.Session session = masking.session();
            String[] rawLines = new String(bytes, cs).split("\n", -1);
            String[] maskedLines = new String(session.maskChunk(bytes), cs).split("\n", -1);
            boolean aligned = rawLines.length == maskedLines.length;
            List<String> out = new ArrayList<>(rawLines.length);
            long rejected = 0;
            for (int i = 0; i < rawLines.length; i++) {
                String raw = stripCr(rawLines[i]);
                if (raw.isBlank()) continue;
                String masked = aligned ? stripCr(maskedLines[i]) : session.maskLine(raw);
                if (leakCheck.apply(raw, masked) != null) {
                    rejected++;
                    continue;
                }
                out.add(masked);
            }
            return new Masked(out, rejected, truncated);
        }

        @Override
        public String devicePseudonym(String deviceId) {
            return pseudonyms.get(deviceId);
        }

        @Override
        public String maskRuleSet() {
            return LogMasking.RULE_SET;
        }

        @Override
        public void close() throws Exception {
            try {
                conn.rollback();
            } finally {
                conn.close();
            }
        }
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
