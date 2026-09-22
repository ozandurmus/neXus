# Cluster DIFF: 38 of 39 clusters show member differences (264 settings); find the settings that are per-member by nature (e.g. PAN HA device priority, CP per-NIC settings) and exempt them in both the browser and the Java projection

status: planned · target: Overview contract; configurationProjection.ts + ConfigurationProjection.java parity

PO 2026-09-23: moved to the front as the control step after the UI refresh deploy. Measure first: per setting name, in how many of the 38 differing clusters it differs (names and counts only, no values); PO classifies which are normal member-specific differences; then apply in configurationProjection.ts and ConfigurationProjection.java (parity test).
