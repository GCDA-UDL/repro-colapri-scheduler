package colapri;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerCompletelyFair;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * EXP-A: rigorous (multi-seed) scheduling evaluation on synthetic workload W1.
 *
 * Upgrades Experiments.java to journal grade: N_SEEDS independent workload instances per config,
 * every metric reported as mean +/- CI95, scheduler decision OVERHEAD measured (wall time of
 * sim.start()), weighted-Jain aligned to the scheduler's class weights. Baselines: FCFS, SJF,
 * StrictPriority, Priority+Aging, WFQ-fair (equal weights), DFPO, SFQ-weighted (classical weighted
 * fair queue with ColaPri's weights), (optional CFS), ColaPri.
 *
 * Three scenarios: MAIN comparison, STARVATION (isolated), ADAPTIVE (load-gated activation).
 */
public class RigorousEval {
    private static final int MIPS = 1000;
    private static final int VM_PES = 2;
    private static final long BASE_LEN = 4000;
    private static final int N_SEEDS = 30;
    private static final double STARV_THRESHOLD = 30.0;   // wait > 30s counts as starved (class 9)

    /** Set true once CfsProbe confirms CFS terminates with a Datacenter schedulingInterval. */
    private static final boolean INCLUDE_CFS = false;

    private record Result(List<Cloudlet> finished, double wallMs) {}

    public static void main(String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        // align the fairness metric to the weights ColaPri actually uses (6:3:1 for classes 0/5/9)
        Metrics.setClassWeights(Map.of(0, 6, 5, 3, 9, 1));

        System.out.println("##########################################################################");
        System.out.println("#  EXP-A · ColaPri rigorous scheduling eval (W1 synthetic, " + N_SEEDS + " seeds, CI95)");
        System.out.println("#  overhead = wall time of sim.start() (proxy for scheduling decision cost)");
        System.out.println("#  " + Metrics.provenance());
        System.out.println("##########################################################################");

        scenarioMain();
        scenarioStarvation();
        scenarioAdaptive();
        scenarioIdleReturn();

        System.out.println("\n#  FIN EXP-A.");
        System.out.flush();
        System.exit(0);
    }

    // ---- MAIN: moderate-high load, 3 classes, weighted differentiation + fairness + overhead ----
    private static void scenarioMain() {
        System.out.println("\n=== MAIN · 60 tareas, ráfaga 5/s, clases 0/5/9 (mix 3:2:1), 30 seeds ===");
        final var schedulers = schedulers();
        // metric samples per scheduler
        final Map<String, List<Double>> respHigh = init(schedulers), p95High = init(schedulers),
            respMid = init(schedulers), respLow = init(schedulers), waitMaxLow = init(schedulers),
            starvLow = init(schedulers), jfiW = init(schedulers), jfi1 = init(schedulers), makespan = init(schedulers), overhead = init(schedulers);

        for (long seed = 0; seed < N_SEEDS; seed++) {
            final var w = Workload.seeded(seed, 60, 5.0, new int[]{0, 5, 9}, new int[]{3, 2, 1}, BASE_LEN, 0.3, 0.2);
            for (final var e : schedulers.entrySet()) {
                final var r = run(e.getValue(), w, e.getKey().equals("CFS"));
                final var f = r.finished();
                respHigh.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 0));
                p95High.get(e.getKey()).add(Metrics.p95ResponseOfClass(f, 0));
                respMid.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 5));
                respLow.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 9));
                waitMaxLow.get(e.getKey()).add(Metrics.maxWaitOfClass(f, 9));
                starvLow.get(e.getKey()).add((double) Metrics.starvationCount(f, 9, STARV_THRESHOLD));
                jfiW.get(e.getKey()).add(Metrics.jainNormalisedService(f, true));
                jfi1.get(e.getKey()).add(Metrics.jainNormalisedService(f, false));
                makespan.get(e.getKey()).add(Metrics.makespan(f));
                overhead.get(e.getKey()).add(r.wallMs());
            }
        }
        System.out.printf("%n%-16s %14s %14s %14s %12s %10s %11s %11s %11s%n",
            "scheduler", "resp.p0", "resp.p5", "resp.p9", "waitMAX.p9", "starv.p9", "overhead.ms", "JFIw", "JFI1");
        for (final String k : schedulers.keySet())
            System.out.printf("%-16s %14s %14s %14s %12s %10s %11s %11s %11s%n", k,
                ci(respHigh.get(k)), ci(respMid.get(k)), ci(respLow.get(k)), ci(waitMaxLow.get(k)),
                ci(starvLow.get(k)), ci(overhead.get(k)), ci2(jfiW.get(k)), ci2(jfi1.get(k)));
        System.out.println("  (resp por clase: FCFS≈plano · Strict≈extremo · ColaPri≈graduado p0<p5<p9)");
        System.out.println("  (JFIw = Jain index of per-class dispatches / weight up to the first class drain, 1 = shares exactly 6:3:1;");
        System.out.println("   JFI1 = same with weight 1 for every class, i.e. the equal-share criterion)");
        System.out.println("  paired per-seed differences (mean±CI95 of the difference):");
        System.out.printf("    %-28s resp.p0 %s  resp.p5 %s  resp.p9 %s%n", "ColaPri - SFQ-weighted",
            Metrics.pairedCi(respHigh.get("ColaPri"), respHigh.get("SFQ-weighted")),
            Metrics.pairedCi(respMid.get("ColaPri"), respMid.get("SFQ-weighted")),
            Metrics.pairedCi(respLow.get("ColaPri"), respLow.get("SFQ-weighted")));
        System.out.printf("    %-28s resp.p0 %s  resp.p9 %s%n", "ColaPri - FCFS",
            Metrics.pairedCi(respHigh.get("ColaPri"), respHigh.get("FCFS")),
            Metrics.pairedCi(respLow.get("ColaPri"), respLow.get("FCFS")));
    }

    // ---- STARVATION isolated: continuous high-priority arrivals, NOT saturated ----
    private static void scenarioStarvation() {
        System.out.println("\n\n=== STARVATION (aislada, llegadas SOSTENIDAS) · 200 tareas 90% p0 + 10% p9, 0.6/s, 30 seeds ===");
        System.out.println("  (rate≈capacidad → p0 fresco llega mientras p9 viejo espera; foco: espera MÁX / inanidos p9)");
        final var schedulers = schedulers();
        // weight sweep: the per-class weight moves ColaPri along the FCFS <-> strict-priority frontier
        for (final int w0 : new int[]{8, 10, 12})
            schedulers.put("ColaPri(" + w0 + ":1)", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, w0).setPriorityWeight(9, 1));
        schedulers.put("ColaPri(20:1)", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 20).setPriorityWeight(9, 1));
        schedulers.put("ColaPri(60:1)", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 60).setPriorityWeight(9, 1));
        schedulers.put("SFQ(60:1)", () -> new CloudletSchedulerSFQ().setPriorityWeight(0, 60).setPriorityWeight(9, 1));
        final Map<String, List<Double>> waitMaxLow = init(schedulers), starvLow = init(schedulers), respHigh = init(schedulers);
        for (long seed = 0; seed < N_SEEDS; seed++) {
            final var w = Workload.seeded(seed, 200, 0.6, new int[]{0, 9}, new int[]{9, 1}, BASE_LEN, 0.3, 0.2);
            for (final var e : schedulers.entrySet()) {
                final var f = run(e.getValue(), w, e.getKey().equals("CFS")).finished();
                waitMaxLow.get(e.getKey()).add(Metrics.maxWaitOfClass(f, 9));
                starvLow.get(e.getKey()).add((double) Metrics.starvationCount(f, 9, STARV_THRESHOLD));
                respHigh.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 0));
            }
        }
        System.out.printf("%n%-16s %14s %12s %14s%n", "scheduler", "waitMAX.p9", "starv.p9", "resp.p0(coste)");
        for (final String k : schedulers.keySet())
            System.out.printf("%-16s %14s %12s %14s%n", k, ci(waitMaxLow.get(k)), ci(starvLow.get(k)), ci(respHigh.get(k)));
        System.out.println("  (Strict: waitMAX.p9 explota · ColaPri: acotada, manteniendo resp.p0 baja)");
        System.out.println("  paired per-seed differences (mean±CI95 of the difference):");
        for (final String[] pr : new String[][]{{"ColaPri", "SFQ-weighted"}, {"ColaPri(60:1)", "SFQ(60:1)"},
                                                {"ColaPri", "FCFS"}, {"ColaPri(60:1)", "StrictPriority"}})
            System.out.printf("    %-30s waitMAX.p9 %s  starv.p9 %s  resp.p0 %s%n", pr[0] + " - " + pr[1],
                Metrics.pairedCi(waitMaxLow.get(pr[0]), waitMaxLow.get(pr[1])),
                Metrics.pairedCi(starvLow.get(pr[0]), starvLow.get(pr[1])),
                Metrics.pairedCi(respHigh.get(pr[0]), respHigh.get(pr[1])));
        boundAudit();
    }

    // ---- Proposition 2, checked task by task on the starvation workload: every ColaPri task tagged at full activation
    //      is dispatched after at most D(b_c,c) other dispatches and waits at most (ceil(D/m)+1)*S_max after tagging ----
    private static void boundAudit() {
        System.out.println("\n  Proposition 2 audit (every task, all seeds; m=" + VM_PES + " PEs; S_max = longest task of the seed;");
        System.out.println("   wait measured from tagging; 'from arrival' adds the time a task spent untagged):");
        for (final int w0 : new int[]{6, 8, 10, 12, 20, 60}) {
            long n = 0, vD = 0, vT = 0, vA = 0;
            double maxRD = 0, maxRT = 0, maxTagDelay = 0;
            final List<Double> worstWait = new ArrayList<>(), worstB = new ArrayList<>(), worstBound = new ArrayList<>();
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final var w = Workload.seeded(seed, 200, 0.6, new int[]{0, 9}, new int[]{9, 1}, BASE_LEN, 0.3, 0.2);
                double sMax = 0;
                for (final var s : w) sMax = Math.max(sMax, s.length() / (double) MIPS);
                final var sched = new CloudletSchedulerColaPri().setPriorityWeight(0, w0).setPriorityWeight(9, 1).enableBoundAudit();
                run(() -> sched, w, false);
                double ww = -1, wb = 0, wbound = 0;
                for (final double[] r : sched.boundAudit()) {
                    n++;
                    final double d = r[2], others = r[3], waitTag = r[5] - r[4], waitArr = r[5] - r[6];
                    final double tb = (Math.ceil(d / VM_PES) + 1) * sMax;
                    if (others > d) vD++;
                    if (waitTag > tb + 1e-9) vT++;
                    if (waitArr > tb + 1e-9) vA++;
                    maxRD = Math.max(maxRD, d > 0 ? others / d : 0);
                    maxRT = Math.max(maxRT, waitTag / tb);
                    maxTagDelay = Math.max(maxTagDelay, r[4] - r[6]);
                    if (r[0] == 9 && waitArr > ww) { ww = waitArr; wb = r[1]; wbound = tb; }
                }
                if (ww >= 0) { worstWait.add(ww); worstB.add(wb); worstBound.add(wbound); }
            }
            System.out.printf("    ColaPri(%d:1) tasks=%d  dispatch-bound violations=%d  time-bound violations=%d (from arrival %d)"
                    + "  max others/D=%.2f  max wait/bound=%.2f  max untagged time=%.2f s%n",
                w0, n, vD, vT, vA, maxRD, maxRT, maxTagDelay);
            System.out.printf("      longest low-class wait per seed %s s: it found b_c=%s class-mates waiting; its bound %s s%n",
                ci(worstWait), ci(worstB), ci(worstBound));
        }
    }

    // ---- ADAPTIVE: the gate (theta=15) is isolated against the ungated scheduler (theta=0) and FCFS at three
    //      load levels: light (no backlog: every scheduler coincides), moderate (small backlog below the
    //      threshold: only the ungated ColaPri differentiates) and heavy burst (both activate) ----
    private static void scenarioAdaptive() {
        System.out.println("\n\n=== ADAPTIVE (umbral=15 vs umbral=0 vs FCFS) · carga BAJA / MODERADA / ALTA, 30 seeds ===");
        final Map<String, Supplier<CloudletScheduler>> m = new LinkedHashMap<>();
        m.put("FCFS", CloudletSchedulerFifo::new);
        m.put("ColaPri(theta=15)", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 6).setPriorityWeight(9, 1).setAdaptiveThreshold(15));
        m.put("ColaPri(theta=0)", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 6).setPriorityWeight(9, 1));
        final var levels = new String[]{"BAJA (24 tareas @0.3/s, rho~0.6, sin backlog)", "MODERADA (60 tareas @0.55/s, rho~1.1, backlog < umbral)", "ALTA (80 tareas @3/s, rafaga, rho~6)"};
        final double[] rates = {0.3, 0.55, 3.0};
        final int[] ns = {24, 60, 80};
        for (int s = 0; s < 3; s++) {
            final Map<String, List<Double>> p0 = init(m), p9 = init(m), bk = init(m);
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final var w = Workload.seeded(seed, ns[s], rates[s], new int[]{0, 9}, new int[]{1, 1}, BASE_LEN, 0.3, 0.2);
                for (final var e : m.entrySet()) {
                    final var f = run(e.getValue(), w, false).finished();
                    p0.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 0));
                    p9.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 9));
                    bk.get(e.getKey()).add(Metrics.maxWaitOfClass(f, 9));
                }
            }
            System.out.printf("%n  carga %s:%n", levels[s]);
            for (final String k : m.keySet())
                System.out.printf("    %-18s resp.p0=%-12s resp.p9=%-12s waitMAX.p9=%-12s%n", k, ci(p0.get(k)), ci(p9.get(k)), ci(bk.get(k)));
            System.out.printf("    paired: theta=15 - FCFS resp.p0 %s resp.p9 %s | theta=0 - FCFS resp.p0 %s resp.p9 %s%n",
                Metrics.pairedCi(p0.get("ColaPri(theta=15)"), p0.get("FCFS")), Metrics.pairedCi(p9.get("ColaPri(theta=15)"), p9.get("FCFS")),
                Metrics.pairedCi(p0.get("ColaPri(theta=0)"), p0.get("FCFS")), Metrics.pairedCi(p9.get("ColaPri(theta=0)"), p9.get("FCFS")));
        }
        System.out.println("  (BAJA: todos iguales, no hay cola · MODERADA: theta=15 sigue a FCFS, theta=0 diferencia · ALTA: ambos diferencian)");
    }

    // ---- IDLE RETURN: a low class that was idle returns while the high class is backlogged ----
    private static void scenarioIdleReturn() {
        System.out.println("\n\n=== IDLE-RETURN · clase 0 sostenida 0.6/s (300 tareas) + clase 9 inactiva hasta t=300 s, luego ráfaga de 20 a 5/s, 30 seeds ===");
        System.out.println("  (maxRun.p9 = racha máxima de arranques consecutivos de p9; resp.p0[300,380) = respuesta de las p0 que llegan durante la ráfaga)");
        final Map<String, Supplier<CloudletScheduler>> m = new LinkedHashMap<>();
        m.put("FCFS", CloudletSchedulerFifo::new);
        m.put("StrictPriority", CloudletSchedulerStrictPriority::new);
        m.put("SFQ-weighted", () -> new CloudletSchedulerSFQ().setPriorityWeight(0, 6).setPriorityWeight(9, 1));
        m.put("ColaPri(no-sync)", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 6).setPriorityWeight(9, 1).setResync(false));
        m.put("ColaPri", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 6).setPriorityWeight(9, 1));
        final Map<String, List<Double>> maxRun = init(m), respHighWin = init(m), respLow = init(m), waitMaxLow = init(m);
        for (long seed = 0; seed < N_SEEDS; seed++) {
            final var high = Workload.seeded(seed, 300, 0.6, new int[]{0}, new int[]{1}, BASE_LEN, 0.3, 0.2);
            final var burst = Workload.seeded(seed + 500, 20, 5.0, new int[]{9}, new int[]{1}, BASE_LEN, 0.3, 0.2);
            final var w = new ArrayList<Workload.Spec>(high);
            for (final var s : burst) w.add(new Workload.Spec(s.arrival() + 300.0, s.priority(), s.length()));
            for (final var e : m.entrySet()) {
                final var f = run(e.getValue(), w, false).finished();
                maxRun.get(e.getKey()).add((double) Metrics.maxConsecutiveStarts(f, 9));
                respHighWin.get(e.getKey()).add(Metrics.meanResponseOfClassArrivingIn(f, 0, 300.0, 380.0));
                respLow.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 9));
                waitMaxLow.get(e.getKey()).add(Metrics.maxWaitOfClass(f, 9));
            }
        }
        System.out.printf("%n%-16s %12s %18s %14s %14s%n", "scheduler", "maxRun.p9", "resp.p0[300,380)", "resp.p9", "waitMAX.p9");
        for (final String k : m.keySet())
            System.out.printf("%-16s %12s %18s %14s %14s%n", k, ci(maxRun.get(k)), ci(respHighWin.get(k)), ci(respLow.get(k)), ci(waitMaxLow.get(k)));
        System.out.println("  (sin resincronización la clase que vuelve acapara una racha larga y retrasa a p0; con resincronización se intercala 6:1)");
        System.out.printf("  paired: ColaPri - ColaPri(no-sync) maxRun.p9 %s resp.p0[300,380) %s | ColaPri - SFQ-weighted maxRun.p9 %s resp.p0[300,380) %s%n",
            Metrics.pairedCi(maxRun.get("ColaPri"), maxRun.get("ColaPri(no-sync)")), Metrics.pairedCi(respHighWin.get("ColaPri"), respHighWin.get("ColaPri(no-sync)")),
            Metrics.pairedCi(maxRun.get("ColaPri"), maxRun.get("SFQ-weighted")), Metrics.pairedCi(respHighWin.get("ColaPri"), respHighWin.get("SFQ-weighted")));
    }

    // ---- infra ----
    private static Map<String, Supplier<CloudletScheduler>> schedulers() {
        final Map<String, Supplier<CloudletScheduler>> m = new LinkedHashMap<>();
        m.put("FCFS", CloudletSchedulerFifo::new);
        m.put("SJF", CloudletSchedulerSJF::new);
        m.put("StrictPriority", CloudletSchedulerStrictPriority::new);
        m.put("Priority+Aging", () -> new CloudletSchedulerPriorityAging(0.2));  // tuned so aging actually fires (0.05 degenerates to strict)
        m.put("EqualShare", CloudletSchedulerWFQ::new);   // equal share per CLASS (virtual-time tags, weight 1 for every class)
        m.put("PS-timeshared", CloudletSchedulerTimeShared::new);   // CloudSim Plus built-in processor sharing per TASK (fair reference)
        m.put("DFPO", () -> new CloudletSchedulerDFPO(3, 2, 1));  // Saxena et al. 2016 baseline: 3 queues, WRR quanta 3:2:1
        m.put("SFQ-weighted", () -> new CloudletSchedulerSFQ()     // classical weighted fair queue (SFQ), SAME weights as ColaPri: novelty control
            .setPriorityWeight(0, 6).setPriorityWeight(5, 3).setPriorityWeight(9, 1));
        if (INCLUDE_CFS) m.put("CFS", CloudletSchedulerCompletelyFair::new);
        m.put("ColaPri", () -> new CloudletSchedulerColaPri()
            .setPriorityWeight(0, 6).setPriorityWeight(5, 3).setPriorityWeight(9, 1));
        return m;
    }

    private static Map<String, List<Double>> init(final Map<String, ?> schedulers) {
        final Map<String, List<Double>> m = new LinkedHashMap<>();
        for (final String k : schedulers.keySet()) m.put(k, new ArrayList<>());
        return m;
    }

    private static String ci2(final List<Double> v) {
        final double[] mc = Metrics.meanCi95(v);
        return String.format("%.2f±%.2f", mc[0], mc[1]);
    }

    private static String ci(final List<Double> v) {
        final double[] mc = Metrics.meanCi95(v);
        return String.format("%.1f±%.1f", mc[0], mc[1]);
    }

    private static Result run(final Supplier<CloudletScheduler> factory, final List<Workload.Spec> specs, final boolean schedInterval) {
        final var sim = Metrics.newSimulation();
        final var peList = new ArrayList<Pe>(VM_PES);
        for (int i = 0; i < VM_PES; i++) peList.add(new PeSimple(MIPS));
        final Host host = new HostSimple(8192, 100_000, 1_000_000, peList);
        final var dc = new DatacenterSimple(sim, List.of(host));
        if (schedInterval) dc.setSchedulingInterval(1.0);   // needed for CFS to terminate
        final var broker = new DatacenterBrokerSimple(sim);
        broker.setVmDestructionDelay(100_000.0);
        final var vm = new VmPatched(MIPS, VM_PES);
        vm.setRam(4096).setBw(10_000).setSize(10_000).setCloudletScheduler(factory.get());
        broker.submitVmList(List.of(vm));
        final var cloudlets = new ArrayList<Cloudlet>(specs.size());
        for (final var s : specs) {
            final var c = Workload.cloudlet(s.length());
            c.setSizes(1024);
            c.setPriority(s.priority());
            c.setSubmissionDelay(s.arrival());
            cloudlets.add(c);
        }
        broker.submitCloudletList(cloudlets);
        final long t0 = System.nanoTime();
        sim.start();
        final double wallMs = (System.nanoTime() - t0) / 1e6;
        return new Result(Metrics.checked(broker.getCloudletFinishedList(), cloudlets.size()), wallMs);
    }
}
