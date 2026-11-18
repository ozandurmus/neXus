package com.securityexpert.nexus.ui2.persistence.script;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@code script} and {@code script_version} (V251).
 */
public interface ScriptRepository {

    List<ScriptRecord> findAll();

    Optional<ScriptRecord> findById(String scriptId);

    Optional<ScriptRecord> findByName(String name);

    List<ScriptVersionRecord> findVersions(String scriptId);

    Optional<ScriptVersionRecord> findVersion(String scriptId, int version);

    ScriptRecord create(ScriptRecord script, ScriptVersionRecord initialVersion, String actorFingerprint, String actionId);

    ScriptRecord appendVersion(String scriptId, ScriptVersionRecord version, String actorFingerprint, String actionId);

    Optional<ScriptRecord> updateSettings(
            String scriptId,
            boolean enabled,
            Optional<String> schedule,
            int timeoutSeconds,
            List<String> egress,
            List<String> egressDeviceTargets,
            List<String> credentialRefs,
            String notifyMode,
            String runAs,
            String actorFingerprint,
            String actionId);

    Optional<ScriptRecord> setEnabled(String scriptId, boolean enabled, String actorFingerprint, String actionId);

    default List<String> findEnrolledDeviceIdsByEndpointHosts(List<String> hosts) {
        return List.of();
    }
}
