package com.securityexpert.nexus.ui2.service.script;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.Cookie;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.annotation.Scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialRecord;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialRepository;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.persistence.script.JooqScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunNotification;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptSecretFinding;
import com.securityexpert.nexus.ui2.persistence.script.ScriptVersionRecord;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.platform.ScriptExecutionJob;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController.CreateScriptRequest;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController.NewVersionRequest;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController.PatchSettingsRequest;
import com.securityexpert.nexus.ui2.service.api.ScriptRunnerInternalController;
import com.securityexpert.nexus.ui2.service.notification.NotificationForwarder;
import com.securityexpert.nexus.ui2.service.notification.NotificationSettings;
import com.securityexpert.nexus.ui2.service.notification.NotificationSettingsStore;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.CreateScriptCommand;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.NewVersionCommand;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.Outcome;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.OutputOutcome;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.RunOutcome;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.UpdateSettingsCommand;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChain;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfig;
import com.securityexpert.nexus.ui2.service.security.SessionHasher;

/**
 * Contract clause-to-test traceability table and service-side gap-closing pin tests for {@code script_execution_module}
 * slices 1-4 ({@code docs/design/SCRIPT_EXECUTION_CONTRACT.md}, FROZEN 2026-09-22, together with Amendment 2026-11-18
 * items 1-5 in {@code docs/design/PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md} and Amendment
 * 2026-11-18 (b) items 1-12). Where an amendment differs from earlier contract text, the amendment governs.
 *
 * <h2>Clause-to-test traceability table</h2>
 * <pre>
 * Clause | Normative invariant                                                      | Status &amp; proof / disposition
 * -------|--------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------
 * S2-1   | Host action boundary: scripts isolated from device transports/collectors | PROVEN ScriptRunnerModuleFenceTest#scriptRunnerPackageNeverDependsOnDeviceTransportsOrVendorCollectors
 * S2-2   | Base upload refusal on secret-pattern matches                            | SUPERSEDED_BY A1-1a
 * S2-3   | Raw-evidence law: output bounded, masked, pruned at 30-day retention     | PROVEN ScriptSchedulerAndRunServiceTest#retentionPrunerDeletes30DayExpiredScriptOutputsAndClearsRunColumnsWhileKeepingRecentOutputs, ScriptExecutionSchemaTest#repositoryPersistsScriptsAndAppendOnlyVersionsAndTriggerRefusesUpdateOrDelete
 * S2-4   | No browser-&gt;device path; Overwatch access only via gated nexus-cli       | PROVEN ScriptSchedulerAndRunServiceTest#perRunScriptSessionTokenIsPermittedForRunAsRoleRefusedForSecurityAdminAndReturns401OnceRevoked
 * S3.1a  | Script + monotonic append-only versions, cron, timeout_s&lt;=3600, egress   | PROVEN ScriptExecutionServiceTest#appendOnlyMonotonicVersioningAndScheduleNextFiveFiresAndDeviceEgressWarning, ScriptExecutionServiceTest#validatesCronTimeoutCredentialRefsEgressAndRunAs, ScriptExecutionSchemaTest#repositoryPersistsScriptsAndAppendOnlyVersionsAndTriggerRefusesUpdateOrDelete
 * S3.1b  | Base 3-kind table (sh|py|jar) and plaintext body / script_binary class   | SUPERSEDED_BY S7a, A1-1b
 * S3.2a  | Single-pod ui2-script-runner executing scripts in-process                | SUPERSEDED_BY B-1, B-2, B-3, B-5, B-8
 * S3.2b  | Default 1 CPU / 1 GiB limits; script_run visible on shared Jobs screen   | PARTIAL ScriptSchedulerAndRunServiceTest#jobLogQueryServiceResolvesScriptRunTargetKindAndScriptName covered Jobs projection; 1 CPU / 1 GiB container limits missing -&gt; now proven by ScriptRunnerContractPinsTest#b2_executionPodSpecDropsCapabilitiesMountsSecretsOnlyInInitAndSetsDeadline
 * S3.3a  | script_run job shape: target_kind = script, target_ref = id@version      | PARTIAL ScriptRunnerModuleFenceTest#scriptRunnerClaimLoopClaimsOnlyScriptRunAndGeneralWorkerNeverClaimsIt checked constants only -&gt; target_ref codec and JooqScriptRepository#admitRun SQL now proven by ScriptExecutionContractPinsTest#s3_3a_admitRunWritesScriptTargetKindAndVersionedTargetRef
 * S3.3b  | Body in scratch; clean env (NEXUS_SCRIPT_ID, NEXUS_RUN_ID, NEXUS_CLI,..) | PARTIAL ScriptRunBrokerTest#executionPodHasNoSecretEnvNoTokenNoStoreMountsAndItsOwnUid checked env key names only -&gt; concrete values, scratch HOME/workingDir, and minimal PATH now proven by ScriptRunnerContractPinsTest#s3_3b_cleanEnvironmentSetsExactContractVariablesScratchHomeAndMinimalPath
 * S3.3c  | stdout/stderr each capped at 256 KiB, masked, exit code, duration, state | PARTIAL ScriptRunBrokerTest#happyPathRunsInItsOwnJobMasksOutputDeletesEverythingAndCompletes, #nonZeroExitIsFailureWithItsExitCode, #deadlineExceededIsTimedOutAndEverythingIsDeletedSoForkedChildrenDieWithThePod missed dual-stream 256 KiB cap, DiagnosticText.scrubSecrets, and durationMs -&gt; now proven by ScriptRunnerContractPinsTest#b6_plainAndBase64CredentialSecretsMaskedBefore256KibCap
 * S3.3d  | Encrypted script_output artefact, audited download, last 4 KiB inline    | PARTIAL ScriptRunDispatchServiceTest#finishMasksAgainStoresEncryptedOutputClosesTheRunAndRevokesTheSession and ScriptSchedulerAndRunServiceTest#outputTailIsWithheldOnListRunsAndForNonOperatorRolesAndAuditedOnGetRun missed &gt;4 KiB full-artefact vs last-4096-byte tail split -&gt; now proven by ScriptExecutionContractPinsTest#s3_3d_capturedOutputStoredEncryptedWithLast4KibInlineTail
 * S3.3e  | Per-run nexus-cli session for run_as (never security_admin), revoked end | PARTIAL ScriptSchedulerAndRunServiceTest#perRunScriptSessionTokenIsPermittedForRunAsRoleRefusedForSecurityAdminAndReturns401OnceRevoked stubbed SessionRecord manually -&gt; full ScriptRunDispatchService start/finish/abort through GateChain now proven by ScriptExecutionContractPinsTest#s3_3e_s5_5_dispatchMintedSessionEnforcesRunAsAndReturns401AfterFinishOrAbort
 * S3.3f  | Live execution pod read-only rootfs, scratch write, in-pod CLI HTTP call | REAL_ENV_ONLY Kubelet container rootfs enforcement and pod-&gt;ui2-service:8080 call require live cluster (checklist R01, R05, R06, R07, R08)
 * S3.4a  | ScriptScheduler 60s @Scheduled tick, slot fence, 1h catch-up, disabled   | PARTIAL ScriptSchedulerAndRunServiceTest#schedulerClaimsDueSlotOnceEnforcesOneHourCatchUpAndIgnoresDisabledScripts and ScriptExecutionSchemaTest#repositoryPersistsScriptsAndAppendOnlyVersionsAndTriggerRefusesUpdateOrDelete missed @Scheduled 60s annotation and 6-night outage non-replay -&gt; now proven by ScriptExecutionContractPinsTest#b12_schedulerUsesEuropeIstanbulAndOneHourCatchUp
 * S3.4b  | Run now (screen / nexus-cli script-run) submits the same script_run job  | PARTIAL ScriptSchedulerAndRunServiceTest#runNowControllerAndOutputDownloadWorkAndRefuseReplayViewer and ScriptCliTest#everyScriptCommandCallsTheExpectedRouteWithExpectedPayload did not compare manual vs scheduled job rows -&gt; now proven by ScriptExecutionContractPinsTest#s3_4b_runNowAndScheduledSlotAdmitIdenticalScriptRunJobShape
 * S3.4c  | Live wall-clock scheduled firing and single-run slot fencing             | REAL_ENV_ONLY Live Spring scheduler tick against PostgreSQL in Europe/Istanbul (checklist S01-S05)
 * S3.5a  | RFC 5424 syslog over UDP/TCP with run metadata and never output content  | PARTIAL ScriptRunNotificationTest#theSyslogLineCarriesTheRunFieldsAndNeverTheOutput and #aSuccessfulRunIsAnInformationalLine mocked SyslogSink -&gt; real RFC 5424 UDP and octet-counted TCP wire frames via NotificationForwarder#tick now proven by ScriptExecutionContractPinsTest#s3_5a_syslogSendsRfc5424OverUdpAndTcpWithFieldsAndNeverOutputContent
 * S3.5b  | SMTP relay mail per run end (or failure-only) with fields + &lt;=4 KiB text | PARTIAL ScriptRunNotificationTest#theMailCarriesTheFieldsAndAtMost4096BytesOfTheMaskedTail and #mailOnFailureOnlySendsNoMailForASuccessButStillTheSyslogLine mocked Mailer -&gt; real SmtpRelaySender MIME wire mail via NotificationForwarder#tick now proven by ScriptExecutionContractPinsTest#s3_5b_smtpRelayDeliversRunMailAndRespectsMailOnFailureOnly
 * S3.5c  | SMTP mail includes the FIRST 4 KiB of masked output when output &gt; 4 KiB  | DEFECT ScriptExecutionContractPinsTest#s3_5c_smtpMailCarriesFirst4KibOfMaskedOutputWhenOutputExceeds4Kib (@Disabled: ScriptRunNotifier mails script_run.output_tail, which holds the LAST 4 KiB from tailUtf8Bytes, omitting the first 4 KiB when output &gt; 4 KiB)
 * S3.5d  | Notification failure recorded on run; never fails or mutates completed   | PARTIAL ScriptRunNotificationTest#aSendFailureIsRecordedFailedAndTheRunRowIsNeverTouched tested a FAILED run only -&gt; COMPLETED/SUCCESS run preserving outcome while joining FAILED notification row now proven by ScriptExecutionContractPinsTest#s3_5_notificationFailureRecordedWithoutFailingCompletedRun
 * S3.5e  | External syslog receiver and corporate SMTP / STARTTLS delivery          | REAL_ENV_ONLY Live syslog collector and SMTP relay / TLS handshake (checklist P1, P4, N01-N05)
 * S3.6a  | Screen editor/upload, 5-fire preview, egress, notify, Run now, history   | PROVEN ScriptExecutionServiceTest#appendOnlyMonotonicVersioningAndScheduleNextFiveFiresAndDeviceEgressWarning (plus frontend Vitest suites ScriptExecutionPanel.test.tsx and ScriptRunNotifications.test.tsx)
 * S3.6b  | Operations &gt; Script Execution list includes last run outcome per script  | DEFECT ScriptExecutionContractPinsTest#s3_6b_scriptListIncludesLastRunOutcome (@Disabled: GET /api/v2/scripts in ScriptExecutionController#toScriptMap and ScriptExecutionPanel list table omit last_run_outcome)
 * S3.6c  | CLI parity: script-list / script-add / script-set / script-run / -runs   | PROVEN ScriptCliTest#everyScriptCommandCallsTheExpectedRouteWithExpectedPayload, ScriptCliTest#usageErrorsExitTwoWithoutSendingRequests
 * S3.6d  | Roles: security_admin write; operator+ run; everyone with job_log_read   | PARTIAL ScriptExecutionSecurityRouteTest#writeActionRequiresSecurityAdminAndReadActionIsOpenToEveryAuthenticatedRole, #onlySecurityAdminMayMutateScriptsAndEveryOtherRoleAndMachineSessionIsRefused, #everyAuthenticatedRoleMayReadScriptMetadata, #operatorAndAboveMayRunScriptsWhileViewerAndReplayViewerAreRefused did not pin SCRIPT_READ == JOB_LOG_READ role set -&gt; now proven by ScriptExecutionContractPinsTest#s3_6d_scriptReadMatchesJobLogReadAndRoleMatrixHolds
 * S4-1   | PO decision 1: dedicated runner pod                                      | SUPERSEDED_BY B-1
 * S4-2   | PO decision 2: free-form user/credential actor on run_as                 | SUPERSEDED_BY B-9, A1-5
 * S4-3   | PO decision 3: 30-day default output retention                           | PROVEN ScriptSchedulerAndRunServiceTest#retentionPrunerDeletes30DayExpiredScriptOutputsAndClearsRunColumnsWhileKeepingRecentOutputs
 * S4-4   | PO decision 4: Linux runner languages; .bat refused                      | PROVEN ScriptExecutionServiceTest#supportsAllTenContractSection7KindsAndInfersKindFromExtension, ScriptExecutionServiceTest#refusesBatAndCmdUploadsWithExactContractReason
 * S5-1   | Mandatory pre-ship test 1: upload refused on credential literal          | SUPERSEDED_BY A1-1a
 * S5-2   | Mandatory pre-ship test 2: execution pod no SA token, RO root, scratch   | PARTIAL ScriptRunBrokerTest#executionPodHasNoSecretEnvNoTokenNoStoreMountsAndItsOwnUid -&gt; completed by ScriptRunnerContractPinsTest#b2_executionPodSpecDropsCapabilitiesMountsSecretsOnlyInInitAndSetsDeadline
 * S5-3a  | Mandatory pre-ship test 3 (manifest/spec): NetworkPolicy egress fixture  | PARTIAL ScriptRunBrokerTest#networkPolicyAllowsOnlyDnsServiceAndEachDeclaredHostPortAsResolvedNow -&gt; completed by ScriptRunnerContractPinsTest#b3_networkPolicyOmitsDnsWhenEgressEmptyAndBlocksClusterAndDbIps
 * S5-3b  | Mandatory pre-ship test 3 (CNI): undeclared egress blocked on wire       | REAL_ENV_ONLY Kubernetes CNI dataplane enforcement of per-run NetworkPolicy (checklist E01-E03)
 * S5-4   | Mandatory pre-ship test 4: run beyond timeout_s killed, TIMED_OUT, wiped | PROVEN ScriptRunBrokerTest#deadlineExceededIsTimedOutAndEverythingIsDeletedSoForkedChildrenDieWithThePod, ScriptRunBrokerTest#brokerDeadlineStopsAPodThatNeverEnds (live kubelet deadline in checklist T01-T03)
 * S5-5   | Mandatory pre-ship test 5: per-run token revoked at end; later use 401   | PARTIAL ScriptSchedulerAndRunServiceTest#perRunScriptSessionTokenIsPermittedForRunAsRoleRefusedForSecurityAdminAndReturns401OnceRevoked -&gt; dispatch-driven GateChain 401 pinned by ScriptExecutionContractPinsTest#s3_3e_s5_5_dispatchMintedSessionEnforcesRunAsAndReturns401AfterFinishOrAbort (checklist R05, T03)
 * S5-6   | Mandatory pre-ship test 6: script_run heartbeats lease and survives drain| PARTIAL ScriptRunBrokerTest#claimLoopTurnsAFailedLeaseRenewalIntoAKilledRun tested failed renewal only -&gt; positive multi-beat renewal and rollout drain now proven by ScriptRunnerContractPinsTest#s5_6_claimLoopHeartbeatsActiveLeaseAndDrainsWithoutClaimingNextJob
 * S6     | Base refusal of enrolled device management address in egress             | SUPERSEDED_BY A1-2
 * S7a    | Section 7 interpreter argv table + Containerfile.script-exec build rules | PARTIAL ScriptRunnerModuleFenceTest#interpreterTableCoversEveryContractSection7Kind checked argv[0] only -&gt; full argv, unsupported-kind refusal, and Containerfile.script-exec offline invariants now proven by ScriptRunnerContractPinsTest#s7_interpreterCommandsAndContainerfileMatchContractLanguages
 * S7b    | Installed interpreters &amp; libraries executing inside built image          | REAL_ENV_ONLY Container runtime execution of bash / 3.12 interpreter + requests + yaml in cluster (checklist R01, L01)
 * A1-1a  | Privacy-gate secret scan warns with line + class, never value/refusal    | PARTIAL ScriptExecutionServiceTest#secretScanProducesWarningWithLineNumbersNeverRefusesEncryptsAtRestAndNeverReturnsBody tested create on sh only -&gt; addVersion, binary scan, and safe-literal/prose exclusions now proven by ScriptExecutionContractPinsTest#a1_1a_secretScannerWarnsOnCreateAndAddVersionNeverEchoesMatchedValueAndSkipsSafeLiterals
 * A1-1b  | Every script body (text + binary) envelope-encrypted as script_body      | PARTIAL ScriptExecutionServiceTest#secretScanProducesWarningWithLineNumbersNeverRefusesEncryptsAtRestAndNeverReturnsBody tested text body only -&gt; binary envelope encryption and DB metadata-only persistence now proven by ScriptExecutionContractPinsTest#a1_1_storedBodyNeverReturnedByGetOrListEvenToSecurityAdmin
 * A1-1c  | Write-only: no route ever returns stored body, even to security_admin    | PARTIAL ScriptExecutionServiceTest#secretScanProducesWarningWithLineNumbersNeverRefusesEncryptsAtRestAndNeverReturnsBody and ScriptRunnerModuleFenceTest#serviceNeverImportsTheBrokerAndOnlyTheDispatchServiceReleasesAScriptBody missed addVersion/updateSettings/enable/disable and version entries -&gt; now proven by ScriptExecutionContractPinsTest#a1_1_storedBodyNeverReturnedByGetOrListEvenToSecurityAdmin (checklist V03, V04)
 * A1-2   | Enrolled device egress warns, never refuses, correlates stored data only | PARTIAL ScriptExecutionServiceTest#appendOnlyMonotonicVersioningAndScheduleNextFiveFiresAndDeviceEgressWarning missed DRAFT exclusion, IPv4-mapped IPv6 literal normalization without DNS, and PATCH re-correlation -&gt; now proven by ScriptExecutionContractPinsTest#a1_2_deviceEgressWarnsWithoutRefusalCorrelatesStoredEnrolledDevicesOnlyAndRecordsOnRun
 * A1-3   | .bat and .cmd refused with exact Linux-runner message                    | PARTIAL ScriptExecutionServiceTest#refusesBatAndCmdUploadsWithExactContractReason missed addVersion and script-name fallback -&gt; now proven by ScriptExecutionContractPinsTest#a1_3_batAndCmdRefusedAndPs1RefusalExplainsImage
 * A1-4   | Single dedicated runner pod                                              | SUPERSEDED_BY B-1
 * A1-5   | Declared credential_refs validated, resolved only for version, masked    | PARTIAL ScriptExecutionServiceTest#validatesCronTimeoutCredentialRefsEgressAndRunAs and ScriptRunDispatchServiceTest#startReleasesBodyOnlyTheVersionRefsAndASessionOncePerEpoch missed ScriptCredentialSource#fromStore decryption &amp; redaction -&gt; now proven by ScriptExecutionContractPinsTest#a1_5_credentialSourceResolvesOnlyDeclaredRefsViaStoreCipherAndRedactsToString (checklist M01-M03)
 * B-1    | Broker (ui2-script-runner) + per-run Job (script-run-&lt;id&gt;); no in-broker | PROVEN ScriptRunnerModuleFenceTest#theBrokerSpawnsNoProcessAndHoldsNoStoreKeyOrScriptTableAccess, ScriptRunBrokerTest#happyPathRunsInItsOwnJobMasksOutputDeletesEverythingAndCompletes, ScriptRunBrokerTest#consecutiveRunsShareNothingEachGetsFreshObjectsAndLeavesNone (checklist R03, R04)
 * B-2    | Execution pod uid/gid 20185, capabilities drop ALL, init-only seed, wipe | PARTIAL ScriptRunBrokerTest#executionPodHasNoSecretEnvNoTokenNoStoreMountsAndItsOwnUid and #largeBodiesAreSplitOverSeveralSeedSecretsAndRejoinedByTheInitStep missed uid/gid/fsGroup+seccomp on init+main, 0500 bin mode, immutable Secrets, and failure cleanup -&gt; now proven by ScriptRunnerContractPinsTest#b2_executionPodSpecDropsCapabilitiesMountsSecretsOnlyInInitAndSetsDeadline
 * B-3    | Per-run NetworkPolicy omits kube-dns when 0 allowed egress; blocks CIDRs | PARTIAL ScriptRunBrokerTest#networkPolicyAllowsOnlyDnsServiceAndEachDeclaredHostPortAsResolvedNow and #networkPolicyBlocksClusterPodServiceCidrsNodeIpsRegistryDatabaseAndAllowsOnlyKubeDnsWhenEgressEmpty missed all-blocked non-empty egress omitting DNS, IPv6 /128 + ULA blocking, and compressed IPv6 UI2_NODE_IPS canonicalization -&gt; fixed in ScriptRunPodSpec and proven by ScriptRunnerContractPinsTest#b3_networkPolicyOmitsDnsWhenEgressEmptyAndBlocksClusterAndDbIps
 * B-4    | Internal port 8086 SHA-256 token gate + lease-fenced start/finish/abort  | PARTIAL ScriptRunDispatchServiceTest#startReleasesBodyOnlyTheVersionRefsAndASessionOncePerEpoch, #startRefusesWithoutAScriptRunnerLeaseOfThatEpoch, #finishMasksAgainStoresEncryptedOutputClosesTheRunAndRevokesTheSession, #abortRevokesTheSessionOfThatEpochOnly missed ScriptRunnerInternalController and HTTP client -&gt; now proven by ScriptExecutionContractPinsTest#b4_internalControllerEnforcesConstantTimeSha256TokenAndDispatchesStartFinishAbort and ScriptRunnerContractPinsTest#b4_httpServiceClientSendsRunnerTokenAndMapsStartFinishAbortAndRefusals
 * B-5    | ui2_script_runner DB role, K8s prefix guard, runner_available bootstrap  | PARTIAL ScriptRunnerDatabaseRoleIntegrationTest#theBrokerRoleRunsItsWholeDatabasePathAndReachesNothingElse and ScriptRunBrokerTest#namePrefixDisciplineRefusesEverythingOutsideScriptRun missed SHA-256 digest + heartbeat runner_available gate and file-secret loading -&gt; now proven by ScriptExecutionContractPinsTest#b5_runnerAvailableRequiresValidTokenDigestAndFreshHeartbeat and ScriptRunnerContractPinsTest#b5_b8_brokerReadsSecretsFromFileAndRejectsNonDigestExecImage (checklist P2, P3)
 * B-6    | Masking of secrets/usernames/cookie/CSRF before 256 KiB cap; cut line    | PARTIAL ScriptOutputMaskingTest#masksEveryEncodedFormOfASecretAndTheUsername, #masksBeforeTruncatingSoASecretAcrossTheCutLeavesNoPrefix, and ScriptRunBrokerTest#logSplitDropsAPartialLastLineAtTheReadLimitAndKeepsTagsUnforgeable missed broker-level cookie/CSRF + dual-stream straddle masking -&gt; now proven by ScriptRunnerContractPinsTest#b6_plainAndBase64CredentialSecretsMaskedBefore256KibCap (checklist M02)
 * B-7    | Lease loss deletes Job, aborts session, leaves run open for next epoch   | PARTIAL ScriptRunBrokerTest#lostLeaseKillsTheRunRevokesTheSessionAndReportsNothing and ScriptRunDispatchServiceTest#abortRevokesTheSessionOfThatEpochOnly missed subsequent epoch N+1 redispatch after abort -&gt; now proven by ScriptExecutionContractPinsTest#b7_lostLeaseAbortLeavesRunOpenForHigherEpochRedispatch
 * B-8    | Digest-pinned exec image, -XX:-UseBASE64Intrinsics, .ps1 image refusal   | PARTIAL ScriptExecutionServiceTest#supportsAllTenContractSection7KindsAndInfersKindFromExtension tested .ps1 on create only -&gt; addVersion .ps1 refusal, digest check, and JVM flag now proven by ScriptExecutionContractPinsTest#a1_3_batAndCmdRefusedAndPs1RefusalExplainsImage and ScriptRunnerContractPinsTest#b5_b8_brokerReadsSecretsFromFileAndRejectsNonDigestExecImage
 * B-9    | run_as allowlist (operator default, viewer, replay_viewer); V258 CHECK   | PARTIAL ScriptExecutionServiceTest#runAsIsARoleFromTheAllowlistOnly and ScriptOutputMaskingTest#runAsAllowlistAdmitsRolesOnlyNeverSecurityAdminOrAUser missed PATCH validation, blank default, and V258 SQL CHECK -&gt; now proven by ScriptExecutionContractPinsTest#b9_runAsDefaultsToOperatorRejectsForbiddenValuesOnPatchAndMatchesV258Check
 * B-10   | Versioned credential_refs; save-time &amp; run-time _2/_3 alias warnings     | PARTIAL ScriptExecutionServiceTest#changingCredentialRefsInSettingsRecordsANewVersionWithTheSameBody and ScriptRunDispatchServiceTest#startReleasesBodyOnlyTheVersionRefsAndASessionOncePerEpoch never wired useCredentialSource on ScriptExecutionService -&gt; now proven by ScriptExecutionContractPinsTest#b10_credentialRefAliasCollisionWarnsAtSaveAndRunUsesExecutedVersionRefs (checklist M01)
 * B-11   | Output download operator+ writes audit_script_output_download pre-stream | PARTIAL ScriptExecutionSecurityRouteTest#outputDownloadIsOperatorAndAboveOnly and ScriptSchedulerAndRunServiceTest#runNowControllerAndOutputDownloadWorkAndRefuseReplayViewer checked post-hoc count only -&gt; strict before-first-byte ordering and fail-closed audit refusal now proven by ScriptExecutionContractPinsTest#b11_outputDownloadWritesAuditBeforeStreaming (checklist R09)
 * B-12   | Five-field cron read in Europe/Istanbul by default (env zone override)   | PARTIAL ScriptSchedulerAndRunServiceTest#scheduleIsReadInEuropeIstanbulByDefault passed ZoneOffset.UTC to ScriptScheduler -&gt; default Europe/Istanbul firing, UI2_SCRIPT_SCHEDULE_ZONE override/fallback, and 1h catch-up now proven by ScriptExecutionContractPinsTest#b12_schedulerUsesEuropeIstanbulAndOneHourCatchUp (checklist S01)
 * </pre>
 */
class ScriptExecutionContractPinsTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static String runtimeSecret(String tag) {
        return "rt_" + "pin_" + tag + "_" + Long.toHexString(Double.doubleToRawLongBits(Math.PI));
    }

    private static FileArtefactStore newArtefactStore(Path root) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 5 + 3);
        }
        return new FileArtefactStore(
                root,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)));
    }

    private static MockHttpServletRequest authedAdminRequest(String actor) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, actor);
        req.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, Boolean.FALSE);
        return req;
    }

    private static Result<Record> stringRows(String[] columns, String[]... values) {
        String[][] matrix = new String[values.length + 1][];
        matrix[0] = columns;
        System.arraycopy(values, 0, matrix, 1, values.length);
        return DSL.using(SQLDialect.POSTGRES).fetchFromStringData(matrix);
    }

    private static Result<Record> typedScriptRunResult(
            String jobId, String scriptId, int version, String state, String outcome, String triggerKind,
            String runAs, String requestedBy, Timestamp requestedAt, Timestamp finishedAt, Long durationMs,
            Integer exitCode, String outputTail, String notifySyslog, String notifySyslogCode,
            String notifySmtp, String notifySmtpCode, Timestamp notifiedAt) {
        DSLContext ctx = DSL.using(SQLDialect.POSTGRES);
        org.jooq.Field<?>[] fields = new org.jooq.Field<?>[] {
                DSL.field("job_id", String.class),
                DSL.field("script_id", String.class),
                DSL.field("version", Integer.class),
                DSL.field("state", String.class),
                DSL.field("outcome", String.class),
                DSL.field("trigger_kind", String.class),
                DSL.field("scheduled_slot", Timestamp.class),
                DSL.field("run_as", String.class),
                DSL.field("requested_by", String.class),
                DSL.field("requested_at", Timestamp.class),
                DSL.field("started_at", Timestamp.class),
                DSL.field("finished_at", Timestamp.class),
                DSL.field("duration_ms", Long.class),
                DSL.field("exit_code", Integer.class),
                DSL.field("stdout_bytes", Integer.class),
                DSL.field("stderr_bytes", Integer.class),
                DSL.field("stdout_truncated", Boolean.class),
                DSL.field("stderr_truncated", Boolean.class),
                DSL.field("output_artefact_ref", String.class),
                DSL.field("output_key_id", String.class),
                DSL.field("output_wrapped_data_key", byte[].class),
                DSL.field("output_sha256", String.class),
                DSL.field("output_size_bytes", Long.class),
                DSL.field("output_tail", String.class),
                DSL.field("egress_declared", String[].class),
                DSL.field("egress_device_targets", String[].class),
                DSL.field("credential_refs_used", String[].class),
                DSL.field("session_id", String.class),
                DSL.field("failure_reason", String.class),
                DSL.field("notify_syslog", String.class),
                DSL.field("notify_syslog_code", String.class),
                DSL.field("notify_smtp", String.class),
                DSL.field("notify_smtp_code", String.class),
                DSL.field("notified_at", Timestamp.class)};
        Result<Record> res = ctx.newResult(fields);
        if (jobId == null) {
            return res;
        }
        Record rec = ctx.newRecord(fields);
        rec.set(DSL.field("job_id", String.class), jobId);
        rec.set(DSL.field("script_id", String.class), scriptId);
        rec.set(DSL.field("version", Integer.class), version);
        rec.set(DSL.field("state", String.class), state);
        rec.set(DSL.field("outcome", String.class), outcome);
        rec.set(DSL.field("trigger_kind", String.class), triggerKind);
        rec.set(DSL.field("run_as", String.class), runAs);
        rec.set(DSL.field("requested_by", String.class), requestedBy);
        rec.set(DSL.field("requested_at", Timestamp.class), requestedAt);
        rec.set(DSL.field("finished_at", Timestamp.class), finishedAt);
        rec.set(DSL.field("duration_ms", Long.class), durationMs);
        rec.set(DSL.field("exit_code", Integer.class), exitCode);
        rec.set(DSL.field("stdout_bytes", Integer.class), 0);
        rec.set(DSL.field("stderr_bytes", Integer.class), 0);
        rec.set(DSL.field("stdout_truncated", Boolean.class), false);
        rec.set(DSL.field("stderr_truncated", Boolean.class), false);
        rec.set(DSL.field("output_tail", String.class), outputTail);
        rec.set(DSL.field("egress_declared", String[].class), new String[0]);
        rec.set(DSL.field("egress_device_targets", String[].class), new String[0]);
        rec.set(DSL.field("credential_refs_used", String[].class), new String[0]);
        rec.set(DSL.field("notify_syslog", String.class), notifySyslog);
        rec.set(DSL.field("notify_syslog_code", String.class), notifySyslogCode);
        rec.set(DSL.field("notify_smtp", String.class), notifySmtp);
        rec.set(DSL.field("notify_smtp_code", String.class), notifySmtpCode);
        rec.set(DSL.field("notified_at", Timestamp.class), notifiedAt);
        res.add(rec);
        return res;
    }

    private static CredentialReferenceRepository credRefs(String... allowedIds) {
        Set<String> allowed = Set.of(allowedIds);
        return new CredentialReferenceRepository() {
            @Override
            public boolean exists(String id) {
                return allowed.contains(id);
            }

            @Override
            public Optional<CredentialReferenceRecord> find(String id) {
                return exists(id)
                        ? Optional.of(new CredentialReferenceRecord(id, "ssh_password", "ptr-" + id, FIXED_NOW))
                        : Optional.empty();
            }
        };
    }

    /**
     * Clauses A1-1b, A1-1c: both text and binary script bodies are envelope-encrypted at rest under
     * {@link ArtefactClass#SCRIPT_BODY}, database version records hold metadata only, and NO route response
     * ({@code create}, {@code addVersion}, {@code updateSettings}, {@code enable}, {@code disable}, {@code get},
     * {@code list}) ever exposes {@code body}, {@code content}, {@code content_base64}, {@code artefact_ref},
     * {@code key_id}, {@code wrapped_data_key}, or the script payload — not even to {@code role:security_admin}.
     */
    @Test
    void a1_1_storedBodyNeverReturnedByGetOrListEvenToSecurityAdmin() throws Exception {
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        FileArtefactStore store = newArtefactStore(tempDir.resolve("a1-1-store"));
        ScriptExecutionService service = new ScriptExecutionService(
                repo, credRefs("cred-alpha"), null, store, FIXED_CLOCK, 65536);
        ScriptExecutionController controller = new ScriptExecutionController(service, null, null);
        MockHttpServletRequest adminReq = authedAdminRequest("secadmin-actor");

        String uniqueMarker = "MARKER_BODY_" + runtimeSecret("v1");
        String v1Body = "#!/bin/bash\necho '" + uniqueMarker + "'\n";
        var createRes = controller.create(new CreateScriptRequest(
                "write-only-pin", "pin.sh", "sh", v1Body, null, "0 4 * * *", false, 120,
                List.of(), List.of("cred-alpha"), "none", RoleToken.OPERATOR), adminReq);
        assertEquals(201, createRes.getStatusCode().value());
        String scriptId = (String) createRes.getBody().get("script_id");

        String uniqueMarkerV2 = "MARKER_BODY_" + runtimeSecret("v2");
        var addVerRes = controller.addVersion(scriptId, new NewVersionRequest(
                "pin.sh", "sh", "#!/bin/bash\necho '" + uniqueMarkerV2 + "'\n", null, List.of("cred-alpha")), adminReq);
        assertEquals(201, addVerRes.getStatusCode().value());

        var patchRes = controller.updateSettings(scriptId, new PatchSettingsRequest(
                "15 4 * * *", false, true, 180, List.of(), null, "syslog", RoleToken.OPERATOR), adminReq);
        assertEquals(200, patchRes.getStatusCode().value());
        var enableRes = controller.enable(scriptId, adminReq);
        assertEquals(200, enableRes.getStatusCode().value());
        var disableRes = controller.disable(scriptId, adminReq);
        assertEquals(200, disableRes.getStatusCode().value());
        var getRes = controller.get(scriptId, adminReq);
        assertEquals(200, getRes.getStatusCode().value());
        var listRes = controller.list(adminReq);
        assertEquals(200, listRes.getStatusCode().value());

        Set<String> forbiddenKeys = Set.of(
                "body", "content", "content_base64", "body_base64", "artefact_ref", "key_id", "wrapped_data_key");
        for (Map<String, Object> payload : List.of(
                createRes.getBody(), addVerRes.getBody(), patchRes.getBody(),
                enableRes.getBody(), disableRes.getBody(), getRes.getBody(), listRes.getBody())) {
            assertNoForbiddenKeysRecursively(payload, forbiddenKeys);
            String json = JSON.writeValueAsString(payload);
            assertFalse(json.contains(uniqueMarker));
            assertFalse(json.contains(uniqueMarkerV2));
        }

        // Binary kind (bin) is also envelope-encrypted at rest under ArtefactClass.SCRIPT_BODY.
        String binMarker = "ELF_MARKER_" + runtimeSecret("bin");
        byte[] binBytes = ("\u007fELF" + binMarker).getBytes(StandardCharsets.ISO_8859_1);
        Outcome binCreated = service.create(new CreateScriptCommand(
                "binary-pin", "agent.bin", "bin", binBytes, null, false, 60, List.of(), List.of(), "none", null),
                "secadmin-actor");
        String binScriptId = ((Outcome.Ok) binCreated).details().script().scriptId();
        ScriptVersionRecord binVer = repo.findVersion(binScriptId, 1).orElseThrow();
        assertTrue(binVer.artefactRef().startsWith("script_body/"), binVer.artefactRef());
        Path diskCiphertext = tempDir.resolve("a1-1-store").resolve(binVer.artefactRef());
        assertFalse(new String(Files.readAllBytes(diskCiphertext), StandardCharsets.ISO_8859_1).contains(binMarker),
                "binary script_body must be encrypted on disk");
        try (InputStream decrypted = store.retrieve(new ArtefactRef(binVer.artefactRef()), binVer.wrappedDataKey(), false)) {
            assertArrayEquals(binBytes, decrypted.readAllBytes());
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertNoForbiddenKeysRecursively(Object node, Set<String> forbiddenKeys) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                assertFalse(forbiddenKeys.contains(key), "Forbidden key exposed in response: " + key);
                assertNoForbiddenKeysRecursively(entry.getValue(), forbiddenKeys);
            }
        } else if (node instanceof List<?> list) {
            for (Object elem : list) {
                assertNoForbiddenKeysRecursively(elem, forbiddenKeys);
            }
        }
    }

    /**
     * Clause A1-1a: {@link ScriptSecretScanner} warns (never refuses) on both {@code create} and {@code addVersion},
     * scans binary kinds via ISO-8859-1 without echoing matched values, and skips safe placeholders and multi-word prose.
     */
    @Test
    void a1_1a_secretScannerWarnsOnCreateAndAddVersionNeverEchoesMatchedValueAndSkipsSafeLiterals() {
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        FileArtefactStore store = newArtefactStore(tempDir.resolve("a1-1a-store"));
        ScriptExecutionService service = new ScriptExecutionService(
                repo, credRefs(), null, store, FIXED_CLOCK, 65536);

        // Safe literals and >=4-word prose must produce 0 findings.
        String cleanScript = "#!/bin/bash\n"
                + "password = \"changeme\"\n"
                + "secret = \"[redacted]\"\n"
                + "api" + "_key = \"this is descriptive prose not secret\"\n";
        Outcome cleanOutcome = service.create(new CreateScriptCommand(
                "scanner-pin", "scan.sh", "sh", cleanScript.getBytes(StandardCharsets.UTF_8),
                null, false, 60, List.of(), List.of(), "none", null), "secadmin");
        var cleanDetails = assertInstanceOf(Outcome.Ok.class, cleanOutcome).details();
        assertTrue(cleanDetails.versions().get(0).secretFindings().isEmpty());
        assertTrue(cleanDetails.warnings().isEmpty());

        // addVersion with inline secret + private key header succeeds with structured findings and never echoes value.
        String v2Secret = runtimeSecret("scanV2");
        String pkHeader = "-----BEGIN " + "EC " + "PRIVATE KEY-----";
        String v2Text = "#!/bin/bash\n"
                + "echo start\n"
                + "access" + "_token = \"" + v2Secret + "\"\n"
                + pkHeader + "\n";
        Outcome v2Outcome = service.addVersion(
                cleanDetails.script().scriptId(),
                new NewVersionCommand("scan.sh", "sh", v2Text.getBytes(StandardCharsets.UTF_8), null),
                "secadmin");
        var v2Details = assertInstanceOf(Outcome.Ok.class, v2Outcome).details();
        assertEquals(2, v2Details.script().currentVersion());
        assertEquals(List.of(
                new ScriptSecretFinding(3, ScriptSecretScanner.RULE_CREDENTIAL_LITERAL),
                new ScriptSecretFinding(4, ScriptSecretScanner.RULE_PRIVATE_KEY_MATERIAL)),
                v2Details.versions().get(0).secretFindings());
        assertEquals(1, v2Details.warnings().size());
        assertFalse(v2Details.warnings().get(0).contains(v2Secret));
        assertTrue(v2Details.warnings().get(0).contains("credential_refs"));

        // Binary scan finds embedded credential assignment without UTF-8 failure.
        String binSecret = runtimeSecret("scanBin");
        byte[] binaryWithSecret = ("\u0000\u00ff\napi" + "_key=\"" + binSecret + "\"\n").getBytes(StandardCharsets.ISO_8859_1);
        List<ScriptSecretFinding> binFindings = ScriptSecretScanner.scan(binaryWithSecret, false);
        assertEquals(List.of(new ScriptSecretFinding(2, ScriptSecretScanner.RULE_CREDENTIAL_LITERAL)), binFindings);
    }

    /**
     * Clause A1-2: declared egress to an enrolled device's management IP produces a warning (never a refusal),
     * ignores {@code DRAFT} devices, correlates IPv4-mapped IPv6 literals against stored data without DNS lookups,
     * re-correlates on {@code updateSettings}, and carries {@code egress_device_targets} onto admitted runs.
     */
    @Test
    void a1_2_deviceEgressWarnsWithoutRefusalCorrelatesStoredEnrolledDevicesOnlyAndRecordsOnRun() {
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        FileArtefactStore store = newArtefactStore(tempDir.resolve("a1-2-store"));
        DeviceRepository devices = new DeviceRepository() {
            @Override
            public List<DeviceSummaryRecord> listAll() {
                return List.of(
                        new DeviceSummaryRecord("DEV-TANGO-04", "gateway", "check_point",
                                DeviceEnrollmentState.ENROLLED, Optional.of("FW-TANGO-04"),
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.of("192.0.2.10:443"), Optional.empty(), false, false),
                        new DeviceSummaryRecord("DEV-DRAFT-99", "gateway", "check_point",
                                DeviceEnrollmentState.DRAFT, Optional.of("FW-BRAVO-02"),
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.of("192.0.2.99"), Optional.empty(), false, false),
                        new DeviceSummaryRecord("DEV-JULIET-06", "gateway", "palo_alto",
                                DeviceEnrollmentState.ENROLLED, Optional.of("FW-JULIET-06"),
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.of("198.51.100.25"), Optional.empty(), false, false));
            }

            @Override public Optional<DeviceRecord> find(String id) { return Optional.empty(); }
            @Override public Optional<EndpointRecord> findEndpoint(String id) { return Optional.empty(); }
            @Override public Optional<EndpointRecord> findEndpointByDeviceId(String id) { return Optional.empty(); }
            @Override public String registerDraft(DeviceDraft d, String a, String act) { throw new UnsupportedOperationException(); }
            @Override public boolean transitionEnrollmentState(String d, DeviceEnrollmentState f, DeviceEnrollmentState t, String a, String act) { throw new UnsupportedOperationException(); }
            @Override public boolean withdrawDraft(String d, String a, String act) { throw new UnsupportedOperationException(); }
            @Override public boolean setDisabled(String d, boolean dis, String a, String act) { throw new UnsupportedOperationException(); }
            @Override public boolean recordConfirmSuccess(String d, DeviceConfirmFacts f, String a, String act) { throw new UnsupportedOperationException(); }
            @Override public Optional<DeviceConfirmFacts> findConfirmFacts(String d) { return Optional.empty(); }
        };

        ScriptExecutionService service = new ScriptExecutionService(
                repo, credRefs(), devices, store, FIXED_CLOCK, 65536);

        // IPv4-mapped IPv6 literal [::ffff:c000:020a]:22 == 192.0.2.10 -> matches DEV-TANGO-04; DRAFT 192.0.2.99 ignored.
        Outcome created = service.create(new CreateScriptCommand(
                "egress-pin", "egress.sh", "sh", "echo ok\n".getBytes(StandardCharsets.UTF_8),
                null, true, 60, List.of("[::ffff:c000:020a]:22", "192.0.2.99:443"), List.of(), "none", null),
                "secadmin");
        var details1 = assertInstanceOf(Outcome.Ok.class, created).details();
        assertEquals(List.of("DEV-TANGO-04"), details1.script().egressDeviceTargets());
        assertTrue(details1.warnings().stream().anyMatch(w -> w.contains("DEV-TANGO-04")));

        // PATCH egress to 198.51.100.25:443 -> re-correlates to DEV-JULIET-06 and propagates to admitted runs.
        String scriptId = details1.script().scriptId();
        Outcome patched = service.updateSettings(scriptId, new UpdateSettingsCommand(
                Optional.empty(), false, Optional.empty(), Optional.empty(),
                Optional.of(List.of("198.51.100.25:443")), Optional.empty(), Optional.empty(), Optional.empty()),
                "secadmin");
        var details2 = assertInstanceOf(Outcome.Ok.class, patched).details();
        assertEquals(List.of("DEV-JULIET-06"), details2.script().egressDeviceTargets());

        ScriptRunRecord run = assertInstanceOf(RunOutcome.RunSubmitted.class, service.runNow(scriptId, "operator-1")).run();
        assertEquals(List.of("DEV-JULIET-06"), run.egressDeviceTargets());
    }

    /**
     * Clauses A1-3, B-8: {@code .bat} and {@code .cmd} uploads are refused with {@link ScriptExecutionService#BAT_CMD_REFUSAL_REASON}
     * on {@code create} (including script-name fallback) and {@code addVersion}; {@code .ps1} uploads are refused with
     * {@link ScriptExecutionService#PS1_REFUSAL_REASON} explaining that PowerShell 7 is absent from the execution image.
     */
    @Test
    void a1_3_batAndCmdRefusedAndPs1RefusalExplainsImage() {
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        FileArtefactStore store = newArtefactStore(tempDir.resolve("a1-3-store"));
        ScriptExecutionService service = new ScriptExecutionService(
                repo, credRefs(), null, store, FIXED_CLOCK, 65536);

        // Script-name fallback (.bat / .cmd with null fileName and null kind).
        for (String name : List.of("legacy.bat", "deploy.CMD")) {
            Outcome.Refused ref = assertInstanceOf(Outcome.Refused.class, service.create(new CreateScriptCommand(
                    name, null, null, "echo 1\r\n".getBytes(StandardCharsets.UTF_8),
                    null, false, 60, List.of(), List.of(), "none", null), "secadmin"));
            assertEquals(400, ref.status());
            assertEquals(ScriptExecutionService.BAT_CMD_REFUSAL_REASON, ref.message());
        }

        // .ps1 via name fallback, file extension, and explicit kind explains execution image absence.
        for (CreateScriptCommand cmd : List.of(
                new CreateScriptCommand("win.ps1", null, null, "Write-Host 1\n".getBytes(StandardCharsets.UTF_8),
                        null, false, 60, List.of(), List.of(), "none", null),
                new CreateScriptCommand("win", null, "ps1", "Write-Host 1\n".getBytes(StandardCharsets.UTF_8),
                        null, false, 60, List.of(), List.of(), "none", null))) {
            Outcome.Refused ref = assertInstanceOf(Outcome.Refused.class, service.create(cmd, "secadmin"));
            assertEquals(400, ref.status());
            assertEquals("UNSUPPORTED_SCRIPT_KIND", ref.code());
            assertEquals(ScriptExecutionService.PS1_REFUSAL_REASON, ref.message());
            assertTrue(ref.message().contains("PowerShell 7 (pwsh) is not installed in the script execution image"));
        }

        // addVersion also refuses .bat, .cmd, and .ps1 before storing any version.
        Outcome okSh = service.create(new CreateScriptCommand(
                "valid-sh", "ok.sh", "sh", "echo ok\n".getBytes(StandardCharsets.UTF_8),
                null, false, 60, List.of(), List.of(), "none", null), "secadmin");
        String scriptId = ((Outcome.Ok) okSh).details().script().scriptId();

        Outcome.Refused addBat = assertInstanceOf(Outcome.Refused.class, service.addVersion(
                scriptId, new NewVersionCommand("bad.bat", null, "@echo off\r\n".getBytes(StandardCharsets.UTF_8), null), "secadmin"));
        assertEquals(ScriptExecutionService.BAT_CMD_REFUSAL_REASON, addBat.message());

        Outcome.Refused addPs1 = assertInstanceOf(Outcome.Refused.class, service.addVersion(
                scriptId, new NewVersionCommand("bad.ps1", "ps1", "Write-Host 1\n".getBytes(StandardCharsets.UTF_8), null), "secadmin"));
        assertEquals(ScriptExecutionService.PS1_REFUSAL_REASON, addPs1.message());
        assertEquals(1, repo.findVersions(scriptId).size(), "refused version uploads must leave version history untouched");
    }

    /**
     * Clause A1-5: {@link ScriptCredentialSource#fromStore} resolves {@code credential_reference_id -> backendPointer ->
     * CredentialRecord}, decrypts the secret via {@link CredentialStoreCipher}, redacts {@code toString()}, and fails
     * closed when the cipher is absent or the reference is unknown.
     */
    @Test
    void a1_5_credentialSourceResolvesOnlyDeclaredRefsViaStoreCipherAndRedactsToString() {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (0x41 + i);
        }
        CredentialStoreCipher cipher = CredentialStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(rawKey));
        String plainSecret = runtimeSecret("storeCred");
        byte[] encrypted = cipher.encrypt(plainSecret);

        CredentialReferenceRepository refRepo = new CredentialReferenceRepository() {
            @Override
            public boolean exists(String id) {
                return "cred-ref-1".equals(id);
            }

            @Override
            public Optional<CredentialReferenceRecord> find(String id) {
                return "cred-ref-1".equals(id)
                        ? Optional.of(new CredentialReferenceRecord("cred-ref-1", "ssh_password", "cred-row-99", FIXED_NOW))
                        : Optional.empty();
            }
        };
        CredentialRepository credRepo = mock(CredentialRepository.class);
        when(credRepo.findById("cred-row-99")).thenReturn(Optional.of(new CredentialRecord(
                "cred-row-99", "Ops Vault", CredentialKind.SSH_PASSWORD, "ops_svc",
                encrypted, null, "key-1", true, true, "secadmin", FIXED_NOW, FIXED_NOW)));

        ScriptCredentialSource source = ScriptCredentialSource.fromStore(refRepo, credRepo, cipher);
        assertEquals(Optional.of("Ops Vault"), source.displayName("cred-ref-1"));
        assertEquals(Optional.empty(), source.displayName("undeclared-ref"));

        ScriptCredentialSource.Resolved resolved = source.resolve("cred-ref-1").orElseThrow();
        assertEquals("cred-ref-1", resolved.refId());
        assertEquals("Ops Vault", resolved.displayName());
        assertEquals("ops_svc", resolved.username());
        assertEquals(plainSecret, resolved.secret());
        assertFalse(resolved.toString().contains(plainSecret), "Resolved.toString() must never print the secret");

        ScriptCredentialSource keyless = ScriptCredentialSource.fromStore(refRepo, credRepo, null);
        assertEquals(Optional.empty(), keyless.resolve("cred-ref-1"), "without cipher, resolve must fail closed");
    }

    /**
     * Clause S3.3a: {@link ScriptExecutionJob#formatTargetRef} validates {@code scriptId} and {@code version >= 1}, and
     * {@link JooqScriptRepository#admitRun} inserts {@code jobs(job_type='script_run', capability_id='script_run',
     * target_kind='script', target_ref='<script_id>@<version>')}.
     */
    @Test
    void s3_3a_admitRunWritesScriptTargetKindAndVersionedTargetRef() {
        assertEquals("scr-7@3", ScriptExecutionJob.formatTargetRef(" scr-7 ", 3));
        assertThrows(IllegalArgumentException.class, () -> ScriptExecutionJob.formatTargetRef(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> ScriptExecutionJob.formatTargetRef("scr-7", 0));

        List<String> sqlLog = new ArrayList<>();
        List<Object[]> bindingsLog = new ArrayList<>();
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String sql = ctx.sql().toLowerCase(Locale.ROOT);
            sqlLog.add(sql);
            bindingsLog.add(ctx.bindings());
            if (sql.startsWith("select ") && sql.contains("from script_run")) {
                Result<Record> row = typedScriptRunResult(
                        "job-s33a", "scr-7", 3, "REQUESTED", null, "MANUAL",
                        RoleToken.OPERATOR, "op-1", Timestamp.from(FIXED_NOW), null, null,
                        null, null, null, null, null, null, null);
                return new MockResult[] {new MockResult(1, row)};
            }
            return new MockResult[] {new MockResult(1, stringRows(new String[] {"ok"}, new String[] {"1"}))};
        }), SQLDialect.POSTGRES);

        JooqScriptRepository jooqRepo = new JooqScriptRepository(new JooqTransactionBoundary(dsl));
        ScriptRunRecord admitted = jooqRepo.admitRun(
                "job-s33a", "scr-7", 3, "MANUAL", Optional.empty(), RoleToken.OPERATOR,
                List.of(), List.of(), List.of(), FIXED_NOW, "op-1", ActionRegistry.SCRIPT_RUN).orElseThrow();
        assertEquals("scr-7", admitted.scriptId());
        assertEquals(3, admitted.version());

        int jobsInsertIdx = -1;
        for (int i = 0; i < sqlLog.size(); i++) {
            if (sqlLog.get(i).startsWith("insert into jobs(")) {
                jobsInsertIdx = i;
                break;
            }
        }
        assertTrue(jobsInsertIdx >= 0, "JooqScriptRepository#admitRun must insert into jobs");
        Object[] jobsBindings = bindingsLog.get(jobsInsertIdx);
        assertEquals("job-s33a", jobsBindings[0]);
        assertEquals("script_run", jobsBindings[1]);
        assertEquals("script_run", jobsBindings[2]);
        assertEquals("script", jobsBindings[3]);
        assertEquals("scr-7@3", jobsBindings[4]);
    }

    /**
     * Clause S3.3d: when captured output exceeds 4 KiB, {@link ScriptRunDispatchService#finish} stores the full masked
     * plaintext encrypted as {@link ArtefactClass#SCRIPT_OUTPUT} (retrievable via {@code downloadRunOutput}), while
     * {@code script_run.output_tail} exposes at most the last 4096 UTF-8 bytes inline.
     */
    @Test
    void s3_3d_capturedOutputStoredEncryptedWithLast4KibInlineTail() throws Exception {
        FileArtefactStore store = newArtefactStore(tempDir.resolve("s3-3d-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        Outcome created = new ScriptExecutionService(repo, credRefs(), null, store, FIXED_CLOCK, 65536)
                .create(new CreateScriptCommand(
                        "tail-cap", "tail.sh", "sh", "echo 1\n".getBytes(StandardCharsets.UTF_8),
                        null, true, 60, List.of(), List.of(), "none", RoleToken.OPERATOR), "secadmin");
        String scriptId = ((Outcome.Ok) created).details().script().scriptId();
        ScriptExecutionService service = new ScriptExecutionService(repo, credRefs(), null, store, FIXED_CLOCK, 65536);
        ScriptRunRecord submitted = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run();
        String jobId = submitted.jobId();
        repo.jobState = "EXECUTING";
        repo.leaseEpoch = 1L;
        repo.leaseWorker = "script_runner-node1";

        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(
                repo, emptyCredentials(), mock(SessionRepository.class), store, FIXED_CLOCK);
        assertInstanceOf(ScriptRunDispatchService.StartOutcome.Started.class, dispatch.start(jobId, 1L));

        String headMarker = "HEAD_FIRST_LINE_MARKER";
        String tailMarker = "TAIL_FINAL_LINE_MARKER";
        String bigStdout = headMarker + "\n" + ("line-padding-0123456789abcdef\n".repeat(220)) + tailMarker + "\n";
        assertTrue(bigStdout.getBytes(StandardCharsets.UTF_8).length > 6000);

        assertTrue(dispatch.finish(jobId, 1L, new ScriptRunDispatchService.FinishReport(
                "SUCCESS", 0, 500L, bigStdout, "", false, false, null)).isEmpty());

        ScriptRunRecord finished = repo.findRunByJobId(jobId).orElseThrow();
        String inlineTail = finished.outputTail().orElseThrow();
        assertTrue(inlineTail.getBytes(StandardCharsets.UTF_8).length <= 4096);
        assertTrue(inlineTail.contains(tailMarker));
        assertFalse(inlineTail.contains(headMarker), "inline output_tail holds only the last 4 KiB");

        OutputOutcome.OutputReady downloaded = assertInstanceOf(
                OutputOutcome.OutputReady.class, service.downloadRunOutput(jobId, "op-1"));
        String fullText = new String(downloaded.plaintextBytes(), StandardCharsets.UTF_8);
        assertTrue(fullText.contains(headMarker), "full script_output artefact retains head beyond 4 KiB");
        assertTrue(fullText.contains(tailMarker));
    }

    /**
     * Clauses S3.3e, S5-5: per-run session minted by {@link ScriptRunDispatchService#start} authenticates through
     * {@link GateChain} with exact {@code run_as} privileges (refusing {@code security_admin} actions with 403), and
     * returns 401 immediately after {@link ScriptRunDispatchService#finish} or {@link ScriptRunDispatchService#abort}.
     */
    @Test
    void s3_3e_s5_5_dispatchMintedSessionEnforcesRunAsAndReturns401AfterFinishOrAbort() {
        FileArtefactStore store = newArtefactStore(tempDir.resolve("s3-3e-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        ScriptExecutionService service = new ScriptExecutionService(repo, credRefs(), null, store, FIXED_CLOCK, 65536);
        String scriptId = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "sess-pin", "sess.sh", "sh", "echo ok\n".getBytes(StandardCharsets.UTF_8),
                null, true, 120, List.of(), List.of(), "none", RoleToken.OPERATOR), "secadmin"))
                .details().script().scriptId();

        Map<String, SessionRecord> sessionTable = new LinkedHashMap<>();
        SessionRepository sessions = inMemorySessionRepository(sessionTable);
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(
                repo, emptyCredentials(), sessions, store, Clock.systemUTC());

        RbacEvaluator rbac = new RbacEvaluator(
                mock(RoleBindingRepository.class), mock(ActorAuthzStateRepository.class), null);
        GateChain gateChain = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var routeGate = SecurityWebMvcConfig.routeGate(gateChain, null, null);

        // 1. Finish path revokes session -> 401.
        String job1 = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run().jobId();
        repo.jobState = "EXECUTING";
        repo.leaseEpoch = 2L;
        repo.leaseWorker = "script_runner-1";
        var bundle1 = assertInstanceOf(ScriptRunDispatchService.StartOutcome.Started.class, dispatch.start(job1, 2L)).bundle();

        assertTrue(routeGate.decide(cliRequest("GET", "/api/v2/scripts", bundle1.sessionCookie(), bundle1.sessionCsrf()), false).permitted());
        var adminAttempt = routeGate.decide(cliRequest("POST", "/api/v2/scripts", bundle1.sessionCookie(), bundle1.sessionCsrf()), false);
        assertFalse(adminAttempt.permitted());
        assertEquals(403, adminAttempt.status());

        assertTrue(dispatch.finish(job1, 2L, new ScriptRunDispatchService.FinishReport(
                "FAILURE", 2, 100L, "", "err\n", false, false, "EXIT_CODE_2")).isEmpty());
        var afterFinish = routeGate.decide(cliRequest("GET", "/api/v2/scripts", bundle1.sessionCookie(), bundle1.sessionCsrf()), false);
        assertFalse(afterFinish.permitted());
        assertEquals(401, afterFinish.status());

        // 2. Abort path also revokes session -> 401.
        String job2 = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run().jobId();
        repo.leaseEpoch = 3L;
        var bundle2 = assertInstanceOf(ScriptRunDispatchService.StartOutcome.Started.class, dispatch.start(job2, 3L)).bundle();
        assertTrue(routeGate.decide(cliRequest("GET", "/api/v2/scripts", bundle2.sessionCookie(), bundle2.sessionCsrf()), false).permitted());
        assertTrue(dispatch.abort(job2, 3L).isEmpty());
        var afterAbort = routeGate.decide(cliRequest("GET", "/api/v2/scripts", bundle2.sessionCookie(), bundle2.sessionCsrf()), false);
        assertFalse(afterAbort.permitted());
        assertEquals(401, afterAbort.status());
    }

    /**
     * Clause S3.4b: manual {@code runNow} (even when {@code enabled = false}) and {@code runScheduledSlot} admit the
     * same {@code script_run} record shape (version, {@code run_as}, declared {@code egress}, device targets, and
     * version {@code credential_refs}), differing only in {@code trigger_kind} and {@code scheduled_slot}.
     */
    @Test
    void s3_4b_runNowAndScheduledSlotAdmitIdenticalScriptRunJobShape() {
        FileArtefactStore store = newArtefactStore(tempDir.resolve("s3-4b-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        ScriptExecutionService service = new ScriptExecutionService(
                repo, credRefs("cred-1"), null, store, FIXED_CLOCK, 65536);

        var created = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "parity-job", "parity.sh", "sh", "echo 1\n".getBytes(StandardCharsets.UTF_8),
                "0 3 * * *", true, 180, List.of("192.0.2.40:443"), List.of("cred-1"), "syslog", RoleToken.VIEWER),
                "secadmin")).details();
        ScriptRecord script = created.script();

        ScriptRunRecord manual = ((RunOutcome.RunSubmitted) service.runNow(script.scriptId(), "op-user")).run();
        ScriptRunRecord scheduled = service.runScheduledSlot(script, Instant.parse("2026-11-24T00:00:00Z")).orElseThrow();

        assertEquals("MANUAL", manual.triggerKind());
        assertEquals(Optional.empty(), manual.scheduledSlot());
        assertEquals("SCHEDULED", scheduled.triggerKind());
        assertEquals(Optional.of(Instant.parse("2026-11-24T00:00:00Z")), scheduled.scheduledSlot());

        assertEquals(manual.scriptId(), scheduled.scriptId());
        assertEquals(manual.version(), scheduled.version());
        assertEquals(manual.state(), scheduled.state());
        assertEquals(manual.runAs(), scheduled.runAs());
        assertEquals(manual.egressDeclared(), scheduled.egressDeclared());
        assertEquals(manual.credentialRefsUsed(), scheduled.credentialRefsUsed());
    }

    /**
     * Clause S3.5a: {@link NotificationForwarder#tick()} transmits real RFC 5424 syslog frames over both UDP and
     * octet-counted TCP (RFC 6587) with script name, version, outcome, exit code, duration, and run id — and never
     * includes script output content.
     */
    @Test
    void s3_5a_syslogSendsRfc5424OverUdpAndTcpWithFieldsAndNeverOutputContent() throws Exception {
        String outputSecretLine = "SENSITIVE_OUTPUT_LINE_42";

        // 1. UDP RFC 5424 check
        try (DatagramSocket udpReceiver = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            udpReceiver.setSoTimeout(5000);
            NotificationMockDb db = new NotificationMockDb();
            db.addFinishedRun("job-udp-1", "nightly-report", 2, "COMPLETED", "SUCCESS", 0, 845L,
                    "2026-11-24 10:00:00", outputSecretLine, "syslog");

            NotificationSettingsStore store = fixedSettings(new NotificationSettings(
                    true, "127.0.0.1", udpReceiver.getLocalPort(), "udp", 16,
                    false, null, 25, false, null, null,
                    false, false, FIXED_NOW, List.of(), NotificationSettings.IDENTITY_RAW));

            new NotificationForwarder(store, db.directBoundary(), null, null).tick();

            byte[] buf = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            udpReceiver.receive(packet);
            String wire = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            // Facility 16 * 8 + INFORMATIONAL (6) = PRI 134, version 1, app-name nexus, msgid SCRIPT_RUN
            assertTrue(wire.startsWith("<134>1 "), wire);
            assertTrue(wire.contains(" nexus - SCRIPT_RUN - script run finished "), wire);
            assertTrue(wire.contains("script=\"nightly-report\" version=2 outcome=SUCCESS exit_code=0 duration_ms=845 run_id=job-udp-1"), wire);
            assertFalse(wire.contains(outputSecretLine), "syslog frame must never carry script output");
            assertEquals(ScriptRunNotification.SENT, db.syslogState("job-udp-1"));
        }

        // 2. TCP octet-counted RFC 5424 check
        try (ServerSocket tcpServer = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            tcpServer.setSoTimeout(5000);
            List<String> receivedTcp = new CopyOnWriteArrayList<>();
            CountDownLatch accepted = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try (Socket client = tcpServer.accept()) {
                    receivedTcp.add(new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                } finally {
                    accepted.countDown();
                }
            });
            reader.setDaemon(true);
            reader.start();

            NotificationMockDb db = new NotificationMockDb();
            db.addFinishedRun("job-tcp-1", "asa-dr-check", 5, "FAILED", "FAILURE", 3, 1920L,
                    "2026-11-24 10:01:00", outputSecretLine, "syslog");

            NotificationSettingsStore store = fixedSettings(new NotificationSettings(
                    true, "127.0.0.1", tcpServer.getLocalPort(), "tcp", 16,
                    false, null, 25, false, null, null,
                    false, false, FIXED_NOW, List.of(), NotificationSettings.IDENTITY_RAW));

            new NotificationForwarder(store, db.directBoundary(), null, null).tick();
            assertTrue(accepted.await(5, TimeUnit.SECONDS));

            assertEquals(1, receivedTcp.size());
            String tcpFrame = receivedTcp.get(0);
            // Facility 16 * 8 + ERROR (3) = PRI 131; octet-counted "<len> <131>1 ..."
            assertTrue(tcpFrame.matches("^\\d+ <131>1 .+ nexus - SCRIPT_RUN - .*"), tcpFrame);
            assertTrue(tcpFrame.contains("outcome=FAILURE exit_code=3 duration_ms=1920 run_id=job-tcp-1"), tcpFrame);
            assertFalse(tcpFrame.contains(outputSecretLine));
        }
    }

    /**
     * Clause S3.5b: {@link NotificationForwarder#tick()} delivers one MIME mail over SMTP per finished run (or skips
     * {@code SUCCESS} runs with {@code failure_only} when the script specifies {@code smtp_mail_on_failure}), carrying
     * script name, version, outcome, exit code, duration, run id, and masked output.
     */
    @Test
    void s3_5b_smtpRelayDeliversRunMailAndRespectsMailOnFailureOnly() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            NotificationMockDb db = new NotificationMockDb();
            db.addFinishedRun("job-mail-skip", "daily-sync", 1, "COMPLETED", "SUCCESS", 0, 400L,
                    "2026-11-24 10:02:00", "all green", "smtp_mail_on_failure");
            db.addFinishedRun("job-mail-send", "daily-sync", 1, "FAILED", "FAILURE", 9, 750L,
                    "2026-11-24 10:03:00", "checked FW-TANGO-04: exit 9", "smtp_mail_on_failure");

            NotificationSettingsStore store = fixedSettings(new NotificationSettings(
                    false, null, 514, "udp", 16,
                    true, "127.0.0.1", smtp.port(), false, "overwatch@example.invalid", "netops@example.invalid",
                    false, false, FIXED_NOW, List.of(), NotificationSettings.IDENTITY_RAW));

            new NotificationForwarder(store, db.directBoundary(), null, null).tick();

            assertEquals(ScriptRunNotification.SKIPPED, db.smtpState("job-mail-skip"));
            assertEquals(ScriptRunNotification.CODE_FAILURE_ONLY, db.smtpCode("job-mail-skip"));
            assertEquals(ScriptRunNotification.SENT, db.smtpState("job-mail-send"));

            assertEquals(1, smtp.messages.size(), "only the failed run mails under smtp_mail_on_failure");
            String rawData = smtp.messages.get(0);
            assertTrue(rawData.contains("daily-sync"), rawData);
            assertTrue(rawData.contains("job-mail-send"), rawData);
            assertTrue(rawData.contains("checked FW-TANGO-04: exit 9"), rawData);
        }
    }

    /**
     * Clause S3.5c (FROZEN contract section 3.5): "one mail per run end (or 'on failure only' per script) with the same
     * fields and the first 4 KiB of masked output."
     *
     * <p>Contradiction found: {@code ScriptOutputMasking#maskThenTruncate} populates {@code script_run.output_tail} with
     * {@code tailUtf8Bytes(..., 4096)} (the LAST 4 KiB, for section 3.3's inline Jobs-screen preview), and
     * {@code ScriptRunNotifier} mails {@code script_run.output_tail} without storing or reading the first 4 KiB of
     * masked output.</p>
     */
    @Disabled("S3.5c: ScriptRunNotifier mails script_run.output_tail (last 4 KiB from tailUtf8Bytes) instead of the first 4 KiB of masked output when output exceeds 4 KiB")
    @Test
    void s3_5c_smtpMailCarriesFirst4KibOfMaskedOutputWhenOutputExceeds4Kib() throws Exception {
        FileArtefactStore store = newArtefactStore(tempDir.resolve("s3-5c-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        ScriptExecutionService service = new ScriptExecutionService(repo, credRefs(), null, store, FIXED_CLOCK, 65536);
        String scriptId = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "mail-head", "head.sh", "sh", "echo 1\n".getBytes(StandardCharsets.UTF_8),
                null, true, 60, List.of(), List.of(), "smtp", RoleToken.OPERATOR), "secadmin"))
                .details().script().scriptId();
        String jobId = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run().jobId();
        repo.jobState = "EXECUTING";
        repo.leaseEpoch = 1L;
        repo.leaseWorker = "script_runner-1";

        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(
                repo, emptyCredentials(), mock(SessionRepository.class), store, FIXED_CLOCK);
        dispatch.start(jobId, 1L);

        String first4KibBanner = "FIRST_4KIB_DIAGNOSTIC_HEADER";
        String last4KibTrailer = "LAST_4KIB_DIAGNOSTIC_TRAILER";
        String fullStdout = first4KibBanner + "\n" + ("0123456789abcdef-padding-line\n".repeat(220)) + last4KibTrailer + "\n";
        dispatch.finish(jobId, 1L, new ScriptRunDispatchService.FinishReport(
                "FAILURE", 1, 300L, fullStdout, "", false, false, "EXIT_CODE_1"));

        ScriptRunRecord completed = repo.findRunByJobId(jobId).orElseThrow();
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            NotificationMockDb db = new NotificationMockDb();
            db.addFinishedRun(jobId, "mail-head", 1, "FAILED", "FAILURE", 1, 300L,
                    "2026-11-24 10:05:00", completed.outputTail().orElse(""), "smtp");
            NotificationSettingsStore settingsStore = fixedSettings(new NotificationSettings(
                    false, null, 514, "udp", 16,
                    true, "127.0.0.1", smtp.port(), false, "overwatch@example.invalid", "netops@example.invalid",
                    false, false, FIXED_NOW, List.of(), NotificationSettings.IDENTITY_RAW));

            new NotificationForwarder(settingsStore, db.directBoundary(), null, null).tick();
            assertEquals(1, smtp.messages.size());
            assertTrue(smtp.messages.get(0).contains(first4KibBanner),
                    "Contract section 3.5 requires the mail to include the first 4 KiB of masked output");
        }
    }

    /**
     * Clause S3.5d: notification failures (both syslog and SMTP unreachable) are recorded on {@code script_run_notification}
     * with {@code FAILED / send_failed} and joined onto {@link JooqScriptRepository#findRunByJobId} without failing or
     * mutating a {@code COMPLETED / SUCCESS} run.
     */
    @Test
    void s3_5_notificationFailureRecordedWithoutFailingCompletedRun() throws Exception {
        // Reserve and immediately close an ephemeral TCP port so both TCP syslog and SMTP connect attempts fail fast.
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = ss.getLocalPort();
        }

        NotificationMockDb db = new NotificationMockDb();
        db.addFinishedRun("job-ok-notify-fail", "ok-script", 2, "COMPLETED", "SUCCESS", 0, 120L,
                "2026-11-24 10:10:00", "ok", "both");

        NotificationSettingsStore settingsStore = fixedSettings(new NotificationSettings(
                true, "127.0.0.1", closedPort, "tcp", 16,
                true, "127.0.0.1", closedPort, false, "overwatch@example.invalid", "netops@example.invalid",
                false, false, FIXED_NOW, List.of(), NotificationSettings.IDENTITY_RAW));

        new NotificationForwarder(settingsStore, db.directBoundary(), null, null).tick();

        assertTrue(db.executedSql.stream().noneMatch(sql -> sql.contains("update script_run")),
                "notification failure must never update script_run");

        JooqScriptRepository jooqRepo = new JooqScriptRepository(new JooqTransactionBoundary(db.dsl));
        ScriptRunRecord run = jooqRepo.findRunByJobId("job-ok-notify-fail").orElseThrow();
        assertEquals("COMPLETED", run.state());
        assertEquals(Optional.of("SUCCESS"), run.outcome());
        ScriptRunNotification notif = run.notification().orElseThrow();
        assertEquals(ScriptRunNotification.FAILED, notif.syslog());
        assertEquals(Optional.of(ScriptRunNotification.CODE_SEND_FAILED), notif.syslogCode());
        assertEquals(ScriptRunNotification.FAILED, notif.smtp());
        assertEquals(Optional.of(ScriptRunNotification.CODE_SEND_FAILED), notif.smtpCode());
    }

    /**
     * Clause S3.6b (FROZEN contract section 3.6): "Operations > Script Execution: list (name, kind, version, schedule,
     * last run outcome, next fire)".
     *
     * <p>Contradiction found: {@code GET /api/v2/scripts} ({@code ScriptExecutionController#toScriptMap}) and
     * {@code ScriptExecutionPanel.tsx} omit {@code last_run_outcome} from script list rows.</p>
     */
    @Disabled("S3.6b: GET /api/v2/scripts in ScriptExecutionController#toScriptMap and ScriptExecutionPanel list table omit last_run_outcome")
    @Test
    void s3_6b_scriptListIncludesLastRunOutcome() {
        FileArtefactStore store = newArtefactStore(tempDir.resolve("s3-6b-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        ScriptExecutionService service = new ScriptExecutionService(repo, credRefs(), null, store, FIXED_CLOCK, 65536);
        ScriptExecutionController controller = new ScriptExecutionController(service, null, null);
        MockHttpServletRequest adminReq = authedAdminRequest("secadmin");

        var created = controller.create(new CreateScriptRequest(
                "list-outcome-check", "check.sh", "sh", "echo ok\n", null, "0 2 * * *", true, 60,
                List.of(), List.of(), "none", RoleToken.OPERATOR), adminReq);
        String scriptId = (String) created.getBody().get("script_id");
        String jobId = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run().jobId();
        repo.completeRun(jobId, "COMPLETED", "SUCCESS", FIXED_NOW.plusSeconds(2), 2000L, 0,
                3, 0, false, false, Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.empty(),
                Optional.of("ok"), List.of(), List.of(), Optional.empty(), "system:script_runner");

        var listRes = controller.list(adminReq);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scripts = (List<Map<String, Object>>) listRes.getBody().get("scripts");
        assertEquals("SUCCESS", scripts.get(0).get("last_run_outcome"));
    }

    /**
     * Clause S3.6d: {@code ActionRegistry.SCRIPT_READ} has the exact same role requirement set as
     * {@code ActionRegistry.JOB_LOG_READ}; {@code SCRIPT_WRITE} requires {@code role:security_admin}; and
     * {@code SCRIPT_RUN} + {@code SCRIPT_OUTPUT_READ} require {@code role:operator} or above.
     */
    @Test
    void s3_6d_scriptReadMatchesJobLogReadAndRoleMatrixHolds() {
        ActionRegistry registry = new ActionRegistry();
        var jobLogRead = registry.find(ActionRegistry.JOB_LOG_READ).orElseThrow();
        var scriptRead = registry.find(ActionRegistry.SCRIPT_READ).orElseThrow();
        var scriptWrite = registry.find(ActionRegistry.SCRIPT_WRITE).orElseThrow();
        var scriptRun = registry.find(ActionRegistry.SCRIPT_RUN).orElseThrow();
        var scriptOutputRead = registry.find(ActionRegistry.SCRIPT_OUTPUT_READ).orElseThrow();

        assertEquals(jobLogRead.requiredRoleTokens(), scriptRead.requiredRoleTokens(),
                "contract 3.6: read is open to everyone with job_log_read");
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN), scriptWrite.requiredRoleTokens());
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                scriptRun.requiredRoleTokens());
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.SECURITY_ADMIN),
                scriptOutputRead.requiredRoleTokens());
    }

    /**
     * Clause B-4: {@link ScriptRunnerInternalController} answers 404 when {@code UI2_SCRIPT_RUNNER_TOKEN_SHA256} is
     * unset or malformed, 403 {@code SCRIPT_RUNNER_TOKEN_REFUSED} when {@code X-Nexus-Script-Runner-Token} is missing or
     * wrong, 413 on oversized finish payloads, and dispatches valid {@code start}, {@code finish}, and {@code abort}
     * calls once authenticated by constant-time SHA-256 digest comparison.
     */
    @Test
    void b4_internalControllerEnforcesConstantTimeSha256TokenAndDispatchesStartFinishAbort() throws Exception {
        FileArtefactStore store = newArtefactStore(tempDir.resolve("b4-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        ScriptExecutionService service = new ScriptExecutionService(repo, credRefs(), null, store, FIXED_CLOCK, 65536);
        String scriptId = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "internal-pin", "int.sh", "sh", "echo internal\n".getBytes(StandardCharsets.UTF_8),
                null, true, 60, List.of("192.0.2.15:443"), List.of(), "none", RoleToken.OPERATOR), "secadmin"))
                .details().script().scriptId();
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(
                repo, emptyCredentials(), mock(SessionRepository.class), store, FIXED_CLOCK);

        // 1. Unset or malformed digest -> 404 on every route.
        for (String badDigest : List.of("", "not-a-sha256", "A".repeat(64))) {
            ScriptRunnerInternalController unconfigured = new ScriptRunnerInternalController(badDigest, dispatch);
            assertFalse(unconfigured.isTokenConfigured());
            assertEquals(404, unconfigured.start("any", jsonPost(ScriptRunnerInternalController.START, "{}")).getStatusCode().value());
            assertEquals(404, unconfigured.finish("any", jsonPost(ScriptRunnerInternalController.FINISH, "{}")).getStatusCode().value());
            assertEquals(404, unconfigured.abort("any", jsonPost(ScriptRunnerInternalController.ABORT, "{}")).getStatusCode().value());
        }

        // 2. Valid SHA-256 digest -> missing/wrong token is 403; valid token dispatches start, finish, and abort.
        String rawToken = "runner_tok_" + runtimeSecret("b4");
        String sha256Hex = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        ScriptRunnerInternalController internal = new ScriptRunnerInternalController(sha256Hex, dispatch);
        assertTrue(internal.isTokenConfigured());

        assertEquals(403, internal.start(null, jsonPost(ScriptRunnerInternalController.START, "{}")).getStatusCode().value());
        assertEquals(403, internal.start(rawToken + "-wrong", jsonPost(ScriptRunnerInternalController.START, "{}")).getStatusCode().value());

        // Malformed job_id rejected with 400.
        assertEquals(400, internal.start(rawToken, jsonPost(ScriptRunnerInternalController.START,
                "{\"job_id\":\"bad/id\",\"lease_epoch\":1}")).getStatusCode().value());

        // Valid start + finish
        String job1 = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run().jobId();
        repo.jobState = "EXECUTING";
        repo.leaseEpoch = 4L;
        repo.leaseWorker = "script_runner-a";

        var startResp = internal.start(rawToken, jsonPost(ScriptRunnerInternalController.START,
                "{\"job_id\":\"" + job1 + "\",\"lease_epoch\":4}"));
        assertEquals(200, startResp.getStatusCode().value());
        assertEquals("echo internal\n", new String(
                Base64.getDecoder().decode((String) startResp.getBody().get("body_base64")), StandardCharsets.UTF_8));

        // Oversized finish content-length -> 413.
        MockHttpServletRequest hugeReq = new MockHttpServletRequest("POST", ScriptRunnerInternalController.FINISH) {
            @Override
            public long getContentLengthLong() {
                return 13L * 1024 * 1024;
            }
        };
        assertEquals(413, internal.finish(rawToken, hugeReq).getStatusCode().value());

        var finishResp = internal.finish(rawToken, jsonPost(ScriptRunnerInternalController.FINISH,
                "{\"job_id\":\"" + job1 + "\",\"lease_epoch\":4,\"outcome\":\"SUCCESS\",\"exit_code\":0,"
                        + "\"duration_ms\":55,\"stdout\":\"ok\\n\",\"stderr\":\"\",\"stdout_cut\":false,\"stderr_cut\":false}"));
        assertEquals(204, finishResp.getStatusCode().value());

        // Valid start + abort on second run
        String job2 = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run().jobId();
        repo.leaseEpoch = 5L;
        assertEquals(200, internal.start(rawToken, jsonPost(ScriptRunnerInternalController.START,
                "{\"job_id\":\"" + job2 + "\",\"lease_epoch\":5}")).getStatusCode().value());
        assertEquals(204, internal.abort(rawToken, jsonPost(ScriptRunnerInternalController.ABORT,
                "{\"job_id\":\"" + job2 + "\",\"lease_epoch\":5}")).getStatusCode().value());
    }

    /**
     * Clause B-5: {@code ui2-service} reports {@code runner_available = false} until a valid 64-char lowercase hex
     * SHA-256 token digest is configured AND the broker module heartbeat is fresh, failing closed on DB exceptions.
     */
    @Test
    void b5_runnerAvailableRequiresValidTokenDigestAndFreshHeartbeat() {
        assertTrue(ScriptRunnerInternalController.isValidTokenDigest("a0b1c2d3".repeat(8)));
        assertFalse(ScriptRunnerInternalController.isValidTokenDigest(null));
        assertFalse(ScriptRunnerInternalController.isValidTokenDigest(""));
        assertFalse(ScriptRunnerInternalController.isValidTokenDigest("A0B1C2D3".repeat(8)), "uppercase hex rejected");
        assertFalse(ScriptRunnerInternalController.isValidTokenDigest("a".repeat(63)));

        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        ScriptExecutionService service = new ScriptExecutionService(
                repo, credRefs(), null, newArtefactStore(tempDir.resolve("b5-store")), FIXED_CLOCK, 65536);

        repo.heartbeatAvailable = true;
        service.useRunnerTokenConfigured(() -> ScriptRunnerInternalController.isValidTokenDigest(""));
        assertFalse(service.runnerAvailable(), "missing token digest must keep runner_available false");

        service.useRunnerTokenConfigured(() -> ScriptRunnerInternalController.isValidTokenDigest("01234567".repeat(8)));
        assertTrue(service.runnerAvailable());

        repo.heartbeatAvailable = false;
        assertFalse(service.runnerAvailable(), "stale/absent broker heartbeat must report runner_available false");

        repo.throwOnHeartbeat = true;
        assertFalse(service.runnerAvailable(), "repository error must fail closed as runner_available false");
    }

    /**
     * Clause B-7: after lease loss aborts epoch {@code N}, the run remains {@code RUNNING} (not closed under the stale
     * lease), and when epoch {@code N + 1} claims the job, {@link ScriptRunDispatchService#start} cleanly dispatches
     * under the new epoch and mints a fresh session.
     */
    @Test
    void b7_lostLeaseAbortLeavesRunOpenForHigherEpochRedispatch() {
        FileArtefactStore store = newArtefactStore(tempDir.resolve("b7-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        ScriptExecutionService service = new ScriptExecutionService(repo, credRefs(), null, store, FIXED_CLOCK, 65536);
        String scriptId = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "lease-reclaim", "reclaim.sh", "sh", "echo reclaim\n".getBytes(StandardCharsets.UTF_8),
                null, true, 60, List.of(), List.of(), "none", RoleToken.OPERATOR), "secadmin"))
                .details().script().scriptId();
        String jobId = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run().jobId();

        Map<String, SessionRecord> sessionTable = new LinkedHashMap<>();
        SessionRepository sessions = inMemorySessionRepository(sessionTable);
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(
                repo, emptyCredentials(), sessions, store, Clock.systemUTC());

        // Epoch 1 starts, then loses lease and aborts.
        repo.jobState = "EXECUTING";
        repo.leaseEpoch = 1L;
        repo.leaseWorker = "script_runner-old";
        var start1 = assertInstanceOf(ScriptRunDispatchService.StartOutcome.Started.class, dispatch.start(jobId, 1L));
        String sid1 = SessionHasher.hash(start1.bundle().sessionCookie());
        assertEquals(SessionState.ACTIVE, sessionTable.get(sid1).state());

        assertTrue(dispatch.abort(jobId, 1L).isEmpty());
        assertEquals(SessionState.REVOKED, sessionTable.get(sid1).state());
        assertEquals("RUNNING", repo.findRunByJobId(jobId).orElseThrow().state(), "aborted epoch leaves run open for retry");

        // Stale epoch 1 cannot finish once epoch 2 owns the lease.
        repo.leaseEpoch = 2L;
        repo.leaseWorker = "script_runner-new";
        assertEquals("SCRIPT_RUN_LEASE_MISMATCH", dispatch.finish(jobId, 1L,
                new ScriptRunDispatchService.FinishReport("SUCCESS", 0, 10L, "", "", false, false, null))
                .orElseThrow().code());

        // Epoch 2 dispatches cleanly, gets a fresh session, and closes the run.
        var start2 = assertInstanceOf(ScriptRunDispatchService.StartOutcome.Started.class, dispatch.start(jobId, 2L));
        String sid2 = SessionHasher.hash(start2.bundle().sessionCookie());
        assertFalse(sid1.equals(sid2));
        assertEquals(SessionState.ACTIVE, sessionTable.get(sid2).state());
        assertTrue(dispatch.finish(jobId, 2L,
                new ScriptRunDispatchService.FinishReport("SUCCESS", 0, 20L, "done\n", "", false, false, null)).isEmpty());
        assertEquals("COMPLETED", repo.findRunByJobId(jobId).orElseThrow().state());
        assertEquals(SessionState.REVOKED, sessionTable.get(sid2).state());
    }

    /**
     * Clause B-9: {@code run_as} defaults to {@code role:operator} when omitted/blank, {@code updateSettings} refuses
     * {@code role:security_admin} and user names with {@code INVALID_RUN_AS}, and {@code V258} defines the exact
     * {@code ('role:operator', 'role:viewer', 'role:replay_viewer')} CHECK on both {@code script} and {@code script_run}.
     */
    @Test
    void b9_runAsDefaultsToOperatorRejectsForbiddenValuesOnPatchAndMatchesV258Check() throws Exception {
        FileArtefactStore store = newArtefactStore(tempDir.resolve("b9-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        ScriptExecutionService service = new ScriptExecutionService(repo, credRefs(), null, store, FIXED_CLOCK, 65536);

        var created = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "runas-pin", "ra.sh", "sh", "echo 1\n".getBytes(StandardCharsets.UTF_8),
                null, false, 60, List.of(), List.of(), "none", "   "), "secadmin")).details();
        assertEquals(RoleToken.OPERATOR, created.script().runAs());

        for (String forbidden : List.of("role:security_admin", "security_admin", "alice", "role:backup_admin", "")) {
            Outcome.Refused refused = assertInstanceOf(Outcome.Refused.class, service.updateSettings(
                    created.script().scriptId(),
                    new UpdateSettingsCommand(Optional.empty(), false, Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(forbidden)),
                    "secadmin"));
            assertEquals("INVALID_RUN_AS", refused.code(), forbidden);
        }

        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/V258__script_execution_slice2.sql")) {
            assertNotNull(in);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("run_as                  TEXT        NOT NULL CHECK (run_as IN ('role:operator', 'role:viewer', 'role:replay_viewer'))"));
            assertTrue(sql.contains("ADD CONSTRAINT chk_script_run_as_allowlist"));
            assertTrue(sql.contains("CHECK (run_as IN ('role:operator', 'role:viewer', 'role:replay_viewer'))"));
        }
    }

    /**
     * Clause B-10: wiring {@link ScriptExecutionService#useCredentialSource} surfaces {@code _2}/{@code _3} alias
     * collision warnings at save/read time (never a refusal), and a run admitted at version 1 injects ONLY version 1's
     * {@code credential_refs} even after settings create version 2 with new refs.
     */
    @Test
    void b10_credentialRefAliasCollisionWarnsAtSaveAndRunUsesExecutedVersionRefs() {
        FileArtefactStore store = newArtefactStore(tempDir.resolve("b10-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        Set<String> validRefs = Set.of("ref-1", "ref-2", "ref-3");
        ScriptExecutionService service = new ScriptExecutionService(
                repo, credRefs(validRefs.toArray(String[]::new)), null, store, FIXED_CLOCK, 65536);

        String sec1 = runtimeSecret("c1");
        String sec2 = runtimeSecret("c2");
        String sec3 = runtimeSecret("c3");
        ScriptCredentialSource credSource = new ScriptCredentialSource() {
            private final Map<String, Resolved> map = Map.of(
                    "ref-1", new Resolved("ref-1", "Ops SSH", "u1", sec1),
                    "ref-2", new Resolved("ref-2", "ops-ssh", "u2", sec2),
                    "ref-3", new Resolved("ref-3", "OPS.SSH", "u3", sec3));

            @Override public Optional<String> displayName(String id) { return Optional.ofNullable(map.get(id)).map(Resolved::displayName); }
            @Override public Optional<Resolved> resolve(String id) { return Optional.ofNullable(map.get(id)); }
        };
        service.useCredentialSource(credSource);

        var created = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "alias-pin", "a.sh", "sh", "echo 1\n".getBytes(StandardCharsets.UTF_8),
                null, true, 60, List.of(), List.of("ref-1", "ref-2"), "none", RoleToken.OPERATOR), "secadmin")).details();
        assertTrue(created.warnings().stream().anyMatch(w -> w.contains("NEXUS_CRED_OPS_SSH_2_USERNAME")));

        // Admit a run while script is at v1 (refs: ref-1, ref-2).
        String scriptId = created.script().scriptId();
        ScriptRunRecord runV1 = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run();
        assertEquals(1, runV1.version());

        // Now update credential_refs in settings -> creates v2 with ref-3 (and reuses v1's encrypted body).
        var updated = ((Outcome.Ok) service.updateSettings(scriptId, new UpdateSettingsCommand(
                Optional.empty(), false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(List.of("ref-1", "ref-2", "ref-3")), Optional.empty(), Optional.empty()), "secadmin")).details();
        assertEquals(2, updated.script().currentVersion());
        assertTrue(updated.warnings().stream().anyMatch(w -> w.contains("NEXUS_CRED_OPS_SSH_3_USERNAME")));

        // Dispatching the v1 run MUST inject v1's refs (ref-1, ref-2) only — NEVER v2's ref-3!
        repo.jobState = "EXECUTING";
        repo.leaseEpoch = 1L;
        repo.leaseWorker = "script_runner-1";
        ScriptRunDispatchService dispatch = new ScriptRunDispatchService(
                repo, credSource, mock(SessionRepository.class), store, FIXED_CLOCK);
        var bundle = assertInstanceOf(ScriptRunDispatchService.StartOutcome.Started.class, dispatch.start(runV1.jobId(), 1L)).bundle();
        assertEquals(1, bundle.version());
        assertEquals(List.of("ref-1", "ref-2"), bundle.credentials().stream().map(c -> c.refId()).toList());
        assertEquals(List.of("OPS_SSH", "OPS_SSH_2"), bundle.credentials().stream().map(c -> c.alias()).toList());
    }

    /**
     * Clause B-11: {@code GET /api/v2/scripts/runs/{runId}/output} and {@code GET /api/v2/scripts/runs/{runId}} (inline
     * {@code output_tail}) write {@code audit_script_output_download} strictly BEFORE opening/reading the output stream,
     * and fail closed with HTTP 500 {@code SCRIPT_OUTPUT_AUDIT_FAILED} and zero artefact reads when auditing fails.
     */
    @Test
    void b11_outputDownloadWritesAuditBeforeStreaming() throws Exception {
        List<String> callOrder = new ArrayList<>();
        AtomicBoolean failAudit = new AtomicBoolean(false);

        FileArtefactStore delegateStore = newArtefactStore(tempDir.resolve("b11-store"));
        ArtefactStore orderedStore = new ArtefactStore() {
            @Override
            public ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) throws IOException {
                return delegateStore.open(deviceId, jobId, vendor, gzip);
            }

            @Override
            public InputStream retrieve(ArtefactRef ref, byte[] wrappedDataKey, boolean gunzip) throws IOException {
                callOrder.add("retrieve_first_byte");
                return delegateStore.retrieve(ref, wrappedDataKey, gunzip);
            }

            @Override
            public boolean delete(ArtefactRef ref) throws IOException {
                return delegateStore.delete(ref);
            }
        };

        InMemoryScriptRepo repo = new InMemoryScriptRepo() {
            @Override
            public void auditOutputDownload(String jobId, String actorFingerprint) {
                callOrder.add("audit:" + jobId + ":" + actorFingerprint);
                if (failAudit.get()) {
                    throw new IllegalStateException("simulated audit failure");
                }
                super.auditOutputDownload(jobId, actorFingerprint);
            }
        };

        ScriptExecutionService service = new ScriptExecutionService(repo, credRefs(), null, orderedStore, FIXED_CLOCK, 65536);
        RbacEvaluator rbac = new RbacEvaluator(
                mock(RoleBindingRepository.class), mock(ActorAuthzStateRepository.class), null);
        ScriptExecutionController controller = new ScriptExecutionController(
                service, Optional.empty(), Optional.empty(),
                Optional.of(new ActionRegistry()), Optional.of(rbac), Optional.of(mock(AuthzDecisionRepository.class)));

        String scriptId = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "audit-order", "ord.sh", "sh", "echo 1\n".getBytes(StandardCharsets.UTF_8),
                null, true, 60, List.of(), List.of(), "none", RoleToken.OPERATOR), "secadmin"))
                .details().script().scriptId();
        String jobId = ((RunOutcome.RunSubmitted) service.runNow(scriptId, "op-1")).run().jobId();

        ArtefactStore.ArtefactMetadata outMeta;
        try (ArtefactStore.ArtefactHandle h = delegateStore.open(scriptId, jobId, ArtefactClass.SCRIPT_OUTPUT, false)) {
            h.sink().write("=== STDOUT ===\nstreamed\n=== STDERR ===\n".getBytes(StandardCharsets.UTF_8));
            outMeta = h.finish();
        }
        repo.completeRun(jobId, "COMPLETED", "SUCCESS", FIXED_NOW.plusSeconds(1), 1000L, 0,
                8, 0, false, false, Optional.of(outMeta.ref().value()), Optional.of(outMeta.keyId()),
                outMeta.wrappedDataKey(), Optional.of(outMeta.plaintextSha256()), Optional.of(outMeta.plaintextBytes()),
                Optional.of("streamed"), List.of(), List.of(), Optional.empty(), "system:script_runner");

        String opActor = ScriptExecutionJob.formatSessionActor(scriptId, "sess-op", RoleToken.OPERATOR);
        MockHttpServletRequest opReq = new MockHttpServletRequest("GET", "/api/v2/scripts/runs/" + jobId + "/output");
        opReq.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, opActor);
        opReq.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "sess-1");

        // 1. Happy path: audit happens strictly BEFORE ArtefactStore#retrieve opens the stream.
        var okDownload = controller.downloadRunOutput(jobId, opReq);
        assertEquals(200, okDownload.getStatusCode().value());
        assertEquals(List.of("audit:" + jobId + ":" + opActor, "retrieve_first_byte"), callOrder);

        // 2. Fail-closed: if audit throws, download returns 500 SCRIPT_OUTPUT_AUDIT_FAILED and NEVER calls retrieve,
        // and getRun also returns 500 without exposing output_tail.
        callOrder.clear();
        failAudit.set(true);
        var failedDownload = controller.downloadRunOutput(jobId, opReq);
        assertEquals(500, failedDownload.getStatusCode().value());
        assertEquals(List.of("audit:" + jobId + ":" + opActor), callOrder, "retrieve must not run when audit fails");

        var failedGetRun = controller.getRun(jobId, opReq);
        assertEquals(500, failedGetRun.getStatusCode().value());
        assertNull(failedGetRun.getBody().get("output_tail"));
    }

    /**
     * Clauses B-12, S3.4a: {@link ScriptScheduler} has a 60-second {@code @Scheduled} tick, evaluates five-field cron in
     * {@code Europe/Istanbul} (UTC+3) by default, honours {@link ScriptSchedule#zoneOf} overrides while falling back to
     * {@code Europe/Istanbul} on blank/invalid values, enforces the 1-hour (3600 s) catch-up window, and does not replay
     * missed nightly runs after a multi-day outage.
     */
    @Test
    void b12_schedulerUsesEuropeIstanbulAndOneHourCatchUp() throws Exception {
        Scheduled tickAnn = ScriptScheduler.class.getMethod("tick").getAnnotation(Scheduled.class);
        assertNotNull(tickAnn, "ScriptScheduler#tick must carry @Scheduled");
        assertEquals("${ui2.script.scheduler.interval-ms:60000}", tickAnn.fixedDelayString());
        assertEquals(3600L, ScriptScheduler.CATCH_UP_WINDOW_SECONDS);

        assertEquals(ZoneId.of("Europe/Istanbul"), ScriptSchedule.zoneOf(null));
        assertEquals(ZoneId.of("Europe/Istanbul"), ScriptSchedule.zoneOf("   "));
        assertEquals(ZoneId.of("Europe/Istanbul"), ScriptSchedule.zoneOf("Not/AZone"));
        assertEquals(ZoneId.of("UTC"), ScriptSchedule.zoneOf("UTC"));

        // 02:00 Europe/Istanbul (UTC+3) == 23:00Z the preceding UTC date.
        // At 2026-11-24T23:59:59Z (59m59s after 02:00 Istanbul on 2026-11-25), the slot is within 1h catch-up -> fires 1.
        Instant withinOneHour = Instant.parse("2026-11-24T23:59:59Z");
        FileArtefactStore store = newArtefactStore(tempDir.resolve("b12-store"));
        InMemoryScriptRepo repo = new InMemoryScriptRepo();
        ScriptExecutionService service = new ScriptExecutionService(
                repo, credRefs(), null, store, Clock.fixed(withinOneHour, ZoneOffset.UTC), 65536);
        ScriptScheduler istanbulScheduler = new ScriptScheduler(
                repo, service, Clock.fixed(withinOneHour, ZoneOffset.UTC), ScriptSchedule.DEFAULT_ZONE);

        var dueIstanbul = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "istanbul-2am", "ist.sh", "sh", "echo 1\n".getBytes(StandardCharsets.UTF_8),
                "0 2 * * *", true, 60, List.of(), List.of(), "none", RoleToken.OPERATOR), "secadmin")).details().script();

        // Script scheduled at 00:59 Istanbul (21:59Z -> 2h00m59s ago, outside 1h catch-up) -> must NOT fire.
        var staleIstanbul = ((Outcome.Ok) service.create(new CreateScriptCommand(
                "istanbul-stale", "stale.sh", "sh", "echo 1\n".getBytes(StandardCharsets.UTF_8),
                "59 0 * * *", true, 60, List.of(), List.of(), "none", RoleToken.OPERATOR), "secadmin")).details().script();

        istanbulScheduler.tick();
        List<ScriptRunRecord> fired = repo.findRunsByScriptId(dueIstanbul.scriptId(), 10);
        assertEquals(1, fired.size());
        assertEquals(Optional.of(Instant.parse("2026-11-24T23:00:00Z")), fired.get(0).scheduledSlot());
        assertEquals(0, repo.findRunsByScriptId(staleIstanbul.scriptId(), 10).size());

        // Simulate a 6-day outage on istanbul-2am: jump 6 days + 30 minutes forward (2026-11-30T23:30:00Z).
        // Six nightly fires were missed; catch-up must fire at most ONCE for the single recent slot, never 6 times!
        Instant afterSixDayOutage = Instant.parse("2026-11-30T23:30:00Z");
        assertEquals(1, istanbulScheduler.evaluateAt(afterSixDayOutage),
                "after a 6-day outage, catch-up window must fire only the one due slot within the last hour");
        assertEquals(2, repo.findRunsByScriptId(dueIstanbul.scriptId(), 10).size());
        assertEquals(0, istanbulScheduler.evaluateAt(afterSixDayOutage.plusSeconds(60)));
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Self-contained test doubles
    // -----------------------------------------------------------------------------------------------------------------

    private static ScriptCredentialSource emptyCredentials() {
        return new ScriptCredentialSource() {
            @Override public Optional<String> displayName(String refId) { return Optional.empty(); }
            @Override public Optional<Resolved> resolve(String refId) { return Optional.empty(); }
        };
    }

    private static MockHttpServletRequest cliRequest(String method, String path, String cookie, String csrf) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setServletPath(path);
        req.setCookies(new Cookie("ui2_session", cookie));
        req.addHeader("X-CSRF-Token", csrf);
        req.addHeader("Origin", "http://ui2-service:8080");
        return req;
    }

    private static MockHttpServletRequest jsonPost(String path, String jsonBody) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        req.setContentType("application/json");
        req.setContent(jsonBody.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    private static SessionRepository inMemorySessionRepository(Map<String, SessionRecord> store) {
        SessionRepository sessions = mock(SessionRepository.class);
        org.mockito.Mockito.doAnswer(inv -> {
            String sid = inv.getArgument(0);
            String actor = inv.getArgument(1);
            String csrf = inv.getArgument(2);
            Instant now = inv.getArgument(3);
            Duration idle = inv.getArgument(4);
            Duration abs = inv.getArgument(5);
            store.put(sid, new SessionRecord(sid, actor, csrf, SessionState.ACTIVE, now, now,
                    now.plus(idle), now.plus(abs), Optional.empty(), Optional.empty(), Optional.empty(), false));
            return null;
        }).when(sessions).createActive(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());

        org.mockito.Mockito.when(sessions.findBySessionId(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));

        org.mockito.Mockito.doAnswer(inv -> {
            String sid = inv.getArgument(0);
            SessionRecord cur = store.get(sid);
            if (cur != null) {
                store.put(sid, new SessionRecord(cur.sessionId(), cur.actorFingerprint(), cur.csrfSecret(),
                        SessionState.REVOKED, cur.createdAt(), cur.lastSeenAt(), cur.idleDeadlineAt(),
                        cur.absoluteExpiresAt(), Optional.empty(), Optional.of(inv.getArgument(1)),
                        Optional.empty(), cur.machine()));
            }
            return null;
        }).when(sessions).revoke(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        return sessions;
    }

    private static class InMemoryScriptRepo implements ScriptRepository {
        final Map<String, ScriptRecord> scripts = new LinkedHashMap<>();
        final Map<String, List<ScriptVersionRecord>> versions = new LinkedHashMap<>();
        final Map<String, ScriptRunRecord> runs = new LinkedHashMap<>();
        final Map<String, Instant> lastScheduledSlots = new LinkedHashMap<>();
        final Map<String, Long> dispatchEpochs = new LinkedHashMap<>();
        final Map<String, String> runSessionIds = new LinkedHashMap<>();
        final List<String> downloadAudits = new ArrayList<>();
        String jobState = "EXECUTING";
        long leaseEpoch = 1L;
        String leaseWorker = "script_runner-test";
        boolean heartbeatAvailable = true;
        boolean throwOnHeartbeat;

        @Override
        public boolean runnerAvailable() {
            if (throwOnHeartbeat) {
                throw new IllegalStateException("db down");
            }
            return heartbeatAvailable;
        }

        @Override
        public List<ScriptRecord> findAll() {
            return List.copyOf(scripts.values());
        }

        @Override
        public Optional<ScriptRecord> findById(String scriptId) {
            return Optional.ofNullable(scripts.get(scriptId));
        }

        @Override
        public Optional<ScriptRecord> findByName(String name) {
            return scripts.values().stream().filter(s -> s.name().equals(name)).findFirst();
        }

        @Override
        public List<ScriptVersionRecord> findVersions(String scriptId) {
            List<ScriptVersionRecord> list = new ArrayList<>(versions.getOrDefault(scriptId, List.of()));
            list.sort((a, b) -> Integer.compare(b.version(), a.version()));
            return List.copyOf(list);
        }

        @Override
        public Optional<ScriptVersionRecord> findVersion(String scriptId, int version) {
            return versions.getOrDefault(scriptId, List.of()).stream()
                    .filter(v -> v.version() == version)
                    .findFirst();
        }

        @Override
        public ScriptRecord create(ScriptRecord script, ScriptVersionRecord initialVersion, String actor, String action) {
            scripts.put(script.scriptId(), script);
            versions.put(script.scriptId(), new ArrayList<>(List.of(initialVersion)));
            return script;
        }

        @Override
        public ScriptRecord appendVersion(String scriptId, ScriptVersionRecord version, String actor, String action) {
            ScriptRecord existing = scripts.get(scriptId);
            versions.computeIfAbsent(scriptId, k -> new ArrayList<>()).add(version);
            ScriptRecord updated = new ScriptRecord(
                    existing.scriptId(), existing.name(), existing.kind(), version.version(), existing.enabled(),
                    existing.schedule(), existing.timeoutSeconds(), existing.egress(), version.egressDeviceTargets(),
                    version.credentialRefs(), existing.notifyMode(), existing.runAs(),
                    existing.createdByActorFingerprint(), existing.createdAt(), version.createdAt(),
                    existing.notifyMailOnFailureOnly());
            scripts.put(scriptId, updated);
            return updated;
        }

        @Override
        public Optional<ScriptRecord> updateSettings(String scriptId, boolean enabled, Optional<String> schedule,
                int timeoutSeconds, List<String> egress, List<String> egressDeviceTargets, List<String> credentialRefs,
                String notifyMode, String runAs, String actor, String action) {
            return updateSettings(scriptId, enabled, schedule, timeoutSeconds, egress, egressDeviceTargets,
                    credentialRefs, notifyMode, false, runAs, actor, action);
        }

        @Override
        public Optional<ScriptRecord> updateSettings(String scriptId, boolean enabled, Optional<String> schedule,
                int timeoutSeconds, List<String> egress, List<String> egressDeviceTargets, List<String> credentialRefs,
                String notifyMode, boolean notifyMailOnFailureOnly, String runAs, String actor, String action) {
            ScriptRecord existing = scripts.get(scriptId);
            if (existing == null) {
                return Optional.empty();
            }
            boolean storedFailureOnly = notifyMailOnFailureOnly && ("smtp".equals(notifyMode) || "both".equals(notifyMode));
            ScriptRecord updated = new ScriptRecord(
                    existing.scriptId(), existing.name(), existing.kind(), existing.currentVersion(), enabled,
                    schedule, timeoutSeconds, egress, egressDeviceTargets, credentialRefs, notifyMode, runAs,
                    existing.createdByActorFingerprint(), existing.createdAt(), FIXED_NOW, storedFailureOnly);
            scripts.put(scriptId, updated);
            return Optional.of(updated);
        }

        @Override
        public Optional<ScriptRecord> setEnabled(String scriptId, boolean enabled, String actor, String action) {
            ScriptRecord existing = scripts.get(scriptId);
            if (existing == null) {
                return Optional.empty();
            }
            ScriptRecord updated = new ScriptRecord(
                    existing.scriptId(), existing.name(), existing.kind(), existing.currentVersion(), enabled,
                    existing.schedule(), existing.timeoutSeconds(), existing.egress(), existing.egressDeviceTargets(),
                    existing.credentialRefs(), existing.notifyMode(), existing.runAs(),
                    existing.createdByActorFingerprint(), existing.createdAt(), FIXED_NOW,
                    existing.notifyMailOnFailureOnly());
            scripts.put(scriptId, updated);
            return Optional.of(updated);
        }

        @Override
        public Optional<Instant> findLastScheduledSlot(String scriptId) {
            return Optional.ofNullable(lastScheduledSlots.get(scriptId));
        }

        @Override
        public Optional<ScriptRunRecord> admitRun(String jobId, String scriptId, int version, String triggerKind,
                Optional<Instant> scheduledSlot, String runAs, List<String> egressDeclared,
                List<String> egressDeviceTargets, List<String> credentialRefs, Instant requestedAt,
                String requestedBy, String actionId) {
            ScriptRecord s = scripts.get(scriptId);
            if (s == null) {
                return Optional.empty();
            }
            if (scheduledSlot.isPresent()) {
                if (!s.enabled()) {
                    return Optional.empty();
                }
                Instant prev = lastScheduledSlots.get(scriptId);
                if (prev != null && !prev.isBefore(scheduledSlot.get())) {
                    return Optional.empty();
                }
                lastScheduledSlots.put(scriptId, scheduledSlot.get());
            }
            ScriptRunRecord run = new ScriptRunRecord(
                    jobId, scriptId, version, "REQUESTED", Optional.empty(), triggerKind, scheduledSlot,
                    runAs, requestedBy, requestedAt, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), 0, 0, false, false, Optional.empty(), Optional.empty(), null,
                    Optional.empty(), Optional.empty(), Optional.empty(), egressDeclared, egressDeviceTargets,
                    credentialRefs, Optional.empty(), Optional.empty());
            runs.put(jobId, run);
            return Optional.of(run);
        }

        @Override
        public Optional<RunLease> findRunLease(String jobId) {
            ScriptRunRecord run = runs.get(jobId);
            if (run == null) {
                return Optional.empty();
            }
            return Optional.of(new RunLease(
                    jobId,
                    jobState,
                    leaseEpoch,
                    leaseWorker,
                    run.state(),
                    Optional.ofNullable(dispatchEpochs.get(jobId)),
                    Optional.ofNullable(runSessionIds.get(jobId))));
        }

        @Override
        public boolean beginDispatch(String jobId, long epoch, String sessionId, List<String> refs, Instant at, String actor) {
            ScriptRunRecord existing = runs.get(jobId);
            if (existing == null) {
                return false;
            }
            Long prevEpoch = dispatchEpochs.get(jobId);
            if (prevEpoch != null && prevEpoch >= epoch) {
                return false;
            }
            dispatchEpochs.put(jobId, epoch);
            runSessionIds.put(jobId, sessionId);
            runs.put(jobId, new ScriptRunRecord(
                    existing.jobId(), existing.scriptId(), existing.version(), "RUNNING", existing.outcome(),
                    existing.triggerKind(), existing.scheduledSlot(), existing.runAs(), existing.requestedBy(),
                    existing.requestedAt(), Optional.of(at), existing.finishedAt(), existing.durationMs(),
                    existing.exitCode(), existing.stdoutBytes(), existing.stderrBytes(), existing.stdoutTruncated(),
                    existing.stderrTruncated(), existing.outputArtefactRef(), existing.outputKeyId(),
                    existing.outputWrappedDataKey(), existing.outputSha256(), existing.outputSizeBytes(),
                    existing.outputTail(), existing.egressDeclared(), existing.egressDeviceTargets(),
                    refs, Optional.of(sessionId), existing.failureReason(), existing.notification()));
            return true;
        }

        @Override
        public Optional<ScriptRunRecord> findRunByJobId(String jobId) {
            return Optional.ofNullable(runs.get(jobId));
        }

        @Override
        public List<ScriptRunRecord> findRunsByScriptId(String scriptId, int limit) {
            return runs.values().stream()
                    .filter(r -> r.scriptId().equals(scriptId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean completeRun(String jobId, String state, String outcome, Instant finishedAt, long durationMs,
                Integer exitCode, int stdoutBytes, int stderrBytes, boolean stdoutTruncated, boolean stderrTruncated,
                Optional<String> outputArtefactRef, Optional<String> outputKeyId, byte[] outputWrappedDataKey,
                Optional<String> outputSha256, Optional<Long> outputSizeBytes, Optional<String> outputTail,
                List<String> egressDeviceTargets, List<String> credentialRefsUsed, Optional<String> failureReason,
                String actorFingerprint) {
            ScriptRunRecord existing = runs.get(jobId);
            if (existing == null || (!"REQUESTED".equals(existing.state()) && !"RUNNING".equals(existing.state()))) {
                return false;
            }
            runs.put(jobId, new ScriptRunRecord(
                    existing.jobId(), existing.scriptId(), existing.version(), state, Optional.ofNullable(outcome),
                    existing.triggerKind(), existing.scheduledSlot(), existing.runAs(), existing.requestedBy(),
                    existing.requestedAt(), existing.startedAt(), Optional.of(finishedAt), Optional.of(durationMs),
                    Optional.ofNullable(exitCode), stdoutBytes, stderrBytes, stdoutTruncated, stderrTruncated,
                    outputArtefactRef, outputKeyId, outputWrappedDataKey, outputSha256, outputSizeBytes, outputTail,
                    existing.egressDeclared(), egressDeviceTargets, credentialRefsUsed, existing.sessionId(),
                    failureReason, existing.notification()));
            return true;
        }

        @Override
        public void auditOutputDownload(String jobId, String actorFingerprint) {
            downloadAudits.add(jobId + "|" + actorFingerprint);
        }
    }

    private static NotificationSettingsStore fixedSettings(NotificationSettings settings) {
        return new NotificationSettingsStore(new TransactionBoundary() {
            @Override
            public <T> T inTransaction(java.util.function.Function<DSLContext, T> work) {
                throw new UnsupportedOperationException();
            }
        }) {
            @Override
            public NotificationSettings read() {
                return settings;
            }
        };
    }

    /** Mock jOOQ database backing {@link NotificationForwarder#tick()} and {@link JooqScriptRepository#findRunByJobId}. */
    private static final class NotificationMockDb {
        private static final String[] SCAN_COLS = {
                "job_id", "name", "version", "state", "outcome", "exit_code",
                "duration_ms", "finished_at", "output_tail", "notify"};
        private static final String[] JOOQ_RUN_COLS = {
                "job_id", "script_id", "version", "state", "outcome", "trigger_kind",
                "scheduled_slot", "run_as", "requested_by", "requested_at", "started_at",
                "finished_at", "duration_ms", "exit_code", "stdout_bytes", "stderr_bytes",
                "stdout_truncated", "stderr_truncated", "output_artefact_ref", "output_key_id",
                "output_wrapped_data_key", "output_sha256", "output_size_bytes", "output_tail",
                "egress_declared", "egress_device_targets", "credential_refs_used", "session_id",
                "failure_reason", "notify_syslog", "notify_syslog_code", "notify_smtp",
                "notify_smtp_code", "notified_at"};

        final Map<String, String> state = new LinkedHashMap<>();
        final Map<String, String[]> runs = new LinkedHashMap<>();
        final Map<String, Object[]> notifications = new LinkedHashMap<>();
        final List<String> executedSql = new CopyOnWriteArrayList<>();

        final DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String rawSql = ctx.sql();
            String lower = rawSql.toLowerCase(Locale.ROOT);
            executedSql.add(lower);

            if (lower.contains("pg_try_advisory_xact_lock")) {
                DSLContext c = DSL.using(SQLDialect.POSTGRES);
                org.jooq.Field<Boolean> f = DSL.field("pg_try_advisory_xact_lock", Boolean.class);
                Result<org.jooq.Record1<Boolean>> r = c.newResult(f);
                org.jooq.Record1<Boolean> rec = c.newRecord(f);
                rec.set(f, Boolean.TRUE);
                r.add(rec);
                return new MockResult[] {new MockResult(1, r)};
            }
            if (lower.contains("select value from notification_state")) {
                String val = state.get(String.valueOf(ctx.bindings()[0]));
                Result<Record> res = val == null
                        ? stringRows(new String[] {"value"})
                        : stringRows(new String[] {"value"}, new String[] {val});
                return new MockResult[] {new MockResult(res.size(), res)};
            }
            if (lower.contains("insert into notification_state")) {
                state.put(String.valueOf(ctx.bindings()[0]), String.valueOf(ctx.bindings()[1]));
                return new MockResult[] {new MockResult(1, stringRows(new String[] {"ok"}, new String[] {"1"}))};
            }
            if (lower.contains("from script_run r join script s")) {
                String[][] unnotified = runs.values().stream()
                        .filter(r -> !notifications.containsKey(r[0]))
                        .toArray(String[][]::new);
                Result<Record> res = stringRows(SCAN_COLS, unnotified);
                return new MockResult[] {new MockResult(res.size(), res)};
            }
            if (lower.contains("insert into script_run_notification")) {
                Object[] b = ctx.bindings();
                notifications.put(String.valueOf(b[0]), b);
                return new MockResult[] {new MockResult(1, stringRows(new String[] {"ok"}, new String[] {"1"}))};
            }
            if (lower.contains("from script_run left join script_run_notification")) {
                String jobId = String.valueOf(ctx.bindings()[0]);
                String[] r = runs.get(jobId);
                if (r == null) {
                    return new MockResult[] {new MockResult(0, typedScriptRunResult(
                            null, null, 0, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))};
                }
                Object[] n = notifications.get(jobId);
                Timestamp ts = Timestamp.valueOf(r[7]);
                Result<Record> joined = typedScriptRunResult(
                        r[0], "scr-" + r[0], Integer.parseInt(r[2]), r[3], r[4], "MANUAL",
                        RoleToken.OPERATOR, "op-1", ts, ts,
                        r[6] == null ? null : Long.parseLong(r[6]),
                        r[5] == null ? null : Integer.parseInt(r[5]),
                        r[8],
                        n == null ? null : String.valueOf(n[1]),
                        n == null || n[2] == null ? null : String.valueOf(n[2]),
                        n == null ? null : String.valueOf(n[3]),
                        n == null || n[4] == null ? null : String.valueOf(n[4]),
                        n == null || n[5] == null ? null : (Timestamp) n[5]);
                return new MockResult[] {new MockResult(1, joined)};
            }
            return new MockResult[] {new MockResult(0, stringRows(new String[] {"empty"}))};
        }), SQLDialect.POSTGRES);

        TransactionBoundary directBoundary() {
            return new TransactionBoundary() {
                @Override
                public <T> T inTransaction(java.util.function.Function<DSLContext, T> work) {
                    return work.apply(dsl);
                }
            };
        }

        void addFinishedRun(String jobId, String scriptName, int version, String runState, String outcome,
                Integer exitCode, Long durationMs, String finishedAtSql, String outputTail, String notify) {
            runs.put(jobId, new String[] {
                    jobId,
                    scriptName,
                    Integer.toString(version),
                    runState,
                    outcome,
                    exitCode == null ? null : exitCode.toString(),
                    durationMs == null ? null : durationMs.toString(),
                    finishedAtSql,
                    outputTail,
                    notify});
        }

        String syslogState(String jobId) {
            return String.valueOf(notifications.get(jobId)[1]);
        }

        String smtpState(String jobId) {
            return String.valueOf(notifications.get(jobId)[3]);
        }

        String smtpCode(String jobId) {
            Object code = notifications.get(jobId)[4];
            return code == null ? null : String.valueOf(code);
        }
    }

    /** Minimal loopback SMTP server speaking 220 / EHLO / MAIL / RCPT / DATA / QUIT for {@code SmtpRelaySender}. */
    private static final class FakeSmtpServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread acceptThread;
        private volatile boolean open = true;
        final List<String> messages = new CopyOnWriteArrayList<>();

        FakeSmtpServer() throws IOException {
            this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            this.acceptThread = new Thread(this::serveLoop, "fake-smtp-pin");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serveLoop() {
            while (open) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = socket.getOutputStream();
                    reply(out, "220 localhost ESMTP");
                    String line;
                    while ((line = in.readLine()) != null) {
                        String upper = line.toUpperCase(Locale.ROOT);
                        if (upper.startsWith("EHLO") || upper.startsWith("HELO")
                                || upper.startsWith("MAIL FROM:") || upper.startsWith("RCPT TO:")) {
                            reply(out, "250 OK");
                        } else if (upper.equals("DATA")) {
                            reply(out, "354 End data with <CR><LF>.<CR><LF>");
                            StringBuilder raw = new StringBuilder();
                            String dataLine;
                            while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                                raw.append(dataLine.startsWith("..") ? dataLine.substring(1) : dataLine).append('\n');
                            }
                            messages.add(decodeQuotedPrintableRough(raw.toString()));
                            reply(out, "250 Queued");
                        } else if (upper.startsWith("QUIT")) {
                            reply(out, "221 Bye");
                            break;
                        } else {
                            reply(out, "250 OK");
                        }
                    }
                } catch (IOException ignored) {
                    if (!open) {
                        break;
                    }
                }
            }
        }

        private static void reply(OutputStream out, String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private static String decodeQuotedPrintableRough(String mime) {
            String unfolded = mime.replace("=\n", "");
            var bytes = new java.io.ByteArrayOutputStream(unfolded.length());
            for (int i = 0; i < unfolded.length(); i++) {
                char c = unfolded.charAt(i);
                if (c == '=' && i + 2 < unfolded.length()) {
                    int hi = Character.digit(unfolded.charAt(i + 1), 16);
                    int lo = Character.digit(unfolded.charAt(i + 2), 16);
                    if (hi >= 0 && lo >= 0) {
                        bytes.write((hi << 4) | lo);
                        i += 2;
                        continue;
                    }
                }
                bytes.write((byte) c);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            open = false;
            server.close();
        }
    }
}
