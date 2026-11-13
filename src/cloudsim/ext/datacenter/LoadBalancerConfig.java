package cloudsim.ext.datacenter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime configuration for {@link ProposedMethodLoadBalancer}.
 *
 * <p>The per-scenario load thresholds, idle threshold and CPU/RAM weights
 * mirror Table 6 of the paper. Values can be overridden with
 * {@code -Dlb.config=<path>} and the scenario can be selected with
 * {@code -Dlb.scenario=1|2|3|auto}.</p>
 */
public final class LoadBalancerConfig {

    public static final String CONFIG_PATH_PROPERTY = "lb.config";
    public static final String SCENARIO_PROPERTY = "lb.scenario";
    public static final String DEFAULT_CONFIG_PATH = "config/loadbalancer.properties";

    /** Paper Table 6: {low, medium, high, idle seconds}. */
    private static final double[][] PAPER_SCENARIOS = {
            { 30, 40, 50, 120 },
            { 27, 36, 45, 180 },
            { 4, 32, 40, 300 }
    };

    private static final double DEFAULT_CPU_WEIGHT = 0.8;
    private static final double DEFAULT_RAM_WEIGHT = 0.2;
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final double DEFAULT_BUSY_FACTOR = 0.85;
    private static final double DEFAULT_ACTIVE_RATIO = 0.85;
    private static final double DEFAULT_EPSILON = 1.0;

    private final int scenario;
    private final int lowLoadThreshold;
    private final int mediumLoadThreshold;
    private final int highLoadThreshold;
    private final double idleThresholdSeconds;
    private final double cpuWeight;
    private final double ramWeight;
    private final int consecutiveFailureThreshold;
    private final double busyFactorThreshold;
    private final double activeRatio;
    private final double epsilon;
    private final String source;

    private LoadBalancerConfig(int scenario, int low, int medium, int high, double idle,
            double cpuWeight, double ramWeight, int failureThreshold, double busyFactor,
            double activeRatio, double epsilon, String source) {
        this.scenario = scenario;
        this.lowLoadThreshold = low;
        this.mediumLoadThreshold = medium;
        this.highLoadThreshold = high;
        this.idleThresholdSeconds = idle;
        this.cpuWeight = cpuWeight;
        this.ramWeight = ramWeight;
        this.consecutiveFailureThreshold = failureThreshold;
        this.busyFactorThreshold = busyFactor;
        this.activeRatio = activeRatio;
        this.epsilon = epsilon;
        this.source = source;
    }

    public static LoadBalancerConfig load(int vmCount) {
        Properties props = new Properties();
        String source = "built-in paper defaults";
        String configuredPath = System.getProperty(CONFIG_PATH_PROPERTY, DEFAULT_CONFIG_PATH);
        File file = new File(configuredPath);

        if (file.isFile()) {
            source = file.getPath();
            try (InputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (Exception e) {
                System.err.println("[HybridLB] Could not read " + file.getPath() + ": " + e.getMessage());
            }
        } else {
            InputStream in = LoadBalancerConfig.class.getClassLoader()
                    .getResourceAsStream("loadbalancer.properties");
            if (in != null) {
                source = "classpath:loadbalancer.properties";
                try (InputStream stream = in) {
                    props.load(stream);
                } catch (Exception e) {
                    System.err.println("[HybridLB] Could not read classpath config: " + e.getMessage());
                }
            }
        }

        int scenario = resolveScenario(props, vmCount);
        double[] defaults = PAPER_SCENARIOS[scenario - 1];

        int low = readInt(props, "scenario." + scenario + ".lowLoadThreshold", (int) defaults[0]);
        int medium = readInt(props, "scenario." + scenario + ".mediumLoadThreshold", (int) defaults[1]);
        int high = readInt(props, "scenario." + scenario + ".highLoadThreshold", (int) defaults[2]);
        double idle = readDouble(props, "scenario." + scenario + ".idleThresholdSeconds", defaults[3]);

        if (!(0 <= low && low < medium && medium < high)) {
            System.err.println("[HybridLB] Invalid thresholds for scenario " + scenario
                    + " (" + low + ", " + medium + ", " + high + "); using paper values.");
            low = (int) defaults[0];
            medium = (int) defaults[1];
            high = (int) defaults[2];
        }
        if (idle <= 0) {
            idle = defaults[3];
        }

        double cpu = readDouble(props, "weight.cpu", DEFAULT_CPU_WEIGHT);
        double ram = readDouble(props, "weight.ram", DEFAULT_RAM_WEIGHT);
        if (cpu < 0 || cpu > 1 || ram < 0 || ram > 1) {
            System.err.println("[HybridLB] Invalid CPU/RAM weights; using 0.8/0.2.");
            cpu = DEFAULT_CPU_WEIGHT;
            ram = DEFAULT_RAM_WEIGHT;
        }

        int failureThreshold = readInt(props, "scaling.consecutiveFailureThreshold",
                DEFAULT_FAILURE_THRESHOLD);
        double busyFactor = readDouble(props, "scaling.busyFactorThreshold", DEFAULT_BUSY_FACTOR);
        double activeRatio = readDouble(props, "scaling.initialActiveRatio", DEFAULT_ACTIVE_RATIO);
        double epsilon = readDouble(props, "scoring.epsilon", DEFAULT_EPSILON);

        return new LoadBalancerConfig(scenario, low, medium, high, idle, cpu, ram,
                Math.max(1, failureThreshold), clamp01(busyFactor), clamp01(activeRatio),
                Math.max(epsilon, 1e-9), source);
    }

    private static int resolveScenario(Properties props, int vmCount) {
        String value = System.getProperty(SCENARIO_PROPERTY, props.getProperty("lb.scenario", "1")).trim();
        if ("auto".equalsIgnoreCase(value)) {
            int detected = vmCount <= 10 ? 1 : (vmCount <= 16 ? 2 : 3);
            System.out.println("[HybridLB] Auto-detected scenario " + detected + " from " + vmCount
                    + " VMs; set -Dlb.scenario to override.");
            return detected;
        }
        try {
            int scenario = Integer.parseInt(value);
            if (scenario >= 1 && scenario <= PAPER_SCENARIOS.length) {
                return scenario;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the default
        }
        System.err.println("[HybridLB] Unknown scenario '" + value + "'; using scenario 1.");
        return 1;
    }

    private static int readInt(Properties props, String key, int fallback) {
        return (int) readDouble(props, key, fallback);
    }

    private static double readDouble(Properties props, String key, double fallback) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("[HybridLB] Invalid value for " + key + ": " + value + "; using " + fallback);
            return fallback;
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public int getScenario() {
        return scenario;
    }

    public int getLowLoadThreshold() {
        return lowLoadThreshold;
    }

    public int getMediumLoadThreshold() {
        return mediumLoadThreshold;
    }

    public int getHighLoadThreshold() {
        return highLoadThreshold;
    }

    public double getIdleThresholdSeconds() {
        return idleThresholdSeconds;
    }

    public double getCpuWeight() {
        return cpuWeight;
    }

    public double getRamWeight() {
        return ramWeight;
    }

    public int getConsecutiveFailureThreshold() {
        return consecutiveFailureThreshold;
    }

    public double getBusyFactorThreshold() {
        return busyFactorThreshold;
    }

    public double getActiveRatio() {
        return activeRatio;
    }

    public double getEpsilon() {
        return epsilon;
    }

    public String getSource() {
        return source;
    }

    public String summary() {
        return "scenario=" + scenario
                + ", L1=" + lowLoadThreshold
                + ", L2=" + mediumLoadThreshold
                + ", L3=" + highLoadThreshold
                + ", idle=" + idleThresholdSeconds + "s"
                + ", wCPU=" + cpuWeight
                + ", wRAM=" + ramWeight
                + ", CF=" + consecutiveFailureThreshold
                + ", BF=" + busyFactorThreshold
                + ", initialActiveRatio=" + activeRatio
                + ", source=" + source;
    }
}
