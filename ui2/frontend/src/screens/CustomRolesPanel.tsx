import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Checkbox from "@mui/material/Checkbox";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { M3Button } from "../shell/M3Widgets";

type Role = { id: string; name: string; token_string?: string; tokenString?: string; description?: string; isSystem?: boolean; is_system?: boolean };
const permissionOptions = ["Read devices", "Manage devices", "Manage credentials", "View audit logs", "Manage roles"];

export function CustomRolesPanel() {
  const [roles, setRoles] = useState<Role[]>([]);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const refresh = () => fetch("/roles").then((response) => response.ok ? response.json() : Promise.reject()).then(setRoles).catch(() => setError("Unable to load roles."));
  useEffect(() => { void refresh(); }, []);
  return <Box sx={{ p: 2 }}><Stack spacing={2}>
    <Stack direction="row" justifyContent="space-between" alignItems="center"><Box><Typography variant="h5">Roles & permissions</Typography><Typography variant="body2" color="text.secondary">Create named roles for directory and local identities.</Typography></Box><M3Button emphasis="filled" onClick={() => setCreating(true)}>Create role</M3Button></Stack>
    {error && <Alert severity="error">{error}</Alert>}
    <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))" }}>
      {roles.map((role) => <Card key={role.id} variant="outlined"><CardContent><Stack spacing={1}><Typography variant="h6">{role.name}</Typography><Typography variant="body2" color="text.secondary">{role.description || "No description"}</Typography><Typography variant="body2" sx={{ fontFamily: "monospace" }}>{role.token_string || role.tokenString}</Typography><Typography variant="caption" color="text.secondary">{role.isSystem || role.is_system ? "System role (read-only)" : "Custom role"}</Typography></Stack></CardContent></Card>)}
      {!roles.length && <Typography color="text.secondary" sx={{ gridColumn: "1 / -1", textAlign: "center", py: 4 }}>No roles found.</Typography>}
    </Box>
  </Stack>{creating && <CreateRoleDialog onClose={() => setCreating(false)} onCreated={() => { setCreating(false); void refresh(); }} />}</Box>;
}

function CreateRoleDialog({ onClose, onCreated }: { readonly onClose: () => void; readonly onCreated: () => void }) {
  const [name, setName] = useState(""); const [token, setToken] = useState(""); const [description, setDescription] = useState(""); const [permissions, setPermissions] = useState<string[]>([]); const [error, setError] = useState<string | null>(null);
  const toggle = (permission: string) => setPermissions((selected) => selected.includes(permission) ? selected.filter((value) => value !== permission) : [...selected, permission]);
  const create = () => {
    if (!name.trim() || !token.trim()) { setError("Role name and token are required."); return; }
    fetch("/roles", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name, token_string: token, description, permissions }) }).then((response) => response.ok ? onCreated() : Promise.reject()).catch(() => setError("Unable to create role."));
  };
  return <Dialog open onClose={onClose} fullWidth maxWidth="sm"><DialogTitle>Create custom role</DialogTitle><DialogContent><Stack spacing={2} sx={{ pt: 1 }}><TextField required autoFocus label="Role name" value={name} onChange={(e) => setName(e.target.value)} /><TextField required label="Role token" value={token} onChange={(e) => setToken(e.target.value)} helperText="Example: role:network_operator" /><TextField label="Description" value={description} onChange={(e) => setDescription(e.target.value)} multiline minRows={2} /><Box><Typography variant="subtitle2">Permissions</Typography>{permissionOptions.map((permission) => <FormControlLabel key={permission} label={permission} control={<Checkbox checked={permissions.includes(permission)} onChange={() => toggle(permission)} />} />)}</Box>{error && <Alert severity="error">{error}</Alert>}</Stack></DialogContent><DialogActions><M3Button emphasis="text" onClick={onClose}>Cancel</M3Button><M3Button emphasis="filled" onClick={create}>Create role</M3Button></DialogActions></Dialog>;
}
