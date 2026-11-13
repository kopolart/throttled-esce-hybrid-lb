package cloudsim.ext.datacenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import cloudsim.VirtualMachine;
import cloudsim.ext.InternetCloudlet;

/**
 * True Min-Min Load Balancer Implementation.
 * 
 * NOTE: Min-Min is a BATCH strategy. It cannot determine the best assignment
 * looking at only one cloudlet in isolation.
 * 
 * Logic:
 * 1. Calculate Expected Completion Time (ECT) for every task on every VM.
 * 2. Find the task that has the absolute minimum earliest completion time.
 * 3. Assign that task, update the VM's ready time, and repeat.
 */
public class MinMinVmLoadBalancer extends VmLoadBalancer {

    private DatacenterController dcb;

    public MinMinVmLoadBalancer(DatacenterController dcb) {
        this.dcb = dcb;
    }

    /**
     * This generic method from the parent class assumes First-Come-First-Serve (Immediate).
     * It is technically incompatible with Min-Min. 
     * If called, we default to a simple Round-Robin or fallback to prevent crashes.
     */
    @Override
    public int getNextAvailableVm() {
        // 1. Get the list of all VMs
        Map<Integer, VirtualMachineState> vmStatesList = dcb.getVmStatesList();
        if (vmStatesList == null || vmStatesList.isEmpty()) {
            return -1;
        }

        int bestVmId = -1;
        double minEstimatedFinishTime = Double.MAX_VALUE;

        // 2. Iterate through all VMs to find the one that will finish soonest
        for (Integer vmId : vmStatesList.keySet()) {
            VirtualMachine vm = (VirtualMachine) dcb.getVmList().getVMbyID(vmId);
            if (vm != null) {
                double mips = vm.getCharacteristics().getMips();
                // Estimate current load. 
                // Since we don't know the exact task size in Immediate Mode, 
                // we assume the VM with the least ratio of (AllocatedTasks / MIPS) is the fastest.
                
                int currentAllocations = vmAllocationCounts.getOrDefault(vmId, 0);
                
                // This is an estimation of when the VM will be free
                double estimatedFinishTime = currentAllocations / mips; 

                if (estimatedFinishTime < minEstimatedFinishTime) {
                    minEstimatedFinishTime = estimatedFinishTime;
                    bestVmId = vmId;
                }
            }
        }

        // 3. Return a valid ID (Stops the NaN/Infinite loop)
        if (bestVmId != -1) {
            allocatedVm(bestVmId); // Update internal stats
            return bestVmId;
        }
        
        // Fallback: Just pick the first one if logic fails
        return vmStatesList.keySet().iterator().next(); 
    }

    /**
     * Executes the True Min-Min Algorithm on a batch of cloudlets.
     * 
     * @param cloudletBatch The list of waiting cloudlets/tasks.
     * @return A Map linking CloudletId -> VmId
     */
    public Map<Integer, Integer> getBatchAssignments(List<InternetCloudlet> cloudletBatch) {
        Map<Integer, Integer> assignments = new HashMap<>();
        
        // 1. Get available VMs
        Map<Integer, VirtualMachineState> vmStates = dcb.getVmStatesList();
        List<VirtualMachine> vmList = new ArrayList<>();
        
        // We use a map to track when each VM will be free (Ready Time)
        // Key: VmId, Value: Time (in seconds/units) until free
        Map<Integer, Double> vmReadyTimes = new HashMap<>();

        for (Integer vmId : vmStates.keySet()) {
            VirtualMachine vm = (VirtualMachine) dcb.getVmList().getVMbyID(vmId);
            if (vm != null) {
                vmList.add(vm);
                // Initialize ready time based on current processing
                // For simplicity in CloudAnalyst, we often assume start of batch is 0.0
                // or you can estimate based on current allocation count.
                vmReadyTimes.put(vmId, 0.0); 
            }
        }

        // Clone the list so we can remove tasks as we schedule them
        List<InternetCloudlet> unscheduled = new ArrayList<>(cloudletBatch);

        // 2. The Min-Min Loop
        while (!unscheduled.isEmpty()) {
            
            InternetCloudlet bestCloudlet = null;
            VirtualMachine bestVm = null;
            double minMinTime = Double.MAX_VALUE;

            // Phase 1: For each cloudlet, find its best VM (Minimum Completion Time)
            // Phase 2: Select the cloudlet with the global minimum among those
            
            // Loop over all remaining cloudlets
            for (InternetCloudlet cl : unscheduled) {
                
                VirtualMachine localBestVm = null;
                double localMinTime = Double.MAX_VALUE;

                // Find the best VM for THIS specific cloudlet
                for (VirtualMachine vm : vmList) {
                    double mips = vm.getCharacteristics().getMips();
                    double length = cl.getGridletLength(); // Request size
                    double executionTime = (length / mips); // Time to run
                    
                    // Completion Time = When VM is Ready + Time to Run
                    double readyTime = vmReadyTimes.get(vm.getVmId());
                    double completionTime = readyTime + executionTime;

                    if (completionTime < localMinTime) {
                        localMinTime = completionTime;
                        localBestVm = vm;
                    }
                }

                // Check if this Cloudlet's best time is better than the Global Best
                if (localMinTime < minMinTime) {
                    minMinTime = localMinTime;
                    bestCloudlet = cl;
                    bestVm = localBestVm;
                }
            }

            // 3. Assign the Winner
            if (bestCloudlet != null && bestVm != null) {
                // Save assignment
                assignments.put(bestCloudlet.getCloudletId(), bestVm.getVmId());
                
                // Update the internal tracker for this VM's load
                double length = bestCloudlet.getGridletLength();
                double execTime = length / bestVm.getCharacteristics().getMips();
                double currentReadyTime = vmReadyTimes.get(bestVm.getVmId());
                
                // The VM is now busy until:
                vmReadyTimes.put(bestVm.getVmId(), currentReadyTime + execTime);
                
                // Also update CloudAnalyst internal counter (optional, but good for GUI)
                allocatedVm(bestVm.getVmId());
                
                // Remove from the batch list
                unscheduled.remove(bestCloudlet);
            } else {
                // Safety break to prevent infinite loops if no VMs exist
                break;
            }
        }

        return assignments;
    }
}