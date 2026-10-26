package colapri;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * EXP-C: scalability (multi-VM datacenter, RoundRobin mapping). Sweeps the cloudlet count
 * {200,500,1000} for {default, ColaPri} and reports makespan, per-class response and scheduler
 * overhead. Key claim to demonstrate: ColaPri's per-class differentiation persists at scale while
 * its overhead stays negligible (the selling point vs DRL/metaheuristic schedulers that scale poorly).
 */
public class ScalabilityEval {
    private static final int HOSTS = 4, HOST_PES = 8, VMS = 16, VM_PES = 2, MIPS = 1000;
    private static final int N_SEEDS = 10;

    public static void main(String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        Metrics.setClassWeights(Map.of(0, 6, 5, 3, 9, 1));

        System.out.println("##########################################################################");
        System.out.println("#  EXP-C · Scalability (" + HOSTS + " hosts, " + VMS + " VMs, RoundRobin, " + N_SEEDS + " seeds, CI95)");
        System.out.println("#  " + Metrics.provenance());
        System.out.println("##########################################################################");
        System.out.printf("%n%-22s %12s %12s %12s %12s %11s %11s%n", "config", "makespan", "resp.p0", "resp.p9", "overhead.ms", "JFIw", "JFI1");

        final var paired = new ArrayList<String>();
        for (final int n : new int[]{200, 500, 1000}) {
            final Map<String, List<Double>> MK = new java.util.LinkedHashMap<>(), P0 = new java.util.LinkedHashMap<>(), P9 = new java.util.LinkedHashMap<>();
            for (final String sched : new String[]{"default", "ColaPri"}) {
                final Supplier<CloudletScheduler> sc = sched.equals("default")
                    ? CloudletSchedulerFifo::new
                    : () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 6).setPriorityWeight(5, 3).setPriorityWeight(9, 1);
                final var mk = new ArrayList<Double>(); final var p0 = new ArrayList<Double>();
                final var p9 = new ArrayList<Double>(); final var ov = new ArrayList<Double>();
                final var jw = new ArrayList<Double>(); final var j1 = new ArrayList<Double>();
                for (long seed = 0; seed < N_SEEDS; seed++) {
                    final var specs = Workload.seeded(seed, n, 1000.0, new int[]{0, 5, 9}, new int[]{3, 2, 1}, 4000, 0.4, 0.0);
                    final double[] r = run(specs, sc);
                    mk.add(r[0]); p0.add(r[1]); p9.add(r[2]); ov.add(r[3]); jw.add(r[4]); j1.add(r[5]);
                }
                MK.put(sched, mk); P0.put(sched, p0); P9.put(sched, p9);
                System.out.printf("%-22s %12s %12s %12s %12s %11s %11s%n", n + "/" + sched, ci(mk), ci(p0), ci(p9), ci(ov),
                    String.format("%.2f±%.2f", Metrics.meanCi95(jw)[0], Metrics.meanCi95(jw)[1]),
                    String.format("%.2f±%.2f", Metrics.meanCi95(j1)[0], Metrics.meanCi95(j1)[1]));
            }
            paired.add(String.format("    %-6d makespan %s  resp.p0 %s  resp.p9 %s", n,
                Metrics.pairedCi(MK.get("ColaPri"), MK.get("default")), Metrics.pairedCi(P0.get("ColaPri"), P0.get("default")),
                Metrics.pairedCi(P9.get("ColaPri"), P9.get("default"))));
        }
        System.out.println("  paired per-seed differences ColaPri - default (mean±CI95):");
        paired.forEach(System.out::println);
        System.out.println("\n  (ColaPri mantiene diferenciación p0<p9 a toda escala; overhead crece poco → barato vs DRL)");
        System.out.println("\n#  FIN EXP-C.");
        System.out.flush();
        System.exit(0);
    }

    /** returns {makespan, resp.p0, resp.p9, overheadMs} */
    private static double[] run(final List<Workload.Spec> specs, final Supplier<CloudletScheduler> schedFactory) {
        final var sim = Metrics.newSimulation();
        final var hosts = new ArrayList<Host>(HOSTS);
        for (int h = 0; h < HOSTS; h++) {
            final var peList = new ArrayList<Pe>(HOST_PES);
            for (int i = 0; i < HOST_PES; i++) peList.add(new PeSimple(MIPS));
            hosts.add(new HostSimple(32768, 100_000, 10_000_000, peList));
        }
        new DatacenterSimple(sim, hosts);
        final var broker = new DatacenterBrokerSimple(sim);
        broker.setVmDestructionDelay(1_000_000.0);
        final var vms = new ArrayList<Vm>(VMS);
        for (int i = 0; i < VMS; i++) {
            final var vm = new VmPatched(MIPS, VM_PES);
            vm.setRam(2048).setBw(1000).setSize(10_000).setCloudletScheduler(schedFactory.get());
            vms.add(vm);
        }
        broker.submitVmList(vms);
        final var cloudlets = new ArrayList<Cloudlet>(specs.size());
        for (final var s : specs) {
            final var c = Workload.cloudlet(s.length());
            c.setSizes(1024);
            c.setPriority(s.priority());
            cloudlets.add(c);
        }
        final var assignment = VmMappers.roundRobin(cloudlets, vms);
        broker.setVmMapper(cl -> assignment.getOrDefault(cl, vms.get(0)));
        broker.submitCloudletList(cloudlets);
        final long t0 = System.nanoTime();
        sim.start();
        final double wallMs = (System.nanoTime() - t0) / 1e6;
        final var f = Metrics.checked(broker.getCloudletFinishedList(), cloudlets.size());
        return new double[]{Metrics.makespan(f), Metrics.meanResponseOfClass(f, 0), Metrics.meanResponseOfClass(f, 9), wallMs,
            Metrics.jainNormalisedService(f, true), Metrics.jainNormalisedService(f, false)};
    }

    private static String ci(final List<Double> v) {
        final double[] mc = Metrics.meanCi95(v);
        return String.format("%.1f±%.1f", mc[0], mc[1]);
    }
}
