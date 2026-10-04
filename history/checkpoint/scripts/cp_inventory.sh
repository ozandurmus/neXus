#!/bin/bash

. /opt/CPshared/5.0/tmp/.CPprofile.sh

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
STARTED_EPOCH=$(date +%s)

LOGFILE="/tmp/gw_list.txt"
CSV="/home/admin/gw_interfaces.csv"
ROUTES="/home/admin/cp_routes.csv"
TIMELOG="/home/admin/cp_gw_times_${TIMESTAMP}.log"

RAW_DIR="/home/admin/cp_raw"
COLLECTION_META="${RAW_DIR}/.collection_meta"
COLLECTION_STATUS="${RAW_DIR}/.collection_status.tsv"
mkdir -p "$RAW_DIR"

# Bounded parallelism protects the MDS and managed gateways from an unbounded
# fan-out while avoiding the old fully sequential 87-gateway collection.
# Values are deliberately conservative and can be tuned without code changes.
PARALLELISM="${FBUDDY_CP_PARALLELISM:-6}"
FIRST_TIMEOUT="${FBUDDY_CP_FIRST_TIMEOUT_SECONDS:-10}"
RETRY_TIMEOUT="${FBUDDY_CP_RETRY_TIMEOUT_SECONDS:-30}"
MAX_RETRIES="${FBUDDY_CP_MAX_RETRIES:-1}"

case "$PARALLELISM" in ''|*[!0-9]*) PARALLELISM=6 ;; esac
case "$FIRST_TIMEOUT" in ''|*[!0-9]*) FIRST_TIMEOUT=10 ;; esac
case "$RETRY_TIMEOUT" in ''|*[!0-9]*) RETRY_TIMEOUT=30 ;; esac
case "$MAX_RETRIES" in ''|*[!0-9]*) MAX_RETRIES=1 ;; esac
[ "$PARALLELISM" -lt 1 ] && PARALLELISM=1
[ "$FIRST_TIMEOUT" -lt 1 ] && FIRST_TIMEOUT=10
[ "$RETRY_TIMEOUT" -lt 1 ] && RETRY_TIMEOUT=30
[ "$MAX_RETRIES" -gt 1 ] && MAX_RETRIES=1

WORK_DIR=$(mktemp -d "/tmp/fbuddy_cp_${TIMESTAMP}_XXXXXX") || exit 2
STATUS_DIR="$WORK_DIR/status"
ERROR_DIR="${RAW_DIR}/errors"
IF_CSV_DIR="$WORK_DIR/if_csv"
RT_CSV_DIR="$WORK_DIR/rt_csv"
TIME_DIR="$WORK_DIR/time"
mkdir -p "$STATUS_DIR" "$ERROR_DIR" "$IF_CSV_DIR" "$RT_CSV_DIR" "$TIME_DIR"
find "$ERROR_DIR" -maxdepth 1 -type f -name '*.err' -delete
trap 'rm -rf "$WORK_DIR"' EXIT

# Only remove artifacts owned by this collector. This prevents a gateway that
# disappeared from the new discovery from surviving as stale RAW data.
find "$RAW_DIR" -maxdepth 1 -type f \( -name '*_interfaces.txt' -o -name '*_routes.txt' -o -name '*_cluster_if.txt' \) -delete
rm -f "$COLLECTION_META" "$COLLECTION_STATUS"

rm -f "$LOGFILE" "$CSV" "$ROUTES" "$TIMELOG"
echo '"CMA","GW","INTERFACE","IP","SUBNET"' > "$CSV"
echo '"GW","NETWORK","NEXT_HOP","INTERFACE"' > "$ROUTES"

###############################################
# GW LIST (ORIGINAL DISCOVERY METHOD)
###############################################
if [ "${SECURITYEXPERT_CP_EXCLUDE_VSX:-0}" = "1" ]; then
    # Development-only CP scope: standalone / ClusterXL physical gateways,
    # explicitly excluding VSX hosts/members and Virtual Systems. The normal
    # full checkpoint keeps the original discovery query unchanged.
    CP_QUERY="(type='cluster_member' & (! vsx_cluster_member='true') & (! vs_cluster_member='true')) \
        | (type='gateway' & cp_products_installed='true' & (! vs_netobj='true') & (! vsx_netobj='true'))"
    COLLECTION_SCOPE="physical-non-vsx"
else
    CP_QUERY="(type='cluster_member' & vsx_cluster_member='true' & vs_cluster_member='true') \
        | (type='cluster_member' & (! vs_cluster_member='true')) \
        | (vsx_netobj='true') \
        | (type='gateway' & cp_products_installed='true' & (! vs_netobj='true'))"
    COLLECTION_SCOPE="baseline-all-managed-cp"
fi

# DEV.0.4.1: environment-specific inventory exclusions are supplied at
# runtime by the local SecurityExpert policy.  This script intentionally
# contains no repository default identities.  Matching remains EXACT-name
# only; no pattern guessing is permitted.
SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES="${SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES:-}"

for CMA_NAME in $($MDSVERUTIL AllCMAs); do
    mdsenv "$CMA_NAME" >/dev/null 2>&1
    echo "CMA $CMA_NAME"
    cpmiquerybin attr "" network_objects \
        " $CP_QUERY" \
        -a __name__,ipaddr,connection_state,type,vsx_cluster_member,vs_cluster_member
done | awk -v excluded="$SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES" '
    BEGIN {
        n = split(excluded, arr, ",")
        for (i = 1; i <= n; i++) if (arr[i] != "") skip[arr[i]] = 1
    }
    /^CMA / { print; next }
    { if (!($1 in skip)) print }
' > "$LOGFILE" 2>/dev/null

TOTAL=$(grep -v '^CMA ' "$LOGFILE" | wc -l | tr -d ' ')
echo "TOTAL_GW=$TOTAL"
echo "CP_PARALLELISM=$PARALLELISM"
echo "CP_SCOPE=$COLLECTION_SCOPE"

###############################################
# LIVE COMMAND WITH ONE BOUNDED RETRY
###############################################
run_live_command() {
    PREFIX="$1"
    OUTPUT_FILE="$2"
    ERROR_FILE="$3"
    REMOTE_COMMAND="$4"

    ATTEMPTS=1
    TMP_OUTPUT="${OUTPUT_FILE}.tmp.$$"
    TMP_ERROR="${ERROR_FILE}.tmp.$$"
    rm -f "$OUTPUT_FILE" "$ERROR_FILE" "$TMP_OUTPUT" "$TMP_ERROR"

    timeout "$FIRST_TIMEOUT" "$CPDIR/bin/cprid_util" -server "$IP" -verbose rexec -rcmd bash -c \
        "$REMOTE_COMMAND" > "$TMP_OUTPUT" 2> "$TMP_ERROR"
    FIRST_RC=$?
    FINAL_RC=$FIRST_RC

    if [ "$FIRST_RC" -eq 0 ] && [ -s "$TMP_OUTPUT" ]; then
        FIRST_ERROR="none"
    elif [ "$FIRST_RC" -eq 124 ]; then
        FIRST_ERROR="timeout"
    elif [ "$FIRST_RC" -ne 0 ]; then
        FIRST_ERROR="command_error"
    else
        FIRST_ERROR="empty_output"
    fi

    if [ "$FIRST_ERROR" != "none" ] && [ "$MAX_RETRIES" -ge 1 ]; then
        ATTEMPTS=2
        : > "$TMP_OUTPUT"
        : > "$TMP_ERROR"
        timeout "$RETRY_TIMEOUT" "$CPDIR/bin/cprid_util" -server "$IP" -verbose rexec -rcmd bash -c \
            "$REMOTE_COMMAND" > "$TMP_OUTPUT" 2> "$TMP_ERROR"
        FINAL_RC=$?
    fi

    if [ "$FINAL_RC" -eq 0 ] && [ -s "$TMP_OUTPUT" ]; then
        FINAL_ERROR="none"
        mv "$TMP_OUTPUT" "$OUTPUT_FILE"
        rm -f "$TMP_ERROR" "$ERROR_FILE"
    elif [ "$FINAL_RC" -eq 124 ]; then
        FINAL_ERROR="timeout"
        {
            echo "classification=timeout"
            cat "$TMP_OUTPUT" "$TMP_ERROR" 2>/dev/null
        } > "$ERROR_FILE"
        rm -f "$TMP_OUTPUT" "$TMP_ERROR" "$OUTPUT_FILE"
    elif [ "$FINAL_RC" -ne 0 ]; then
        FINAL_ERROR="command_error"
        {
            echo "classification=command_error"
            echo "rc=$FINAL_RC"
            cat "$TMP_OUTPUT" "$TMP_ERROR" 2>/dev/null
        } > "$ERROR_FILE"
        rm -f "$TMP_OUTPUT" "$TMP_ERROR" "$OUTPUT_FILE"
    else
        FINAL_ERROR="empty_output"
        {
            echo "classification=empty_output"
            cat "$TMP_ERROR" 2>/dev/null
        } > "$ERROR_FILE"
        rm -f "$TMP_OUTPUT" "$TMP_ERROR" "$OUTPUT_FILE"
    fi

    eval "${PREFIX}_RC=$FINAL_RC"
    eval "${PREFIX}_FIRST_RC=$FIRST_RC"
    eval "${PREFIX}_ATTEMPTS=$ATTEMPTS"
    eval "${PREFIX}_ERROR=$FINAL_ERROR"
    eval "${PREFIX}_FIRST_ERROR=$FIRST_ERROR"
}

###############################################
# ONE GATEWAY WORKER
# Interface + route stay sequential inside one gateway.
# Different gateways run concurrently with bounded fan-out.
###############################################
collect_gateway() {
    SEQ="$1"
    CMA_NAME="$2"
    GW="$3"
    IP="$4"
    SAFE_GW="$5"
    MGMT_STATE="$6"
    OBJECT_TYPE="$7"
    VSX_CLUSTER_MEMBER="$8"
    VS_CLUSTER_MEMBER="$9"
    MGMT_STATE_NORM=$(echo "${MGMT_STATE:-unknown}" | tr '[:upper:]' '[:lower:]')

    GW_START=$(date +%s)
    STATUS_FILE=$(printf '%s/%05d.tsv' "$STATUS_DIR" "$SEQ")
    IF_CSV_FILE=$(printf '%s/%05d.csv' "$IF_CSV_DIR" "$SEQ")
    RT_CSV_FILE=$(printf '%s/%05d.csv' "$RT_CSV_DIR" "$SEQ")
    TIME_FILE=$(printf '%s/%05d.log' "$TIME_DIR" "$SEQ")
    IF_RAW="${RAW_DIR}/${SAFE_GW}_interfaces.txt"
    RT_RAW="${RAW_DIR}/${SAFE_GW}_routes.txt"
    IF_ERR="${ERROR_DIR}/${SAFE_GW}_interfaces.err"
    RT_ERR="${ERROR_DIR}/${SAFE_GW}_routes.err"
    CLUSTER_RAW="${RAW_DIR}/${SAFE_GW}_cluster_if.txt"
    CLUSTER_ERR="${ERROR_DIR}/${SAFE_GW}_cluster_if.err"

    # A gateway may legitimately exist in management while currently down.
    # Skip remote execution only when management explicitly reports a non-communicating state.
    if [ "$MGMT_STATE_NORM" != "unknown" ] && [ "$MGMT_STATE_NORM" != "" ] && [ "$MGMT_STATE_NORM" != "communicating" ]; then
        rm -f "$IF_RAW" "$RT_RAW" "$CLUSTER_RAW" "$IF_ERR" "$RT_ERR" "$CLUSTER_ERR"
        printf '%s\t126\t126\t0\t0\t126\t126\tmanagement_down\tmanagement_down\tmanagement_down\tmanagement_down\t%s\tmanagement_down\t%s\t%s\t%s\t%s\t%s\t127\t0\tnot_applicable\n' \
            "$SAFE_GW" "$MGMT_STATE_NORM" "$IP" "$CMA_NAME" "$OBJECT_TYPE" "$VSX_CLUSTER_MEMBER" "$VS_CLUSTER_MEMBER" > "$STATUS_FILE"
        echo "$GW,iface=0s,route=0s,total=0s" > "$TIME_FILE"
        return 0
    fi

    # Each worker gets its own MDS environment in its background subshell.
    if ! mdsenv "$CMA_NAME" >/dev/null 2>&1; then
        rm -f "$IF_RAW" "$RT_RAW" "$CLUSTER_RAW" "$IF_ERR" "$RT_ERR" "$CLUSTER_ERR"
        printf '%s\t125\t125\t1\t1\t125\t125\tmdsenv_error\tmdsenv_error\tmdsenv_error\tmdsenv_error\t%s\tmdsenv_error\t%s\t%s\t%s\t%s\t%s\t127\t0\tnot_attempted\n' \
            "$SAFE_GW" "$MGMT_STATE_NORM" "$IP" "$CMA_NAME" "$OBJECT_TYPE" "$VSX_CLUSTER_MEMBER" "$VS_CLUSTER_MEMBER" > "$STATUS_FILE"
        echo "$GW,iface=0s,route=0s,total=0s" > "$TIME_FILE"
        return 0
    fi

    IF_START=$(date +%s)
    run_live_command IF "$IF_RAW" "$IF_ERR" "ip -details -4 addr show"
    IF_END=$(date +%s)
    IF_TIME=$((IF_END-IF_START))

    # Preserve the legacy CSV feature without issuing a second remote command.
    # The CSV is now derived from the same live RAW output consumed by Python.
    if [ -s "$IF_RAW" ]; then
        awk -v cma="$CMA_NAME" -v gw="$GW" '
        /^[0-9]+:/ { iface=$2; sub(":", "", iface); split(iface,b,"@"); iface=b[1] }
        /inet / {
            split($2,a,"/");
            printf "\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n", cma,gw,iface,a[1],a[2]
        }' "$IF_RAW" > "$IF_CSV_FILE"
    else
        : > "$IF_CSV_FILE"
    fi

    RT_START=$(date +%s)
    run_live_command RT "$RT_RAW" "$RT_ERR" "ip -4 route show table all"
    RT_END=$(date +%s)
    RT_TIME=$((RT_END-RT_START))

    # Preserve the legacy route CSV using the same single live route capture.
    # Explicit non-main table rows are omitted to stay close to old `ip route`.
    if [ -s "$RT_RAW" ]; then
        awk -v gw="$GW" '
        NF == 0 { next }
        $1 == "local" || $1 == "broadcast" { next }
        /[[:space:]]table[[:space:]]/ { next }
        { printf "\"%s\",\"%s\",\"%s\",\"%s\"\n", gw,$1,$3,$5 }
        ' "$RT_RAW" > "$RT_CSV_FILE"
    else
        : > "$RT_CSV_FILE"
    fi

    # Phase 0.5.3: ClusterXL members (including VSX physical members) expose
    # the configured virtual cluster interfaces through cphaprob. This is a
    # read-only, non-blocking live-runtime probe and does not replace the
    # existing interface/route collection. If a VSX member does not expose
    # useful VS0 cluster data, the probe is simply ignored by the parser/UI.
    CLUSTER_RC=127
    CLUSTER_ATTEMPTS=0
    CLUSTER_FIRST_RC=127
    CLUSTER_ERROR="not_applicable"
    CLUSTER_FIRST_ERROR="not_applicable"
    OBJ_NORM=$(echo "${OBJECT_TYPE:-unknown}" | tr '[:upper:]' '[:lower:]')
    VSX_NORM=$(echo "${VSX_CLUSTER_MEMBER:-false}" | tr '[:upper:]' '[:lower:]')
    VS_NORM=$(echo "${VS_CLUSTER_MEMBER:-false}" | tr '[:upper:]' '[:lower:]')
    if [ "$OBJ_NORM" = "cluster_member" ]; then
        run_live_command CLUSTER "$CLUSTER_RAW" "$CLUSTER_ERR" "cphaprob -a -m if"
    else
        rm -f "$CLUSTER_RAW" "$CLUSTER_ERR"
    fi

    if [ "$IF_ERROR" = "none" ] && [ "$RT_ERROR" = "none" ]; then
        OUTCOME=success
    elif [ "$IF_ERROR" = "none" ] || [ "$RT_ERROR" = "none" ]; then
        OUTCOME=partial
    else
        OUTCOME=collection_failed
    fi

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$SAFE_GW" "$IF_RC" "$RT_RC" "$IF_ATTEMPTS" "$RT_ATTEMPTS" \
        "$IF_FIRST_RC" "$RT_FIRST_RC" "$IF_ERROR" "$RT_ERROR" \
        "$IF_FIRST_ERROR" "$RT_FIRST_ERROR" "$MGMT_STATE_NORM" "$OUTCOME" \
        "$IP" "$CMA_NAME" "$OBJECT_TYPE" "$VSX_CLUSTER_MEMBER" "$VS_CLUSTER_MEMBER" \
        "$CLUSTER_RC" "$CLUSTER_ATTEMPTS" "$CLUSTER_ERROR" > "$STATUS_FILE"

    GW_END=$(date +%s)
    GW_TOTAL=$((GW_END-GW_START))
    echo "$GW,iface=${IF_TIME}s,route=${RT_TIME}s,total=${GW_TOTAL}s" > "$TIME_FILE"
}

###############################################
# PARALLEL SCHEDULER
###############################################
CMA_NAME=""
SEQ=0
while read -r line; do
    if echo "$line" | grep -q '^CMA '; then
        CMA_NAME=$(echo "$line" | awk '{print $2}')
        continue
    fi

    GW=$(echo "$line" | awk '{print $1}')
    IP=$(echo "$line" | awk '{print $2}')
    MGMT_STATE=$(echo "$line" | awk '{print $3}')
    OBJECT_TYPE=$(echo "$line" | awk '{print $4}')
    VSX_CLUSTER_MEMBER=$(echo "$line" | awk '{print $5}')
    VS_CLUSTER_MEMBER=$(echo "$line" | awk '{print $6}')
    [ -z "$MGMT_STATE" ] && MGMT_STATE="unknown"
    [ -z "$OBJECT_TYPE" ] && OBJECT_TYPE="unknown"
    [ -z "$VSX_CLUSTER_MEMBER" ] && VSX_CLUSTER_MEMBER="false"
    [ -z "$VS_CLUSTER_MEMBER" ] && VS_CLUSTER_MEMBER="false"
    [ -z "$GW" ] && continue
    [ -z "$IP" ] && continue
    [ -z "$CMA_NAME" ] && continue

    SEQ=$((SEQ+1))
    SAFE_GW=$(echo "$GW" | tr -c '[:alnum:]_-' '_')
    # `echo`'s own trailing newline isn't in the allowed class, so `tr -c`
    # rewrites it to a literal "_" -- and since it is no longer a newline
    # byte, command substitution above has nothing left to strip. This fires
    # for every device unconditionally, appending a trailing "_" that was
    # never part of the real object name (real-env retry finding). Drop it.
    SAFE_GW="${SAFE_GW%_}"

    # Python consumes this line without echoing the sensitive identity.
    echo ">>> GW: $GW ($IP)"

    collect_gateway "$SEQ" "$CMA_NAME" "$GW" "$IP" "$SAFE_GW" "$MGMT_STATE" "$OBJECT_TYPE" "$VSX_CLUSTER_MEMBER" "$VS_CLUSTER_MEMBER" &

    while [ "$(jobs -rp | wc -l | tr -d ' ')" -ge "$PARALLELISM" ]; do
        sleep 0.2
    done
done < "$LOGFILE"

# Workers intentionally record per-device command failures instead of failing
# the whole script. Missing status rows are detected by verification.
wait

###############################################
# DETERMINISTIC FINALIZATION OF PARALLEL FRAGMENTS
###############################################
: > "$COLLECTION_STATUS"
find "$STATUS_DIR" -maxdepth 1 -type f -name '*.tsv' | sort | while read -r f; do
    cat "$f" >> "$COLLECTION_STATUS"
done

find "$IF_CSV_DIR" -maxdepth 1 -type f -name '*.csv' | sort | while read -r f; do
    cat "$f" >> "$CSV"
done
find "$RT_CSV_DIR" -maxdepth 1 -type f -name '*.csv' | sort | while read -r f; do
    cat "$f" >> "$ROUTES"
done
find "$TIME_DIR" -maxdepth 1 -type f -name '*.log' | sort | while read -r f; do
    cat "$f" >> "$TIMELOG"
done

PROCESSED=$(wc -l < "$COLLECTION_STATUS" | tr -d ' ')
MGMT_DOWN=$(awk -F '\t' '$13 == "management_down" {c++} END {print c+0}' "$COLLECTION_STATUS")
ATTEMPTED=$((PROCESSED-MGMT_DOWN))
FAILED=$(awk -F '\t' '$13 == "collection_failed" || $13 == "mdsenv_error" {c++} END {print c+0}' "$COLLECTION_STATUS")
PARTIAL=$(awk -F '\t' '$13 == "partial" {c++} END {print c+0}' "$COLLECTION_STATUS")
SUCCESS=$(awk -F '\t' '$13 == "success" {c++} END {print c+0}' "$COLLECTION_STATUS")
RETRIED=$(awk -F '\t' '$4 > 1 || $5 > 1 {c++} END {print c+0}' "$COLLECTION_STATUS")
RECOVERED=$(awk -F '\t' '$13 != "management_down" && (($4 > 1 && $8 == "none") || ($5 > 1 && $9 == "none")) {c++} END {print c+0}' "$COLLECTION_STATUS")
MGMT_UP=$(awk -F '\t' '$12 == "communicating" {c++} END {print c+0}' "$COLLECTION_STATUS")
MGMT_UNKNOWN=$(awk -F '\t' '$12 == "unknown" || $12 == "" {c++} END {print c+0}' "$COLLECTION_STATUS")

COMPLETED_EPOCH=$(date +%s)
{
    echo "started_epoch=$STARTED_EPOCH"
    echo "completed_epoch=$COMPLETED_EPOCH"
    echo "discovered=$TOTAL"
    echo "processed=$PROCESSED"
    echo "attempted=$ATTEMPTED"
    echo "successful=$SUCCESS"
    echo "partial=$PARTIAL"
    echo "failed=$FAILED"
    echo "management_up=$MGMT_UP"
    echo "management_down=$MGMT_DOWN"
    echo "management_unknown=$MGMT_UNKNOWN"
    echo "retried=$RETRIED"
    echo "recovered_after_retry=$RECOVERED"
    echo "parallelism=$PARALLELISM"
    echo "first_timeout_seconds=$FIRST_TIMEOUT"
    echo "retry_timeout_seconds=$RETRY_TIMEOUT"
    echo "max_retries=$MAX_RETRIES"
    echo "collection_mode=bounded_parallel"
    echo "scope=$COLLECTION_SCOPE"
} > "$COLLECTION_META"

echo "CP_RESULT_SUCCESS=$SUCCESS"
echo "CP_RESULT_PARTIAL=$PARTIAL"
echo "CP_RESULT_FAILED=$FAILED"
echo "CP_RESULT_MANAGEMENT_DOWN=$MGMT_DOWN"
echo "CP_RESULT_RETRIED=$RETRIED"
echo "CP_RESULT_RECOVERED=$RECOVERED"
echo "TIMELOG_FILE=$TIMELOG"
echo "DONE"
