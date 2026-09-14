import { NEXUS_X_BLUE, NEXUS_X_TEAL } from "./colors";

/**
 * The two-stroke X shared by NexusWordmark's capital letter and
 * NexusMark's icon variant, drawn in a 64x64 box: a teal left stroke and a
 * blue right stroke crossing, per the study's option D (see colors.ts).
 */
export function NexusXStrokes() {
  return (
    <>
      <path d="M12 10 L24 10 L52 54 L40 54 Z" fill={NEXUS_X_TEAL} />
      <path d="M52 10 L40 10 L12 54 L24 54 Z" fill={NEXUS_X_BLUE} />
    </>
  );
}
