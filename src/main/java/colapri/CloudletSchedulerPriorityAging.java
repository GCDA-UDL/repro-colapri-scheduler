package colapri;

import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baseline: PRIORITY + AGING scheduler (space-shared). Dispatches by an effective
 * priority that decreases (becomes more urgent) the longer a cloudlet waits:
 *
 *   effective = priority - agingFactor * waitedTime
 *
 * Classic anti-starvation fix for strict priority: long-waiting low-priority cloudlets
 * eventually overtake newer high-priority ones. Closest "ad-hoc no-starvation priority"
 * competitor — but WITHOUT per-class weights (so no controlled differentiation).
 *
 * waitedTime is tracked from the first time each cloudlet is seen in the waiting list
 * (robust; does not rely on the simulator's arrival getter).
 */
public class CloudletSchedulerPriorityAging extends CloudletSchedulerFifo {
    private final double agingFactor;
    private final Map<Long, Double> firstSeen = new HashMap<>();

    public CloudletSchedulerPriorityAging(final double agingFactor) {
        this.agingFactor = agingFactor;
    }

    @Override
    protected double moveNextCloudletsFromWaitingToExecList(final double currentTime) {
        // Use the absolute simulation clock, not the method parameter (which is not the wall clock and
        // left the aging term ~0 -> the scheduler degenerated to strict priority).
        final double now = getVm().getSimulation().clock();
        final List<CloudletExecution> waiting = getCloudletWaitingList();
        for (final CloudletExecution cle : waiting) {
            firstSeen.putIfAbsent(cle.getId(), now);
        }
        sortCloudletWaitingList(Comparator.comparingDouble(cle -> {
            final double waited = Math.max(0, now - firstSeen.getOrDefault(cle.getId(), now));
            return cle.getCloudlet().getPriority() - agingFactor * waited;   // lower = dispatched first
        }));
        return super.moveNextCloudletsFromWaitingToExecList(currentTime);
    }
}
