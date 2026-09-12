import { useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { M3Button } from "./M3Widgets";

/**
 * The `M3Components` enrollment dialog. The canvas's own mock fills every
 * field with a specific example device; that value is device-name-shaped
 * and stays out of source, so every field here opens blank instead.
 *
 * The canvas implies a submit that enrolls a device -- a write this
 * movement has no backend path for. "Enrol" only closes the dialog; nothing
 * is sent anywhere and nothing is persisted, and the dialog says so rather
 * than showing a success state for an enrollment that did not happen.
 */
export function AddDeviceDialogTrigger() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <M3Button emphasis="filled" icon="plus" onClick={() => setOpen(true)}>
        Add device
      </M3Button>
      <Dialog open={open} onClose={() => setOpen(false)} PaperProps={{ sx: { borderRadius: "28px", width: 420 } }}>
        <DialogContent sx={{ p: 3, display: "flex", flexDirection: "column", gap: 2 }}>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            <Typography variant="h3">Add device</Typography>
            <Typography variant="body1" sx={{ color: m3.onSurfaceVar }}>
              Enrolling a device grants read collection only. Backup creation stays off until the
              device enters the pilot allowlist.
            </Typography>
          </Box>
          <Stack spacing={1.5}>
            <TextField label="Device name" placeholder="Not entered yet" size="small" fullWidth />
            <TextField label="Vendor" placeholder="Not selected yet" size="small" fullWidth />
            <TextField label="Transport" placeholder="Not selected yet" size="small" fullWidth />
            <TextField label="Credential profile" placeholder="Not selected yet" size="small" fullWidth />
          </Stack>
          <Typography variant="body2">
            Credentials are stored outside the repository. This dialog does not submit anywhere in
            this build; no device is created by closing it.
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 3 }}>
          <Button onClick={() => setOpen(false)} sx={{ textTransform: "none", color: m3.primary }}>
            Cancel
          </Button>
          <M3Button emphasis="filled" onClick={() => setOpen(false)}>
            Enrol
          </M3Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
