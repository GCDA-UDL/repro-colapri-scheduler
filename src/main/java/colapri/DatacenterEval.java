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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * EXP-B: datacenter-scale evaluation (multi-host, multi-VM). Crosses 4 task->VM MAPPING policies
 * (RoundRobin, Min-Min, Max-Min, Demand-Aware 2026) with 2 per-VM cloudlet SCHEDULERS (default
 * space-shared vs ColaPri). Shows that, regardless of how tasks are spread across VMs, swapping the
 * per-VM scheduler to ColaPri re-orders per-class service (faster high class, slower low class),
 * while the mapping policy governs makespan / load balance. N_SEEDS instances, mean +/- CI95.
 */
public class DatacenterEval {
    private static final int HOSTS = 4, HOST_PES = 8, VMS = 16, VM_PES = 2, MIPS = 1000;
    private static final int N_CLOUDLETS = 320, N_SEEDS = 20;
    private static final double STARV_THRESHOLD = 200.0;   // batch -> larger waits; wait>200s = starved (p9)
    private static boolean diagShown = false;               // print VM/cloudlet distribution once

    private record Result(List<Cloudlet> finished, double wallMs) {}

    public static void main(String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        Metrics.setClassWeights(Map.of(0, 6, 5, 3, 9, 1));

        System.out.println("##########################################################################");
        System.out.println("#  EXP-B · ColaPri at datacenter scale (" + HOSTS + " hosts, " + VMS + " VMs x " + VM_PES
            + " PEs, " + N_CLOUDLETS + " cloudlets, " + N_SEEDS + " seeds, CI95)");
        System.out.println("#  mapping {RR,MinMin,MaxMin,DemandAware} x scheduler {default, ColaPri}");
        System.out.println("##########################################################################");

        final Map<String, BiFunction<List<Cloudlet>, List<Vm>, Map<Cloudlet, Vm>>> mappings = new LinkedHashMap<>();
        mappings.put("RoundRobin", VmMappers::roundRobin);
        mappings.put("MinMin", VmMappers::minMin);
        mappings.put("MaxMin", VmMappers::maxMin);
        mappings.put("DemandAware", VmMappers::demandAware);

        final Map<String, Supplier<CloudletScheduler>> scheds = new LinkedHashMap<>();
        scheds.put("default", CloudletSchedulerFifo::new);
        scheds.put("ColaPri", () -> new CloudletSchedulerColaPri()
            .setPriorityWeight(0, 6).setPriorityWeight(5, 3).setPriorityWeight(9, 1));

        // metric samples keyed by "mapping/sched"
        final Map<String, List<Double>> makespan = new LinkedHashMap<>(), loadCV = new LinkedHashMap<>(),
            respHigh = new LinkedHashMap<>(), respLow = new LinkedHashMap<>(), waitMaxLow = new LinkedHashMap<>(),
            starvLow = new LinkedHashMap<>(), overhead = new LinkedHashMap<>(), jfiW = new LinkedHashMap<>(), jfi1 = new LinkedHashMap<>();
        final List<String> order = new ArrayList<>();
        for (final String mp : mappings.keySet())
            for (final String sc : scheds.keySet()) {
                final String k = mp + "/" + sc;
                order.add(k);
                for (final var m : List.of(makespan, loadCV, respHigh, respLow, waitMaxLow, starvLow, overhead, jfiW, jfi1))
                    m.put(k, new ArrayList<>());
            }

        for (long seed = 0; seed < N_SEEDS; seed++) {
            final var specs = Workload.seeded(seed, N_CLOUDLETS, 1000.0, new int[]{0, 5, 9}, new int[]{3, 2, 1}, 4000, 0.4, 0.0);
            for (final var mp : mappings.entrySet())
                for (final var sc : scheds.entrySet()) {
                    final String k = mp.getKey() + "/" + sc.getKey();
                    final var r = run(specs, mp.getValue(), sc.getValue());
                    final var f = r.finished();
                    makespan.get(k).add(Metrics.makespan(f));
                    loadCV.get(k).add(Metrics.loadImbalanceCV(groupByVm(f)));
                    respHigh.get(k).add(Metrics.meanResponseOfClass(f, 0));
                    respLow.get(k).add(Metrics.meanResponseOfClass(f, 9));
                    waitMaxLow.get(k).add(Metrics.maxWaitOfClass(f, 9));
                    starvLow.get(k).add((double) Metrics.starvationCount(f, 9, STARV_THRESHOLD));
                    overhead.get(k).add(r.wallMs());
                    jfiW.get(k).add(Metrics.jainNormalisedService(f, true));
                    jfi1.get(k).add(Metrics.jainNormalisedService(f, false));
                }
        }

        System.out.printf("%n%-22s %13s %9s %13s %13s %13s %10s %11s %11s %11s%n",
            "mapping/scheduler", "makespan", "loadCV", "resp.p0", "resp.p9", "waitMAX.p9", "starv.p9", "overhead.ms", "JFIw", "JFI1");
        for (final String k : order)
            System.out.printf("%-22s %13s %9s %13s %13s %13s %10s %11s %11s %11s%n", k,
                ci(makespan.get(k)), ci(loadCV.get(k)), ci(respHigh.get(k)), ci(respLow.get(k)),
                ci(waitMaxLow.get(k)), ci(starvLow.get(k)), ci(overhead.get(k)), ci2(jfiW.get(k)), ci2(jfi1.get(k)));
        System.out.println("  (JFIw/JFI1 = Jain index of per-VM-pooled class dispatches / weight (or / 1) up to the first class drain)");
        System.out.println("\n  (makespan/loadCV ≈ mapping · resp.p0/p9 + starv ≈ per-VM scheduler:");
        System.out.println("   para CADA mapping, ColaPri baja resp.p0 y sube resp.p9 frente a default; makespan igual)");
        System.out.println("  paired per-seed differences ColaPri - default (mean±CI95):");
        for (final String mp : mappings.keySet())
            System.out.printf("    %-14s makespan %s  resp.p0 %s  resp.p9 %s%n", mp,
                Metrics.pairedCi(makespan.get(mp + "/ColaPri"), makespan.get(mp + "/default")),
                Metrics.pairedCi(respHigh.get(mp + "/ColaPri"), respHigh.get(mp + "/default")),
                Metrics.pairedCi(respLow.get(mp + "/ColaPri"), respLow.get(mp + "/default")));

        System.out.println("\n#  FIN EXP-B.");
        System.out.flush();
        System.exit(0);
    }

    private static List<List<Cloudlet>> groupByVm(final List<Cloudlet> finished) {
        final Map<Long, List<Cloudlet>> g = new TreeMap<>();
        for (final var c : finished) g.computeIfAbsent(c.getVm().getId(), x -> new ArrayList<>()).add(c);
        return new ArrayList<>(g.values());
    }

    private static Result run(final List<Workload.Spec> specs,
                              final BiFunction<List<Cloudlet>, List<Vm>, Map<Cloudlet, Vm>> mapper,
                              final Supplier<CloudletScheduler> schedFactory) {
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
            // EXP-B is a batch scenario (all at t0) so Min-Min/Max-Min completion-time model applies
            cloudlets.add(c);
        }
        // map each cloudlet to the VM chosen by the policy via the broker's mapper hook
        final var assignment = mapper.apply(cloudlets, vms);
        broker.setVmMapper(cl -> assignment.getOrDefault(cl, vms.get(0)));
        broker.submitCloudletList(cloudlets);

        final long t0 = System.nanoTime();
        sim.start();
        final double wallMs = (System.nanoTime() - t0) / 1e6;
        final var finished = Metrics.checked(broker.getCloudletFinishedList(), cloudlets.size());
        if (!diagShown) {
            diagShown = true;
            final Map<Long, Integer> perVm = new TreeMap<>();
            for (final var c : finished) perVm.merge(c.getVm().getId(), 1, Integer::sum);
            System.out.println("[DIAG] VMs created=" + broker.getVmCreatedList().size()
                + " finished=" + finished.size() + " makespan=" + String.format("%.1f", Metrics.makespan(finished))
                + " cloudlets/VM=" + perVm);
        }
        return new Result(finished, wallMs);
    }

    private static String ci2(final List<Double> v) {
        final double[] mc = Metrics.meanCi95(v);
        return String.format("%.2f±%.2f", mc[0], mc[1]);
    }

    private static String ci(final List<Double> v) {
        final double[] mc = Metrics.meanCi95(v);
        return String.format("%.1f±%.1f", mc[0], mc[1]);
    }
}
