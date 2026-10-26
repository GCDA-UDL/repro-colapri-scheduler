# ColaPri — Class-Oriented Load-Adaptive Priority scheduler (reproduction package)

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
![Reproducible with Docker](https://img.shields.io/badge/reproducible-Docker-2496ED.svg)
<!-- After the Zenodo deposit add: [![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.XXXXXXX.svg)](https://doi.org/10.5281/zenodo.XXXXXXX) -->

Reproducible code, experiment harnesses, captured results and trace provenance for the paper:

> **ColaPri: a Starvation-Free, Class-Oriented Load-Adaptive Priority Scheduler for Cloud Task
> Scheduling** (prepared for submission to *Simulation Modelling Practice and Theory*).

**Authors:** Vitor L. da Silva, Rosana Tomàs, Joaquim Oliva, Fernando Guirado — SEMBAC, Universitat de
Lleida, Spain.
Repository: [GCDA-UDL/repro-colapri-scheduler](https://github.com/GCDA-UDL/repro-colapri-scheduler)
(formerly `GCDA-UDL/colapri-cloud-scheduler`; the old URL redirects). Step-by-step instructions: [REPRODUCE.md](REPRODUCE.md).

A weighted-fair cloud **task scheduler** for [CloudSim Plus](https://cloudsimplus.org), with three
properties evaluated rigorously (up to 30 seeds, 95% CI):

1. **No starvation** — every task is dispatched after a number of other dispatches bounded by its own
   class's backlog (Proposition 2 of the paper, checked task by task in EXP-A: 0 violations). Under
   sustained load, 6:1 weights cut the worst-case low-class wait by **85%** vs. strict priority and 27% vs.
   FCFS, at a high-class response 3.5% slower than FCFS's; the weight moves ColaPri along the
   FCFS ↔ strict-priority frontier (20:1 → −37%, 60:1 → −10% at +8% high-class response).
2. **Tunable weighted differentiation** — per-class response ratio scales with the configured weight (**1.0 → 2.6**).
3. **Load-adaptive** — below a backlog threshold the queue keeps arrival order (identical to FCFS at ρ≈1.1 against a θ=0 control); above it, it differentiates. A whole 60-task simulation run costs ≈ **1.2 ms** of wall-clock time.

ColaPri is implemented as a `CloudletScheduler` using **persistent virtual-time tags**: the *k*-th tagged
task of class *c* gets tag *k/w_c*, assigned once, with *k* resynchronised to the virtual time (largest tag
already dispatched) when a class returns from idleness. This realises per-class virtual-clock / stride
proportional sharing without starvation (equivalently, start-time fair queueing with a unit cost per
task), and it is orthogonal to the task-to-VM mapping policy.

> The algorithmic core is classical proportional-share scheduling (WFQ, VirtualClock, DRR, WF²Q, SFQ,
> lottery/stride; 1989–1997). The contribution here is the length-agnostic, load-adaptive, per-class
> formulation with a per-task bound, the CloudSim Plus realisation, a rigorous cloud evaluation and the
> correction of a dispatch-order defect of CloudSim Plus 8.5.7 (`CloudletSchedulerFifo`, see below).

## Requirements

- **Option A (recommended):** Docker. No local Java needed.
- **Option B:** JDK 17+ and Maven 3.9+.

CloudSim Plus 8.5.7 is pulled automatically as a Maven dependency.

## Quick start

```bash
# Option A — Docker Compose (builds the image once, dependencies pre-fetched)
docker compose build
docker compose run --rm sim RigorousEval          # EXP-A; any other experiment main class works too

# Option A' — stock Maven image, no build step; -v m2 caches dependencies between runs
docker run --rm -v "$PWD:/workspace" -v colapri_m2:/root/.m2 -w /workspace \
  maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 \
  mvn -q compile exec:java -Dexec.mainClass=colapri.RigorousEval

# Option B — local
mvn -q compile exec:java -Dexec.mainClass=colapri.RigorousEval
```

## Experiments

Each is a `main` class; select it with `-Dexec.mainClass=colapri.<Class>`.

| Class | Experiment | What it shows |
|-------|------------|---------------|
| `RigorousEval`     | EXP-A · 1-VM scheduling (30 seeds) | per-class / starvation (with ColaPri and SFQ weight sweeps) / adaptive / idle-return (resync ablation), 9 schedulers |
| `DatacenterEval`   | EXP-B · multi-host, 16 VMs (20 seeds) | 4 mapping policies × {default, ColaPri} |
| `ScalabilityEval`  | EXP-C · 200/500/1000 cloudlets (10 seeds) | makespan, per-class response, overhead vs. scale |
| `SensitivityEval`  | EXP-D · parameter sweeps (30 seeds) | weight ratio, adaptive threshold, load, PEs |
| `RealTraceRigorous`| EXP-E · 5 real traces (20 seeds) | per-class response on PlanetLab/Alibaba/Bitbrains/Materna/Azure |
| `ActivationEval`   | EXP-F · automatic activation (10 train + 20 test seeds; calm → storm → drain → calm) | always-on / FCFS / fixed / analytic / fuzzy / learned + fixed-weight controls 3:1, 2:1, 1:1 |
| `AgingSweep`       | EXP-G · aging-factor sweep (30 seeds) | priority with aging, factor 0.01…3, against ColaPri's weight 1:1…60:1 on the EXP-A starvation workload (200 and 600 tasks), compared at matched high-class response |
| `EngineProbe`      | simulator probe (30 seeds) | queue bypass and idle gap of CloudSim Plus 8.5.7 with and without the dispatch-order fix |
| `CfsProbe`         | simulator probe | built-in CFS scheduler: termination and finished tasks |

All metrics are reported as mean ± 95% CI. **`results/EXP-{A..G}.log`** are the captured logs behind
every table in the paper; `results/ENGINE*.log`, `results/EXP-F_noRebase.log` and `results/CFS.log` are
the probes quoted in its setup and activation sections. Simulated metrics (response, waiting, makespan) are seed-deterministic —
re-running reproduces them bit-identically; only the wall-clock `overhead.ms` column varies with the
host (reference host: Intel Core i7-14700KF, 32 GB RAM, Windows 11, Docker, Temurin JDK 21).
The `JFIw` / `JFI1` columns (EXP-A MAIN, EXP-B, EXP-C) are Jain's fairness index over the per-class
dispatches normalised by weight (`JFIw`, the proportional-share criterion) or by 1 (`JFI1`, equal share),
counted up to the first class drain (`Metrics.jainNormalisedService`).
Reproduce everything with one command: `./scripts/run_all.sh [/path/to/traces]`.

### Seeds / paper-table manifest

| Experiment | Seeds | Feeds |
|---|---|---|
| EXP-A | 0–29 (30) | per-class, starvation (weight sweep 6:1…60:1), gate (3 load levels × θ=15/θ=0/FCFS) and idle-return tables/figures, Jain-index table, Proposition 2 audit, run-time cost; paired per-seed differences |
| EXP-B | 0–19 (20) | datacenter mapping×scheduler table |
| EXP-C | 0–9 (10) | scalability table |
| EXP-D | 0–29 (30) | weight-ratio / threshold / load / PEs sweeps |
| EXP-E | 0–19 (20) | production-trace table (priority = burstiness tertiles per subsample) |
| EXP-F | train 0–9, test 10–29 | activation-policy table + θ sweep |
| EXP-G | 0–29 (30) | aging curve of the starvation trade-off figure and the matched-response comparisons of the starvation section (its 200-task rows repeat those of EXP-A) |

### Activation degree vs. weight (EXP-F)

`ActivationPolicy` turns ColaPri's threshold into an activation degree `α ∈ [0,1]` (0 = FIFO, 1 = full
weighted-RR), set by one of: `alwaysOn`, `fixed(θ)`, `analytic(lo,hi)` (closed-form ramp),
`fuzzy(lo,hi)` (zero-order Sugeno), or `learned(table,width)` (offline lookup). All are O(1) and
deterministic at inference. Every hyper-parameter (fixed θ, ramp placement, learned table) is selected on
the 10 training seeds and reported on the 20 test seeds. Because α scales the effective weight
(`1 + α·(w−1)`), EXP-F also runs fixed-weight controls (always on, 3:1 / 2:1 / 1:1): they trace the
response/late-task frontier. **Every activation policy lands on that frontier and none improves on it**:
activation chooses the effective weight from the backlog. The closed-form **analytic** ramp reaches the
frontier without training and the offline-**learned** lookup does no better, so no ML is needed. Under
the experiment's cost (resp.p0 + 10·late.p9) the cheapest configuration is the 1:1 weight, i.e. the least
prioritisation; which point of the frontier to use is a policy decision. Without the counter re-base of
v1.1.2 the continuous policies broke the arrival order within a class (`results/EXP-F_noRebase.log`).

### Priority with aging, factor by factor (EXP-G)

Linear aging dispatches by `priority − f·waited`, i.e. by first-seen time plus an offset of `priority/f`
seconds, so its factor moves it between strict priority (f → 0) and FCFS (f → ∞). EXP-G sweeps
f = 0.01…3 and ColaPri's weight 1:1…60:1 on the EXP-A starvation workload and compares them at matched
high-class response (the other curve interpolated between neighbouring settings, per-seed paired
differences). For aging offsets from 30 to 180 s aging has the lower worst-case low-class wait
(−28.8 ± 16.6 s at f = 0.2), and ColaPri has fewer late tasks from f = 0.075 on (3.2 ± 0.7 fewer at
f = 0.2); the mean low-class response does not differ significantly. No aging factor brings the
worst-case wait below FCFS's (68.0 s), while ColaPri's weights below 7:1 do (49.8 s at 6:1, 4.4 s at 1:1);
over a 600-task run the 6:1 worst-case wait is 79.3 s against 213.4 s for FCFS.

### Real traces (EXP-E)

PlanetLab (`data/planetlab/`), Bitbrains and Materna (`data/bitbrains/`, `data/materna/`; GWA — free
for research with citation) are bundled; see `data/PROVENANCE.md`. Alibaba and Azure are third-party
datasets **not bundled** — `data/checksums.sha256` pins the SHA-256 of
every exact input file used in the paper, and `data/PROVENANCE.md` documents origins, licenses and the
derivation of the 100-VM subsets. Mount a directory at `/traces` with this layout
(CPU-utilisation series; the loader derives a priority class from each VM's burstiness):

```
/traces/planetlab/<dir>/        one integer per line
/traces/alibaba2018/<dir>/      CSV, cpu col 2
/traces/bitbrains/<dir>/        CSV ';', col 4
/traces/materna/<dir>/          CSV ';', col 4 (decimal comma)
/traces/azure/<dir>/            CSV, avg col 3
```

Sources: [PlanetLab](https://github.com/beloglazov/planetlab-workload-traces),
[Alibaba](https://github.com/alibaba/clusterdata), Bitbrains (GWA), Materna, Azure.
Adjust the dataset paths in `RealTraceRigorous.java` to match your mount, then:

```bash
docker run --rm -v "$PWD:/workspace" -v "/path/to/traces:/traces" -v colapri_m2:/root/.m2 \
  -w /workspace maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 \
  mvn -q compile exec:java -Dexec.mainClass=colapri.RealTraceRigorous
```

## Schedulers and baselines

| File | Role |
|------|------|
| `CloudletSchedulerFifo`           | FCFS baseline and "default" scheduler of EXP-B/C; base class of every space-shared scheduler here, with the dispatch-order fix for CloudSim Plus 8.5.7 (freed PEs refilled from the queue in the same update; `-Dcolapri.legacyEngine=true` disables it) |
| `CloudletSchedulerColaPri`        | **ColaPri** (weighted virtual-time tags with resynchronisation to the virtual time, counter re-base on weight changes, adaptive threshold, no starvation; `setResync(false)` = ablation; `enableBoundAudit()` = per-task check of Proposition 2) |
| `CloudletSchedulerStrictPriority` | strict priority (anti-baseline: starves low class) |
| `CloudletSchedulerPriorityAging`  | priority + aging (ad-hoc anti-starvation) |
| `CloudletSchedulerSJF`            | shortest-job-first |
| `CloudletSchedulerWFQ`            | equal share per class ("EqualShare" in the logs): ColaPri's tag rule with weight 1 for every class |
| `CloudletSchedulerTimeShared` (CloudSim Plus) | per-task processor sharing ("PS-timeshared" in the logs): the simulator's built-in fair reference |
| `CloudletSchedulerDFPO`           | DFPO (Saxena et al. 2016): three buckets, weighted-RR quanta 3:2:1 — closest weighted baseline |
| `CloudletSchedulerSFQ`            | start-time fair queueing (Goyal et al. 1997) with ColaPri's own weights 6:3:1 — classical weighted-FQ control (tags advance by length/weight, always on) |
| `VmMappers`                       | RoundRobin / Min-Min / Max-Min / Demand-Aware task→VM mapping |
| `CfsProbe`                        | probe for the built-in `CloudletSchedulerCompletelyFair`: its runs end with most tasks unfinished, without and with a scheduling interval (6/40 and 12/40, `results/CFS.log`) |
| `EngineProbe`                     | probe for the dispatch-order defect: queue bypass and idle gap per scheduler (`results/ENGINE*.log`) |
| `CapacityProbe`                   | probe for the v1.0.x defect: cloudlets with `UtilizationModelFull` for RAM/BW are throttled to one PE and one per VM may never finish (kept as evidence) |
| `VmPatched`                       | `VmSimple` with a two-line workaround for a CloudSim Plus 8.5.7 corner case (`updateProcessing` returning 0 ends the simulation with cloudlets queued); every harness uses it and asserts that all submitted cloudlets finish (`Metrics.checked`) |
| `Workload.cloudlet(len)`          | factory for CPU-bound cloudlets (RAM/BW demand 1% of the VM) used by every harness |

> Note: CloudSim Plus's built-in `CloudletSchedulerCompletelyFair` ends its runs with most tasks unfinished
> under a staggered backlog, so the fair references here are `CloudletSchedulerTimeShared`, `CloudletSchedulerWFQ`
> (equal share per class) and the weighted `CloudletSchedulerSFQ` control.

## Figures

`figures/make_figures.py` parses the logs in `results/` and regenerates the paper figures (PDF):

```bash
python figures/make_figures.py   # requires matplotlib
```

## Repository layout

```
src/main/java/colapri/   schedulers (ColaPri + 8 baselines), mappers, metrics, seeded workload,
                         trace loader, 7 experiment harnesses, EngineProbe, CfsProbe
results/                 captured logs (EXP-A..G) behind every table in the paper + probe logs
data/                    bundled PlanetLab / Bitbrains / Materna subsets + checksums.sha256 + PROVENANCE.md
scripts/run_all.sh       one-command reproduction of all experiments (stock Maven image)
Dockerfile, docker-compose.yml   reproduction image (deps pre-fetched) and `docker compose run sim <Exp>`
REPRODUCE.md             step-by-step reproduction guide; THIRD_PARTY.md: third-party licenses
figures/                 make_figures.py + generated PDFs
pom.xml                  Maven build (CloudSim Plus 8.5.7, Java 17)
CITATION.cff, .zenodo.json, CHANGELOG.md   citation + Zenodo deposit metadata + release notes
```

## Citation

```bibtex
@article{colapri2026,
  title   = {ColaPri: a Starvation-Free, Class-Oriented Load-Adaptive Priority Scheduler
             for Cloud Task Scheduling},
  author  = {da Silva, Vitor L. and Tom{\`a}s, Rosana and Oliva, Joaquim and Guirado, Fernando},
  journal = {Simulation Modelling Practice and Theory},
  year    = {2026},
  note    = {Manuscript in preparation. Software: https://github.com/GCDA-UDL/repro-colapri-scheduler (Zenodo DOI pending)}
}
```

See also `CITATION.cff` (machine-readable, picked up by GitHub and Zenodo).

## Acknowledgments

This work has been granted by the Ministerio de Ciencia, Innovación y Universidades (MICIU) AEI/10.13039/501100011033 under contract PID2023-146193OB-I00.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
