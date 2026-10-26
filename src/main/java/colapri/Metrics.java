package colapri;

import org.cloudsimplus.cloudlets.Cloudlet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-class and global scheduling metrics for the ColaPri evaluation.
 *
 * For each cloudlet: arrival = getDcArrivalTime(), start = getStartTime(),
 * finish = getFinishTime(). waiting = start - arrival, response = finish - arrival.
 *
 * Weighted Jain fairness uses per-class weight = ColaPri repetition (0:60 .. 9:1),
 * i.e. each class "should" receive service proportional to its weight. A
 * weighted-fair scheduler -> J close to 1; strict priority or unweighted-fair -> low J.
 */
public final class Metrics {

    /** Per-class weight used by the fairness metric. Must match the scheduler's weights for the
     *  weighted-Jain to be meaningful. Default is monotonic; override with setClassWeights(). */
    private static final int[] CLASS_WEIGHT = {6, 5, 4, 3, 3, 3, 2, 2, 2, 1};

    public static void setClassWeights(final Map<Integer, Integer> w) {
        for (int p = 0; p < 10; p++) {
            final Integer x = w.get(p);
            if (x != null && x > 0) CLASS_WEIGHT[p] = x;
        }
    }

    /** mean and 95% confidence interval half-width (t_{n-1,0.975}*std/sqrt(n)) over a sample. */
    public static double[] meanCi95(final List<Double> v) {
        if (v.isEmpty()) return new double[]{0, 0};
        final double m = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (v.size() < 2) return new double[]{m, 0};
        double s2 = 0;
        for (final double x : v) s2 += (x - m) * (x - m);
        final double std = Math.sqrt(s2 / (v.size() - 1));
        return new double[]{m, tq(v.size()) * std / Math.sqrt(v.size())};
    }

    /** Paired comparison over seeds: mean and 95% CI half-width of (a_i - b_i), i.e. the per-seed difference
     *  between two schedulers that saw the same workload. Overlapping marginal intervals say nothing about
     *  the difference; this does. */
    public static double[] pairedCi95(final List<Double> a, final List<Double> b) {
        final int n = Math.min(a.size(), b.size());
        final var d = new ArrayList<Double>(n);
        for (int i = 0; i < n; i++) d.add(a.get(i) - b.get(i));
        return meanCi95(d);
    }

    public static String pairedCi(final List<Double> a, final List<Double> b) {
        final double[] mc = pairedCi95(a, b);
        return String.format("%+.1f±%.1f", mc[0], mc[1]);
    }

    /** Version string printed at the top of every captured log (provenance). */
    public static final String PACKAGE_VERSION = "colapri-cloud-scheduler 1.1.2 · CloudSim Plus 8.5.7";

    /** {@code -Dcolapri.legacyEngine=true}: the behaviour of package 1.1.1, i.e. no PE refill in
     *  {@link CloudletSchedulerFifo} and no counter re-base in {@link CloudletSchedulerColaPri}, to reproduce its logs. */
    public static final boolean LEGACY_ENGINE = Boolean.getBoolean("colapri.legacyEngine");

    /** {@code -Dcolapri.noRebase=true} (implied by the legacy engine): ColaPri keeps the 1.1.1 class counter when the
     *  effective weight changes, so the effect of the re-base on the continuous activation policies can be measured. */
    public static final boolean NO_REBASE = LEGACY_ENGINE || Boolean.getBoolean("colapri.noRebase");

    /** Minimum time between simulation events, in seconds: the CloudSim Plus default (0.1 s) unless the system property
     *  {@code colapri.minDt} is set. Finer values are used only by {@link EngineProbe}: the built-in time-shared
     *  scheduler does not terminate below 0.1 s. */
    private static final String MIN_DT = System.getProperty("colapri.minDt");

    public static double minTimeBetweenEvents() {
        return MIN_DT != null ? Double.parseDouble(MIN_DT) : 0.1;
    }

    /** Every harness creates its simulation here, so the event granularity is the same across experiments. */
    public static org.cloudsimplus.core.CloudSimPlus newSimulation() {
        return MIN_DT == null ? new org.cloudsimplus.core.CloudSimPlus()
                              : new org.cloudsimplus.core.CloudSimPlus(minTimeBetweenEvents());
    }

    /** Provenance line: package version and simulation engine settings. */
    public static String provenance() {
        return PACKAGE_VERSION + " · event granularity " + minTimeBetweenEvents() + " s · "
            + (LEGACY_ENGINE ? "legacy engine of 1.1.1 (no PE refill, no counter re-base)"
                             : "PE refill on completion" + (NO_REBASE ? ", no counter re-base (ablation)" : ""));
    }

    public static int weightOf(final int priority) {
        final int p = priority < 0 ? 0 : (priority > 9 ? 9 : priority);
        return CLASS_WEIGHT[p];
    }

    private static double waiting(final Cloudlet c) { return Math.max(0, c.getStartTime() - c.getDcArrivalTime()); }
    private static double response(final Cloudlet c) { return Math.max(0, c.getFinishTime() - c.getDcArrivalTime()); }

    private static double mean(final List<Double> v) {
        return v.isEmpty() ? 0 : v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double p95(final List<Double> v) {
        if (v.isEmpty()) return 0;
        final var s = new ArrayList<>(v);
        s.sort(Double::compareTo);
        return s.get((int) Math.floor(0.95 * (s.size() - 1)));
    }

    private static double max(final List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    public static Map<Integer, List<Cloudlet>> byPriority(final List<Cloudlet> finished) {
        final Map<Integer, List<Cloudlet>> m = new TreeMap<>();
        for (final var c : finished) m.computeIfAbsent(c.getPriority(), k -> new ArrayList<>()).add(c);
        return m;
    }

    public static double makespan(final List<Cloudlet> finished) {
        double min = Double.MAX_VALUE, maxF = 0;
        for (final var c : finished) {
            min = Math.min(min, c.getDcArrivalTime());
            maxF = Math.max(maxF, c.getFinishTime());
        }
        return finished.isEmpty() ? 0 : maxF - min;
    }

    // ---- per-class accessors (return raw numbers so a multi-seed harness can aggregate with meanCi95) ----

    private static List<Double> respOfClass(final List<Cloudlet> finished, final int c) {
        final var v = new ArrayList<Double>();
        for (final var x : finished) if (x.getPriority() == c) v.add(response(x));
        return v;
    }
    private static List<Double> waitOfClass(final List<Cloudlet> finished, final int c) {
        final var v = new ArrayList<Double>();
        for (final var x : finished) if (x.getPriority() == c) v.add(waiting(x));
        return v;
    }

    public static double meanResponseOfClass(final List<Cloudlet> finished, final int c) { return mean(respOfClass(finished, c)); }
    public static double p95ResponseOfClass(final List<Cloudlet> finished, final int c) { return p95(respOfClass(finished, c)); }
    public static double meanWaitOfClass(final List<Cloudlet> finished, final int c) { return mean(waitOfClass(finished, c)); }
    public static double maxWaitOfClass(final List<Cloudlet> finished, final int c) { return max(waitOfClass(finished, c)); }

    public static int starvationCount(final List<Cloudlet> finished, final int c, final double threshold) {
        int n = 0;
        for (final var x : finished) if (x.getPriority() == c && waiting(x) > threshold) n++;
        return n;
    }

    public static double throughput(final List<Cloudlet> finished) {
        final double mk = makespan(finished);
        return mk <= 0 ? 0 : finished.size() / mk;
    }

    /** Load imbalance across VMs = coefficient of variation (std/mean) of per-VM assigned work (MI). 0 = perfectly balanced. */
    public static double loadImbalanceCV(final List<? extends List<Cloudlet>> perVm) {
        final var loads = new ArrayList<Double>();
        for (final var vm : perVm) {
            double s = 0;
            for (final var c : vm) s += c.getLength();
            loads.add(s);
        }
        final double m = mean(loads);
        if (m <= 0) return 0;
        double s2 = 0;
        for (final double l : loads) s2 += (l - m) * (l - m);
        return Math.sqrt(s2 / loads.size()) / m;
    }

    /** Weighted Jain fairness over per-class (meanResponse * weight). Under ideal
     *  weighted-fairness response is inversely proportional to weight, so the product is
     *  ~constant across classes -> J ~ 1; strict priority or unweighted -> J < 1.
     *  (Throughput-based Jain is useless here: every cloudlet eventually completes.) */
    public static double jainWeighted(final List<Cloudlet> finished) {
        final var groups = byPriority(finished);
        final List<Double> x = new ArrayList<>();
        for (final var e : groups.entrySet()) {
            final double meanResp = e.getValue().stream().mapToDouble(Metrics::response).average().orElse(0);
            x.add(meanResp * weightOf(e.getKey()));
        }
        double sum = 0, sumSq = 0;
        for (final double xi : x) { sum += xi; sumSq += xi * xi; }
        return x.isEmpty() || sumSq == 0 ? 0 : (sum * sum) / (x.size() * sumSq);
    }

    /** Jain fairness index of the per-class service normalised by weight. Service is counted in dispatches
     *  (task starts) over the longest prefix of the dispatch sequence in which every class still has tasks to
     *  dispatch, i.e. up to the first class drain, so that every class could have used its share.
     *  x_c = starts_c / w_c (weighted = true: conformance to the configured shares) or x_c = starts_c
     *  (weighted = false: the equal-share criterion). J = (sum x)^2 / (n sum x^2) in (0,1]; 1 = service exactly
     *  proportional to the criterion. Meaningful for burst workloads where all classes are backlogged from the
     *  start (EXP-A MAIN, EXP-B, EXP-C); with sparse arrivals the prefix can be short. */
    public static double jainNormalisedService(final List<Cloudlet> finished, final boolean weighted) {
        if (finished.isEmpty()) return 0;
        final var byStart = new ArrayList<>(finished);
        byStart.sort(java.util.Comparator.comparingDouble(Cloudlet::getStartTime));
        final Map<Integer, Integer> total = new TreeMap<>(), started = new TreeMap<>();
        for (final var c : finished) total.merge(c.getPriority(), 1, Integer::sum);
        for (final var c : byStart) {
            final int p = c.getPriority();
            started.merge(p, 1, Integer::sum);
            if (started.get(p).equals(total.get(p))) break;   // this class has drained: stop counting
        }
        double sum = 0, sumSq = 0; int n = 0;
        for (final var e : total.entrySet()) {
            final double x = started.getOrDefault(e.getKey(), 0) / (weighted ? (double) weightOf(e.getKey()) : 1.0);
            sum += x; sumSq += x * x; n++;
        }
        return sumSq == 0 ? 0 : (sum * sum) / (n * sumSq);
    }

    /** Prints the per-class table and the global summary for one scheduler run. */
    public static void report(final String name, final List<Cloudlet> finished, final int starvationThreshold) {
        System.out.printf("%n--- %s ---%n", name);
        System.out.printf("  %-6s %-5s %10s %10s %10s %10s %8s%n",
            "class", "n", "resp.mean", "resp.p95", "wait.mean", "wait.MAX", "starv");
        for (final var e : byPriority(finished).entrySet()) {
            final var resp = new ArrayList<Double>();
            final var wait = new ArrayList<Double>();
            int starved = 0;
            for (final var c : e.getValue()) {
                resp.add(response(c));
                final double w = waiting(c);
                wait.add(w);
                if (w > starvationThreshold) starved++;
            }
            System.out.printf("  p%-5d %-5d %10.1f %10.1f %10.1f %10.1f %8d%n",
                e.getKey(), e.getValue().size(), mean(resp), p95(resp), mean(wait), max(wait), starved);
        }
        System.out.printf("  GLOBAL: makespan=%.1f  throughput=%.3f/s  JainWeighted=%.3f%n",
            makespan(finished), finished.size() / Math.max(1e-9, makespan(finished)), jainWeighted(finished));
    }

    /** Compact summary line for cross-scheduler comparison tables. */
    public static String summaryLine(final String name, final List<Cloudlet> finished, final int highPri, final int lowPri) {
        final var g = byPriority(finished);
        final double respHigh = g.containsKey(highPri) ? mean(resps(g.get(highPri))) : 0;
        final double waitLowMax = g.containsKey(lowPri) ? max(waits(g.get(lowPri))) : 0;
        return String.format("%-26s respHigh(p%d)=%7.1f  waitMAX_low(p%d)=%8.1f  Jain=%.3f  makespan=%6.1f",
            name, highPri, respHigh, lowPri, waitLowMax, jainWeighted(finished), makespan(finished));
    }

    private static List<Double> resps(final List<Cloudlet> cs) {
        final var v = new ArrayList<Double>();
        for (final var c : cs) v.add(response(c));
        return v;
    }
    private static List<Double> waits(final List<Cloudlet> cs) {
        final var v = new ArrayList<Double>();
        for (final var c : cs) v.add(waiting(c));
        return v;
    }
    /** Longest run of consecutive dispatches (ordered by start time) that belong to class c. */
    public static int maxConsecutiveStarts(final List<Cloudlet> finished, final int c) {
        final var s = new ArrayList<>(finished);
        s.sort((a, b) -> Double.compare(a.getStartTime(), b.getStartTime()));
        int run = 0, best = 0;
        for (final var x : s) { run = x.getPriority() == c ? run + 1 : 0; best = Math.max(best, run); }
        return best;
    }

    /** Pairs of class-c cloudlets started out of arrival order: an earlier arrival that starts strictly after a later
     *  one of the same class (0 = FIFO within the class). Ties in arrival time are not counted. */
    public static int intraClassInversions(final List<Cloudlet> finished, final int c) {
        final var v = new ArrayList<Cloudlet>();
        for (final var x : finished) if (x.getPriority() == c) v.add(x);
        v.sort((a, b) -> Double.compare(a.getDcArrivalTime(), b.getDcArrivalTime()));
        int inv = 0;
        for (int i = 0; i < v.size(); i++)
            for (int j = i + 1; j < v.size(); j++)
                if (v.get(j).getDcArrivalTime() > v.get(i).getDcArrivalTime() + 1e-9
                        && v.get(j).getStartTime() < v.get(i).getStartTime() - 1e-9) inv++;
        return inv;
    }

    /** Mean response of the class-c cloudlets that arrived in [t0, t1). */
    public static double meanResponseOfClassArrivingIn(final List<Cloudlet> finished, final int c, final double t0, final double t1) {
        final var v = new ArrayList<Double>();
        for (final var x : finished)
            if (x.getPriority() == c && x.getDcArrivalTime() >= t0 && x.getDcArrivalTime() < t1) v.add(response(x));
        return mean(v);
    }

    /** Returns the finished list after checking that no cloudlet was lost (audit finding C-01/M-09). */
    public static List<Cloudlet> checked(final List<Cloudlet> finished, final int submitted) {
        if (finished.size() != submitted)
            System.out.printf("[LOST] finished=%d submitted=%d%n", finished.size(), submitted);
        return finished;
    }

    /** two-sided 97.5% Student t quantile with n-1 degrees of freedom (n = 10/20/30 -> 2.26/2.09/2.05). */
    private static double tq(final int n) {
        return new org.apache.commons.math3.distribution.TDistribution(n - 1).inverseCumulativeProbability(0.975);
    }
}
