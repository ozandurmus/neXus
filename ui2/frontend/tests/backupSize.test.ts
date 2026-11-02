import { describe, expect, it } from "vitest";

import {
  LARGE_BACKUP_MIN_BYTES, compareBySize, largeBackupFlag, largeBackupTitle, nextSizeSort, vendorMedianSizes, type SizeSort,
} from "../src/screens/backupSize";

/**
 * The large-backup rule (Product Owner, 2026-11-02): per vendor, the median of each device's latest archive over the
 * devices that have one; a device is flagged when its archive is at least 10x that median and at least 1 GiB. Synthetic
 * sizes only, shaped like the reviewed estate: Check Point archives around 0.2 GB and one of 28 GB.
 */
const MB = 1024 * 1024;
const GB = 1024 * MB;
const cp = (sizeBytes: number) => ({ vendor: "check_point", sizeBytes });
const pan = (sizeBytes: number) => ({ vendor: "palo_alto", sizeBytes });

describe("vendor medians", () => {
  it("takes the median per vendor, odd and even counts, ignoring unknown sizes", () => {
    const medians = vendorMedianSizes([cp(100 * MB), cp(300 * MB), cp(28 * GB), pan(10 * MB), pan(30 * MB), pan(Number.NaN), pan(-1)]);
    expect(medians.get("check_point")).toBe(300 * MB);
    expect(medians.get("palo_alto")).toBe(20 * MB);
    expect(medians.has("unknown")).toBe(false);
  });

  it("is empty for an empty fleet", () => {
    expect(vendorMedianSizes([]).size).toBe(0);
  });
});

describe("large-backup flag", () => {
  const fleet = [...Array.from({ length: 12 }, (_, i) => cp((250 + i * 5) * MB)), cp(28_537 * MB), pan(2 * GB), pan(3 * GB), pan(40 * GB)];
  const medians = vendorMedianSizes(fleet);

  it("flags the 28 GB Check Point archive against the Check Point median and explains the ratio", () => {
    const flag = largeBackupFlag(cp(28_537 * MB), medians);
    expect(flag).not.toBeNull();
    expect(flag!.vendor).toBe("check_point");
    expect(flag!.medianBytes).toBe(280 * MB);
    expect(flag!.factor).toBeCloseTo(101.9, 1);
    expect(largeBackupTitle(flag!)).toBe("101.9x the Check Point fleet median (280.0 MB)");
  });

  it("does not flag a normal archive of the same vendor", () => {
    expect(largeBackupFlag(cp(300 * MB), medians)).toBeNull();
  });

  it("measures each vendor against its own median: 40 GB is 13x the Palo Alto median, 3 GB is not", () => {
    expect(largeBackupFlag(pan(40 * GB), medians)?.factor).toBeCloseTo(40 / 3, 3);
    expect(largeBackupFlag(pan(3 * GB), medians)).toBeNull();
  });

  it("needs at least 1 GiB even when the ratio is met", () => {
    const small = vendorMedianSizes([pan(1 * MB), pan(1 * MB), pan(900 * MB)]);
    expect(largeBackupFlag(pan(900 * MB), small)).toBeNull();
    expect(largeBackupFlag(pan(LARGE_BACKUP_MIN_BYTES), small)).not.toBeNull();
    expect(largeBackupFlag(pan(LARGE_BACKUP_MIN_BYTES - 1), small)).toBeNull();
  });

  it("never flags a vendor with a single device (its own median), nor a vendor it has no median for", () => {
    const single = vendorMedianSizes([cp(28 * GB)]);
    expect(largeBackupFlag(cp(28 * GB), single)).toBeNull();
    expect(largeBackupFlag({ vendor: "radware", sizeBytes: 50 * GB }, medians)).toBeNull();
  });

  it("never divides by a zero median", () => {
    const zero = vendorMedianSizes([cp(0), cp(0), cp(5 * GB)]);
    expect(largeBackupFlag(cp(5 * GB), zero)).toBeNull();
  });
});

describe("Size header sort", () => {
  it("cycles default -> largest first -> smallest first -> default", () => {
    const seen: SizeSort[] = ["none"];
    for (let i = 0; i < 3; i++) seen.push(nextSizeSort(seen[seen.length - 1]));
    expect(seen).toEqual(["none", "desc", "asc", "none"]);
  });

  it("puts rows without an archive last whichever way the column points, ties stable", () => {
    const sizes: Array<number | null> = [null, 300, null, 100, 200];
    expect([...sizes].sort((a, b) => compareBySize(a, b, "desc"))).toEqual([300, 200, 100, null, null]);
    expect([...sizes].sort((a, b) => compareBySize(a, b, "asc"))).toEqual([100, 200, 300, null, null]);
    expect(compareBySize(5, 5, "desc")).toBe(0);
    expect(compareBySize(null, null, "asc")).toBe(0);
  });
});
