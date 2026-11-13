import { useEffect, useState } from "react";
import { Autocomplete, Box, TextField, Typography } from "@mui/material";

import { getPolicy } from "../auth/adminApi";
import { m3 } from "../theme/m3Theme";

/**
 * The rule of a One-rule analysis (RULE_OBJECT_USAGE_CONTRACT.md, section 3): picked from the stored snapshot's rules,
 * or typed as its rule UID / id. Either way exactly one string leaves this control, and the server resolves it against
 * the snapshot: a pasted value that matches no rule, or several, is refused there. Nothing is matched by similarity here.
 */
export interface RuleChoice {
  readonly ruleId: string;
  /** False when the value was typed or pasted rather than picked from the snapshot. */
  readonly picked: boolean;
  readonly number?: number; readonly name?: string; readonly sectionName?: string;
}
interface RuleOption { readonly id: string; readonly number: number; readonly name: string; readonly sectionName: string }

const optionText = (option: RuleOption) => `${option.number} · ${option.name || "Unnamed rule"}`;

export function RuleUsagePicker({ policy, value, onChange, disabled = false }: {
  readonly policy: string; readonly value: RuleChoice | null; readonly onChange: (choice: RuleChoice | null) => void; readonly disabled?: boolean;
}) {
  const [input, setInput] = useState("");
  const [options, setOptions] = useState<RuleOption[]>([]);
  const [more, setMore] = useState(0);
  const [state, setState] = useState<"idle" | "loading" | "failed">("idle");
  // The snapshot's own search answers the query; a short pause keeps one request per pause in typing.
  useEffect(() => {
    if (!policy) return;
    let active = true;
    setState("loading");
    const timer = setTimeout(() => {
      getPolicy(policy, 0, input.trim())
        .then((page) => {
          if (!active) return;
          const found = (page.sections ?? []).flatMap((section) => (section.rules ?? []).map((rule) => ({ id: rule.id, number: rule.number, name: rule.name, sectionName: section.name })));
          setOptions(found); setMore(Math.max(0, (page.total ?? found.length) - found.length)); setState("idle");
        })
        .catch(() => { if (active) { setOptions([]); setMore(0); setState("failed"); } });
    }, 250);
    return () => { active = false; clearTimeout(timer); };
  }, [policy, input]);
  const selected = value?.picked ? options.find((o) => o.id === value.ruleId) ?? { id: value.ruleId, number: value.number ?? 0, name: value.name ?? "", sectionName: value.sectionName ?? "" } : null;
  return <Box sx={{ my: 2, maxWidth: 560 }}>
    <Autocomplete<RuleOption, false, false, true> freeSolo size="small" disabled={disabled} options={options} loading={state === "loading"}
      filterOptions={(all) => all} value={selected} inputValue={input}
      getOptionLabel={(option) => typeof option === "string" ? option : optionText(option)}
      isOptionEqualToValue={(option, chosen) => option.id === chosen.id}
      onInputChange={(_e, text, reason) => {
        setInput(text);
        // Typing or pasting is a rule UID / id until a rule is picked from the list.
        if (reason === "input") onChange(text.trim() ? { ruleId: text.trim(), picked: false } : null);
        if (reason === "clear") onChange(null);
      }}
      onChange={(_e, option) => {
        if (option && typeof option !== "string") onChange({ ruleId: option.id, picked: true, number: option.number, name: option.name, sectionName: option.sectionName });
        else if (typeof option === "string") onChange(option.trim() ? { ruleId: option.trim(), picked: false } : null);
        else onChange(null);
      }}
      renderOption={(props, option) => <Box component="li" {...props} key={option.id}>
        <Box>
          <Typography>{optionText(option)}</Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Section {option.sectionName || "UNKNOWN"}</Typography>
        </Box>
      </Box>}
      renderInput={(params) => <TextField {...params} label="Rule" required placeholder="Search by name, number or section, or paste a rule UID / id" />}
    />
    <Typography variant="caption" role="status" data-rule-choice={value ? (value.picked ? "picked" : "pasted") : "none"} sx={{ display: "block", mt: 0.5, color: m3.onSurfaceVar }}>
      {value?.picked ? `Chosen: rule ${value.number} · ${value.name || "Unnamed rule"} · section ${value.sectionName || "UNKNOWN"}.`
        : value ? "Sent as a rule UID / id. It must match exactly one rule of this snapshot; otherwise the upload is refused and nothing is analysed."
        : state === "failed" ? "The snapshot's rules could not be listed. Paste the rule UID / id instead."
        : "Choose the rule whose objects you want to check, or paste its rule UID / id."}
      {!value?.picked && state !== "failed" && more > 0 ? ` ${more.toLocaleString("en-US")} more rules match; type to narrow the list.` : ""}
    </Typography>
  </Box>;
}
