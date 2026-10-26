# How to reproduce the experiments

Everything runs inside Docker — no local Java or Maven needed. The image provides **Maven 3.9 +
Eclipse Temurin JDK 21** and pulls **CloudSim Plus 8.5.7** as a Maven dependency; this repository
provides ColaPri, the eight baseline schedulers (six implemented here, two CloudSim Plus built-ins), the
four task-to-VM mappers, the seven experiment harnesses, the simulator probes and the bundled trace subsets.
The reference logs are in `results/`; re-runs are written to `results/rerun/` so they never overwrite them.

> Requirement: **Docker** (Desktop on Windows/macOS, Engine on Linux). ~1 GB free disk.
> On Windows start Docker Desktop first; the commands below assume Git Bash or PowerShell.

---

## 0. Clone

```bash
git clone https://github.com/GCDA-UDL/repro-colapri-scheduler.git
cd repro-colapri-scheduler
```

## 1. Build the image (first time, ~2 min)

```bash
docker compose build            # or:  docker build -t colapri-sim .
```

## 2. Run an experiment

Each experiment is a `main` class; pass its name as the single argument.

| Argument | Experiment | Seeds | Output |
|----------|------------|-------|--------|
| `RigorousEval`     | EXP-A · 1-VM scheduling: per-class, starvation, adaptive | 30 | `results/EXP-A.log` |
| `DatacenterEval`   | EXP-B · 4 hosts / 16 VMs × 4 mapping policies | 20 | `results/EXP-B.log` |
| `ScalabilityEval`  | EXP-C · 200 / 500 / 1000 tasks | 10 | `results/EXP-C.log` |
| `SensitivityEval`  | EXP-D · weight ratio, threshold, load, PEs | 30 | `results/EXP-D.log` |
| `RealTraceRigorous`| EXP-E · five production traces (needs `/traces`) | 20 | `results/EXP-E.log` |
| `ActivationEval`   | EXP-F · activation degree vs weight | 10 train + 20 test | `results/EXP-F.log` |
| `AgingSweep`       | EXP-G · aging-factor sweep vs ColaPri weight sweep | 30 | `results/EXP-G.log` |
| `EngineProbe`      | dispatch-order defect of CloudSim Plus 8.5.7 (queue bypass, idle gap) | 30 | `results/ENGINE*.log` |
| `CfsProbe`         | built-in CFS scheduler: termination and finished tasks | — | `results/CFS.log` |

### Linux / macOS (bash)

```bash
mkdir -p results/rerun
docker compose run --rm sim RigorousEval | tee results/rerun/EXP-A.log
```

### Windows (PowerShell)

```powershell
New-Item -ItemType Directory -Force results\rerun | Out-Null
docker compose run --rm sim RigorousEval | Tee-Object -FilePath results\rerun\EXP-A.log
```

### Everything in one go (stock Maven image, no build step)

```bash
./scripts/run_all.sh [/absolute/path/to/traces]      # EXP-A..F + probes, one log per experiment in results/rerun/ (reference logs in results/ untouched)
LEGACY=1 ./scripts/run_all.sh [/path/to/traces]      # engine of v1.1.1 (no dispatch-order fix, no counter re-base) -> results/rerun_legacy/
```

Approximate runtime on the reference host (Intel Core i7-14700KF, Windows 11, Docker Desktop):
EXP-A and EXP-E under 30 s each; the whole matrix a few minutes.

## 3. Production traces (EXP-E)

`RealTraceRigorous` reads five directories under `/traces` (layout and file formats in the README):

```
/traces/planetlab/20110303/                                   bundled in data/
/traces/bitbrains/bitbrains_rnd_2013-8_100_mostDiff_short/    bundled in data/
/traces/materna/materna_trace1_valid_0_288_100_mostDiff/      bundled in data/
/traces/alibaba2018/alibaba2018_fixed_first_500_100_mostDiff/ NOT bundled (see data/PROVENANCE.md)
/traces/azure/azure_azure2019_traces_100_mostDiff/            NOT bundled (see data/PROVENANCE.md)
```

By default `docker compose` mounts `./data` at `/traces`, so PlanetLab, Bitbrains and Materna run out
of the box and the other two are reported as missing. To run all five, assemble the directory from the
origins listed in `data/PROVENANCE.md`, verify it with `sha256sum -c data/checksums.sha256`, and point
`TRACES` at it:

```bash
TRACES=/path/to/traces docker compose run --rm sim RealTraceRigorous | tee results/rerun/EXP-E.log
```

## 4. Check the results against the paper

The committed `results/EXP-{A..F}.log` are the captured logs behind every table and figure of the
paper (mapping in the README, section *Seeds / paper-table manifest*). All simulated metrics —
response, waiting, makespan, starvation counts, Jain index — are **seed-deterministic**: a re-run must
reproduce them to the last decimal. Only the wall-clock `overhead.ms` column depends on the host.

```bash
python scripts/compare_logs.py results/EXP-A.log results/rerun/EXP-A.log   # exit 0 = identical
```

`compare_logs.py` drops the `overhead.ms` column (the last column of the blocks that have one)
before comparing, so a plain `diff` of the two files is expected to differ only there.
Each log also ends without any `[LOST]` line: every harness asserts that all submitted cloudlets
finished (`Metrics.checked`), see the v1.1.0 entry of `CHANGELOG.md` for the defects this guards
against.

## 5. Regenerate the figures

```bash
python figures/make_figures.py        # requires matplotlib; writes figures/*.pdf
```

The figure script parses every plotted number from `results/*.log` (no literals), so the figures always
match the logs they are generated from.

## 6. Strict long-term reproduction

The base image is pinned by digest in `Dockerfile` and `scripts/run_all.sh` (index sha256:99e61abc…, resolved 2026-10-21); the tag form
(`maven:3.9-eclipse-temurin-21@sha256:<digest>` — `docker inspect --format '{{index .RepoDigests 0}}' maven:3.9-eclipse-temurin-21`),
and cite the Zenodo record of the release you used (see `CITATION.cff`).
