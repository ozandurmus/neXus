import React, { useState, useEffect } from 'react';

export const DirectorySettingsPanel: React.FC = () => {
    const [profile, setProfile] = useState<any>(null);

    useEffect(() => {
        fetch('/config/ldap').then(r => r.json()).then(setProfile).catch(() => {});
    }, []);

    const handleSave = () => {
        fetch('/config/ldap', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(profile)
        });
    };

    if (!profile) return <div>Loading...</div>;

    return (
        <div>
            <h2>LDAP Configuration</h2>
            <input value={profile.host} onChange={e => setProfile({...profile, host: e.target.value})} placeholder="Host" />
            <button onClick={handleSave}>Save</button>
        </div>
    );
};
