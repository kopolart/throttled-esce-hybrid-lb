package cloudsim.ext.datacenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import cloudsim.VirtualMachine;
import cloudsim.ext.Constants;
import cloudsim.ext.event.CloudSimEvent;
import cloudsim.ext.event.CloudSimEventListener;
import cloudsim.ext.event.CloudSimEvents;
import gridsim.GridSim;

/**
 * Hybrid Throttled-ESCE load balancer with tri-level load thresholds and
 * cost-aware elastic scaling. Numeric parameters are read from
 * {@link LoadBalancerConfig} and default to Table 6 of the paper.
 */
public class ProposedMethodLoadBalancer extends VmLoadBalancer implements CloudSimEventListener {

    private static final int ACTIVATION_QUEUE_FLOOR = 20;
    private static final int ACTIVATION_QUEUE_DIVISOR = 3;
    private static final int ACTIVATION_QUEUE_SPIKE = 10;
    private static final int ACTIVATION_REVERSE_AFTER_FAILURES = 3;

    private static final int SCALE_HEAVY_QUEUE = 10;
    private static final int SCALE_MEDIUM_QUEUE = 5;
    private static final int SCALE_HEAVY_FAILURES = 5;
    private static final int SCALE_MEDIUM_FAILURES = 2;
    private static final int SCALE_MAX_BATCH = 5;
    private static final int SCALE_MEDIUM_BATCH = 3;

    private final DatacenterController dcb;
    private final LoadBalancerConfig config;
    private final int lowLoadThreshold;
    private final int mediumLoadThreshold;
    private final int highLoadThreshold;

    private Map<Integer, Integer> currentAllocationCounts;
    private Map<Integer, VirtualMachineState> vmStatesList;
    private double maxMips = 4000.0;
    private double maxRam = 2048.0;
    private Map<Integer, Double> averageProcessingTime;

    private Map<Integer, Double> vmBusyStartTime;
    private Map<Integer, Double> vmTotalActiveTime;
    private Map<Integer, FixedSizeQueue<Double>> processingTimeHistory;

    private Map<Integer, Double> vmIdleStartTime;
    private Map<Integer, Boolean> vmActiveStatus;
    private int totalVmCount;
    private List<Integer> vmIdsByTier;
    private int consecutiveFailures = 0;

    private double idleThresholdSeconds;
    private FixedSizeQueue<Integer> queueLengthHistory;
    private int optimalActiveVMs;

    private FixedSizeQueue<Double> responseTimeHistory;

    private Map<Integer, Integer> vmTaskCounters = new HashMap<>();

    public ProposedMethodLoadBalancer(DatacenterController dcb) {
        this.dcb = dcb;
        this.config = LoadBalancerConfig.load(dcb.getVmList().size());
        this.lowLoadThreshold = config.getLowLoadThreshold();
        this.mediumLoadThreshold = config.getMediumLoadThreshold();
        this.highLoadThreshold = config.getHighLoadThreshold();

        dcb.addCloudSimEventListener(this);
        this.currentAllocationCounts = Collections.synchronizedMap(new HashMap<Integer, Integer>());
        this.processingTimeHistory = new HashMap<>();
        this.vmTaskCounters = new HashMap<>();
        this.vmBusyStartTime = new HashMap<>();
        this.vmTotalActiveTime = new HashMap<>();

        this.vmStatesList = dcb.getVmStatesList();
        this.averageProcessingTime = new HashMap<>();
        this.vmIdleStartTime = new HashMap<>();
        this.vmActiveStatus = new HashMap<>();
        this.vmIdsByTier = new ArrayList<>();
        this.queueLengthHistory = new FixedSizeQueue<>(20);
        this.responseTimeHistory = new FixedSizeQueue<>(20);

        initializeVMs();
        calculateMaxValues();
        calculateDynamicParameters();

        System.out.println("[HybridLB] " + config.summary());
    }

    private void calculateMaxValues() {
        maxMips = 0.0;
        maxRam = 0.0;

        for (Object vmObj : dcb.getVmList()) {
            VirtualMachine vm = (VirtualMachine) vmObj;
            double vmMips = vm.getCharacteristics().getMips();
            double vmRam = vm.getCharacteristics().getMemory();

            if (vmMips > maxMips) {
                maxMips = vmMips;
            }
            if (vmRam > maxRam) {
                maxRam = vmRam;
            }
        }

        if (maxMips == 0) maxMips = 1.0;
        if (maxRam == 0) maxRam = 1.0;
    }

    private void calculateDynamicParameters() {
        idleThresholdSeconds = config.getIdleThresholdSeconds();
        optimalActiveVMs = (int) Math.max(1, Math.round(totalVmCount * config.getActiveRatio()));

        System.out.println("[HybridLB] Total VMs: " + totalVmCount
                + ", idle threshold: " + idleThresholdSeconds + "s"
                + ", initial active target: " + optimalActiveVMs);
    }

    private void initializeVMs() {
        totalVmCount = dcb.getVmList().size();

        List<VmInfo> vmInfoList = new ArrayList<>();
        for (Object vmObj : dcb.getVmList()) {
            VirtualMachine vm = (VirtualMachine) vmObj;
            int vmId = vm.getVmId();
            int cpus = vm.getCharacteristics().getCpus();
            vmInfoList.add(new VmInfo(vmId, cpus));
        }
        vmInfoList.sort((v1, v2) -> Integer.compare(v1.cpus, v2.cpus));

        for (VmInfo info : vmInfoList) {
            vmIdsByTier.add(info.vmId);
        }

        for (int i = 0; i < totalVmCount; i++) {
            Integer vmId = vmIdsByTier.get(i);
            processingTimeHistory.put(vmId, new FixedSizeQueue<>(20));
            averageProcessingTime.put(vmId, 0.0);
            vmIdleStartTime.put(vmId, GridSim.clock());
            vmTaskCounters.put(vmId, 0);

            vmActiveStatus.put(vmId, false);
            vmStatesList.remove(vmId);

            currentAllocationCounts.put(vmId, 0);
            vmTotalActiveTime.put(vmId, 0.0);
            vmBusyStartTime.put(vmId, null);
        }
    }

    private void ensureVmInitialized(int vmId) {
        if (!currentAllocationCounts.containsKey(vmId)) {
            currentAllocationCounts.put(vmId, 0);
            vmIdleStartTime.put(vmId, GridSim.clock());
            vmTotalActiveTime.put(vmId, 0.0);
            vmBusyStartTime.put(vmId, null);
            vmTaskCounters.put(vmId, 0);
        }
        if (!processingTimeHistory.containsKey(vmId)) {
            processingTimeHistory.put(vmId, new FixedSizeQueue<>(20));
            averageProcessingTime.put(vmId, 0.0);
        }
    }

    private double calculateVmThroughput(int vmId) {
        Integer completed = vmTaskCounters.get(vmId);
        Double activeTime = vmTotalActiveTime.get(vmId);

        if (completed == null || activeTime == null || activeTime <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (double) completed / activeTime;
    }

    public void updateProcessingTime(int vmId, double processingTime) {
        FixedSizeQueue<Double> queue = processingTimeHistory.get(vmId);
        queue.add(processingTime);
        updateAverage(vmId);

        responseTimeHistory.add(processingTime);
    }

    private void updateAverage(int vmId) {
        FixedSizeQueue<Double> queue = processingTimeHistory.get(vmId);
        double sum = 0;
        int size = queue.size();
        for (Double time : queue.elements()) {
            sum += time;
        }
        averageProcessingTime.put(vmId, size > 0 ? sum / size : 0);
    }

    @Override
    public int getNextAvailableVm() {
        checkForIdleVMs();

        int currentQueueSize = (dcb.waitingQueue != null) ? dcb.waitingQueue.size() : 0;
        if (dcb.waitingQueue != null) {
            queueLengthHistory.add(currentQueueSize);
        }

        int activeVMs = countActiveVMs();
        int busyVMs = getBusyVMCount();
        double utilization = activeVMs > 0 ? (double) busyVMs / activeVMs : 0;

        if (currentQueueSize > Math.max(ACTIVATION_QUEUE_FLOOR, optimalActiveVMs / ACTIVATION_QUEUE_DIVISOR)
                || utilization > config.getBusyFactorThreshold()) {
            activateIdleVMs(1);
        }

        int bestPhase1Vm = -1;
        double bestPhase1Score = -1.0;
        int bestPhase2Vm = -1;
        double bestPhase2Score = -1.0;
        int bestPhase3Vm = -1;
        double bestPhase3Throughput = -1.0;
        int bestPhase4Vm = -1;
        double bestPhase4MeanProcess = -1.0;

        for (int vmId : vmStatesList.keySet()) {
            ensureVmInitialized(vmId);
            VirtualMachineState state = vmStatesList.get(vmId);
            Integer currentTasks = currentAllocationCounts.get(vmId);

            if (state.equals(VirtualMachineState.AVAILABLE)) {
                double mips = dcb.getVmMips(vmId);
                int cores = dcb.getVmCpus(vmId);
                double score = mips * cores;
                if (score > bestPhase1Score) {
                    bestPhase1Score = score;
                    bestPhase1Vm = vmId;
                }
            } else {
                if (currentTasks < lowLoadThreshold) {
                    double score = calculateVmScore(vmId, currentTasks);
                    if (score > bestPhase2Score) {
                        bestPhase2Score = score;
                        bestPhase2Vm = vmId;
                    }
                } else if (currentTasks < mediumLoadThreshold) {
                    double throughput = calculateVmThroughput(vmId);
                    if (throughput > 0.0 && throughput > bestPhase3Throughput) {
                        bestPhase3Throughput = throughput;
                        bestPhase3Vm = vmId;
                    }
                } else if (currentTasks < highLoadThreshold) {
                    double processTimeScore = calculateProcessingTimeLoadScore(vmId, currentTasks);
                    if (processTimeScore > bestPhase4MeanProcess) {
                        bestPhase4MeanProcess = processTimeScore;
                        bestPhase4Vm = vmId;
                    }
                }
            }
        }

        if (bestPhase1Vm != -1) {
            consecutiveFailures = 0;
            allocatedVm(bestPhase1Vm);
            return bestPhase1Vm;
        } else if (bestPhase2Vm != -1) {
            consecutiveFailures = 0;
            allocatedVm(bestPhase2Vm);
            return bestPhase2Vm;
        } else if (bestPhase3Vm != -1) {
            consecutiveFailures = 0;
            allocatedVm(bestPhase3Vm);
            return bestPhase3Vm;
        } else if (bestPhase4Vm != -1) {
            consecutiveFailures = 0;
            allocatedVm(bestPhase4Vm);
            return bestPhase4Vm;
        }

        consecutiveFailures++;
        int queueSize = dcb.waitingQueue != null ? dcb.waitingQueue.size() : 0;
        boolean shouldActivate = consecutiveFailures > config.getConsecutiveFailureThreshold()
                || queueSize > optimalActiveVMs;
        if (shouldActivate) {
            int toActivate = calculateVMsToActivate();
            activateIdleVMs(toActivate);
        }

        int activeCount = countActiveVMs();
        if (activeCount < totalVmCount) {
            List<Integer> vmOrder = new ArrayList<>(vmIdsByTier);
            if (consecutiveFailures > ACTIVATION_REVERSE_AFTER_FAILURES || queueSize > ACTIVATION_QUEUE_SPIKE) {
                Collections.reverse(vmOrder);
            }
            for (Integer vmId : vmOrder) {
                Boolean isActive = vmActiveStatus.get(vmId);
                if (isActive != null && !isActive) {
                    activateVM(vmId);
                    consecutiveFailures = 0;
                    allocatedVm(vmId);
                    return vmId;
                }
            }
        }
        return -1;
    }

    private double calculateProcessingTimeLoadScore(int vmId, int currentTasks) {
        Double avgProcessingTime = averageProcessingTime.get(vmId);

        if (avgProcessingTime == null || avgProcessingTime == 0) {
            double mips = dcb.getVmMips(vmId);
            avgProcessingTime = 1.0 / (Math.max(mips, 1.0) / 1000.0);
        }

        double normalizedProcessingTime = 1.0 / (avgProcessingTime + 1.0);
        double normalizedLoad = 1.0 / (currentTasks + 1.0);

        return (0.4 * normalizedProcessingTime) + (0.6 * normalizedLoad);
    }

    private double calculateVmScore(int vmId, int currentTasks) {
        double mips = dcb.getVmMips(vmId);
        double ram = dcb.getVmRam(vmId);

        double normalizedMips = mips / maxMips;
        double normalizedRam = ram / maxRam;

        return (config.getCpuWeight() * normalizedMips + config.getRamWeight() * normalizedRam)
                / Math.max(currentTasks, config.getEpsilon());
    }

    private int calculateVMsToActivate() {
        int queueSize = dcb.waitingQueue != null ? dcb.waitingQueue.size() : 0;
        int activeVMs = countActiveVMs();

        if (queueSize > SCALE_HEAVY_QUEUE || consecutiveFailures > SCALE_HEAVY_FAILURES) {
            return Math.min(SCALE_MAX_BATCH, totalVmCount - activeVMs);
        } else if (queueSize > SCALE_MEDIUM_QUEUE || consecutiveFailures > SCALE_MEDIUM_FAILURES) {
            return Math.min(SCALE_MEDIUM_BATCH, totalVmCount - activeVMs);
        } else {
            return Math.min(1, totalVmCount - activeVMs);
        }
    }

    private void initializeNewVm(int vmId) {
        VirtualMachine vm = (VirtualMachine) dcb.getVmList().getVMbyID(vmId);
        if (vm == null) return;

        int cpus = vm.getCharacteristics().getCpus();
        int insertIndex = 0;
        for (int i = 0; i < vmIdsByTier.size(); i++) {
            VirtualMachine existingVm = (VirtualMachine) dcb.getVmList().getVMbyID(vmIdsByTier.get(i));
            if (existingVm != null && existingVm.getCharacteristics().getCpus() > cpus) {
                break;
            }
            insertIndex = i + 1;
        }
        vmIdsByTier.add(insertIndex, vmId);

        vmActiveStatus.put(vmId, true);
        currentAllocationCounts.put(vmId, 0);
        processingTimeHistory.put(vmId, new FixedSizeQueue<>(10));
        averageProcessingTime.put(vmId, 0.0);
        vmIdleStartTime.put(vmId, GridSim.clock());
        vmTaskCounters.put(vmId, 0);
        vmTotalActiveTime.put(vmId, 0.0);
        vmBusyStartTime.put(vmId, null);
        totalVmCount++;

        calculateDynamicParameters();
    }

    private void activateIdleVMs(int count) {
        int activated = 0;
        List<Integer> vmOrder = new ArrayList<>(vmIdsByTier);

        for (Integer vmId : vmOrder) {
            Boolean isActive = vmActiveStatus.get(vmId);
            if (isActive != null && !isActive) {
                activateVM(vmId);
                activated++;
                if (activated >= count) break;
            }
        }

        if (activated < count) {
            for (Integer vmId : vmIdsByTier) {
                Boolean isActive = vmActiveStatus.get(vmId);
                if (isActive != null && !isActive) {
                    activateVM(vmId);
                    activated++;
                    if (activated >= count) break;
                }
            }
        }
    }

    private void checkForIdleVMs() {
        double currentTime = GridSim.clock();
        int activeCount = countActiveVMs();
        int dynamicMinimum = Math.max(1, optimalActiveVMs);

        if (responseTimeHistory.size() > 5) {
            double recentMaxResponse = 0;
            for (Double rt : responseTimeHistory.elements()) {
                if (rt > recentMaxResponse) recentMaxResponse = rt;
            }
            if (recentMaxResponse > 1.4 * (averageOf(responseTimeHistory))) {
                return;
            }
        }

        List<Integer> candidates = new ArrayList<>();
        for (Integer vmId : vmActiveStatus.keySet()) {
            if (!Boolean.TRUE.equals(vmActiveStatus.get(vmId))) continue;

            int cloudletCount = currentAllocationCounts.getOrDefault(vmId, 0);
            if (cloudletCount != 0) continue;

            Double idleStart = vmIdleStartTime.get(vmId);
            if (idleStart == null || (currentTime - idleStart) <= idleThresholdSeconds) continue;

            VirtualMachine vm = (VirtualMachine) dcb.getVmList().getVMbyID(vmId);
            if (vm == null) continue;

            candidates.add(vmId);
        }

        candidates.sort((a, b) -> {
            double ia = currentTime - vmIdleStartTime.getOrDefault(a, currentTime);
            double ib = currentTime - vmIdleStartTime.getOrDefault(b, currentTime);
            return Double.compare(ib, ia);
        });

        for (Integer vmId : candidates) {
            if (activeCount <= dynamicMinimum) break;
            deactivateVM(vmId);
            activeCount--;
            break;
        }
    }

    private void activateVM(int vmId) {
        vmActiveStatus.put(vmId, true);
        vmStatesList.put(vmId, VirtualMachineState.AVAILABLE);
        vmIdleStartTime.put(vmId, GridSim.clock());

        Double[] usage = dcb.vmUsage.get(vmId);
        if (usage == null || usage[1] != -1) {
            dcb.vmUsage.put(vmId, new Double[] { GridSim.clock(), -1.0 });
        }
    }

    private void deactivateVM(int vmId) {
        vmActiveStatus.put(vmId, false);
        vmStatesList.remove(vmId);

        Double[] usage = dcb.vmUsage.get(vmId);
        if (usage != null && usage[1] == -1) {
            dcb.vmUsage.put(vmId, new Double[] { usage[0], GridSim.clock() });
        }
    }

    private int countActiveVMs() {
        int count = 0;
        for (Map.Entry<Integer, Boolean> entry : vmActiveStatus.entrySet()) {
            if (entry.getValue() != null && entry.getValue()) {
                count++;
            }
        }
        return count;
    }

    public void finalizeActiveTimes() {
        double now = GridSim.clock();
        for (int vmId : vmBusyStartTime.keySet()) {
            Double busyStart = vmBusyStartTime.get(vmId);
            if (busyStart != null) {
                double partialActiveTime = now - busyStart;
                vmTotalActiveTime.put(vmId, vmTotalActiveTime.getOrDefault(vmId, 0.0) + partialActiveTime);
                vmBusyStartTime.put(vmId, null);
            }
        }
    }

    private int getBusyVMCount() {
        int count = 0;
        for (Map.Entry<Integer, Integer> entry : currentAllocationCounts.entrySet()) {
            if (entry.getValue() > 0 && vmActiveStatus.getOrDefault(entry.getKey(), false)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Map<Integer, Integer> getVmAllocationCounts() {
        return new HashMap<>(vmTaskCounters);
    }

    @Override
    public void cloudSimEventFired(CloudSimEvent e) {
        if (e.getId() == CloudSimEvents.EVENT_CLOUDLET_ALLOCATED_TO_VM) {
            int vmId = (Integer) e.getParameter(Constants.PARAM_VM_ID);
            ensureVmInitialized(vmId);

            vmStatesList.put(vmId, VirtualMachineState.BUSY);
            vmIdleStartTime.remove(vmId);

            Integer currCount = currentAllocationCounts.get(vmId);
            currCount = (currCount == null) ? 1 : (currCount + 1);
            currentAllocationCounts.put(vmId, currCount);

            if (currCount == 1) {
                vmBusyStartTime.put(vmId, GridSim.clock());
                vmIdleStartTime.remove(vmId);
            }

            if (!vmActiveStatus.containsKey(vmId)) {
                initializeNewVm(vmId);
            }

            vmTaskCounters.put(vmId, vmTaskCounters.getOrDefault(vmId, 0) + 1);

        } else if (e.getId() == CloudSimEvents.EVENT_VM_FINISHED_CLOUDLET) {
            int vmId = (Integer) e.getParameter(Constants.PARAM_VM_ID);
            ensureVmInitialized(vmId);

            Integer currCount = currentAllocationCounts.get(vmId);
            Double busyStart = vmBusyStartTime.get(vmId);

            double finishedCloudletProcessingTime;
            if (busyStart != null) {
                finishedCloudletProcessingTime = Math.max(0.0, GridSim.clock() - busyStart);
            } else {
                finishedCloudletProcessingTime = 0.0;
            }

            int completedTasks = vmTaskCounters.getOrDefault(vmId, 0) + 1;
            vmTaskCounters.put(vmId, completedTasks);
            updateProcessingTime(vmId, finishedCloudletProcessingTime);

            if (currCount != null && currCount > 0) {
                currCount--;
                if (currCount == 0) {
                    vmStatesList.put(vmId, VirtualMachineState.AVAILABLE);
                    vmIdleStartTime.put(vmId, GridSim.clock());
                    if (busyStart != null) {
                        double activeTime = GridSim.clock() - busyStart;
                        vmTotalActiveTime.put(vmId, vmTotalActiveTime.getOrDefault(vmId, 0.0) + activeTime);
                        vmBusyStartTime.put(vmId, null);
                    }
                }
                currentAllocationCounts.put(vmId, currCount);
            }

            if (!vmActiveStatus.containsKey(vmId)) {
                initializeNewVm(vmId);
            }
        }
    }

    private double averageOf(FixedSizeQueue<Double> q) {
        double sum = 0.0;
        int n = 0;
        for (Double d : q.elements()) {
            sum += d;
            n++;
        }
        return n == 0 ? 0.0 : (sum / n);
    }

    private static class VmInfo implements Comparable<VmInfo> {
        int vmId;
        int cpus;

        VmInfo(int vmId, int cpus) {
            this.vmId = vmId;
            this.cpus = cpus;
        }

        @Override
        public int compareTo(VmInfo other) {
            return Integer.compare(this.cpus, other.cpus);
        }
    }

    private static class FixedSizeQueue<T> {
        private final int capacity;
        private final Queue<T> queue = new LinkedList<>();

        public FixedSizeQueue(int capacity) {
            this.capacity = capacity;
        }

        public void add(T element) {
            if (queue.size() >= capacity) {
                queue.poll();
            }
            queue.add(element);
        }

        public int size() {
            return queue.size();
        }

        public Iterable<T> elements() {
            return new LinkedList<>(queue);
        }
    }
}
