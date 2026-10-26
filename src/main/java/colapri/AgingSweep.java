package colapri;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * EXP-G: aging-factor sweep on the starvation workload of EXP-A.
 *
 * Linear aging ({@link CloudletSchedulerPriorityAging}) dispatches the waiting task with the lowest
 * priority - f * waited. At a dispatch every task sees the same clock, so this is the order of first-seen time
 * + priority / f: a class-9 task is ordered as if it had arrived 9/f seconds later than it did. EXP-A runs one
 * factor (0.2, offset 45 s). Here the factor is swept so that aging traces its own trade-off curve, compared with
 * ColaPri's weight sweep on the same workloads. At a matched high-class response the other curve is interpolated
 * between its two neighbouring settings: the same convex combination for every seed, i.e. the expected result of
 * picking one of the two settings at random, so the per-seed paired difference keeps its meaning.
 *
 * Scenarios: the EXP-A starvation workload (200 tasks at 0.6/s, overloaded) and the same arrival process over a three
 * times longer horizon (600 tasks; the first 200 are the EXP-A tasks). Below capacity the near-periodic arrivals of
 * {@link Workload#seeded} form no queue (at 0.45/s every configuration gives identical results), so no stable-load
 * scenario is run.
 */
public class AgingSweep {
    private static final int MIPS = 1000;
    private static final int VM_PES = 2;
    private static final long BASE_LEN = 4000;
    private static final int N_SEEDS = 30;
    private static final double LATE = 30.0;   // wait > 30 s counts as a late low-class task, as in EXP-A
    private static final double[] FACTORS = {0.01, 0.02, 0.03, 0.05, 0.075, 0.1, 0.15, 0.2, 0.3, 0.5, 1.0, 3.0};
    private static final int[] WEIGHTS = {1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 30, 40, 60};
    private static final String[] METRIC = {"resp.p0", "resp.p9", "waitMAX.p9", "late.p9"};

    public static void main(String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        System.out.println("##########################################################################");
        System.out.println("#  EXP-G · aging-factor sweep vs ColaPri weight sweep (starvation workload of EXP-A, " + N_SEEDS + " seeds, CI95)");
        System.out.println("#  aging offset = 9/f s: a p9 task is ordered as if it had arrived that much later");
        System.out.println("#  " + Metrics.provenance());
        System.out.println("##########################################################################");

        sweep("EXP-A starvation workload", 200, 0.6);
        sweep("longer horizon", 600, 0.6);

        System.out.println("\n#  FIN EXP-G.");
        System.out.flush();
        System.exit(0);
    }

    private static void sweep(final String title, final int n, final double rate) {
        System.out.printf("%n%n=== %s · %d tasks, 90%% p0 + 10%% p9, %.2f/s, %d seeds ===%n", title, n, rate, N_SEEDS);
        final Map<String, Supplier<CloudletScheduler>> schedulers = new LinkedHashMap<>();
        schedulers.put("FCFS", CloudletSchedulerFifo::new);
        schedulers.put("StrictPriority", CloudletSchedulerStrictPriority::new);
        final List<String> agingCurve = new ArrayList<>(), colapriCurve = new ArrayList<>();
        for (final double f : FACTORS) {
            agingCurve.add(aging(f));
            schedulers.put(aging(f), () -> new CloudletSchedulerPriorityAging(f));
        }
        for (final int w : WEIGHTS) {
            colapriCurve.add(colapri(w));
            schedulers.put(colapri(w), () -> new CloudletSchedulerColaPri().setPriorityWeight(0, w).setPriorityWeight(9, 1));
        }
        // per configuration and metric: one value per seed
        final Map<String, List<List<Double>>> v = new LinkedHashMap<>();
        for (final String k : schedulers.keySet()) {
            final List<List<Double>> m = new ArrayList<>();
            for (int i = 0; i < METRIC.length; i++) m.add(new ArrayList<>());
            v.put(k, m);
        }
        for (long seed = 0; seed < N_SEEDS; seed++) {
            final var w = Workload.seeded(seed, n, rate, new int[]{0, 9}, new int[]{9, 1}, BASE_LEN, 0.3, 0.2);
            for (final var e : schedulers.entrySet()) {
                final var f = run(e.getValue(), w);
                final var m = v.get(e.getKey());
                m.get(0).add(Metrics.meanResponseOfClass(f, 0));
                m.get(1).add(Metrics.meanResponseOfClass(f, 9));
                m.get(2).add(Metrics.maxWaitOfClass(f, 9));
                m.get(3).add((double) Metrics.starvationCount(f, 9, LATE));
            }
        }
        System.out.printf("%n%-16s %9s %12s %12s %12s %12s%n", "config", "offset.s", METRIC[0], METRIC[1], METRIC[2], METRIC[3]);
        for (final String k : schedulers.keySet()) {
            final var m = v.get(k);
            System.out.printf("%-16s %9s %12s %12s %12s %12s%n", k, offset(k), ci(m.get(0)), ci(m.get(1)), ci(m.get(2)), ci(m.get(3)));
        }
        System.out.println("\n  matched high-class response: the other policy interpolated between its two neighbouring settings at the");
        System.out.println("  same mean resp.p0; paired per-seed differences, mean±CI95 (negative = the first policy is lower):");
        System.out.printf("  %-30s %-30s %12s %12s %12s %12s%n", "pair", "interpolated between",
            "d." + METRIC[0], "d." + METRIC[1], "d." + METRIC[2], "d." + METRIC[3]);
        for (final String a : agingCurve) matched(v, a, colapriCurve, "ColaPri");
        for (final String c : colapriCurve) matched(v, c, agingCurve, "Aging");
    }

    /** Paired comparison of configuration {@code a} with {@code curve} interpolated at a's mean resp.p0. */
    private static void matched(final Map<String, List<List<Double>>> v, final String a, final List<String> curve, final String label) {
        final String pair = a + " - " + label + "@p0";
        final double p = Metrics.meanCi95(v.get(a).get(0))[0];
        for (int i = 0; i + 1 < curve.size(); i++) {
            final String lo = curve.get(i), hi = curve.get(i + 1);
            final double p1 = Metrics.meanCi95(v.get(lo).get(0))[0], p2 = Metrics.meanCi95(v.get(hi).get(0))[0];
            if (p1 == p2 || (p - p1) * (p - p2) > 0) continue;   // a's response is not between these two settings
            final double t = (p - p1) / (p2 - p1);
            final var row = new StringBuilder(String.format("  %-30s %-30s", pair, String.format("%s/%s t=%.2f", lo, hi, t)));
            for (int k = 0; k < METRIC.length; k++) {
                final List<Double> x = v.get(a).get(k), y1 = v.get(lo).get(k), y2 = v.get(hi).get(k);
                final var d = new ArrayList<Double>(x.size());
                for (int s = 0; s < x.size(); s++) d.add(x.get(s) - ((1 - t) * y1.get(s) + t * y2.get(s)));
                final double[] mc = Metrics.meanCi95(d);
                row.append(String.format(" %12s", String.format("%+.1f±%.1f", mc[0], mc[1])));
            }
            System.out.println(row);
            return;
        }
        System.out.printf("  %-30s outside the %s curve (resp.p0 %.1f s)%n", pair, label, p);
    }

    private static String aging(final double f) {
        return "Aging(" + BigDecimal.valueOf(f).stripTrailingZeros().toPlainString() + ")";
    }

    private static String colapri(final int w) {
        return "ColaPri(" + w + ":1)";
    }

    private static String offset(final String config) {
        if (!config.startsWith("Aging(")) return "-";
        final double f = Double.parseDouble(config.substring(6, config.length() - 1));
        return String.format("%.0f", 9 / f);
    }

    private static String ci(final List<Double> v) {
        final double[] mc = Metrics.meanCi95(v);
        return String.format("%.1f±%.1f", mc[0], mc[1]);
    }

    private static List<Cloudlet> run(final Supplier<CloudletScheduler> factory, final List<Workload.Spec> specs) {
        final var sim = Metrics.newSimulation();
        final var peList = new ArrayList<Pe>(VM_PES);
        for (int i = 0; i < VM_PES; i++) peList.add(new PeSimple(MIPS));
        final Host host = new HostSimple(8192, 100_000, 1_000_000, peList);
        new DatacenterSimple(sim, List.of(host));
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
        sim.start();
        return Metrics.checked(broker.getCloudletFinishedList(), cloudlets.size());
    }
}
