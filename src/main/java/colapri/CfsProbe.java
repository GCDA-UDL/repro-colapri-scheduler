package colapri;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerCompletelyFair;

import java.util.ArrayList;
import java.util.List;

/**
 * Gating probe for the built-in Completely Fair Scheduler (CFS) of CloudSim Plus 8.5.7 as a baseline: 40 staggered
 * tasks (one every 0.2 s, classes 0/5/9) on one 2-PE VM, run without and with a periodic datacenter scheduling
 * interval of 1 s. Each run gets a 60 s wall-clock watchdog; the probe reports whether the simulation terminated and
 * how many tasks finished. CFS is usable as a baseline only if a run terminates with 40/40.
 */
public class CfsProbe {
    public static void main(String[] args) throws InterruptedException {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.OFF);
        System.out.println("#  CfsProbe · CloudletSchedulerCompletelyFair, 40 staggered tasks on one 2-PE VM");
        System.out.println("#  " + Metrics.provenance());
        for (final double interval : new double[]{0.0, 1.0}) {
            final String[] out = {null};
            final Thread t = new Thread(() -> out[0] = run(interval));
            t.setDaemon(true);
            final long t0 = System.currentTimeMillis();
            t.start();
            t.join(60_000);
            final String cfg = interval > 0 ? "scheduling interval " + interval + " s" : "no scheduling interval";
            if (t.isAlive())
                System.out.printf("[CfsProbe] %s: did NOT terminate within 60 s of wall-clock time%n", cfg);
            else
                System.out.printf("[CfsProbe] %s: terminated, %s (wall %d ms)%n", cfg, out[0], System.currentTimeMillis() - t0);
            System.out.flush();
        }
        System.exit(0);
    }

    private static String run(final double interval) {
        final var sim = Metrics.newSimulation();
        final var peList = new ArrayList<Pe>(2);
        for (int i = 0; i < 2; i++) peList.add(new PeSimple(1000));
        final Host host = new HostSimple(8192, 100_000, 1_000_000, peList);
        final var dc = new DatacenterSimple(sim, List.of(host));
        if (interval > 0) dc.setSchedulingInterval(interval);
        final var broker = new DatacenterBrokerSimple(sim);
        broker.setVmDestructionDelay(100_000.0);
        final var vm = new VmPatched(1000, 2);
        vm.setRam(4096).setBw(10_000).setSize(10_000).setCloudletScheduler(new CloudletSchedulerCompletelyFair());
        broker.submitVmList(List.of(vm));
        final var cloudlets = new ArrayList<Cloudlet>(40);
        for (int i = 0; i < 40; i++) {
            final var c = Workload.cloudlet(4000);
            c.setSizes(1024);
            c.setPriority(i % 3 == 0 ? 0 : (i % 3 == 1 ? 5 : 9));
            c.setSubmissionDelay(i * 0.2);   // staggered -> backlog
            cloudlets.add(c);
        }
        broker.submitCloudletList(cloudlets);
        sim.start();
        final var fin = broker.getCloudletFinishedList();
        return String.format("%d/40 tasks finished, makespan %.1f s", fin.size(), Metrics.makespan(fin));
    }
}
