import { ScreenHeader, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { AddDeviceDialogTrigger } from "../shell/AddDeviceDialog";
import { M3Button, M3Tabs } from "../shell/M3Widgets";
import { CredentialsPanel } from "./CredentialsPanel";
import { LocalIdentitiesPanel } from "./LocalIdentitiesPanel";
import { DeviceManagementPane } from "./DeviceRegistryPanel";

/** M3Administration with an empty registry. Enrollment is the one place a device enters the product. */
export function AdministrationScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Administration"
        subtitle="Device registry, collection scope and delivery plan. Enrollment is the one place a device enters the product."
        actions={
          <>
            <M3Button emphasis="outlined">Import from manager</M3Button>
            <AddDeviceDialogTrigger />
          </>
        }
      />
      <M3Tabs
        ariaLabel="Administration sections"
        tabs={[
          {
            label: "Device management",
            panel: <DeviceManagementPane />,
          },
          {
            label: "Inventory exclusions",
            panel: (
              <EmptyPanel
                title="No device excluded"
                body="Excluding a device removes it from inventory collection while it stays enrolled for
                      configuration evidence. No device has been excluded yet, because no device has been
                      enrolled yet."
              />
            ),
          },
          {
            label: "Credentials",
            panel: <CredentialsPanel />,
          },
          {
            label: "Project plan",
            panel: (
              <EmptyPanel
                title="No project plan"
                body="No delivery plan exists for this registry yet. The design canvas names this tab but does
                      not depict a populated view for it, so this build shows its absence rather than inventing
                      content the canvas does not specify."
              />
            ),
          },
          {
            label: "Local identities",
            panel: <LocalIdentitiesPanel />,
          },
        ]}
      />
    </ScreenRoot>
  );
}
