# Configuration

The hybrid balancer reads its experiment parameters at construction time. The canonical file is [`config/loadbalancer.properties`](../config/loadbalancer.properties); its values are the paper's Table 6 values.

---

## Resolution order

1. System property `-Dlb.config=<path>` (absolute or relative to the working directory).
2. `config/loadbalancer.properties` relative to the working directory.
3. `loadbalancer.properties` on the classpath.
4. Built-in paper defaults (scenario 1).

The scenario is chosen by:

1. System property `-Dlb.scenario=1|2|3|auto`.
2. `lb.scenario` in the properties file.
3. Default `1`.

`-Dlb.scenario` always wins, which makes reproducible batch runs easy without editing the file.

---

## Paper Table 6 values

| Parameter | Scenario 1 | Scenario 2 | Scenario 3 |
|---|---:|---:|---:|
| VMs per data centre | 7 | 15 / 13 / 9 | 50 / 21 / 24 / 31 / 16 / 14 |
| Low-load threshold $L_1$ | 30 | 27 | 4 |
| Medium-load threshold $L_2$ | 40 | 36 | 32 |
| High-load threshold $L_3$ | 50 | 45 | 40 |
| Idle threshold | 120 s (2 min) | 180 s (3 min) | 300 s (5 min) |
| CPU weight $w$ | 0.8 | 0.8 | 0.8 |
| RAM weight $1-w$ | 0.2 | 0.2 | 0.2 |

---

## Keys

| Key | Default | Meaning |
|---|---:|---|
| `lb.scenario` | `1` | Active experiment: `1`, `2`, `3` or `auto` |
| `scenario.N.lowLoadThreshold` | Table 6 | $L_1$ for scenario `N` |
| `scenario.N.mediumLoadThreshold` | Table 6 | $L_2$ for scenario `N` |
| `scenario.N.highLoadThreshold` | Table 6 | $L_3$ for scenario `N` |
| `scenario.N.idleThresholdSeconds` | Table 6 | Idle time before down-scaling |
| `weight.cpu` | `0.8` | $w$ in Eq. (3) |
| `weight.ram` | `0.2` | $1-w$ in Eq. (3) |
| `scoring.epsilon` | `1.0` | $\varepsilon$ in Eq. (3) |
| `scaling.consecutiveFailureThreshold` | `5` | $CF$ guard (Eq. 6) |
| `scaling.busyFactorThreshold` | `0.85` | $BF$ guard (Eq. 6) |
| `scaling.initialActiveRatio` | `0.85` | Active-VM floor used by down-scaling |

`scaling.consecutiveFailureThreshold`, `scaling.busyFactorThreshold` and `scaling.initialActiveRatio` are **not** fixed numerically in the paper; they are the reference implementation's values and are exposed so experiments can vary them explicitly.

---

## Validation

- Thresholds must satisfy `0 <= L1 < L2 < L3`; otherwise the paper values for the selected scenario are restored and a warning is printed.
- The idle threshold must be positive.
- CPU and RAM weights must each lie in `[0, 1]`.
- Values are echoed at balancer start-up:

```text
[HybridLB] scenario=3, L1=4, L2=32, L3=40, idle=300.0s, wCPU=0.8, wRAM=0.2, CF=5, BF=0.85, initialActiveRatio=0.85, source=config/loadbalancer.properties
```

---

## Examples

```bash
# Scenario 2 with the bundled properties file
java -Dlb.scenario=2 \
     -cp "build/classes:jars/*" \
     cloudsim.ext.gui.GuiMain

# Scenario 3 with an external configuration file
java -Dlb.scenario=3 -Dlb.config=/data/experiments/lb-scenario3.properties \
     -cp "build/classes:jars/*" \
     cloudsim.ext.gui.GuiMain

# Let the balancer infer the scenario from the data-centre VM count
java -Dlb.scenario=auto \
     -cp "build/classes:jars/*" \
     cloudsim.ext.gui.GuiMain
```

On Windows use `;` as the classpath separator, or use [`scripts/run.bat`](../scripts/run.bat).

The effective setup is also printed by the simulator's console, so a run log always records which thresholds were used.
