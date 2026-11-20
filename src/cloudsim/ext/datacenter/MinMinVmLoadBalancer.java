package cloudsim.ext.datacenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cloudsim.VirtualMachine;
import cloudsim.ext.Constants;
import cloudsim.ext.event.CloudSimEvent;
import cloudsim.ext.event.CloudSimEventListener;
import cloudsim.ext.event.CloudSimEvents;

/**
 * Min-Min in immediate-allocation mode.
 *
 * <p>Classic Min-Min schedules a batch of tasks by repeatedly assigning the
 * task with the smallest completion time. CloudAnalyst submits one request at
 * a time, so the batch is always a singleton. With equal-length requests the
 * minimum completion time reduces to the smallest value of
 * {@code (in-flight + 1) / MIPS}, which is what this policy evaluates.</p>
 */
public class MinMinVmLoadBalancer extends VmLoadBalancer implements CloudSimEventListener {

    private final DatacenterController dcb;
    private final Map<Integer, Integer> inFlight;

    public MinMinVmLoadBalancer(DatacenterController dcb) {
        this.dcb = dcb;
        this.inFlight = new HashMap<Integer, Integer>();
        dcb.addCloudSimEventListener(this);
    }

    @Override
    public int getNextAvailableVm() {
        List<Integer> candidates = new ArrayList<Integer>(dcb.getVmStatesList().keySet());
        if (candidates.isEmpty()) {
            return -1;
        }
        Collections.sort(candidates);

        int chosen = -1;
        double bestCompletion = Double.MAX_VALUE;
        for (Integer vmId : candidates) {
            if (dcb.getVmStatesList().get(vmId) == VirtualMachineState.INACTIVE) {
                continue;
            }
            VirtualMachine vm = (VirtualMachine) dcb.getVmList().getVMbyID(vmId.intValue());
            if (vm == null) {
                continue;
            }
            double mips = vm.getCharacteristics().getMips();
            if (mips <= 0.0) {
                continue;
            }
            double completion = (inFlightOf(vmId.intValue()) + 1) / mips;
            if (completion < bestCompletion) {
                bestCompletion = completion;
                chosen = vmId.intValue();
            }
        }

        if (chosen == -1) {
            return -1;
        }
        allocatedVm(chosen);
        return chosen;
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
        }
    }

    private int inFlightOf(int vmId) {
        Integer count = inFlight.get(vmId);
        return count == null ? 0 : count.intValue();
    }
}
