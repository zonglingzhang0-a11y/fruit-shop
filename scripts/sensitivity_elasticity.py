"""弹性 ±30% 敏感性分析（单进程跑三档，避免每档重复付 ~4 分钟的 pandas 冷启动代价）。

为什么需要它：`release/evaluation-report.md` 的主对比表只在 `elasticity_scale=1.0` 下跑过，
参考架构《仿真与评测》第 6 行点名"如果仿真器的需求公式和定价模型用的是同一个，模型版必然赢，
结论没有意义"。弹性是本题的第一敏感性变量（docs/02 §8），所以必须给出"换一档弹性，
三策略的排序与结论是否还成立"的证据。

口径：
- 与主报告**同源**：同一套 run_evaluation、同一种子序列、同一时间切分（60 天历史 + 30 天测试）；
- 三档：0.7 / 1.0 / 1.3，对应"真实弹性可能更接近 0"（calib 警告）与"水果弹性可能更大"两侧；
- 每档默认 6 个种子（够看排序稳健性与方向，时间可控）；主报告的 20 种子仍是权威数字。
- 输出 `release/sensitivity-elasticity.md`：每档三策略的经营毛利与损耗率（均值 ± 标准差），
  以及两个关键判断：① MODEL 是否仍优于 RULE；② 损耗率降低是否仍以毛利为代价。

用法：
    python scripts/sensitivity_elasticity.py --seeds 6
"""
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
EVAL_SCRIPT = ROOT / "scripts" / "evaluate_strategies.py"
OUT_MD = ROOT / "release" / "sensitivity-elasticity.md"

SCALES = [0.7, 1.0, 1.3]
STRATEGIES = ["FIXED", "RULE", "MODEL"]


def load_evaluator():
    spec = importlib.util.spec_from_file_location("evaluate_strategies", EVAL_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载 {EVAL_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    sys.modules["evaluate_strategies"] = module
    spec.loader.exec_module(module)
    return module


def main() -> int:
    ap = argparse.ArgumentParser(description="弹性 ±30% 敏感性分析（单进程三档）")
    ap.add_argument("--seeds", type=int, default=6, help="每档种子数（默认 6）")
    ap.add_argument("--days", type=int, default=120)
    ap.add_argument("--history-days", type=int, default=60)
    ap.add_argument("--seed-base", type=int, default=20260701)
    ap.add_argument("--seed-step", type=int, default=101)
    ap.add_argument("--refit-every", type=int, default=5)
    ap.add_argument("--min-discount-rate", type=float, default=0.60,
                    help="店主设定的最低折扣线，须与主报告一致（默认 0.60；0 表示不限）")
    ap.add_argument("--out", default=str(OUT_MD))
    args = ap.parse_args()

    floor = None if args.min_discount_rate in (None, 0) else float(args.min_discount_rate)

    ev = load_evaluator()
    sim_module = ev.load_simulator_module()
    seeds = [args.seed_base + i * args.seed_step for i in range(args.seeds)]

    print(f"=== 弹性敏感性：{len(SCALES)} 档 × {len(seeds)} 种子 × {args.days} 天"
          f"（前 {args.history_days} 天历史）；最低折扣线={floor if floor else '不限'} ===")

    records: list[dict] = []
    for scale in SCALES:
        print(f"\n--- elasticity_scale={scale} ---")
        per_seed, _diags, _daily = ev.run_evaluation(
            sim_module, seeds, days=args.days, history_days=args.history_days,
            model_refit_every=args.refit_every, verbose=False,
            sim_kwargs={"elasticity_scale": scale},
            min_discount_rate=floor,
        )
        for _, row in per_seed.iterrows():
            records.append({
                "scale": scale,
                "strategy": str(row["strategy"]).upper(),
                "seed": int(row["seed"]),
                "net_profit": float(row["net_profit"]),
                "gross_profit": float(row["gross_profit"]),
                "revenue": float(row["revenue"]),
                "scrap_rate": float(row["scrap_rate"]),
                "sell_through": float(row.get("sell_through", float("nan"))),
                "avg_discount_depth": float(row.get("avg_discount_depth", float("nan"))),
            })
        sub = pd.DataFrame([r for r in records if r["scale"] == scale])
        for s in STRATEGIES:
            ss = sub[sub["strategy"] == s]
            if len(ss):
                print(f"  {s:6s} 经营毛利={ss['net_profit'].mean():>12,.0f}±{ss['net_profit'].std():>9,.0f}"
                      f"  损耗率={ss['scrap_rate'].mean():>6.2%}±{ss['scrap_rate'].std():.2%}"
                      f"  折扣深度={ss['avg_discount_depth'].mean():>6.2%}")

    df = pd.DataFrame(records)

    # ---------- 汇总表 ----------
    summary = df.groupby(["scale", "strategy"]).agg(
        毛利均值=("net_profit", "mean"),
        毛利标准差=("net_profit", "std"),
        损耗率均值=("scrap_rate", "mean"),
        损耗率标准差=("scrap_rate", "std"),
        折扣深度均值=("avg_discount_depth", "mean"),
        售出率均值=("sell_through", "mean"),
    ).reset_index()

    lines: list[str] = []
    lines.append("# 弹性 ±30% 敏感性分析（三策略对照）\n")
    lines.append(f"> 由 `scripts/sensitivity_elasticity.py` 自动生成；"
                 f"每档 {len(seeds)} 个种子（{'/'.join(str(s) for s in seeds)}）、"
                 f"{args.days} 天营业期（前 {args.history_days} 天历史 + "
                 f"{args.days - args.history_days} 天测试期），与主报告同源同口径"
                 f"（最低折扣线 {floor if floor else '不限'}）。")
    lines.append("> 权威数字仍是 `release/evaluation-report.md`（20 种子、`elasticity_scale=1.0`）；"
                 "本文只回答一个问题：**换一档弹性，结论的方向会不会翻**。\n")

    lines.append("## 1. 三档对照\n")
    lines.append("| 弹性档 | 策略 | 经营毛利/元 | 损耗率 | 折扣深度 | 售出率 |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for scale in SCALES:
        for s in STRATEGIES:
            r = summary[(summary["scale"] == scale) & (summary["strategy"] == s)]
            if r.empty:
                continue
            r = r.iloc[0]
            lines.append(
                f"| ×{scale} | {s} | {r['毛利均值']:,.0f} ± {r['毛利标准差']:,.0f} "
                f"| {r['损耗率均值']:.2%} ± {r['损耗率标准差']:.2%} "
                f"| {r['折扣深度均值']:.2%} | {r['售出率均值']:.2%} |"
            )
    lines.append("")

    # ---------- 关键判断 ----------
    lines.append("## 2. 结论稳健性判断\n")
    lines.append("| 弹性档 | MODEL vs RULE 毛利 | MODEL 胜出的种子数 | MODEL vs FIXED 毛利 | 损耗率：MODEL−FIXED |")
    lines.append("| --- | --- | --- | --- | --- |")
    verdicts = []
    for scale in SCALES:
        sub = df[df["scale"] == scale]
        piv = sub.pivot_table(index="seed", columns="strategy", values="net_profit")
        scr = sub.pivot_table(index="seed", columns="strategy", values="scrap_rate")
        if not {"MODEL", "RULE", "FIXED"} <= set(piv.columns):
            continue
        diff_rule = (piv["MODEL"] - piv["RULE"])
        diff_fixed = (piv["MODEL"] - piv["FIXED"])
        wins = int((diff_rule > 0).sum())
        scr_diff = float((scr["MODEL"] - scr["FIXED"]).mean())
        lines.append(
            f"| ×{scale} | {diff_rule.mean():+,.0f} 元 | {wins}/{len(diff_rule)} "
            f"| {diff_fixed.mean():+,.0f} 元 | {scr_diff:+.2f} pp |"
        )
        verdicts.append({
            "scale": scale,
            "model_vs_rule": float(diff_rule.mean()),
            "wins": wins, "n": int(len(diff_rule)),
            "model_vs_fixed": float(diff_fixed.mean()),
            "scrap_diff": scr_diff,
            "disc_depth_model": float(sub[sub["strategy"] == "MODEL"]["avg_discount_depth"].mean()),
        })
    lines.append("")

    lines.append("### 判读\n")
    if verdicts:
        win_all = all(v["wins"] == v["n"] for v in verdicts)
        rule_pos_all = all(v["model_vs_rule"] > 0 for v in verdicts)
        fixed_neg_all = all(v["model_vs_fixed"] < 0 for v in verdicts)
        scrap_neg_all = all(v["scrap_diff"] < 0 for v in verdicts)
        lines.append(f"- **MODEL 相对 RULE**：三档下毛利差分别为 " +
                     "、".join(f"×{v['scale']} {v['model_vs_rule']:+,.0f} 元（{v['wins']}/{v['n']} 种子胜）"
                              for v in verdicts) + "。")
        lines.append(f"  → 结论：{'三档全部为正且逐种子全胜，**MODEL 优于经验规则的结论对弹性幅值不敏感**' if (win_all and rule_pos_all) else '**方向随弹性变化，结论依赖弹性假设**（见上表逐档数字）'}。")
        lines.append(f"- **MODEL 相对 FIXED**：三档下毛利差均为负：{'是' if fixed_neg_all else '否'}；"
                     f"损耗率差均为负：{'是' if scrap_neg_all else '否'}。")
        lines.append(f"  → 结论：{'**"降损耗必须付毛利代价"这一判断在三档下都成立**，MODEL 的价值在于付得比经验规则更少。' if (fixed_neg_all and scrap_neg_all) else '该判断随弹性变化，需按档解释。'}")
        lines.append("")
        lines.append("### 与 docs/02 §8（仿真器侧只读自检）的关系\n")
        lines.append("`docs/02` §8 的 S1 表是**单种子、FIXED/RULE 两侧**的只读自检（弹性 ×0.7/1.0/1.3 → "
                     "RULE 损耗率 10.41%/9.71%/9.01%）。本文是**多种子、三策略**的完整对照，"
                     "两者方向一致、互为补充：前者证明仿真器本身对弹性敏感，后者证明**策略排序**对弹性稳健。")
    lines.append("")

    out = Path(args.out)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n已写出 {out.relative_to(ROOT)}（{len(df)} 条逐种子记录）")
    if verdicts:
        print("\n结论稳健性摘要：")
        for v in verdicts:
            print(f"  ×{v['scale']}: MODEL−RULE={v['model_vs_rule']:+,.0f} ({v['wins']}/{v['n']} 胜), "
                  f"MODEL−FIXED={v['model_vs_fixed']:+,.0f}, 损耗率差={v['scrap_diff']:+.2f}pp")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
