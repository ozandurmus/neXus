import { useState, useEffect } from "react";
import { M3Card, M3Button } from "../shell/M3Widgets";

export function CustomRolesPanel() {
  const [roles, setRoles] = useState<any[]>([]);

  useEffect(() => {
    fetch("/api/v2/roles")
      .then(res => res.json())
      .then(data => setRoles(data))
      .catch(err => console.error("Failed to load roles", err));
  }, []);

  return (
    <div style={{ padding: "16px", display: "flex", flexDirection: "column", gap: "16px" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h2 style={{ margin: 0, fontSize: "1.25rem", fontWeight: 500 }}>Roles & Permissions</h2>
        <M3Button emphasis="filled">Create role</M3Button>
      </div>

      <div style={{ display: "grid", gap: "16px", gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))" }}>
        {roles.map(role => (
          <M3Card key={role.id}>
            <div style={{ padding: "16px" }}>
              <h3 style={{ margin: "0 0 8px 0", fontSize: "1rem" }}>{role.name}</h3>
              <p style={{ margin: "0 0 16px 0", fontSize: "0.875rem", color: "var(--md-sys-color-on-surface-variant)" }}>
                {role.description || "No description"}
              </p>
              <div style={{ fontSize: "0.75rem", fontFamily: "monospace", background: "var(--md-sys-color-surface-container)", padding: "4px 8px", borderRadius: "4px" }}>
                {role.token_string || role.tokenString}
              </div>
              <div style={{ marginTop: "16px", display: "flex", gap: "8px" }}>
                {!role.isSystem && !role.is_system && (
                  <>
                    <M3Button emphasis="outlined">Edit</M3Button>
                    <M3Button emphasis="text">Delete</M3Button>
                  </>
                )}
                {(role.isSystem || role.is_system) && (
                  <span style={{ fontSize: "0.75rem", color: "var(--md-sys-color-on-surface-variant)", alignSelf: "center" }}>System Role (Read-only)</span>
                )}
              </div>
            </div>
          </M3Card>
        ))}
        {roles.length === 0 && (
          <div style={{ padding: "32px", textAlign: "center", gridColumn: "1 / -1", color: "var(--md-sys-color-on-surface-variant)" }}>
            No roles found.
          </div>
        )}
      </div>
    </div>
  );
}
