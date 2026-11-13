package cloudsim.ext.datacenter;

import java.util.Map;
import cloudsim.VirtualMachine;

/**
 * Max-Min Load Balancer (Immediate Mode Compatible).
 * 
 * FIXES THE NaN ERROR:
 * Since CloudAnalyst calls getNextAvailableVm() one by one (Immediate Mode),
 * we cannot compare multiple tasks against each other.
 * 
 * Strategy:
 * We assign the incoming task to the VM that has the Minimum Estimated Completion Time
 * regarding its current load. This is the closest approximation to Max-Min/Min-Min
 * possible without modifying the core DatacenterController.
 */
public class MaxMinVmLoadBalancer extends VmLoadBalancer {

    private DatacenterController dcb;

    public MaxMinVmLoadBalancer(DatacenterController dcb) {
        this.dcb = dcb;
    }

    /**
     * This is the method CloudAnalyst calls. 
     * It MUST return a valid VM ID (int), not -1.
     */
    @Override
    public int getNextAvailableVm() {
        Map<Integer, VirtualMachineState> vmStatesList = dcb.getVmStatesList();
        
        // Safety check
        if (vmStatesList == null || vmStatesList.isEmpty()) {
            return -1;
        }

        int selectedVmId = -1;
        double minEstimatedFinishTime = Double.MAX_VALUE;

        // Iterate over all VMs to find the best candidate
        for (Integer vmId : vmStatesList.keySet()) {
            VirtualMachine vm = (VirtualMachine) dcb.getVmList().getVMbyID(vmId);
            
            if (vm != null) {
                double mips = vm.getCharacteristics().getMips();
                
                // We use the allocation count as a proxy for load
                // because we don't know the exact length of the queued tasks in Immediate Mode.
                int currentAllocations = vmAllocationCounts.getOrDefault(vmId, 0);
                
                // Calculate when this VM is expected to be free
                // Logic: More allocations on a slow VM = High Finish Time
                double estimatedFinishTime = (currentAllocations + 1) / mips;

                // We pick the VM that finishes its current work soonest
                // (Standard MCT - Minimum Completion Time logic)
                if (estimatedFinishTime < minEstimatedFinishTime) {
                    minEstimatedFinishTime = estimatedFinishTime;
                    selectedVmId = vmId;
                }
            }
        }

        // CRITICAL FIX: Ensure we return a valid ID
        if (selectedVmId != -1) {
            allocatedVm(selectedVmId); // Increment internal counter
            return selectedVmId;
        }

        // Fallback: If logic fails, return the first available VM to prevent NaN
        return vmStatesList.keySet().iterator().next();
    }
}