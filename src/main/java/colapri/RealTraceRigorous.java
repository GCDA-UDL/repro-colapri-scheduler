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
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import colapri.TraceLoader.Fmt;
import colapri.TraceLoader.TaskSpec;

/**
 * EXP-E: rigorous evaluation on REAL traces (Paper 4 datasets: PlanetLab, Alibaba, Bitbrains, Materna,
 * Azure). External validity for the journal: each VM's CPU series -> a cloudlet whose priority class is
 * derived from its burstiness (bursty=interactive=p0, stable=batch=p9). Per dataset, N_SEEDS random
 * subsamples (TraceLoader.sample) give mean +/- CI95 for FCFS / StrictPriority / WFQ-fair / SFQ-weighted / ColaPri.
 *
 * Traces mounted at /traces (mount networkExperiments/workloads there).
 */
public class RealTraceRigorous {
    private static final int MIPS = 1000, VM_PES = 2;
    private static final int N_SEEDS = 20, SAMPLE = 80;
    private static final double SPACING = 0.5, STARV_THRESHOLD = 50.0;

    private record WL(String name, String dir, Fmt fmt) {}

    private static final List<WL> WORKLOADS = List.of(
        new WL("PlanetLab", "/traces/planetlab/20110303", Fmt.PLANETLAB),
        new WL("Alibaba",   "/traces/alibaba2018/alibaba2018_fixed_first_500_100_mostDiff", Fmt.ALIBABA),
        new WL("Bitbrains", "/traces/bitbrains/bitbrains_rnd_2013-8_100_mostDiff_short", Fmt.BITBRAINS),
        new WL("Materna",   "/traces/materna/materna_trace1_valid_0_288_100_mostDiff", Fmt.MATERNA),
        new WL("Azure",     "/traces/azure/azure_azure2019_traces_100_mostDiff", Fmt.AZURE)
    );

    public static void main(String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        Metrics.setClassWeights(Map.of(0, 6, 5, 3, 9, 1));
        System.out.println("##########################################################################");
        System.out.println("#  EXP-E · ColaPri on REAL traces (" + N_SEEDS + " seeds, subsample " + SAMPLE + " VMs, CI95)");
        System.out.println("#  priority by burstiness: bursty=interactive(p0) ... stable=batch(p9)");
        System.out.println("#  " + Metrics.provenance());
        System.out.println("#  task length = clamp(0.3 * sum(CPU series), 1000, 12000) MI; one arrival every " + SPACING + " s;");
        System.out.println("#  rho = mean length / (MIPS * VM_PES * spacing); 'floor'/'ceiling' = share of tasks clipped to 1000 / 12000 MI");
        System.out.println("##########################################################################");

        final Map<String, Supplier<CloudletScheduler>> scheds = new LinkedHashMap<>();
        scheds.put("FCFS", CloudletSchedulerFifo::new);
        scheds.put("StrictPriority", CloudletSchedulerStrictPriority::new);
        scheds.put("EqualShare", CloudletSchedulerWFQ::new);   // equal share per class
        scheds.put("PS-timeshared", org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared::new);   // per-task processor sharing
        scheds.put("SFQ-weighted", () -> new CloudletSchedulerSFQ()     // classical weighted FQ, same weights (novelty control)
            .setPriorityWeight(0, 6).setPriorityWeight(5, 3).setPriorityWeight(9, 1));
        scheds.put("ColaPri", () -> new CloudletSchedulerColaPri()
            .setPriorityWeight(0, 6).setPriorityWeight(5, 3).setPriorityWeight(9, 1));

        for (final WL w : WORKLOADS) {
            final var raw = TraceLoader.loadRaw(w.dir(), w.fmt(), 200);
            if (raw.size() < 6) { System.out.println("\n[" + w.name() + "] SIN DATOS en " + w.dir()); continue; }
            System.out.printf("%n========== %s (%d VMs disponibles, subsample %d) ==========%n", w.name(), raw.size(), Math.min(SAMPLE, raw.size()));
            {   // what the trace-to-task reduction keeps: length distribution of the pool (same rule as TraceLoader.sample)
                int floor = 0, ceil = 0; double sum = 0;
                for (final double[] v : raw) {
                    final double len = Math.max(1000, Math.min(12000, v[0] * 0.3));
                    sum += len; if (v[0] * 0.3 <= 1000) floor++; if (v[0] * 0.3 >= 12000) ceil++;
                }
                final double meanLen = sum / raw.size();
                System.out.printf("  pool: mean length %.0f MI, floor %.1f%%, ceiling %.1f%%, rho~%.2f%n",
                    meanLen, 100.0 * floor / raw.size(), 100.0 * ceil / raw.size(), meanLen / (MIPS * VM_PES * SPACING));
            }
            final Map<String, List<Double>> respHigh = init(scheds), respLow = init(scheds),
                waitMaxLow = init(scheds), starvLow = init(scheds);
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final List<TaskSpec> specs = TraceLoader.sample(raw, seed, SAMPLE, SPACING);
                for (final var e : scheds.entrySet()) {
                    final var f = run(e.getValue(), specs);
                    respHigh.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 0));
                    respLow.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 9));
                    waitMaxLow.get(e.getKey()).add(Metrics.maxWaitOfClass(f, 9));
                    starvLow.get(e.getKey()).add((double) Metrics.starvationCount(f, 9, STARV_THRESHOLD));
                }
            }
            System.out.printf("%-16s %14s %14s %14s %10s%n", "scheduler", "resp.p0", "resp.p9", "waitMAX.p9", "starv.p9");
            for (final String k : scheds.keySet())
                System.out.printf("%-16s %14s %14s %14s %10s%n", k,
                    ci(respHigh.get(k)), ci(respLow.get(k)), ci(waitMaxLow.get(k)), ci(starvLow.get(k)));
            System.out.println("  (ColaPri debe bajar resp.p0 (interactiva) vs FCFS y acotar waitMAX.p9 vs StrictPriority)");
            System.out.printf("  paired: ColaPri - SFQ-weighted resp.p0 %s resp.p9 %s waitMAX.p9 %s | ColaPri - FCFS resp.p0 %s | ColaPri - StrictPriority waitMAX.p9 %s starv.p9 %s%n",
                Metrics.pairedCi(respHigh.get("ColaPri"), respHigh.get("SFQ-weighted")), Metrics.pairedCi(respLow.get("ColaPri"), respLow.get("SFQ-weighted")),
                Metrics.pairedCi(waitMaxLow.get("ColaPri"), waitMaxLow.get("SFQ-weighted")), Metrics.pairedCi(respHigh.get("ColaPri"), respHigh.get("FCFS")),
                Metrics.pairedCi(waitMaxLow.get("ColaPri"), waitMaxLow.get("StrictPriority")), Metrics.pairedCi(starvLow.get("ColaPri"), starvLow.get("StrictPriority")));
        }

        System.out.println("\n#  FIN EXP-E.");
        System.out.flush();
        System.exit(0);
    }

    private static Map<String, List<Double>> init(final Map<String, ?> s) {
        final Map<String, List<Double>> m = new LinkedHashMap<>();
        for (final String k : s.keySet()) m.put(k, new ArrayList<>());
        return m;
    }

    private static String ci(final List<Double> v) {
        final double[] mc = Metrics.meanCi95(v);
        return String.format("%.1f±%.1f", mc[0], mc[1]);
    }

    private static List<Cloudlet> run(final Supplier<CloudletScheduler> sched, final List<TaskSpec> specs) {
        final var sim = Metrics.newSimulation();
        final var peList = new ArrayList<Pe>(VM_PES);
        for (int i = 0; i < VM_PES; i++) peList.add(new PeSimple(MIPS));
        final Host host = new HostSimple(16384, 100_000, 10_000_000, peList);
        new DatacenterSimple(sim, List.of(host));
        final var broker = new DatacenterBrokerSimple(sim);
        broker.setVmDestructionDelay(1_000_000.0);
        final var vm = new VmPatched(MIPS, VM_PES);
        vm.setRam(8192).setBw(10_000).setSize(10_000).setCloudletScheduler(sched.get());
        broker.submitVmList(List.of(vm));
        final var cloudlets = new ArrayList<Cloudlet>(specs.size());
        for (final TaskSpec s : specs) {
            final var c = Workload.cloudlet(s.length());
            c.setSizes(1024);
            c.setPriority(s.priority());
            c.setSubmissionDelay(s.arrival());
            cloudlets.add(c);
        }
        broker.submitCloudletList(cloudlets);
        sim.start();
        return Metrics.checked(broker.getCloudletFinishedList(), cloudlets.size());
    }
}
