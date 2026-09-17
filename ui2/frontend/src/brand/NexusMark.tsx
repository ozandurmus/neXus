import appMarkSvg from "../assets/app-mark.svg";

export function NexusMark({ size = 40 }: { readonly size?: number }) {
  return <img src={appMarkSvg} alt="neXus" width={size} height={size} />;
}
