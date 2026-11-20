# Online-learning experiment: key findings

- Rounds: 96 fifteen-minute rounds; seeds: 5; experts: 9.
- Best fixed expert in hindsight: **Latency-lean (5/5)** with cumulative loss 72.43.
- Hedge greedy cumulative loss: **72.61**; mixture cumulative loss: 73.64.
- Greedy gap to the best fixed expert: +0.18 (+0.25%).
- Greedy vs uniform mixture: +1.60% cumulative loss.
- Greedy vs fixed hybrid (S1): +0.25% cumulative loss.
- Greedy static regret: +0.18; dynamic regret: 0.21.
- The theoretical Hedge bound is about 20.54; the empirical greedy regret is 0.88% of that bound.

## Interpretation

The learner discovers the best fixed expert during the peak window and then keeps it. Almost all regret is incurred in rounds 5-12; after the peak, all experts have the same off-peak loss, so no further regret accumulates. The greedy policy tracks the best fixed expert, and its gap to that expert is the cost of exploration. Table 2 ranks every expert, and Table 7 gives the paired differences between the deployable greedy policy and each one.

Static regret compares the learner with the single best fixed expert chosen after seeing all rounds. Dynamic regret compares the learner with a per-round oracle, so it is always non-negative and measures tracking quality. The mixture is the theoretical Hedge object; the greedy argmax policy is the one an operator would deploy.

The primary loss is `p95_response_ms / 80 + response_sla_rate`; VM cost is reported separately in the expert table and the cost-latency scatter. Table 8 shows how the selected expert changes when an amortised VM-cost penalty is added.

## Files

- `figures/fig01_cumulative_loss.png`
- `figures/fig02_regret.png`
- `figures/fig03_weights.png`
- `figures/fig04_expert_bars.png`
- `figures/fig05_cost_latency.png`
- `figures/fig06_peak_shift.png`
- `figures/fig07_loss_heatmap.png`
- `figures/fig08_learner_vs_experts.png`
- `figures/fig09_choice_timeline.png`
- `tables/table1_expert_summary.(csv|md)`
- `tables/table2_learner_comparison.(csv|md)`
- `tables/table3_regret.(csv|md)`
- `tables/table4_peak_offpeak.(csv|md)`
- `tables/table5_per_seed.(csv|md)`
- `tables/table6_loss_sensitivity.(csv|md)`
- `tables/table7_paired_differences.(csv|md)`
- `tables/table8_cost_weight_sensitivity.(csv|md)`
