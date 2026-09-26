import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ThemeProvider } from '@mui/material/styles';
import { m3Theme } from '../src/theme/m3Theme';
import { DeviceConfigurationDetail } from '../src/screens/ConfigurationDetail';
import type { DeviceSummary } from '../src/auth/adminApi';
afterEach(()=>vi.unstubAllGlobals());
it('reloads the projected configuration after Collect now finishes',async()=>{
 let collected=false; let reads=0;
 vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL,init?:RequestInit)=>{
  const url=String(input); const reply=(body:unknown)=>new Response(JSON.stringify(body));
  if(url==='/session/status') return reply({csrf_token:'synthetic-token'});
  if(url.endsWith('/configuration/collect') && init?.method==='POST'){collected=true;return reply({job_id:'job-1'});}
  if(url==='/devices/dev-1')return reply({job:{job_id:'job-1',state:'COMPLETED'}});
  if(url.endsWith('/configuration/text')){reads++;return new Response('set hostname FW-TANGO-04');}
  if(url.endsWith('/configuration'))return reply({device_id:'dev-1',vendor:'check_point',collected_at:collected?'2026-09-26T10:00:00Z':null,
    sanitized_text_available:collected,index:[],overrides:[],withheld_line_count:0});
  return reply({});
 }));
 const device={device_id:'dev-1',hostname:'FW-TANGO-04',vendor_hint:'check_point',role:'gateway',cluster_member_ref:null,
   virtual_systems:null,model:null,software_version:null,ha_role:null} as DeviceSummary;
 render(<ThemeProvider theme={m3Theme}><DeviceConfigurationDetail device={device} embedded/></ThemeProvider>);
 await screen.findByText('No configuration read yet');
 fireEvent.click(screen.getByRole('button',{name:'Collect now'}));
 await screen.findByText(/projected settings/);
 expect(reads).toBe(1);
 expect(screen.queryByText('No configuration read yet')).not.toBeInTheDocument();
});
