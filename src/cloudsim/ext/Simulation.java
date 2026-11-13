package cloudsim.ext;

import eduni.simjava.Sim_stat;
import eduni.simjava.Sim_system;
import gridsim.GridSim;
import gridsim.MachineList;
import gridsim.PE;
import gridsim.PEList;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.swing.JOptionPane;

import cloudsim.Cloudlet;
import cloudsim.CloudletList;
import cloudsim.DataCenter;
import cloudsim.DatacenterCharacteristics;
import cloudsim.Host;
import cloudsim.SimpleBWProvisioner;
import cloudsim.SimpleMemoryProvisioner;
import cloudsim.SimpleVMProvisioner;
import cloudsim.SpaceSharedAllocationPolicy;
import cloudsim.SpaceSharedVMScheduler;
import cloudsim.TimeSharedAllocationPolicy;
import cloudsim.TimeSharedVMScheduler;
import cloudsim.TimeSharedWithPriorityAllocationPolicy;
import cloudsim.TimeSpaceSharedAllocationPolicy;
import cloudsim.VMCharacteristics;
import cloudsim.VMMAllocationPolicy;
import cloudsim.VirtualMachine;
import cloudsim.VirtualMachineList;
import cloudsim.ext.datacenter.DatacenterController;
import cloudsim.ext.event.BaseCloudSimObservable;
import cloudsim.ext.event.CloudSimEvent;
import cloudsim.ext.event.CloudSimEventListener;
import cloudsim.ext.event.CloudSimEvents;
import cloudsim.ext.gui.DataCenterUIElement;
import cloudsim.ext.gui.MachineUIElement;
import cloudsim.ext.gui.UserBaseUIElement;
import cloudsim.ext.gui.VmAllocationUIElement;
import cloudsim.ext.gui.utils.SimMeasure;
import cloudsim.ext.servicebroker.BestResponseTimeServiceBroker;
import cloudsim.ext.servicebroker.CloudAppServiceBroker;
import cloudsim.ext.servicebroker.DynamicServiceBroker;
import cloudsim.ext.servicebroker.ServiceProximityServiceBroker;
import cloudsim.ext.stat.HourlyEventCounter;
import cloudsim.ext.util.InternetEntitityRegistry;
import cloudsim.ext.util.ObservableList;

/**
 * Main controller class of the simulation.
 * 
 * @author Bhathiya Wickremasinghe
 *
 */
public class Simulation extends BaseCloudSimObservable implements Constants {

	// private static CloudletList cloudletList;
	private static VirtualMachineList vmlist;

	private final ObservableList<DataCenterUIElement> dataCenters;
	private List<DatacenterController> dcbs;
	private List<DataCenter> dcs;
	private final ObservableList<UserBaseUIElement> userBases;
	private List<UserBase> ubs;
	private double simulationTime;
	private String serviceBrokerPolicy = Constants.BROKER_POLICY_PROXIMITY;
	private String loadBalancePolicy = Constants.LOAD_BALANCE_POLICY_RR;
	private int userGroupingFactor = 10;
	private int dcRequestGroupingFactor = 10;
	private int instructionLengthPerRequest = 100;
	private CloudSimEventListener progressListener;
	private Map<String, Object> results;
	private Internet internet;

	/** Constructor. */
	public Simulation(CloudSimEventListener gui) throws Exception {

		this.progressListener = gui;
		addCloudSimEventListener(gui);

		dataCenters = new ObservableList<DataCenterUIElement>();
		userBases = new ObservableList<UserBaseUIElement>();

		InternetEntitityRegistry.initialize(userBases, dataCenters);

		createDefaultSimulation();
	}

	/**
	 * Populates the initial default simulation displayed at the start.
	 */
	private void createDefaultSimulation() {
		DataCenterUIElement dataCenter = new DataCenterUIElement(DEFAULT_DATA_CENTER_NAME, DEFAULT_DC_REGION,
				DEFAULT_ARCHITECTURE, DEFAULT_OS, DEFAULT_VMM, DEFAULT_COST_PER_PROC, DEFAULT_COST_PER_MEM,
				DEFAULT_COST_PER_STOR, DEFAULT_COST_PER_BW);
		MachineUIElement machine1 = new MachineUIElement(DEFAULT_MC_MEMORY, DEFAULT_MC_STORAGE, DEFAULT_MC_BW,
				DEFAULT_MC_PROCESSORS, DEFAULT_MC_SPEED, MachineUIElement.VmAllocationPolicy.TIME_SHARED);
		MachineUIElement machine2 = new MachineUIElement(DEFAULT_MC_MEMORY, DEFAULT_MC_STORAGE, DEFAULT_MC_BW,
				DEFAULT_MC_PROCESSORS, DEFAULT_MC_SPEED, MachineUIElement.VmAllocationPolicy.TIME_SHARED);
		List<MachineUIElement> machineList = new ArrayList<MachineUIElement>();
		machineList.add(machine1);
		machineList.add(machine2);
		dataCenter.setMachineList(machineList);
		dataCenters.add(dataCenter);
		VmAllocationUIElement vmAllocation = new VmAllocationUIElement(dataCenter, DEFAULT_VM_COUNT,
				DEFAULT_VM_IMAGE_SIZE, DEFAULT_VM_MEMORY, DEFAULT_VM_BW);
		dataCenter.setVmAllocation(vmAllocation);

		userBases.add(new UserBaseUIElement(DEFAULT_USER_BASE_NAME, DEFAULT_UB_REGION, DEFAULT_REQ_PER_USER_PER_HR,
				DEFAULT_REQ_SIZE, DEFAULT_PEAK_HOURS, DEFAULT_PEAK_USERS, DEFAULT_OFFPEAK_USERS));
		simulationTime = 3600000.0;

	}

	/**
	 * Creates and runs the simulation from the configuration obtained by the GUI.
	 */
	public void runSimulation() throws Exception {
		System.out.println("Starting Simulation...");

		// Set up stuff
		int num_user = 1; // number of grid users
		Calendar calendar = Calendar.getInstance();
		boolean trace_flag = false; // mean trace GridSim events
		String[] exclude_from_file = { "" };
		String[] exclude_from_processing = { "" };
		String report_name = null;

		// Initialize GridSim
		GridSim.init(num_user, calendar, trace_flag, exclude_from_file, exclude_from_processing, report_name);

		// Create Datacenters and Controllers
		dcbs = new ArrayList<DatacenterController>();
		dcs = new ArrayList<DataCenter>();
		for (DataCenterUIElement d : dataCenters) {
			if (d.isAllocated()) {
				DataCenter dc = createDatacenter(d);
				DatacenterController controller = createBroker(d.getName(), d.getRegion(), d.getCostPerProcessor(),
						d.getCostPerBw());
				dcbs.add(controller);
				dcs.add(dc);

				int brokerId = controller.get_id();
				vmlist = createVM(brokerId, d.getVmAllocation().getVmCount());
				if (vmlist != null && vmlist.size() > 0) {

					for (int i = 0; i < vmlist.size(); i++) {
						VirtualMachine vm = (VirtualMachine) vmlist.get(i);

						int memory = vm.getCharacteristics().getMemory();
						int cpu = vm.getCharacteristics().getCpus();
						long bw = vm.getCharacteristics().getBw();
						long storage = vm.getCharacteristics().getSize();
						long mips = vm.getCharacteristics().getMips();

						int vmId = vm.getVmId();
						System.out.println("VM (ID: " + vmId + ") Memory: " + memory + " MB");
						System.out.println("VM (ID: " + vmId + ") Cpu: " + cpu);
						System.out.println("VM (ID: " + vmId + ") Bw: " + bw);
						System.out.println("VM (ID: " + vmId + ") Storage: " + storage);
						System.out.println("VM (ID: " + vmId + ") Mips: " + mips);

					}
				}

				controller.submitVMList(vmlist);
			}
		}

		// Create user bases
		ubs = new ArrayList<UserBase>();
		for (UserBaseUIElement ub : userBases) {
			UserBase userBase = new UserBase(ub.getName(), ub.getRegion(), ub.getReqPerHrPerUser(),
					new int[] { ub.getPeakHoursStart(), ub.getPeakHoursEnd() }, ub.getPeakUserCount(),
					ub.getOffPeakUserCount(), ub.getReqSize(), userGroupingFactor, instructionLengthPerRequest,
					this.loadBalancePolicy);
			ubs.add(userBase);
		}

		// The Internet
		internet = new Internet(progressListener);

		CloudAppServiceBroker serviceBroker;
		if (serviceBrokerPolicy.equals(Constants.BROKER_POLICY_PROXIMITY)) {
			serviceBroker = new ServiceProximityServiceBroker();
		} else if (serviceBrokerPolicy.equals(Constants.BROKER_POLICY_DYNAMIC)) {
			serviceBroker = new DynamicServiceBroker(dcbs);
		} else {
			serviceBroker = new BestResponseTimeServiceBroker();
		}
		internet.addServiceBroker(DEFAULT_APP_ID, serviceBroker);

		// Set the simulation duration
		Sim_system.set_termination_condition(Sim_system.TIME_ELAPSED, simulationTime, false);

		// Start the simulation
		GridSim.startGridSimulation();

		// Comes here when the simulation has completed.
		// Gather the results and package them for the results screen
		results = new HashMap<String, Object>();
		results.put(Constants.SIMULATION_COMPLETED_TIME, new Date());

		Map<String, HourlyEventCounter> dcArrivalStats = new HashMap<String, HourlyEventCounter>();
		Map<String, HourlyEventCounter> dcLoadingStats = new HashMap<String, HourlyEventCounter>();
		Map<String, SimMeasure> dcProcTimes = new TreeMap<String, SimMeasure>();
		Map<String, Map<String, Double>> costs = new HashMap<String, Map<String, Double>>();
		HourlyEventCounter hrlyArrivalStat = null;
		double vmCost, dataCost, totalCost;

		for (DatacenterController dcb : dcbs) {
			hrlyArrivalStat = dcb.getHourlyArrival();
			String dcName = dcb.get_name().substring(0, dcb.get_name().indexOf("-Broker"));
			String dcbName = dcName;
			dcArrivalStats.put(dcbName, hrlyArrivalStat);

			Map<String, Double> dcCosts = new HashMap<String, Double>();
			vmCost = dcb.getVmCost();
			dcCosts.put(Constants.VM_COST, vmCost);
			dataCost = dcb.getDataTransferCost();
			dcCosts.put(Constants.DATA_COST, dataCost);
			totalCost = vmCost + dataCost;
			dcCosts.put(Constants.TOTAL_COST, totalCost);

			costs.put(dcName, dcCosts);

			Sim_stat stat = dcb.get_stat();
			List res = stat.get_measures();
			for (Object o : res) {
				Object[] oArray = (Object[]) o;
				String measure = (String) oArray[0];

				SimMeasure m = new SimMeasure();
				m.setName(measure);
				m.setEntityName(dcName);
				m.setType(MEASURE_TYPE_DC_PROCESSING_TIME);
				m.setAvg(stat.average(measure));
				m.setMin(stat.minimum(measure));
				m.setMax(stat.maximum(measure));
				m.setCount(dcb.getAllRequestsProcessed());

				dcProcTimes.put(dcName + "||" + measure, m);
			}

			printVmAllocations(dcb);
		}
		results.put(Constants.DC_PROCESSING_TIME_STATS, dcProcTimes);
		results.put(Constants.DC_ARRIVAL_STATS, dcArrivalStats);
		results.put(Constants.DC_OVER_LOADING_STATS, dcLoadingStats);
		results.put(Constants.COSTS, costs);

		for (DataCenter dc : dcs) {
			dc.printDebts();
		}

		Map<String, SimMeasure> ubResults = new TreeMap<String, SimMeasure>();
		for (UserBase ub : ubs) {
			Sim_stat stat = ub.get_stat();
			String ubName = ub.get_name();

			List res = stat.get_measures();
			for (Object o : res) {
				Object[] oArray = (Object[]) o;
				String measure = (String) oArray[0];

				SimMeasure m = new SimMeasure();
				m.setName(measure);
				m.setEntityName(ubName);
				m.setType(MEASURE_TYPE_USER_BASE_RESPONSE);
				m.setAvg(stat.average(measure));
				m.setMin(stat.minimum(measure));
				m.setMax(stat.maximum(measure));
				m.setCount(ub.getResponsesReceived());

				ubResults.put(ubName + "||" + measure, m);
			}
		}
		results.put(Constants.UB_STATS, ubResults);

		// Finish off simulation
		System.out.println("Simulation finished at " + GridSim.clock());
		CloudSimEvent cloudSimEvent = new CloudSimEvent(CloudSimEvents.EVENT_SIMULATION_ENDED);
		fireCloudSimEvent(cloudSimEvent);

	}

	private String getVmTier(VirtualMachine vm) {
		if (vm == null) {
			return "Unknown";
		}

		int cpus = vm.getCharacteristics().getCpus();

		// These conditions match the "Powerful", "Moderate", and "Weak" VMs
		// you create in the createVM() method.
		if (cpus == 4) {
			return "Strong";
		} else if (cpus == 2) {
			return "Moderate";
		} else {
			return "Weak";
		}
	}

	private void printVmAllocations(DatacenterController dcb) {
		String dcName = dcb.getDataCenterName();
		Map<Integer, Integer> allocStats = dcb.getVmAllocationStats();

		System.out.println("************ Vm allocations in " + dcName);
		if (allocStats == null || allocStats.isEmpty()) {
			System.out.println("Vm allocation data not available for " + dcName);
			return;
		}

		// Print the detailed, per-VM allocation counts first
		new TreeMap<Integer, Integer>(allocStats).forEach((vmId, count) -> {
			System.out.println(vmId + "->" + count);
		});

		// --- Start of universal tier summary logic ---
		int strongAllocations = 0;
		int moderateAllocations = 0;
		int weakAllocations = 0;
		int totalAllocations = 0;

		// Get the full list of VMs to check their properties
		VirtualMachineList vmlist = dcb.getVmList();
		if (vmlist == null) {
			System.out.println("Could not retrieve VM list for tier summary.");
			return;
		}

		// Create a lookup map for faster access to VM objects
		Map<Integer, VirtualMachine> vmMap = new HashMap<>();
		for (Object vmObj : vmlist) {
			VirtualMachine vm = (VirtualMachine) vmObj;
			vmMap.put(vm.getVmId(), vm);
		}

		// Iterate through the allocation stats and sum them by tier
		for (Map.Entry<Integer, Integer> entry : allocStats.entrySet()) {
			int vmId = entry.getKey();
			int count = entry.getValue();
			totalAllocations += count;

			VirtualMachine vm = vmMap.get(vmId);
			String tier = getVmTier(vm);

			switch (tier) {
			case "Strong":
				strongAllocations += count;
				break;
			case "Moderate":
				moderateAllocations += count;
				break;
			case "Weak":
				weakAllocations += count;
				break;
			}
		}

		// Print the final formatted summary
		System.out.println("------ Tiered Allocation Summary for " + dcName + " ------");
		System.out.println("  Strong VMs allocated   : " + strongAllocations);
		System.out.println("  Moderate VMs allocated : " + moderateAllocations);
		System.out.println("  Weak VMs allocated     : " + weakAllocations);
		System.out.println("  --------------------------------------");
		System.out.println("  Total allocations      : " + totalAllocations);
		System.out.println("**********************************************************");
	}

	@SuppressWarnings("unchecked")
	private VirtualMachineList createVM(int userID, int vms) {
		VirtualMachineList list = new VirtualMachineList();
		long bw = 1000;
		int priority = 1;
		String vmm = "Xen";

		// Define VM configurations - make differences more significant
		int[] memories = { 512, 1024, 2048 }; // Memory in MB
		int[] processors = { 1, 2, 4 }; // Number of processors
		double[] speeds = { 1000, 2000, 4000 }; // Processor speed in MIPS
		int[] storages = { 10000, 20000, 40000 }; // Storage

		VirtualMachine[] vmArray = new VirtualMachine[vms];
		for (int i = 0; i < vms; i++) {
			int configIndex = i % 3; // Weak, moderate, strong
			int memory = memories[configIndex];
			int storage = storages[configIndex];
			int vcpus = processors[configIndex];
			double speed = speeds[configIndex];

			VMCharacteristics vmChar = new VMCharacteristics(i, userID, storage, memory, bw, vcpus, priority, vmm,
					new TimeSharedVMScheduler(), 1);
			vmChar.setMips((int) speed);

			vmArray[i] = new VirtualMachine(vmChar);
			list.add(vmArray[i]);
		}
		return list;
	}

	// Replace the entire method with this corrected version

	@SuppressWarnings("unchecked")
	private DataCenter createDatacenter(DataCenterUIElement dc) {

		MachineList mList = new MachineList();

		// Define MIPS ratings for different host tiers
		final int STRONG_HOST_MIPS = 40000;
		final int MODERATE_HOST_MIPS = 20000;
		final int WEAK_HOST_MIPS = 10000;

		final int STRONG_HOST_CORES = 16;
		final int MODERATE_HOST_CORES = 8;
		final int WEAK_HOST_CORES = 4;

		int hostId = 0;
		for (MachineUIElement mc : dc.getMachineList()) {
			// --- START of new heterogeneous host logic ---
			int currentMips;
			int currentCores;
			int hostType = hostId % 3; // Simple round-robin for host types: 0=Strong, 1=Moderate, 2=Weak

			switch (hostType) {
			case 0: {
				currentMips = STRONG_HOST_MIPS;
				currentCores = STRONG_HOST_CORES;
			}
				break;
			case 1: {
				currentMips = MODERATE_HOST_MIPS;
				currentCores = MODERATE_HOST_CORES;
			}
				break;
			default: {
				currentMips = WEAK_HOST_MIPS;
				currentCores = WEAK_HOST_CORES;
			}
				break;
			}

			PEList peList = new PEList();
			// The UI element specifies # of processors, we specify their speed here
			for (int i = 0; i < currentCores; i++) {
				peList.add(new PE(i, currentMips)); // Use the tiered MIPS rating
			}
			// --- END of new heterogeneous host logic ---

			// --- START of CORRECTED vmPolicy logic ---
			VMMAllocationPolicy vmPolicy = new SpaceSharedAllocationPolicy(peList); // This is the full, exhaustive
																					// if-else-if structure from your
																					// original code.
			// It guarantees that vmPolicy is always initialized.
			if (mc.getVmAllocationPolicy().equals(MachineUIElement.VmAllocationPolicy.TIME_SHARED)) {
				vmPolicy = new TimeSharedAllocationPolicy(peList);
			} else if (mc.getVmAllocationPolicy().equals(MachineUIElement.VmAllocationPolicy.SPACE_SHARED)) {
				vmPolicy = new SpaceSharedAllocationPolicy(peList);
			} else if (mc.getVmAllocationPolicy().equals(MachineUIElement.VmAllocationPolicy.TIME_SHARED_W_PRIORITY)) {
				vmPolicy = new TimeSharedWithPriorityAllocationPolicy(peList);
			} else {
				vmPolicy = new TimeSpaceSharedAllocationPolicy(peList);
			}
			// --- END of CORRECTED vmPolicy logic ---

			Host h = new Host(hostId, // Use the iterating hostId
					mc.getMemory(), mc.getStorage(), mc.getBw(), peList, new SimpleMemoryProvisioner(),
					new SimpleBWProvisioner(), vmPolicy);

			mList.add(h);

			System.out.println("  Creating Host #" + h.getMachineID() + " with " + peList.size() + " PEs @ "
					+ currentMips + " MIPS each.");

			hostId++; // Increment the ID for the next host
		}

		double time_zone = WorldGeometry.getInstance().getTimeZone(dc.getRegion());
		LinkedList storageList = new LinkedList(); // we are not adding SAN devices by now

		DatacenterCharacteristics resConfig = new DatacenterCharacteristics(dc.getArchitecture(), dc.getOs(),
				dc.getVmm(), mList, time_zone, dc.getCostPerProcessor(), dc.getCostPerMem(), dc.getCostPerStorage(),
				dc.getCostPerBw());
		DataCenter datacenter = null;
		try {
			datacenter = new DataCenter(dc.getName(), resConfig, new SimpleVMProvisioner(), storageList);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return datacenter;
	}

	// We strongly encourage users to develop their own broker policies, to submit
	// vms and cloudlets according
	// to the specific rules of the simulated scenario
	private DatacenterController createBroker(String namePrefix, int region, double costPerVmHour,
			double costPerDataGB) {

		DatacenterController broker = null;
		try {
			broker = new DatacenterController(namePrefix, region, costPerVmHour, costPerDataGB, dcRequestGroupingFactor,
					loadBalancePolicy);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		return broker;
	}

	/**
	 * Prints the Cloudlet objects
	 * 
	 * @param list list of Cloudlets
	 */
	private static void printCloudletList(CloudletList list) {
		int size = list.size();
		Cloudlet cloudlet;

		String indent = "    ";
		System.out.println();
		System.out.println("========== OUTPUT ==========");
		System.out.println("Cloudlet ID" + indent + "STATUS" + indent + "Resource ID" + indent + "VM ID" + indent
				+ indent + indent + "Time" + indent + "Start Time" + indent + "Finish Time");

		for (int i = 0; i < size; i++) {
			cloudlet = (Cloudlet) list.get(i);
			System.out.print(indent + cloudlet.getGridletID() + indent + indent);

			if (cloudlet.getGridletStatus() == Cloudlet.SUCCESS) {
				System.out.print("SUCCESS");

				DecimalFormat dft = new DecimalFormat("###.##");
				System.out.println(indent + indent + cloudlet.getResourceID() + indent + indent + indent
						+ cloudlet.getVmId() + indent + indent + indent + dft.format(cloudlet.getActualCPUTime())
						+ indent + indent + dft.format(cloudlet.getExecStartTime()) + indent
						+ dft.format(cloudlet.getFinishTime()));
			}
		}

	}

	/**
	 * @return the simulationTime
	 */
	public double getSimulationTime() {
		return simulationTime;
	}

	/**
	 * @param simulationTime the simulationTime to set
	 */
	public void setSimulationTime(double simulationTime) {
		System.out.println("simulation time =" + simulationTime + "ms");
		this.simulationTime = simulationTime;
	}

	/**
	 * @return the dataCenters
	 */
	public ObservableList<DataCenterUIElement> getDataCenters() {
		return dataCenters;
	}

	/**
	 * @return the userBases
	 */
	public ObservableList<UserBaseUIElement> getUserBases() {
		return userBases;
	}

	/**
	 * @return the results
	 */
	public Map<String, Object> getResults() {
		return results;
	}

	public boolean isRunning() {
		return internet.isRunning();
	}

	/**
	 * @return the userGroupingFactor
	 */
	public int getUserGroupingFactor() {
		return userGroupingFactor;
	}

	/**
	 * @param userGroupingFactor the userGroupingFactor to set
	 */
	public void setUserGroupingFactor(int userGroupingFactor) {
		this.userGroupingFactor = userGroupingFactor;
	}

	/**
	 * @return the dcCloudletGroupingFactor
	 */
	public int getDcRequestGroupingFactor() {
		return dcRequestGroupingFactor;
	}

	/**
	 * @param dcCloudletGroupingFactor the dcCloudletGroupingFactor to set
	 */
	public void setDcRequestGroupingFactor(int dcCloudletGroupingFactor) {
		this.dcRequestGroupingFactor = dcCloudletGroupingFactor;
	}

	/**
	 * @return the instructionLengthPerRequest
	 */
	public int getInstructionLengthPerRequest() {
		return instructionLengthPerRequest;
	}

	/**
	 * @param instructionLengthPerRequest the instructionLengthPerRequest to set
	 */
	public void setInstructionLengthPerRequest(int instructionLengthPerRequest) {
		this.instructionLengthPerRequest = instructionLengthPerRequest;
	}

	public void cancelSimulation() {
		for (UserBase ub : ubs) {
			ub.cancelRun();
		}
	}

	/**
	 * @param serviceBrokerPolicy the serviceBrokerPolicy to set
	 */
	public void setServiceBrokerPolicy(String serviceBrokerPolicy) {
		this.serviceBrokerPolicy = serviceBrokerPolicy;
	}

	/**
	 * @return the serviceBrokerPolicy
	 */
	public String getServiceBrokerPolicy() {
		return serviceBrokerPolicy;
	}

	/**
	 * @return the loadBalancePolicy
	 */
	public String getLoadBalancePolicy() {
		return loadBalancePolicy;
	}

	/**
	 * @param loadBalancePolicy the loadBalancePolicy to set
	 */
	public void setLoadBalancePolicy(String loadBalancePolicy) {
		this.loadBalancePolicy = loadBalancePolicy;
	}

//	public static void main(String[] args) throws Exception{
//		Simulation sim = new Simulation(null);
//		sim.testClassLoading();
//	}
}