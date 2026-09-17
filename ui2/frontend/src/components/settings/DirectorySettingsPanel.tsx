import React, { useState, useEffect } from "react";
import Box from "@mui/material/Box";
import TextField from "@mui/material/TextField";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import FormControlLabel from "@mui/material/FormControlLabel";
import Typography from "@mui/material/Typography";
import Alert from "@mui/material/Alert";

import { M3Button } from "../../shell/M3Widgets";

export function DirectorySettingsPanel() {
    const [profile, setProfile] = useState<any>(null);
    const [saving, setSaving] = useState(false);
    const [message, setMessage] = useState<{ text: string, type: "success" | "error" } | null>(null);

    useEffect(() => {
        fetch('/config/ldap').then(async r => {
            const text = await r.text();
            if (text) {
                setProfile(JSON.parse(text));
            } else {
                setProfile({
                    id: crypto.randomUUID(),
                    profileName: 'Default LDAP',
                    host: '',
                    port: 389,
                    transport: 'LDAPS',
                    trustFormat: 'PEM',
                    trustMaterialPem: '',
                    storePinEncrypted: '',
                    bindDnTemplate: '',
                    groupSearchBaseDn: '',
                    accessGroupReference: '',
                    isActive: true
                });
            }
        }).catch(err => {
            console.error(err);
        });
    }, []);

    const handleSave = async () => {
        setSaving(true);
        setMessage(null);
        try {
            const res = await fetch('/config/ldap', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(profile)
            });
            if (res.ok) {
                const text = await res.text();
                if (text) {
                    setProfile(JSON.parse(text));
                }
                setMessage({ text: "Settings saved successfully", type: "success" });
            } else {
                setMessage({ text: "Failed to save settings", type: "error" });
            }
        } catch (e) {
            setMessage({ text: "Error saving settings", type: "error" });
        } finally {
            setSaving(false);
        }
    };

    const handleChange = (field: string, value: any) => {
        setProfile((prev: any) => ({ ...prev, [field]: value }));
    };

    if (!profile) return <Box p={3}>Loading...</Box>;

    return (
        <Box sx={{ maxWidth: 800, p: 2 }}>
            <Typography variant="h6" sx={{ mb: 3 }}>LDAP Directory Settings</Typography>
            
            {message && (
                <Alert severity={message.type} sx={{ mb: 3 }}>
                    {message.text}
                </Alert>
            )}

            <Stack spacing={3}>
                <TextField
                    label="Profile Name"
                    value={profile.profileName}
                    onChange={(e) => handleChange('profileName', e.target.value)}
                    fullWidth
                    size="small"
                />
                
                <Stack direction="row" spacing={2}>
                    <TextField
                        label="Host"
                        value={profile.host}
                        onChange={(e) => handleChange('host', e.target.value)}
                        fullWidth
                        size="small"
                    />
                    <TextField
                        label="Port"
                        type="number"
                        value={profile.port}
                        onChange={(e) => handleChange('port', parseInt(e.target.value, 10))}
                        size="small"
                        sx={{ width: 150 }}
                    />
                </Stack>

                <Stack direction="row" spacing={2}>
                    <TextField
                        label="Transport"
                        value={profile.transport}
                        onChange={(e) => handleChange('transport', e.target.value)}
                        size="small"
                        sx={{ width: 150 }}
                    />
                    <TextField
                        label="Trust Format"
                        value={profile.trustFormat}
                        onChange={(e) => handleChange('trustFormat', e.target.value)}
                        size="small"
                        sx={{ width: 150 }}
                    />
                </Stack>

                <TextField
                    label="Bind DN Template"
                    value={profile.bindDnTemplate}
                    onChange={(e) => handleChange('bindDnTemplate', e.target.value)}
                    fullWidth
                    size="small"
                    helperText="e.g. uid={0},ou=people,dc=example,dc=com"
                />

                <TextField
                    label="Group Search Base DN"
                    value={profile.groupSearchBaseDn}
                    onChange={(e) => handleChange('groupSearchBaseDn', e.target.value)}
                    fullWidth
                    size="small"
                />

                <TextField
                    label="Access Group Reference"
                    value={profile.accessGroupReference}
                    onChange={(e) => handleChange('accessGroupReference', e.target.value)}
                    fullWidth
                    size="small"
                />

                <TextField
                    label="Trust Material (PEM)"
                    value={profile.trustMaterialPem}
                    onChange={(e) => handleChange('trustMaterialPem', e.target.value)}
                    fullWidth
                    multiline
                    rows={4}
                    size="small"
                />

                <TextField
                    label="Store PIN (Encrypted)"
                    type="password"
                    value={profile.storePinEncrypted}
                    onChange={(e) => handleChange('storePinEncrypted', e.target.value)}
                    fullWidth
                    size="small"
                />

                <FormControlLabel
                    control={
                        <Switch
                            checked={profile.isActive}
                            onChange={(e) => handleChange('isActive', e.target.checked)}
                            color="primary"
                        />
                    }
                    label="Active"
                />

                <Box>
                    <M3Button emphasis="filled" onClick={handleSave} disabled={saving}>
                        {saving ? "Saving..." : "Save Settings"}
                    </M3Button>
                </Box>
            </Stack>
        </Box>
    );
}
