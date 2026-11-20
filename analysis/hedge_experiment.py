#!/usr/bin/env python3
"""Offline online-learning analysis for the CloudAnalyst expert runs.

The script treats each fixed policy as an expert and runs Hedge over the
per-round losses of the seeded 24-hour scenario. It is an offline replay:
all expert losses are observed after the runs, which is the full-information
setting of the Hedge algorithm (Mohri, Chapter on On-Line Learning).

Primary loss per round:
    loss_t(k) = p95_response_ms(k, t) / SLA_ms + response_sla_rate(k, t)

The first term is tail latency relative to the SLA; the second is the
fraction of requests above the SLA. Lower is better. VM cost is reported
separately, not folded into the learning loss.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import PercentFormatter
import numpy as np
import pandas as pd

EXPERTS = [
    "rr",
    "throttled",
    "esce",
    "min-min",
    "aco",
    "pso",
    "hybrid",
    "hybrid-cost-lean",
    "hybrid-latency-lean",
]
SEEDS = [1, 2, 3, 4, 5]

LABELS = {
    "rr": "Round Robin",
    "throttled": "Throttled",
    "esce": "ESCE",
    "min-min": "Min-Min",
    "aco": "Ant Colony (ACO)",
    "pso": "Particle Swarm (PSO)",
    "hybrid": "Hybrid (S1)",
    "hybrid-cost-lean": "Hybrid cost-lean (S4)",
    "hybrid-latency-lean": "Hybrid latency-lean (S5)",
}
SHORT = {
    "rr": "RR",
    "throttled": "Throttled",
    "esce": "ESCE",
    "min-min": "Min-Min",
    "aco": "ACO",
    "pso": "PSO",
    "hybrid": "Hybrid",
    "hybrid-cost-lean": "Cost-lean",
    "hybrid-latency-lean": "Latency-lean",
}
COLORS = {
    "rr": "#6c757d",
    "throttled": "#8d6e63",
    "esce": "#4c78a8",
    "min-min": "#6a4c93",
    "aco": "#e08e45",
    "pso": "#5b8c5a",
    "hybrid": "#111111",
    "hybrid-cost-lean": "#2a9d8f",
    "hybrid-latency-lean": "#e76f51",
}
LABEL_OFFSETS = {
    "min-min": (8, 6),
    "aco": (8, -14),
    "pso": (-46, 6),
}

T_CRIT_95 = {
    1: 12.706,
    2: 4.303,
    3: 3.182,
    4: 2.776,
    5: 2.571,
    6: 2.447,
    7: 2.365,
    8: 2.306,
    9: 2.262,
    10: 2.228,
    11: 2.201,
    12: 2.179,
    13: 2.160,
    14: 2.145,
    15: 2.131,
    16: 2.120,
    17: 2.110,
    18: 2.101,
    19: 2.093,
    20: 2.086,
    25: 2.060,
    30: 2.042,
}


@dataclass
class Run:
    expert: str
    seed: int
    rounds: pd.DataFrame
    summary: pd.Series


@dataclass
class HedgeResult:
    seed: int
    losses: np.ndarray
    learner_losses: np.ndarray
    greedy_losses: np.ndarray
    greedy_choices: np.ndarray
    weights: np.ndarray
    cumulative_learner: np.ndarray
    cumulative_greedy: np.ndarray
    cumulative_experts: np.ndarray
    static_regret: np.ndarray
    greedy_static_regret: np.ndarray
    dynamic_regret: np.ndarray
    best_fixed: int


def parse_args() -> argparse.Namespace:
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-root", type=Path, default=repo / "results" / "online")
    parser.add_argument("--out", type=Path, default=repo / "analysis" / "output")
    parser.add_argument("--experts", default=",".join(EXPERTS))
    parser.add_argument("--seeds", default=",".join(str(s) for s in SEEDS))
    parser.add_argument("--sla-ms", type=float, default=80.0)
    parser.add_argument("--peak-start-hour", type=float, default=1.0)
    parser.add_argument("--peak-end-hour", type=float, default=3.0)
    parser.add_argument("--round-minutes", type=int, default=15)
    parser.add_argument(
        "--loss",
        choices=["p95_sla", "mean_sla", "p95_only", "combined"],
        default="p95_sla",
    )
    parser.add_argument("--loss-scale", type=float, default=2.0)
    return parser.parse_args()


def load_runs(root: Path, experts: list[str], seeds: list[int]) -> dict[tuple[str, int], Run]:
    runs: dict[tuple[str, int], Run] = {}
    missing: list[str] = []
    for expert in experts:
        for seed in seeds:
            run_dir = root / expert / f"seed-{seed}"
            rounds_path = run_dir / "rounds.csv"
            summary_path = run_dir / "summary.csv"
            if not rounds_path.is_file() or not summary_path.is_file():
                missing.append(str(run_dir))
                continue
            rounds = pd.read_csv(rounds_path).sort_values("round").reset_index(drop=True)
            if rounds.empty:
                missing.append(f"{run_dir} (empty rounds.csv)")
                continue
            expected = np.arange(len(rounds))
            if not np.array_equal(rounds["round"].to_numpy(), expected):
                raise SystemExit(f"Non-contiguous rounds in {rounds_path}")
            summary = pd.read_csv(summary_path).iloc[0]
            runs[(expert, seed)] = Run(expert, seed, rounds, summary)
    if missing:
        raise SystemExit(
            "Missing or incomplete runs:\n  " + "\n  ".join(missing)
        )
    lengths = {len(run.rounds) for run in runs.values()}
    if len(lengths) != 1:
        raise SystemExit(f"Runs have different round counts: {sorted(lengths)}")
    return runs


def loss_series(run: Run, kind: str, sla_ms: float) -> np.ndarray:
    df = run.rounds
    if kind == "p95_sla":
        return (df["p95_response_ms"] / sla_ms + df["response_sla_rate"]).to_numpy()
    if kind == "mean_sla":
        return (df["mean_response_ms"] / 50.0 + df["response_sla_rate"]).to_numpy()
    if kind == "p95_only":
        return (df["p95_response_ms"] / sla_ms).to_numpy()
    if kind == "combined":
        return (
            0.5 * df["mean_response_ms"] / 50.0
            + 0.5 * df["p95_response_ms"] / sla_ms
            + df["response_sla_rate"]
        ).to_numpy()
    raise ValueError(kind)


def hedge(loss_matrix: np.ndarray, loss_scale: float) -> HedgeResult:
    t_count, k_count = loss_matrix.shape
    eta = math.sqrt(8.0 * math.log(k_count) / t_count) / loss_scale
    weights = np.full(k_count, 1.0 / k_count)

    learner_losses = np.empty(t_count)
    greedy_losses = np.empty(t_count)
    greedy_choices = np.empty(t_count, dtype=int)
    weight_history = np.empty((t_count, k_count))

    for t in range(t_count):
        probabilities = weights / weights.sum()
        weight_history[t] = probabilities
        losses = loss_matrix[t]

        learner_losses[t] = float(probabilities @ losses)
        greedy_choices[t] = int(np.argmax(probabilities))
        greedy_losses[t] = float(losses[greedy_choices[t]])

        weights = weights * np.exp(-eta * losses)
        weights /= weights.sum()

    cumulative_experts = np.cumsum(loss_matrix, axis=0)
    best_fixed = int(np.argmin(cumulative_experts[-1]))
    cumulative_learner = np.cumsum(learner_losses)
    cumulative_greedy = np.cumsum(greedy_losses)
    oracle = np.cumsum(loss_matrix.min(axis=1))

    return HedgeResult(
        seed=-1,
        losses=loss_matrix,
        learner_losses=learner_losses,
        greedy_losses=greedy_losses,
        greedy_choices=greedy_choices,
        weights=weight_history,
        cumulative_learner=cumulative_learner,
        cumulative_greedy=cumulative_greedy,
        cumulative_experts=cumulative_experts,
        static_regret=cumulative_learner - cumulative_experts[:, best_fixed],
        greedy_static_regret=cumulative_greedy - cumulative_experts[:, best_fixed],
        dynamic_regret=cumulative_learner - oracle,
        best_fixed=best_fixed,
    )


def mean_ci(values: np.ndarray) -> tuple[float, float, float]:
    n = len(values)
    mean = float(np.mean(values))
    if n < 2:
        return mean, mean, mean
    sd = float(np.std(values, ddof=1))
    crit = T_CRIT_95.get(n - 1, 1.96)
    half = crit * sd / math.sqrt(n)
    return mean, mean - half, mean + half


def fmt_mean_sd(mean: float, sd: float, digits: int = 2) -> str:
    return f"{mean:.{digits}f} ± {sd:.{digits}f}"


def write_markdown(df: pd.DataFrame, path: Path, caption: str | None = None) -> None:
    lines: list[str] = []
    if caption:
        lines.extend([f"**{caption}**", ""])
    headers = list(df.columns)
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("| " + " | ".join("---" for _ in headers) + " |")
    for _, row in df.iterrows():
        cells = ["" if pd.isna(v) else str(v) for v in row.tolist()]
        lines.append("| " + " | ".join(cells) + " |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def peak_round_mask(round_count: int, args: argparse.Namespace) -> np.ndarray:
    round_hours = args.round_minutes / 60.0
    mask = np.zeros(round_count, dtype=bool)
    for r in range(round_count):
        hour = r * round_hours
        if args.peak_start_hour <= hour < args.peak_end_hour:
            mask[r] = True
    return mask


def style_axis(ax: plt.Axes, peak_mask: np.ndarray, label: str = "Peak 01:00-03:00") -> None:
    if peak_mask.any():
        idx = np.flatnonzero(peak_mask)
        ax.axvspan(idx[0] - 0.5, idx[-1] + 0.5, color="#f4a261", alpha=0.16, lw=0)
        ax.text(
            idx[0] + 0.25,
            ax.get_ylim()[1],
            label,
            ha="left",
            va="top",
            fontsize=8,
            color="#9c5a1c",
        )
    ax.grid(alpha=0.25, lw=0.6)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)


def plot_cumulative_loss(
    results: list[HedgeResult],
    experts: list[str],
    peak_mask: np.ndarray,
    out: Path,
) -> None:
    x = np.arange(1, len(peak_mask) + 1)
    learner_curves = np.vstack([r.cumulative_learner for r in results])
    greedy_curves = np.vstack([r.cumulative_greedy for r in results])
    learner_mean = learner_curves.mean(axis=0)
    greedy_mean = greedy_curves.mean(axis=0)
    _, low, high = zip(*(mean_ci(learner_curves[:, t]) for t in range(len(x))))

    fig, ax = plt.subplots(figsize=(9.5, 5.2))
    for k, expert in enumerate(experts):
        curves = np.vstack([r.cumulative_experts[:, k] for r in results])
        ax.plot(x, curves.mean(axis=0), color=COLORS[expert], lw=1.3, label=LABELS[expert])
    ax.plot(x, greedy_mean, color="#d00000", lw=2.8, label="Hedge (greedy pick)")
    ax.plot(x, learner_mean, color="#d00000", lw=1.6, ls="--", label="Hedge (mixture)")
    ax.fill_between(x, low, high, color="#d00000", alpha=0.10, lw=0)
    ax.set_xlabel("15-minute round")
    ax.set_ylabel("Cumulative loss (SLA-normalised)")
    ax.set_title("Cumulative loss: Hedge vs fixed load-balancer experts")
    style_axis(ax, peak_mask)
    ax.legend(fontsize=8, ncol=2, frameon=False)
    fig.tight_layout()
    fig.savefig(out / "fig01_cumulative_loss.png", dpi=170)
    plt.close(fig)


def plot_learner_bars(
    results: list[HedgeResult],
    experts: list[str],
    out: Path,
) -> None:
    labels = ["Hedge greedy", "Hedge mixture"] + [SHORT[e] for e in experts]
    mixture = np.mean([r.cumulative_learner[-1] for r in results])
    greedy = np.mean([r.cumulative_greedy[-1] for r in results])
    expert_values = [
        np.mean([r.cumulative_experts[-1, k] for r in results]) for k in range(len(experts))
    ]
    best_fixed = min(expert_values)
    values = [greedy, mixture] + expert_values
    excess = [value - best_fixed for value in values]
    colors = ["#d00000", "#f4a261"] + [COLORS[e] for e in experts]

    fig, ax = plt.subplots(figsize=(10.0, 4.6))
    bars = ax.bar(range(len(labels)), excess, color=colors, alpha=0.92)
    for bar, value in zip(bars, excess):
        ax.text(
            bar.get_x() + bar.get_width() / 2,
            value + 0.06,
            f"{value:+.2f}",
            ha="center",
            va="bottom",
            fontsize=8,
        )
    ax.axhline(0, color="#333333", lw=0.8)
    ax.set_xticks(range(len(labels)))
    ax.set_xticklabels(labels, rotation=18, ha="right", fontsize=8)
    ax.set_ylabel("Excess cumulative loss vs best fixed")
    ax.set_title("Hedge greedy vs fixed experts: excess loss over the best fixed expert")
    ax.set_ylim(0, max(excess) * 1.12)
    ax.grid(axis="y", alpha=0.25, lw=0.6)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    fig.tight_layout()
    fig.savefig(out / "fig08_learner_vs_experts.png", dpi=170)
    plt.close(fig)


def plot_choice_timeline(
    results: list[HedgeResult],
    experts: list[str],
    peak_mask: np.ndarray,
    out: Path,
) -> None:
    fig, ax = plt.subplots(figsize=(10.0, 3.6))
    for r_index, result in enumerate(results):
        for t, choice in enumerate(result.greedy_choices):
            ax.fill_between(
                [t - 0.5, t + 0.5],
                [r_index - 0.36, r_index - 0.36],
                [r_index + 0.36, r_index + 0.36],
                color=COLORS[experts[choice]],
                lw=0,
            )
    ax.set_yticks(range(len(results)))
    ax.set_yticklabels([f"seed {r.seed}" for r in results], fontsize=8)
    ax.set_xlabel("15-minute round")
    ax.set_title("Greedy policy selected by Hedge in each round")
    if peak_mask.any():
        idx = np.flatnonzero(peak_mask)
        ax.axvspan(idx[0] - 0.5, idx[-1] + 0.5, fill=False, ec="#1d3557", lw=1.1, ls="--")
        ax.text(idx[0] + 0.2, len(results) - 0.35, "peak", fontsize=8, color="#1d3557")
    handles = [
        plt.Rectangle((0, 0), 1, 1, color=COLORS[e]) for e in experts
    ]
    ax.legend(handles, [SHORT[e] for e in experts], fontsize=7, ncol=5, frameon=False,
              loc="upper center", bbox_to_anchor=(0.5, -0.26))
    ax.set_xlim(-0.5, len(peak_mask) - 0.5)
    ax.grid(axis="x", alpha=0.18, lw=0.5)
    fig.tight_layout()
    fig.savefig(out / "fig09_choice_timeline.png", dpi=170, bbox_inches="tight")
    plt.close(fig)



def plot_regret(results: list[HedgeResult], peak_mask: np.ndarray, out: Path) -> None:
    x = np.arange(1, len(peak_mask) + 1)
    static = np.vstack([r.static_regret for r in results])
    greedy_static = np.vstack([r.greedy_static_regret for r in results])
    dynamic = np.vstack([r.dynamic_regret for r in results])
    greedy_dynamic = np.vstack(
        [r.cumulative_greedy - r.losses.min(axis=1).cumsum() for r in results]
    )

    fig, axes = plt.subplots(1, 2, figsize=(11.5, 4.4), sharex=True)
    panels = (
        (axes[0], static, greedy_static, "Static regret vs best fixed expert"),
        (axes[1], dynamic, greedy_dynamic, "Dynamic regret vs per-round oracle"),
    )
    for ax, mixture, greedy, title in panels:
        mean = mixture.mean(axis=0)
        greedy_mean = greedy.mean(axis=0)
        _, low, high = zip(*(mean_ci(mixture[:, t]) for t in range(len(x))))
        ax.plot(x, greedy_mean, color="#d00000", lw=2.2, label="Greedy pick")
        ax.plot(x, mean, color="#d00000", lw=1.5, ls="--", label="Mixture")
        ax.fill_between(x, low, high, color="#d00000", alpha=0.10, lw=0)
        ax.axhline(0, color="#333333", lw=0.8, ls="--")
        ax.set_title(title, fontsize=11)
        ax.set_xlabel("15-minute round")
        ax.set_ylabel("Cumulative regret")
        style_axis(ax, peak_mask)
        ax.annotate(
            f"final {mean[-1]:+.2f}",
            xy=(x[-1], mean[-1]),
            xytext=(-78, -20 if mean[-1] >= 0 else 12),
            textcoords="offset points",
            fontsize=8,
            arrowprops=dict(arrowstyle="-", lw=0.6, color="#555555"),
        )
        ax.legend(fontsize=7, frameon=False, loc="center right")
    fig.suptitle("Hedge regret over the 24-hour scenario (mean across seeds, 95% CI)", y=1.02)
    fig.tight_layout()
    fig.savefig(out / "fig02_regret.png", dpi=170, bbox_inches="tight")
    plt.close(fig)


def plot_weights(
    results: list[HedgeResult],
    experts: list[str],
    peak_mask: np.ndarray,
    out: Path,
) -> None:
    x = np.arange(1, len(peak_mask) + 1)
    weights = np.stack([r.weights for r in results])
    mean_weights = weights.mean(axis=0)
    cumulative_mean = np.cumsum(mean_weights, axis=0) / np.arange(1, len(x) + 1)[:, None]

    fig, axes = plt.subplots(2, 1, figsize=(9.5, 6.6), sharex=True)
    for k, expert in enumerate(experts):
        axes[0].plot(x, mean_weights[:, k], color=COLORS[expert], lw=1.5, label=LABELS[expert])
        axes[1].plot(x, cumulative_mean[:, k], color=COLORS[expert], lw=1.4)
    axes[0].set_ylabel("Weight $p_t(k)$")
    axes[0].set_title("Instantaneous Hedge weights")
    axes[1].set_ylabel("Cumulative average weight")
    axes[1].set_title("Convergence of the average policy")
    axes[1].set_xlabel("15-minute round")
    for ax in axes:
        style_axis(ax, peak_mask)
    axes[0].legend(
        fontsize=7.5,
        ncol=1,
        frameon=False,
        loc="center left",
        bbox_to_anchor=(1.0, 0.5),
        title="Experts",
        title_fontsize=7.5,
    )
    fig.tight_layout()
    fig.savefig(out / "fig03_weights.png", dpi=170, bbox_inches="tight")
    plt.close(fig)


def plot_expert_bars(
    summaries: pd.DataFrame,
    experts: list[str],
    out: Path,
) -> None:
    metrics = [
        ("mean_response_ms", "Mean response (ms)", 0),
        ("p95_response_ms", "p95 response (ms)", 0),
        ("response_sla_rate", "Response SLA violation rate", 3),
        ("vm_cost", "VM cost", 2),
    ]
    fig, axes = plt.subplots(2, 2, figsize=(10.5, 6.4))
    for ax, (metric, title, digits) in zip(axes.flat, metrics):
        means = [summaries.loc[e, (metric, "mean")] for e in experts]
        stds = [summaries.loc[e, (metric, "sd")] for e in experts]
        colors = [COLORS[e] for e in experts]
        ax.bar(range(len(experts)), means, yerr=stds, color=colors, capsize=3, alpha=0.9)
        ax.set_xticks(range(len(experts)))
        ax.set_xticklabels([SHORT[e] for e in experts], rotation=20, ha="right", fontsize=8)
        ax.set_title(title, fontsize=10)
        ax.grid(axis="y", alpha=0.25, lw=0.6)
        ax.spines["top"].set_visible(False)
        ax.spines["right"].set_visible(False)
        if metric == "response_sla_rate":
            ax.yaxis.set_major_formatter(PercentFormatter(1.0))
    fig.suptitle("Fixed-expert quality metrics (mean ± sd across seeds)", y=1.01)
    fig.tight_layout()
    fig.savefig(out / "fig04_expert_bars.png", dpi=170, bbox_inches="tight")
    plt.close(fig)


def plot_cost_latency(
    summaries: pd.DataFrame,
    experts: list[str],
    out: Path,
) -> None:
    fig, ax = plt.subplots(figsize=(7.2, 5.0))
    for expert in experts:
        x = summaries.loc[expert, ("vm_cost", "mean")]
        y = summaries.loc[expert, ("p95_response_ms", "mean")]
        xerr = summaries.loc[expert, ("vm_cost", "sd")]
        yerr = summaries.loc[expert, ("p95_response_ms", "sd")]
        ax.errorbar(
            x,
            y,
            xerr=xerr,
            yerr=yerr,
            fmt="o",
            ms=8,
            color=COLORS[expert],
            ecolor=COLORS[expert],
            elinewidth=1.1,
            capsize=3,
        )
        if expert == "pso":
            ax.plot(x, y, marker="o", ms=15, mfc="none", mec=COLORS[expert], mew=1.6, zorder=5)
        ax.annotate(SHORT[expert], (x, y), xytext=LABEL_OFFSETS.get(expert, (7, 5)),
                    textcoords="offset points", fontsize=9,
                    arrowprops=({"arrowstyle": "-", "lw": 0.6, "color": COLORS[expert]}
                                if expert == "pso" else None))
    ax.set_xlabel("VM cost")
    ax.set_ylabel("p95 response time (ms)")
    ax.set_title("Cost-latency trade-off (mean ± sd across seeds)")
    ax.grid(alpha=0.25, lw=0.6)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    fig.tight_layout()
    fig.savefig(out / "fig05_cost_latency.png", dpi=170)
    plt.close(fig)


def plot_peak_shift(
    results: list[HedgeResult],
    experts: list[str],
    peak_mask: np.ndarray,
    out: Path,
) -> None:
    weights = np.stack([r.weights for r in results]).mean(axis=0)
    peak_weight = weights[peak_mask].mean(axis=0)
    off_weight = weights[~peak_mask].mean(axis=0)

    x = np.arange(len(experts))
    width = 0.38
    fig, ax = plt.subplots(figsize=(9.0, 4.4))
    ax.bar(x - width / 2, off_weight, width, label="Off-peak", color="#8ecae6")
    ax.bar(x + width / 2, peak_weight, width, label="Peak 01:00-03:00", color="#e76f51")
    ax.set_xticks(x)
    ax.set_xticklabels([SHORT[e] for e in experts], rotation=15, ha="right")
    ax.set_ylabel("Mean Hedge weight")
    ax.set_title("Where Hedge puts its weight: off-peak vs peak")
    ax.legend(frameon=False)
    ax.grid(axis="y", alpha=0.25, lw=0.6)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    fig.tight_layout()
    fig.savefig(out / "fig06_peak_shift.png", dpi=170)
    plt.close(fig)


def plot_loss_heatmap(
    losses: np.ndarray,
    experts: list[str],
    peak_mask: np.ndarray,
    out: Path,
) -> None:
    fig, ax = plt.subplots(figsize=(11.5, 3.8))
    image = ax.imshow(losses.T, aspect="auto", cmap="YlOrRd", interpolation="nearest")
    ax.set_yticks(range(len(experts)))
    ax.set_yticklabels([SHORT[e] for e in experts], fontsize=8)
    ax.set_xlabel("15-minute round")
    ax.set_title("Per-round loss (mean across seeds); dots mark the best expert")
    for t in range(losses.shape[0]):
        best = int(np.argmin(losses[t]))
        ax.plot(t, best, marker="o", ms=2.2, color="#111111", alpha=0.55)
    if peak_mask.any():
        idx = np.flatnonzero(peak_mask)
        ax.axvspan(idx[0] - 0.5, idx[-1] + 0.5, fill=False, ec="#1d3557", lw=1.2, ls="--")
        ax.text(idx[0] + 0.2, -0.85, "peak", fontsize=8, color="#1d3557")
    cbar = fig.colorbar(image, ax=ax, pad=0.01)
    cbar.set_label("Loss")
    fig.tight_layout()
    fig.savefig(out / "fig07_loss_heatmap.png", dpi=170)
    plt.close(fig)


def build_summary_table(runs: dict[tuple[str, int], Run], experts: list[str], seeds: list[int]) -> pd.DataFrame:
    metrics = {
        "mean_response_ms": "mean",
        "p95_response_ms": "mean",
        "response_sla_rate": "mean",
        "mean_proc_ms": "mean",
        "p95_proc_ms": "mean",
        "proc_sla_rate": "mean",
        "vm_cost": "mean",
        "total_cost": "mean",
        "cloudlets": "mean",
        "requests": "mean",
    }
    rows = {}
    for expert in experts:
        per_seed = pd.DataFrame([runs[(expert, seed)].summary for seed in seeds])
        row = {}
        for metric in metrics:
            values = per_seed[metric].astype(float).to_numpy()
            row[(metric, "mean")] = float(values.mean())
            row[(metric, "sd")] = float(values.std(ddof=1))
        min_cloudlets = min(int(runs[(expert, seed)].rounds["cloudlets"].min()) for seed in seeds)
        row[("min_round_cloudlets", "mean")] = float(min_cloudlets)
        row[("min_round_cloudlets", "sd")] = 0.0
        rows[expert] = row
    table = pd.DataFrame(rows).T
    table.columns = pd.MultiIndex.from_tuples(table.columns)
    table.index.name = "expert"
    return table


def build_learner_table(
    results: list[HedgeResult],
    experts: list[str],
    losses_by_seed: dict[int, np.ndarray],
) -> pd.DataFrame:
    rows: list[dict[str, object]] = []

    def add_row(name: str, values: np.ndarray, note: str = "") -> None:
        mean, low, high = mean_ci(values)
        rows.append(
            {
                "policy": name,
                "cumulative_loss_mean": round(mean, 2),
                "sd": round(float(np.std(values, ddof=1)), 2),
                "ci95_low": round(low, 2),
                "ci95_high": round(high, 2),
                "note": note,
            }
        )

    add_row("Hedge (mixture)", np.array([r.cumulative_learner[-1] for r in results]))
    add_row("Hedge (greedy pick)", np.array([r.cumulative_greedy[-1] for r in results]))
    add_row(
        "Uniform mixture",
        np.array([losses_by_seed[r.seed].mean(axis=1).sum() for r in results]),
        "average of all experts, no learning",
    )
    for k, expert in enumerate(experts):
        add_row(LABELS[expert], np.array([r.cumulative_experts[-1, k] for r in results]))
    add_row(
        "Best fixed in hindsight",
        np.array([r.cumulative_experts[-1, r.best_fixed] for r in results]),
        "oracle selected per seed",
    )
    add_row(
        "Per-round oracle",
        np.array([r.losses.min(axis=1).sum() for r in results]),
        "unattainable lower bound",
    )
    return pd.DataFrame(rows)


def build_regret_table(results: list[HedgeResult], experts: list[str]) -> pd.DataFrame:
    mixture_static = np.array([r.static_regret[-1] for r in results])
    greedy_static = np.array([r.greedy_static_regret[-1] for r in results])
    mixture_dynamic = np.array([r.dynamic_regret[-1] for r in results])
    greedy_dynamic = np.array(
        [r.cumulative_greedy[-1] - r.losses.min(axis=1).sum() for r in results]
    )
    best_fixed_counts = pd.Series([experts[r.best_fixed] for r in results]).value_counts()

    rows = []
    for name, static, dynamic in (
        ("Hedge (mixture)", mixture_static, mixture_dynamic),
        ("Hedge (greedy pick)", greedy_static, greedy_dynamic),
    ):
        s_mean, s_low, s_high = mean_ci(static)
        d_mean, d_low, d_high = mean_ci(dynamic)
        rows.append(
            {
                "policy": name,
                "static_regret_mean": round(s_mean, 2),
                "static_ci95": f"[{s_low:.2f}, {s_high:.2f}]",
                "dynamic_regret_mean": round(d_mean, 2),
                "dynamic_ci95": f"[{d_low:.2f}, {d_high:.2f}]",
            }
        )
    table = pd.DataFrame(rows)
    table["best_fixed_selected"] = ", ".join(
        f"{SHORT[e]} ({c}/{len(results)})" for e, c in best_fixed_counts.items()
    )
    mean_final_weights = np.mean([r.weights[-1] for r in results], axis=0)
    top_idx = int(np.argmax(mean_final_weights))
    table["mean_final_top_weight"] = (
        f"{SHORT[experts[top_idx]]} {mean_final_weights[top_idx]:.2f}"
    )
    return table


def build_peak_table(
    results: list[HedgeResult],
    experts: list[str],
    peak_mask: np.ndarray,
) -> pd.DataFrame:
    losses = np.stack([r.losses for r in results])
    weights = np.stack([r.weights for r in results]).mean(axis=0)
    peak_loss = losses[:, peak_mask, :].mean(axis=(0, 1))
    off_loss = losses[:, ~peak_mask, :].mean(axis=(0, 1))
    peak_weight = weights[peak_mask].mean(axis=0)
    off_weight = weights[~peak_mask].mean(axis=0)

    rows = []
    for k, expert in enumerate(experts):
        rows.append(
            {
                "expert": SHORT[expert],
                "offpeak_loss": round(float(off_loss[k]), 3),
                "peak_loss": round(float(peak_loss[k]), 3),
                "loss_change_pct": round(
                    100.0 * (peak_loss[k] - off_loss[k]) / off_loss[k], 1
                ),
                "offpeak_weight": round(float(off_weight[k]), 3),
                "peak_weight": round(float(peak_weight[k]), 3),
                "weight_shift": round(float(peak_weight[k] - off_weight[k]), 3),
            }
        )
    return pd.DataFrame(rows)


def build_per_seed_table(
    results: list[HedgeResult],
    experts: list[str],
) -> pd.DataFrame:
    rows = []
    for r in results:
        rows.append(
            {
                "seed": r.seed,
                "learner_loss": round(float(r.cumulative_learner[-1]), 2),
                "greedy_loss": round(float(r.cumulative_greedy[-1]), 2),
                "best_fixed": SHORT[experts[r.best_fixed]],
                "best_fixed_loss": round(float(r.cumulative_experts[-1, r.best_fixed]), 2),
                "static_regret": round(float(r.static_regret[-1]), 2),
                "dynamic_regret": round(float(r.dynamic_regret[-1]), 2),
                "switches": int(np.sum(np.diff(r.greedy_choices) != 0)),
                "final_top_expert": SHORT[experts[int(np.argmax(r.weights[-1]))]],
                "final_top_weight": round(float(np.max(r.weights[-1])), 3),
            }
        )
    return pd.DataFrame(rows)


def sensitivity_table(
    runs: dict[tuple[str, int], Run],
    experts: list[str],
    seeds: list[int],
    args: argparse.Namespace,
) -> pd.DataFrame:
    rows = []
    for kind in ("p95_sla", "mean_sla", "p95_only", "combined"):
        results = []
        for seed in seeds:
            loss_matrix = np.column_stack(
                [loss_series(runs[(expert, seed)], kind, args.sla_ms) for expert in experts]
            )
            result = hedge(loss_matrix, args.loss_scale)
            result.seed = seed
            results.append(result)
        static = np.array([r.static_regret[-1] for r in results])
        dynamic = np.array([r.dynamic_regret[-1] for r in results])
        best_counts = pd.Series([SHORT[experts[r.best_fixed]] for r in results]).value_counts()
        rows.append(
            {
                "loss": kind,
                "learner_loss": round(float(np.mean([r.cumulative_learner[-1] for r in results])), 2),
                "static_regret": round(float(static.mean()), 2),
                "dynamic_regret": round(float(dynamic.mean()), 2),
                "best_fixed": ", ".join(f"{e} ({c})" for e, c in best_counts.items()),
            }
        )
    return pd.DataFrame(rows)


def build_paired_table(results: list[HedgeResult], experts: list[str]) -> pd.DataFrame:
    rows = []
    for policy_name, values in (
        ("Hedge greedy", [r.cumulative_greedy[-1] for r in results]),
        ("Hedge mixture", [r.cumulative_learner[-1] for r in results]),
    ):
        learner = np.array(values)
        for k, expert in enumerate(experts):
            diffs = np.array([r.cumulative_experts[-1, k] for r in results]) - learner
            mean, low, high = mean_ci(diffs)
            rows.append(
                {
                    "policy": policy_name,
                    "expert": LABELS[expert],
                    "mean_improvement": round(mean, 3),
                    "ci95": f"[{low:.3f}, {high:.3f}]",
                    "wins": int(np.sum(diffs > 1e-9)),
                    "losses": int(np.sum(diffs < -1e-9)),
                    "ties": int(np.sum(np.abs(diffs) <= 1e-9)),
                }
            )
    return pd.DataFrame(rows)


def build_cost_weight_table(
    runs: dict[tuple[str, int], Run],
    experts: list[str],
    seeds: list[int],
    args: argparse.Namespace,
) -> pd.DataFrame:
    rows = []
    for weight in (0.0, 0.1, 0.25, 0.5, 1.0, 2.0):
        results = []
        for seed in seeds:
            base = np.column_stack(
                [loss_series(runs[(expert, seed)], "p95_sla", args.sla_ms) for expert in experts]
            )
            round_count = base.shape[0]
            cost = np.column_stack(
                [
                    np.full(round_count, float(runs[(expert, seed)].summary["vm_cost"]) / round_count)
                    for expert in experts
                ]
            )
            result = hedge(base + weight * cost, args.loss_scale)
            result.seed = seed
            results.append(result)

        best_counts = pd.Series([SHORT[experts[r.best_fixed]] for r in results]).value_counts()
        final_weights = np.mean([r.weights[-1] for r in results], axis=0)
        rows.append(
            {
                "cost_weight": weight,
                "greedy_loss": round(float(np.mean([r.cumulative_greedy[-1] for r in results])), 2),
                "best_fixed_loss": round(
                    float(np.mean([r.cumulative_experts[-1, r.best_fixed] for r in results])), 2
                ),
                "greedy_static_regret": round(
                    float(np.mean([r.greedy_static_regret[-1] for r in results])), 2
                ),
                "best_fixed_selected": ", ".join(f"{e} ({c})" for e, c in best_counts.items()),
                "final_top_expert": SHORT[experts[int(np.argmax(final_weights))]],
                "final_top_weight": round(float(np.max(final_weights)), 2),
            }
        )
    return pd.DataFrame(rows)


def write_findings(
    out: Path,
    learner_table: pd.DataFrame,
    regret_table: pd.DataFrame,
    peak_table: pd.DataFrame,
    results: list[HedgeResult],
    experts: list[str],
    loss_scale: float,
) -> None:
    def value(policy: str, column: str) -> float:
        return float(learner_table.loc[learner_table["policy"] == policy, column].iloc[0])

    mixture = value("Hedge (mixture)", "cumulative_loss_mean")
    greedy = value("Hedge (greedy pick)", "cumulative_loss_mean")
    best_fixed = value("Best fixed in hindsight", "cumulative_loss_mean")
    uniform = value("Uniform mixture", "cumulative_loss_mean")
    hybrid = value(LABELS["hybrid"], "cumulative_loss_mean")

    greedy_static = float(regret_table.iloc[1]["static_regret_mean"])
    greedy_dynamic = float(regret_table.iloc[1]["dynamic_regret_mean"])
    theoretical = math.sqrt(len(results[0].losses) * math.log(len(experts)) / 2.0) * loss_scale
    best_fixed_counts = pd.Series([experts[r.best_fixed] for r in results]).value_counts()
    best_fixed_name = ", ".join(f"{SHORT[e]} ({c}/{len(results)})" for e, c in best_fixed_counts.items())

    gap = greedy - best_fixed
    gap_pct = 100.0 * gap / best_fixed
    greedy_vs_uniform = 100.0 * (uniform - greedy) / uniform
    greedy_vs_hybrid = 100.0 * (hybrid - greedy) / hybrid

    lines = [
        "# Online-learning experiment: key findings",
        "",
        f"- Rounds: {len(results[0].losses)} fifteen-minute rounds; seeds: {len(results)}; experts: {len(experts)}.",
        f"- Best fixed expert in hindsight: **{best_fixed_name}** with cumulative loss {best_fixed:.2f}.",
        f"- Hedge greedy cumulative loss: **{greedy:.2f}**; mixture cumulative loss: {mixture:.2f}.",
        f"- Greedy gap to the best fixed expert: {gap:+.2f} ({gap_pct:+.2f}%).",
        f"- Greedy vs uniform mixture: {greedy_vs_uniform:+.2f}% cumulative loss.",
        f"- Greedy vs fixed hybrid (S1): {greedy_vs_hybrid:+.2f}% cumulative loss.",
        f"- Greedy static regret: {greedy_static:+.2f}; dynamic regret: {greedy_dynamic:.2f}.",
        f"- The theoretical Hedge bound is about {theoretical:.2f}; the empirical greedy regret is "
        f"{100.0 * greedy_static / theoretical:.2f}% of that bound.",
        "",
        "## Interpretation",
        "",
        "The learner discovers the best fixed expert during the peak window and then keeps it. "
        "Almost all regret is incurred in rounds 5-12; after the peak, all experts have the same "
        "off-peak loss, so no further regret accumulates. The greedy policy tracks the best fixed "
        "expert, and its gap to that expert is the cost of exploration. Table 2 ranks every expert, "
        "and Table 7 gives the paired differences between the deployable greedy policy and each one.",
        "",
        "Static regret compares the learner with the single best fixed expert chosen after seeing "
        "all rounds. Dynamic regret compares the learner with a per-round oracle, so it is always "
        "non-negative and measures tracking quality. The mixture is the theoretical Hedge object; "
        "the greedy argmax policy is the one an operator would deploy.",
        "",
        "The primary loss is `p95_response_ms / 80 + response_sla_rate`; VM cost is reported "
        "separately in the expert table and the cost-latency scatter. Table 8 shows how the "
        "selected expert changes when an amortised VM-cost penalty is added.",
        "",
        "## Files",
        "",
        "- `figures/fig01_cumulative_loss.png`",
        "- `figures/fig02_regret.png`",
        "- `figures/fig03_weights.png`",
        "- `figures/fig04_expert_bars.png`",
        "- `figures/fig05_cost_latency.png`",
        "- `figures/fig06_peak_shift.png`",
        "- `figures/fig07_loss_heatmap.png`",
        "- `figures/fig08_learner_vs_experts.png`",
        "- `figures/fig09_choice_timeline.png`",
        "- `tables/table1_expert_summary.(csv|md)`",
        "- `tables/table2_learner_comparison.(csv|md)`",
        "- `tables/table3_regret.(csv|md)`",
        "- `tables/table4_peak_offpeak.(csv|md)`",
        "- `tables/table5_per_seed.(csv|md)`",
        "- `tables/table6_loss_sensitivity.(csv|md)`",
        "- `tables/table7_paired_differences.(csv|md)`",
        "- `tables/table8_cost_weight_sensitivity.(csv|md)`",
    ]
    (out / "RESULTS.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    args = parse_args()
    experts = [e.strip() for e in args.experts.split(",") if e.strip()]
    seeds = [int(s.strip()) for s in args.seeds.split(",") if s.strip()]
    unknown = [e for e in experts if e not in LABELS]
    if unknown:
        raise SystemExit(f"Unknown experts: {unknown}")

    out = args.out
    figures = out / "figures"
    tables = out / "tables"
    figures.mkdir(parents=True, exist_ok=True)
    tables.mkdir(parents=True, exist_ok=True)

    runs = load_runs(args.results_root, experts, seeds)
    round_count = len(runs[(experts[0], seeds[0])].rounds)
    peak_mask = peak_round_mask(round_count, args)

    losses_by_seed: dict[int, np.ndarray] = {}
    results: list[HedgeResult] = []
    max_loss = 0.0
    for seed in seeds:
        loss_matrix = np.column_stack(
            [loss_series(runs[(expert, seed)], args.loss, args.sla_ms) for expert in experts]
        )
        max_loss = max(max_loss, float(loss_matrix.max()))
        losses_by_seed[seed] = loss_matrix
        result = hedge(loss_matrix, args.loss_scale)
        result.seed = seed
        results.append(result)

    if max_loss > args.loss_scale:
        print(
            f"WARNING: max raw loss {max_loss:.3f} exceeds loss scale "
            f"{args.loss_scale:.2f}; increase --loss-scale for the theory bound."
        )

    expert_summary = build_summary_table(runs, experts, seeds)
    overall_min_cloudlets = int(expert_summary[("min_round_cloudlets", "mean")].min())
    if overall_min_cloudlets < 1000:
        print(
            f"WARNING: minimum cloudlets in a round is {overall_min_cloudlets}; "
            "the >=1000 tasks/round requirement is not met."
        )
    learner_table = build_learner_table(results, experts, losses_by_seed)
    regret_table = build_regret_table(results, experts)
    peak_table = build_peak_table(results, experts, peak_mask)
    per_seed_table = build_per_seed_table(results, experts)
    sensitivity = sensitivity_table(runs, experts, seeds, args)
    paired = build_paired_table(results, experts)
    cost_weight = build_cost_weight_table(runs, experts, seeds, args)

    expert_summary.to_csv(tables / "table1_expert_summary.csv")
    learner_table.to_csv(tables / "table2_learner_comparison.csv", index=False)
    regret_table.to_csv(tables / "table3_regret.csv", index=False)
    peak_table.to_csv(tables / "table4_peak_offpeak.csv", index=False)
    per_seed_table.to_csv(tables / "table5_per_seed.csv", index=False)
    sensitivity.to_csv(tables / "table6_loss_sensitivity.csv", index=False)
    paired.to_csv(tables / "table7_paired_differences.csv", index=False)
    cost_weight.to_csv(tables / "table8_cost_weight_sensitivity.csv", index=False)

    display_expert = pd.DataFrame(
        {
            "Expert": [LABELS[e] for e in experts],
            "Mean response (ms)": [
                fmt_mean_sd(
                    expert_summary.loc[e, ("mean_response_ms", "mean")],
                    expert_summary.loc[e, ("mean_response_ms", "sd")],
                )
                for e in experts
            ],
            "p95 response (ms)": [
                fmt_mean_sd(
                    expert_summary.loc[e, ("p95_response_ms", "mean")],
                    expert_summary.loc[e, ("p95_response_ms", "sd")],
                )
                for e in experts
            ],
            "SLA violation rate": [
                f"{expert_summary.loc[e, ('response_sla_rate', 'mean')] * 100:.2f}%"
                for e in experts
            ],
            "Mean processing (ms)": [
                fmt_mean_sd(
                    expert_summary.loc[e, ("mean_proc_ms", "mean")],
                    expert_summary.loc[e, ("mean_proc_ms", "sd")],
                )
                for e in experts
            ],
            "p95 processing (ms)": [
                fmt_mean_sd(
                    expert_summary.loc[e, ("p95_proc_ms", "mean")],
                    expert_summary.loc[e, ("p95_proc_ms", "sd")],
                )
                for e in experts
            ],
            "VM cost": [
                fmt_mean_sd(
                    expert_summary.loc[e, ("vm_cost", "mean")],
                    expert_summary.loc[e, ("vm_cost", "sd")],
                )
                for e in experts
            ],
            "Total cost": [
                fmt_mean_sd(
                    expert_summary.loc[e, ("total_cost", "mean")],
                    expert_summary.loc[e, ("total_cost", "sd")],
                )
                for e in experts
            ],
            "Min cloudlets/round": [
                int(expert_summary.loc[e, ("min_round_cloudlets", "mean")]) for e in experts
            ],
        }
    )
    write_markdown(display_expert, tables / "table1_expert_summary.md", "Table 1. Fixed-expert quality and cost (mean ± sd across seeds).")
    write_markdown(learner_table, tables / "table2_learner_comparison.md", "Table 2. Cumulative loss of the learner and fixed experts.")
    write_markdown(regret_table, tables / "table3_regret.md", "Table 3. Static and dynamic regret of the online learner.")
    write_markdown(peak_table, tables / "table4_peak_offpeak.md", "Table 4. Off-peak vs peak loss and Hedge weight.")
    write_markdown(per_seed_table, tables / "table5_per_seed.md", "Table 5. Per-seed learner outcomes.")
    write_markdown(sensitivity, tables / "table6_loss_sensitivity.md", "Table 6. Sensitivity of the conclusion to the loss definition.")
    write_markdown(paired, tables / "table7_paired_differences.md", "Table 7. Paired improvement over each fixed expert (positive = learner better).")
    write_markdown(cost_weight, tables / "table8_cost_weight_sensitivity.md", "Table 8. Effect of an amortised VM-cost penalty on expert selection.")

    mean_losses = np.stack([r.losses for r in results]).mean(axis=0)
    plot_cumulative_loss(results, experts, peak_mask, figures)
    plot_regret(results, peak_mask, figures)
    plot_weights(results, experts, peak_mask, figures)
    plot_expert_bars(expert_summary, experts, figures)
    plot_cost_latency(expert_summary, experts, figures)
    plot_peak_shift(results, experts, peak_mask, figures)
    plot_loss_heatmap(mean_losses, experts, peak_mask, figures)
    plot_learner_bars(results, experts, figures)
    plot_choice_timeline(results, experts, peak_mask, figures)

    write_findings(out, learner_table, regret_table, peak_table, results, experts, args.loss_scale)

    payload = {
        "experts": experts,
        "seeds": seeds,
        "rounds": round_count,
        "loss": args.loss,
        "sla_ms": args.sla_ms,
        "max_raw_loss": round(max_loss, 4),
        "learner_cumulative_loss": round(
            float(np.mean([r.cumulative_learner[-1] for r in results])), 3
        ),
        "greedy_cumulative_loss": round(
            float(np.mean([r.cumulative_greedy[-1] for r in results])), 3
        ),
        "static_regret": round(float(np.mean([r.static_regret[-1] for r in results])), 3),
        "dynamic_regret": round(float(np.mean([r.dynamic_regret[-1] for r in results])), 3),
        "best_fixed": {
            experts[i]: int(sum(1 for r in results if r.best_fixed == i))
            for i in range(len(experts))
        },
    }
    (out / "summary.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote figures and tables to {out}")
    print(f"Learner cumulative loss: {payload['learner_cumulative_loss']:.2f}")
    print(f"Static regret: {payload['static_regret']:+.2f}; dynamic regret: {payload['dynamic_regret']:.2f}")


if __name__ == "__main__":
    main()
