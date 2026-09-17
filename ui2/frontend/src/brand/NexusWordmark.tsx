import wordmarkSvg from "../assets/wordmark.svg";
import wordmarkTaglineSvg from "../assets/wordmark-tagline.svg";

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
      src={tagline ? wordmarkTaglineSvg : wordmarkSvg}
      alt={tagline ? "neXus — A CLEARER TOMORROW" : "neXus"}
      height={height}
      style={{ width: "auto", color }}
    />
  );
}
