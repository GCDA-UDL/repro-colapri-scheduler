package colapri;

/**
 * EXP-F: how ColaPri decides its activation degree alpha in [0,1] from the live load
 * (0 = keep arrival order, 1 = full weighted round-robin; in between the effective weight 1 + alpha*(w-1),
 * which tends to equal share per class as alpha goes to 0). This turns the hand-set threshold into an
 * automatic setting and lets us compare design approaches under the SAME mechanism:
 *
 *   - {@link #alwaysOn()}      : alpha=1 always (no adaptation).
 *   - {@link #fixed(int)}      : step at a backlog threshold (the original ColaPri behaviour).
 *   - {@link #analytic(int,int)}: a closed-form ramp on backlog (cheap, principled equation).
 *   - {@link #fuzzy(int,int)}  : a small zero-order Sugeno fuzzy controller on (backlog level, backlog growth).
 *   - {@link #learned(double[],int)}: a lookup table over backlog buckets, filled offline by
 *                                     coordinate-descent optimisation on training seeds (ML baseline).
 *
 * All are O(1) per dispatch and deterministic at inference -> they keep ColaPri's negligible-overhead
 * advantage over DRL/metaheuristic schedulers.
 */
@FunctionalInterface
public interface ActivationPolicy {

    /** @param backlog current waiting-queue length; @param prevBacklog length at the previous event. */
    double alpha(int backlog, int prevBacklog);

    static double clamp01(final double x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }

    static ActivationPolicy alwaysOn() { return (l, p) -> 1.0; }

    static ActivationPolicy fixed(final int theta) { return (l, p) -> l > theta ? 1.0 : 0.0; }

    /** Closed-form: linear ramp of alpha from 0 (backlog<=lo) to 1 (backlog>=hi). */
    static ActivationPolicy analytic(final int lo, final int hi) {
        final double span = Math.max(1, hi - lo);
        return (l, p) -> clamp01((l - lo) / span);
    }

    /**
     * Zero-order Sugeno fuzzy controller (singleton consequents, weighted-average defuzzification).
     * Inputs: backlog level (membership LOW/HIGH via a ramp over [lo,hi]) and backlog growth sign. Rules:
     *   R1: backlog HIGH                 -> alpha = 1.0
     *   R2: backlog LOW  and growing     -> alpha = 0.5   (pre-emptive ramp-up)
     *   R3: backlog LOW  and not growing -> alpha = 0.0   (stay cheap)
     */
    static ActivationPolicy fuzzy(final int lo, final int hi) {
        final double span = Math.max(1, hi - lo);
        return (l, p) -> {
            final double muHigh = clamp01((l - lo) / span);
            final double muLow = 1.0 - muHigh;
            final double growing = (l - p) > 0 ? 1.0 : 0.0;
            final double notGrowing = 1.0 - growing;
            final double wHigh = muHigh;                       // R1
            final double wMed = Math.min(muLow, growing);      // R2
            final double wLow = Math.min(muLow, notGrowing);   // R3
            final double denom = wHigh + wMed + wLow;
            return denom <= 0 ? 0.0 : (1.0 * wHigh + 0.5 * wMed + 0.0 * wLow) / denom;
        };
    }

    /** Lookup table over backlog buckets (bucket = backlog/bucketWidth, clamped). Table is produced
     *  offline by {@code ActivationEval} via coordinate descent on training seeds. */
    static ActivationPolicy learned(final double[] alphaByBucket, final int bucketWidth) {
        final int n = alphaByBucket.length;
        final double[] table = alphaByBucket.clone();
        final int w = Math.max(1, bucketWidth);
        return (l, p) -> table[Math.min(l / w, n - 1)];
    }
}
