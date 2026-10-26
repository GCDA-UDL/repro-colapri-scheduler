package colapri;

import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * CloudletSchedulerDFPO — Dynamic Fair Priority Optimization baseline (Saxena et al. 2016),
 * re-implemented for CloudSim Plus as an ordering CloudletScheduler.
 *
 * <p>DFPO organises tasks into three priority queues (high/middle/low) and drains them with
 * weighted round-robin: w_b dispatch slots per round for bucket b. We realise the same drain
 * order with persistent per-bucket virtual-time tags (the k-th task of bucket b gets the tag
 * {@code k / w_b}), the mechanism already validated for ColaPri — under backlog this reproduces
 * the weighted round-robin drain of three FIFO sub-queues. Unlike ColaPri, DFPO is
 * <b>non-adaptive</b> (always active, no load gate) and offers no per-class weight granularity
 * beyond the three buckets. Default quanta follow Saxena's high:mid:low = 3:2:1.</p>
 */
public class CloudletSchedulerDFPO extends CloudletSchedulerFifo {

    private final int wHigh, wMid, wLow;

    // persistent state (survives across scheduling events)
    private final Map<Long, Double> tag = new HashMap<>();        // cloudlet id -> virtual tag
    private final Map<Integer, Integer> seen = new HashMap<>();   // per-bucket arrival counter

    public CloudletSchedulerDFPO() { this(3, 2, 1); }             // Saxena's quanta

    public CloudletSchedulerDFPO(final int wHigh, final int wMid, final int wLow) {
        this.wHigh = Math.max(1, wHigh);
        this.wMid = Math.max(1, wMid);
        this.wLow = Math.max(1, wLow);
    }

    private static int bucket(final int priority) {
        final int p = priority < 0 ? 0 : (priority > 9 ? 9 : priority);
        return p <= 3 ? 0 : (p <= 6 ? 1 : 2);                     // high / middle / low
    }

    private int quanta(final int b) {
        return b == 0 ? wHigh : (b == 1 ? wMid : wLow);
    }

    @Override
    protected double moveNextCloudletsFromWaitingToExecList(final double currentTime) {
        orderWaitingByDfpo();
        return super.moveNextCloudletsFromWaitingToExecList(currentTime);
    }

    private void orderWaitingByDfpo() {
        final var waiting = getCloudletWaitingList();
        if (waiting.size() < 2) return;
        for (final CloudletExecution cle : waiting) {
            tag.computeIfAbsent(cle.getId(), id -> {
                final int b = bucket(cle.getCloudlet().getPriority());
                final int k = seen.merge(b, 1, Integer::sum) - 1; // 0-based index within bucket
                return k / (double) quanta(b);                    // weighted-RR virtual tag
            });
        }
        sortCloudletWaitingList(
            Comparator.comparingDouble(cle -> tag.getOrDefault(cle.getId(), Double.MAX_VALUE)));
    }
}
