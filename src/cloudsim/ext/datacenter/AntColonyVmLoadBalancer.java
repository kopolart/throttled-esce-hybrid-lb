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
 * Ant-colony-inspired VM selection.
 *
 * <p>Every VM holds a pheromone trail. The trail evaporates at each decision
 * and is reinforced by the processing time of completed cloudlets. Requests
 * are sampled with probability proportional to
 * {@code pheromone^alpha * (MIPS / (in-flight + 1))^beta}, so good VMs attract
 * more work without ever being hard-coded as the only choice.</p>
 */
public class AntColonyVmLoadBalancer extends VmLoadBalancer implements CloudSimEventListener {

    private static final double ALPHA = 1.0;
    private static final double BETA = 2.0;
    private static final double EVAPORATION = 0.10;
    private static final double MIN_PHEROMONE = 0.05;
    private static final double DEPOSIT = 1.0;
    private static final long DEFAULT_SEED = 42L;

    private final DatacenterController dcb;
    private final Map<Integer, Double> pheromone;
    private final Map<Integer, Integer> inFlight;
    private final Random random;

    public AntColonyVmLoadBalancer(DatacenterController dcb) {
        this.dcb = dcb;
        this.pheromone = new HashMap<Integer, Double>();
        this.inFlight = new HashMap<Integer, Integer>();
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

        double[] weight = new double[candidates.size()];
        double total = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            int vmId = candidates.get(i).intValue();
            VirtualMachine vm = (VirtualMachine) dcb.getVmList().getVMbyID(vmId);
            double mips = vm == null ? 0.0 : vm.getCharacteristics().getMips();
            double heuristic = mips / (inFlightOf(vmId) + 1);
            double trail = pheromoneOf(vmId);
            weight[i] = Math.pow(trail, ALPHA) * Math.pow(Math.max(heuristic, 1.0e-9), BETA);
            total += weight[i];
        }

        int chosen;
        if (total <= 0.0 || Double.isNaN(total)) {
            chosen = candidates.get(random.nextInt(candidates.size())).intValue();
        } else {
            double draw = random.nextDouble() * total;
            chosen = candidates.get(candidates.size() - 1).intValue();
            for (int i = 0; i < candidates.size(); i++) {
                draw -= weight[i];
                if (draw <= 0.0) {
                    chosen = candidates.get(i).intValue();
                    break;
                }
            }
        }

        evaporate();
        allocatedVm(chosen);
        return chosen;
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

    private void evaporate() {
        for (Map.Entry<Integer, Double> entry : pheromone.entrySet()) {
            entry.setValue(Double.valueOf(Math.max(MIN_PHEROMONE,
                    entry.getValue().doubleValue() * (1.0 - EVAPORATION))));
        }
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
                double deposit = DEPOSIT / (1.0 + Math.max(millis, 0.0));
                pheromone.put(vmId, Double.valueOf(pheromoneOf(vmId) + deposit));
            }
        }
    }

    private double pheromoneOf(int vmId) {
        Double trail = pheromone.get(vmId);
        if (trail == null) {
            trail = Double.valueOf(1.0);
            pheromone.put(vmId, trail);
        }
        return trail.doubleValue();
    }

    private int inFlightOf(int vmId) {
        Integer count = inFlight.get(vmId);
        return count == null ? 0 : count.intValue();
    }
}
