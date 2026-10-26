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
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;

/**
 * Audit probe (finding C-01): does a 2-PE VM at 1000 MIPS really run 2 cloudlets in parallel when
 * cloudlets queue (more cloudlets than PEs) and arrive staggered (setSubmissionDelay), as in the
 * harnesses? 8 cloudlets x 4000 MI: parallel on 2 PEs -> makespan ~16 s; serial -> ~32 s.
 */
public class CapacityProbe {
    public static void main(String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        System.out.printf("%-58s %10s %10s%n", "scenario (8 cloudlets x 4000 MI, VM 2 PEs x 1000 MIPS)", "makespan", "maxConc");
        run("all at t=0, RAM/BW Full (harness ctor), sizes 1024", 0.0, true, true);
        run("all at t=0, RAM/BW 1%, sizes 1024", 0.0, false, true);
        run("staggered 0.2 s, RAM/BW Full, sizes 1024", 0.2, true, true);
        run("staggered 0.2 s, RAM/BW 1%, sizes 1024", 0.2, false, true);
        run("staggered 0.2 s, RAM/BW Full, no setSizes", 0.2, true, false);
        run("staggered 5 s (no queue), RAM/BW Full, sizes 1024", 5.0, true, true);
        System.exit(0);
    }

    private static void run(final String label, final double gap, final boolean full, final boolean sizes) {
        final var sim = Metrics.newSimulation();
        final var peList = new ArrayList<Pe>(2);
        for (int i = 0; i < 2; i++) peList.add(new PeSimple(1000));
        final Host host = new HostSimple(8192, 100_000, 1_000_000, peList);
        new DatacenterSimple(sim, List.of(host));
        final var broker = new DatacenterBrokerSimple(sim);
        broker.setVmDestructionDelay(100_000.0);
        final var vm = new VmPatched(1000, 2);
        vm.setRam(4096).setBw(10_000).setSize(10_000).setCloudletScheduler(new CloudletSchedulerFifo());
        broker.submitVmList(List.of(vm));
        final var cloudlets = new ArrayList<Cloudlet>(8);
        for (int i = 0; i < 8; i++) {
            final var c = new CloudletSimple(4000, 1, new UtilizationModelFull());
            if (!full) { c.setUtilizationModelRam(new UtilizationModelDynamic(0.01)); c.setUtilizationModelBw(new UtilizationModelDynamic(0.01)); }
            if (sizes) c.setSizes(1024);
            c.setSubmissionDelay(i * gap);
            cloudlets.add(c);
        }
        broker.submitCloudletList(cloudlets);
        sim.start();
        final var fin = broker.getCloudletFinishedList();
        // max concurrency: count overlapping [start, finish) intervals
        int maxConc = 0;
        for (final Cloudlet a : fin) {
            int conc = 0;
            for (final Cloudlet b : fin)
                if (b.getStartTime() <= a.getStartTime() && a.getStartTime() < b.getFinishTime()) conc++;
            maxConc = Math.max(maxConc, conc);
        }
        final StringBuilder sb = new StringBuilder();
        for (final Cloudlet c : fin) sb.append(String.format(" [%d: %.1f-%.1f]", c.getId(), c.getStartTime(), c.getFinishTime()));
        System.out.printf("%-58s %10.1f %10d%s%n", label, Metrics.makespan(fin), maxConc, sb);
    }
}
