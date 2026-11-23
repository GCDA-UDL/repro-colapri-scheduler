package colapri;

import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded synthetic workload generator for the rigorous (multi-seed) evaluation. Each seed produces a
 * genuinely different instance (random class assignment from a weighted distribution, jittered lengths
 * and arrival times) so that mean +/- CI95 over N seeds reflects real workload variability, not a single
 * lucky run. The class MIX (fraction of each priority) is held fixed so the load regime is comparable.
 */
public final class Workload {

    /** One task: arrival time (s), priority class, length (MI). */
    public record Spec(double arrival, int priority, long length) {}

    /**
     * @param seed      RNG seed (one workload instance per seed)
     * @param n         number of tasks
     * @param rate      arrival rate (tasks/s); spacing = 1/rate
     * @param classes   priority classes present (e.g. {0,5,9})
     * @param mix       relative frequency of each class (e.g. {3,2,1} -> 50/33/17%)
     * @param baseLen   base length (MI)
     * @param lenJitter fractional length jitter (0 = constant length; 0.3 = +/-30%)
     * @param arrJitter fractional arrival jitter around the staggered grid
     */
    public static List<Spec> seeded(final long seed, final int n, final double rate,
                                    final int[] classes, final int[] mix,
                                    final long baseLen, final double lenJitter, final double arrJitter) {
        final var rng = new Random(seed);
        final List<Integer> bag = new ArrayList<>();
        for (int i = 0; i < classes.length; i++) for (int k = 0; k < mix[i]; k++) bag.add(classes[i]);
        final double spacing = 1.0 / rate;
        final var specs = new ArrayList<Spec>(n);
        for (int i = 0; i < n; i++) {
            final int pri = bag.get(rng.nextInt(bag.size()));                       // random class from weighted bag
            final long len = Math.max(500, Math.round(baseLen * (1 + lenJitter * (rng.nextDouble() * 2 - 1))));
            final double arr = Math.max(0, i * spacing + arrJitter * spacing * (rng.nextDouble() * 2 - 1));
            specs.add(new Spec(arr, pri, len));
        }
        return specs;
    }
    /**
     * Poisson arrivals (exponential inter-arrival times at {@code rate} tasks/s) with the class drawn from the weighted
     * bag of {@link #seeded}. Lengths: {@code lenJitter >= 0} draws them uniformly within +/-lenJitter around
     * {@code baseLen} (as {@link #seeded}, floor 500 MI); {@code lenJitter < 0} draws them exponentially with mean
     * {@code baseLen} (M/M/c validation of the simulation engine). Since package version 1.1.3 (EXP-H).
     */
    public static List<Spec> poisson(final long seed, final int n, final double rate,
                                     final int[] classes, final int[] mix,
                                     final long baseLen, final double lenJitter) {
        final var rng = new Random(seed);
        final List<Integer> bag = new ArrayList<>();
        for (int i = 0; i < classes.length; i++) for (int k = 0; k < mix[i]; k++) bag.add(classes[i]);
        final var specs = new ArrayList<Spec>(n);
        double t = 0;
        for (int i = 0; i < n; i++) {
            t += -Math.log(1 - rng.nextDouble()) / rate;
            final int pri = bag.get(rng.nextInt(bag.size()));
            final long len = lenJitter >= 0
                ? Math.max(500, Math.round(baseLen * (1 + lenJitter * (rng.nextDouble() * 2 - 1))))
                : Math.max(1, Math.round(-baseLen * Math.log(1 - rng.nextDouble())));
            specs.add(new Spec(t, pri, len));
        }
        return specs;
    }

    /** CPU-bound cloudlet: 100% CPU of one PE, negligible RAM/BW demand. (Using UtilizationModelFull for
     *  RAM and BW as well made CloudSim Plus serialise the VM's PEs under a backlog and drop one cloudlet
     *  per VM -- audit finding C-01, 2026-10-20.) */
    public static CloudletSimple cloudlet(final long length) {
        final var c = new CloudletSimple(length, 1, new UtilizationModelFull());
        c.setUtilizationModelRam(new UtilizationModelDynamic(0.01));
        c.setUtilizationModelBw(new UtilizationModelDynamic(0.01));
        return c;
    }
}
