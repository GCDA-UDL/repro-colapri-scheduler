package colapri;

import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;

import java.util.Comparator;

/**
 * Baseline: SHORTEST JOB FIRST (space-shared). Dispatches the shortest waiting cloudlet first.
 * Classic latency-optimal baseline for mean response time, but ignores priority/fairness and
 * starves long jobs. Standard comparator in CloudSim scheduling studies (FCFS/SJF/RR family).
 */
public class CloudletSchedulerSJF extends CloudletSchedulerFifo {
    @Override
    protected double moveNextCloudletsFromWaitingToExecList(final double currentTime) {
        sortCloudletWaitingList(Comparator.comparingLong(cle -> cle.getCloudlet().getLength()));
        return super.moveNextCloudletsFromWaitingToExecList(currentTime);
    }
}
