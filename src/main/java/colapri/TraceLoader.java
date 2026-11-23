package colapri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Loads REAL cloud workload traces (the same datasets used in Paper 4) and turns each VM's
 * CPU-utilisation time series into a cloudlet:
 *   - length  ∝ total work (sum of CPU%) — busier VMs => longer tasks
 *   - priority class ∝ CPU variability (sum|ΔCPU|): the most bursty third => class 0
 *     (interactive/latency-sensitive), middle => class 5, most stable third => class 9 (batch).
 *
 * This gives a *semantic* priority derived from the real data: ColaPri should speed up the
 * bursty/interactive workloads without starving the stable/batch ones.
 *
 * Supported formats (CPU% column): PlanetLab (one value/line), Alibaba, Bitbrains, Materna, Azure.
 */
public final class TraceLoader {
    /** Floor of the trace-to-task reduction in MI (default 1000). {@code -Dcolapri.traceFloor=1} removes it, for the
     *  robustness run EXP-E_nofloor (since package version 1.1.3). */
    public static final long FLOOR = Long.getLong("colapri.traceFloor", 1000L);


    public record TaskSpec(double arrival, int priority, long length) {}

    public enum Fmt {
        PLANETLAB(-1, ' ', false, false),   // single integer per line
        ALIBABA(2, ',', true, false),       // ...,cpu_util_percent,...
        BITBRAINS(4, ';', true, false),     // CPU usage [%] at col 4
        MATERNA(4, ';', true, true),        // CPU usage [%] at col 4, decimal comma
        AZURE(3, ',', true, false);         // avg at col 3
        final int cpuCol; final char sep; final boolean header; final boolean decimalComma;
        Fmt(int c, char s, boolean h, boolean d) { cpuCol = c; sep = s; header = h; decimalComma = d; }
    }

    /** One cloudlet per VM file (up to maxVms), arrivals staggered by arrivalSpacing seconds. */
    public static List<TaskSpec> load(final String dir, final Fmt fmt, final int maxVms, final double arrivalSpacing) {
        final File[] files = new File(dir).listFiles(File::isFile);
        if (files == null) return List.of();
        Arrays.sort(files, Comparator.comparing(File::getName));

        final List<double[]> vm = new ArrayList<>();   // {work, variability}
        for (final File f : files) {
            if (vm.size() >= maxVms) break;
            final double[] s = parse(f, fmt);
            if (s.length < 2) continue;
            double work = 0, var = 0;
            for (int i = 0; i < s.length; i++) { work += s[i]; if (i > 0) var += Math.abs(s[i] - s[i - 1]); }
            vm.add(new double[]{work, var});
        }
        // assign priority class by variability tertile (most bursty => class 0)
        final Integer[] order = new Integer[vm.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(vm.get(b)[1], vm.get(a)[1]));
        final int[] pri = new int[vm.size()];
        final int third = Math.max(1, vm.size() / 3);
        for (int rank = 0; rank < order.length; rank++) {
            pri[order[rank]] = rank < third ? 0 : (rank < 2 * third ? 5 : 9);
        }
        final List<TaskSpec> specs = new ArrayList<>(vm.size());
        for (int i = 0; i < vm.size(); i++) {
            final long len = (long) Math.max(FLOOR, Math.min(12000, vm.get(i)[0] * 0.3));
            specs.add(new TaskSpec(i * arrivalSpacing, pri[i], len));
        }
        return specs;
    }

    /** Parse each VM file ONCE into {totalWork, variability}; the rigorous harness then subsamples
     *  this pool per seed (cheaper than re-reading files for every seed). */
    public static List<double[]> loadRaw(final String dir, final Fmt fmt, final int maxVms) {
        final File[] files = new File(dir).listFiles(File::isFile);
        if (files == null) return List.of();
        Arrays.sort(files, Comparator.comparing(File::getName));
        final List<double[]> vm = new ArrayList<>();
        for (final File f : files) {
            if (vm.size() >= maxVms) break;
            final double[] s = parse(f, fmt);
            if (s.length < 2) continue;
            double work = 0, var = 0;
            for (int i = 0; i < s.length; i++) { work += s[i]; if (i > 0) var += Math.abs(s[i] - s[i - 1]); }
            vm.add(new double[]{work, var});
        }
        return vm;
    }

    /** Seeded subsample of a raw pool: pick n series at random, assign priority by variability tertile
     *  WITHIN the sample (bursty third => class 0), length proportional to work, staggered arrivals.
     *  Different seeds => different subset/order => genuine variability for mean +/- CI95. */
    public static List<TaskSpec> sample(final List<double[]> raw, final long seed, final int n, final double arrivalSpacing) {
        final Random rng = new Random(seed);
        final List<double[]> pool = new ArrayList<>(raw);
        Collections.shuffle(pool, rng);
        final List<double[]> sub = new ArrayList<>(pool.subList(0, Math.min(n, pool.size())));
        final Integer[] order = new Integer[sub.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(sub.get(b)[1], sub.get(a)[1]));
        final int[] pri = new int[sub.size()];
        final int third = Math.max(1, sub.size() / 3);
        for (int rank = 0; rank < order.length; rank++) pri[order[rank]] = rank < third ? 0 : (rank < 2 * third ? 5 : 9);
        final List<TaskSpec> specs = new ArrayList<>(sub.size());
        for (int i = 0; i < sub.size(); i++) {
            final long len = (long) Math.max(FLOOR, Math.min(12000, sub.get(i)[0] * 0.3));
            specs.add(new TaskSpec(i * arrivalSpacing, pri[i], len));
        }
        return specs;
    }

    private static double[] parse(final File f, final Fmt fmt) {
        final List<Double> v = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (fmt.header && first) { first = false; continue; }
                first = false;
                line = line.trim();
                if (line.isEmpty()) continue;
                final double cpu;
                if (fmt.cpuCol < 0) {
                    cpu = parseD(line, fmt.decimalComma);
                } else {
                    final String[] p = line.split(Pattern.quote(String.valueOf(fmt.sep)));
                    if (p.length <= fmt.cpuCol) continue;
                    cpu = parseD(p[fmt.cpuCol], fmt.decimalComma);
                }
                if (!Double.isNaN(cpu)) v.add(cpu);
            }
        } catch (IOException e) {
            return new double[0];
        }
        final double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    private static double parseD(String s, final boolean decimalComma) {
        s = s.trim();
        if (decimalComma) s = s.replace(',', '.');
        try { return Double.parseDouble(s); } catch (Exception e) { return Double.NaN; }
    }
}
