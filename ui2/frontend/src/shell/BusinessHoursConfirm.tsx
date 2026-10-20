import { useEffect, useState, type ReactNode } from "react";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogContentText from "@mui/material/DialogContentText";
import DialogTitle from "@mui/material/DialogTitle";
import { setBusinessHoursConfirm } from "../auth/adminApi";

interface Pending { readonly message: string; readonly resolve: (confirmed: boolean) => void }

/**
 * PO decision 2026-10-20: an administrator's manual device job inside business hours (08:00–20:00 Europe/Istanbul)
 * is confirmed here before it is resubmitted with the acknowledgement. The server decides when to ask (409
 * BUSINESS_HOURS_CONFIRM_REQUIRED); this dialog only shows its words and returns the answer.
 */
export function BusinessHoursConfirmProvider({ children }: { readonly children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  useEffect(() => setBusinessHoursConfirm(message => new Promise<boolean>(resolve => setPending({ message, resolve }))), []);
  const answer = (confirmed: boolean) => {
    pending?.resolve(confirmed);
    setPending(null);
  };
  return (
    <>
      {children}
      <Dialog open={pending !== null} onClose={() => answer(false)} maxWidth="xs" fullWidth
        aria-labelledby="business-hours-confirm-title" data-testid="business-hours-confirm">
        <DialogTitle id="business-hours-confirm-title">Business hours</DialogTitle>
        <DialogContent>
          <DialogContentText>{pending?.message}</DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => answer(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => answer(true)} autoFocus>Continue</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
