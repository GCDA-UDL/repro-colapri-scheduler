package colapri;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.vms.Vm;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task->VM mapping policies (the broker-level "which VM runs this cloudlet" decision), reimplemented
 * as baselines for the multi-VM datacenter experiment (EXP-B). These are orthogonal to the per-VM
 * cloudlet scheduler: the experiment crosses each mapping with {default scheduler, ColaPri} to show
 * ColaPri improves per-class metrics regardless of how tasks are spread across VMs.
 *
 *   - roundRobin   : cloudlet i -> VM (i mod m). Trivial spread baseline.
 *   - minMin       : classic Min-Min (assign the task with the smallest earliest-completion first).
 *   - maxMin       : classic Max-Min (assign the task with the largest earliest-completion first).
 *   - mct          : Minimum Completion Time (Braun et al. 2001) — bind each task, in submission order, to the VM
 *                    on which it would finish first given the VMs' projected load. (Labelled "DemandAware" up to
 *                    package version 1.1.2; the Demand-Aware VM selection of Sultan (2026) is a different rule, a
 *                    weighted CPU/memory/bandwidth demand-to-capacity score, and is not implemented here.)
 *
 * Completion-time model: rate(v) = mips(v) * pes(v); a VM's ready time advances by length/rate.
 * Heuristic-grade (ignores intra-VM queueing detail), which is standard for these mappers.
 */
public final class VmMappers {

    private static double rate(final Vm v) { return v.getMips() * v.getPesNumber(); }

    public static Map<Cloudlet, Vm> roundRobin(final List<Cloudlet> cloudlets, final List<Vm> vms) {
        final Map<Cloudlet, Vm> a = new IdentityHashMap<>();
        for (int i = 0; i < cloudlets.size(); i++) a.put(cloudlets.get(i), vms.get(i % vms.size()));
        return a;
    }

    /** Minimum Completion Time (Braun et al. 2001): greedy least projected completion, tasks in submission order. */
    public static Map<Cloudlet, Vm> mct(final List<Cloudlet> cloudlets, final List<Vm> vms) {
        final Map<Cloudlet, Vm> a = new IdentityHashMap<>();
        final double[] ready = new double[vms.size()];
        for (final Cloudlet c : cloudlets) {
            int best = 0; double bestCt = Double.MAX_VALUE;
            for (int v = 0; v < vms.size(); v++) {
                final double ct = ready[v] + c.getLength() / rate(vms.get(v));
                if (ct < bestCt) { bestCt = ct; best = v; }
            }
            a.put(c, vms.get(best));
            ready[best] = bestCt;
        }
        return a;
    }

    public static Map<Cloudlet, Vm> minMin(final List<Cloudlet> cloudlets, final List<Vm> vms) { return minMaxMin(cloudlets, vms, true); }
    public static Map<Cloudlet, Vm> maxMin(final List<Cloudlet> cloudlets, final List<Vm> vms) { return minMaxMin(cloudlets, vms, false); }

    /** Shared Min-Min / Max-Min core. minFirst=true -> Min-Min, false -> Max-Min.
     *  Deterministic (v1.1.1): candidates are scanned in submission order and ties in the completion time
     *  (frequent, because task lengths are integers) are broken by the lowest task index and the lowest VM
     *  index. v1.1.0 iterated an identity-hash set, whose order changes between JVM runs, so the Min-Min and
     *  Max-Min rows of EXP-B varied by up to 0.3 s between executions. */
    private static Map<Cloudlet, Vm> minMaxMin(final List<Cloudlet> cloudlets, final List<Vm> vms, final boolean minFirst) {
        final Map<Cloudlet, Vm> a = new IdentityHashMap<>();
        final double[] ready = new double[vms.size()];
        final boolean[] done = new boolean[cloudlets.size()];
        for (int left = cloudlets.size(); left > 0; left--) {
            int chosenTask = -1; int chosenVm = -1; double chosenCt = minFirst ? Double.MAX_VALUE : -1;
            for (int i = 0; i < cloudlets.size(); i++) {
                if (done[i]) continue;
                final Cloudlet t = cloudlets.get(i);
                // earliest completion time of this task over all VMs (ties -> lowest VM index)
                int bestV = 0; double bestCt = Double.MAX_VALUE;
                for (int v = 0; v < vms.size(); v++) {
                    final double ct = ready[v] + t.getLength() / rate(vms.get(v));
                    if (ct < bestCt - 1e-9) { bestCt = ct; bestV = v; }
                }
                // Min-Min keeps the task whose best-CT is smallest; Max-Min the largest (ties -> lowest task index)
                if ((minFirst && bestCt < chosenCt - 1e-9) || (!minFirst && bestCt > chosenCt + 1e-9)) {
                    chosenCt = bestCt; chosenTask = i; chosenVm = bestV;
                }
            }
            a.put(cloudlets.get(chosenTask), vms.get(chosenVm));
            ready[chosenVm] = chosenCt;
            done[chosenTask] = true;
        }
        return a;
    }

    private VmMappers() {}
}
