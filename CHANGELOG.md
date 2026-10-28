# Changelog

All notable changes to the ColaPri reproduction package. Versions follow the GitHub releases;
the Zenodo record (once created) archives each release.

## v1.1.2 — 2026-10-28

A third audit (2026-10-22/24) found a defect of the simulation engine that affects every space-shared
scheduler and a defect of ColaPri's continuous activation. **Every number in `results/` changed.** The
logs of v1.1.1 reproduce with `-Dcolapri.legacyEngine=true` (`LEGACY=1 ./scripts/run_all.sh`, written to
`results/rerun_legacy/`): every existing line of EXP-A..E is identical, and in EXP-F only the analytic ramp
differs, because the extended placement grid selects (50,100) instead of (40,80).

- **Fixed (simulation engine, all space-shared schedulers): dispatch order of CloudSim Plus 8.5.7.**
  `CloudletSchedulerAbstract.updateProcessing()` moves waiting cloudlets into execution *before* it removes
  the cloudlets that have just finished. A PE freed by a completion therefore stayed idle until the next
  processing update (0.11 s on average with the default 0.1 s event granularity), and a cloudlet arriving in
  between took it ahead of the waiting queue: 5.2% of the tasks of the EXP-A sustained-load workload under
  FCFS and strict priority, 4.9% under ColaPri. The new base class `CloudletSchedulerFifo` (the FCFS
  baseline, the "default" scheduler of EXP-B/C, and the superclass of every space-shared scheduler of the
  package) refills the freed PEs from the waiting list in the same update, through the subclass's own
  order. `EngineProbe` measures bypass and idle gap (`results/ENGINE.log`, `ENGINE_legacy.log`,
  `ENGINE_1ms.log`): 0 bypasses and no gap with the fix. Effect: shorter response times in the overloaded
  scenarios (FCFS high-class mean in the starvation scenario 45.2 → 39.6 s) and the same ranking of the
  schedulers.
- **Fixed (ColaPri, continuous activation only): counter re-base.** The per-class counter indexes the
  lattice 1/w_eff; when the effective weight changed (analytic ramp, fuzzy controller, learned lookup of
  EXP-F) the counter was reused on the new lattice, so new tasks of a class could overtake earlier ones
  (weight rising) or jump far ahead of the virtual time (weight falling). The counter is now re-based to the
  virtual finish of the class's last tag. With constant activation (every experiment except the continuous
  policies of EXP-F) nothing changes. EXP-F prints an intra-class order check (0 inversions for every
  policy); `results/EXP-F_noRebase.log` (`-Dcolapri.noRebase=true`) shows 40 561 inverted pairs for the ramp
  without the re-base.
- **Proposition 2 audit (EXP-A).** `CloudletSchedulerColaPri.enableBoundAudit()` records, for every task
  tagged at full activation, its class backlog b_c and the bound D(b_c,c), and counts the dispatches issued
  before it starts. EXP-A checks the dispatch bound and the waiting-time bound for weights 6:1 … 60:1:
  0 violations, and the dispatch bound is attained.
- **EXP-A:** the starvation scenario adds ColaPri 8:1, 10:1 and 12:1, to compare with priority-with-aging at
  the same high-class response.
- **EXP-G (new, 2026-10-26): aging-factor sweep.** `AgingSweep` runs priority with aging at factors
  0.01…3 and ColaPri at weights 1:1…60:1 on the EXP-A starvation workload (200 tasks; its rows for FCFS,
  strict priority, aging 0.2 and ColaPri 6…60:1 are identical to EXP-A's) and over a three times longer
  horizon (600 tasks), and compares the two curves at matched high-class response (`results/EXP-G.log`).
  It replaces the single aging factor chosen for EXP-A with the whole aging curve.
- **Repository (2026-10-26):** renamed from `GCDA-UDL/colapri-cloud-scheduler` to
  `GCDA-UDL/repro-colapri-scheduler` (group naming standard; GitHub redirects the old URL). The personal
  mirror `VitorDaSilvaUdL/colapri-cloud-scheduler` is archived. Zenodo title "… (reproduction package)".
  The Maven artifact and the log provenance keep the software name `colapri-cloud-scheduler`.
- **EXP-F:** ramp-placement grid extended to (50,100) and (60,120), so the selection is bracketed; paired
  lines `always-on − FCFS` and `analytic − always-on w=1:1`.
- **CfsProbe:** runs without and with a datacenter scheduling interval under a 60 s watchdog and logs the
  outcome (`results/CFS.log`): both runs terminate with most tasks unfinished (6/40 and 12/40).
- **Engine settings:** every harness creates its simulation through `Metrics.newSimulation()`; system
  properties `colapri.legacyEngine`, `colapri.noRebase` and `colapri.minDt`; the provenance line of every log
  names the settings. The default event granularity stays at 0.1 s because `CloudletSchedulerTimeShared`
  does not terminate below it.
- `figures/make_figures.py` parses the logs instead of carrying the numbers as literals, so the figures
  cannot drift from the tables; `fig_operation` shows the dispatch order of the tag rule (6:3:1, class 9 in
  the third slot).
- `scripts/run_all.sh` runs the probes and supports `LEGACY=1`; REPRODUCE.md no longer writes into
  `results/`; funding acknowledgment added to the README.

## v1.1.1 — 2026-10-21

Never published as a release (superseded by v1.1.2). A second independent audit of v1.1.0 found
one latent defect (non-deterministic Min-Min/Max-Min) and several gaps in what the experiments reported;
this release fixes the defect, adds the missing controls and re-captures every log.

- **Fixed:** `VmMappers.minMaxMin` iterated an identity-hash set and broke ties in the completion time by
  that (JVM-dependent) order, so the Min-Min and Max-Min rows of EXP-B varied by up to 0.3 s between
  runs. Candidates are now scanned in submission order with deterministic tie-breaking; three
  consecutive runs of `DatacenterEval` are identical.
- **Paired statistics:** every harness now prints the per-seed paired difference (mean ± CI95) between
  ColaPri and its main comparator (SFQ, FCFS, strict priority, the default scheduler, the no-resync
  ablation, the best fixed threshold, the weight controls). `Metrics.pairedCi95`.
- **EXP-A gate scenario:** the adaptive scenario now isolates the gate with a θ=0 control and three
  load levels (24 @ 0.3/s, 60 @ 0.55/s, 80 @ 3/s).
- **EXP-E:** each dataset prints the length distribution of its pool (mean length, share of tasks
  clipped to the 1000/12000 MI floor/ceiling, offered load ρ).
- **EXP-F:** 240 s drain gap between the storm and the second calm phase (the second phase previously
  started with ~90 tasks still queued); fixed-weight controls (always on, 3:1 / 2:1 / 1:1) added because
  the activation degree scales the effective weight; paired differences printed. All EXP-F numbers
  changed.
- `scripts/compare_logs.py` is column-aware (drops `overhead.ms` wherever it sits; the previous version
  dropped the last column, which in EXP-F is `cost`). `scripts/run_all.sh` writes to `results/rerun/`
  and never overwrites the reference logs. Base image pinned by digest in `Dockerfile` and `run_all.sh`.
- Every log starts with a provenance line (package version, CloudSim Plus version).
- **Fairness index redefined:** the former `Jain` column (Jain's index over mean response × weight, which
  rewarded strict priority) is removed from EXP-A and EXP-E. `Metrics.jainNormalisedService` computes
  Jain's index over the per-class dispatches normalised by weight (`JFIw`) or by 1 (`JFI1`, the
  equal-share criterion), counted up to the first class drain; printed in EXP-A MAIN, EXP-B and EXP-C.
- Figures regenerated; `fig_operation` now draws a 6:3:1 dispatch (it drew 7:2:1).
- Metadata: version 1.1.1 in `pom.xml`, `CITATION.cff`, `.zenodo.json`; author affiliation
  **SEMBAC, Universitat de Lleida**; second author signed as Rosana Tomàs; PROVENANCE file count 1452;
  Javadoc fixes (zero-order Sugeno; DatacenterEval). `ZENODO_CHECKLIST.md` rewritten for the
  GitHub-integration flow.

## v1.1.0 — 2026-10-20

**Corrected harness: every number in `results/` and `figures/` changed.** An independent audit of
v1.0.1 found two defects that invalidated the earlier captures; both are fixed here and the full
experimental matrix was re-run. The scheduler's algorithm also gained counter resynchronisation.

- **Fixed (harness, critical):** every cloudlet was created with `UtilizationModelFull` for RAM and
  bandwidth as well as CPU. CloudSim Plus then throttled co-scheduled cloudlets to a single processing
  element (the 2-PE VMs ran as 1-PE VMs), and in EXP-B one cloudlet per VM never finished (304/320).
  `Workload.cloudlet(len)` now sets RAM/BW demand to 1% of the VM (CPU stays `Full`); `CapacityProbe`
  reproduces the old behaviour. All simulated response/waiting/makespan values are roughly halved
  relative to v1.0.1 and the qualitative ranking of the baselines changed (see the paper).
- **Fixed (simulator workaround):** `VmPatched` extends `VmSimple` so that `updateProcessing` never
  returns 0 while cloudlets remain; with plain `VmSimple`, CloudSim Plus 8.5.7 could end a simulation
  with cloudlets still queued (EXP-D θ∈[58,62] seed 15: 26/80 finished) or stall it (EXP-F seed 22).
  `Metrics.checked(finished, submitted)` now asserts completeness in every harness and prints `[LOST]`
  otherwise; the committed logs contain no such line.
- **Algorithm:** `CloudletSchedulerColaPri` resynchronises a class's counter to the frontier (largest
  tag already dispatched) when the class returns from idleness, as SFQ start tags do; `setResync(false)`
  restores the old rule for the new idle-return ablation in EXP-A. The bounded-waiting proposition is
  restated in terms of the class's own backlog at tagging time.
- **Baselines:** `CloudletSchedulerWFQ` is now reported as "EqualShare" (equal share per class, which is
  what it computes); CloudSim Plus's `CloudletSchedulerTimeShared` ("PS-timeshared", per-task processor
  sharing) added to EXP-A and EXP-E; the starvation scenario adds ColaPri 20:1, ColaPri 60:1 and SFQ
  60:1 rows (weight sweep along the FCFS–strict frontier).
- **Statistics:** confidence intervals use the Student *t* quantile for the seed count (was 1.96).
- **EXP-F protocol:** the fixed-θ grid (now {0,5,10,20,40,60,80,160,∞=FCFS}) and the ramp placement of
  the analytic and fuzzy policies are selected on the training seeds (0–9) and reported on the test
  seeds (10–29); an FCFS row (θ=∞) is added. The fuzzy controller is described as zero-order Sugeno.
- **Figures:** regenerated from the new logs; `fig_jain.pdf` and `fig_traces_speedup.pdf` removed (the
  Jain index is no longer reported in the paper, although the harness still prints it);
  `figures/make_figures.py` now writes next to itself when run from `figures/`.
- **Packaging:** `scripts/run_all.sh` mounts the bundled `data/` by default; `scripts/compare_logs.py`
  compares two logs ignoring the host-dependent overhead column; `data/checksums.sha256` regenerated to
  cover all 1252 bundled files (50 PlanetLab files with spaces in their names were missing).
- Metadata: version 1.1.0 in `CITATION.cff` / `.zenodo.json`; README numbers updated.

## v1.0.1 — 2026-10-19

- **New baseline `CloudletSchedulerSFQ`**: start-time fair queueing (Goyal, Vin and Cheng, 1997)
  configured with ColaPri's own per-class weights (6:3:1). It is the classical weighted-fair control:
  tags advance by service demand (length / weight), the virtual clock is re-anchored to the task in
  service, and it is always on. Registered in EXP-A (`RigorousEval`) and EXP-E (`RealTraceRigorous`).
- `results/EXP-A.log` and `results/EXP-E.log` re-captured with the new baseline. Every previously
  reported row reproduces bit-identically; only the wall-clock `overhead.ms` column differs
  (ColaPri 1.8 ms on the reference host in this capture).
- `CfsProbe` added: the probe showing that the built-in `CloudletSchedulerCompletelyFair` does not
  terminate under a staggered backlog in our configuration (kept so the issue can be reproduced).
- Figures regenerated (`figures/make_figures.py`): SFQ and DFPO added to the per-class, starvation,
  Pareto, Jain and trace plots.
- Packaging: `Dockerfile`, `docker-compose.yml`, `REPRODUCE.md`, `THIRD_PARTY.md`, this changelog;
  README updated (8 schedulers, canonical repository under the GCDA-UDL organisation).
- Metadata: authors updated in `CITATION.cff` / `.zenodo.json` (Vitor Luiz da Silva, Rosa Ana Tomás,
  Joaquim Oliva, Fernando Guirado); version 1.0.1.

## v1.0.0 — 2026-07-24

- First public release: ColaPri (`CloudletSchedulerColaPri`) plus six baselines (FCFS, SJF, strict
  priority, priority+aging, equal-weight WFQ, DFPO), four task-to-VM mappers, the six experiment
  harnesses (EXP-A..F) and the captured logs behind every table of the paper.
- Bundled PlanetLab, Bitbrains and Materna trace subsets; SHA-256 checksums and provenance for all five
  datasets (`data/`); one-command reproduction script (`scripts/run_all.sh`); `CITATION.cff` and
  `.zenodo.json` deposit metadata.
