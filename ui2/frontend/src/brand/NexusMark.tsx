import appMarkSvg from "../assets/app-mark.svg";
import { m3 } from "../theme/m3Theme";

export function NexusMark({ size = 40 }: { readonly size?: number }) {
  return <img src={appMarkSvg} alt="neXus" width={size} height={size} style={{ backgroundColor: m3.brandBackdrop, padding: 2, borderRadius: 8 }} />;
}
