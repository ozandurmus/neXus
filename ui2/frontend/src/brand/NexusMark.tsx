const APP_MARK = new URL("../../../../docs/design/ui2_mockups/wordmark-option-d/app-mark.svg", import.meta.url).href;

export function NexusMark({ size = 40 }: { readonly size?: number }) {
  return <img src={APP_MARK} alt="neXus" width={size} height={size} />;
}
