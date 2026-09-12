import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";

/** M3Inventory with nothing enrolled. */
export function InventoryScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader title="Network inventory" subtitle="0 logical views live · nothing collected yet" />
      <ListDetail
        list={<EmptyPanel title="No devices" body="Nothing is enrolled yet." />}
        detail={<EmptyPanel title="No device selected" body="Enrol a device from Administration to see it here." />}
      />
    </ScreenRoot>
  );
}
