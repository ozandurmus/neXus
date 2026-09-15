const WORDMARK = new URL("../../../../docs/design/ui2_mockups/wordmark-option-d/wordmark.svg", import.meta.url).href;
const WORDMARK_TAGLINE = new URL("../../../../docs/design/ui2_mockups/wordmark-option-d/wordmark-tagline.svg", import.meta.url).href;

export function NexusWordmark({
  height = 40,
  color = "currentColor",
  tagline = false,
}: {
  readonly height?: number;
  readonly color?: string;
  readonly tagline?: boolean;
}) {
  return (
    <img
      src={tagline ? WORDMARK_TAGLINE : WORDMARK}
      alt={tagline ? "neXus — A CLEARER TOMORROW" : "neXus"}
      height={height}
      style={{ width: "auto", color }}
    />
  );
}
