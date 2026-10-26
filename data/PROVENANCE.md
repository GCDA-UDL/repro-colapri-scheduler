# Trace data — provenance, licensing and verification

EXP-E consumes five trace directories, mounted at `/traces` (layout in the main README).
`checksums.sha256` in this folder lists the SHA-256 of **every input file of the five trace directories**
(1452 files: 1252 bundled + 200 external). EXP-E reads all 100 files of each derived directory and, for
PlanetLab, the first 200 files by name of `planetlab/20110303` (1052 files bundled). Verify with:

```bash
cd /path/to/traces && sha256sum -c /path/to/data/checksums.sha256
```

| Dataset | Directory used | Origin | License / terms | In package? |
|---|---|---|---|---|
| PlanetLab | `planetlab/20110303` | [beloglazov/planetlab-workload-traces](https://github.com/beloglazov/planetlab-workload-traces) (the trace set long redistributed with CloudSim; cite Beloglazov & Buyya 2012) | no explicit license file at origin; de-facto freely redistributed with CloudSim for over a decade | **yes** (`data/planetlab/20110303`, 4.8 MB, unmodified) |
| Alibaba | `alibaba2018/alibaba2018_fixed_first_500_100_mostDiff` | [alibaba/clusterdata](https://github.com/alibaba/clusterdata) cluster-trace-v2018 | README: usable "for research or study purpose"; no LICENSE file; Alibaba asks to be e-mailed when a publication uses the trace; no specific citation requested | **no** — obtain from origin; checksums here identify the exact derived files |
| Bitbrains | `bitbrains/bitbrains_rnd_2013-8_100_mostDiff_short` | Grid Workloads Archive GWA-T-12 (TU Delft; data from Bitbrains IT Services) | free for research; **cite** Shen, van Beek & Iosup, CCGrid 2015 (doi:10.1109/CCGrid.2015.60) and acknowledge the GWA (Iosup et al., FGCS 2008, doi:10.1016/j.future.2008.02.003) | **yes** (`data/bitbrains/...`, 7 MB derived subset) |
| Materna | `materna/materna_trace1_valid_0_288_100_mostDiff` | Grid Workloads Archive GWA-T-13 (data provided by Materna GmbH Information & Communications, Dortmund, Germany) | no formal licence: the archive **requires an acknowledgement of the data source** in any publication and asks to **cite both** Kohne et al., CCB 2014 (doi:10.1145/2676662.2676674) and Kohne et al., CrossCloud 2016 (doi:10.1145/2904111.2904113), plus the GWA | **yes** (`data/materna/...`, 3 MB derived subset) |
| Azure | `azure/azure_azure2019_traces_100_mostDiff` | [Azure/AzurePublicDataset](https://github.com/Azure/AzurePublicDataset) V2 (2019) | the GitHub repository declares CC-BY-4.0 (checked 2026-10-19); the README asks to cite Cortez et al., SOSP 2017 (doi:10.1145/3132747.3132772) | **no** — obtain from origin; checksums here |

The Grid Workloads Archive web site (`gwa.ewi.tudelft.nl`) was unreachable in October 2026; its dataset pages
and archives remain available through the Internet Archive (Wayback Machine). The paper cites the references
above and carries the required Materna/GWA acknowledgement.

## Derived subsets

The two non-bundled directories (Alibaba, Azure) are **deterministic derivations** of the origin datasets produced
by the companion preprocessing pipeline (VM subset selection by maximum pairwise difference,
"`mostDiff`", plus trace-window fixes). The derivation parameters are encoded in the directory
names (`fixed_first_500`, `valid_0_288`, `100_mostDiff`, `short`). The exact derived files used in
the paper are pinned by `checksums.sha256`; researchers who cannot regenerate them from origin can
request the derived subsets from the corresponding author (redistribution restrictions permitting).

## Class assignment

Priority classes are NOT part of the data: EXP-E derives them at load time from each VM's CPU
burstiness (tertiles within the seeded 80-VM subsample; see `TraceLoader.sample`), so no label
files are needed.
