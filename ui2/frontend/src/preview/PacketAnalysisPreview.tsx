import { ResultView } from "../screens/PacketAnalysisScreen";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { StatusChip } from "../shell/M3Widgets";

/** Synthetic, masked: the result a stub-engine analysis produces, under pseudonymous names only. */
export function PacketAnalysisPreview() {
  const stages = ["ingest", "core_flow_loss", "topology", "path_integrity", "application_security_hints", "nexus_correlation"]
    .map(stage => ({ stage, verdict: "unknown", reason: "engine not yet ported" }));
  return (
    <ScreenRoot>
      <ScreenHeader title="Packet Analysis" subtitle="OBJ-TANGO-04 · 2 captures · expires 2026-10-22"
        actions={<StatusChip tone="mem" label="Masked view" />} />
      <ResultView report={{ job_id: "preview", case_id: "preview", projection: "masked",
        summary: { verdict: "unknown", reason: "engine not yet ported", stages, capture_count: 2, total_bytes: 268435456, finding_count: 0 } }} />
    </ScreenRoot>
  );
}
