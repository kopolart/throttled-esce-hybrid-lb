package cloudsim.ext.datacenter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import cloudsim.DatacenterBroker;
import cloudsim.DatacenterCharacteristics;
import cloudsim.DatacenterTags;
import cloudsim.VMCharacteristics;
import cloudsim.VirtualMachine;
import cloudsim.VirtualMachineList;
import cloudsim.ext.Constants;
import cloudsim.ext.GeoLocatable;
import cloudsim.ext.InternetCharacteristics;
import cloudsim.ext.InternetCloudlet;
import cloudsim.ext.event.CloudSimEvent;
import cloudsim.ext.event.CloudSimEventListener;
import cloudsim.ext.event.CloudSimEvents;
import cloudsim.ext.event.CloudsimObservable;
import cloudsim.ext.stat.HourlyEventCounter;
import cloudsim.ext.stat.HourlyStat;
import cloudsim.ext.util.CommPath;
import cloudsim.ext.util.InternetEntitityRegistry;
import eduni.simjava.Sim_event;
import eduni.simjava.Sim_stat;
import eduni.simjava.Sim_system;
import gridsim.GridSim;
import gridsim.GridSimTags;

public class DatacenterController extends DatacenterBroker implements GeoLocatable, CloudsimObservable, Constants {

	private List<CloudSimEventListener> listeners;
	private VmLoadBalancer loadBalancer;
	private int region;
	private Sim_stat stat;
	private int queuedCount = 0;
	private double costPerVmHour;
	private double costPerDataGB;
	private double totalData;
	private HourlyEventCounter hourlyArrival;
	private HourlyStat hourlyProcessingTimes;
	public Map<Integer, Double[]> vmUsage;
	private Map<Integer, VirtualMachineState> vmStatesList;
	private Map<Integer, Long[]> processingCloudletStatuses;
	private int requestsPerCloudlet;
	public List<InternetCloudlet> waitingQueue;
	private String dcName;
	private boolean lastVmCreateFailed = false;
	private int allRequestsProcessed = 0;

	/** Constructor. */
	public DatacenterController(String name, int region, double costPerVmHour, double costPerDataGB,
			int requestsPerCloudlet, String loadBalancePolicy) throws Exception {
		super(name + "-Broker");

		this.dcName = name;

		System.out.println("Creating new broker " + get_name());

		listeners = new ArrayList<CloudSimEventListener>();

		this.region = region;
		this.costPerVmHour = costPerVmHour;
		this.costPerDataGB = costPerDataGB;
		this.requestsPerCloudlet = requestsPerCloudlet;

		InternetCharacteristics.getInstance().addEntity(this);

		stat = new Sim_stat();
		stat.add_measure(DC_SERVICE_TIME, Sim_stat.INTERVAL_BASED);

		hourlyProcessingTimes = new HourlyStat(stat, "Overloading status : " + get_name(), Sim_stat.INTERVAL_BASED);
		set_stat(stat);

		hourlyArrival = new HourlyEventCounter("Hourly Arrival Rate : " + get_name());

		vmUsage = new HashMap<Integer, Double[]>();
		vmStatesList = Collections.synchronizedMap(new HashMap<Integer, VirtualMachineState>());
		waitingQueue = Collections.synchronizedList(new LinkedList<InternetCloudlet>());
		processingCloudletStatuses = new HashMap<Integer, Long[]>();

		if (loadBalancePolicy.equals(Constants.LOAD_BALANCE_ACTIVE)) {
			this.loadBalancer = new ActiveVmLoadBalancer(this);
		}

		else if (loadBalancePolicy.equals(Constants.LOAD_BALANCE_POLICY_RR)) {
			this.loadBalancer = new RoundRobinVmLoadBalancer(vmStatesList);
		}

		else if (loadBalancePolicy.equals(Constants.LOAD_BALANCE_THROTTLED)) {
			this.loadBalancer = new ThrottledVmLoadBalancer(this);
		}
		else if (loadBalancePolicy.equals(Constants.LOAD_BALANCE_ANT_COLONY)) {
			this.loadBalancer = new AntColonyVmLoadBalancer(this);
		} else if (loadBalancePolicy.equals(Constants.LOAD_BALANCE_HONEY_COLONY)) {
			this.loadBalancer = new honeyBee(this);
		} else if (loadBalancePolicy.equals(Constants.LOAD_BALANCE_PPM)) {
			this.loadBalancer = new ProposedMethodLoadBalancer(this);
			System.out.println("Enhanced Load Balancer for " + name);
		} else if (loadBalancePolicy.equals(Constants.LOAD_BALANCE_PSO)) {
			this.loadBalancer = new PSOVmLoadBalancer(this);
		}else if (loadBalancePolicy.equals(Constants.LOAD_BALANCE_MM)) {
			this.loadBalancer = new MinMinVmLoadBalancer(this);
		}else if (loadBalancePolicy.equals(Constants.LOAD_BALANCE_MMax)) {
			this.loadBalancer = new MaxMinVmLoadBalancer(this);
		}
	
		else {
			this.loadBalancer = new RoundRobinVmLoadBalancer(vmStatesList);
		}
	}

	@Override
	public void body() {

		System.out.println("Starting broker " + get_id() + " name=" + get_name());

		sim_process(5.0);
		LinkedList datacenterList = GridSim.getGridResourceList();
		System.out.println(GridSim.clock() + ": " + this.get_name() + ": Cloud Resource List received with "
				+ datacenterList.size() + " resource(s)");

		// initilize fields
		this.datacenters = 1;
		this.datacenterID = new int[1];
		this.datacenterChar = new DatacenterCharacteristics[1];
		datacenterChar[0] = null;

		// queries datacenters about their characteristics
		this.datacenterID[0] = GridSim.getEntityId(dcName);
		send(dcName, GridSimTags.SCHEDULE_NOW, GridSimTags.RESOURCE_CHARACTERISTICS, this.get_id());

		// receives events and process them
		Sim_event ev = new Sim_event();
		while (Sim_system.running()) {
			super.sim_get_next(ev);

			// System.out.println(GridSim.clock()+": Broker got event:" + ev.get_tag() + "
			// from " + ev.get_src() + " with data=" + ev.get_data());

			// if the simulation finishes then exit the loop
			if (ev.get_tag() == GridSimTags.END_OF_SIMULATION) {
				break;
			}

			// process the received event
			processEvent(ev);
		}

		System.out.println(
				get_name() + " finalizing, submitted cloudlets=" + cloudletsSubmitted + " processing cloudlets="
						+ processingCloudletStatuses.size() + " ,allRequestsProcessed=" + allRequestsProcessed);
	}// body

	@Override
	protected void processEvent(Sim_event ev) {
		Object payload = ev.get_data();

		// Filter out in bound InternetCloudlet events and returning cloudlet events.
		// Let the super handle the rest.
		if (ev.get_tag() == Constants.REQUEST_INTERNET_CLOUDLET_TAG) {
			if ((payload != null) && (payload instanceof InternetCloudlet)) {
				handleRequestCloudlet((InternetCloudlet) payload);
			} else {
				System.out.println("invalid payload");
			}
		} else if ((ev.get_tag() == GridSimTags.GRIDLET_RETURN) && (payload instanceof InternetCloudlet)) {
			if ((payload != null) && (payload instanceof InternetCloudlet)) {
				InternetCloudlet cl = (InternetCloudlet) payload;

				handleResponseCloudlet(cl);
			}
		} else {
			super.processEvent(ev);
		}
	}

	/**
	 * Handle responses returning for request previously handled. CORRECTED to
	 * measure the completion time of the ENTIRE parent request, not just the first
	 * sub-task.
	 * 
	 * @param cl
	 */
	protected void handleResponseCloudlet(InternetCloudlet cl) {
		cloudletCompletedProcessing(cl);
		int parentRequest = cl.getParentId();
		Long[] parentReqStatus = processingCloudletStatuses.remove(parentRequest);
		cl.setActualCPUTime(GridSim.clock() - cl.getExecStartTime());
		if (parentReqStatus == null) {
			System.out.println("Got response for " + parentRequest + " but it seems to be completed.");
			return;
		}

		long totalRequests = parentReqStatus[0];
		long completedRequests = parentReqStatus[1] + cl.getRequestCount();
		final long startTime = parentReqStatus[2];

		if (parentReqStatus[1] == 0) {
			double endTime = GridSim.clock();
			double thisProcessingTime = (endTime - startTime);

			InternetCloudlet responseCloudlet = new InternetCloudlet(parentRequest, 0,
					(long) (cl.getDataSize() / cl.getRequestCount()), 0, cl.getOriginator(), cl.getAppId(),
					(int) totalRequests);
			responseCloudlet.addData("procTime", thisProcessingTime);
			send("Internet", 0.0, Constants.RESPONSE_INTERNET_CLOUDLET_TAG, responseCloudlet);

			stat.update(DC_SERVICE_TIME, startTime, endTime);
			hourlyProcessingTimes.update(startTime, endTime);

			InternetCharacteristics.getInstance().updateSerivceLatency(get_name(), thisProcessingTime);
		}

		if (completedRequests >= totalRequests) {
			allRequestsProcessed += totalRequests;
		} else {
			processingCloudletStatuses.put(parentRequest, new Long[] { totalRequests, completedRequests, startTime });
		}

		totalData += cl.getDataSize();
	}

	private void handleRequestCloudlet(InternetCloudlet cl) {

		// Reflect completion of request transmission in traffic levels
		InternetCharacteristics.getInstance().removeTraffic((CommPath) cl.getData(Constants.PARAM_COMM_PATH),
				cl.getRequestCount());

		int numOfActualRequests = cl.getRequestCount();
		int numOfReqCloudlets;
		if (numOfActualRequests <= requestsPerCloudlet) {
			numOfReqCloudlets = 0;
		} else {
			numOfReqCloudlets = numOfActualRequests / requestsPerCloudlet;
		}

		int i;
		for (i = 0; i < numOfReqCloudlets; i++) {
			InternetCloudlet subCloudlet = new InternetCloudlet(cl.getCloudletId() * 1000 + i,
					cl.getGridletLength() * requestsPerCloudlet, cl.getGridletFileSize() * requestsPerCloudlet,
					cl.getGridletOutputSize() * requestsPerCloudlet, cl.getOriginator(), cl.getAppId(),
					requestsPerCloudlet);
			subCloudlet.setParentId(cl.getCloudletId());
			submitNewCloudlet(subCloudlet);
		}

		// If there are any remaining, which didn't fit into a default sized group
		int remainingRequests = numOfActualRequests - requestsPerCloudlet * numOfReqCloudlets;
		if (remainingRequests != 0) {
			InternetCloudlet subCloudlet = new InternetCloudlet(cl.getCloudletId() * 1000 + i + 1,
					cl.getGridletLength() * remainingRequests, cl.getGridletFileSize() * remainingRequests,
					cl.getGridletOutputSize() * remainingRequests, cl.getOriginator(), cl.getAppId(),
					remainingRequests);
			subCloudlet.setParentId(cl.getCloudletId());
			submitNewCloudlet(subCloudlet);
		}

		totalData += cl.getDataSize();
		long startTime = (long) GridSim.clock();

		// System.out.println(startTime + ": " + get_name() + " started processing " +
		// cl.getCloudletId());
		processingCloudletStatuses.put(cl.getCloudletId(), new Long[] { (long) numOfActualRequests, 0L, startTime });
	}

	private void cloudletCompletedProcessing(InternetCloudlet cl) {
		// Notify load balancer of vm getting freed up
		int vmId = cl.getVmId();
		CloudSimEvent e = new CloudSimEvent(CloudSimEvents.EVENT_VM_FINISHED_CLOUDLET);
		e.addParameter(Constants.PARAM_VM_ID, vmId);
		fireCloudSimEvent(e);

		submitWaitingCloudlet();
	}

	private void submitWaitingCloudlet() {
		int nextAvailVM = loadBalancer.getNextAvailableVm();

		if ((nextAvailVM != -1) && (waitingQueue.size() > 0)) {
			InternetCloudlet cl = waitingQueue.remove(0);
			submitCloudlet(cl, nextAvailVM);
		}
	}

	private void submitNewCloudlet(InternetCloudlet cl) {

		hourlyArrival.addEvent(GridSim.clock(), cl.getRequestCount());
		if (loadBalancer == null) {
			loadBalancer = new RoundRobinVmLoadBalancer(vmStatesList);
		}

		int nextAvailVM = loadBalancer.getNextAvailableVm();

		if (nextAvailVM == -1) {
			// All VM's are busy. Put it in queue
			// System.out.println("VM's busy, queueing " + cl);
			waitingQueue.add(cl);

			queuedCount++;
		} else {
			submitCloudlet(cl, nextAvailVM);
		}
	}

	private void submitCloudlet(InternetCloudlet cl, int vmId) {
		// submit to the next machine
		cl.setVmId(vmId);
		cl.setUserID(this.get_id());

		int dest = vmMapping[vmId];
		// System.out.println(GridSim.clock()+": "+this.get_name()+ ": Sending cloudlet
		// "+cl.getGridletID()+" to VM #"+((VirtualMachine)vmlist.get(vmId)).getVmId() +
		// ", dest=" + dest);
		super.send(dest, GridSimTags.SCHEDULE_NOW, GridSimTags.GRIDLET_SUBMIT, cl);

		cloudletsSubmitted++;

		// Notify load balancer
		CloudSimEvent e = new CloudSimEvent(CloudSimEvents.EVENT_CLOUDLET_ALLOCATED_TO_VM);
		e.addParameter(Constants.PARAM_VM_ID, vmId);
		fireCloudSimEvent(e);

		String destName = GridSim.getEntityName(dest);
		InternetEntitityRegistry.getInstance().addCommunicationPath(cl.getOriginator().get_name(), destName);
	}

	@Override
	protected void processVMCreate(Sim_event ev) {
		int[] array = (int[]) ev.get_data();
		int vmId = array[1];

		// If VM creation success
		if (array[2] == GridSimTags.TRUE) {
			double vmStartTime = GridSim.clock();
			double vmEndTime = -1;

			vmUsage.put(vmId, new Double[] { vmStartTime, vmEndTime });
			vmStatesList.put(vmId, VirtualMachineState.AVAILABLE);

			// Log VM creation
			System.out.println("VM " + vmId + " created successfully in " + get_name());

			// If there are any waiting cloudlets in queue, attempt to re-schedule them
			submitWaitingCloudlet();
		} else {
			// Don't try to create VM's further in this DC
			lastVmCreateFailed = true;
		}

		super.processVMCreate(ev);
	}

	public void createNewVm() {
		if (!lastVmCreateFailed) {
			int currentVmCount = vmlist.size();
			int configIndex = currentVmCount % 3; // Weak, moderate, strong

			// Define configurations
			int[] memories = { 512, 1024, 2048 };
			int[] processors = { 1, 2, 4 };
			double[] speeds = { 500, 1000, 2000 };

			VMCharacteristics vm0Char = ((VirtualMachine) vmlist.get(0)).getCharacteristics();

			VMCharacteristics newVmChar = new VMCharacteristics(vmlist.size(), vm0Char.getUserId(), vm0Char.getSize(),
					memories[configIndex], vm0Char.getBw(), processors[configIndex], vm0Char.getPriority(),
					vm0Char.getVmm(), vm0Char.getVMScheduler(), 1);
			newVmChar.setMips((int) speeds[configIndex]);

			VirtualMachine newVm = new VirtualMachine(newVmChar);
			vmlist.add(newVm);

			int[] oldVmMapping = vmMapping;
			vmMapping = Arrays.copyOf(oldVmMapping, oldVmMapping.length + 1);

			super.send(datacenterID[0], GridSimTags.SCHEDULE_NOW, DatacenterTags.VM_CREATE_ACK, newVmChar);

			this.vmsRequested = 1;
			this.vmsAcks = 0;
		}
	}

	/**
	 * @return the regionId
	 */
	public int getRegion() {
		return region;
	}

	public String getDataCenterName() {
		return get_name().substring(0, get_name().indexOf("-Broker"));
	}

	public double getTotalCost() {
		return getDataTransferCost() + getVmCost();
	}

	public double getDataTransferCost() {
		double dataGB = totalData / (1024 * 1024);
		return (dataGB * costPerDataGB);
	}

	/**
	 * Calculates the total cost for all VMs, considering their individual resources
	 * (tiered pricing). The cost for each VM is scaled by the number of its CPUs.
	 * 
	 * @return The total cost of all virtual machines.
	 */
	public double getVmCost() {
		double totalCost = 0.0;
		double now = GridSim.clock();
		// 'costPerVmHour' is treated as the baseline cost for a VM with 1 CPU.
		double baseCostPerHour = this.costPerVmHour;

		// Iterate through each VM that has been used to calculate its individual cost.
		for (Map.Entry<Integer, Double[]> entry : vmUsage.entrySet()) {
			int vmId = entry.getKey();
			Double[] vmAllocationTime = entry.getValue();

			// Retrieve the full VirtualMachine object to access its properties.
			VirtualMachine vm = (VirtualMachine) vmlist.getVMbyID(vmId);
			if (vm == null) {
				System.err.println("Error in getVmCost: Could not find VM with ID " + vmId + " in vmlist.");
				continue;
			}

			// Calculate the duration this specific VM was active.
			double start = vmAllocationTime[0];
			double end;
			if (vmAllocationTime[1] == -1) { // A value of -1 means the VM is still running.
				end = now;
			} else {
				end = vmAllocationTime[1];
			}
			double durationSeconds = end - start;

			// Ensure duration is not negative.
			if (durationSeconds < 0) {
				durationSeconds = 0;
			}

			double durationHours = durationSeconds / Constants.MILLI_SECONDS_TO_HOURS;

			// Determine the cost multiplier based on the VM's resources.
			// Using CPU count directly maps to the weak(1), moderate(2), and strong(4)
			// tiers.
			int cpuCount = vm.getCharacteristics().getCpus();

			// The cost for this VM is its usage duration multiplied by its specific tiered
			// rate.
			double weight = 1;
			switch (cpuCount) {
			case 1:
				weight = 1;
				break;
			case 2:
				weight = 1.3;

				break;
			case 4:
				weight = 1.6;
				break;

			default:
				break;
			}

			double vmTieredCostPerHour = baseCostPerHour * weight;
			double costForThisVm = durationHours * vmTieredCostPerHour;

			totalCost += costForThisVm;
		}

		return totalCost;
	}

	/**
	 * @return the hourlyArrival
	 */
	public HourlyEventCounter getHourlyArrival() {
		return hourlyArrival;
	}

	public void addCloudSimEventListener(CloudSimEventListener l) {
		listeners.add(l);
	}

	public void fireCloudSimEvent(CloudSimEvent e) {
		for (CloudSimEventListener l : listeners) {
			l.cloudSimEventFired(e);
		}
	}

	public void removeCloudSimEventListener(CloudSimEventListener l) {
		listeners.remove(l);
	}

	public Map<Integer, VirtualMachineState> getVmStatesList() {
		return vmStatesList;
	}

	/**
	 * @return the processingTimes
	 */
	public HourlyStat getHourlyProcessingTimes() {
		return hourlyProcessingTimes;
	}

	public Map<Integer, Integer> getVmAllocationStats() {
		if (loadBalancer != null) {
			return loadBalancer.getVmAllocationCounts();
		} else {
			return null;
		}
	}

	/**
	 * @return the allRequestsProcessed
	 */
	public int getAllRequestsProcessed() {
		return allRequestsProcessed;
	}

	public VirtualMachineList getVmList() {
		return vmlist;
	}

	public VmLoadBalancer getLoadBalancer() {
		return loadBalancer;
	}

	public double getVmRam(int vmId) {
		VirtualMachine vm = (VirtualMachine) vmlist.getVMbyID(vmId);
		if (vm != null) {
			return vm.getCharacteristics().getMemory();
		}
		return 0;
	}

	public double getVmBW(int vmId) {
		VirtualMachine vm = (VirtualMachine) vmlist.getVMbyID(vmId);
		if (vm != null) {
			return vm.getCharacteristics().getBw();
		}
		return 0;
	}

	public double getVmStorage(int vmId) {
		VirtualMachine vm = (VirtualMachine) vmlist.getVMbyID(vmId);
		if (vm != null) {
			return vm.getCharacteristics().getSize();
		}
		return 0;
	}

	public int getVmMips(int vmId) {
		VirtualMachine vm = (VirtualMachine) vmlist.getVMbyID(vmId);
		if (vm != null) {
			return vm.getCharacteristics().getMips();
		}
		return 0;
	}

	public int getVmCpus(int vmId) {
		VirtualMachine vm = (VirtualMachine) vmlist.getVMbyID(vmId);
		if (vm != null) {
			return vm.getCharacteristics().getCpus();
		}
		return 0;
	}

}