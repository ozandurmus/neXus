import { Stack, Typography } from "@mui/material";
import type { ReportDocumentView } from "../auth/adminApi";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { ReportDocumentPanel } from "../screens/ReportDocumentPanel";

/**
 * Design preview of the Reports screen (REPORTS_R1_CONTRACT.md "UI": masked fixtures and previews only). Every name is
 * an AIView pseudonym and every address a 192.0.2.x documentation address; nothing here was collected or generated.
 */
export const PREVIEW_REPORT: ReportDocumentView = {
  template_id: "RPT-01",
  title: "Executive weekly summary",
  audience: "Executive",
  question: "How is the estate doing this week, how much of it is backed by fresh evidence, and what needs attention?",
  generated_at: "2026-11-05T06:00:00Z",
  projection: "MASKED",
  parameters: { from: "2026-10-30", to: "2026-11-05" },
  data_as_of: [
    { plane: "inventory", newest: "2026-11-05T03:10:00Z", oldest: "2026-11-03T02:40:00Z", covered: 11, total: 12, stale: 1, unit: "devices" },
    { plane: "backup", newest: "2026-11-05T02:20:00Z", oldest: "2026-11-04T02:15:00Z", covered: 7, total: 8, stale: 0, unit: "backup targets" },
  ],
  notices: ["NO EVIDENCE: 1 of 12 devices have no inventory evidence; they are shown as UNKNOWN, never as pass or fail."],
  sections: [{
    id: "verdict", title: "Estate verdict", data_as_of: [],
    kpis: [{ label: "Estate", value: "AT RISK", tone: "BAD" }, { label: "Devices with inventory evidence", value: "11 of 12", tone: "INFO" }],
    tables: [{
      id: "attention", title: "Up to five items, most severe first",
      columns: [{ key: "area", label: "Area" }, { key: "item", label: "Item" }, { key: "why", label: "Why" }],
      rows: [
        [{ text: "HA readiness", kind: "TEXT" }, { text: "CLS-ROMEO-01", kind: "CLUSTER" }, { text: "Not ready: CP-04", kind: "TEXT" }],
        [{ text: "Backup", kind: "TEXT" }, { text: "FW-TANGO-04", kind: "DEVICE" }, { text: "Backup target without an archive", kind: "TEXT" }],
        [{ text: "Evidence", kind: "TEXT" }, { text: "FW-JULIET-06", kind: "DEVICE" }, { text: "Never inventoried (192.0.2.15)", kind: "TEXT" }],
      ],
      empty_text: "Nothing needs attention.",
    }],
    notes: ["The estate is AT RISK when any pillar is, otherwise UNKNOWN when any pillar is."],
  }],
};

export function ReportsPreview() {
  return (
    <ScreenRoot screenType="summary">
      <ScreenHeader title="Reports" subtitle="Predefined reports built from stored evidence only: no device is contacted." />
      <Stack spacing={1}>
        <Typography variant="caption">Catalogue · Run & preview · History</Typography>
        <ReportDocumentPanel doc={PREVIEW_REPORT} />
      </Stack>
    </ScreenRoot>
  );
}
