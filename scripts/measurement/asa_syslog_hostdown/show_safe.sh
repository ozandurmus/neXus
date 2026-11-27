# Prints only lines whose every tab field matches [a-z0-9_.]+, and counts the rest. Usage: sh show_safe.sh <file>
awk -F'\t' '{ok=1; for(i=1;i<=NF;i++) if ($i !~ /^[a-z0-9_.]+$/) ok=0; if(ok) print; else bad++} END {print "filtered_lines\t" bad+0}' "$1"
