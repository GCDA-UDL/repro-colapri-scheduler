#!/usr/bin/env python3
"""Generate ColaPri paper figures (PDF, vector) from the captured logs.
Every plotted number is parsed from the EXP-A..EXP-G logs of the reproduction package (results/), so the figures
cannot drift from the tables. The logs are looked up in $COLAPRI_RESULTS, ../results (package figures/) or
../colapri-cloud-scheduler/results (paper/). Run: python make_figures.py"""
import os
import re
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.patches import FancyBboxPatch, Rectangle, FancyArrowPatch

_HERE = os.path.dirname(os.path.abspath(__file__))
# paper/: write to paper/figures ; package figures/: write next to the script
OUT = _HERE if os.path.basename(_HERE) == "figures" else os.path.join(_HERE, "figures")
os.makedirs(OUT, exist_ok=True)
plt.rcParams.update({"font.size": 11, "figure.autolayout": True})
GREEN, AMBER, GREY, BLUE = "#2e8b57", "#e0a106", "#888888", "#3b6ea5"
RED, DARK = "#c0392b", "#1b2733"

# ---- measured values: parsed from the captured logs, never typed in ----
_CAND = [os.environ.get("COLAPRI_RESULTS", ""), os.path.join(_HERE, "..", "results"),
         os.path.join(_HERE, "..", "colapri-cloud-scheduler", "results")]
RESULTS = next((p for p in _CAND if p and os.path.exists(os.path.join(p, "EXP-A.log"))), None)
_ROW = re.compile(r"^(\S.*?)\s{2,}((?:[-\d.]+±[-\d.]+\s*)+)$", re.M)


def _log(k):
    return open(os.path.join(RESULTS, f"EXP-{k}.log"), encoding="utf-8").read()


def _rows(text):
    """'name   a±b   c±d ...' -> {name: [(a, b), (c, d), ...]}"""
    return {m.group(1).strip(): [tuple(map(float, v.split("±"))) for v in m.group(2).split()] for m in _ROW.finditer(text)}


def _section(text, start):
    return [b for b in re.split(r"\n===", text) if b.startswith(start)][0]


def _starv():
    return _rows(_section(_log("A"), " STARVATION").split("Proposition 2 audit")[0])


def _aging():
    """EXP-G on the EXP-A starvation workload: {config: [resp.p0, resp.p9, waitMAX.p9, late.p9]} as (mean, ci);
    the aging-offset column is skipped."""
    sec = _section(_log("G"), " EXP-A starvation workload").split("matched high-class")[0]
    return {m.group(1): [tuple(map(float, v.split("±"))) for v in m.group(2).split()]
            for m in re.finditer(r"^(\S+)\s+\S+\s+((?:[-\d.]+±[-\d.]+\s*){4})$", sec, re.M)}


def _box(ax, x, y, w, h, fc, ec="#30363d", lw=1.2, r=0.06):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle=f"round,pad=0.02,rounding_size={r}",
                                fc=fc, ec=ec, lw=lw, zorder=2))


def _arrow(ax, x1, y1, x2, y2, c=DARK, lw=2.0):
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="-|>", mutation_scale=16,
                                 color=c, lw=lw, zorder=4))


def _toks(ax, x, y, seq, n=None, s=0.16, gap=0.04):
    # draw a row of small colored squares from a class sequence
    cmap = {0: GREEN, 5: AMBER, 9: GREY}
    for i, c in enumerate(seq if n is None else seq[:n]):
        ax.add_patch(Rectangle((x + i * (s + gap), y), s, s, fc=cmap[c], ec="white", lw=0.4, zorder=3))


def fig_operation():
    """How ColaPri orders the per-VM queue: mixed arrivals -> weighted virtual-time tags -> dispatch."""
    fig, ax = plt.subplots(figsize=(9.2, 4.2))
    ax.set_xlim(0, 10); ax.set_ylim(0, 5); ax.axis("off")

    # arrivals (mixed stream)
    ax.text(1.0, 4.62, "(1) arriving cloudlets\n(mixed priority classes, tagged once)", ha="center", fontsize=8.6, color=DARK)
    rng = [0, 9, 0, 5, 0, 0, 9, 5, 0, 0]
    _toks(ax, 0.3, 3.7, rng)
    _arrow(ax, 2.2, 3.78, 3.0, 3.78)

    # ColaPri box
    _box(ax, 3.0, 1.0, 3.8, 3.6, "#f5f8fa", GREEN, lw=2.0)
    ax.text(4.9, 4.32, "ColaPri  (per-VM CloudletScheduler)", ha="center", fontsize=10,
            color=GREEN, fontweight="bold")
    # adaptive gate (two lines so the label never overflows the inner box)
    _box(ax, 3.12, 3.5, 3.56, 0.62, "#eef6ef", GREEN, lw=1.0)
    ax.text(4.9, 3.92, r"adaptive gate  $\alpha\in[0,1]$", ha="center", fontsize=7.8,
            color=DARK, fontweight="bold")
    ax.text(4.9, 3.68, r"FIFO if backlog $\leq\theta$, else weighted-RR", ha="center",
            fontsize=7.0, color=DARK)
    # three class lanes with weights
    lanes = [(0, "p0", 6, GREEN, [0, 0, 0, 0, 0, 0]), (5, "p5", 3, AMBER, [5, 5, 5]), (9, "p9", 1, GREY, [9])]
    yl = 3.05
    for cls, lbl, w, col, seq in lanes:
        ax.text(3.35, yl + 0.07, f"{lbl} (w={w})", fontsize=8, color=col, fontweight="bold")
        _toks(ax, 4.25, yl, seq, s=0.18, gap=0.05)
        yl -= 0.52
    ax.text(4.9, 1.28, r"(2) order by virtual-time tag  $\tau = k / w_c$  (assigned once)", ha="center",
            fontsize=8.0, color=DARK, style="italic")
    _arrow(ax, 6.85, 2.6, 7.6, 2.6)

    # dispatch order
    ax.text(8.7, 4.62, "(3) dispatch order\n($\\propto$ weight, no starvation)", ha="center", fontsize=8.6, color=DARK)
    # first ten dispatches of the tag rule (smallest tau first, ties by class), as in fig_vtime: 6:3:1
    wmap = {0: 6, 5: 3, 9: 1}
    disp = [c for _, c in sorted((k / wmap[c], c) for c in (0, 5, 9) for k in range(10))[:10]]
    for i, c in enumerate(disp):
        y = 3.9 - i * 0.32
        _toks(ax, 7.7, y, [c], s=0.2, gap=0)
    _box(ax, 8.5, 0.55, 1.1, 0.55, DARK, DARK)
    ax.text(9.05, 0.82, "PEs", ha="center", color="white", fontsize=9, fontweight="bold")
    _arrow(ax, 7.95, 0.7, 8.5, 0.82)
    fig.savefig(os.path.join(OUT, "fig_operation.pdf"))
    plt.close(fig)


def fig_architecture():
    """Where ColaPri plugs into CloudSim Plus: mapping (broker) is orthogonal to scheduling (per-VM)."""
    fig, ax = plt.subplots(figsize=(9.2, 4.6))
    ax.set_xlim(0, 10); ax.set_ylim(0, 5); ax.axis("off")

    ax.text(5, 4.78, "Tasks (cloudlets) with priority class", ha="center", fontsize=10.5, color=DARK)
    _toks(ax, 4.0, 4.35, [0, 5, 9, 0, 5, 0], s=0.16, gap=0.05)
    _arrow(ax, 5.0, 4.3, 5.0, 3.95)

    # broker (mapping axis)
    _box(ax, 1.2, 3.2, 7.6, 0.7, "#eef3f8", BLUE, lw=1.6)
    ax.text(5.0, 3.55, "DatacenterBroker — task$\\to$VM mapping", ha="center", fontsize=11,
            color=BLUE, fontweight="bold")
    ax.text(5.0, 3.30, "{RoundRobin · Min-Min · Max-Min · Demand-Aware}", ha="center", fontsize=9, color=DARK)

    # VMs (scheduling axis)
    for i, vx in enumerate([1.3, 4.0, 6.7]):
        _arrow(ax, 5.0, 3.18, vx + 1.0, 2.62)
        _box(ax, vx, 1.5, 2.0, 1.1, "#f5f8fa", GREEN, lw=1.6)
        ax.text(vx + 1.0, 2.36, f"VM{i+1}", ha="center", fontsize=10.5, color=DARK, fontweight="bold")
        ax.text(vx + 1.0, 2.04, "CloudletScheduler", ha="center", fontsize=9, color=DARK)
        ax.text(vx + 1.0, 1.80, "= ColaPri", ha="center", fontsize=10, color=GREEN, fontweight="bold")
        _toks(ax, vx + 0.35, 1.58, [0, 0, 5, 9], s=0.13, gap=0.04)
        _arrow(ax, vx + 1.0, 1.48, vx + 1.0, 1.12)

    # hosts base
    _box(ax, 1.2, 0.5, 7.6, 0.6, DARK, DARK)
    ax.text(5.0, 0.8, "Hosts  (processing elements, PEs)", ha="center", color="white",
            fontsize=10.5, fontweight="bold")

    # orthogonality annotation
    ax.text(9.62, 3.55, "mapping\n(which VM)", ha="center", fontsize=9.5, color=BLUE, rotation=90)
    ax.text(9.62, 2.05, "scheduling\n(order in VM)", ha="center", fontsize=9.5, color=GREEN, rotation=90)
    fig.savefig(os.path.join(OUT, "fig_architecture.pdf"))
    plt.close(fig)


STARV_ROWS = [("FCFS", "FCFS", GREY), ("SJF", "SJF", GREY), ("Strict", "StrictPriority", AMBER),
              ("Aging", "Priority+Aging", AMBER), ("EqualShare", "EqualShare", BLUE), ("PS", "PS-timeshared", BLUE),
              ("DFPO", "DFPO", BLUE), ("SFQ 6:1", "SFQ-weighted", BLUE), ("ColaPri 6:1", "ColaPri", GREEN),
              ("ColaPri 10:1", "ColaPri(10:1)", GREEN), ("ColaPri 20:1", "ColaPri(20:1)", GREEN),
              ("ColaPri 60:1", "ColaPri(60:1)", GREEN), ("SFQ 60:1", "SFQ(60:1)", BLUE)]


def fig_starvation():
    # EXP-A STARV (30 seeds): waitMAX.p9 mean +/- CI95
    s = _starv()
    sched = [r[0] for r in STARV_ROWS]
    wait = [s[r[1]][0][0] for r in STARV_ROWS]; err = [s[r[1]][0][1] for r in STARV_ROWS]
    colors = [r[2] for r in STARV_ROWS]
    fig, ax = plt.subplots(figsize=(7.4, 3.6))
    ax.bar(sched, wait, yerr=err, capsize=3, color=colors)
    ax.set_ylabel("max waiting, low class p9 (s)")
    ax.set_title("Worst-case low-class wait under sustained load (lower is better)")
    ax.set_xticks(range(len(sched))); ax.set_xticklabels(sched, rotation=35, ha="right", fontsize=8.5)
    cut = round((1 - s["ColaPri"][0][0] / s["StrictPriority"][0][0]) * 100)
    i = sched.index("ColaPri 6:1")
    ax.annotate(f"-{cut}% vs Strict", xy=(i, s["ColaPri"][0][0]), xytext=(i - 3.2, 0.75 * max(wait)),
                arrowprops=dict(arrowstyle="->", color=GREEN), color=GREEN, fontweight="bold")
    fig.savefig(os.path.join(OUT, "fig_starvation.pdf"))
    plt.close(fig)


def fig_weights():
    d1 = _rows(_section(_log("D"), " D1"))   # EXP-D D1 (30 seeds)
    w = [1, 2, 4, 6, 10, 20]
    p0 = [d1[f"{x}:1"][0][0] for x in w]; p9 = [d1[f"{x}:1"][1][0] for x in w]; ratio = [d1[f"{x}:1"][2][0] for x in w]
    fig, ax1 = plt.subplots(figsize=(6, 3.4))
    ax1.plot(w, p0, "o-", color=GREEN, label="resp p0 (high)")
    ax1.plot(w, p9, "s-", color=AMBER, label="resp p9 (low)")
    ax1.set_xlabel("configured weight ratio (class 0 : class 9)")
    ax1.set_ylabel("mean response (s)")
    ax1.set_xscale("log"); ax1.set_xticks(w); ax1.set_xticklabels([f"{x}:1" for x in w])
    ax2 = ax1.twinx()
    ax2.plot(w, ratio, "^--", color=BLUE, label="ratio p9/p0")
    ax2.set_ylabel("differentiation ratio p9/p0", color=BLUE)
    ax2.tick_params(axis="y", colors=BLUE)
    ax1.set_title("Differentiation is a tunable knob (D1)")
    lines = ax1.get_lines() + ax2.get_lines()
    ax1.legend(lines, [l.get_label() for l in lines], loc="center right", fontsize=9)
    fig.savefig(os.path.join(OUT, "fig_weights.pdf"))
    plt.close(fig)


def fig_perclass():
    # EXP-A MAIN (30 seeds): mean response per class
    m = _rows(_section(_log("A"), " MAIN"))
    keys = [("FCFS", "FCFS"), ("SJF", "SJF"), ("Strict", "StrictPriority"), ("PS", "PS-timeshared"),
            ("EqualShare", "EqualShare"), ("DFPO", "DFPO"), ("SFQ", "SFQ-weighted"), ("ColaPri", "ColaPri")]
    sched = [k[0] for k in keys]
    p0 = [m[k[1]][0][0] for k in keys]; p5 = [m[k[1]][1][0] for k in keys]; p9 = [m[k[1]][2][0] for k in keys]
    x = np.arange(len(sched)); ww = 0.27
    fig, ax = plt.subplots(figsize=(7.4, 3.4))
    ax.bar(x - ww, p0, ww, label="p0 (high)", color=GREEN)
    ax.bar(x, p5, ww, label="p5 (mid)", color=AMBER)
    ax.bar(x + ww, p9, ww, label="p9 (low)", color=GREY)
    ax.set_xticks(x); ax.set_xticklabels(sched)
    ax.set_ylabel("mean response (s)")
    ax.set_title("Per-class response: flat / extreme / graded")
    ax.legend(fontsize=9)
    fig.savefig(os.path.join(OUT, "fig_perclass.pdf"))
    plt.close(fig)


def fig_traces():
    ds = ["PlanetLab", "Alibaba", "Bitbrains", "Materna", "Azure"]
    e = {b.split(" ")[0]: _rows(b) for b in re.split(r"\n========== ", _log("E"))[1:]}   # EXP-E (20 seeds)
    fcfs = [e[d]["FCFS"][0][0] for d in ds]; sfq = [e[d]["SFQ-weighted"][0][0] for d in ds]
    cola = [e[d]["ColaPri"][0][0] for d in ds]; strict = [e[d]["StrictPriority"][0][0] for d in ds]
    x = np.arange(len(ds)); ww = 0.2
    fig, ax = plt.subplots(figsize=(6.6, 3.4))
    ax.bar(x - 1.5 * ww, fcfs, ww, label="FCFS", color=GREY)
    ax.bar(x - 0.5 * ww, sfq, ww, label="SFQ (weighted)", color=BLUE)
    ax.bar(x + 0.5 * ww, cola, ww, label="ColaPri", color=GREEN)
    ax.bar(x + 1.5 * ww, strict, ww, label="Strict (lower bound)", color=AMBER)
    ax.set_xticks(x); ax.set_xticklabels(ds, rotation=15)
    ax.set_ylabel("interactive-class response p0 (s)")
    ax.set_title("Production traces: interactive-class response", fontsize=10)
    ax.legend(fontsize=9)
    fig.savefig(os.path.join(OUT, "fig_traces.pdf"))
    plt.close(fig)


def fig_activation():
    # EXP-F TEST seeds (20): resp.p0 (x) vs late low-class tasks (y, logged as starv.p9); fixed weights trace the frontier
    f = _log("F")
    t = _rows(f.split("(TEST seeds")[1].split("paired per-seed")[0])
    key = lambda prefix: next(k for k in t if k.startswith(prefix))
    pol = [("always-on 6:1", "always-on", GREY, "o"), ("FCFS", "FCFS", GREY, "D"),
           ("fixed(15)", "fixed(theta=15)", AMBER, "s"), ("best-fixed", "best-fixed", AMBER, "s"),
           ("analytic", "analytic", GREEN, "*"), ("fuzzy", "fuzzy", GREY, "o"), ("learned", "learned", BLUE, "^"),
           ("w=3:1", "always-on w=3:1", DARK, "P"), ("w=2:1", "always-on w=2:1", DARK, "P"), ("w=1:1", "always-on w=1:1", DARK, "P")]
    name = [p[0] if p[0] not in ("best-fixed", "analytic", "fuzzy") else key(p[1]).replace("theta=", "") for p in pol]
    p0 = [t[key(p[1])][0][0] for p in pol]; st = [t[key(p[1])][3][0] for p in pol]
    col = [p[2] for p in pol]; mk = [p[3] for p in pol]
    off = {0: (-60, 2), 1: (8, 4), 2: (-10, 10), 3: (6, -14), 4: (-82, -2), 5: (-62, -4), 6: (6, 5), 7: (6, 5), 8: (7, 2), 9: (4, 8)}
    fig, ax = plt.subplots(figsize=(6.2, 3.6))
    ax.set_xlim(min(p0) - 2, max(p0) + 3); ax.set_ylim(-0.8, max(st) + 2.5)
    fr = [0, 7, 8, 9]
    ax.plot([p0[i] for i in fr], [st[i] for i in fr], ":", color=DARK, lw=1.0, zorder=2, label="fixed-weight frontier")
    for i in range(len(name)):
        ax.scatter(p0[i], st[i], s=160 if mk[i] == "*" else 90, c=col[i], marker=mk[i],
                   edgecolors="black", linewidths=0.5, zorder=3)
        ax.annotate(name[i], (p0[i], st[i]), textcoords="offset points", xytext=off[i], fontsize=8)
    ax.legend(fontsize=8, loc="upper right")
    ax.set_xlabel("high-class response p0 (s)  —  lower is better")
    ax.set_ylabel("late low-class tasks (wait > 60 s)")
    ax.set_title("EXP-F: activation policies and fixed weights (test seeds)")
    ax.grid(True, alpha=0.3)
    fig.savefig(os.path.join(OUT, "fig_activation.pdf"))
    plt.close(fig)


def fig_problem():
    """A single FIFO queue lets one client's bulk submission block an urgent task (head-of-line blocking).
    Two panels: (a) light load served one-by-one; (b) a bulk batch traps the urgent task at the back."""
    URG, BATCH = GREEN, BLUE
    fig, (axA, axB) = plt.subplots(2, 1, figsize=(8.6, 3.4))
    for ax in (axA, axB):
        ax.set_xlim(0, 13); ax.set_ylim(0, 1.5); ax.axis("off")

    def row(ax, n, urgent_idx):
        ax.add_patch(FancyArrowPatch((-0.02, 0.52), (0.28, 0.52), arrowstyle="-|>",
                     mutation_scale=12, color=DARK, lw=1.6))
        for i in range(n):
            fc = URG if i == urgent_idx else BATCH
            ax.add_patch(Rectangle((0.3 + i * 1.0, 0.22), 0.86, 0.6, fc=fc, ec="white", lw=1, zorder=3))
        ax.text(0.3 + n * 1.0 + 0.12, 0.52, "out", fontsize=9, color=DARK, va="center")

    axA.text(0.0, 1.34, "(a) light load: mixed arrivals served one-by-one — the urgent task leaves quickly",
             fontsize=8.6, color=DARK, fontweight="bold")
    row(axA, 5, 1)
    axA.annotate("urgent\nserved soon", xy=(0.3 + 1 * 1.0 + 0.43, 0.84), xytext=(3.0, 1.02),
                 fontsize=7.2, color=GREEN, ha="center",
                 arrowprops=dict(arrowstyle="->", color=GREEN, lw=1.2))
    axA.add_patch(Rectangle((9.7, 1.04), 0.3, 0.22, fc=URG, ec="white"))
    axA.text(10.1, 1.15, "urgent task", fontsize=7.0, va="center", color=DARK)
    axA.add_patch(Rectangle((9.7, 0.70), 0.3, 0.22, fc=BATCH, ec="white"))
    axA.text(10.1, 0.81, "one client's batch", fontsize=7.0, va="center", color=DARK)

    axB.text(0.0, 1.34, "(b) burst: one client submits a bulk batch — the urgent task is stuck at the back",
             fontsize=8.6, color=DARK, fontweight="bold")
    row(axB, 11, 10)
    axB.annotate("urgent task stuck behind one client's\nbulk submission (head-of-line blocking)",
                 xy=(0.3 + 10 * 1.0 + 0.43, 0.84), xytext=(5.7, 1.02),
                 fontsize=7.2, color=RED, ha="center",
                 arrowprops=dict(arrowstyle="->", color=RED, lw=1.3))
    fig.savefig(os.path.join(OUT, "fig_problem.pdf")); plt.close(fig)


def fig_vtime_timeline():
    """Educational, step by step: weighted virtual-time tags tau=k/w_c are stamped once per class, then
    merged by smallest tau; after ten dispatch slots the service split is exactly 6:3:1, low class present."""
    cmap = {0: GREEN, 5: AMBER, 9: GREY}
    wmap = {0: 6, 5: 3, 9: 1}
    tags = []
    for cls in (0, 5, 9):
        for k in range(40):
            tags.append((k / wmap[cls], cls, k))
    tags.sort(key=lambda t: (t[0], t[1]))
    N = 10
    fig, ax = plt.subplots(figsize=(5.0, 4.6))
    ax.set_xlim(0, 6); ax.set_ylim(2.6, 13); ax.axis("off")
    ax.text(3.0, 12.5, r"weighted virtual-time tags  $\tau = k / w_c$", ha="center",
            fontsize=10, color=DARK, fontweight="bold")
    ax.text(3.0, 12.0, r"stamp each task once, then always dispatch the smallest $\tau$",
            ha="center", fontsize=7.6, color=DARK)

    # left: per-class tag streams (first six tags of each class), tau printed inside
    ax.text(1.42, 11.45, "per-class tags", ha="center", fontsize=8, color=DARK, style="italic")
    for col, cls in enumerate((0, 5, 9)):
        x = 0.45 + col * 0.92
        ax.text(x + 0.18, 11.08, f"p{cls} (w={wmap[cls]})", ha="center", fontsize=6.8,
                color=cmap[cls], fontweight="bold")
        for k in range(6):
            y = 10.55 - k * 0.5
            ax.add_patch(Rectangle((x, y), 0.36, 0.42, fc=cmap[cls], ec="white", lw=0.5, zorder=3))
            ax.text(x + 0.18, y + 0.21, f"{k / wmap[cls]:.2f}", ha="center", va="center",
                    fontsize=5.4, color="white")

    ax.annotate("", xy=(4.05, 9.7), xytext=(2.55, 9.7),
                arrowprops=dict(arrowstyle="-|>", color=DARK, lw=1.6))
    ax.text(3.3, 10.15, r"merge by" + "\n" + r"smallest $\tau$", ha="center", fontsize=7.0, color=DARK)

    # right: dispatch order (top = first out), tau and slot index labelled
    ax.text(4.55, 11.45, "dispatch order", ha="center", fontsize=8, color=DARK, style="italic")
    tally = {0: 0, 5: 0, 9: 0}
    for i in range(N):
        tau, cls, k = tags[i]
        tally[cls] += 1
        y = 10.6 - i * 0.62
        ax.add_patch(Rectangle((4.25, y), 0.6, 0.5, fc=cmap[cls], ec="white", lw=0.6, zorder=3))
        ax.text(4.55, y + 0.25, f"{tau:.2f}", ha="center", va="center", fontsize=5.8, color="white")
        ax.text(5.0, y + 0.25, f"#{i + 1}", ha="left", va="center", fontsize=6.2, color=DARK)
    # explanatory note filling the otherwise-empty left-bottom area
    note = ["Why it works:", r"heavier class $\to$ smaller $\tau$ steps,",
            "so it reaches the front more often;", r"every task still gets a finite $\tau$,",
            "so no class is ever starved."]
    for j, ln in enumerate(note):
        ax.text(0.35, 7.15 - j * 0.46, ln, ha="left", fontsize=7.0,
                color=DARK, fontweight="bold" if j == 0 else "normal")
    ax.text(3.0, 4.35, f"after {N} slots:  p0:p5:p9 $=$ {tally[0]}:{tally[5]}:{tally[9]}",
            ha="center", fontsize=8.2, color=DARK, fontweight="bold")

    for c, x in [(0, 0.45), (5, 2.25), (9, 4.05)]:
        ax.add_patch(Rectangle((x, 3.05), 0.3, 0.3, fc=cmap[c], ec="white"))
        ax.text(x + 0.42, 3.2, f"p{c} (w={wmap[c]})", fontsize=6.8, va="center", color=DARK)
    fig.savefig(os.path.join(OUT, "fig_vtime.pdf")); plt.close(fig)


def fig_pareto_starv():
    """Starvation experiment as a trade-off: resp.p0 (x) vs waitMAX.p9 (y); bottom-left is better."""
    # EXP-A STARV (30 seeds): resp.p0 vs waitMAX.p9 of the baselines; EXP-G on the same workloads: the ColaPri weight
    # sweep (1:1 ... 60:1) and the aging-factor sweep (0.01 ... 3) trace one curve each
    s, g = _starv(), _aging()
    rows = [("FCFS", "FCFS", GREY, "o"), ("SJF", "SJF", GREY, "o"), ("Strict", "StrictPriority", RED, "s"),
            ("EqualShare", "EqualShare", BLUE, "^"), ("PS", "PS-timeshared", BLUE, "^"),
            ("DFPO", "DFPO", BLUE, "^"), ("SFQ 6:1", "SFQ-weighted", BLUE, "D"), ("SFQ 60:1", "SFQ(60:1)", BLUE, "D")]
    sweep = [k for k in g if k.startswith("ColaPri(")]
    aging = [k for k in g if k.startswith("Aging(")]
    name = [r[0] for r in rows]; p0 = [s[r[1]][2][0] for r in rows]; wm = [s[r[1]][0][0] for r in rows]
    col = [r[2] for r in rows]; mk = [r[3] for r in rows]
    offs = {"SFQ 6:1": (-50, -12), "DFPO": (8, -12), "EqualShare": (8, 4), "SFQ 60:1": (9, -9),
            "Strict": (9, -3), "SJF": (7, 5), "FCFS": (-34, -13)}
    fig, ax = plt.subplots(figsize=(6.2, 4.0))
    xmax = 50   # processor sharing (p0 = 86.5 s) is marked at the right edge instead of stretching the axis
    ax.set_xlim(20, xmax); ax.set_ylim(-25, 350)
    ax_, ay = [g[k][0][0] for k in aging], [g[k][2][0] for k in aging]
    ax.plot(ax_, ay, "-", color=AMBER, lw=1.0, zorder=2)
    ax.scatter(ax_, ay, s=40, c=AMBER, marker="s", edgecolors="black", linewidths=0.5, zorder=3,
               label=f"Priority+Aging, factor {aging[0][6:-1]} ... {aging[-1][6:-1]}")
    for k, x, y in zip(aging, ax_, ay):
        if k in ("Aging(0.05)", "Aging(0.1)", "Aging(0.2)"):
            ax.annotate(f"f={k[6:-1]}", (x, y), textcoords="offset points", xytext=(-38, -16), fontsize=7.5, color="#9a6d00",
                        arrowprops=dict(arrowstyle="-", color="#9a6d00", lw=0.6, shrinkB=4))
    cx, cy = [g[k][0][0] for k in sweep], [g[k][2][0] for k in sweep]
    ax.plot(cx, cy, "--", color=GREEN, lw=1.0, zorder=2)
    ax.scatter(cx, cy, s=110, c=GREEN, marker="*", edgecolors="black", linewidths=0.5, zorder=3,
               label=f"ColaPri, weight {sweep[0][8:-1]} ... {sweep[-1][8:-1]}")
    lab = {"ColaPri(1:1)": (-30, -4), "ColaPri(6:1)": (8, 9), "ColaPri(10:1)": (7, 2), "ColaPri(20:1)": (7, 2),
           "ColaPri(60:1)": (-6, 9)}
    for k, x, y in zip(sweep, cx, cy):
        if k in lab:
            ax.annotate(k[8:-1], (x, y), textcoords="offset points", xytext=lab[k], fontsize=7.5, color=GREEN)
    for i in range(len(name)):
        if p0[i] > xmax:   # off-axis baseline: an arrow at the right edge with its coordinates
            ax.annotate(f"{name[i]} ({p0[i]:.1f} s, {wm[i]:.1f} s)", xy=(xmax, wm[i]), xytext=(xmax - 0.3, 75), ha="right",
                        fontsize=8, arrowprops=dict(arrowstyle="->", color=DARK, lw=0.8))
            continue
        ax.scatter(p0[i], wm[i], s=95, c=col[i], marker=mk[i], edgecolors="black", linewidths=0.5, zorder=3)
        ax.annotate(name[i], (p0[i], wm[i]), textcoords="offset points", xytext=offs.get(name[i], (7, 5)), fontsize=8.5)
    ax.legend(fontsize=8, loc="upper right")
    ax.set_xlabel("high-class response p0 (s), lower is better")
    ax.set_ylabel("worst-case low-class wait p9 (s)")
    ax.set_title("Starvation trade-off (sustained load; lower left is better)", fontsize=10.5)
    ax.grid(True, alpha=0.3)
    fig.savefig(os.path.join(OUT, "fig_pareto_starv.pdf")); plt.close(fig)


def fig_adaptive():
    """The gate isolated: FCFS vs gated ColaPri (theta=15) vs ungated ColaPri (theta=0) at three loads (EXP-A ADAPTIVE)."""
    fig, axes = plt.subplots(1, 3, figsize=(9.6, 3.2), sharey=False)
    g = {}
    for lv in re.split(r"\n  carga ", _section(_log("A"), " ADAPTIVE"))[1:]:
        for mm in re.finditer(r"^\s+(\S+)\s+resp\.p0=([\d.]+)±[\d.]+\s+resp\.p9=([\d.]+)±", lv, re.M):
            g[(lv.split(" ")[0], mm.group(1))] = [float(mm.group(2)), float(mm.group(3))]
    pick = lambda lv: (g[(lv, "FCFS")], g[(lv, "ColaPri(theta=15)")], g[(lv, "ColaPri(theta=0)")])
    panels = [(axes[0], "light load (24 @ 0.3/s, no queue)", *pick("BAJA")),
              (axes[1], "moderate load (60 @ 0.55/s, backlog < 15)", *pick("MODERADA")),
              (axes[2], "burst (80 @ 3/s)", *pick("ALTA"))]
    for ax, title, f, g, u in panels:
        x = np.arange(2); w = 0.26
        ax.bar(x - w, f, w, label="FCFS", color=GREY)
        ax.bar(x, g, w, label=r"ColaPri $\theta$=15 (gated)", color=GREEN)
        ax.bar(x + w, u, w, label=r"ColaPri $\theta$=0 (ungated)", color=BLUE)
        ax.set_xticks(x); ax.set_xticklabels(["p0", "p9"]); ax.set_title(title, fontsize=9)
        ax.set_ylabel("mean response (s)")
    axes[1].legend(fontsize=7.5, loc="upper left")
    fig.suptitle("The gate isolated: no queue -> all equal; small backlog -> gated = FCFS, ungated differentiates; burst -> both differentiate", fontsize=9)
    fig.savefig(os.path.join(OUT, "fig_adaptive.pdf")); plt.close(fig)


def fig_datacenter():
    """Per mapping, ColaPri lowers high-class response vs the default scheduler."""
    mp = ["RoundRobin", "MinMin", "MaxMin", "DemandAware"]
    b = _rows(_log("B"))   # EXP-B: resp.p0 is the third metric column (makespan, loadCV, resp.p0, ...)
    d0 = [b[f"{m}/default"][2][0] for m in mp]; c0 = [b[f"{m}/ColaPri"][2][0] for m in mp]
    x = np.arange(len(mp)); w = 0.38
    fig, ax = plt.subplots(figsize=(6.6, 3.3))
    ax.bar(x - w / 2, d0, w, label="default FCFS (resp p0)", color=GREY)
    ax.bar(x + w / 2, c0, w, label="ColaPri  (resp p0)", color=GREEN)
    ax.set_xticks(x); ax.set_xticklabels(mp); ax.set_ylabel("high-class response p0 (s)")
    lo = int(min(c0)) - 1
    ax.set_ylim(lo, lo + 4); ax.set_title("Datacenter: ColaPri speeds p0 under every mapping (axis truncated)", fontsize=10)
    ax.legend(fontsize=8)
    fig.savefig(os.path.join(OUT, "fig_datacenter.pdf")); plt.close(fig)


def fig_scalability():
    """Differentiation grows with scale; makespan unchanged; overhead small."""
    n = [200, 500, 1000]
    c = _rows(_log("C"))   # EXP-C: makespan, resp.p0, resp.p9, overhead.ms, JFIw, JFI1
    d0 = [c[f"{k}/default"][1][0] for k in n]; c0 = [c[f"{k}/ColaPri"][1][0] for k in n]
    c9 = [c[f"{k}/ColaPri"][2][0] for k in n]; ov = [c[f"{k}/ColaPri"][3][0] for k in n]
    fig, ax1 = plt.subplots(figsize=(6.4, 3.4))
    ax1.plot(n, c0, "o-", color=GREEN, label="ColaPri p0")
    ax1.plot(n, c9, "s-", color=AMBER, label="ColaPri p9")
    ax1.plot(n, d0, "x--", color=GREY, label="default (p0=p9)")
    ax1.set_xlabel("number of tasks"); ax1.set_ylabel("mean response (s)"); ax1.set_xticks(n)
    ax2 = ax1.twinx()
    ax2.plot(n, ov, "^:", color=BLUE, label="ColaPri overhead (ms)")
    ax2.set_ylabel("scheduler overhead (ms)", color=BLUE); ax2.tick_params(axis="y", colors=BLUE)
    ax1.set_title("Scalability: differentiation widens, overhead stays low")
    lines = ax1.get_lines() + ax2.get_lines()
    ax1.legend(lines, [l.get_label() for l in lines], fontsize=8, loc="upper left")
    fig.savefig(os.path.join(OUT, "fig_scalability.pdf")); plt.close(fig)


def fig_sensitivity():
    """Three sensitivity panels: differentiation (p9-p0) vs load, vs PEs, vs threshold."""
    fig, ax = plt.subplots(1, 3, figsize=(9.4, 3.0))
    # EXP-D D3 / D4 / D2 (30 seeds): p9-p0 differentiation (third metric column)
    d = _log("D")
    d2, d3, d4 = _rows(_section(d, " D2")), _rows(_section(d, " D3")), _rows(_section(d, " D4"))
    ax[0].plot([0.5, 1, 2, 3, 5], [d3[k][2][0] for k in ("0.5", "1.0", "2.0", "3.0", "5.0")], "o-", color=GREEN)
    ax[0].set_title("vs offered load"); ax[0].set_xlabel("arrival rate (1/s)")
    ax[1].plot([1, 2, 4, 8], [d4[k][2][0] for k in ("1", "2", "4", "8")], "s-", color=AMBER)
    ax[1].set_title("vs VM PEs"); ax[1].set_xlabel("PEs per VM")
    ax[2].plot([0, 5, 15, 30, 60], [d2[k][2][0] for k in ("0", "5", "15", "30", "60")], "^-", color=BLUE)
    ax[2].set_title("vs adaptive threshold"); ax[2].set_xlabel(r"threshold $\theta$")
    ax[0].set_ylabel("differentiation  p9$-$p0  (s)")
    for a in ax:
        a.grid(True, alpha=0.3)
    fig.suptitle("Parameter sensitivity of ColaPri's differentiation", fontsize=10)
    fig.savefig(os.path.join(OUT, "fig_sensitivity.pdf")); plt.close(fig)


def fig_algo_flow():
    """Flowchart of Algorithm 1."""
    fig, ax = plt.subplots(figsize=(4.4, 5.4)); ax.set_xlim(0, 6); ax.set_ylim(0, 11); ax.axis("off")

    def cb(y, txt, fc, ec):
        _box(ax, 0.7, y, 3.6, 0.8, fc, ec); ax.text(2.5, y + 0.4, txt, ha="center", va="center", fontsize=8.3, color=DARK)
    cb(9.6, "dispatch event", "#eef3f8", BLUE)
    cb(8.2, r"$L\leftarrow|W|$;  $\alpha\leftarrow$Activation($L$)", "#eef3f8", BLUE)
    ax.add_patch(plt.Polygon([(2.5, 7.55), (4.3, 6.85), (2.5, 6.15), (0.7, 6.85)], closed=True, fc="#fff7e6", ec=AMBER, zorder=2))
    ax.text(2.5, 6.85, r"$\alpha=0$ ?", ha="center", va="center", fontsize=8.5)
    cb(4.7, r"tag untagged: $\tau=k/w^{\mathrm{eff}}$", "#eef6ef", GREEN)
    cb(3.4, r"sort waiting list by $\tau$", "#eef6ef", GREEN)
    cb(2.1, "dispatch to free PEs", "#eef6ef", GREEN)
    _box(ax, 4.7, 6.45, 1.2, 0.8, "#f3eef8", "#9b59b6"); ax.text(5.3, 6.85, "FIFO\n(return)", ha="center", va="center", fontsize=7.8, color=DARK)
    _arrow(ax, 2.5, 9.6, 2.5, 9.0); _arrow(ax, 2.5, 8.2, 2.5, 7.55)
    _arrow(ax, 2.5, 6.15, 2.5, 5.5); ax.text(2.65, 5.55, "no", fontsize=8, color=GREEN)
    _arrow(ax, 4.3, 6.85, 4.7, 6.85); ax.text(4.35, 7.05, "yes", fontsize=8, color="#9b59b6")
    _arrow(ax, 2.5, 4.7, 2.5, 4.2); _arrow(ax, 2.5, 3.4, 2.5, 2.9)
    fig.savefig(os.path.join(OUT, "fig_algo_flow.pdf")); plt.close(fig)


if __name__ == "__main__":
    fig_operation(); fig_architecture(); fig_problem(); fig_vtime_timeline(); fig_algo_flow()
    fig_starvation(); fig_pareto_starv(); fig_weights(); fig_perclass(); fig_adaptive()
    fig_datacenter(); fig_scalability(); fig_sensitivity()
    fig_traces(); fig_activation()  # fig_traces_speedup and fig_jain retired (Jain index dropped from the paper)
    print("figures written to", OUT)
