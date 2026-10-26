package colapri;

import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baseline: classical WEIGHTED fair queueing with the SAME per-class weights as ColaPri (6:3:1),
 * realized as Start-time Fair Queueing (SFQ; Goyal, Vin and Cheng, IEEE/ACM ToN 1997).
 *
 * <p>This is the "novelty control": a textbook weighted fair queue, always on, whose virtual-time
 * tags advance by SERVICE DEMAND (length / weight) instead of ColaPri's per-class arrival index.
 * The first time a cloudlet of class c is observed in the waiting list it receives</p>
 * <pre>
 *   S = max(v, F_c)          start tag   (v = start tag of the cloudlet(s) currently in service)
 *   F = S + length / w_c     finish tag; F_c = F
 * </pre>
 * <p>and the waiting list is ordered by S (ties by arrival order). Unlike ColaPri it (i) needs the
 * task length up front, (ii) re-anchors the virtual clock to the task in service, so a class
 * returning from idleness gets no transient credit, and (iii) has no load-adaptive gate.</p>
 *
 * <p>Weights are configurable ({@code setPriorityWeight}); the default per class follows the same
 * repetition table as ColaPri so that both schedulers can be compared with identical weights.</p>
 */
public class CloudletSchedulerSFQ extends CloudletSchedulerFifo {

    private static final int[] DEFAULT_REP = {60, 50, 40, 30, 15, 10, 5, 3, 2, 1};

    private final Map<Integer, Integer> weights = new HashMap<>();   // class -> weight (override)

    // persistent SFQ state (survives across scheduling events)
    private final Map<Long, Double> startTag = new HashMap<>();      // cloudlet id -> S
    private final Map<Long, Long> arrivalSeq = new HashMap<>();      // cloudlet id -> tie-breaker
    private final Map<Integer, Double> classFinish = new HashMap<>(); // class -> last F assigned
    private double virtualTime = 0.0;                                // v: start tag of task in service
    private long nextSeq = 0;

    public CloudletSchedulerSFQ setPriorityWeight(final int priority, final int weight) {
        weights.put(clamp(priority), weight);
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
        final List<CloudletExecution> waiting = getCloudletWaitingList();
        if (!waiting.isEmpty()) {
            // tag every newly observed cloudlet once: S = max(v, F_c), F = S + length / w_c
            for (final CloudletExecution cle : waiting) {
                if (startTag.containsKey(cle.getId())) continue;
                final int c = clamp(cle.getCloudlet().getPriority());
                final double s = Math.max(virtualTime, classFinish.getOrDefault(c, 0.0));
                final double f = s + (double) cle.getCloudlet().getLength() / weight(c);
                classFinish.put(c, f);
                startTag.put(cle.getId(), s);
                arrivalSeq.put(cle.getId(), nextSeq++);
            }
            if (waiting.size() >= 2) {
                sortCloudletWaitingList(Comparator
                    .comparingDouble((CloudletExecution cle) -> startTag.getOrDefault(cle.getId(), Double.MAX_VALUE))
                    .thenComparingLong(cle -> arrivalSeq.getOrDefault(cle.getId(), Long.MAX_VALUE)));
            }
        }
        final double next = super.moveNextCloudletsFromWaitingToExecList(currentTime);
        // SFQ virtual time = start tag of the cloudlet(s) in service (max over the PEs in use)
        for (final CloudletExecution cle : getCloudletExecList()) {
            final Double s = startTag.get(cle.getId());
            if (s != null && s > virtualTime) virtualTime = s;
        }
        return next;
    }
}
