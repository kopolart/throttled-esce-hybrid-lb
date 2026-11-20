package cloudsim.ext.experiment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import cloudsim.ext.Constants;
import cloudsim.ext.gui.utils.SimMeasure;

/**
 * Optional CSV instrumentation for experiment runs.
 *
 * <p>When {@code -Dsim.outDir=<dir>} is set, the logger writes three files:</p>
 * <ul>
 *   <li>{@code rounds.csv} - one row per simulated round (default 15 minutes),</li>
 *   <li>{@code summary.csv} - one row per run, with costs and overall metrics,</li>
 *   <li>{@code tasks_sample.csv} - the first N completed cloudlets.</li>
 * </ul>
 *
 * <p>When the property is absent every method is a no-op, so the GUI and the
 * published scenarios keep their original behaviour.</p>
 */
public final class ExperimentMetrics {

    private static final ExperimentMetrics INSTANCE = new ExperimentMetrics();

    private final TreeMap<Integer, RoundStats> rounds = new TreeMap<Integer, RoundStats>();
    private final List<Double> allProcessing = new ArrayList<Double>();
    private final List<Double> allResponse = new ArrayList<Double>();

    private long totalCloudlets;
    private long totalRequests;
    private long totalResponses;
    private long totalResponseRequests;
    private double responseWeightedSum;
    private long responseWeight;
    private long processingSlaViolations;
    private long responseSlaViolations;
    private double maxFinishTime;

    private BufferedWriter roundWriter;
    private BufferedWriter taskWriter;
    private boolean finished;
    private int sampleWritten;

    private ExperimentMetrics() {
    }

    public static ExperimentMetrics getInstance() {
        return INSTANCE;
    }

    /**
     * Records one completed cloudlet (the simulator's task unit).
     *
     * @param vmId           virtual machine that executed the cloudlet
     * @param startTime      cloudlet execution start, in simulation milliseconds
     * @param finishTime     cloudlet completion time, in simulation milliseconds
     * @param processingTime observed processing time, in milliseconds
     * @param requestCount   number of user requests represented by the cloudlet
     */
    public synchronized void recordCloudlet(int vmId, double startTime, double finishTime,
            double processingTime, int requestCount) {
        if (!ExperimentSettings.loggingEnabled() || finished
                || finishTime > ExperimentSettings.durationMs()) {
            return;
        }
        ensureOpen();

        int round = roundIndex(finishTime);
        RoundStats stats = roundStats(round);
        stats.cloudlets++;
        stats.requests += requestCount;
        stats.processingSum += processingTime;
        stats.processingMax = Math.max(stats.processingMax, processingTime);
        stats.processing.add(processingTime);
        if (processingTime > ExperimentSettings.processingSlaMs()) {
            stats.processingSlaViolations++;
        }

        totalCloudlets++;
        totalRequests += requestCount;
        allProcessing.add(processingTime);
        maxFinishTime = Math.max(maxFinishTime, finishTime);
        if (processingTime > ExperimentSettings.processingSlaMs()) {
            processingSlaViolations++;
        }

        if (taskWriter != null && sampleWritten < ExperimentSettings.taskSampleLimit()) {
            writeLine(taskWriter, String.format(Locale.ROOT,
                    "%s,%d,%.3f,%d,%.3f,%.3f,%d",
                    ExperimentSettings.runId(), Integer.valueOf(round), Double.valueOf(finishTime),
                    Integer.valueOf(vmId), Double.valueOf(startTime), Double.valueOf(processingTime),
                    Integer.valueOf(requestCount)));
            sampleWritten++;
        }
    }

    /**
     * Records one completed end-to-end request group at the user base.
     *
     * @param finishTime   completion time, in simulation milliseconds
     * @param responseTime end-to-end response time, in milliseconds
     * @param requestCount number of user requests in the group
     */
    public synchronized void recordResponse(double finishTime, double responseTime, int requestCount) {
        if (!ExperimentSettings.loggingEnabled() || finished
                || finishTime > ExperimentSettings.durationMs()) {
            return;
        }
        ensureOpen();

        int round = roundIndex(finishTime);
        RoundStats stats = roundStats(round);
        stats.responses++;
        stats.responseRequests += requestCount;
        stats.responseWeightedSum += responseTime * requestCount;
        stats.responseWeight += requestCount;
        stats.responseMax = Math.max(stats.responseMax, responseTime);
        stats.response.add(responseTime);
        if (responseTime > ExperimentSettings.responseSlaMs()) {
            stats.responseSlaViolations++;
        }

        totalResponses++;
        totalResponseRequests += requestCount;
        responseWeightedSum += responseTime * requestCount;
        responseWeight += requestCount;
        allResponse.add(responseTime);
        maxFinishTime = Math.max(maxFinishTime, finishTime);
        if (responseTime > ExperimentSettings.responseSlaMs()) {
            responseSlaViolations++;
        }
    }

    /**
     * Flushes per-round data and writes the run summary. Called once at the
     * end of {@code Simulation.runSimulation()}.
     */
    public synchronized void finish(Map<String, Object> results) {
        if (!ExperimentSettings.loggingEnabled() || finished) {
            return;
        }
        finished = true;
        ensureOpen();

        writeRounds();
        writeSummary(results);
        writeRunProperties();

        closeQuietly(roundWriter);
        closeQuietly(taskWriter);
    }

    /** Clears all state. Intended for unit tests and repeated in-process runs. */
    public synchronized void reset() {
        closeQuietly(roundWriter);
        closeQuietly(taskWriter);
        rounds.clear();
        allProcessing.clear();
        allResponse.clear();
        totalCloudlets = 0;
        totalRequests = 0;
        totalResponses = 0;
        totalResponseRequests = 0;
        responseWeightedSum = 0;
        responseWeight = 0;
        processingSlaViolations = 0;
        responseSlaViolations = 0;
        maxFinishTime = 0;
        sampleWritten = 0;
        roundWriter = null;
        taskWriter = null;
        finished = false;
    }

    private void ensureOpen() {
        if (roundWriter != null) {
            return;
        }
        File dir = new File(ExperimentSettings.outDir());
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("[Experiment] Could not create output directory " + dir.getAbsolutePath());
            return;
        }
        try {
            roundWriter = writer(new File(dir, "rounds.csv"));
            writeLine(roundWriter, "run_id,expert,seed,round,start_ms,end_ms,cloudlets,requests,"
                    + "mean_proc_ms,p95_proc_ms,max_proc_ms,proc_sla_violations,proc_sla_rate,"
                    + "responses,response_requests,mean_response_ms,p95_response_ms,max_response_ms,"
                    + "response_sla_violations,response_sla_rate");
            if (ExperimentSettings.taskSampleLimit() > 0) {
                taskWriter = writer(new File(dir, "tasks_sample.csv"));
                writeLine(taskWriter, "run_id,round,finish_ms,vm_id,start_ms,processing_ms,requests");
            }
        } catch (IOException e) {
            System.err.println("[Experiment] Could not open CSV output: " + e.getMessage());
            closeQuietly(roundWriter);
            roundWriter = null;
        }
    }

    private void writeRounds() {
        int maxRound = rounds.isEmpty() ? 0 : rounds.lastKey().intValue();
        long roundMillis = ExperimentSettings.roundMillis();
        for (int round = 0; round <= maxRound; round++) {
            RoundStats stats = rounds.get(Integer.valueOf(round));
            if (stats == null) {
                stats = new RoundStats();
            }
            writeLine(roundWriter, String.format(Locale.ROOT,
                    "%s,%s,%s,%d,%d,%d,%d,%d,%s,%s,%s,%d,%s,%d,%d,%s,%s,%s,%d,%s",
                    ExperimentSettings.runId(), ExperimentSettings.expert(), seedText(),
                    Integer.valueOf(round), Long.valueOf(round * roundMillis), Long.valueOf((round + 1L) * roundMillis),
                    Long.valueOf(stats.cloudlets), Long.valueOf(stats.requests),
                    fmt(mean(stats.processingSum, stats.cloudlets)), fmt(percentile(stats.processing, 0.95)),
                    fmt(stats.processingMax), Long.valueOf(stats.processingSlaViolations),
                    fmt(rate(stats.processingSlaViolations, stats.cloudlets)),
                    Long.valueOf(stats.responses), Long.valueOf(stats.responseRequests),
                    fmt(stats.responseWeight == 0 ? 0.0 : stats.responseWeightedSum / stats.responseWeight),
                    fmt(percentile(stats.response, 0.95)), fmt(stats.responseMax),
                    Long.valueOf(stats.responseSlaViolations),
                    fmt(rate(stats.responseSlaViolations, stats.responses))));
        }
    }

    @SuppressWarnings("unchecked")
    private void writeSummary(Map<String, Object> results) {
        double vmCost = 0.0;
        double dataCost = 0.0;
        double totalCost = 0.0;
        double ubAverageResponse = 0.0;
        long ubResponseCount = 0;

        if (results != null) {
            Object costsObject = results.get(Constants.COSTS);
            if (costsObject instanceof Map) {
                for (Object dcCosts : ((Map<String, Object>) costsObject).values()) {
                    if (dcCosts instanceof Map) {
                        Map<String, Object> values = (Map<String, Object>) dcCosts;
                        vmCost += number(values.get(Constants.VM_COST));
                        dataCost += number(values.get(Constants.DATA_COST));
                        totalCost += number(values.get(Constants.TOTAL_COST));
                    }
                }
            }

            Object ubObject = results.get(Constants.UB_STATS);
            if (ubObject instanceof Map) {
                for (Object value : ((Map<String, Object>) ubObject).values()) {
                    if (value instanceof SimMeasure) {
                        SimMeasure measure = (SimMeasure) value;
                        if (Constants.UB_RESPONSE_TIME.equals(measure.getName()) && measure.getCount() > 0) {
                            ubAverageResponse += measure.getAvg() * measure.getCount();
                            ubResponseCount += measure.getCount();
                        }
                    }
                }
            }
        }

        double response = ubResponseCount > 0 ? ubAverageResponse / ubResponseCount
                : (responseWeight > 0 ? responseWeightedSum / responseWeight : 0.0);
        double procP95 = percentile(allProcessing, 0.95);
        double responseP95 = percentile(allResponse, 0.95);

        File file = new File(ExperimentSettings.outDir(), "summary.csv");
        try {
            BufferedWriter out = writer(file);
            writeLine(out, "run_id,expert,seed,policy,scenario,cloudlets,requests,mean_proc_ms,"
                    + "p95_proc_ms,max_proc_ms,proc_sla_rate,responses,response_requests,"
                    + "mean_response_ms,p95_response_ms,max_response_ms,response_sla_rate,"
                    + "vm_cost,data_cost,total_cost,sim_end_ms");
            writeLine(out, String.format(Locale.ROOT,
                    "%s,%s,%s,%s,%s,%d,%d,%s,%s,%s,%s,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s",
                    ExperimentSettings.runId(), ExperimentSettings.expert(), seedText(),
                    ExperimentSettings.policy(), System.getProperty("lb.scenario", ""),
                    Long.valueOf(totalCloudlets), Long.valueOf(totalRequests),
                    fmt(mean(processingSum(), totalCloudlets)), fmt(procP95), fmt(max(allProcessing)),
                    fmt(rate(processingSlaViolations, totalCloudlets)),
                    Long.valueOf(totalResponses), Long.valueOf(totalResponseRequests),
                    fmt(response), fmt(responseP95), fmt(max(allResponse)),
                    fmt(rate(responseSlaViolations, totalResponses)),
                    fmt(vmCost), fmt(dataCost), fmt(totalCost), fmt(maxFinishTime)));
            closeQuietly(out);
        } catch (IOException e) {
            System.err.println("[Experiment] Could not write summary.csv: " + e.getMessage());
        }
    }

    private void writeRunProperties() {
        File file = new File(ExperimentSettings.outDir(), "run.properties");
        try {
            BufferedWriter out = writer(file);
            writeLine(out, "# Generated by cloudsim.ext.experiment.ExperimentMetrics");
            writeLine(out, "sim.runId=" + ExperimentSettings.runId());
            writeLine(out, "sim.expert=" + ExperimentSettings.expert());
            writeLine(out, "sim.policy=" + ExperimentSettings.policy());
            writeLine(out, "sim.seed=" + seedText());
            writeLine(out, "lb.scenario=" + System.getProperty("lb.scenario", ""));
            writeLine(out, "sim.roundMinutes=" + (ExperimentSettings.roundMillis() / 60_000L));
            writeLine(out, "sim.slaMs=" + ExperimentSettings.responseSlaMs());
            writeLine(out, "sim.procSlaMs=" + ExperimentSettings.processingSlaMs());
            writeLine(out, "sim.end_ms=" + fmt(maxFinishTime));
            writeLine(out, "sim.cloudlets=" + totalCloudlets);
            closeQuietly(out);
        } catch (IOException e) {
            System.err.println("[Experiment] Could not write run.properties: " + e.getMessage());
        }
    }

    private double processingSum() {
        double sum = 0.0;
        for (Double value : allProcessing) {
            sum += value.doubleValue();
        }
        return sum;
    }

    private RoundStats roundStats(int round) {
        Integer key = Integer.valueOf(round);
        RoundStats stats = rounds.get(key);
        if (stats == null) {
            stats = new RoundStats();
            rounds.put(key, stats);
        }
        return stats;
    }

    private int roundIndex(double finishTime) {
        long roundMillis = ExperimentSettings.roundMillis();
        return (int) Math.max(0L, Math.floor(Math.max(0.0, finishTime) / roundMillis));
    }

    private String seedText() {
        Long seed = ExperimentSettings.seedOrNull();
        return seed == null ? "" : seed.toString();
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private static double mean(double sum, long count) {
        return count == 0 ? 0.0 : sum / count;
    }

    private static double rate(long count, long total) {
        return total == 0 ? 0.0 : (double) count / total;
    }

    private static double max(List<Double> values) {
        double max = 0.0;
        for (Double value : values) {
            max = Math.max(max, value.doubleValue());
        }
        return max;
    }

    private static double percentile(List<Double> values, double quantile) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index).doubleValue();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", Double.valueOf(value));
    }

    private static BufferedWriter writer(File file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
    }

    private static void writeLine(BufferedWriter writer, String line) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("[Experiment] CSV write failed: " + e.getMessage());
        }
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    private static final class RoundStats {
        long cloudlets;
        long requests;
        double processingSum;
        double processingMax;
        long processingSlaViolations;
        long responses;
        long responseRequests;
        double responseWeightedSum;
        long responseWeight;
        double responseMax;
        long responseSlaViolations;
        final List<Double> processing = new ArrayList<Double>();
        final List<Double> response = new ArrayList<Double>();
    }
}
