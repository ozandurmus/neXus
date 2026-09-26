import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { DeviceInventoryPanels } from "../src/screens/InventoryPanels";
import { DeviceList, InventoryScreen } from "../src/screens/InventoryScreen";
import type { DeviceSummary } from "../src/auth/adminApi";
const manager = { device_id:"smc", hostname:"FW-DELTA-01", vendor_hint:"bluecoat", role:"management_server",
  enrollment_state:"ENROLLED", cluster_member_ref:null, virtual_systems:"FW-TANGO-04, FW-BRAVO-02",
  model:"Symantec Management Center", software_version:null, ha_role:null } as DeviceSummary;
const members = ["FW-TANGO-04", "FW-BRAVO-02"].map((name,i)=>({ virtual_system:name,address:`192.0.2.${i+1}`,
  platform:"ProxySG",hardware_type:"SG-Enterprise",hypervisor:"SGOS 7.4",node_status:"FULLY_MANAGED",
  services:[],grid_master:false,master_candidate:false,ha_enabled:false }));
afterEach(()=>vi.unstubAllGlobals());
it("opens SMC on collected managed devices and selects the clicked child with its address",async()=>{
  vi.stubGlobal("fetch",vi.fn(async(input:RequestInfo|URL)=>new Response(JSON.stringify(String(input).includes('/inventory')
    ? {device_id:"smc",collected_at:"2026-09-26T10:00:00Z",contexts:[],grid_members:members,job:null} : {}))));
  const view=render(<ThemeProvider theme={m3Theme}><DeviceInventoryPanels device={manager}/></ThemeProvider>);
  expect(screen.getByRole('tab',{name:'Managed devices'})).toHaveAttribute('aria-selected','true');
  fireEvent.click(await screen.findByRole('button',{name:'FW-TANGO-04'}));
  expect(within(screen.getByRole('table')).getByText('192.0.2.1')).toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'FW-BRAVO-02'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button',{name:'All managed devices'}));
  expect(screen.getByRole('button',{name:'FW-BRAVO-02'})).toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab',{name:'Interfaces'}));
  view.rerender(<ThemeProvider theme={m3Theme}><DeviceInventoryPanels device={manager} initialVs="FW-BRAVO-02"/></ThemeProvider>);
  expect(screen.getByRole('tab',{name:'Managed devices'})).toHaveAttribute('aria-selected','true');
  expect(within(screen.getByRole('table')).getByText('192.0.2.2')).toBeInTheDocument();
});
it("passes the selected listed child from the manager sidebar",async()=>{
  vi.stubGlobal("fetch",vi.fn(async()=>new Response(JSON.stringify({device_id:"smc",vendor:"bluecoat",run_id:null,domains:[],counts:{}}))));
  const select=vi.fn();
  render(<ThemeProvider theme={m3Theme}><DeviceList groupByManager devices={[manager]} selectedDeviceId={null}
    selectedClusterRef={null} onSelectDevice={select} onSelectCluster={vi.fn()}/></ThemeProvider>);
  fireEvent.click(await screen.findByRole('button',{name:'Expand managed devices'}));
  fireEvent.click(screen.getByRole('button',{name:/FW-TANGO-04/}));
  expect(select).toHaveBeenCalledWith(manager,"FW-TANGO-04");
});

it("includes collected vendors beyond Check Point and Palo Alto in the inventory filter",async()=>{
  vi.stubGlobal("fetch",vi.fn(async(input:RequestInfo|URL)=>new Response(JSON.stringify(String(input)==="/devices"
    ? {devices:[manager]} : {domains:[],counts:{}}))));
  render(<ThemeProvider theme={m3Theme}><InventoryScreen/></ThemeProvider>);
  expect(await screen.findByRole("option",{name:/Symantec \/ Blue Coat/})).toBeInTheDocument();
});
