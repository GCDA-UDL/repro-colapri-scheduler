package colapri;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Probe for the dispatch-order defect of CloudSim Plus 8.5.7 described in {@link CloudletSchedulerFifo} (finding F-34):
 * on the sustained-load workload of EXP-A it counts the tasks that start at once on arrival while an earlier task is
 * still waiting (queue bypass) and the idle time of a PE between a completion and the next dispatch, and prints the
 * headline metrics. Run it with the default engine and with {@code -Dcolapri.legacyEngine=true} to compare.
 */
public class EngineProbe {
    private static final int MIPS = 1000, VM_PES = 2, N_SEEDS = 30;

    public static void main(final String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        System.out.println("#  EngineProbe · queue bypass and dispatch gap on the sustained-load workload (200 tasks, 0.6/s, 90% class 0)");
        System.out.println("#  " + Metrics.provenance());
        final Map<String, Supplier<CloudletScheduler>> m = new LinkedHashMap<>();
        m.put("FCFS", CloudletSchedulerFifo::new);
        m.put("StrictPriority", CloudletSchedulerStrictPriority::new);
        m.put("ColaPri(6:1)", () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 6).setPriorityWeight(9, 1));
        for (final var e : m.entrySet()) {
            final var waitMax = new ArrayList<Double>(); final var resp0 = new ArrayList<Double>();
            int tasks = 0, bypass = 0; double gapSum = 0; int gapN = 0;
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final var f = run(e.getValue(), Workload.seeded(seed, 200, 0.6, new int[]{0, 9}, new int[]{9, 1}, 4000, 0.3, 0.2));
                tasks += f.size();
                waitMax.add(Metrics.maxWaitOfClass(f, 9));
                resp0.add(Metrics.meanResponseOfClass(f, 0));
                for (final var x : f) {
                    if (x.getStartTime() - x.getDcArrivalTime() > 1e-9) continue;     // waited: not a bypass
                    for (final var y : f)
                        if (y.getDcArrivalTime() < x.getDcArrivalTime() - 1e-9 && y.getStartTime() > x.getStartTime() + 1e-9) { bypass++; break; }
                }
                // PE idle time after a completion while tasks were waiting: completion -> next start of a task that arrived before it
                for (final var c : f) {
                    final double t = c.getFinishTime();
                    double next = Double.MAX_VALUE;
                    for (final var x : f) if (x.getDcArrivalTime() < t - 1e-9 && x.getStartTime() >= t - 1e-9) next = Math.min(next, x.getStartTime());
                    if (next < Double.MAX_VALUE && next - t < 1.0) { gapSum += next - t; gapN++; }   // gaps >= 1 s are bypass effects
                }
            }
            final double[] a = Metrics.meanCi95(waitMax), b = Metrics.meanCi95(resp0);
            System.out.printf("%-16s tasks=%d  queue bypass=%d (%.2f%%)  mean completion-to-dispatch gap=%.3f s  waitMAX.p9=%.1f±%.1f  resp.p0=%.1f±%.1f%n",
                e.getKey(), tasks, bypass, 100.0 * bypass / tasks, gapSum / Math.max(1, gapN), a[0], a[1], b[0], b[1]);
        }
        System.exit(0);
    }

    private static List<Cloudlet> run(final Supplier<CloudletScheduler> s, final List<Workload.Spec> specs) {
        final var sim = Metrics.newSimulation();
        final var pes = new ArrayList<Pe>(VM_PES);
        for (int i = 0; i < VM_PES; i++) pes.add(new PeSimple(MIPS));
        new DatacenterSimple(sim, List.of(new HostSimple(8192, 100_000, 1_000_000, pes)));
        final var broker = new DatacenterBrokerSimple(sim);
        broker.setVmDestructionDelay(100_000.0);
        final var vm = new VmPatched(MIPS, VM_PES);
        vm.setRam(4096).setBw(10_000).setSize(10_000).setCloudletScheduler(s.get());
        broker.submitVmList(List.of(vm));
        final var cls = new ArrayList<Cloudlet>(specs.size());
        for (final var x : specs) {
            final var c = Workload.cloudlet(x.length());
            c.setSizes(1024);
            c.setPriority(x.priority());
            c.setSubmissionDelay(x.arrival());
            cls.add(c);
        }
        broker.submitCloudletList(cls);
        sim.start();
        return Metrics.checked(new ArrayList<Cloudlet>(broker.getCloudletFinishedList()), cls.size());
    }
}
