import { describe, expect, it } from "vitest";

import { filterProjection, projectCheckPoint, projectCluster, projectPaloAlto } from "../src/screens/configurationProjection";

const CP_A = `# SecurityExpert Check Point Gaia configuration evidence (redacted)
# secret-bearing-lines-withheld=2
set hostname FW-TANGO-01
set domainname example.test
set timezone Europe / Istanbul
set dns primary 192.0.2.53
set dns secondary 192.0.2.54
# [SECURITYEXPERT SECRET-BEARING CONFIGURATION LINE WITHHELD]
set ntp active on
set ntp server primary 192.0.2.10 version 4
set ntp server secondary 192.0.2.11 version 4
set inactivity-timeout 720
set management interface Mgmt
set password-controls min-password-length 14
set message banner on
set syslog filename /var/log/messages
set cluster member mvc off
set interface eth1-01 state on
set interface eth1-01 ipv4-address 192.0.2.1 mask-length 28
set static-route default nexthop gateway address 192.0.2.254 on
set snmp agent on
set aaa tacacs-servers state off
set user admin shell /bin/bash
set lcd screensaver mode model
`;

const CP_B = CP_A.replace("FW-TANGO-01", "FW-TANGO-02").replace("192.0.2.1 mask-length 28", "192.0.2.2 mask-length 28")
  .replace("set ntp server secondary 192.0.2.11 version 4", "set ntp server secondary 192.0.2.99 version 4");

describe("Check Point projection", () => {
  it("files every set line under its section with a readable setting and the value, and counts withheld lines", () => {
    const p = projectCheckPoint(CP_A);
    const labels = p.sections.map((s) => s.label);
    expect(labels).toEqual(["System", "DNS", "NTP", "Management", "Password Policy", "Login Banner", "Logging",
      "High Availability", "Interfaces", "Routing", "SNMP", "AAA", "Users", "Other Gaia Configuration"]);
    const dns = p.sections.find((s) => s.label === "DNS")!.rows.map((r) => [r.setting, r.value]);
    expect(dns).toEqual([["Primary DNS", "192.0.2.53"], ["Secondary DNS", "192.0.2.54"]]);
    const ntp = p.sections.find((s) => s.label === "NTP")!.rows.map((r) => [r.setting, r.value]);
    expect(ntp).toEqual([["NTP · Active", "on"], ["Primary NTP Server", "192.0.2.10 version 4"], ["Secondary NTP Server", "192.0.2.11 version 4"]]);
    const iface = p.sections.find((s) => s.label === "Interfaces")!.rows;
    expect(iface[1]).toMatchObject({ setting: "Interface eth1-01 · IPv4 Address", value: "192.0.2.1 mask-length 28", origin: "MEMBER" });
    expect(p.sections.find((s) => s.label === "System")!.rows[0]).toMatchObject({ setting: "Hostname", origin: "MEMBER" });
    expect(p.withheldCount).toBe(2);
    expect(p.settingCount).toBe(21);
    expect(p.snapshot.map((t) => t.label)).toEqual(["Hostname", "Domain", "Timezone", "Primary DNS", "Secondary DNS", "Primary NTP Server", "Secondary NTP Server", "Management Interface", "Cluster Member"]);
  });

  it("a cluster view marks a real difference DIFF and a member-specific difference as expected", () => {
    const c = projectCluster([{ id: "m1", projection: projectCheckPoint(CP_A) }, { id: "m2", projection: projectCheckPoint(CP_B) }]);
    const ntp = c.sections.find((s) => s.label === "NTP")!.rows.find((r) => r.setting === "Secondary NTP Server")!;
    expect(ntp.diff).toBe(true);
    expect(ntp.values).toEqual({ m1: "192.0.2.11 version 4", m2: "192.0.2.99 version 4" });
    const host = c.sections.find((s) => s.label === "System")!.rows.find((r) => r.setting === "Hostname")!;
    expect(host.diff).toBe(false);
    expect(host.memberSpecific).toBe(true);
    const ip = c.sections.find((s) => s.label === "Interfaces")!.rows.find((r) => r.setting === "Interface eth1-01 · IPv4 Address")!;
    expect(ip.diff).toBe(false);
    expect(c.diffCount).toBe(1);
  });

  it("filters by setting, value or section", () => {
    const p = projectCheckPoint(CP_A);
    expect(filterProjection(p.sections, "ntp").map((s) => s.label)).toEqual(["NTP"]);
    expect(filterProjection(p.sections, "192.0.2.53")[0].rows[0].setting).toBe("Primary DNS");
  });
});

describe("Palo Alto projection", () => {
  const XML = `<response status="success"><result><config>
    <devices><entry name="localhost.localdomain">
      <deviceconfig><system>
        <hostname>FW-PALT-01</hostname><timezone>Europe/Istanbul</timezone>
        <dns-setting><servers><primary>192.0.2.53</primary><secondary>192.0.2.54</secondary></servers></dns-setting>
        <ntp-servers><primary-ntp-server><ntp-server-address>192.0.2.10</ntp-server-address></primary-ntp-server></ntp-servers>
        <permitted-ip><entry name="192.0.2.0/24"/></permitted-ip>
      </system>
      <high-availability><enabled>yes</enabled><group><group-id>20</group-id></group></high-availability>
      </deviceconfig>
      <network><interface><ethernet><entry name="ethernet1/1"><layer3/></entry><entry name="ethernet1/2"><layer3/></entry></ethernet></interface>
        <virtual-router><entry name="default"/></virtual-router></network>
      <vsys><entry name="vsys1"><zone><entry name="trust"/><entry name="untrust"/></zone></entry></vsys>
    </entry></devices>
    <mgt-config><users><entry name="admin"><phash>[REDACTED]</phash><permissions><role-based><superuser>yes</superuser></role-based></permissions></entry></users></mgt-config>
  </config></result></response>`;

  it("flattens leaves into sections, redacts secrets as withheld, and counts network objects", () => {
    const p = projectPaloAlto(XML);
    const byLabel = Object.fromEntries(p.sections.map((s) => [s.label, s.rows.map((r) => [r.setting, r.value])]));
    expect(byLabel["DNS"]).toEqual([["Primary DNS", "192.0.2.53"], ["Secondary DNS", "192.0.2.54"]]);
    expect(byLabel["NTP"][0]).toEqual(["Primary NTP Server", "192.0.2.10"]);
    expect(byLabel["High Availability"]).toEqual([["High Availability · Enabled", "yes"], ["High Availability · Group · Group ID", "20"]]);
    expect(byLabel["Network Configuration"]).toEqual([["VSYS", "1"], ["Virtual Routers", "1"], ["Zones", "2"], ["Interfaces", "2"]]);
    expect(byLabel["Users"]).toEqual([["Permissions · Role Based · Superuser · admin", "yes"]]);
    expect(p.withheldCount).toBe(1);
    expect(p.snapshot.find((t) => t.label === "Hostname")?.value).toBe("FW-PALT-01");
    expect(p.snapshot.find((t) => t.label === "Group ID")?.value).toBe("20");
  });
});
