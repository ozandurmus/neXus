import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Checkbox from "@mui/material/Checkbox";
import Chip from "@mui/material/Chip";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import Paper from "@mui/material/Paper";
import { M3Button } from "../shell/M3Widgets";
import {
  listRoleBindings,
  createDirectoryRoleBinding,
  revokeRoleBinding,
  type RoleBindingView,
} from "../auth/adminApi";

export type Role = {
  id: string;
  name: string;
  token_string?: string;
  tokenString?: string;
  description?: string;
  isSystem?: boolean;
  is_system?: boolean;
  permissions?: string[];
};

export const PRODUCT_PLANES = ["Devices", "Config", "Compliance", "Operations", "Admin"] as const;

export function CustomRolesPanel() {
  const [roles, setRoles] = useState<Role[]>([]);
  const [bindings, setBindings] = useState<RoleBindingView[]>([]);
  const [creatingRole, setCreatingRole] = useState(false);
  const [mappingGroup, setMappingGroup] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refreshRoles = () =>
    fetch("/roles")
      .then((response) => (response.ok ? response.json() : Promise.reject()))
      .then((res) => setRoles(Array.isArray(res) ? res : []))
      .catch(() => setError("Unable to load roles."));

  const refreshBindings = () =>
    listRoleBindings()
      .then((res) => setBindings(Array.isArray(res) ? res : []))
      .catch(() => setError("Unable to load directory group role bindings."));

  useEffect(() => {
    void refreshRoles();
    void refreshBindings();
  }, []);

  const handleRevokeBinding = async (bindingId: string) => {
    try {
      await revokeRoleBinding(bindingId);
      void refreshBindings();
    } catch {
      setError("Failed to revoke directory group role binding.");
    }
  };

  const directoryBindings = (Array.isArray(bindings) ? bindings : []).filter(
    (b) => b.binding_kind === "DIRECTORY_GROUP" || (!b.binding_kind && b.directory_profile_id)
  );

  return (
    <Box sx={{ p: 2 }}>
      <Stack spacing={4}>
        {/* Roles Section */}
        <Stack spacing={2}>
          <Stack direction="row" justifyContent="space-between" alignItems="center">
            <Box>
              <Typography variant="h5">Roles & permissions</Typography>
              <Typography variant="body2" color="text.secondary">
                Configured product plane permissions for directory and local identities.
              </Typography>
            </Box>
            <M3Button emphasis="filled" onClick={() => setCreatingRole(true)}>
              Create role
            </M3Button>
          </Stack>

          {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

          <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))" }}>
            {roles.map((role) => {
              const token = role.token_string || role.tokenString || "";
              const isSys = role.isSystem || role.is_system;
              const rolePlanes = role.permissions || [];
              return (
                <Card key={role.id || token} variant="outlined">
                  <CardContent>
                    <Stack spacing={1.5}>
                      <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                        <Typography variant="h6">{role.name}</Typography>
                        <Chip
                          label={isSys ? "System" : "Custom"}
                          size="small"
                          color={isSys ? "default" : "primary"}
                          variant="outlined"
                        />
                      </Stack>
                      <Typography variant="body2" color="text.secondary">
                        {role.description || "No description provided."}
                      </Typography>
                      <Typography variant="caption" sx={{ fontFamily: "monospace", color: "text.secondary" }}>
                        {token}
                      </Typography>
                      <Box>
                        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 0.5 }}>
                          Allowed product planes:
                        </Typography>
                        <Stack direction="row" spacing={0.5} flexWrap="wrap" sx={{ gap: 0.5 }}>
                          {rolePlanes.length > 0 ? (
                            rolePlanes.map((plane) => (
                              <Chip key={plane} label={plane} size="small" variant="filled" />
                            ))
                          ) : (
                            <Typography variant="caption" color="text.disabled">
                              None
                            </Typography>
                          )}
                        </Stack>
                      </Box>
                    </Stack>
                  </CardContent>
                </Card>
              );
            })}
            {!roles.length && (
              <Typography color="text.secondary" sx={{ gridColumn: "1 / -1", textAlign: "center", py: 4 }}>
                No roles found.
              </Typography>
            )}
          </Box>
        </Stack>

        {/* Directory Group Mappings Section */}
        <Stack spacing={2}>
          <Stack direction="row" justifyContent="space-between" alignItems="center">
            <Box>
              <Typography variant="h5">Active Directory Group Mappings</Typography>
              <Typography variant="body2" color="text.secondary">
                Map LDAP / Active Directory security groups directly to product roles.
              </Typography>
            </Box>
            <M3Button emphasis="filled" onClick={() => setMappingGroup(true)}>
              Map directory group
            </M3Button>
          </Stack>

          <TableContainer component={Paper} variant="outlined">
            <Table size="small" aria-label="Directory Group Role Mappings">
              <TableHead>
                <TableRow>
                  <TableCell>Directory Group (Name or DN)</TableCell>
                  <TableCell>Mapped Role</TableCell>
                  <TableCell>Directory Profile</TableCell>
                  <TableCell>Created At</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {directoryBindings.map((binding) => (
                  <TableRow key={binding.binding_id}>
                    <TableCell sx={{ fontFamily: "monospace", fontWeight: 500 }}>
                      {binding.group_reference}
                    </TableCell>
                    <TableCell sx={{ fontFamily: "monospace" }}>{binding.role_token}</TableCell>
                    <TableCell>{binding.directory_profile_id || "default"}</TableCell>
                    <TableCell>
                      {binding.created_at ? new Date(binding.created_at).toLocaleString() : "—"}
                    </TableCell>
                    <TableCell align="right">
                      <M3Button
                        emphasis="text"
                        onClick={() => void handleRevokeBinding(binding.binding_id)}
                      >
                        Revoke
                      </M3Button>
                    </TableCell>
                  </TableRow>
                ))}
                {directoryBindings.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={5} align="center" sx={{ py: 3, color: "text.secondary" }}>
                      No directory group mappings configured.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
        </Stack>
      </Stack>

      {creatingRole && (
        <CreateRoleDialog
          onClose={() => setCreatingRole(false)}
          onCreated={() => {
            setCreatingRole(false);
            void refreshRoles();
          }}
        />
      )}

      {mappingGroup && (
        <MapDirectoryGroupDialog
          roles={roles}
          onClose={() => setMappingGroup(false)}
          onMapped={() => {
            setMappingGroup(false);
            void refreshBindings();
          }}
        />
      )}
    </Box>
  );
}

function CreateRoleDialog({
  onClose,
  onCreated,
}: {
  readonly onClose: () => void;
  readonly onCreated: () => void;
}) {
  const [name, setName] = useState("");
  const [token, setToken] = useState("");
  const [description, setDescription] = useState("");
  const [permissions, setPermissions] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const toggle = (plane: string) =>
    setPermissions((selected) =>
      selected.includes(plane) ? selected.filter((value) => value !== plane) : [...selected, plane]
    );

  const create = () => {
    if (!name.trim() || !token.trim()) {
      setError("Role name and token are required.");
      return;
    }
    fetch("/roles", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, token_string: token, description, permissions }),
    })
      .then((response) => (response.ok ? onCreated() : Promise.reject()))
      .catch(() => setError("Unable to create role."));
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Create custom role</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField
            required
            autoFocus
            label="Role name"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <TextField
            required
            label="Role token"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            helperText="Identifier string, e.g. custom_operator or noc_analyst"
          />
          <TextField
            label="Description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
          />
          <Box>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>
              Product Planes (Permissions)
            </Typography>
            <Stack direction="row" flexWrap="wrap" gap={1}>
              {PRODUCT_PLANES.map((plane) => (
                <FormControlLabel
                  key={plane}
                  label={plane}
                  control={
                    <Checkbox
                      checked={permissions.includes(plane)}
                      onChange={() => toggle(plane)}
                    />
                  }
                />
              ))}
            </Stack>
          </Box>
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <M3Button emphasis="text" onClick={onClose}>
          Cancel
        </M3Button>
        <M3Button emphasis="filled" onClick={create}>
          Create role
        </M3Button>
      </DialogActions>
    </Dialog>
  );
}

function MapDirectoryGroupDialog({
  roles,
  onClose,
  onMapped,
}: {
  readonly roles: Role[];
  readonly onClose: () => void;
  readonly onMapped: () => void;
}) {
  const [selectedRoleToken, setSelectedRoleToken] = useState(
    roles.length > 0 ? roles[0].token_string || roles[0].tokenString || "" : ""
  );
  const [groupReference, setGroupReference] = useState("");
  const [directoryProfileId, setDirectoryProfileId] = useState("default");
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    if (!selectedRoleToken || !groupReference.trim()) {
      setError("Role and Directory Group Reference are required.");
      return;
    }
    try {
      await createDirectoryRoleBinding({
        roleToken: selectedRoleToken,
        groupReference: groupReference.trim(),
        directoryProfileId: directoryProfileId.trim() || "default",
      });
      onMapped();
    } catch {
      setError("Failed to map directory group to role.");
    }
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Map Directory Group to Role</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField
            select
            required
            label="Role"
            value={selectedRoleToken}
            onChange={(e) => setSelectedRoleToken(e.target.value)}
            helperText="Select the role to grant members of this directory group"
          >
            {roles.map((r) => {
              const token = r.token_string || r.tokenString || "";
              return (
                <MenuItem key={r.id || token} value={token}>
                  {r.name} ({token})
                </MenuItem>
              );
            })}
          </TextField>

          <TextField
            required
            autoFocus
            label="Directory Group (Name or DN)"
            placeholder="e.g. Domain Admins or CN=NetOps,OU=Groups,DC=nexus,DC=local"
            value={groupReference}
            onChange={(e) => setGroupReference(e.target.value)}
            helperText="The exact AD / LDAP group name or distinguished name"
          />

          <TextField
            label="Directory Profile ID"
            value={directoryProfileId}
            onChange={(e) => setDirectoryProfileId(e.target.value)}
            helperText="Default profile is 'default'"
          />

          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <M3Button emphasis="text" onClick={onClose}>
          Cancel
        </M3Button>
        <M3Button emphasis="filled" onClick={() => void handleSubmit()}>
          Save Mapping
        </M3Button>
      </DialogActions>
    </Dialog>
  );
}

