import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { EstateBackupsDeviationsQuotas } from "../src/estate/EstateBackupsDeviationsQuotas";
import {
  BACKUP_MAJOR_DEVIATION_CAP,
  BACKUP_SIZE_ANOMALY_CAP,
  BACKUP_STORE_QUOTA_CAP,
  projectBackupDeviations,
  projectBackupStoreQuotas,
  type BackupDeviationOverview,
  type BackupStoreQuotaOverview,
} from "../src/estate/estateBackupsParityModel";

// Synthetic data only: FW-TANGO-04 style pseudonyms, no real hosts, IPs, serials or users.
const NOW = new Date("2026-11-22T12:00:00Z");
const EVIDENCE_AT = "2026-11-22T02:00:00Z";
const CHANGED_AT = "2026-11-22T06:00:00Z";
const DETECTED_AT = "2026-11-22T08:00:00Z";
const GIB = 1024 ** 3;

describe("EstateBackupsDeviationsQuotas & estateBackupsParityModel", () => {
  it("renders UNKNOWN badge (never OK / No active deviations) when total_deviations_checked === 0 and no deviations/anomalies exist, or when deviations is null", () => {
    const nullModel = projectBackupDeviations(null, "ok");
    expect(nullModel.badge).toEqual({ state: "unk", word: "UNKNOWN" });
    expect(nullModel.coverageText).toBe("Deviation comparison unavailable — UNKNOWN (not assumed clear).");

    const zeroChecked: BackupDeviationOverview = {
      active_major_deviations: [],
      size_anomalies: [],
      total_deviations_checked: 0,
      devices_known: 12,
      window_days: 7,
    };
    const zeroModel = projectBackupDeviations(zeroChecked, "ok");
    expect(zeroModel.badge).toEqual({ state: "unk", word: "UNKNOWN" });
    expect(zeroModel.coverageText).toBe("0 of 12 devices compared · last 7 days");

    const { rerender } = render(
      <EstateBackupsDeviationsQuotas
        deviations={zeroChecked}
        backupsState="ok"
        quotas={{ state: "failed" }}
        backupEvidenceAt={EVIDENCE_AT}
        now={NOW}
      />,
    );

    const devCard = screen.getByTestId("estate-backups-deviations-card");
    expect(within(devCard).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(devCard).getByText("0 of 12 devices compared · last 7 days")).toBeInTheDocument();
    expect(within(devCard).queryByText("No active deviations")).toBeNull();
    expect(within(devCard).queryByText(/^(OK|Fine)$/)).toBeNull();

    rerender(
      <EstateBackupsDeviationsQuotas
        deviations={null}
        backupsState="failed"
        quotas={{ state: "restricted" }}
        backupEvidenceAt={null}
        now={NOW}
      />,
    );

    expect(within(devCard).getByText("UNKNOWN")).toBeInTheDocument();
    expect(
      within(devCard).getByText("Deviation comparison unavailable — UNKNOWN (not assumed clear)."),
    ).toBeInTheDocument();
    expect(within(devCard).getByText("no comparison timestamp")).toBeInTheDocument();

    const quotaCard = screen.getByTestId("estate-backups-store-quotas-card");
    expect(within(quotaCard).getByText("Restricted")).toBeInTheDocument();
    expect(within(quotaCard).getByText("Restricted for this role.")).toBeInTheDocument();
  });

  it("groups simultaneous active major deviations per device + timestamp, renders category badges, changeWords, EvidenceStamp, &standard=1 configuration changes link, and prints MoreFooter when groups exceed BACKUP_MAJOR_DEVIATION_CAP (10)", () => {
    const onNavigate = vi.fn();

    // Device 1 has two simultaneous categories (routing + interfaces) in the same timestamp minute -> 1 group.
    // Plus 11 additional devices (dev-tango-02 .. dev-tango-12) -> 12 groups total (exceeds cap of 10 by 2).
    const activeMajor = [
      {
        device_id: "dev-tango-01",
        hostname: "FW-TANGO-01",
        vendor: "check_point",
        category: "routing",
        changed_at: CHANGED_AT,
        count: 1,
        sections: ["static-route"],
      },
      {
        device_id: "dev-tango-01",
        hostname: "FW-TANGO-01",
        vendor: "check_point",
        category: "interfaces",
        changed_at: CHANGED_AT,
        count: 2,
        sections: ["interface"],
      },
      ...Array.from({ length: 11 }, (_, idx) => {
        const num = String(idx + 2).padStart(2, "0");
        return {
          device_id: `dev-tango-${num}`,
          hostname: `FW-TANGO-${num}`,
          vendor: "palo_alto",
          category: "admin_access",
          changed_at: new Date(Date.parse(CHANGED_AT) - (idx + 1) * 120_000).toISOString(),
          count: 1,
          sections: ["users"],
        };
      }),
    ];

    const deviations: BackupDeviationOverview = {
      active_major_deviations: activeMajor,
      size_anomalies: [],
      total_deviations_checked: 12,
      devices_known: 15,
      window_days: 7,
    };

    render(
      <EstateBackupsDeviationsQuotas
        deviations={deviations}
        backupsState="ok"
        quotas={{ state: "ok", value: { store_mounted: true, stores: [] } }}
        backupEvidenceAt={EVIDENCE_AT}
        now={NOW}
        onNavigate={onNavigate}
      />,
    );

    const devCard = screen.getByTestId("estate-backups-deviations-card");
    expect(within(devCard).getByText("12 major deviation groups · 0 size anomalies")).toBeInTheDocument();
    expect(within(devCard).getByText("12 of 15 devices compared · last 7 days")).toBeInTheDocument();

    // First group combines Routing and Interfaces on FW-TANGO-01 at CHANGED_AT
    const firstRow = devCard.querySelector('[data-major-deviation-group="dev-tango-01"]') as HTMLElement;
    expect(firstRow).not.toBeNull();
    expect(within(firstRow).getByText("Routing")).toBeInTheDocument();
    expect(within(firstRow).getByText("Interfaces")).toBeInTheDocument();
    expect(within(firstRow).getByText("Routing, Interfaces: static-route, interface")).toBeInTheDocument();
    expect(firstRow.querySelector("[data-evidence-stamp]")).not.toBeNull();

    const diffLink = within(firstRow).getByRole("link", { name: /Configuration changes/i });
    expect(diffLink).toHaveAttribute(
      "href",
      "?screen=inventory&device_id=dev-tango-01&tab=configuration&config_tab=changes&standard=1",
    );

    fireEvent.click(within(firstRow).getByRole("link", { name: "FW-TANGO-01" }));
    expect(onNavigate).toHaveBeenCalledWith({ kind: "dossier", deviceId: "dev-tango-01" });

    // Capped at BACKUP_MAJOR_DEVIATION_CAP (10) with MoreFooter showing +2 more major deviation groups
    expect(devCard.querySelectorAll("[data-major-deviation-group]")).toHaveLength(BACKUP_MAJOR_DEVIATION_CAP);
    expect(within(devCard).getByTestId("backup-major-deviations-more")).toHaveTextContent(
      "+2 more major deviation groups",
    );
  });

  it("renders open size anomalies with open_copies unit words ('anomalous backup' / 'anomalous backups') and MoreFooter when anomalies exceed BACKUP_SIZE_ANOMALY_CAP (10)", () => {
    const anomalies = Array.from({ length: BACKUP_SIZE_ANOMALY_CAP + 2 }, (_, idx) => {
      const num = String(idx + 1).padStart(2, "0");
      return {
        device_id: `dev-juliet-${num}`,
        hostname: `FW-JULIET-${num}`,
        deviation_id: `anom-${num}`,
        artefact_id: `art-curr-${num}`,
        last_good_artefact_id: `art-prev-${num}`,
        direction: idx === 0 ? "growth" : "shrink",
        plaintext_bytes: idx === 0 ? 500_000 : 20_000,
        median_bytes: 100_000,
        ratio: idx === 0 ? 5.0 : 0.2,
        summary: "Size anomaly",
        detected_at: DETECTED_AT,
        open_copies: idx === 0 ? 1 : 3,
        status: "OPEN",
      };
    });

    const deviations: BackupDeviationOverview = {
      active_major_deviations: [],
      size_anomalies: [
        ...anomalies,
        {
          device_id: "dev-juliet-99",
          hostname: "FW-JULIET-99",
          deviation_id: "anom-99",
          artefact_id: "art-curr-99",
          last_good_artefact_id: "art-prev-99",
          direction: "growth",
          plaintext_bytes: 900_000,
          median_bytes: 100_000,
          ratio: 9.0,
          summary: "Closed anomaly",
          detected_at: DETECTED_AT,
          open_copies: 2,
          status: "CLOSED",
        },
      ],
      total_deviations_checked: 12,
      devices_known: 12,
      window_days: 7,
    };

    render(
      <EstateBackupsDeviationsQuotas
        deviations={deviations}
        backupsState="ok"
        quotas={{ state: "ok", value: { store_mounted: true, stores: [] } }}
        backupEvidenceAt={EVIDENCE_AT}
        now={NOW}
      />,
    );

    const devCard = screen.getByTestId("estate-backups-deviations-card");
    expect(within(devCard).getByText("0 major deviation groups · 12 size anomalies")).toBeInTheDocument();

    const row1 = devCard.querySelector('[data-size-anomaly-row="dev-juliet-01"]') as HTMLElement;
    expect(within(row1).getByText("Grew 5.0x")).toBeInTheDocument();
    expect(
      within(row1).getByText("500% of 100,000 B baseline (500,000 B) · 1 anomalous backup"),
    ).toBeInTheDocument();

    const row2 = devCard.querySelector('[data-size-anomaly-row="dev-juliet-02"]') as HTMLElement;
    expect(within(row2).getByText("Shrank to 0.2x")).toBeInTheDocument();
    expect(
      within(row2).getByText("20% of 100,000 B baseline (20,000 B) · 3 anomalous backups"),
    ).toBeInTheDocument();

    expect(devCard.querySelectorAll("[data-size-anomaly-row]")).toHaveLength(BACKUP_SIZE_ANOMALY_CAP);
    expect(within(devCard).getByTestId("backup-size-anomalies-more")).toHaveTextContent(
      "+2 more size anomalies",
    );
  });

  it("renders per-store quota rows (Within quota, Near limit, Over quota, No quota, and UNKNOWN when used_bytes === null or store_mounted === false), summary counts with unit words, EvidenceStamp, and MoreFooter when stores exceed BACKUP_STORE_QUOTA_CAP (8)", () => {
    const stores = [
      {
        store: "check_point",
        used_bytes: 5 * GIB,
        quota_bytes: 10 * GIB,
        remaining_bytes: 5 * GIB,
        percent: 50,
        near_limit: false,
        quota_exceeded: false,
      },
      {
        store: "palo_alto",
        used_bytes: 9 * GIB,
        quota_bytes: 10 * GIB,
        remaining_bytes: GIB,
        percent: 90,
        near_limit: true,
        quota_exceeded: false,
      },
      {
        store: "cisco_asa",
        used_bytes: 12 * GIB,
        quota_bytes: 10 * GIB,
        remaining_bytes: 0,
        percent: 120,
        near_limit: true,
        quota_exceeded: true,
      },
      {
        store: "fortinet",
        used_bytes: 2 * GIB,
        quota_bytes: null,
        remaining_bytes: null,
        percent: null,
        near_limit: false,
        quota_exceeded: false,
      },
      {
        store: "infoblox",
        used_bytes: null,
        quota_bytes: 10 * GIB,
        remaining_bytes: null,
        percent: null,
        near_limit: false,
        quota_exceeded: false,
      },
      ...Array.from({ length: 5 }, (_, idx) => ({
        store: `synthetic_store_${idx + 1}`,
        used_bytes: GIB,
        quota_bytes: 4 * GIB,
        remaining_bytes: 3 * GIB,
        percent: 25,
        near_limit: false,
        quota_exceeded: false,
      })),
    ];

    const mountedQuotas: BackupStoreQuotaOverview = {
      store_mounted: true,
      stores,
    };

    const { rerender } = render(
      <EstateBackupsDeviationsQuotas
        deviations={{
          active_major_deviations: [],
          size_anomalies: [],
          total_deviations_checked: 5,
          devices_known: 5,
          window_days: 7,
        }}
        backupsState="ok"
        quotas={{ state: "ok", value: mountedQuotas }}
        backupEvidenceAt={EVIDENCE_AT}
        now={NOW}
      />,
    );

    const quotaCard = screen.getByTestId("estate-backups-store-quotas-card");
    expect(within(quotaCard).getByText("1 store over quota")).toBeInTheDocument();
    expect(
      within(quotaCard).getByText("9 of 10 stores with a quota · 1 store over quota · 1 store near limit"),
    ).toBeInTheDocument();
    expect(quotaCard.querySelector("[data-evidence-stamp]")).not.toBeNull();

    const cpRow = quotaCard.querySelector('[data-store-quota-row="check_point"]') as HTMLElement;
    expect(within(cpRow).getByText("5.0 GB used / 10.0 GB")).toBeInTheDocument();
    expect(within(cpRow).getByText("50%")).toBeInTheDocument();
    expect(within(cpRow).getByText("Within quota")).toBeInTheDocument();

    const panRow = quotaCard.querySelector('[data-store-quota-row="palo_alto"]') as HTMLElement;
    expect(within(panRow).getByText("Near limit")).toBeInTheDocument();

    const asaRow = quotaCard.querySelector('[data-store-quota-row="cisco_asa"]') as HTMLElement;
    expect(within(asaRow).getByText("Over quota")).toBeInTheDocument();

    const fgRow = quotaCard.querySelector('[data-store-quota-row="fortinet"]') as HTMLElement;
    expect(within(fgRow).getByText("2.0 GB used / no quota")).toBeInTheDocument();
    expect(within(fgRow).getByText("No quota")).toBeInTheDocument();

    const ibRow = quotaCard.querySelector('[data-store-quota-row="infoblox"]') as HTMLElement;
    expect(within(ibRow).getByText("UNKNOWN used / 10.0 GB")).toBeInTheDocument();
    expect(within(ibRow).getByText("UNKNOWN")).toBeInTheDocument();

    expect(quotaCard.querySelectorAll("[data-store-quota-row]")).toHaveLength(BACKUP_STORE_QUOTA_CAP);
    expect(within(quotaCard).getByTestId("backup-stores-more")).toHaveTextContent("+2 more stores");

    // Unmounted store -> UNKNOWN badge and reason
    const unmountedModel = projectBackupStoreQuotas({
      state: "ok",
      value: { store_mounted: false, reason: "Artefact store PVC not mounted — UNKNOWN." },
    });
    expect(unmountedModel.badge).toEqual({ state: "unk", word: "UNKNOWN" });
    expect(unmountedModel.summaryText).toBe("Artefact store PVC not mounted — UNKNOWN.");

    rerender(
      <EstateBackupsDeviationsQuotas
        deviations={null}
        backupsState="failed"
        quotas={{ state: "ok", value: { store_mounted: false } }}
        backupEvidenceAt={EVIDENCE_AT}
        now={NOW}
      />,
    );
    expect(within(quotaCard).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(quotaCard).getByText("Backup store is not mounted — UNKNOWN.")).toBeInTheDocument();
  });
});
