// --- START OF FILE CloudSimEvents.java ---

package cloudsim.ext.event;

public interface CloudSimEvents {

	public static final int EVENT_SIMULATION_ENDED = 1000;
	
	public static final int EVENT_LIST_CONTENT_CHANGED = 2001;
	public static final int EVENT_LIST_CONTENT_ADDED = 2002;
	public static final int EVENT_LIST_CONTENT_REMOVED = 2003;
	
	public static final int EVENT_NEW_COMM_PATH = 3001;
	public static final int EVENT_VM_FINISHED_CLOUDLET = 3003;
	public static final int EVENT_CLOUDLET_ALLOCATED_TO_VM = 1001;
	public static final int EVENT_VM_DESTROYED = 1003;
	final int EVENT_PROGRESS_UPDATE = 4000;
	public static final int EVENT_VM_SHUTDOWN = 6;
	public static final int EVENT_VM_STARTUP = 7;
	
	// *** ADDED CONSTANTS FOR EVENT PARAMETERS ***
	public static final String PARAM_VM_ID = "vmId";
	public static final String PARAM_CLOUDLET_ID = "cloudletId";
}
// --- END OF FILE CloudSimEvents.java ---