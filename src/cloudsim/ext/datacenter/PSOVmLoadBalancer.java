package cloudsim.ext.datacenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import cloudsim.VirtualMachine;
import cloudsim.ext.Constants;
import cloudsim.ext.event.CloudSimEvent;
import cloudsim.ext.event.CloudSimEventListener;
import cloudsim.ext.event.CloudSimEvents;
import cloudsim.ext.experiment.ExperimentSettings;

/**
 * Particle-swarm-inspired VM selection.
 *
 * <p>Each VM is a particle whose position encodes its desirability. A request
 * is routed to the VM with the smallest {@code expected wait / position}. The
 * swarm then moves: velocity is pulled toward the position that produced each
 * VM's best observed processing time (cognitive term) and toward the best VM
 * in the swarm (social term). Positions are clamped, and a small random
 * exploration keeps the swarm from collapsing onto one VM.</p>
 */
public class PSOVmLoadBalancer extends VmLoadBalancer implements CloudSimEventListener {

    private static final double INERTIA = 0.60;
    private static final double COGNITIVE = 1.20;
    private static final double SOCIAL = 1.40;
    private static final double MIN_POSITION = 0.25;
    private static final double MAX_POSITION = 1.60;
    private static final double EXPLORATION_RATE = 0.05;
    private static final long DEFAULT_SEED = 42L;

    private final DatacenterController dcb;
    private final Map<Integer, Integer> inFlight;
    private final Map<Integer, double[]> particles;
    private final Random random;

    public PSOVmLoadBalancer(DatacenterController dcb) {
        this.dcb = dcb;
        this.inFlight = new HashMap<Integer, Integer>();
        this.particles = new HashMap<Integer, double[]>();
        Long seed = ExperimentSettings.seedOrNull();
        this.random = new Random(seed == null ? DEFAULT_SEED : seed.longValue());
        dcb.addCloudSimEventListener(this);
    }

    @Override
    public int getNextAvailableVm() {
        List<Integer> candidates = activeCandidates();
        if (candidates.isEmpty()) {
            return -1;
        }

        int chosen;
        if (random.nextDouble() < EXPLORATION_RATE) {
            chosen = candidates.get(random.nextInt(candidates.size())).intValue();
        } else {
            chosen = -1;
            double bestScore = Double.MAX_VALUE;
            for (Integer vmId : candidates) {
                double wait = expectedWait(vmId.intValue());
                double position = particle(vmId.intValue())[0];
                double score = wait / Math.max(position, MIN_POSITION);
                if (score < bestScore) {
                    bestScore = score;
                    chosen = vmId.intValue();
                }
            }
        }

        moveSwarm(candidates);
        allocatedVm(chosen);
        return chosen;
    }

    private void moveSwarm(List<Integer> candidates) {
        int swarmBest = -1;
        double bestWait = Double.MAX_VALUE;
        for (Integer vmId : candidates) {
            double observed = particle(vmId.intValue())[2];
            if (observed < bestWait) {
                bestWait = observed;
                swarmBest = vmId.intValue();
            }
        }
        double socialTarget = swarmBest == -1
                ? averagePosition(candidates)
                : particle(swarmBest)[3];

        for (Integer vmId : candidates) {
            double[] p = particle(vmId.intValue());
            double velocity = INERTIA * p[1]
                    + COGNITIVE * random.nextDouble() * (p[3] - p[0])
                    + SOCIAL * random.nextDouble() * (socialTarget - p[0]);
            p[1] = velocity;
            p[0] = clamp(p[0] + velocity);
        }
    }

    private double averagePosition(List<Integer> candidates) {
        double sum = 0.0;
        for (Integer vmId : candidates) {
            sum += particle(vmId.intValue())[0];
        }
        return sum / candidates.size();
    }

    private double[] particle(int vmId) {
        double[] p = particles.get(vmId);
        if (p == null) {
            double mips = mipsOf(vmId);
            double maxMips = maxMips();
            double position = 0.5 + 0.5 * (maxMips <= 0.0 ? 0.5 : mips / maxMips);
            p = new double[] { position, 0.0, Double.MAX_VALUE, position };
            particles.put(vmId, p);
        }
        return p;
    }

    private double expectedWait(int vmId) {
        double mips = mipsOf(vmId);
        if (mips <= 0.0) {
            return Double.MAX_VALUE;
        }
        return (inFlightOf(vmId) + 1) / mips;
    }

    private double mipsOf(int vmId) {
        VirtualMachine vm = (VirtualMachine) dcb.getVmList().getVMbyID(vmId);
        return vm == null ? 0.0 : vm.getCharacteristics().getMips();
    }

    private double maxMips() {
        double max = 0.0;
        for (Integer vmId : dcb.getVmStatesList().keySet()) {
            max = Math.max(max, mipsOf(vmId.intValue()));
        }
        return max;
    }

    private List<Integer> activeCandidates() {
        List<Integer> candidates = new ArrayList<Integer>();
        for (Integer vmId : dcb.getVmStatesList().keySet()) {
            if (dcb.getVmStatesList().get(vmId) != VirtualMachineState.INACTIVE) {
                candidates.add(vmId);
            }
        }
        Collections.sort(candidates);
        return candidates;
    }

    @Override
    public void cloudSimEventFired(CloudSimEvent e) {
        Object vmParameter = e.getParameter(Constants.PARAM_VM_ID);
        if (!(vmParameter instanceof Integer)) {
            return;
        }
        int vmId = ((Integer) vmParameter).intValue();
        if (e.getId() == CloudSimEvents.EVENT_CLOUDLET_ALLOCATED_TO_VM) {
            inFlight.put(vmId, Integer.valueOf(inFlightOf(vmId) + 1));
        } else if (e.getId() == CloudSimEvents.EVENT_VM_FINISHED_CLOUDLET) {
            inFlight.put(vmId, Integer.valueOf(Math.max(0, inFlightOf(vmId) - 1)));
            Object processing = e.getParameter(Constants.PARAM_PROCESSING_TIME);
            if (processing instanceof Double) {
                double millis = ((Double) processing).doubleValue();
                double[] p = particle(vmId);
                if (millis < p[2]) {
                    p[2] = millis;
                    p[3] = p[0];
                }
            }
        }
    }

    private int inFlightOf(int vmId) {
        Integer count = inFlight.get(vmId);
        return count == null ? 0 : count.intValue();
    }

    private static double clamp(double position) {
        return Math.max(MIN_POSITION, Math.min(MAX_POSITION, position));
    }
}
