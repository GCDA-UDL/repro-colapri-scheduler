package colapri;

import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baseline: WEIGHTED FAIR QUEUING with EQUAL weights = a fair-share scheduler that interleaves
 * priority classes in lockstep, giving every class the same service rate regardless of its priority.
 *
 * This is the "fair but priority-blind" reference (the role CFS would play). CloudSim Plus's built-in
 * CloudletSchedulerCompletelyFair hangs under a sustained staggered backlog, so we use this clean,
 * deterministic fair baseline instead. It shares ColaPri's virtual-time mechanism but with weight=1
 * for all classes, which isolates exactly what ColaPri's per-class weighting adds.
 *
 * Persistent virtual-time tag: the k-th arrival of class c gets tag = k (weight 1). Sorting the
 * waiting list by tag advances all classes equally -> fair, no differentiation, no starvation.
 */
public class CloudletSchedulerWFQ extends CloudletSchedulerFifo {
    private final Map<Long, Double> tag = new HashMap<>();
    private final Map<Integer, Integer> seen = new HashMap<>();

    @Override
    protected double moveNextCloudletsFromWaitingToExecList(final double currentTime) {
        final List<CloudletExecution> waiting = getCloudletWaitingList();
        if (waiting.size() >= 2) {
            for (final CloudletExecution cle : waiting) {
                tag.computeIfAbsent(cle.getId(), id -> {
                    final int c = cle.getCloudlet().getPriority();
                    final int k = seen.merge(c, 1, Integer::sum) - 1;
                    return (double) k;   // equal weight -> classes advance in lockstep
                });
            }
            sortCloudletWaitingList(Comparator.comparingDouble(cle -> tag.getOrDefault(cle.getId(), Double.MAX_VALUE)));
        }
        return super.moveNextCloudletsFromWaitingToExecList(currentTime);
    }
}
