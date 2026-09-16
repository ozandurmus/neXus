import { useEffect, useState } from "react";
import { EmptyPanel } from "../shell/ScreenLayout";

interface LdapConfig {
  server_url: string;
  bind_dn: string;
  base_dn: string;
  search_filter: string;
  ca_certificate: string;
}

export function LdapConfigurationPanel() {
  const [config, setConfig] = useState<LdapConfig | null>(null);

  useEffect(() => {
    fetch("/ldap-configuration", { credentials: "include" })
      .then((res) => res.json())
      .then((data) => {
        if (data.server_url) {
          setConfig(data);
        }
      })
      .catch(() => {});
  }, []);

  return (
    <div style={{ padding: "16px" }}>
      {config ? (
        <div>
          <h3>LDAP Configuration</h3>
          <p><strong>Server URL:</strong> {config.server_url}</p>
          <p><strong>Bind DN:</strong> {config.bind_dn}</p>
          <p><strong>Base DN:</strong> {config.base_dn}</p>
          <p><strong>Search Filter:</strong> {config.search_filter}</p>
          <p><strong>CA Certificate:</strong> {config.ca_certificate ? "Configured" : "None"}</p>
        </div>
      ) : (
        <EmptyPanel title="No LDAP Configuration" body="No connection or trust settings have been configured yet." />
      )}
    </div>
  );
}
