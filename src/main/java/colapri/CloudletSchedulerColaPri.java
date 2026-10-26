package colapri;

import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CloudletSchedulerColaPri — ColaPri (Class-Oriented Load-Adaptive PRIority) realized as
 * a CloudSim Plus cloudlet scheduler on top of space-shared PE allocation.
 *
 * <p>Dispatches waiting cloudlets in ColaPri's <b>weighted round-robin</b> order by priority
 * class: a class of weight w gets ~w dispatch slots per round, so higher classes go faster
 * WITHOUT starving lower ones (each class keeps interleaved slots proportional to its weight).</p>
 *
 * <p><b>Persistence matters.</b> A scheduler is invoked many times as PEs free up; if the
 * round-robin position were recomputed from scratch each call, only the top class would ever be
 * picked and the policy would collapse into strict priority. ColaPri's weighted round-robin is
 * therefore realized with <em>persistent virtual-time tags</em> (equivalent to weighted
 * round-robin / WFQ virtual clock): the k-th arriving cloudlet of class c gets the tag
 * {@code k / weight(c)}, assigned once; the waiting list is ordered by tag. Higher-weight classes
 * get smaller tag increments → more, earlier slots, interleaved with the lower ones.</p>
 *
 * <p>The tag is the start tag of start-time fair queueing with a unit cost per task, rounded up to the
 * lattice 1/w of the class; a class returning from idleness is resynchronised to the virtual time
 * (largest tag already dispatched).</p>
 *
 * <p>Weights are configurable ({@code setPriorityWeight}); a class without a configured weight gets the
 * repetition table {60,50,...,1} (every experiment of the paper configures the weights, 6:3:1 for classes
 * 0/5/9). Adaptive activation: with at most {@code adaptiveThreshold} waiting cloudlets the list keeps its
 * arrival order (FIFO). An {@link ActivationPolicy} sets a degree alpha in [0,1] instead: alpha = 0 keeps
 * arrival order, alpha in (0,1] scales the effective weight 1 + alpha*(w-1), which tends to equal share per
 * class (not FIFO) as alpha goes to 0, and the class counter is re-based whenever the effective weight
 * changes.</p>
 */
public class CloudletSchedulerColaPri extends CloudletSchedulerFifo {

    private static final int[] DEFAULT_REP = {60, 50, 40, 30, 15, 10, 5, 3, 2, 1};

    private final Map<Integer, Integer> weights = new HashMap<>();   // class -> weight (override)
    private int adaptiveThreshold = 0;
    private boolean resync = true;                // resynchronise an idle class's counter to the frontier (ablation: false)
    private ActivationPolicy activation = null;   // EXP-F: auto-tuned activation (fuzzy/analytic/learned)
    private int prevBacklog = 0;                  // for growth-aware activation policies

    // persistent ColaPri state (survives across scheduling events)
    private final Map<Long, Double> tag = new HashMap<>();           // cloudlet id -> virtual tag
    private final Map<Integer, Integer> seen = new HashMap<>();      // per-class arrival counter
    private final Map<Integer, Double> lastWeight = new HashMap<>(); // effective weight of the class's last tag
    private double virtualTime = 0.0;                                // frontier: largest tag already dispatched

    // optional per-task audit of Proposition 2 (off by default, enableBoundAudit()): at tagging the class backlog b_c
    // and the bound D(b_c,c); at dispatch the number of other dispatches issued since the task was tagged
    private boolean audit = false;
    private long dispatches = 0;
    private final java.util.Set<Long> started = new java.util.HashSet<>();
    private final Map<Long, double[]> pending = new HashMap<>();               // id -> {class, b_c, D, dispatches, tag time, arrival}
    private final List<double[]> auditRows = new java.util.ArrayList<>();      // {class, b_c, D, others, tag time, start, arrival}

    public CloudletSchedulerColaPri enableBoundAudit() {
        this.audit = true;
        return this;
    }

    /** Audited tasks (tagged at full activation): {class, b_c, D(b_c,c), other dispatches while waiting, tag time, start time,
     *  arrival time}. */
    public List<double[]> boundAudit() {
        return auditRows;
    }

    /** D(b,c) of Proposition 2 over the populated classes: the configured ones (or, without configuration, the classes
     *  seen so far), plus c. A class that has not arrived yet still counts: its future tags can fall inside [V, tau]. */
    private double bound(final int b, final int c) {
        final var classes = new java.util.TreeSet<>(weights.isEmpty() ? seen.keySet() : weights.keySet());
        classes.add(c);
        double d = -1;
        for (final int c2 : classes) d += Math.floor((b + 1) * (double) weight(c2) / weight(c)) + 1;
        return d;
    }

    public CloudletSchedulerColaPri setPriorityWeight(final int priority, final int weight) {
        weights.put(clamp(priority), weight);
        return this;
    }

    public CloudletSchedulerColaPri setAdaptiveThreshold(final int threshold) {
        this.adaptiveThreshold = Math.max(0, threshold);
        return this;
    }

    /** Ablation switch: false restores the original rule without counter resynchronisation. */
    public CloudletSchedulerColaPri setResync(final boolean resync) {
        this.resync = resync;
        return this;
    }

    /** EXP-F: replace the fixed threshold with an automatic activation policy returning an
     *  activation degree alpha in [0,1] (0 = keep arrival order, 1 = full weighted-RR). In between the
     *  effective per-class weight is w_eff = 1 + alpha*(w-1), i.e. equal share per class as alpha -> 0+. */
    public CloudletSchedulerColaPri setActivationPolicy(final ActivationPolicy policy) {
        this.activation = policy;
        return this;
    }

    private int weight(final int priority) {
        final int c = clamp(priority);
        final Integer w = weights.get(c);
        return (w != null && w > 0) ? w : DEFAULT_REP[c];
    }

    private int clamp(final int p) {
        return p < 0 ? 0 : (p > 9 ? 9 : p);
    }

    @Override
    protected double moveNextCloudletsFromWaitingToExecList(final double currentTime) {
        orderWaitingByColaPri(currentTime);
        final double next = super.moveNextCloudletsFromWaitingToExecList(currentTime);
        // advance the frontier to the largest tag now in service (SFQ-style virtual time)
        for (final CloudletExecution cle : getCloudletExecList()) {
            final Double t = tag.get(cle.getId());
            if (t != null && t > virtualTime) virtualTime = t;
        }
        if (audit) {   // count dispatches in the order they were issued: untagged first, then by tag
            final var fresh = new java.util.ArrayList<CloudletExecution>();
            for (final CloudletExecution cle : getCloudletExecList()) if (started.add(cle.getId())) fresh.add(cle);
            fresh.sort(Comparator.comparingDouble(cle -> tag.getOrDefault(cle.getId(), -1.0)));
            for (final CloudletExecution cle : fresh) {
                final double[] p = pending.remove(cle.getId());
                if (p != null) auditRows.add(new double[]{p[0], p[1], p[2], dispatches - p[3], p[4], currentTime, p[5]});
                dispatches++;
            }
        }
        return next;
    }

    private void orderWaitingByColaPri(final double currentTime) {
        final List<CloudletExecution> waiting = getCloudletWaitingList();
        if (waiting.size() < 2) return;
        final int backlog = waiting.size();

        // activation degree alpha in [0,1]: 0 = stay FIFO, 1 = full weighted-RR
        final double a;
        if (activation != null) {
            a = activation.alpha(backlog, prevBacklog);
            prevBacklog = backlog;
        } else {
            prevBacklog = backlog;
            if (backlog <= adaptiveThreshold) return;   // fixed-threshold mode (legacy)
            a = 1.0;
        }
        if (a <= 0.0) return;
        final double alpha = a;

        // assign a persistent weighted-round-robin tag to any cloudlet not seen yet;
        // effective weight blends toward neutral (1) as alpha shrinks.
        // Resynchronisation: a class returning from idleness may not mint tags below the frontier
        // (the largest tag already dispatched), so its counter jumps to ceil(frontier * w) first.
        final Map<Integer, Integer> taggedWaiting = new HashMap<>();   // audit: tagged tasks of each class still waiting
        if (audit)
            for (final CloudletExecution cle : waiting)
                if (tag.containsKey(cle.getId())) taggedWaiting.merge(clamp(cle.getCloudlet().getPriority()), 1, Integer::sum);
        for (final CloudletExecution cle : waiting) {
            tag.computeIfAbsent(cle.getId(), id -> {
                final int c = clamp(cle.getCloudlet().getPriority());
                final double wEff = 1.0 + alpha * (weight(c) - 1.0); // alpha=1 -> w, alpha=0 -> 1
                int next = seen.getOrDefault(c, 0);
                final Double wPrev = lastWeight.get(c);
                // Re-base on a change of effective weight: the counter indexes the lattice 1/wEff, so after a change
                // it restarts at the virtual finish of the class's last tag (next/wPrev) on the new lattice. Without
                // this a lower weight would push the class far ahead of the frontier and a higher one would let new
                // tasks overtake earlier tasks of the same class. With a constant weight nothing changes.
                if (!Metrics.NO_REBASE && wPrev != null && wPrev != wEff) next = (int) Math.ceil(next / wPrev * wEff - 1e-9);
                final int kMin = resync ? (int) Math.ceil(virtualTime * wEff - 1e-9) : 0;
                final int k = Math.max(next, kMin);                  // 0-based index within class
                seen.put(c, k + 1);
                lastWeight.put(c, wEff);
                if (audit && alpha == 1.0) {
                    final int b = taggedWaiting.getOrDefault(c, 0);
                    pending.put(id, new double[]{c, b, bound(b, c), dispatches, currentTime, cle.getCloudlet().getDcArrivalTime()});
                    taggedWaiting.merge(c, 1, Integer::sum);
                }
                return k / wEff;                                     // ColaPri weighted-RR virtual tag
            });
        }
        sortCloudletWaitingList(
            Comparator.comparingDouble(cle -> tag.getOrDefault(cle.getId(), Double.MAX_VALUE)));
    }
}
