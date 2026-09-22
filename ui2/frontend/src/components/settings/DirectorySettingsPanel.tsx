import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { M3Button } from "../../shell/M3Widgets";

type DirectoryProfile = {
  id: string; profileName: string; host: string; port: number; transport: "LDAPS" | "STARTTLS"; trustFormat: "PEM" | "PKCS12";
  trustMaterialPem: string; storePinEncrypted: string; bindDnTemplate: string; groupSearchBaseDn: string; accessGroupReference: string; isActive: boolean;
};

/** crypto.randomUUID exists only in a secure context; the console is also served over plain HTTP. */
function newProfileId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const emptyProfile = (): DirectoryProfile => ({
  id: newProfileId(), profileName: "Default LDAP", host: "", port: 636, transport: "LDAPS", trustFormat: "PEM", trustMaterialPem: "", storePinEncrypted: "", bindDnTemplate: "", groupSearchBaseDn: "", accessGroupReference: "", isActive: true,
});

export function DirectorySettingsPanel() {
  const [profile, setProfile] = useState<DirectoryProfile | null>(null);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ text: string; type: "success" | "error" } | null>(null);

  useEffect(() => {
    // No saved profile yet is a 200 with an empty body: that is "start a new profile", not an error.
    fetch("/api/v2/config/ldap").then(async (response) => {
      if (!response.ok) return Promise.reject();
      const text = await response.text();
      return text.trim() ? JSON.parse(text) : null;
    })
      .then((saved) => setProfile(saved ? { ...saved, transport: saved.transport === "STARTTLS" ? "STARTTLS" : "LDAPS" } : emptyProfile())).catch(() => {
        setProfile(emptyProfile());
        setMessage({ text: "Unable to load LDAP settings; enter values to create a profile.", type: "error" });
      });
  }, []);
  const change = <K extends keyof DirectoryProfile>(field: K, value: DirectoryProfile[K]) => setProfile((current) => current ? { ...current, [field]: value } : current);
  const handleSave = async () => {
    if (!profile) return;
    if (!profile.host.trim() || profile.port < 1 || profile.port > 65535) { setMessage({ text: "Enter a directory host and a valid port.", type: "error" }); return; }
    setSaving(true); setMessage(null);
    try {
      const response = await fetch("/api/v2/config/ldap", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(profile) });
      if (!response.ok) throw new Error();
      setProfile(await response.json()); setMessage({ text: "LDAP settings saved.", type: "success" });
    } catch { setMessage({ text: "Unable to save LDAP settings.", type: "error" }); } finally { setSaving(false); }
  };
  if (!profile) return <Box p={3}>Loading LDAP settings…</Box>;
  return <Box sx={{ maxWidth: 820, p: 2 }}><Stack spacing={3}>
    <Box><Typography variant="h5">LDAP directory</Typography><Typography variant="body2" color="text.secondary">Configure the directory connection and group used for operator access.</Typography></Box>
    {message && <Alert severity={message.type}>{message.text}</Alert>}
    <Card variant="outlined"><CardContent><Stack spacing={2}><Typography variant="h6">Connection</Typography>
      <TextField label="Profile name" value={profile.profileName} onChange={(e) => change("profileName", e.target.value)} fullWidth />
      <Stack direction={{ xs: "column", sm: "row" }} spacing={2}><TextField required label="Directory host" value={profile.host} onChange={(e) => change("host", e.target.value)} fullWidth autoComplete="off" /><TextField required label="Port" type="number" value={profile.port} onChange={(e) => change("port", Number(e.target.value))} inputProps={{ min: 1, max: 65535 }} sx={{ width: { sm: 150 } }} /><TextField select label="Transport" value={profile.transport} onChange={(e) => change("transport", e.target.value as DirectoryProfile["transport"])} sx={{ width: { sm: 150 } }}><MenuItem value="LDAPS">LDAPS (636)</MenuItem><MenuItem value="STARTTLS">StartTLS (389)</MenuItem></TextField></Stack>
      <FormControlLabel control={<Switch checked={profile.isActive} onChange={(e) => change("isActive", e.target.checked)} />} label="Use this directory for sign-in" />
    </Stack></CardContent></Card>
    <Card variant="outlined"><CardContent><Stack spacing={2}><Typography variant="h6">Directory lookup</Typography><TextField label="Bind DN template" value={profile.bindDnTemplate} onChange={(e) => change("bindDnTemplate", e.target.value)} helperText="Example: uid={0},ou=people,dc=example,dc=com" fullWidth /><TextField label="Group search base DN" value={profile.groupSearchBaseDn} onChange={(e) => change("groupSearchBaseDn", e.target.value)} fullWidth /><TextField label="Access group reference" value={profile.accessGroupReference} onChange={(e) => change("accessGroupReference", e.target.value)} helperText="Only members of this group can receive directory roles." fullWidth /></Stack></CardContent></Card>
    <Card variant="outlined"><CardContent><Stack spacing={2}><Typography variant="h6">TLS trust</Typography><TextField select label="Trust material format" value={profile.trustFormat} onChange={(e) => change("trustFormat", e.target.value as DirectoryProfile["trustFormat"])} sx={{ maxWidth: 240 }}><MenuItem value="PEM">PEM certificate</MenuItem><MenuItem value="PKCS12">PKCS#12 trust store</MenuItem></TextField><TextField label="Trust material" value={profile.trustMaterialPem} onChange={(e) => change("trustMaterialPem", e.target.value)} multiline minRows={4} fullWidth /><TextField label="Trust-store PIN" type="password" value={profile.storePinEncrypted} onChange={(e) => change("storePinEncrypted", e.target.value)} autoComplete="new-password" fullWidth /></Stack></CardContent></Card>
    <Box><M3Button emphasis="filled" onClick={handleSave} disabled={saving}>{saving ? "Saving…" : "Save LDAP settings"}</M3Button></Box>
  </Stack></Box>;
}
