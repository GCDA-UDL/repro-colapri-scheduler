package colapri;

import org.cloudsimplus.cloudlets.CloudletExecution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudletSchedulerDFPO — the ordering core of Dynamic Fair Priority Optimization (Saxena et al. 2016),
 * re-implemented for CloudSim Plus as a weighted round-robin over three FIFO queues.
 *
 * <p>Tasks join one of three FIFO queues by priority (high = 0-3, middle = 4-6, low = 7-9). Each round serves up to
 * w_b tasks from queue b in turn, with quanta high:mid:low = 3:2:1 as in Saxena et al. An empty queue is skipped
 * when its turn comes and keeps no credit, and the position in the round (whose turn it is and how many of its
 * dispatches are left) persists across dispatch events, so the drain order is that of a weighted round-robin over
 * FIFO queues. Like the other space-shared schedulers of the package it extends {@link CloudletSchedulerFifo}, which
 * refills freed PEs in the same processing update. The deadline grouping and the VM selection of the original are
 * not modelled.</p>
 *
 * <p>Since package version 1.1.3. Versions up to 1.1.2 realised the drain with per-bucket arrival-index tags
 * ({@code k / w_b}) without resynchronisation: a queue that stayed empty accumulated credit and its next tasks were
 * served ahead of the others, so under sustained load the sparse low queue was served first (audit of 2026-11-23).</p>
 */
public class CloudletSchedulerDFPO extends CloudletSchedulerFifo {

    private final int[] quanta;
    private final Map<Long, Long> seq = new HashMap<>();   // first-seen order of each task (FIFO within a queue)
    private long nextSeq = 0;
    private int turn = 0;                                  // queue whose turn it is
    private int left;                                      // dispatches left for that queue in the current round

    public CloudletSchedulerDFPO() { this(3, 2, 1); }      // Saxena's quanta

    public CloudletSchedulerDFPO(final int wHigh, final int wMid, final int wLow) {
        this.quanta = new int[]{Math.max(1, wHigh), Math.max(1, wMid), Math.max(1, wLow)};
        this.left = quanta[0];
    }

    static int bucket(final int priority) {
        final int p = priority < 0 ? 0 : (priority > 9 ? 9 : priority);
        return p <= 3 ? 0 : (p <= 6 ? 1 : 2);              // high / middle / low
    }

    @Override
    protected double moveNextCloudletsFromWaitingToExecList(final double currentTime) {
        final List<CloudletExecution> waiting = getCloudletWaitingList();
        if (waiting.isEmpty()) return super.moveNextCloudletsFromWaitingToExecList(currentTime);

        for (final CloudletExecution cle : waiting) seq.computeIfAbsent(cle.getId(), id -> nextSeq++);
        final List<List<CloudletExecution>> queues = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (final CloudletExecution cle : waiting) queues.get(bucket(cle.getCloudlet().getPriority())).add(cle);
        for (final var q : queues) q.sort(Comparator.comparingLong(cle -> seq.get(cle.getId())));

        // project the weighted round-robin order from the current position in the round; turnAfter[k] / leftAfter[k]
        // is the position after the first k dispatches of that order
        final int n = waiting.size();
        final List<CloudletExecution> order = new ArrayList<>(n);
        final int[] turnAfter = new int[n + 1], leftAfter = new int[n + 1];
        final int[] head = new int[3];
        int b = turn, l = left;
        turnAfter[0] = b; leftAfter[0] = l;
        while (order.size() < n) {
            if (l > 0 && head[b] < queues.get(b).size()) {
                order.add(queues.get(b).get(head[b]++));
                l--;
                turnAfter[order.size()] = b; leftAfter[order.size()] = l;
            } else {                                       // quanta used up or queue empty: next queue, fresh quanta
                b = (b + 1) % 3;
                l = quanta[b];
            }
        }
        final Map<Long, Integer> pos = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) pos.put(order.get(i).getId(), i);
        sortCloudletWaitingList(Comparator.comparingInt(cle -> pos.get(cle.getId())));

        final Set<Long> before = new HashSet<>(pos.keySet());
        final double next = super.moveNextCloudletsFromWaitingToExecList(currentTime);
        for (final CloudletExecution cle : getCloudletWaitingList()) before.remove(cle.getId());
        final int dispatched = before.size();              // the first `dispatched` tasks of the projected order
        turn = turnAfter[dispatched];
        left = leftAfter[dispatched];
        return next;
    }
}
