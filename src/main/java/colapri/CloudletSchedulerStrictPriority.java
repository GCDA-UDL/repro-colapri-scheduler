package colapri;

import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;

import java.util.Comparator;

/**
 * Baseline: STRICT priority scheduler (space-shared). Always dispatches the
 * highest-priority waiting cloudlet first (lower priority number = more urgent).
 * Anti-baseline: optimal for high-priority latency but STARVES low-priority classes
 * under sustained high-priority arrivals.
 */
public class CloudletSchedulerStrictPriority extends CloudletSchedulerFifo {
    @Override
    protected double moveNextCloudletsFromWaitingToExecList(final double currentTime) {
        sortCloudletWaitingList(Comparator.comparingInt(cle -> cle.getCloudlet().getPriority()));
        return super.moveNextCloudletsFromWaitingToExecList(currentTime);
    }
}
