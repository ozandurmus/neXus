import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { listJobs, type JobEventView } from "../auth/adminApi";
import { EstateCard, EvidenceStamp, MoreFooter } from "./EstatePrimitives";
import { standardJobsHref } from "./estateRouting";
import {
  buildDossierJobs,
  DOSSIER_JOBS_CAP,
  DOSSIER_JOBS_FAILED_TEXT,
  DOSSIER_JOBS_NOT_EVALUABLE_TEXT,
  type DossierJobsRead,
} from "./estateDossierJobsModel";
import { ESTATE_MONO, ev } from "./estateTokens";

interface JobsState {
  readonly read: DossierJobsRead;
  /** Time the read settled; the evidence stamp of this read. */
  readonly readAt: string;
}

/**
 * Dossier block: the unit's most recent jobs, read-only, as the standard job log lists them (type and state only, no
 * transcript). A cluster unit is not evaluable and makes no call. A failed read says so; it is never shown as an
 * empty list.
 */
export function EstateDossierJobsBlock({
  deviceId,
  clusterUnit,
}: {
  readonly deviceId: string;
  readonly clusterUnit: boolean;
}) {
  if (clusterUnit) return <JobsView key={`cluster:${deviceId}`} state={null} clusterUnit />;
  return <JobsLoader key={deviceId} deviceId={deviceId} />;
}

function JobsLoader({ deviceId }: { readonly deviceId: string }) {
  const [state, setState] = useState<JobsState | null>(null);

  useEffect(() => {
    let active = true;
    // Same device identifier the dossier's other reads use; passed as-is, never parsed or normalised.
    listJobs({ device_id: deviceId, page_size: DOSSIER_JOBS_CAP })
      .then(
        (page): DossierJobsRead => ({
          ok: true,
          items: page?.items as readonly JobEventView[],
          total: page?.total,
        }),
        (): DossierJobsRead => ({ ok: false }),
      )
      .then((read) => {
        if (active) setState({ read, readAt: new Date().toISOString() });
      });
    return () => {
      active = false;
    };
  }, [deviceId]);

  return <JobsView state={state} clusterUnit={false} />;
}

function JobsView({ state, clusterUnit }: { readonly state: JobsState | null; readonly clusterUnit: boolean }) {
  const model = buildDossierJobs(state?.read ?? null, clusterUnit);
  const link = (
    <Box component="a" href={standardJobsHref()} sx={{ fontSize: "13px", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}>
      Open job log (standard view)
    </Box>
  );
  const header = (
    <Box sx={{ px: "18px", pt: "14px", pb: "8px" }}>
      <Typography sx={{ fontSize: "14px", fontWeight: 600, color: ev.ink }}>Recent jobs for this unit</Typography>
      <Typography sx={{ fontSize: "12px", color: ev.ink3 }}>
        The latest jobs recorded for this device, as the standard job log lists them. Read-only: type and state only.
      </Typography>
    </Box>
  );

  let body;
  if (model.status === "not_evaluable") {
    body = (
      <Typography data-testid="dossier-jobs-not-evaluable" sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink2 }}>
        {DOSSIER_JOBS_NOT_EVALUABLE_TEXT}
      </Typography>
    );
  } else if (!state) {
    body = <Typography sx={{ px: "18px", pb: "14px", fontSize: "12.5px", color: ev.ink3 }}>Reading recent jobs…</Typography>;
  } else if (model.status === "failed") {
    body = (
      <Box sx={{ px: "18px", pb: "14px" }} data-testid="dossier-jobs-failed">
        <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>{DOSSIER_JOBS_FAILED_TEXT}</Typography>
        <Box sx={{ mt: "4px" }}>
          <EvidenceStamp at={state.readAt} source="jobs read" missingLabel="UNKNOWN" />
        </Box>
      </Box>
    );
  } else if (model.rows.length === 0) {
    body = (
      <Box sx={{ px: "18px", pb: "14px" }} data-testid="dossier-jobs-empty">
        <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>The job read returned no records for this unit.</Typography>
        <Box sx={{ mt: "4px" }}>
          <EvidenceStamp at={state.readAt} source="jobs read" />
        </Box>
      </Box>
    );
  } else {
    body = (
      <Box sx={{ px: "18px", pb: "8px" }} data-testid="dossier-jobs">
        {model.rows.map((row, i) => (
          <Box
            key={`${i}:${row.submittedAt ?? ""}:${row.jobType}`}
            data-job-row={i}
            sx={{ display: "flex", alignItems: "baseline", gap: "10px", flexWrap: "wrap", py: "6px", borderTop: `1px solid ${ev.line}` }}
          >
            <Typography sx={{ fontSize: "13px", fontWeight: 600, fontFamily: ESTATE_MONO, minWidth: "180px" }}>{row.jobType}</Typography>
            <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>{row.state}</Typography>
            <Box sx={{ ml: "auto" }}>
              <EvidenceStamp at={row.submittedAt} source="job record" />
            </Box>
          </Box>
        ))}
        <Box sx={{ pt: "4px" }}>
          <MoreFooter shown={model.rows.length} total={model.rows.length + model.remainder} noun="jobs" testId="dossier-jobs-more" />
        </Box>
        <Box sx={{ mt: "4px" }}>
          <EvidenceStamp at={state.readAt} source="jobs read" />
        </Box>
      </Box>
    );
  }

  return (
    <EstateCard sx={{ mt: "16px" }}>
      {header}
      {body}
      <Box sx={{ px: "18px", pb: "14px" }}>{link}</Box>
    </EstateCard>
  );
}
