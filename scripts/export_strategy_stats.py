"""把三策略评测的**逐日**结果导出成系统看板可直接导入的 CSV。

为什么需要它：后端 `GET /api/sales/effect` 读 `strategy_daily_stat` 表，而该表只有
"现场销售回流"才会写入。没有这份种子数据时，答辩打开"效果看板"是空的（实测全 0）。

产出（列名与后端 SeedDataLoader 严格约定，勿改）：
    data/seed/strategy_daily_stat.csv
    biz_date,strategy,sold_qty,scrap_qty,revenue,cost,gross_profit,scrap_rate,stockout_rate

口径（会同步写进 release/evaluation-report.md 的复现段）：
- 与 `evaluate_strategies.py --seeds N` **同源**：同一批判定逻辑、同一批种子、同一时间切分（测试期 30 天）。
- 逐日明细取自**第 1 个种子**（seed-base），因为评测脚本的逐日表给的是"当日值"，
  评测脚本的逐日输出里没有"当日进货量"这一列，所以：
    * `sold_qty / scrap_qty / revenue / cost / gross_profit` 都是该种子当日的真实值；
    * `scrap_rate` 用 **跨种子平均进货量**（每策略一个常数，来自采样的多种子）作分母。
      损耗率在天与天之间会有波动（因为分母是常数），但策略之间的高低关系与主报告一致。
      这里不假装它是"当日进货量口径"，报告与文档都会写明。
- `cost` = 销售成本(cogs)；`gross_profit` = revenue − cost（不含报废成本的"毛毛利"），
  与主报告第 3 节的口径一致；看板另列损耗率，读者可自行判断报废的影响。

用法：
    python scripts/export_strategy_stats.py             # 20 种子采样 + 1 种子逐日，约 1.5 分钟
    python scripts/export_strategy_stats.py --rate-seeds 3
"""
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
EVAL_SCRIPT = ROOT / "scripts" / "evaluate_strategies.py"
OUT_CSV = ROOT / "data" / "seed" / "strategy_daily_stat.csv"

REQUIRED_COLS = ["biz_date", "strategy", "sold_qty", "scrap_qty", "revenue",
                 "cost", "gross_profit", "scrap_rate", "stockout_rate"]


def load_evaluator():
    """把 evaluate_strategies.py 当模块导入（文件名不是合法模块名，故用 spec 加载）。"""
    spec = importlib.util.spec_from_file_location("evaluate_strategies", EVAL_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载 {EVAL_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    sys.modules["evaluate_strategies"] = module
    spec.loader.exec_module(module)
    return module


def daily_from_cumulative(daily: pd.DataFrame, strategy: str) -> pd.DataFrame:
    """评测的逐日表给的是累计量（scrap_qty_cum），差分回当日值。"""
    sub = daily[daily["strategy"] == strategy].sort_values("biz_date").copy()
    if sub.empty:
        return sub
    sub["scrap_qty"] = sub["scrap_qty_cum"].astype(float).diff().fillna(
        sub["scrap_qty_cum"].astype(float).iloc[0])
    sub["sold_qty"] = pd.to_numeric(sub["sold_qty"], errors="coerce").fillna(0.0)
    return sub


def main() -> int:
    ap = argparse.ArgumentParser(description="导出三策略逐日 KPI（供系统看板）")
    ap.add_argument("--rate-seeds", type=int, default=20,
                    help="用于计算平均进货量（损耗率分母）的种子数")
    # 注意：--days 必须与种子 CSV 的营业期一致（simulator run --days 120），
    # 否则测试期的日历日期会落在种子数据的区间之外，系统看板按 bizDate 查不到数据
    # （踩过一次：默认 90 天 → 测试期是 2026-05-01~05-30，而种子数据是 03-02~06-29）。
    ap.add_argument("--days", type=int, default=120,
                    help="仿真营业期天数，须与 data/seed/shop_*.csv 一致（默认 120）")
    ap.add_argument("--history-days", type=int, default=60)
    ap.add_argument("--seed-base", type=int, default=20260701)
    ap.add_argument("--seed-step", type=int, default=101)
    ap.add_argument("--refit-every", type=int, default=5)
    ap.add_argument("--min-discount-rate", type=float, default=0.60,
                    help="店主设定的最低折扣线（价格比下限），须与主报告 "
                         "evaluate_strategies.py --min-discount-rate 一致；0 表示不限")
    ap.add_argument("--out", default=str(OUT_CSV))
    args = ap.parse_args()

    floor = None if args.min_discount_rate in (None, 0) else float(args.min_discount_rate)

    ev = load_evaluator()
    sim_module = ev.load_simulator_module()

    # ---- 1) 采样多个种子，取"平均进货量"作为损耗率分母（同源检查已证明三策略进货量相同）
    seeds = [args.seed_base + i * args.seed_step for i in range(args.rate_seeds)]
    print(f"=== 采样 {len(seeds)} 个种子，计算平均进货量（损耗率分母）===")
    per_seed, _diags, daily = ev.run_evaluation(
        sim_module, seeds, days=args.days, history_days=args.history_days,
        model_refit_every=args.refit_every, verbose=False,
        min_discount_rate=floor,
    )
    if daily is None or len(daily) == 0:
        print("评测未返回逐日结果", file=sys.stderr)
        return 2
    print(f"逐日明细 {len(daily)} 行，列：{list(daily.columns)}")

    qty_col = "purchase_qty" if "purchase_qty" in per_seed.columns else None
    if qty_col is None:
        print(f"per-seed 结果没有进货量列，实际列：{list(per_seed.columns)}", file=sys.stderr)
        return 3

    # 测试期天数：用逐日表的日期数
    test_days = int(daily["biz_date"].nunique()) if "biz_date" in daily.columns else args.days - args.history_days
    denom = per_seed.groupby("strategy")[qty_col].mean() / max(test_days, 1)
    print("\n每策略 平均日进货量（损耗率分母）:")
    print(denom.to_string(float_format=lambda v: f"{v:,.2f}"))

    # ---- 2) 用第 1 个种子（seed_base，也就是主报告里展示的那个 seed）的逐日明细
    first_seed = seeds[0]
    d1 = daily[daily["seed"] == first_seed].copy()
    frames = []
    for strategy in ["FIXED", "RULE", "MODEL"]:
        sub = daily_from_cumulative(d1, strategy)
        if sub.empty:
            continue
        sub["strategy"] = strategy
        sub["cost"] = sub["cogs"].astype(float)
        sub["gross_profit"] = sub["revenue"].astype(float) - sub["cost"]
        sub["scrap_rate"] = sub["scrap_qty"] / float(denom.get(strategy, 1.0))
        sub["stockout_rate"] = 0.0  # 逐日表无此项，看板不做逐日缺货率（主报告有区间口径）
        frames.append(sub)

    out = pd.concat(frames, ignore_index=True)
    out = pd.DataFrame({
        "biz_date": out["biz_date"].astype(str),
        "strategy": out["strategy"].astype(str).str.upper(),
        "sold_qty": pd.to_numeric(out["sold_qty"], errors="coerce").round(2),
        "scrap_qty": pd.to_numeric(out["scrap_qty"], errors="coerce").round(2),
        "revenue": pd.to_numeric(out["revenue"], errors="coerce").round(2),
        "cost": pd.to_numeric(out["cost"], errors="coerce").round(2),
        "gross_profit": pd.to_numeric(out["gross_profit"], errors="coerce").round(2),
        "scrap_rate": pd.to_numeric(out["scrap_rate"], errors="coerce").round(6),
        "stockout_rate": pd.to_numeric(out["stockout_rate"], errors="coerce").round(6),
    })[REQUIRED_COLS].sort_values(["strategy", "biz_date"]).reset_index(drop=True)

    assert list(out.columns) == REQUIRED_COLS, "列名不符合后端契约"
    assert set(out["strategy"]) == {"FIXED", "RULE", "MODEL"}, f"策略枚举异常: {set(out['strategy'])}"
    assert not out[["sold_qty", "scrap_qty", "revenue", "cost", "gross_profit"]].isna().any().any(), "存在空值"
    dev = float((out["gross_profit"] - (out["revenue"] - out["cost"])).abs().max())
    assert dev < 0.02, f"毛利恒等式偏差过大: {dev}"

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out.to_csv(out_path, index=False, encoding="utf-8")
    print(f"\n已写出 {out_path.relative_to(ROOT)}：{len(out)} 行"
          f"（{out['strategy'].nunique()} 策略 × {out['biz_date'].nunique()} 天，来自 seed={first_seed}）")

    print("\n每策略汇总（该种子测试期）:")
    summary = out.groupby("strategy").agg(
        天数=("biz_date", "nunique"),
        销量=("sold_qty", "sum"),
        报废量=("scrap_qty", "sum"),
        营收=("revenue", "sum"),
        销售成本=("cost", "sum"),
        毛利=("gross_profit", "sum"),
        平均损耗率=("scrap_rate", "mean"),
    )
    print(summary.to_string(float_format=lambda v: f"{v:,.2f}"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
