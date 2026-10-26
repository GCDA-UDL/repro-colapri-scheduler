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
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * EXP-D: parameter sensitivity (30 seeds, CI95). Turns the "moderate differentiation" of EXP-A into
 * tunable curves a reviewer can see:
 *   D1 weight ratio  -> resp.p9/resp.p0 grows with the configured weight (differentiation is a knob)
 *   D2 adaptive thr. -> differentiation activates only above the load threshold
 *   D3 offered load  -> differentiation grows with backlog
 *   D4 VM PEs        -> behavior across capacity (more PEs -> less queueing -> less differentiation)
 */
public class SensitivityEval {
    private static final int MIPS = 1000;
    private static final long BASE_LEN = 4000;
    private static final int N_SEEDS = 30;

    public static void main(String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        Metrics.setClassWeights(Map.of(0, 6, 9, 1));

        System.out.println("##########################################################################");
        System.out.println("#  EXP-D · ColaPri parameter sensitivity (" + N_SEEDS + " seeds, CI95)");
        System.out.println("#  " + Metrics.provenance());
        System.out.println("##########################################################################");

        d1Weights();
        d2Threshold();
        d3Load();
        d4Pes();

        System.out.println("\n#  FIN EXP-D.");
        System.out.flush();
        System.exit(0);
    }

    // D1: weight ratio w:1 (classes 0 and 9) -> differentiation ratio resp.p9/resp.p0
    private static void d1Weights() {
        System.out.println("\n=== D1 · RATIO DE PESOS (clase 0 peso w, clase 9 peso 1); 60 tareas 5/s ===");
        System.out.printf("%-8s %14s %14s %12s%n", "w(0:9)", "resp.p0", "resp.p9", "ratio p9/p0");
        for (final int w : new int[]{1, 2, 4, 6, 10, 20}) {
            final var p0 = new ArrayList<Double>(); final var p9 = new ArrayList<Double>(); final var ratio = new ArrayList<Double>();
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final var wl = Workload.seeded(seed, 60, 5.0, new int[]{0, 9}, new int[]{1, 1}, BASE_LEN, 0.3, 0.2);
                final Supplier<CloudletScheduler> sc = () -> new CloudletSchedulerColaPri().setPriorityWeight(0, w).setPriorityWeight(9, 1);
                final var f = run(sc, wl, 2);
                final double a = Metrics.meanResponseOfClass(f, 0), b = Metrics.meanResponseOfClass(f, 9);
                p0.add(a); p9.add(b); ratio.add(a > 0 ? b / a : 0);
            }
            System.out.printf("%-8s %14s %14s %12s%n", w + ":1", ci(p0), ci(p9), ci(ratio));
        }
        System.out.println("  (ratio crece con w → la diferenciación es un parámetro ajustable, no fija)");
    }

    // D2: adaptive threshold -> differentiation only above it
    private static void d2Threshold() {
        System.out.println("\n=== D2 · UMBRAL ADAPTATIVO (pesos 6:1, carga alta 80@3/s) ===");
        System.out.printf("%-10s %14s %14s %14s%n", "umbral", "resp.p0", "resp.p9", "p9-p0 (dif)");
        for (final int thr : new int[]{0, 5, 15, 30, 60}) {
            final var p0 = new ArrayList<Double>(); final var p9 = new ArrayList<Double>(); final var dif = new ArrayList<Double>();
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final var wl = Workload.seeded(seed, 80, 3.0, new int[]{0, 9}, new int[]{1, 1}, BASE_LEN, 0.3, 0.2);
                final Supplier<CloudletScheduler> sc = () -> new CloudletSchedulerColaPri()
                    .setPriorityWeight(0, 6).setPriorityWeight(9, 1).setAdaptiveThreshold(thr);
                final var f = run(sc, wl, 2);
                final double a = Metrics.meanResponseOfClass(f, 0), b = Metrics.meanResponseOfClass(f, 9);
                p0.add(a); p9.add(b); dif.add(b - a);
            }
            System.out.printf("%-10s %14s %14s %14s%n", thr, ci(p0), ci(p9), ci(dif));
        }
        System.out.println("  (umbral alto → menos diferenciación: solo se activa si la cola supera el umbral)");
    }

    // D3: offered load -> differentiation grows with backlog
    private static void d3Load() {
        System.out.println("\n=== D3 · CARGA OFRECIDA (pesos 6:1, 80 tareas, rate variable) ===");
        System.out.printf("%-10s %14s %14s %14s%n", "rate/s", "resp.p0", "resp.p9", "p9-p0 (dif)");
        for (final double rate : new double[]{0.5, 1.0, 2.0, 3.0, 5.0}) {
            final var p0 = new ArrayList<Double>(); final var p9 = new ArrayList<Double>(); final var dif = new ArrayList<Double>();
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final var wl = Workload.seeded(seed, 80, rate, new int[]{0, 9}, new int[]{1, 1}, BASE_LEN, 0.3, 0.2);
                final Supplier<CloudletScheduler> sc = () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 6).setPriorityWeight(9, 1);
                final var f = run(sc, wl, 2);
                final double a = Metrics.meanResponseOfClass(f, 0), b = Metrics.meanResponseOfClass(f, 9);
                p0.add(a); p9.add(b); dif.add(b - a);
            }
            System.out.printf("%-10s %14s %14s %14s%n", rate, ci(p0), ci(p9), ci(dif));
        }
        System.out.println("  (más carga → más backlog → más diferenciación; a baja carga ~plano)");
    }

    // D4: VM PEs -> capacity effect
    private static void d4Pes() {
        System.out.println("\n=== D4 · Nº PEs de la VM (pesos 6:1, 80@3/s) ===");
        System.out.printf("%-8s %14s %14s %14s %12s%n", "PEs", "resp.p0", "resp.p9", "p9-p0 (dif)", "makespan");
        for (final int pes : new int[]{1, 2, 4, 8}) {
            final var p0 = new ArrayList<Double>(); final var p9 = new ArrayList<Double>();
            final var dif = new ArrayList<Double>(); final var mk = new ArrayList<Double>();
            for (long seed = 0; seed < N_SEEDS; seed++) {
                final var wl = Workload.seeded(seed, 80, 3.0, new int[]{0, 9}, new int[]{1, 1}, BASE_LEN, 0.3, 0.2);
                final Supplier<CloudletScheduler> sc = () -> new CloudletSchedulerColaPri().setPriorityWeight(0, 6).setPriorityWeight(9, 1);
                final var f = run(sc, wl, pes);
                final double a = Metrics.meanResponseOfClass(f, 0), b = Metrics.meanResponseOfClass(f, 9);
                p0.add(a); p9.add(b); dif.add(b - a); mk.add(Metrics.makespan(f));
            }
            System.out.printf("%-8s %14s %14s %14s %12s%n", pes, ci(p0), ci(p9), ci(dif), ci(mk));
        }
        System.out.println("  (más PEs → menos cola → menos diferenciación: el efecto vive en el backlog)");
    }

    private static String ci(final List<Double> v) {
        final double[] mc = Metrics.meanCi95(v);
        return String.format("%.1f±%.1f", mc[0], mc[1]);
    }

    private static List<Cloudlet> run(final Supplier<CloudletScheduler> factory, final List<Workload.Spec> specs, final int vmPes) {
        final var sim = Metrics.newSimulation();
        final var peList = new ArrayList<Pe>(vmPes);
        for (int i = 0; i < vmPes; i++) peList.add(new PeSimple(MIPS));
        final Host host = new HostSimple(8192, 100_000, 1_000_000, peList);
        new DatacenterSimple(sim, List.of(host));
        final var broker = new DatacenterBrokerSimple(sim);
        broker.setVmDestructionDelay(100_000.0);
        final var vm = new VmPatched(MIPS, vmPes);
        vm.setRam(4096).setBw(10_000).setSize(10_000).setCloudletScheduler(factory.get());
        broker.submitVmList(List.of(vm));
        final var cloudlets = new ArrayList<Cloudlet>(specs.size());
        for (final var s : specs) {
            final var c = Workload.cloudlet(s.length());
            c.setSizes(1024);
            c.setPriority(s.priority());
            c.setSubmissionDelay(s.arrival());
            cloudlets.add(c);
        }
        broker.submitCloudletList(cloudlets);
        sim.start();
        return Metrics.checked(broker.getCloudletFinishedList(), cloudlets.size());
    }
}
