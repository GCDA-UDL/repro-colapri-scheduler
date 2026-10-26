#!/usr/bin/env bash
# Reproduce every experiment in the ColaPri paper (EXP-A..G) plus the simulator probes (ENGINE, CFS), writing one
# log per experiment to results/rerun/ (the committed reference logs in results/ are never overwritten). Simulated
# metrics are seed-deterministic: the tables reproduce bit-identically; only the wall-clock overhead column varies
# with the host. LEGACY=1 runs the engine of package 1.1.1 (0.1 s event granularity, no PE refill) and writes to
# results/rerun_legacy/ instead (see CHANGELOG v1.1.2).
#
# Requirements: Docker. Traces (EXP-E only) mounted at /traces — see README + data/PROVENANCE.md.
# Usage:  ./scripts/run_all.sh [/absolute/path/to/traces]
#
# Approx. runtime: a few minutes total on a modern desktop (i7-14700KF reference host).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
TRACES="${1:-$REPO/data}"   # default: the bundled subsets (PlanetLab, Bitbrains, Materna)
IMG=maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320   # pinned by digest (2026-10-21)

VOLS=(-v "$REPO:/workspace" -v colapri_m2:/root/.m2)
[ -n "$TRACES" ] && VOLS+=(-v "$TRACES:/traces")

OUT=results/rerun; PROPS=""
[ "${LEGACY:-0}" = "1" ] && { OUT=results/rerun_legacy; PROPS="-Dcolapri.legacyEngine=true"; }

docker run --rm "${VOLS[@]}" -e OUT="$OUT" -e PROPS="$PROPS" -w /workspace "$IMG" bash -lc '
  set -e; mvn -q compile; mkdir -p "$OUT"
  # run <main class> <log name> [extra -D properties]
  run(){ echo "=== $2 ($1) start $(date -u +%T) ==="
         timeout 1800 mvn -q exec:java $PROPS ${3:-} -Dexec.mainClass=colapri.$1 -Dexec.cleanupDaemonThreads=false \
           > "$OUT/$2.log" 2>&1 && echo "=== $2 OK ==="; }
  run RigorousEval     EXP-A
  run DatacenterEval   EXP-B
  run ScalabilityEval  EXP-C
  run SensitivityEval  EXP-D
  run ActivationEval   EXP-F
  if [ -d /traces ]; then run RealTraceRigorous EXP-E; else echo "EXP-E skipped (no /traces mount)"; fi
  if [ -z "$PROPS" ]; then
    run AgingSweep     EXP-G   # new in 1.1.2, so it has no 1.1.1 log to reproduce under LEGACY=1
    # simulator probes (v1.1.2): dispatch-order defect of CloudSim Plus 8.5.7 under three engine settings,
    # the counter re-base of ColaPri (EXP-F without it) and the built-in CFS scheduler
    run EngineProbe    ENGINE
    run EngineProbe    ENGINE_legacy "-Dcolapri.legacyEngine=true"
    run EngineProbe    ENGINE_1ms    "-Dcolapri.minDt=0.001"
    run ActivationEval EXP-F_noRebase "-Dcolapri.noRebase=true"
    run CfsProbe       CFS
  fi
  echo ALL_DONE'
