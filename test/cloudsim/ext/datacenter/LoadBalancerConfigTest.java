package cloudsim.ext.datacenter;

/**
 * Headless checks for the scenario presets. Run from the
 * repository root so that {@code config/loadbalancer.properties} is found.
 */
public final class LoadBalancerConfigTest {

    private LoadBalancerConfigTest() {
    }

    public static void main(String[] args) {
        System.clearProperty(LoadBalancerConfig.CONFIG_PATH_PROPERTY);
        System.clearProperty(LoadBalancerConfig.SCENARIO_PROPERTY);

        LoadBalancerConfig scenario1 = LoadBalancerConfig.load(7);
        expect(scenario1.getScenario() == 1, "scenario 1 selected");
        expect(scenario1.getLowLoadThreshold() == 30, "L1 = 30");
        expect(scenario1.getMediumLoadThreshold() == 40, "L2 = 40");
        expect(scenario1.getHighLoadThreshold() == 50, "L3 = 50");
        expect(scenario1.getIdleThresholdSeconds() == 120, "idle = 120 s");
        expect(scenario1.getCpuWeight() == 0.8, "CPU weight = 0.8");
        expect(scenario1.getRamWeight() == 0.2, "RAM weight = 0.2");

        System.setProperty(LoadBalancerConfig.SCENARIO_PROPERTY, "2");
        LoadBalancerConfig scenario2 = LoadBalancerConfig.load(15);
        expect(scenario2.getLowLoadThreshold() == 27, "scenario 2 L1 = 27");
        expect(scenario2.getMediumLoadThreshold() == 36, "scenario 2 L2 = 36");
        expect(scenario2.getHighLoadThreshold() == 45, "scenario 2 L3 = 45");
        expect(scenario2.getIdleThresholdSeconds() == 180, "scenario 2 idle = 180 s");

        System.setProperty(LoadBalancerConfig.SCENARIO_PROPERTY, "3");
        System.setProperty(LoadBalancerConfig.CONFIG_PATH_PROPERTY, "missing-config.properties");
        LoadBalancerConfig scenario3 = LoadBalancerConfig.load(50);
        expect(scenario3.getLowLoadThreshold() == 4, "scenario 3 L1 = 4");
        expect(scenario3.getMediumLoadThreshold() == 32, "scenario 3 L2 = 32");
        expect(scenario3.getHighLoadThreshold() == 40, "scenario 3 L3 = 40");
        expect(scenario3.getIdleThresholdSeconds() == 300, "scenario 3 idle = 300 s");

        System.setProperty(LoadBalancerConfig.SCENARIO_PROPERTY, "auto");
        expect(LoadBalancerConfig.load(7).getScenario() == 1, "auto: 7 VMs -> scenario 1");
        expect(LoadBalancerConfig.load(16).getScenario() == 2, "auto: 16 VMs -> scenario 2");
        expect(LoadBalancerConfig.load(50).getScenario() == 3, "auto: 50 VMs -> scenario 3");

        System.setProperty(LoadBalancerConfig.CONFIG_PATH_PROPERTY, LoadBalancerConfig.DEFAULT_CONFIG_PATH);
        System.setProperty(LoadBalancerConfig.SCENARIO_PROPERTY, "4");
        LoadBalancerConfig costLean = LoadBalancerConfig.load(7);
        expect(costLean.getLowLoadThreshold() == 40, "scenario 4 L1 = 40");
        expect(costLean.getMediumLoadThreshold() == 55, "scenario 4 L2 = 55");
        expect(costLean.getHighLoadThreshold() == 70, "scenario 4 L3 = 70");
        expect(costLean.getIdleThresholdSeconds() == 600, "scenario 4 idle = 600 s");
        expect(costLean.getActiveRatio() == 0.60, "scenario 4 active ratio = 0.60");
        expect(costLean.getBusyFactorThreshold() == 0.95, "scenario 4 busy factor = 0.95");
        expect(costLean.getConsecutiveFailureThreshold() == 8, "scenario 4 failure threshold = 8");

        System.setProperty(LoadBalancerConfig.SCENARIO_PROPERTY, "5");
        LoadBalancerConfig latencyLean = LoadBalancerConfig.load(7);
        expect(latencyLean.getLowLoadThreshold() == 10, "scenario 5 L1 = 10");
        expect(latencyLean.getMediumLoadThreshold() == 20, "scenario 5 L2 = 20");
        expect(latencyLean.getHighLoadThreshold() == 30, "scenario 5 L3 = 30");
        expect(latencyLean.getIdleThresholdSeconds() == 60, "scenario 5 idle = 60 s");
        expect(latencyLean.getActiveRatio() == 1.00, "scenario 5 active ratio = 1.00");
        expect(latencyLean.getBusyFactorThreshold() == 0.70, "scenario 5 busy factor = 0.70");
        expect(latencyLean.getConsecutiveFailureThreshold() == 3, "scenario 5 failure threshold = 3");

        System.out.println("LoadBalancerConfigTest: all checks passed.");
    }

    private static void expect(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError("Failed: " + description);
        }
    }
}
