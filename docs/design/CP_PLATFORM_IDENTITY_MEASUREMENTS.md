# Check Point platform identity — measurement record (2026-09-22)

Source: the Product Owner ran the commands by hand in the Expert shell of an
R81.20 **management server** (Multi-Domain, open server) on 2026-09-22 and
pasted the output into the engineering session. Hostname and prompt withheld
(sensitive identity reporting law). No gateway or VSX member has been
measured yet; the parser is proven on this shape only.

## `fw ver`

```
This is Check Point's software version R81.20 - Build 059
```

Version already comes from `show version all` (first contact); `fw ver` is
not gated and not used.

## `cpinfo -y all` (gate `cp_identity_cpinfo_hotfixes`)

Shape: a build line, then `[PACKAGE]` headings each followed by indented
hotfix names, some with `Take:  N`. The main jumbo appears under several
headings with the same take:

```
This is Check Point CPinfo Build 914000274 for GAIA
[MGMT]
        HOTFIX_R81_20_JUMBO_HF_MAIN     Take:  122
[IDA]
        No hotfixes..
[FW1]
        HOTFIX_INEXT_NANO_EGG_AUTOUPDATE
        HOTFIX_GOT_MGMT_AUTOUPDATE
        HOTFIX_R81_20_JUMBO_HF_MAIN     Take:  122
        ...
[VSEC]
        HOTFIX_CLOUDGUARD_CONTROLLER_R81_20_AUTOUPDATE
        HOTFIX_R81_20_JUMBO_HF_MAIN            <- no take on this heading
[CPUpdates]
        BUNDLE_R81_20_JUMBO_HF_MAIN     Take:  122
        ... (auto-update bundles, each with its own take)
```

Field extracted: **hotfix level** = the first `HOTFIX_<release>_JUMBO_HF_MAIN
Take: N` line → `R81.20 Jumbo Take 122`. The auto-update bundles are not
kept. Output length on this server: ~150 lines; timeout 60 s.

## `uptime` (gate `cp_identity_uptime`)

```
 21:43:46 up 237 days, 21:10,  2 users,  load average: 0.87, 0.69, 0.58
```

Field extracted: **uptime** = the text between `up ` and the user count →
`237 days, 21:10`. Load average not kept.

## `clish -c "show asset system"` (gate `cp_identity_show_asset_system`)

Measured on a Smart-1 5150 appliance (management), 2026-09-22; the serial
below is a placeholder, the real value is withheld:

```
Platform: ST-4150-00
Model: Smart-1 5150
Serial Number: 0000000
CPU Model: Intel(R) Xeon(R) Gold 5118 CPU
CPU Frequency: 2300.000 Mhz
Number of Cores: 24
CPU Hyperthreading: Disabled
```

Fields extracted: **serial number** = `Serial Number:` (an open server's
`N/A` is treated as none), **platform family** = `Model (Platform)` →
`Smart-1 5150 (ST-4150-00)`. CPU lines not kept. A gateway appliance has
not been measured separately; the line shape is Gaia's, not the model's.
