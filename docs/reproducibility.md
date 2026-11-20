# Reproducibility

All experiments were run in **CloudAnalyst** over a simulated 24-hour period. This page records the setup used for the archived runs so the three scenarios can be rebuilt in the GUI.

---

## 1. Environment

| Item | Value |
|---|---|
| Simulator | CloudAnalyst (CloudSim-based, event-driven) |
| Language | Java 8 |
| Service broker policy | Optimize Response Time |
| User grouping factor | 100 |
| Request grouping factor (DC) | 100 |
| Load balancers compared | Proposed Method, Throttled (TLB), Round Robin (RR), ESCE, Min Min, Ant Colony, PSO LoadBalancer |
| Simulation duration | 24 hours |

The proposed policy appears in the **Advanced** tab of the configuration screen as **Proposed Method**; the additional balancers appear as **Min Min**, **Ant Colony** and **PSO LoadBalancer**.

---

## 2. User bases

| Scenario | UB | Region | Off-peak users | Peak users | Peak window | Data per request (B) | Requests/user/hour |
|---|---|---:|---:|---:|---|---:|---:|
| 1 | UB1 | R3 | 12,000 | 120,000 | 01:00-03:00 | 100 | 60 |
| 2 | UB1 | R3 | 20,000 | 200,000 | 01:00-03:00 | 100 | 70 |
| 2 | UB2 | R2 | 20,000 | 200,000 | 20:00-22:00 | 100 | 70 |
| 2 | UB3 | R4 | 7,500 | 75,000 | 21:00-23:00 | 100 | 70 |
| 3 | UB1 | R0 | 35,000 | 350,000 | 13:00-15:00 | 100 | 75 |
| 3 | UB2 | R1 | 45,000 | 450,000 | 15:00-17:00 | 100 | 75 |
| 3 | UB3 | R2 | 30,000 | 300,000 | 20:00-22:00 | 100 | 75 |
| 3 | UB4 | R3 | 20,000 | 200,000 | 01:00-03:00 | 100 | 75 |
| 3 | UB5 | R4 | 15,000 | 150,000 | 21:00-23:00 | 100 | 75 |
| 3 | UB6 | R5 | 8,500 | 85,000 | 09:00-11:00 | 100 | 75 |

---

## 3. Data centres

| Scenario | DC | Region | VMs | VMM | OS | Architecture |
|---|---|---:|---|---|---|---|
| 1 | DC1 | R3 | 7 | Xen | Linux | x86 |
| 2 | DC1 | R3 | 15 | Xen | Linux | x86 |
| 2 | DC2 | R2 | 13 | Xen | Linux | x86 |
| 2 | DC3 | R4 | 9 | Xen | Linux | x86 |
| 3 | DC1 | R0 | 50 | Xen | Linux | x86 |
| 3 | DC2 | R1 | 21 | Xen | Linux | x86 |
| 3 | DC3 | R2 | 24 | Xen | Linux | x86 |
| 3 | DC4 | R3 | 31 | Xen | Linux | x86 |
| 3 | DC5 | R5 | 16 | Xen | Linux | x86 |
| 3 | DC6 | R4 | 14 | Xen | Linux | x86 |

---

## 4. Physical servers

Each data centre hosts two heterogeneous physical servers.

| Policy | Processor speed | Processors | Bandwidth | Storage | Memory |
|---|---:|---:|---|---:|---:|
| Time-Shared | 40,000 MIPS | 16 CPU | 1 Gb/s | 100,000,000 MB | 204,800 MB |
| Time-Shared | 20,000 MIPS | 8 CPU | 1 Gb/s | 100,000,000 MB | 204,800 MB |

VMs are instantiated across the servers with Round Robin; the total VM count per DC is set in the GUI (Section 3).

---

## 5. VM classes

| Class | MIPS | Bandwidth | PEs | Image size | RAM |
|---|---:|---:|---:|---:|---:|
| Low-Capacity | 1,000 | 1,000 | 1 | 10,000 | 512 MB |
| Medium-Capacity | 2,000 | 1,000 | 2 | 20,000 | 1,024 MB |
| High-Capacity | 4,000 | 1,000 | 4 | 40,000 | 2,048 MB |

---

## 6. Cost configuration

| Physical units | Data transfer | Storage | Memory | Base cost per VM | DC |
|---:|---:|---:|---:|---:|---|
| 2 | 0.1 USD/GB | 0.1 USD/s | 0.05 USD/s | 0.1 USD/hour | all |

`DatacenterController.getVmCost()` applies multipliers **1.0 / 1.3 / 1.6** for the 1-, 2- and 4-vCPU classes, so high-capacity VMs carry a higher hourly rate.

---

## 7. Instruction lengths

| Scenario | Instruction length (bytes) | Output size (bytes) |
|---|---|---:|
| 1 | 150 / 100 / 50 | 2,000 |
| 2 | 225 / 150 / 75 | 2,000 |
| 3 | 300 / 200 / 100 | 2,000 |

---

## 8. Proposed-method parameters

Set the matching scenario before each run:

```bash
# Scenario 1
java -Dlb.scenario=1 -cp "build/classes:jars/*" cloudsim.ext.gui.GuiMain

# Scenario 2
java -Dlb.scenario=2 -cp "build/classes:jars/*" cloudsim.ext.gui.GuiMain

# Scenario 3
java -Dlb.scenario=3 -cp "build/classes:jars/*" cloudsim.ext.gui.GuiMain
```

The values loaded from [`config/loadbalancer.properties`](../config/loadbalancer.properties) are:

| Scenario | L<sub>1</sub> | L<sub>2</sub> | L<sub>3</sub> | Idle threshold |
|---|---:|---:|---:|---:|
| 1 | 30 | 40 | 50 | 120 s |
| 2 | 27 | 36 | 45 | 180 s |
| 3 | 4 | 32 | 40 | 300 s |

CPU/RAM weights are 0.8 / 0.2 in all scenarios.

---

## 9. Run procedure

1. Build the project ([README quick start](../README.md#quick-start)).
2. Launch CloudAnalyst and open **Configure Simulation**.
3. On **Main**, create the user bases and data centres exactly as in Sections 2-4.
4. On **Advanced**, select:
   - Service broker policy: **Optimize Response Time**
   - User grouping factor: **100**
   - Request grouping factor: **100**
   - Load balancing policy: **Proposed Method**
5. Start the simulation and wait for the 24-hour run to finish.
6. Export the generated report as PDF (CloudAnalyst does this automatically on the results screen).
7. Repeat steps 4-6 with **Round Robin**, **Throttled**, **Equally Spread Current Execution Load**, **Min Min**, **Ant Colony** and **PSO LoadBalancer**.
8. Compare against the reports in [`results/`](../results).

The archived CloudAnalyst reports for every scenario and policy are included under [`results/`](../results); console logs from the runs are under [`results/logs/`](../results/logs).

### Seeded 9-expert Hedge grid

The online-learning comparison uses scenario 1 and a headless, one-JVM-per-run driver instead of the GUI:

```bash
JAVA=".../bin/java" JAVAC=".../bin/javac" ./scripts/run-online-experiment.sh
python analysis/hedge_experiment.py
```

The driver runs RR, Throttled, ESCE, Min Min, Ant Colony, PSO LoadBalancer, and the Proposed Method under the scenario 1, 4 and 5 presets, for five seeds each (45 runs). Outputs land in [`results/online/`](../results/online); the full setup is in [online-learning-experiment.md](online-learning-experiment.md).
