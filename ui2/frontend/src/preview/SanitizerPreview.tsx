import { OutcomeView } from "../screens/SanitizerScreen";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";

/** Synthetic: the summary a sanitized firewall log produces -- counts per kind only, never a value. */
export function SanitizerPreview() {
  return (
    <ScreenRoot>
      <ScreenHeader title="Data Sanitizer" subtitle="Data Anonymization · synthetic preview" />
      <OutcomeView downloaded={false} onDownload={() => undefined} outcome={{
        mode: "sanitize", id: "00000000-0000-4000-8000-000000000000", state: "SUCCEEDED", fileType: "text",
        inputBytes: 48 * 1024 * 1024, outputBytes: 49 * 1024 * 1024, failure: null, outputState: "READY",
        vaultEntries: 1834, vaultExpires: "2027-01-14T00:00:00Z",
        counts: { ipv4: 120394, ipv6: 2210, cidr: 418, mac: 3301, hostname: 912, fqdn: 4410, email: 37, url_host: 290, device_name: 64 },
      }} />
    </ScreenRoot>
  );
}
