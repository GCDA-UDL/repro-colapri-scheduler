package colapri;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * EXP-H (since package version 1.1.3): stochastic arrivals.
 *
 * <p>The other synthetic experiments place arrivals on a jittered grid, which forms almost no queue below capacity, so
 * their differentiation comes from overload or bursts. This harness uses Poisson arrivals on the same VM (2 PEs at
 * 1000 MIPS, capacity 0.5 tasks/s for 4000 MI tasks) and discards a warm-up before measuring.</p>
 *
 * <p>Part 1, engine validation: FCFS with Poisson arrivals and exponential task lengths is an M/M/2 queue; its mean
 * waiting time is compared with the Erlang-C value at four loads. Part 2, a stable heavy-traffic regime (offered load
 * 0.7 to 0.95, classes 0/9 mixed 9:1, lengths 4000 MI +/-30% as in EXP-A): per-class response, the 95th percentile
 * and the maximum of the low-class wait, and the frequency-weighted mean wait of all tasks, which Kleinrock's
 * conservation law keeps (nearly) constant across work-conserving schedulers that ignore task length.</p>
 */
public class StochasticEval {
    private static final int MIPS = 1000, VM_PES = 2;
    private static final long BASE_LEN = 4000;
    private static final int N_SEEDS = 30;
    private static final double CAPACITY = VM_PES * MIPS / (double) BASE_LEN;   // 0.5 tasks/s
    private static final int N_VALID = 5000, WARM_VALID = 500;
    private static final int N_MAIN = 3000, WARM_MAIN = 300;

    public static void main(final String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        Metrics.setClassWeights(Map.of(0, 6, 9, 1));
        System.out.println("##########################################################################");
        System.out.println("#  EXP-H · stochastic (Poisson) arrivals: M/M/2 validation of the engine and a stable");
        System.out.println("#  heavy-traffic regime (" + N_SEEDS + " seeds, CI95, warm-up discarded)");
        System.out.println("#  " + Metrics.provenance());
        System.out.println("##########################################################################");
        validation();
        stableRegime();
        System.out.println("\n#  FIN EXP-H.");
        System.out.flush();
        System.exit(0);
    }

    /** Erlang-C mean waiting time of an M/M/c queue (arrival rate lambda, service rate mu per server). */
    static double erlangCWait(final double lambda, final double mu, final int c) {
        final double a = lambda / mu, rho = a / c;
        double sum = 0, term = 1;
        for (int k = 0; k < c; k++) { if (k > 0) term *= a / k; sum += term; }
        final double last = term * a / c / (1 - rho);    // a^c / c! / (1 - rho)
        final double pWait = last / (sum + last);
        return pWait / (c * mu - lambda);
    }

    private static void validation() {
        System.out.println("\n=== VALIDATION · FCFS, Poisson arrivals, exponential lengths (mean " + BASE_LEN
            + " MI) = M/M/2; " + N_VALID + " tasks, first " + WARM_VALID + " discarded, " + N_SEEDS + " seeds ===");
        System.out.printf("%n%-6s %10s %16s %12s %10s%n", "rho", "lambda/s", "wait (sim)", "Erlang-C", "rel.diff");
        final double mu = MIPS / (double) BASE_LEN;      // per PE: 0.25 tasks/s
        for (final double rho : new double[]{0.5, 0.7, 0.8, 0.9}) {
            final double lambda = rho * CAPACITY;
            final var w = new ArrayList<Double>();
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final var specs = Workload.poisson(seed, N_VALID, lambda, new int[]{0}, new int[]{1}, BASE_LEN, -1);
                final var f = Metrics.arrivedFrom(run(CloudletSchedulerFifo::new, specs), specs.get(WARM_VALID).arrival());
                w.add(Metrics.meanWait(f));
            }
            final double theory = erlangCWait(lambda, mu, VM_PES);
            final double[] mc = Metrics.meanCi95(w);
            System.out.printf("%-6.2f %10.3f %16s %12.4f %+9.1f%%%n", rho, lambda, ci(w), theory, 100 * (mc[0] - theory) / theory);
        }
    }

    private static Map<String, Supplier<CloudletScheduler>> schedulers() {
        final Map<String, Supplier<CloudletScheduler>> m = new LinkedHashMap<>();
        m.put("FCFS", CloudletSchedulerFifo::new);
        m.put("StrictPriority", CloudletSchedulerStrictPriority::new);
        m.put("Priority+Aging", () -> new CloudletSchedulerPriorityAging(0.2));
        m.put("EqualShare", CloudletSchedulerWFQ::new);
        m.put("DFPO", () -> new CloudletSchedulerDFPO(3, 2, 1));
        m.put("SFQ-weighted", () -> new CloudletSchedulerSFQ().setPriorityWeight(0, 6).setPriorityWeight(9, 1));
        m.put("ColaPri", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 6).setPriorityWeight(9, 1));
        // larger weights: with a 9:1 mix, 6:1 entitles the low class to 1/7 of the dispatches, more than its 10% of the
        // traffic; 20:1 (1/21) and 60:1 (1/61) favour the high class, as strict priority does, with a bound
        m.put("SFQ(20:1)", () -> new CloudletSchedulerSFQ().setPriorityWeight(0, 20).setPriorityWeight(9, 1));
        m.put("ColaPri(20:1)", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 20).setPriorityWeight(9, 1));
        m.put("ColaPri(60:1)", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 60).setPriorityWeight(9, 1));
        return m;
    }

    private static void stableRegime() {
        System.out.println("\n\n=== STABLE HEAVY TRAFFIC · Poisson arrivals, classes 0/9 mixed 9:1, lengths " + BASE_LEN
            + " MI +/-30%; " + N_MAIN + " tasks, first " + WARM_MAIN + " discarded, " + N_SEEDS + " seeds; weights 6:1 ===");
        System.out.println("  (wait.p95.p9 = 95th percentile of the low-class waiting time in a seed; meanWait.all = mean wait of all");
        System.out.println("   tasks, the frequency-weighted mean that Kleinrock's conservation law keeps constant for length-blind schedulers)");
        for (final double rho : new double[]{0.7, 0.8, 0.9, 0.95}) {
            final double lambda = rho * CAPACITY;
            final var sch = schedulers();
            final Map<String, List<Double>> r0 = init(sch), r9 = init(sch), q9 = init(sch), m9 = init(sch), wAll = init(sch);
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final var specs = Workload.poisson(seed, N_MAIN, lambda, new int[]{0, 9}, new int[]{9, 1}, BASE_LEN, 0.3);
                final double t0 = specs.get(WARM_MAIN).arrival();
                for (final var e : sch.entrySet()) {
                    final var f = Metrics.arrivedFrom(run(e.getValue(), specs), t0);
                    r0.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 0));
                    r9.get(e.getKey()).add(Metrics.meanResponseOfClass(f, 9));
                    q9.get(e.getKey()).add(Metrics.quantileWaitOfClass(f, 9, 0.95));
                    m9.get(e.getKey()).add(Metrics.maxWaitOfClass(f, 9));
                    wAll.get(e.getKey()).add(Metrics.meanWait(f));
                }
            }
            System.out.printf("%n--- offered load rho = %.2f (lambda = %.3f tasks/s) ---%n", rho, lambda);
            System.out.printf("%-16s %14s %14s %14s %14s %14s%n", "scheduler", "resp.p0", "resp.p9", "wait.p95.p9", "waitMAX.p9", "meanWait.all");
            for (final String k : sch.keySet())
                System.out.printf("%-16s %14s %14s %14s %14s %14s%n", k, ci(r0.get(k)), ci(r9.get(k)), ci(q9.get(k)), ci(m9.get(k)), ci(wAll.get(k)));
            System.out.println("  paired per-seed differences (mean±CI95 of the difference):");
            for (final String o : new String[]{"FCFS", "SFQ-weighted", "Priority+Aging", "StrictPriority", "DFPO", "EqualShare"})
                System.out.printf("    %-26s resp.p0 %s  resp.p9 %s  wait.p95.p9 %s  waitMAX.p9 %s%n", "ColaPri - " + o,
                    Metrics.pairedCi(r0.get("ColaPri"), r0.get(o)), Metrics.pairedCi(r9.get("ColaPri"), r9.get(o)),
                    Metrics.pairedCi(q9.get("ColaPri"), q9.get(o)), Metrics.pairedCi(m9.get("ColaPri"), m9.get(o)));
            for (final String[] pr : new String[][]{{"ColaPri(20:1)", "FCFS"}, {"ColaPri(20:1)", "Priority+Aging"},
                    {"ColaPri(20:1)", "StrictPriority"}, {"ColaPri(20:1)", "SFQ(20:1)"}, {"ColaPri(60:1)", "StrictPriority"},
                    {"ColaPri(60:1)", "Priority+Aging"}})
                System.out.printf("    %-26s resp.p0 %s  resp.p9 %s  wait.p95.p9 %s  waitMAX.p9 %s%n", pr[0] + " - " + pr[1],
                    Metrics.pairedCi(r0.get(pr[0]), r0.get(pr[1])), Metrics.pairedCi(r9.get(pr[0]), r9.get(pr[1])),
                    Metrics.pairedCi(q9.get(pr[0]), q9.get(pr[1])), Metrics.pairedCi(m9.get(pr[0]), m9.get(pr[1])));
        }
    }

    private static Map<String, List<Double>> init(final Map<String, ?> s) {
        final Map<String, List<Double>> m = new LinkedHashMap<>();
        for (final String k : s.keySet()) m.put(k, new ArrayList<>());
        return m;
    }

    private static String ci(final List<Double> v) {
        final double[] mc = Metrics.meanCi95(v);
        return String.format("%.2f±%.2f", mc[0], mc[1]);
    }

    private static List<Cloudlet> run(final Supplier<CloudletScheduler> factory, final List<Workload.Spec> specs) {
        final var sim = Metrics.newSimulation();
        final var peList = new ArrayList<Pe>(VM_PES);
        for (int i = 0; i < VM_PES; i++) peList.add(new PeSimple(MIPS));
        final Host host = new HostSimple(8192, 100_000, 1_000_000, peList);
        new DatacenterSimple(sim, List.of(host));
        final var broker = new DatacenterBrokerSimple(sim);
        broker.setVmDestructionDelay(1_000_000.0);
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
        sim.start();
        return Metrics.checked(broker.getCloudletFinishedList(), cloudlets.size());
    }
}
