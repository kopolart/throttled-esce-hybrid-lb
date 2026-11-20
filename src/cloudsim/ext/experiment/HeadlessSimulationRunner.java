package cloudsim.ext.experiment;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cloudsim.ext.Simulation;
import cloudsim.ext.event.CloudSimEvent;
import cloudsim.ext.event.CloudSimEventListener;
import cloudsim.ext.gui.DataCenterUIElement;
import cloudsim.ext.gui.UserBaseUIElement;
import cloudsim.ext.util.IOUtil;

/**
 * Headless entry point for one CloudAnalyst experiment run.
 *
 * <p>Example:</p>
 * <pre>
 * java -cp "build/classes;jars/*" cloudsim.ext.experiment.HeadlessSimulationRunner \
 *   --sim experiments/scenario-1.sim \
 *   --policy "Proposed Method" --lb-scenario 4 \
 *   --seed 1 --expert hybrid-cost-lean \
 *   --out results/online/hybrid-cost-lean/seed-1
 * </pre>
 *
 * <p>Each JVM conducts exactly one simulation because GridSim/SimJava keeps
 * global state. The companion script launches one JVM per run.</p>
 */
public final class HeadlessSimulationRunner implements CloudSimEventListener {

    private static final String USAGE = "Usage: HeadlessSimulationRunner --sim <file.sim> --out <dir> "
            + "[--policy <name>] [--lb-scenario <1-5>] [--seed <long>] [--expert <label>] "
            + "[--run-id <id>] [--round-minutes <n>] [--sla-ms <ms>] [--proc-sla-ms <ms>] "
            + "[--sample-limit <n>]";

    private HeadlessSimulationRunner() {
    }

    public static void main(String[] args) {
        try {
            new HeadlessSimulationRunner().run(args);
        } catch (Throwable t) {
            System.err.println("RUN_FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    @SuppressWarnings("unchecked")
    private void run(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help")) {
            System.out.println(USAGE);
            return;
        }

        File simFile = required(options, "sim");
        File outDir = required(options, "out");

        List<Object> entities = (List<Object>) IOUtil.loadFromXml(simFile);
        List<UserBaseUIElement> userBases = (List<UserBaseUIElement>) entities.get(0);
        List<DataCenterUIElement> dataCenters = (List<DataCenterUIElement>) entities.get(1);
        String duration = (String) entities.get(3);
        String durationUnit = (String) entities.get(4);
        String userGroupingFactor = (String) entities.get(5);
        String dcGroupingFactor = (String) entities.get(6);
        String instructionLength = (String) entities.get(7);
        String serviceBroker = (String) entities.get(8);
        String policy = options.containsKey("policy") ? options.get("policy") : (String) entities.get(9);

        configure(outDir, options, policy);

        Simulation simulation = new Simulation(this);
        simulation.getUserBases().replaceContent(userBases);
        simulation.getDataCenters().replaceContent(dataCenters);
        double durationMs = parseDuration(duration, durationUnit);
        System.setProperty(ExperimentSettings.DURATION_MS, Long.toString((long) durationMs));
        simulation.setSimulationTime(durationMs);
        simulation.setUserGroupingFactor(Integer.parseInt(userGroupingFactor.trim()));
        simulation.setDcRequestGroupingFactor(Integer.parseInt(dcGroupingFactor.trim()));
        simulation.setInstructionLengthPerRequest(Integer.parseInt(instructionLength.trim()));
        simulation.setServiceBrokerPolicy(serviceBroker);
        simulation.setLoadBalancePolicy(policy);

        System.out.println("RUN_START expert=" + ExperimentSettings.expert()
                + " policy=" + policy
                + " seed=" + options.get("seed")
                + " out=" + outDir.getPath());
        simulation.runSimulation();
        System.out.println("RUN_COMPLETE expert=" + ExperimentSettings.expert()
                + " policy=" + policy
                + " seed=" + options.get("seed"));
        System.out.flush();

        // GridSim/SimJava leaves non-daemon threads behind; the run is done.
        System.exit(0);
    }

    private void configure(File outDir, Map<String, String> options, String policy) {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalArgumentException("Cannot create output directory " + outDir.getAbsolutePath());
        }
        System.setProperty(ExperimentSettings.OUT_DIR, outDir.getAbsolutePath());
        String seed = options.get("seed");
        if (seed != null) {
            System.setProperty(ExperimentSettings.SEED, seed);
        }
        String lbScenario = options.get("lb-scenario");
        if (lbScenario != null) {
            System.setProperty("lb.scenario", lbScenario);
        }
        String expert = options.containsKey("expert") ? options.get("expert") : policy;
        String runId = options.containsKey("run-id") ? options.get("run-id")
                : expert + (seed == null ? "" : "-seed" + seed);
        System.setProperty(ExperimentSettings.EXPERT, expert);
        System.setProperty(ExperimentSettings.POLICY, policy);
        System.setProperty(ExperimentSettings.RUN_ID, runId);
        setDefault(ExperimentSettings.ROUND_MINUTES, options.get("round-minutes"), "15");
        setDefault(ExperimentSettings.RESPONSE_SLA_MS, options.get("sla-ms"), "200");
        setDefault(ExperimentSettings.PROCESSING_SLA_MS, options.get("proc-sla-ms"), "50");
        setDefault(ExperimentSettings.TASK_SAMPLE_LIMIT, options.get("sample-limit"), "2000");
    }

    private static void setDefault(String key, String value, String fallback) {
        System.setProperty(key, value == null ? fallback : value);
    }

    private static File required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing --" + key + "\n" + USAGE);
        }
        return new File(value);
    }

    private static double parseDuration(String value, String unit) {
        double duration = Double.parseDouble(value.trim());
        String normalized = unit == null ? "hours" : unit.trim().toLowerCase();
        if (normalized.startsWith("day")) {
            return duration * 86_400_000.0;
        }
        if (normalized.startsWith("hour") || normalized.equals("hr") || normalized.equals("hrs")) {
            return duration * 3_600_000.0;
        }
        if (normalized.startsWith("min")) {
            return duration * 60_000.0;
        }
        if (normalized.startsWith("sec")) {
            return duration * 1_000.0;
        }
        throw new IllegalArgumentException("Unsupported time unit: " + unit);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
                continue;
            }
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg + "\n" + USAGE);
            }
            String key = arg.substring(2);
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + arg + "\n" + USAGE);
            }
            options.put(key, args[++i]);
        }
        return options;
    }

    @Override
    public void cloudSimEventFired(CloudSimEvent e) {
        // Headless runs do not display progress.
    }
}
