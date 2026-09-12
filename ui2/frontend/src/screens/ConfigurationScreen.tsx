import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";

/** M3Configuration with no expected intent and no effective evidence yet. */
export function ConfigurationScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Configuration"
        subtitle="Expected intent from the management plane against effective device state · 0 settings classified"
      />
      <ListDetail
        list={<EmptyPanel title="No devices" body="Nothing is enrolled yet." />}
        detail={<EmptyPanel title="No alignment evidence" body="Alignment needs both an intent snapshot and a device read; neither exists yet." />}
      />
    </ScreenRoot>
  );
}
