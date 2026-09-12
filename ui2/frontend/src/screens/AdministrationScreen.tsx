import { ScreenHeader, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import Box from "@mui/material/Box";

/** M3Administration with an empty registry. Enrollment is the one place a device enters the product. */
export function AdministrationScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Administration"
        subtitle="Device registry, collection scope and delivery plan. Enrollment is the one place a device enters the product."
      />
      <Box sx={{ flex: 1, minHeight: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) 320px", gap: 2 }}>
        <EmptyPanel title="Device registry · 0 entries" body="No device has been enrolled yet." />
        <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
          <EmptyPanel title="Enrollment" body="Enrolling a device grants read collection only." />
          <EmptyPanel title="Collection scope" body="No collection scope is configured yet." />
        </Box>
      </Box>
    </ScreenRoot>
  );
}
