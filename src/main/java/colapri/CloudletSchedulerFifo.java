package colapri;

import org.cloudsimplus.schedulers.MipsShare;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;

/**
 * FIFO space-shared scheduler (the FCFS baseline and the "default" scheduler of EXP-B/C) and the base class of
 * every space-shared scheduler in this package, with a fix for the dispatch order of CloudSim Plus 8.5.7.
 *
 * <p>{@code CloudletSchedulerAbstract.updateProcessing()} moves waiting cloudlets into execution <em>before</em> it
 * removes the cloudlets that have just finished. A PE freed by a completion therefore stays idle until the next
 * processing update (0.11 s on average with the default 0.1 s event granularity, about 3% of a 4 s task), and a
 * cloudlet that arrives in between finds the PE free and starts at once, ahead of the waiting queue: about 5% of the
 * tasks of the sustained-load scenario bypassed the queue under every scheduler (audit finding F-34, 2026-10-24;
 * {@link EngineProbe}). This class refills the freed PEs from the waiting list in the same update, through the
 * subclass's own ordering, so every dispatch follows the scheduler's queue order and no PE idles while tasks wait.</p>
 *
 * <p>{@code -Dcolapri.legacyEngine=true} disables the refill to reproduce the logs of package version 1.1.1.</p>
 */
public class CloudletSchedulerFifo extends CloudletSchedulerSpaceShared {

    @Override
    public double updateProcessing(final double currentTime, final MipsShare mipsShare) {
        final double next = super.updateProcessing(currentTime, mipsShare);
        if (Metrics.LEGACY_ENGINE || getCloudletWaitingList().isEmpty() || getFreePes() == 0) return next;
        return Math.min(next, moveNextCloudletsFromWaitingToExecList(currentTime));
    }
}
