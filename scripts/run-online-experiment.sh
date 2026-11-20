#!/usr/bin/env bash
#
# Runs the online-learning experiment grid on scenario 1.
#
# Each label is one CloudAnalyst load-balancer policy ("expert"), evaluated
# over the same seeded 24-hour workload. One JVM per run because GridSim keeps
# global state.
#
# Usage:
#   ./scripts/run-online-experiment.sh
#   SEEDS="1 2 3 4 5 6 7 8 9 10" ./scripts/run-online-experiment.sh
#   EXPERTS="rr hybrid" ./scripts/run-online-experiment.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

JAVA="${JAVA:-java}"
JAVAC="${JAVAC:-javac}"
SEEDS="${SEEDS:-1 2 3 4 5}"
EXPERTS="${EXPERTS:-rr throttled esce min-min aco pso hybrid hybrid-cost-lean hybrid-latency-lean}"
SIM="${SIM:-experiments/scenario-1.sim}"
OUT_ROOT="${OUT_ROOT:-results/online}"
SLA_MS="${SLA_MS:-80}"
PROC_SLA_MS="${PROC_SLA_MS:-5}"
ROUND_MINUTES="${ROUND_MINUTES:-15}"
SAMPLE_LIMIT="${SAMPLE_LIMIT:-2000}"
HEAP="${HEAP:--Xmx3g}"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac

if [ ! -d build/classes ]; then
    JAVAC="$JAVAC" ./scripts/build.sh
fi

# expert label | broker policy | lb scenario (empty = not applicable)
expert_config() {
    case "$1" in
        rr)                   echo "Round Robin|" ;;
        throttled)            echo "Throttled|" ;;
        esce)                 echo "Equally Spread Current Execution Load|" ;;
        min-min)              echo "Min Min|" ;;
        aco)                  echo "Ant Colony|" ;;
        pso)                  echo "PSO LoadBalancer|" ;;
        hybrid)               echo "Proposed Method|1" ;;
        hybrid-cost-lean)     echo "Proposed Method|4" ;;
        hybrid-latency-lean)  echo "Proposed Method|5" ;;
        *)
            echo "Unknown expert: $1" >&2
            return 1
            ;;
    esac
}

failed=0
ran=0
skipped=0

for expert in $EXPERTS; do
    IFS='|' read -r policy scenario <<< "$(expert_config "$expert")"
    for seed in $SEEDS; do
        run_dir="$OUT_ROOT/$expert/seed-$seed"
        summary="$run_dir/summary.csv"

        if [ -f "$summary" ] && [ "$(wc -l < "$summary")" -gt 1 ]; then
            echo "SKIP  $expert seed=$seed (already complete)"
            skipped=$((skipped + 1))
            continue
        fi

        rm -rf "$run_dir"
        mkdir -p "$run_dir"

        args=(--sim "$SIM" --out "$run_dir" --policy "$policy"
              --expert "$expert" --seed "$seed" --run-id "$expert-seed$seed"
              --round-minutes "$ROUND_MINUTES"
              --sla-ms "$SLA_MS" --proc-sla-ms "$PROC_SLA_MS"
              --sample-limit "$SAMPLE_LIMIT")
        if [ -n "$scenario" ]; then
            args+=(--lb-scenario "$scenario")
        fi

        echo "RUN   $expert seed=$seed"
        if "$JAVA" "$HEAP" -cp "build/classes${SEP}jars/*" \
                cloudsim.ext.experiment.HeadlessSimulationRunner "${args[@]}" \
                > "$run_dir/run.log" 2>&1; then
            if grep -q "RUN_COMPLETE" "$run_dir/run.log"; then
                ran=$((ran + 1))
            else
                echo "FAIL  $expert seed=$seed (no RUN_COMPLETE marker)" >&2
                failed=$((failed + 1))
            fi
        else
            echo "FAIL  $expert seed=$seed (see $run_dir/run.log)" >&2
            failed=$((failed + 1))
        fi
    done
done

echo "Done: ran=$ran skipped=$skipped failed=$failed"
[ "$failed" -eq 0 ]
