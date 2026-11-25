import type { LogFindingChipData } from "../auth/adminApi";
import { StatusChip } from "../shell/M3Widgets";
import { chipLabel, chipTone, coverageLine } from "./logFindingsModel";

/**
 * The log chip of one device (design section 9): the worst open finding and its count, or the coverage state when there is
 * nothing to count. The hover text says what is counted and the coverage; a device with incomplete coverage is never green.
 */
export function LogFindingChip({ chip }: { readonly chip: LogFindingChipData }) {
  return (
    <span data-testid="log-finding-chip" data-state={chip.state}>
      <StatusChip tone={chipTone(chip)} label={chipLabel(chip)} dense title={`${chip.text}. ${coverageLine(chip.coverage)}.`} />
    </span>
  );
}
