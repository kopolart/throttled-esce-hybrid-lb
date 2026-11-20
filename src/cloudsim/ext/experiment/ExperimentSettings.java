package cloudsim.ext.experiment;

/**
 * System-property based settings shared by the headless experiment runner,
 * the seeded workload generator and the CSV metrics logger.
 *
 * <p>All accessors return safe defaults when the corresponding property is
 * absent, so normal GUI runs are unaffected.</p>
 */
public final class ExperimentSettings {

    public static final String SEED = "sim.seed";
    public static final String OUT_DIR = "sim.outDir";
    public static final String RUN_ID = "sim.runId";
    public static final String EXPERT = "sim.expert";
    public static final String POLICY = "sim.policy";
    public static final String ROUND_MINUTES = "sim.roundMinutes";
    public static final String RESPONSE_SLA_MS = "sim.slaMs";
    public static final String PROCESSING_SLA_MS = "sim.procSlaMs";
    public static final String TASK_SAMPLE_LIMIT = "sim.taskSampleLimit";
    public static final String DURATION_MS = "sim.durationMs";

    private ExperimentSettings() {
    }

    /** Returns the configured seed, or {@code null} when the run is unseeded. */
    public static Long seedOrNull() {
        return parseLong(SEED);
    }

    /**
     * Derives a distinct but reproducible seed for one stochastic stream.
     * The workload generator uses one stream for user counts and one for
     * request inter-arrival delays.
     */
    public static long derivedSeed(int stream) {
        Long seed = seedOrNull();
        if (seed == null) {
            return System.nanoTime() + stream;
        }
        return seed + 2L * stream;
    }

    public static boolean loggingEnabled() {
        return !outDir().isEmpty();
    }

    public static String outDir() {
        return System.getProperty(OUT_DIR, "").trim();
    }

    public static String runId() {
        return System.getProperty(RUN_ID, "run").trim();
    }

    public static String expert() {
        return System.getProperty(EXPERT, "").trim();
    }

    public static String policy() {
        return System.getProperty(POLICY, "").trim();
    }

    public static long roundMillis() {
        return Math.max(1L, longProperty(ROUND_MINUTES, 15L)) * 60_000L;
    }

    public static double responseSlaMs() {
        return doubleProperty(RESPONSE_SLA_MS, 200.0);
    }

    public static double processingSlaMs() {
        return doubleProperty(PROCESSING_SLA_MS, 50.0);
    }

    public static int taskSampleLimit() {
        return (int) Math.max(0L, longProperty(TASK_SAMPLE_LIMIT, 2000L));
    }

    /** Simulation horizon in milliseconds; tasks completing later are ignored. */
    public static long durationMs() {
        return longProperty(DURATION_MS, Long.MAX_VALUE);
    }

    private static Long parseLong(String key) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("[Experiment] Invalid " + key + "=" + value + "; ignoring it.");
            return null;
        }
    }

    private static long longProperty(String key, long fallback) {
        Long value = parseLong(key);
        return value == null ? fallback : value.longValue();
    }

    private static double doubleProperty(String key, double fallback) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("[Experiment] Invalid " + key + "=" + value + "; using " + fallback);
            return fallback;
        }
    }
}
