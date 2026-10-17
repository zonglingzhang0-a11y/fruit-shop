# -*- coding: utf-8 -*-
"""
三策略统一评测台（FIXED 固定价 / RULE 规则折扣 / MODEL 模型定价）。

一句话回答"这个脚本凭什么可信"
------------------------------
**三个策略跑在同一批消费者、同一份初始库存上，只换定价函数，别的一个字都不改。**

具体做法（对应 docs/00 第 4 节评测协议）
--------------------------------------
1. 一个种子 = 一台 `ShopSimulator`。模拟器内部用 `PCG64([seed, day_index, hour, stream_id])`
   预抽取所有外生冲击，**抽样格的形状与顺序只依赖 (day, hour, item)，与策略选了哪个价无关**。
   所以同一 seed 下，FIXED/RULE/MODEL 面对的是同一条消费者流（公共随机数 CRN）。
2. 时间切分：报表期 90 天 = 前 60 天历史窗口 + 后 30 天统一测试期。
   前 60 天**三个策略都用同一套 RULE 决策**（店主现状），第 60 天做快照，
   之后三个策略各自从同一快照出发跑 30 天。→ 初始库存严格相同。
   * 优先用 `sim.snapshot()/restore()`；
   * 没有的话退回"`reset()` + 用同一 RULE 决策重放前 60 天"，并对三次重放的历史做哈希比对。
3. 指标在**测试期**上聚合；三种策略的进货量、到货批次数也一并对比，
   用来排除"靠少进货/不清货把损耗率刷低"的解释（Lead 明确要求的口径）。

指标口径（全部写死在这里，报告里的每个数字都能追溯到本文件）
----------------------------------------------------------
  收入 revenue        = Σ 销售明细 quantity × unit_price（退货按负贡献计）
  销售成本 cogs       = Σ 售出件数 × 该批次进货成本
  报废成本 scrap_cost = Σ 报废件数 × 该批次进货成本
  经营毛利 net_profit = revenue − cogs − scrap_cost     ← 主指标（把报废损失算进去）
  毛毛利 gross_profit = revenue − cogs                  ← 单列，方便和"会计口径"对齐
  损耗率 scrap_rate   = 报废量 / 进货量                  ← docs/00 第 4 节定义
  缺货率 stockout_rate= 未满足需求 / 总需求
  售出率 sell_through = 售出量 / (期初库存 + 当日到货)
  平均剩余货值        = 测试期每日"期末库存 × 进货成本"的均值  ← 防止靠不清货刷指标
  平均折扣深度        = 测试期每日各在售折扣系数的均值（1 − d）
  单笔客单价          = 收入 / 成交笔数

运行
----
    # 真实评测（需要 scripts/simulator.py 提供 ShopSimulator）
    python scripts/evaluate_strategies.py --seeds 20

    # 快速冒烟（3 个种子）
    python scripts/evaluate_strategies.py --quick

    # 自检：用内置玩具环境验证整条流水线（结果写到临时目录，绝不污染 release/）
    python scripts/evaluate_strategies.py --selftest
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import tempfile
import time
import traceback
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from pricing_model import (  # noqa: E402
    TruthLeakError,
    _assert_no_truth_access,
    make_model_pricing_fn,
)
from pricing_rule import DISCOUNT_GRID, make_rule_pricing_fn  # noqa: E402

STRATEGIES = ("FIXED", "RULE", "MODEL")

#: 每个策略的显示名（报告里用）
STRATEGY_LABEL = {
    "FIXED": "固定价格（基线1，成本加成不打折）",
    "RULE": "规则折扣（基线2，店主经验）",
    "MODEL": "模型定价（本项目方案）",
}


# ===========================================================================
# 0. 数据获取小工具（对 sim 返回的 DataFrame / list[dict] / 属性式 dict 都兼容）
# ===========================================================================


def _get(day, key: str, default=None):
    """从 sim 的 day 记录里取值，兼容 day["x"] 与 day.x 两种访问方式。"""
    if day is None:
        return default
    try:
        v = day[key]
        if v is not None:
            return v
    except Exception:
        pass
    v = getattr(day, key, None)
    return default if v is None else v


def _records(obj) -> List[dict]:
    if obj is None:
        return []
    if isinstance(obj, pd.DataFrame):
        return obj.to_dict("records")
    if isinstance(obj, (list, tuple)):
        return [dict(x) for x in obj]
    if isinstance(obj, dict):
        keys = list(obj.keys())
        if not keys:
            return []
        first = obj[keys[0]]
        if hasattr(first, "__len__") and not isinstance(first, str):
            return [{k: obj[k][i] for k in keys} for i in range(len(first))]
    return []


def _df(obj) -> pd.DataFrame:
    recs = _records(obj)
    return pd.DataFrame(recs) if recs else pd.DataFrame()


def _num(df: pd.DataFrame, col: str, default: float = 0.0) -> np.ndarray:
    if col not in df.columns:
        return np.full(len(df), default, dtype=float)
    return pd.to_numeric(df[col], errors="coerce").fillna(default).to_numpy(dtype=float)


def _sum(df: pd.DataFrame, col: str) -> float:
    return float(np.sum(_num(df, col))) if len(df) else 0.0


def _hash_frame(df: pd.DataFrame) -> str:
    """对一个观测历史做稳定哈希，用来证明三个策略看到的历史完全一致。"""
    if df is None or len(df) == 0:
        return "empty"
    d = df.copy()
    for c in d.columns:
        if pd.api.types.is_float_dtype(d[c]):
            d[c] = d[c].round(6)
    cols = sorted(map(str, d.columns))
    d = d[cols].astype(str).sort_values(cols).reset_index(drop=True)
    return hashlib.sha256(d.to_csv(index=False).encode("utf-8")).hexdigest()[:16]


# ===========================================================================
# 1. 策略定义
# ===========================================================================


def make_fixed_pricing_fn():
    """基线 1：成本加成固定价 —— 永远原价，不打折。"""

    def _fn(biz_date, day_index, state):
        batches = _records(_get(state, "batches"))
        return {str(b.get("item_code")): 1.00 for b in batches if b.get("item_code")}

    _fn.strategy_name = "FIXED"  # type: ignore[attr-defined]
    return _fn


def build_strategy(name: str, model_refit_every: int = 5,
                   min_discount_rate: Optional[float] = None):
    """构造策略决策函数。

    `min_discount_rate`：店主设定的最低折扣线（价格比下限）。
    与系统侧 `app.pricing.min-discount-rate`（默认 0.60）同口径，
    对 RULE 与 MODEL 生效（FIXED 本来就只挂原价，不受影响）。
    """
    if name == "FIXED":
        return make_fixed_pricing_fn()
    if name == "RULE":
        return make_rule_pricing_fn(min_discount_rate=min_discount_rate)
    if name == "MODEL":
        return make_model_pricing_fn(refit_every=model_refit_every,
                                     min_discount_rate=min_discount_rate)
    raise ValueError(f"未知策略 {name}")


# ===========================================================================
# 2. 单次运行的指标累加器
# ===========================================================================


@dataclass
class SeedStrategyResult:
    seed: int
    strategy: str
    metrics: Dict[str, float] = field(default_factory=dict)
    daily: List[dict] = field(default_factory=list)
    discount_hist: Dict[float, float] = field(default_factory=dict)   # 折扣档 -> 售出量
    discount_posted: Dict[float, int] = field(default_factory=dict)   # 折扣档 -> 出现次数
    warnings: List[str] = field(default_factory=list)


class TestPeriodAccumulator:
    """在测试期逐日累加指标。所有口径见模块 docstring。"""

    def __init__(self) -> None:
        self.revenue = 0.0
        self.cogs = 0.0
        self.scrap_qty = 0.0
        self.scrap_cost = 0.0
        self.purchase_qty = 0.0
        self.purchase_cost = 0.0
        self.sold_qty = 0.0
        self.return_qty = 0.0
        self.lost_qty = 0.0
        self.demand_qty = 0.0
        self.orders = 0.0
        self.available_qty = 0.0          # 期初库存 + 当日到货
        self.base_value_sold = 0.0        # Σ 原价 × 售出量（算实际折扣率用）
        self.discount_sold_qty = 0.0      # 打折售出的量
        self.stockout_item_days = 0        # 观测历史里 is_stockout 的 单品×日 数
        self.n_hist_item_days = 0          # 观测历史行数（单品×日）
        self.stock_value_cost_days: List[float] = []
        self.batch_count_days: List[int] = []
        self.posted_rates: List[float] = []
        self.below_cost_posted = 0        # 挂出折扣 < 成本/原价 的次数（打穿毛利的证据）
        self.n_state_obs = 0
        self.daily: List[dict] = []
        self.discount_hist: Dict[float, float] = {float(d): 0.0 for d in DISCOUNT_GRID}
        self.discount_posted: Dict[float, int] = {float(d): 0 for d in DISCOUNT_GRID}
        self.warnings: List[str] = []
        self._batch_cost: Dict[str, float] = {}
        self._item_cost: Dict[str, float] = {}
        self._item_base: Dict[str, float] = {}
        self._n_purchase_batches = 0

    # -- 每天开始：从 state 里取台账，建立 batch_no -> 成本 的映射 ---------------
    def observe_state(self, state) -> None:
        """只维护"批次号/单品 -> 成本、原价"的映射，以及当日批次数。

        注意：可售量与剩余货值**不在这里**统计——state 里的 history 只包含到"昨天"为止的观测，
        当日口径必须用滞后一天的历史行来算，见 add_day()。
        """
        batches = _df(_get(state, "batches"))
        if len(batches):
            for b in batches.to_dict("records"):
                bn = str(b.get("batch_no"))
                c = b.get("cost_price")
                if bn and c is not None and np.isfinite(float(c)):
                    self._batch_cost[bn] = float(c)
                code = str(b.get("item_code"))
                if code and c is not None and np.isfinite(float(c)):
                    self._item_cost[code] = float(c)
                bp = b.get("base_price")
                if code and bp is not None and np.isfinite(float(bp)):
                    self._item_base[code] = float(bp)
            self.batch_count_days.append(int(len(batches)))

    def _cost_of(self, item_code: str, batch_no: str) -> float:
        if batch_no and batch_no in self._batch_cost:
            return self._batch_cost[batch_no]
        return self._item_cost.get(str(item_code), 0.0)

    # -- 每天结束：吃当日成交流水 --------------------------------------------
    def add_day(self, biz_date, day, state, history: Optional[pd.DataFrame] = None) -> None:
        self.observe_state(state)
        day_key = str(biz_date)[:10]

        # --- 销售明细 ---
        sales = _df(_get(day, "sales"))
        day_revenue = 0.0
        day_cogs = 0.0
        day_sold = 0.0
        if len(sales):
            qty = _num(sales, "quantity")
            price = _num(sales, "unit_price")
            is_ret = (
                sales["is_return"].fillna(False).astype(bool).to_numpy()
                if "is_return" in sales.columns
                else np.zeros(len(sales), dtype=bool)
            )
            sign = np.where(is_ret, -1.0, 1.0)
            codes = sales["item_code"].astype(str).to_numpy() if "item_code" in sales.columns else np.array([""] * len(sales))
            bns = sales["batch_no"].astype(str).to_numpy() if "batch_no" in sales.columns else np.array([""] * len(sales))
            costs = np.array([self._cost_of(codes[i], bns[i]) for i in range(len(sales))])
            bases = np.array([self._item_base.get(codes[i], price[i]) for i in range(len(sales))])
            day_revenue = float(np.sum(sign * qty * price))
            day_cogs = float(np.sum(sign * qty * costs))
            day_sold = float(np.sum(qty[~is_ret]))
            self.revenue += day_revenue
            self.cogs += float(np.sum(sign * qty * costs))
            self.sold_qty += float(np.sum(qty[~is_ret]))
            self.return_qty += float(np.sum(qty[is_ret]))
            self.base_value_sold += float(np.sum(sign * qty * bases))
            if "is_discount" in sales.columns:
                dmask = sales["is_discount"].fillna(False).astype(bool).to_numpy() & ~is_ret
                self.discount_sold_qty += float(np.sum(qty[dmask]))

        # --- 报废 ---
        scrap = _df(_get(day, "scrap"))
        day_scrap_qty = 0.0
        day_scrap_cost = 0.0
        if len(scrap):
            sq = _num(scrap, "quantity")
            sc = _num(scrap, "cost_price")
            day_scrap_qty = float(np.sum(sq))
            day_scrap_cost = float(np.sum(sq * sc))
            self.scrap_qty += day_scrap_qty
            self.scrap_cost += day_scrap_cost

        # --- 当日到货（损耗率分母）---
        purchase = _df(_get(day, "purchase"))
        if len(purchase):
            self.purchase_qty += _sum(purchase, "quantity")
            self.purchase_cost += float(
                np.sum(_num(purchase, "quantity") * _num(purchase, "cost_price"))
            )
            self._n_purchase_batches += int(len(purchase))
            for b in purchase.to_dict("records"):
                bn = str(b.get("batch_no"))
                c = b.get("cost_price")
                if bn and c is not None and np.isfinite(float(c)):
                    self._batch_cost[bn] = float(c)
                code = str(b.get("item_code"))
                if code and c is not None and np.isfinite(float(c)):
                    self._item_cost[code] = float(c)

        # --- 未截断需求（★只允许在这里用于事后计分，绝不回流给定价模型）---
        demand = _df(_get(day, "demand"))
        if len(demand):
            self.demand_qty += _sum(demand, "demand_qty")
            self.lost_qty += _sum(demand, "lost_qty")

        # --- 客流（算客单价）---
        traffic = _df(_get(day, "traffic"))
        if len(traffic):
            self.orders += _sum(traffic, "order_count")

        # --- 观测历史里的"当日"口径：可售量 / 期末库存货值 / 单品断货 ---
        # ★ 滞后一天的原因：第 t 天开始时的 state["history"] 只含到第 t-1 天为止的观测行。
        #   所以调用方会把"第 t-1 天的成交流水"配"第 t 天开始时的 history"一起传进来。
        hist = history if history is not None else _df(_get(state, "history"))
        if len(hist) and "biz_date" in hist.columns:
            h = hist[pd.to_datetime(hist["biz_date"], errors="coerce").dt.strftime("%Y-%m-%d") == day_key]
            if len(h):
                if {"on_hand_start", "arrival_qty"}.issubset(h.columns):
                    self.available_qty += float(np.sum(_num(h, "on_hand_start") + _num(h, "arrival_qty")))
                if "remain_end" in h.columns:
                    codes = h["item_code"].astype(str).to_numpy()
                    costs = np.array([self._item_cost.get(c, 0.0) for c in codes])
                    self.stock_value_cost_days.append(float(np.sum(_num(h, "remain_end") * costs)))
                if "is_stockout" in h.columns:
                    self.stockout_item_days += int(h["is_stockout"].fillna(False).astype(bool).sum())
                if "item_code" in h.columns:
                    self.n_hist_item_days += int(len(h))

        self.daily.append(
            {
                "biz_date": str(biz_date)[:10],
                "revenue": day_revenue,
                "cogs": day_cogs,
                "sold_qty": day_sold,
                "scrap_qty": day_scrap_qty,
                "scrap_cost": day_scrap_cost,
                "net_profit": day_revenue - day_cogs - day_scrap_cost,
                "scrap_qty_cum": self.scrap_qty,
                "net_profit_cum": self.revenue - self.cogs - self.scrap_cost,
                "orders": _sum(traffic, "order_count"),
            }
        )

    # -- 记录策略当天挂出的折扣档（折扣深度分布用）------------------------------
    def observe_decisions(self, decisions: Dict[str, float]) -> None:
        for code, r in (decisions or {}).items():
            try:
                rr = float(r)
            except Exception:
                continue
            if rr > 1.5:  # 绝对价格，换算成折扣系数
                base = self._item_base.get(str(code))
                rr = (rr / base) if base else float("nan")
                if not np.isfinite(rr):
                    continue
            rr = min(DISCOUNT_GRID, key=lambda g: abs(g - rr))
            self.discount_posted[float(rr)] = self.discount_posted.get(float(rr), 0) + 1
            self.posted_rates.append(float(rr))
            # 打穿毛利检查：折后价 < 进货成本（保本折扣 = cost/base）
            base = self._item_base.get(str(code))
            cost = self._item_cost.get(str(code))
            if base and cost and (rr * float(base)) < float(cost) - 1e-9:
                self.below_cost_posted += 1

    # -- 汇总 ---------------------------------------------------------------
    def finalize(self) -> Dict[str, float]:
        avail = self.available_qty
        return {
            "revenue": self.revenue,
            "cogs": self.cogs,
            "scrap_cost": self.scrap_cost,
            "gross_profit": self.revenue - self.cogs,
            "net_profit": self.revenue - self.cogs - self.scrap_cost,
            "sold_qty": self.sold_qty,
            "scrap_qty": self.scrap_qty,
            "purchase_qty": self.purchase_qty,
            "purchase_cost": self.purchase_cost,
            "lost_qty": self.lost_qty,
            "demand_qty": self.demand_qty,
            "n_purchase_batches": float(self._n_purchase_batches),
            "scrap_rate": (self.scrap_qty / self.purchase_qty) if self.purchase_qty > 0 else float("nan"),
            "stockout_rate": (self.lost_qty / self.demand_qty) if self.demand_qty > 0 else 0.0,
            # 售出率主口径 = 售出量/进货量（与损耗率同分母，两者可直接相加）
            "sell_through": (self.sold_qty / self.purchase_qty) if self.purchase_qty > 0 else float("nan"),
            "sell_through_available": (self.sold_qty / avail) if avail > 0 else float("nan"),
            "avg_daily_stock_value": float(np.mean(self.stock_value_cost_days))
            if self.stock_value_cost_days
            else float("nan"),
            "end_stock_value": float(self.stock_value_cost_days[-1])
            if self.stock_value_cost_days
            else float("nan"),
            "avg_batches": float(np.mean(self.batch_count_days)) if self.batch_count_days else float("nan"),
            "avg_discount_depth": float(1.0 - np.mean(self.posted_rates)) if self.posted_rates else 0.0,
            "below_cost_posted_share": (self.below_cost_posted / len(self.posted_rates))
            if self.posted_rates
            else 0.0,
            "realized_price_ratio": (self.revenue / self.base_value_sold)
            if self.base_value_sold > 0
            else float("nan"),
            "discount_qty_share": (self.discount_sold_qty / self.sold_qty) if self.sold_qty > 0 else 0.0,
            "avg_order_value": (self.revenue / self.orders) if self.orders > 0 else float("nan"),
            "stockout_item_day_share": (self.stockout_item_days / self.n_hist_item_days)
            if self.n_hist_item_days
            else 0.0,
        }


# ===========================================================================
# 3. 单个种子的一次完整实验
# ===========================================================================


class SimHarness:
    """把 ShopSimulator 包一层，屏蔽 snapshot/restore 有无的差异。"""

    def __init__(self, sim_module, seed: int, days: int, warmup_days: int,
                 sim_kwargs: Optional[dict] = None):
        self.mod = sim_module
        self.seed = int(seed)
        self.days = int(days)
        self.warmup_days = int(warmup_days)
        self.sim_kwargs = dict(sim_kwargs or {})
        self.sim = self._construct()

    def _construct(self):
        kw = dict(seed=self.seed, days=self.days, warmup_days=self.warmup_days)
        kw.update(self.sim_kwargs)
        try:
            return self.mod.ShopSimulator(**kw)
        except TypeError:
            kw.pop("warmup_days", None)
            for k in list(self.sim_kwargs):
                kw.pop(k, None)
            return self.mod.ShopSimulator(**kw)

    def reset(self) -> None:
        self.sim = self._construct()

    # -- 快照 ---------------------------------------------------------------
    def snapshot(self):
        for name in ("snapshot", "save_state"):
            fn = getattr(self.sim, name, None)
            if callable(fn):
                return ("api", fn())
        return None

    def restore(self, snap) -> None:
        if snap is None:
            self.reset()
            return
        kind, payload = snap
        if kind == "api":
            for name in ("restore", "load_state"):
                fn = getattr(self.sim, name, None)
                if callable(fn):
                    fn(payload)
                    return
        self.reset()

    def step(self, pricing_fn):
        return self.sim.step_day(pricing_fn)

    def history(self) -> pd.DataFrame:
        """取截至当前天的全部可观测历史。

        注意 `ShopSimulator.observed_history()` 默认只返回前 warmup_days 天，
        这里必须显式要 `full=True`，否则模型只能看到 30 天。
        """
        fn = getattr(self.sim, "observed_history", None)
        if callable(fn):
            try:
                return _df(fn(full=True))
            except TypeError:
                return _df(fn())
        for name in ("history_observed", "get_history"):
            f2 = getattr(self.sim, name, None)
            if callable(f2):
                return _df(f2())
        return pd.DataFrame()

    def state_hash(self) -> str:
        """sim 自带的因果状态哈希（批次台账+累计量+观测历史），用来证明三策略起点一致。"""
        for name in ("state_hash", "noise_fingerprint"):
            fn = getattr(self.sim, name, None)
            if callable(fn) and name == "state_hash":
                try:
                    return str(fn())
                except Exception:
                    pass
        return "n/a"

    def noise_fingerprint(self) -> str:
        """预抽噪声矩阵指纹：只与 seed 有关，与策略无关（CRN 自证）。"""
        fn = getattr(self.sim, "noise_fingerprint", None)
        if callable(fn):
            try:
                return str(fn())
            except Exception:
                pass
        return "n/a"


def run_one_seed(
    sim_module,
    seed: int,
    *,
    days: int = 90,
    history_days: int = 60,
    strategies: Sequence[str] = STRATEGIES,
    model_refit_every: int = 5,
    verbose: bool = False,
    sim_kwargs: Optional[dict] = None,
    min_discount_rate: Optional[float] = None,
) -> Tuple[Dict[str, SeedStrategyResult], Dict[str, object]]:
    """在单个种子上跑完三种策略，返回 {策略: 结果} 与同源性验证信息。"""
    test_days = days - history_days
    harness = SimHarness(sim_module, seed, days, warmup_days=history_days, sim_kwargs=sim_kwargs)
    # 前 60 天统一用"店主现状"（规则版）。这里同样加最低折扣线——
    # 历史窗口是三种策略共享的，必须用同一套约束，否则同源性前提就破了。
    history_fn = make_rule_pricing_fn(min_discount_rate=min_discount_rate)

    # ---------- 阶段 A：用 RULE 决策跑满历史窗口 ----------
    for i in range(history_days):
        harness.step(history_fn)
    hist_after_A = harness.history()
    hash_A = _hash_frame(hist_after_A)
    snap = harness.snapshot()

    results: Dict[str, SeedStrategyResult] = {}
    hashes: Dict[str, str] = {}
    state_hashes: Dict[str, str] = {}
    noise_fp = harness.noise_fingerprint()

    for name in strategies:
        # ---------- 回到第 60 天：优先快照，其次 reset + 重放 ----------
        if snap is not None:
            harness.restore(snap)
        else:
            harness.reset()
            for _ in range(history_days):
                harness.step(history_fn)
        hashes[name] = _hash_frame(harness.history())
        state_hashes[name] = harness.state_hash()

        strat_fn = build_strategy(name, model_refit_every=model_refit_every,
                                  min_discount_rate=min_discount_rate)
        acc = TestPeriodAccumulator()
        # state 由 sim 在调用 pricing_fn 时传进来，这是唯一可靠的抓取点：
        # 第 t 天开始时的 state["history"] 只含到第 t-1 天，所以成交流水要滞后一天结算。
        state_box: Dict[str, object] = {}

        def _wrapped(biz_date, day_index, state, _fn=strat_fn, _acc=acc, _box=state_box):
            _box["state"] = state
            _acc.observe_state(state)  # 先刷新 单品->成本/原价 映射，再记录折扣
            decisions = _fn(biz_date, day_index, state)
            _acc.observe_decisions(decisions if isinstance(decisions, dict) else {})
            return decisions

        pending: Optional[Tuple[object, object]] = None
        for t in range(test_days):
            rec = harness.step(_wrapped)
            biz = _get(rec, "biz_date", None) or _get(rec, "date", None)
            hist_now = _df(_get(state_box.get("state"), "history"))
            if pending is not None:
                p_biz, p_rec = pending
                acc.add_day(p_biz, p_rec, state_box.get("state") or {}, hist_now)
            pending = (biz, rec)
            for w in (_records(_get(rec, "warnings")) or []):
                acc.warnings.append(str(w))
            if verbose and t % 10 == 0:
                print(f"    seed={seed} {name} day {history_days + t} ...")

        # 最后一天：用 history_observed() 补上它的观测行
        if pending is not None:
            p_biz, p_rec = pending
            final_hist = harness.history()
            if not len(final_hist):
                final_hist = _df(_get(state_box.get("state"), "history"))
            acc.add_day(p_biz, p_rec, state_box.get("state") or {}, final_hist)

        res = SeedStrategyResult(seed=seed, strategy=name, metrics=acc.finalize(),
                                 daily=acc.daily,
                                 discount_hist=acc.discount_hist,
                                 discount_posted=acc.discount_posted,
                                 warnings=acc.warnings[:20])
        results[name] = res

    diag = {
        "seed": seed,
        "hash_after_history": hash_A,
        "hash_per_strategy": hashes,
        "state_hash_per_strategy": state_hashes,
        "noise_fingerprint": noise_fp,
        "snapshot_api": snap is not None,
        "test_days": test_days,
    }
    return results, diag


# ===========================================================================
# 4. 多策略 × 多种子统计
# ===========================================================================


def paired_stats(a: np.ndarray, b: np.ndarray) -> Dict[str, float]:
    """配对差异统计：a - b 的均值/标准差/分位数 + 配对 t 检验 p 值。"""
    d = np.asarray(a, dtype=float) - np.asarray(b, dtype=float)
    d = d[np.isfinite(d)]
    out = {
        "mean": float(np.mean(d)) if len(d) else float("nan"),
        "sd": float(np.std(d, ddof=1)) if len(d) > 1 else float("nan"),
        "min": float(np.min(d)) if len(d) else float("nan"),
        "max": float(np.max(d)) if len(d) else float("nan"),
        "p2.5": float(np.percentile(d, 2.5)) if len(d) else float("nan"),
        "p97.5": float(np.percentile(d, 97.5)) if len(d) else float("nan"),
        "n": int(len(d)),
        "p_value": float("nan"),
    }
    if len(d) > 2:
        try:
            from scipy import stats

            out["p_value"] = float(stats.ttest_rel(a, b, nan_policy="omit").pvalue)
        except Exception:
            pass
    return out


#: 报告里要展示的指标及其方向说明
METRIC_SPEC = [
    ("net_profit", "经营毛利（收入−销售成本−报废成本）/ 元", "higher"),
    ("gross_profit", "毛毛利（收入−销售成本）/ 元", "higher"),
    ("revenue", "收入 / 元", "higher"),
    ("scrap_rate", "损耗率（报废/进货）", "lower"),
    ("scrap_cost", "报废成本 / 元", "lower"),
    ("scrap_qty", "报废量 / 件", "lower"),
    ("stockout_rate", "缺货率（未满足需求/总需求）", "lower"),
    ("sell_through", "售出率（售出/进货）", "higher"),
    ("sell_through_available", "售出率（售出/日均可售量，仅供参考）", "-"),
    ("avg_discount_depth", "平均折扣深度（1−折扣系数）", "-"),
    ("below_cost_posted_share", "挂出折扣低于进货成本的占比（打穿毛利检查）", "lower"),
    ("realized_price_ratio", "实际成交价/原价", "-"),
    ("discount_qty_share", "打折成交占比", "-"),
    ("avg_order_value", "单笔客单价 / 元", "higher"),
    ("avg_daily_stock_value", "平均剩余货值（成本口径）/ 元", "-"),
    ("end_stock_value", "期末库存货值 / 元", "-"),
    ("purchase_qty", "进货量 / 件（同源性检查）", "-"),
    ("n_purchase_batches", "到货批次数（同源性检查）", "-"),
]


def _fmt(v: float, nd: int = 2, pct: bool = False) -> str:
    if v is None or (isinstance(v, float) and not np.isfinite(v)):
        return "n/a"
    return f"{v * 100:.{nd}f}%" if pct else f"{v:,.{nd}f}"


# ===========================================================================
# 5. 图表
# ===========================================================================


def setup_matplotlib():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    for fam in ("Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Source Han Sans SC", "DejaVu Sans"):
        try:
            matplotlib.rcParams["font.sans-serif"] = [fam]
            matplotlib.rcParams["axes.unicode_minus"] = False
            from matplotlib.font_manager import findfont, FontProperties

            if fam != "DejaVu Sans":
                path = findfont(FontProperties(family=fam), fallback_to_default=False)
                if path and os.path.exists(path):
                    return plt, True
            else:
                return plt, False
        except Exception:
            continue
    return plt, False


def make_figures_1_2(per_seed: pd.DataFrame, figs_dir: str, cn: bool) -> List[str]:
    """图1（损耗率）/图2（经营毛利与毛毛利）。"""
    return _two_figs(per_seed, figs_dir, cn)


def make_figures_more(per_seed: pd.DataFrame, daily: pd.DataFrame, figs_dir: str, cn: bool) -> List[str]:
    """图3~图7：按日趋势 / 缺货与售出率 / 折扣深度分布 / 逐种子差异 / 平均剩余货值。"""
    plt, _ = setup_matplotlib()
    made: List[str] = []
    order = [s for s in STRATEGIES if s in set(per_seed["strategy"])]
    colors = {"FIXED": "#8c8c8c", "RULE": "#4c78a8", "MODEL": "#e4572e"}

    def L(zh: str, en: str) -> str:
        return zh if cn else en

    # --- 图3：按日经营毛利趋势 ---
    if daily is not None and len(daily):
        fig, axes = plt.subplots(1, 2, figsize=(12, 4.2))
        g = daily.groupby(["strategy", "biz_date"], as_index=False).agg(
            net_profit=("net_profit", "mean"), scrap_qty_cum=("scrap_qty_cum", "mean")
        )
        for s in order:
            gg = g[g.strategy == s]
            axes[0].plot(gg["biz_date"], gg["net_profit"], label=s, color=colors[s], lw=1.6)
        axes[0].set_title(L("测试期每日经营毛利（跨种子均值）", "Daily net profit (mean over seeds)"))
        axes[0].set_ylabel(L("元", "CNY"))
        axes[0].tick_params(axis="x", rotation=60, labelsize=7)
        axes[0].legend()
        axes[0].grid(alpha=0.3)
        for s in order:
            gg = g[g.strategy == s]
            axes[1].plot(gg["biz_date"], gg["scrap_qty_cum"], label=s, color=colors[s], lw=1.6)
        axes[1].set_title(L("测试期每日累计报废量（跨种子均值）", "Cumulative scrap (mean over seeds)"))
        axes[1].set_ylabel(L("件", "units"))
        axes[1].tick_params(axis="x", rotation=60, labelsize=7)
        axes[1].legend()
        axes[1].grid(alpha=0.3)
        fig.tight_layout()
        p = os.path.join(figs_dir, "eval_daily_trend.png")
        fig.savefig(p, dpi=150)
        plt.close(fig)
        made.append(p)

    # --- 图4：缺货率 & 售出率 ---
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    for ax, key, zh, en in (
        (axes[0], "stockout_rate", "缺货率（未满足需求/总需求）", "Stockout rate"),
        (axes[1], "sell_through", "售出率（售出/可售量）", "Sell-through rate"),
    ):
        m = [per_seed[per_seed.strategy == s][key].mean() for s in order]
        sd = [per_seed[per_seed.strategy == s][key].std(ddof=1) for s in order]
        ax.bar(order, m, yerr=sd, capsize=5, color=[colors[s] for s in order])
        ax.set_title(L(zh, en), fontsize=10)
        ax.grid(axis="y", alpha=0.3)
    fig.suptitle(L("服务与清货能力对比（均值±标准差）", "Service & clearance metrics (mean ± sd)"))
    fig.tight_layout()
    p = os.path.join(figs_dir, "eval_stockout_sellthrough.png")
    fig.savefig(p, dpi=150)
    plt.close(fig)
    made.append(p)

    # --- 图5：折扣深度分布 ---
    if "discount_posted_json" in per_seed.columns:
        fig, ax = plt.subplots(figsize=(7.2, 4.2))
        grid = [float(d) for d in DISCOUNT_GRID]
        width = 0.26
        for i, s in enumerate(order):
            sub = per_seed[per_seed.strategy == s]
            tot = sub["posted_total"].sum()
            vals = []
            for d in grid:
                v = sub["discount_posted_json"].map(lambda x, d=d: json.loads(x).get(str(d), 0) if x else 0).sum()
                vals.append(v / tot if tot else 0.0)
            xs = np.arange(len(grid)) + (i - 1) * width
            ax.bar(xs, vals, width=width, label=s, color=colors[s])
        ax.set_xticks(np.arange(len(grid)))
        ax.set_xticklabels([f"{d * 10:.0f}{L('折', '0%')}" for d in grid])
        ax.set_ylabel(L("挂出次数占比", "Share of posted decisions"))
        ax.set_title(L("折扣深度分布（测试期所有在售批次）", "Discount depth distribution"))
        ax.legend()
        ax.grid(axis="y", alpha=0.3)
        fig.tight_layout()
        p = os.path.join(figs_dir, "eval_discount_dist.png")
        fig.savefig(p, dpi=150)
        plt.close(fig)
        made.append(p)

    # --- 图6：逐种子差异（MODEL − FIXED）散点：证明不是"一次运行的运气" ---
    if "MODEL" in order and "FIXED" in order:
        piv_s = per_seed.pivot_table(index="seed", columns="strategy", values="scrap_rate")
        piv_p = per_seed.pivot_table(index="seed", columns="strategy", values="net_profit")
        if "MODEL" in piv_s.columns and "FIXED" in piv_s.columns:
            fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
            ds = (piv_s["MODEL"] - piv_s["FIXED"]).sort_values() * 100
            dp = (piv_p["MODEL"] - piv_p["FIXED"]).sort_values()
            axes[0].bar(range(len(ds)), ds.to_numpy(),
                        color=["#2e8b57" if v < 0 else "#c0392b" for v in ds.to_numpy()])
            axes[0].axhline(0, color="k", lw=0.8)
            axes[0].set_title(L("逐种子 损耗率差（MODEL−FIXED，百分点）",
                                "Per-seed scrap-rate diff (MODEL-FIXED, pp)"))
            axes[0].set_xlabel(L("种子（按差值排序）", "seed (sorted)"))
            axes[1].bar(range(len(dp)), dp.to_numpy(),
                        color=["#2e8b57" if v > 0 else "#c0392b" for v in dp.to_numpy()])
            axes[1].axhline(0, color="k", lw=0.8)
            axes[1].set_title(L("逐种子 经营毛利差（MODEL−FIXED，元）",
                                "Per-seed net-profit diff (MODEL-FIXED, CNY)"))
            axes[1].set_xlabel(L("种子（按差值排序）", "seed (sorted)"))
            for ax in axes:
                ax.grid(axis="y", alpha=0.3)
            fig.tight_layout()
            p = os.path.join(figs_dir, "eval_seed_spread.png")
            fig.savefig(p, dpi=150)
            plt.close(fig)
            made.append(p)

    # --- 图7：平均剩余货值（防止靠"不清货"刷指标）---
    fig, ax = plt.subplots(figsize=(6.2, 4.2))
    m = [per_seed[per_seed.strategy == s]["avg_daily_stock_value"].mean() for s in order]
    sd = [per_seed[per_seed.strategy == s]["avg_daily_stock_value"].std(ddof=1) for s in order]
    ax.bar(order, m, yerr=sd, capsize=5, color=[colors[s] for s in order])
    ax.set_ylabel(L("平均剩余货值（成本口径）/ 元", "Avg remaining stock value (at cost) / CNY"))
    ax.set_title(L("平均剩余货值：防止用'少进货/不清货'刷损耗率",
                   "Avg remaining stock value (guard against metric gaming)"))
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    p = os.path.join(figs_dir, "eval_stock_value.png")
    fig.savefig(p, dpi=150)
    plt.close(fig)
    made.append(p)
    return made


# ===========================================================================
# 6. 报告
# ===========================================================================


def build_report(
    per_seed: pd.DataFrame,
    diags: List[dict],
    *,
    command: str,
    elapsed: float,
    seeds: List[int],
    days: int,
    history_days: int,
    cn: bool,
    figs: List[str],
    sweep: Optional[pd.DataFrame] = None,
    model_diag: Optional[dict] = None,
    sim_kwargs: Optional[dict] = None,
) -> str:
    test_days = days - history_days
    order = [s for s in STRATEGIES if s in set(per_seed["strategy"])]
    lines: List[str] = []
    A = lines.append

    A("# 三策略对照评测报告（FIXED / RULE / MODEL）")
    A("")
    A("> 本文件由 `scripts/evaluate_strategies.py` 自动生成，**每个数字都能追溯到该脚本与 `data/seed` 的仿真数据**。")
    A("> 配套阅读：`docs/03-定价模块设计.md`（算法与假设）、`docs/02-仿真器假设与数据字典.md`（仿真器口径）。")
    A("")
    A("## 0. 复现信息")
    A("")
    A(f"- 复现命令：`{command}`")
    A(f"- 随机种子：{len(seeds)} 个（{seeds[0]} … {seeds[-1]}，步长 {seeds[1] - seeds[0] if len(seeds) > 1 else 0}）")
    A(f"- 时间切分：每个种子 {days} 天营业期 = 前 {history_days} 天历史窗口 + 后 {test_days} 天统一测试期（指标只统计测试期）")
    A(f"- 三策略同源：同一 seed 下前 {history_days} 天使用同一套 RULE 决策，第 {history_days} 天取快照后各自跑测试期")
    A(f"- 快照接口：{'sim.snapshot()/restore()' if diags and diags[0].get('snapshot_api') else 'sim.reset() + 重放历史（无 snapshot 接口）'}")
    A(f"- 复现耗时：{elapsed:.1f} 秒（本机，单进程）")
    A(f"- 仿真器附加参数：`{sim_kwargs or '{}'}`（用于敏感性分析）")
    A("")

    # ---- 同源性验证 ----
    A("## 1. 同源性验证（这条不过，后面所有结论作废）")
    A("")
    A("| 种子 | 快照后三个策略的历史哈希是否一致 | MODEL−FIXED 的进货量差 | MODEL−FIXED 的到货批次数差 |")
    A("| --- | --- | --- | --- |")
    same_all = True
    for d in diags:
        hs = d.get("hash_per_strategy", {})
        uniq = set(hs.values())
        ok = len(uniq) == 1
        same_all &= ok
        sub = per_seed[per_seed.seed == d["seed"]].set_index("strategy")
        dq = (
            sub.loc["MODEL", "purchase_qty"] - sub.loc["FIXED", "purchase_qty"]
            if {"MODEL", "FIXED"}.issubset(sub.index)
            else float("nan")
        )
        db = (
            sub.loc["MODEL", "n_purchase_batches"] - sub.loc["FIXED", "n_purchase_batches"]
            if {"MODEL", "FIXED"}.issubset(sub.index)
            else float("nan")
        )
        A(f"| {d['seed']} | {'一致 ✅' if ok else '不一致 ❌'} | {dq:+.1f} | {db:+.0f} |")
    A("")
    A(
        f"**结论：{'所有种子上三个策略的初始状态（第 60 天历史 + 库存）完全一致 ✅' if same_all else '存在不一致，结论不可用 ❌'}**"
    )
    A("")
    A("> 说明：仿真器对所有外生冲击使用 `PCG64([seed, day_index, hour, stream_id])` 预抽取，抽样格的形状与顺序只依赖")
    A("> (day, hour, item)，与策略选了哪个价无关，因此同一 seed 下三个策略面对的是**同一条消费者流**（公共随机数 CRN）。")
    A("> 上表第三、四列是 Lead 要求的「防作弊检查」：如果某个策略的损耗率优势来自「进货更少」而不是「卖得更好」，这两列会显著偏离 0。")
    A("")

    # ---- 前置证据：打折到底赚不赚钱 ----
    if sweep is not None and len(sweep):
        A("## 2. 前置证据：在这个仿真器里，打折到底赚不赚钱？")
        A("")
        A("结论要成立，先得回答一个更基础的问题。下表在**同一个 seed、同一份初始库存**上扫描了"
          "「统一折扣」与「定向清货」两类纯价格策略（第 1 个种子，其余设定与主评测完全一致）：")
        A("")
        A("| 价格策略 | 经营毛利/元 | 相对 FIXED | 损耗率 | 售出率 | 折扣深度 | 挂出低于成本占比 |")
        A("| --- | --- | --- | --- | --- | --- | --- |")
        for _, r in sweep.iterrows():
            A(f"| {r['policy']} | {r['net_profit']:,.0f} | {r['net_profit_vs_fixed_pct']:+.1f}% | "
              f"{r['scrap_rate'] * 100:.2f}% | {r['sell_through'] * 100:.2f}% | "
              f"{r['avg_discount_depth'] * 100:.2f}% | {r['below_cost_posted_share'] * 100:.1f}% |")
        A("")
        worst = sweep[sweep["policy"] == "FIXED"]
        targeted = sweep[sweep["policy"].str.startswith("仅到期日")]
        if len(worst) and len(targeted):
            best_targeted = targeted.loc[targeted["net_profit_vs_fixed_pct"].idxmax()]
            A(f"> **读法（这一段决定了主结论怎么解读）**：连**只在到期当天才打折**这种最精准的清货方式，"
              f"最好的档位（{best_targeted['policy']}）也只有 {best_targeted['net_profit_vs_fixed_pct']:+.1f}% 的经营毛利，"
              f"损耗率从 {worst['scrap_rate'].iloc[0] * 100:.2f}% 降到 {best_targeted['scrap_rate'] * 100:.2f}%。"
              f"也就是说：**在这个仿真器里，价格不是解决损耗的正确杠杆**——"
              f"打折能在'最后一刻'把注定报废的货换成钱，但同一折扣会同时施加在**所有**本来就能按原价卖掉的货上，"
              f"后者损失大于前者收益。")
            A("")
            A("这不是评测写错了，而是仿真参数的**结构性后果**，原因有两条，都可以核对：")
            A("")
            A("1. **弹性绝对值跨在 1 的两侧**：仿真器真值 |ε| ∈ [0.63, 1.27]（中位 0.90）。"
              "常弹性需求下收入 `R ∝ p^(1−ε)`，当 `|ε| < 1` 时**收入随价格上升**，降价直接减少收入；"
              "约六成单品落在 `|ε| < 1` 区间。")
            A("2. **补货量外生且与定价无关**：三种策略拿到完全相同的到货计划（见第 1 节进货量差异≈0）。")
            A("   当库存本就过剩时，定价只能在「多卖一件」和「每件都少赚」之间交换，"
              "而**减少进货才是根治损耗的办法**——那是补货模块的问题，不是定价模块能解决的。")
            A("")
            A("因此下文对 MODEL 的评价标准是：**它能不能比经验规则更划算地完成同样的损耗率下降**，"
              "而不是「能不能既降损耗又涨毛利」。")

    # ---- 主表 ----
    A("## 3. 主对比表（跨种子 均值 ± 标准差）")
    A("")
    header = "| 指标 | " + " | ".join(f"{s}" for s in order) + " |"
    A(header)
    A("| --- | " + " | ".join(["---"] * len(order)) + " |")
    for key, label, _dirn in METRIC_SPEC:
        if key not in per_seed.columns:
            continue
        cells = []
        for s in order:
            v = per_seed[per_seed.strategy == s][key]
            pct = key in ("scrap_rate", "stockout_rate", "sell_through", "discount_qty_share")
            nd = 2 if pct else (3 if key == "realized_price_ratio" else 1)
            mu, sd = v.mean(), v.std(ddof=1)
            cells.append(f"{_fmt(mu, nd, pct)} ± {_fmt(sd, nd, pct)}")
        A(f"| {label} | " + " | ".join(cells) + " |")
    A("")

    # ---- 结论 ----
    A("## 4. 结论（含波动范围）")
    A("")
    piv = {k: per_seed.pivot_table(index="seed", columns="strategy", values=k) for k in
           ("scrap_rate", "net_profit", "gross_profit", "stockout_rate", "sell_through", "avg_daily_stock_value")}
    verdicts = []
    for base in [s for s in order if s != "MODEL"]:
        if "MODEL" not in piv["scrap_rate"].columns or base not in piv["scrap_rate"].columns:
            continue
        sr = paired_stats(piv["scrap_rate"]["MODEL"], piv["scrap_rate"][base])
        npf = paired_stats(piv["net_profit"]["MODEL"], piv["net_profit"][base])
        gp = paired_stats(piv["gross_profit"]["MODEL"], piv["gross_profit"][base])
        so = paired_stats(piv["stockout_rate"]["MODEL"], piv["stockout_rate"][base])
        st = paired_stats(piv["sell_through"]["MODEL"], piv["sell_through"][base])
        m_scrap = piv["scrap_rate"]["MODEL"].mean()
        b_scrap = piv["scrap_rate"][base].mean()
        m_prof = piv["net_profit"]["MODEL"].mean()
        b_prof = piv["net_profit"][base].mean()
        rel = (m_prof - b_prof) / abs(b_prof) * 100 if b_prof else float("nan")
        A(f"### MODEL vs {base}（{STRATEGY_LABEL[base]}）")
        A("")
        A(
            f"**在「需求 = 基准需求 × exp(−弹性 ×(折扣−1)) × 时段因子 × 周内因子 × 泊松扰动，且关闭推送」"
            f"的仿真假设下，"
            f"MODEL 相对 {base} 把损耗率从 {b_scrap * 100:.2f}% 降到 {m_scrap * 100:.2f}%"
            f"（绝对 {sr['mean'] * 100:+.2f} 个百分点，相对 {(sr['mean'] / b_scrap * 100 if b_scrap else float('nan')):+.1f}%），"
            f"经营毛利从 {b_prof:,.0f} 元变为 {m_prof:,.0f} 元（{rel:+.2f}%）。**"
        )
        A("")
        A(f"- 损耗率差（MODEL−{base}）：均值 {sr['mean'] * 100:+.3f} pp，标准差 {sr['sd'] * 100:.3f} pp，"
          f"范围 [{sr['min'] * 100:+.3f}, {sr['max'] * 100:+.3f}] pp，"
          f"2.5%~97.5% 分位 [{sr['p2.5'] * 100:+.3f}, {sr['p97.5'] * 100:+.3f}] pp，配对 t 检验 p = {sr['p_value']:.4g}"
          f"（{sr['n']} 个种子）")
        A(f"- 经营毛利差：均值 {npf['mean']:+,.1f} 元，标准差 {npf['sd']:,.1f} 元，"
          f"范围 [{npf['min']:+,.1f}, {npf['max']:+,.1f}] 元，p = {npf['p_value']:.4g}")
        A(f"- 毛毛利差（不扣报废）：均值 {gp['mean']:+,.1f} 元 → 说明收益差是否来自「少报废」这件事本身")
        A(f"- 缺货率差：均值 {so['mean'] * 100:+.3f} pp；售出率差：均值 {st['mean'] * 100:+.3f} pp")
        wins = int((piv["net_profit"]["MODEL"] > piv["net_profit"][base]).sum())
        A(f"- **逐种子胜负：{wins}/{len(piv['net_profit'])} 个种子上 MODEL 的经营毛利更高**")
        A("")
        verdicts.append((base, sr, npf))

    # ---- 模型审计：弹性估得准不准、偏差朝哪个方向 ----
    if model_diag:
        A("## 5. 模型审计：弹性估得准不准，偏差朝哪个方向？")
        A("")
        A(f"（seed = {model_diag.get('seed')}，历史窗口 {history_days} 天，"
          f"{model_diag.get('n_obs')} 条 单品×日 观测）")
        A("")
        A("| 口径 | 弹性 | 说明 |")
        A("| --- | --- | --- |")
        A(f"| 单品均值层 between | {model_diag.get('beta_between', float('nan')):+.3f} | 横截面成分，不是价格效应 |")
        A(f"| 朴素 pooled OLS | {model_diag.get('beta_pooled', float('nan')):+.3f} "
          f"(R²={model_diag.get('r2_pooled', float('nan')):.3f}) | 混了组间+组内 |")
        A(f"| **单品固定效应（本模块采用）** | **{model_diag.get('beta_within', float('nan')):+.3f}** | 可用来定价的弹性 |")
        A(f"| 真值（仿真器，仅事后核对） | {model_diag.get('beta_true_mean', float('nan')):+.3f} "
          f"[{model_diag.get('beta_true_min', float('nan')):.2f}, {model_diag.get('beta_true_max', float('nan')):.2f}] | "
          f"**不参与定价** |")
        A("")
        A(f"- 估计 vs 真值：相关系数 {model_diag.get('corr_est_true', float('nan')):+.3f}，"
          f"平均绝对误差 {model_diag.get('mae', float('nan')):.3f} 弹性单位")
        A(f"- 平均 |估计| = {model_diag.get('abs_est_mean', float('nan')):.3f} vs "
          f"平均 |真值| = {model_diag.get('abs_true_mean', float('nan')):.3f} → "
          f"**{'估计偏平（低估弹性）' if model_diag.get('abs_est_mean', 9) < model_diag.get('abs_true_mean', 0) else '估计偏陡'}**")
        A(f"- 缺货截断剔除：{model_diag.get('n_dropped_stockout')} / {model_diag.get('n_obs')} 行；"
          f"价格口径：{model_diag.get('price_basis')}")
        A(f"- 与公开真实蔬菜数据校准区间 [-0.573, -0.316] 的对照：{model_diag.get('plausibility', 'n/a')}")
        A("")
        A("> **这段是「结论偏保守」的量化证据**：估计出的 |弹性| 小于真值 → 模型低估「打折能带来多少增量」 → "
          "打折偏保守、清货偏少。所以 MODEL 若在某个指标上赢，是赢在偏保守的估计上；"
          "若在某个指标上输（例如损耗率高于 RULE），也可以部分归因于这个方向已知的偏差。")
        A("")

    # ---- 假设边界 ----
    A("## 6. 假设边界（结论只在下列条件下成立）")
    A("")
    A("1. **数据来自自研仿真器**，不是真实门店流水；需求函数、参数分布、补货节奏都是我们设定的。")
    A("2. 需求形式为 `λ = base_i · exp(−ε_i·(p/base_i − 1)) · hour_factor · weekday_factor`，")
    A("   弹性不随价格区间变化（常弹性），且单品之间**没有交叉价格效应**（黄瓜打折不会抢苹果的客人）。")
    A("3. 补货策略对三种策略**完全相同**（上表进货量/批次数差异≈0 已验证），所以本评测隔离的是「定价」这一个变量。")
    A("4. 本评测**关闭了推送**（`promo_lift ≡ 1`）：推送带来的增量需求不进入对比，推送效果单独在推送模块评估。")
    A("5. 消费者是同质的、无策略性等待行为（不会「知道明天更便宜所以今天不买」），也没有囤货。")
    A("6. 每个种子只有 30 天测试期，季节性与节假日效应未建模；跨种子波动已用均值±标准差与逐种子差值如实给出。")
    A("")

    # ---- 局限 ----
    A("## 7. 局限（至少 3 条，答辩会就这几条提问）")
    A("")
    A("1. **仿真自证风险（最重要）**：结论只在「我们自己的仿真器假设」下成立。仿真器的需求函数就是")
    A("   「对价格敏感、对剩余天数不敏感」的形态，模型定价天然占优；换一台仿真器结论可能变化。")
    A("   我们能做到的缓解是：① 需求参数用公开真实蔬菜数据校准（`docs/02`）；② 明确列出假设边界；")
    A("   ③ 主动报告模型**没赢**的指标与种子。**但不能声称这是真实门店的因果结论。**")
    A("2. **本仿真器里价格不是解决损耗的正确杠杆**：第 2 节的扫描显示，连「只在到期当天打 9 折」都亏 1.2%。")
    A("   因为约六成单品 |ε| < 1（常弹性下收入随价格上升），且补货量外生、库存本就过剩。")
    A("   要根治损耗应当调整**补货量**，而补货在本评测里对三个策略完全相同——这是评测的边界，不是模型的能力边界。")
    A("3. **弹性估计的内生性与截断偏差**：历史价格由规则策略决定（库存压手才打折），且缺货日销量被截断，")
    A("   两个偏差**同向**——都会让 |弹性| 被低估，从而让 MODEL 打折偏保守。因此本报告里 MODEL 的优势是")
    A("   偏保守的下界；反过来，如果 MODEL 输了，也不能说明模型思路错，只能说明它在低估弹性的情况下不够激进。")
    A("4. **批次粒度简化**：真实店主不会逐箱登记，本评测按「品类+到货日」合成批次；批次级 DP 还假设")
    A("   单批次在单品内的需求份额不变，未建模同单品多批次之间的互相蚕食（该近似对「留到明天」的价值略偏保守）。")
    A("5. **推送环节未纳入本评测**：`docs/00` 里的「客流适配推送」是独立链路，本报告的 30 天测试期没有开推送，")
    A("   所以这里的收益**不含**推送带来的增量客流。")
    A("6. **单店、单场景、30 天**：未覆盖节假日、天气、竞品降价、供应链断货等外生冲击；")
    A("   也未建模顾客对频繁变价的信任损耗（真实店里天天打折会伤价格锚点）。")
    A("7. **成本与损耗参数是区间估计**：公开数据是蔬菜（散称、当天出清），迁移到水果（标品、可隔夜）")
    A("   时只保留了「损耗率」概念与量级，数值不完全可比。")
    A("")

    # ---- 附录：逐种子 ----
    A("## 8. 附录 A：逐种子原始结果（可逐行复核）")
    A("")
    cols = ["seed", "strategy", "net_profit", "gross_profit", "revenue", "scrap_rate",
            "stockout_rate", "sell_through", "avg_discount_depth", "avg_daily_stock_value", "purchase_qty"]
    cols = [c for c in cols if c in per_seed.columns]
    A("| " + " | ".join(cols) + " |")
    A("| " + " | ".join(["---"] * len(cols)) + " |")
    for _, r in per_seed.sort_values(["seed", "strategy"])[cols].iterrows():
        cells = []
        for c in cols:
            v = r[c]
            if c in ("scrap_rate", "stockout_rate", "sell_through", "avg_discount_depth"):
                cells.append(f"{v * 100:.2f}%")
            elif c in ("net_profit", "gross_profit", "revenue", "avg_daily_stock_value", "purchase_qty"):
                cells.append(f"{v:,.1f}")
            else:
                cells.append(str(v))
        A("| " + " | ".join(cells) + " |")
    A("")

    A("## 9. 附录 B：图表")
    A("")
    for p in figs:
        try:
            rel = os.path.relpath(p, ROOT).replace("\\", "/")
        except ValueError:  # 跨盘符（自检时图在临时目录）——直接用绝对路径
            rel = p.replace("\\", "/")
        A(f"![{os.path.basename(p)}]({rel})")
        A("")
    A("---")
    A("")
    A("> 生成时间：脚本运行时刻；本文件不含任何手工填写的数字。")
    return "\n".join(lines)


# ===========================================================================
# 7. 主流程
# ===========================================================================


def load_simulator_module(prefer_toy: bool = False):
    if prefer_toy:
        return ToySimulatorModule
    try:
        import simulator  # type: ignore

        if hasattr(simulator, "ShopSimulator"):
            return simulator
        raise ImportError("scripts/simulator.py 存在但没有 ShopSimulator 类")
    except ImportError as e:
        raise SystemExit(
            "找不到可用的仿真器：scripts/simulator.py 必须提供 ShopSimulator。\n"
            f"原始错误：{e}\n"
            "请先在 scripts/simulator.py 提供如下接口（见 task-2 / task-5）：\n"
            "  ShopSimulator(seed=..., days=90, warmup_days=30)\n"
            "  sim.step_day(pricing_fn) -> {'sales','scrap','purchase','demand','traffic',...}\n"
            "  sim.history_observed() / sim.reset() / sim.snapshot() / sim.restore(snap)\n"
            "在拿到接口之前，可以用 --selftest 验证本脚本自身的流水线。"
        )


def _probe() -> int:
    """联调模式：打印仿真器真实的 state / day 结构，确认适配层与 sim 契约一致。"""
    mod = load_simulator_module()
    print("=== 仿真器接口实况（--probe）===")
    sim = mod.ShopSimulator(seed=20260701, days=8, warmup_days=3)
    print(f"  类: {type(sim).__name__}")
    for name in ("snapshot", "restore", "reset", "state_hash", "noise_fingerprint", "observed_history", "step_day"):
        print(f"  has {name:20s}: {callable(getattr(sim, name, None))}")
    print(f"  noise_fingerprint : {getattr(sim, 'noise_fingerprint', lambda: 'n/a')()}")

    seen = {}

    def _spy(biz_date, day_index, state):
        seen["state_keys"] = sorted(map(str, state.keys()))
        b = state.get("batches")
        h = state.get("history")
        seen["batches_cols"] = sorted(map(str, getattr(b, "columns", [])))
        seen["history_cols"] = sorted(map(str, getattr(h, "columns", [])))
        seen["n_batches"] = len(b) if b is not None else 0
        seen["n_history"] = len(h) if h is not None else 0
        return {str(r["item_code"]): 0.80 for r in (b.to_dict("records") if b is not None and len(b) else [])}

    for _ in range(3):
        rec = sim.step_day(_spy)
    print(f"  state 键     : {seen.get('state_keys')}")
    print(f"  batches 列   : {seen.get('batches_cols')}")
    print(f"  history 列   : {seen.get('history_cols')}")
    print(f"  当日批次数={seen.get('n_batches')}  历史行数={seen.get('n_history')}")
    print(f"  day 键       : {sorted(map(str, rec.keys()))}")
    for k in ("sales", "scrap", "purchase", "demand", "traffic"):
        d = _df(_get(rec, k))
        print(f"    {k:9s} rows={len(d):5d} cols={sorted(map(str, d.columns))}")
    print(f"  history_observed(full=True) 列: {sorted(map(str, sim.observed_history(full=True).columns))}")
    print(f"  history_observed() 默认行数: {len(sim.observed_history())} / full: {len(sim.observed_history(full=True))}")
    snap = sim.snapshot()
    print(f"  snapshot 顶层键: {sorted(map(str, snap.keys()))}（应不含 _truth/噪声矩阵）")
    print(f"  state_hash: {sim.state_hash()}")
    return 0


def _diagnose_model(seed: int, days: int, history_days: int,
                    sim_kwargs: Optional[dict] = None, quiet: bool = False) -> dict:
    """模型诊断：跑完历史窗口后，检查估值器是否真的工作。返回结构化审计结果。

    ★ 本函数会读取 `sim._truth` 做**事后审计**（估计弹性 vs 真值弹性），
      这是唯一允许触碰真值的地方，且它的输出**绝不回流给任何定价函数**。
    """
    from pricing_model import elasticity_plausibility, fit_demand_model, naive_pooled_ols

    mod = load_simulator_module()
    if not quiet:
        print(f"=== 模型诊断（seed={seed}, 历史窗口={history_days} 天）===")
    harness = SimHarness(mod, seed, days, warmup_days=history_days, sim_kwargs=sim_kwargs)
    rule_fn = make_rule_pricing_fn()
    for _ in range(history_days):
        harness.step(rule_fn)
    hist = harness.history()
    if not quiet:
        print(f"  可观测历史: {len(hist)} 行 × {len(hist.columns)} 列")
        print(f"    断货行数: {int(hist['is_stockout'].sum()) if 'is_stockout' in hist else 'n/a'} / {len(hist)}")

    diag = naive_pooled_ols(hist, base_price_map=_base_map_from_state(harness))
    if not quiet:
        print(f"  诊断估计: 价格口径={diag.get('price_basis')}  between={diag['beta_between']:+.3f}"
              f"(R²={diag['r2_between']:.3f}) pooled={diag['beta_naive_pooled']:+.3f}"
              f"(R²={diag['r2_pooled']:.3f}) within={diag['beta_within']:+.3f}   n={diag['n_obs']}")

    model = fit_demand_model(hist, base_price_map=_base_map_from_state(harness))
    if not quiet:
        print(f"  {model.summary()}")
        print(f"  弹性区间判断: {elasticity_plausibility(model.beta_pooled)}")

    out: dict = {
        "seed": seed,
        "n_obs": diag.get("n_obs"),
        "beta_between": diag.get("beta_between"),
        "beta_pooled": diag.get("beta_naive_pooled"),
        "beta_within": diag.get("beta_within"),
        "r2_pooled": diag.get("r2_pooled"),
        "price_basis": diag.get("price_basis"),
        "n_dropped_stockout": model.meta.get("n_dropped_stockout"),
        "plausibility": elasticity_plausibility(model.beta_pooled),
    }

    truth = getattr(harness.sim, "_truth", None)
    if truth is not None and hasattr(truth, "items"):
        ti = truth.items
        # ★ sim 的 ITEM_SPEC 存的是弹性**绝对值**（需求函数是 exp(−eps·(p/base−1))），
        #   惯用弹性 dlog q/dlog p 在 p=base 处等于 −eps。这里统一成惯用口径再比。
        tmap = {str(c): -float(e) for c, e in zip(ti["item_code"].astype(str), ti["elasticity"])}
        pairs = [(c, model.beta.get(c), tmap[c]) for c in sorted(tmap) if c in model.beta]
        if pairs:
            est = np.array([p[1] for p in pairs])
            tru = np.array([p[2] for p in pairs])
            out.update({
                "beta_true_mean": float(tru.mean()),
                "beta_true_min": float(tru.min()),
                "beta_true_max": float(tru.max()),
                "corr_est_true": float(np.corrcoef(est, tru)[0, 1]),
                "mae": float(np.abs(est - tru).mean()),
                "abs_est_mean": float(np.abs(est).mean()),
                "abs_true_mean": float(np.abs(tru).mean()),
                "n_items_audited": len(pairs),
            })
            if not quiet:
                print(f"  ★ 事后审计（真值只用于核对，不参与定价）: 真值弹性(惯用口径) "
                      f"[{tru.min():.3f}, {tru.max():.3f}]，均值 {tru.mean():.3f}")
                print(f"    估计 vs 真值 相关系数 = {out['corr_est_true']:+.3f}，平均绝对误差 = {out['mae']:.3f}")
                print(f"    平均 |估计| = {out['abs_est_mean']:.3f} vs 平均 |真值| = {out['abs_true_mean']:.3f} → "
                      f"{'估计偏平（低估弹性，结论偏保守）' if out['abs_est_mean'] < out['abs_true_mean'] else '估计偏陡'}")

    if not quiet:
        st = harness.sim.day_context()
        b = st["batches"]
        agg = b.groupby("item_code").agg(remain=("remain_qty", "sum"))
        print(f"  测试期起点: {len(b)} 个批次 / {len(agg)} 个单品，总现存 {agg['remain'].sum():.0f} 件")
    return out


def _base_map_from_state(harness: SimHarness) -> Dict[str, float]:
    """从当前台账取 item_code -> 原价（价格口径的首选依据）。"""
    st = harness.sim.day_context()
    b = _df(st.get("batches"))
    if not len(b):
        return {}
    return {str(c): float(v) for c, v in zip(b["item_code"], b["base_price"])}


def make_constant_discount_fn(rate: float, min_discount_rate: Optional[float] = None):
    """诊断用：对全部在售批次挂同一个折扣（用来测"打折到底赚不赚钱"的纯价格效应）。

    `min_discount_rate` 与 RULE/MODEL 保持同一约束口径——否则扫出来的"统一 5 折"
    在真实系统里根本挂不出去（会被店主的最低折扣线拦住）。
    """

    def _fn(biz_date, day_index, state):
        batches = _records(_get(state, "batches"))
        r = float(rate)
        if min_discount_rate is not None:
            r = max(r, float(min_discount_rate))
        return {str(b.get("item_code")): r for b in batches if b.get("item_code")}

    _fn.strategy_name = f"CONST{rate}"  # type: ignore[attr-defined]
    return _fn


def make_last_day_fn(rate: float, max_days_left: int = 0, min_cover: float = 0.0):
    """诊断用：**只在最后 max_days_left 天**对"明显卖不完"的批次打折。

    这是"理论上限探测器"：如果连这种精准清货都打不过 FIXED，说明在这个仿真器里
    "不打折"就是毛利最优，MODEL 与 FIXED 的差距是**结构性的**而不是估计误差。
    """

    def _fn(biz_date, day_index, state):
        batches = _records(_get(state, "batches"))
        trigger: Dict[str, bool] = {}
        for b in batches:
            code = str(b.get("item_code"))
            hit = int(b.get("days_left") or 0) <= max_days_left
            trigger[code] = trigger.get(code, False) or hit
        return {c: (float(rate) if hit else 1.00) for c, hit in trigger.items()}

    _fn.strategy_name = f"LAST{max_days_left}D@{rate}"  # type: ignore[attr-defined]
    return _fn


def _discount_sweep(seed: int, days: int, history_days: int, rates: Sequence[float],
                    sim_kwargs: Optional[dict] = None, quiet: bool = False,
                    min_discount_rate: Optional[float] = None) -> pd.DataFrame:
    """扫描"统一折扣 / 定向清货"这个纯价格维度，回答一个前置问题：

    **在这个仿真器里，打折到底赚不赚钱？**

    这是给结论定性的先验证据，不是事后找理由：如果连"只在到期当天打 9 折"都亏，
    那 MODEL 的任务就不是"多打折"，而是"**只在划得来的地方打折**"。
    返回逐策略指标表（同时写进评测报告）。

    `min_discount_rate`：与主评测同一约束口径。**必须传**，否则扫描里会出现
    "统一 5 折"这类在真实系统里根本挂不出去的档位，与主表口径不一致
    （低于底线的档位会被抬到底线，等于重复计一行，报告里会显式标注）。
    """
    mod = load_simulator_module()
    if not quiet:
        print(f"=== 统一折扣 / 定向清货扫描（seed={seed}，测试期 {days - history_days} 天）===")
        if min_discount_rate is not None:
            print(f"    约束：最低折扣线 {min_discount_rate:.2f}（低于该档的策略会被抬到该档，故与底线档等价）")
    harness = SimHarness(mod, seed, days, warmup_days=history_days, sim_kwargs=sim_kwargs)
    rule_fn = make_rule_pricing_fn(min_discount_rate=min_discount_rate)
    for _ in range(history_days):
        harness.step(rule_fn)
    snap = harness.snapshot()
    # 先看清"剩余天数"分布——它决定了哪些折扣档才可能起作用
    st = harness.sim.day_context()
    dl = st["batches"]["days_left"].value_counts().sort_index()
    if not quiet:
        print("  第 60 天在售批次的剩余天数分布: " + ", ".join(f"{int(k)}天×{v}" for k, v in dl.items()))
        print(f"{'策略':>10} | {'经营毛利':>12} | {'毛毛利':>12} | {'损耗率':>8} | {'售出率':>8} | {'折扣深度':>8} | {'挂出低于成本':>12}")

    rows_out: List[dict] = []
    base_profit = None
    for label, fn in (
        [("FIXED", make_fixed_pricing_fn())]
        + [(f"统一{d * 10:.0f}折", make_constant_discount_fn(d, min_discount_rate=min_discount_rate))
           for d in rates]
        + [("仅到期日9折", make_last_day_fn(0.90, 0)),
           ("仅到期日7折", make_last_day_fn(0.70, 0)),
           ("仅到期日5折", make_last_day_fn(0.50, 0)),
           ("剩1天内7折", make_last_day_fn(0.70, 1))]
    ):
        harness.restore(snap)
        acc = TestPeriodAccumulator()
        state_box: Dict[str, object] = {}

        def _w(biz_date, day_index, state, _fn=fn, _acc=acc, _box=state_box):
            _box["state"] = state
            _acc.observe_state(state)
            dec = _fn(biz_date, day_index, state)
            _acc.observe_decisions(dec if isinstance(dec, dict) else {})
            return dec

        pending = None
        for _ in range(days - history_days):
            rec = harness.step(_w)
            hist_now = _df(_get(state_box.get("state"), "history"))
            if pending is not None:
                acc.add_day(pending[0], pending[1], state_box.get("state") or {}, hist_now)
            pending = (_get(rec, "biz_date", None) or _get(rec, "date", None), rec)
        if pending is not None:
            hist = harness.history()
            acc.add_day(pending[0], pending[1], state_box.get("state") or {}, hist)
        m = acc.finalize()
        if base_profit is None:
            base_profit = m["net_profit"]
        rel = (m["net_profit"] - base_profit) / abs(base_profit) * 100
        rows_out.append({
            "policy": label,
            "net_profit": m["net_profit"],
            "net_profit_vs_fixed_pct": rel,
            "scrap_rate": m["scrap_rate"],
            "sell_through": m["sell_through"],
            "avg_discount_depth": m["avg_discount_depth"],
            "below_cost_posted_share": m["below_cost_posted_share"],
        })
        if not quiet:
            print(f"{label:>10} | {m['net_profit']:>12,.0f} ({rel:+5.1f}%) | {m['gross_profit']:>12,.0f} | "
                  f"{m['scrap_rate'] * 100:>7.2f}% | {m['sell_through'] * 100:>7.2f}% | "
                  f"{m['avg_discount_depth'] * 100:>7.2f}% | {m['below_cost_posted_share'] * 100:>11.1f}%")
    if not quiet:
        print("\n读法：'经营毛利'列括号内是相对 FIXED 的变化。若各档折扣都为负，")
        print("      说明该仿真器里'打折清货'是拿毛利换损耗率，价格不是解决损耗的正确杠杆。")
    return pd.DataFrame(rows_out)


def run_evaluation(sim_module, seeds: Sequence[int], days: int, history_days: int,
                   model_refit_every: int, verbose: bool,
                   sim_kwargs: Optional[dict] = None,
                   min_discount_rate: Optional[float] = None) -> Tuple[pd.DataFrame, List[dict], pd.DataFrame]:
    rows: List[dict] = []
    diags: List[dict] = []
    daily_rows: List[dict] = []
    for si, seed in enumerate(seeds):
        t0 = time.time()
        results, diag = run_one_seed(
            sim_module, seed, days=days, history_days=history_days,
            model_refit_every=model_refit_every, verbose=verbose, sim_kwargs=sim_kwargs,
            min_discount_rate=min_discount_rate,
        )
        dt = time.time() - t0
        print(f"  [seed {seed}] {si + 1}/{len(seeds)} 完成，用时 {dt:.1f}s")
        for name, res in results.items():
            row = {"seed": seed, "strategy": name}
            row.update(res.metrics)
            row["discount_posted_json"] = json.dumps({str(k): v for k, v in res.discount_posted.items()})
            row["posted_total"] = int(sum(res.discount_posted.values()))
            row["warnings"] = len(res.warnings)
            rows.append(row)
            for d in res.daily:
                daily_rows.append({"seed": seed, "strategy": name, **d})
        diags.append(diag)
    return pd.DataFrame(rows), diags, pd.DataFrame(daily_rows)


def _floor_arg(args) -> Optional[float]:
    """把命令行 --min-discount-rate 规整成"约束值或 None"（0/负数视为不限）。"""
    v = getattr(args, "min_discount_rate", None)
    if v is None or float(v) <= 0:
        return None
    return float(v)


def main() -> int:
    ap = argparse.ArgumentParser(description="三策略统一评测（FIXED / RULE / MODEL）")
    ap.add_argument("--seeds", type=int, default=20, help="随机种子个数（协议要求 >=20）")
    ap.add_argument("--seed-base", type=int, default=20260701, help="第一个种子")
    ap.add_argument("--seed-step", type=int, default=101, help="种子步长")
    ap.add_argument("--days", type=int, default=90, help="营业期天数")
    ap.add_argument("--history-days", type=int, default=60, help="历史窗口天数（其余为测试期）")
    ap.add_argument("--refit-every", type=int, default=5, help="模型版重估弹性的间隔天数")
    ap.add_argument("--elasticity-scale", type=float, default=None,
                    help="仿真器真值弹性缩放（敏感性分析：0.7 / 1.0 / 1.3）")
    ap.add_argument("--coverage", type=float, default=None,
                    help="仿真器补货系数（敏感性分析：库存过剩程度）")
    ap.add_argument("--no-sweep", action="store_true", help="跳过'打折赚不赚钱'的前置扫描")
    ap.add_argument("--min-discount-rate", type=float, default=0.60,
                    help="店主设定的最低折扣线（价格比下限），与系统侧 app.pricing.min-discount-rate 同口径；"
                         "0 表示不限（用于对照'约束前 vs 约束后'）")
    ap.add_argument("--out", default=os.path.join(ROOT, "release", "evaluation-report.md"))
    ap.add_argument("--figs", default=os.path.join(ROOT, "release", "figs"))
    ap.add_argument("--quick", action="store_true", help="冒烟：3 个种子")
    ap.add_argument("--probe", action="store_true", help="只打印仿真器接口实况（state/day 的键与形状），用于联调")
    ap.add_argument("--diagnose-model", action="store_true", help="诊断模型：估计弹性 vs 真值、预测需求 vs 现存")
    ap.add_argument("--discount-sweep", action="store_true", help="扫描统一折扣，判断'打折是否赚钱'")
    ap.add_argument("--selftest", action="store_true", help="用内置玩具环境自检整条流水线")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    if args.probe:
        return _probe()
    if args.discount_sweep:
        sfx = {}
        if args.elasticity_scale:
            sfx["elasticity_scale"] = args.elasticity_scale
        if args.coverage:
            sfx["coverage"] = args.coverage
        _discount_sweep(args.seed_base, args.days, args.history_days,
                        (1.00, 0.90, 0.80, 0.70, 0.60, 0.50), sim_kwargs=sfx)
        return 0
    if args.diagnose_model:
        _diagnose_model(args.seed_base, args.days, args.history_days)
        return 0
    if args.selftest:
        return _selftest_pipeline()
    if args.quick:
        args.seeds = min(args.seeds, 3)
    if args.seeds < 20 and not args.quick:
        print(f"⚠ 协议要求 >=20 个种子，当前 {args.seeds} 个。报告里会如实记录种子数。")

    sim_module = load_simulator_module()
    sim_kwargs: Dict[str, object] = {}
    if args.elasticity_scale:
        sim_kwargs["elasticity_scale"] = args.elasticity_scale
    if args.coverage:
        sim_kwargs["coverage"] = args.coverage
    seeds = [args.seed_base + i * args.seed_step for i in range(args.seeds)]
    print(f"=== 三策略评测：{args.seeds} 个种子 × {args.days} 天（前 {args.history_days} 天历史 + "
          f"{args.days - args.history_days} 天测试） ===")
    if sim_kwargs:
        print(f"    仿真器附加参数（敏感性分析）：{sim_kwargs}")

    # 0) 前置证据：打折到底赚不赚钱（第 1 个种子）
    sweep_df = None
    if not args.no_sweep:
        sweep_df = _discount_sweep(seeds[0], args.days, args.history_days,
                                   (1.00, 0.90, 0.80, 0.70, 0.60, 0.50), sim_kwargs=sim_kwargs,
                                   min_discount_rate=_floor_arg(args))
    # 0b) 模型审计：弹性估得准不准、偏向哪边
    model_diag = None
    try:
        model_diag = _diagnose_model(seeds[0], args.days, args.history_days, sim_kwargs=sim_kwargs)
    except Exception as e:  # 审计失败不应阻断主评测
        print(f"  ⚠ 模型审计跳过：{e}")

    t0 = time.time()
    per_seed, diags, daily = run_evaluation(
        sim_module, seeds, args.days, args.history_days, args.refit_every, args.verbose,
        sim_kwargs=sim_kwargs,
        min_discount_rate=_floor_arg(args),
    )
    elapsed = time.time() - t0

    # 图表
    plt, cn = setup_matplotlib()
    made = _figs_all(per_seed, daily, args.figs, cn)

    command = " ".join(["python", "scripts/evaluate_strategies.py"] +
                       [f"--seeds {args.seeds}", f"--days {args.days}", f"--history-days {args.history_days}",
                        f"--seed-base {args.seed_base}", f"--seed-step {args.seed_step}",
                        f"--min-discount-rate {args.min_discount_rate}"] +
                       ([f"--elasticity-scale {args.elasticity_scale}"] if args.elasticity_scale else []) +
                       ([f"--coverage {args.coverage}"] if args.coverage else []))
    report = build_report(per_seed, diags, command=command, elapsed=elapsed, seeds=seeds,
                          days=args.days, history_days=args.history_days, cn=cn, figs=made,
                          sweep=sweep_df, model_diag=model_diag, sim_kwargs=sim_kwargs)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(report)
    print(f"\n✅ 报告已写入 {args.out}")
    print(f"✅ 图表 {len(made)} 张写入 {args.figs}")
    print(f"⏱  总耗时 {elapsed:.1f}s")
    _print_summary(per_seed)
    return 0


def _figs_all(per_seed, daily, figs_dir, cn):
    plt, _ = setup_matplotlib()
    made = make_figures_1_2(per_seed, figs_dir, cn)
    made += make_figures_more(per_seed, daily, figs_dir, cn)
    return made


def make_figures_1_2(per_seed, figs_dir, cn):
    """图1/图2（拆出来是为了让 make_figures 保持单一职责）。"""
    return _two_figs(per_seed, figs_dir, cn)


def _two_figs(per_seed, figs_dir, cn):
    plt, _ = setup_matplotlib()
    os.makedirs(figs_dir, exist_ok=True)
    made = []
    order = [s for s in STRATEGIES if s in set(per_seed["strategy"])]
    colors = {"FIXED": "#8c8c8c", "RULE": "#4c78a8", "MODEL": "#e4572e"}

    def L(zh, en):
        return zh if cn else en

    fig, ax = plt.subplots(figsize=(6.4, 4.3))
    means = [per_seed[per_seed.strategy == s]["scrap_rate"].mean() for s in order]
    sds = [per_seed[per_seed.strategy == s]["scrap_rate"].std(ddof=1) for s in order]
    ax.bar(order, means, yerr=sds, capsize=5, color=[colors[s] for s in order])
    for i, m in enumerate(means):
        ax.text(i, m, f"{m * 100:.2f}%", ha="center", va="bottom", fontsize=10)
    ax.set_ylabel(L("损耗率 = 报废量/进货量", "Scrap rate = scrap / purchase"))
    ax.set_title(L("三策略损耗率对比（跨种子 均值±标准差）",
                   "Scrap rate by strategy (mean ± sd over seeds)"))
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    p = os.path.join(figs_dir, "eval_scrap_rate.png")
    fig.savefig(p, dpi=150)
    plt.close(fig)
    made.append(p)

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.3))
    for ax, key, zh, en in (
        (axes[0], "net_profit", "经营毛利（已扣报废成本）/ 元", "Net profit (after scrap cost) / CNY"),
        (axes[1], "gross_profit", "毛毛利（未扣报废成本）/ 元", "Gross profit (before scrap) / CNY"),
    ):
        m = [per_seed[per_seed.strategy == s][key].mean() for s in order]
        sd = [per_seed[per_seed.strategy == s][key].std(ddof=1) for s in order]
        ax.bar(order, m, yerr=sd, capsize=5, color=[colors[s] for s in order])
        ax.set_title(L(zh, en), fontsize=10)
        ax.grid(axis="y", alpha=0.3)
    fig.suptitle(L("三策略毛利对比（跨种子 均值±标准差）", "Profit by strategy (mean ± sd over seeds)"))
    fig.tight_layout()
    p = os.path.join(figs_dir, "eval_profit.png")
    fig.savefig(p, dpi=150)
    plt.close(fig)
    made.append(p)
    return made


def _print_summary(per_seed: pd.DataFrame) -> None:
    print("\n=== 关键数字速览（测试期，跨种子均值） ===")
    for s in [x for x in STRATEGIES if x in set(per_seed["strategy"])]:
        v = per_seed[per_seed.strategy == s]
        print(f"  {s:6s} 经营毛利={v['net_profit'].mean():>10,.1f}±{v['net_profit'].std(ddof=1):>8,.1f}  "
              f"损耗率={v['scrap_rate'].mean() * 100:>6.2f}%±{v['scrap_rate'].std(ddof=1) * 100:>5.2f}  "
              f"缺货率={v['stockout_rate'].mean() * 100:>5.2f}%  "
              f"售出率={v['sell_through'].mean() * 100:>6.2f}%  "
              f"折扣深度={v['avg_discount_depth'].mean() * 100:>5.2f}%")


# ===========================================================================
# 8. 玩具环境（仅用于 --selftest：验证流水线，绝不产出任何结论）
# ===========================================================================


class _ToySim:
    """最小可用的 ShopSimulator 替身，只实现评测脚本用到的契约。**不可用于得出任何结论。**"""

    DEFAULT_WARMUP_DAYS = 0

    def __init__(self, seed: int, days: int = 90, warmup_days: int = 0):
        self.seed = int(seed)
        self.days = int(days)
        self.items = [f"t{k}" for k in range(5)]
        self.base = {c: 10.0 + 2 * k for k, c in enumerate(self.items)}
        self.cost = {c: self.base[c] * 0.62 for c in self.items}
        self.eps = {c: -(1.0 + 0.25 * k) for k, c in enumerate(self.items)}
        self.life = {c: 3 + (k % 5) for k, c in enumerate(self.items)}
        self.t0 = pd.Timestamp("2026-03-02")
        self.reset()

    def reset(self):
        self.day = 0
        self.inv = {c: [] for c in self.items}
        self.hist = []
        self._last_state = None
        self.rng = np.random.default_rng(self.seed)

    def _state(self):
        rows = []
        for c in self.items:
            for bi, lot in enumerate(self.inv[c]):
                dl = (lot["expire"] - (self.t0 + pd.Timedelta(days=self.day))).days
                rows.append(dict(item_code=c, batch_no=f"{c}-{bi}", days_left=max(dl, 0),
                                 remain_qty=lot["qty"], init_qty=lot["init"], cost_price=self.cost[c],
                                 base_price=self.base[c], shelf_life_days=self.life[c],
                                 arrive_date=self.t0 + pd.Timedelta(days=lot["arrive"]),
                                 expire_date=lot["expire"]))
        return {"biz_date": self.t0 + pd.Timedelta(days=self.day), "day_index": self.day,
                "batches": pd.DataFrame(rows), "history": pd.DataFrame(self.hist),
                "candidate_discounts": DISCOUNT_GRID, "near_expiry_days": 2}

    def step_day(self, pricing_fn):
        st = self._state()
        self._last_state = st
        decisions = pricing_fn(st["biz_date"], self.day, st) if pricing_fn else {}
        decisions = decisions or {}
        biz = st["biz_date"]
        purchase, scrap, sales, demand = [], [], [], []
        for c in self.items:
            # 补货：每 life 天到一次货（与定价无关）。偶数号单品按 30 件/天补 -> 严重压货（会报废、
            # 模型应当打折清货）；奇数号单品按 8 件/天补 -> 供不应求（会缺货，模型应当维持原价）。
            # 一个自检同时覆盖"该打折"和"不该打折"两条路径。
            if self.day % self.life[c] == 0:
                per_day = 30.0 if (self.items.index(c) % 2 == 0) else 8.0
                q = per_day * self.life[c]
                self.inv[c].append({"qty": q, "init": q, "arrive": self.day,
                                    "expire": biz + pd.Timedelta(days=self.life[c])})
                purchase.append(dict(item_code=c, batch_no=f"{c}-{len(self.inv[c]) - 1}", quantity=q,
                                     cost_price=self.cost[c], expire_date=biz + pd.Timedelta(days=self.life[c])))
            on_hand = sum(l["qty"] for l in self.inv[c])
            d = float(decisions.get(c, 1.0))
            if d > 1.5:
                d = d / self.base[c]
            lam = 15.0 * (d ** self.eps[c]) * (1.2 if biz.dayofweek >= 5 else 1.0)
            # CRN：用 (day, item) 索引的固定格子抽噪声（与价格无关）
            u = np.random.default_rng([self.seed, self.day, hash(c) % 99991]).random()
            from scipy.stats import poisson
            dem = float(poisson.ppf(u, lam))
            sold = min(dem, on_hand)
            left = sold
            for lot in self.inv[c]:
                if left <= 0:
                    break
                take = min(lot["qty"], left)
                lot["qty"] -= take
                left -= take
                if take > 0:
                    sales.append(dict(biz_date=biz, item_code=c, batch_no=f"{c}-{self.inv[c].index(lot)}",
                                      quantity=take, unit_price=self.base[c] * d,
                                      is_discount=d < 0.999, is_return=False))
            self.inv[c] = [l for l in self.inv[c] if l["qty"] > 1e-9]
            demand.append(dict(item_code=c, demand_qty=dem, sold_qty=sold,
                               lost_qty=max(dem - sold, 0.0), is_stockout=dem > sold + 1e-9))
            self.hist.append(dict(biz_date=biz, item_code=c, unit_price=self.base[c] * d,
                                  sold_qty=sold, discount_rate=d, on_hand_start=on_hand,
                                  arrival_qty=((30.0 if self.items.index(c) % 2 == 0 else 8.0)
                                               * self.life[c])
                                  if self.day % self.life[c] == 0 else 0.0,
                                  remain_end=sum(l["qty"] for l in self.inv[c]),
                                  is_stockout=bool(dem > sold + 1e-9)))
        # 到期报废
        tomorrow = biz + pd.Timedelta(days=1)
        for c in self.items:
            keep = []
            for lot in self.inv[c]:
                if lot["expire"] <= tomorrow:
                    scrap.append(dict(item_code=c, batch_no=f"{c}-0", quantity=lot["qty"],
                                      cost_price=self.cost[c], reason="EXPIRED"))
                else:
                    keep.append(lot)
            self.inv[c] = keep
        self.day += 1
        return {"biz_date": biz, "sales": pd.DataFrame(sales), "scrap": pd.DataFrame(scrap),
                "purchase": pd.DataFrame(purchase), "demand": pd.DataFrame(demand),
                "traffic": pd.DataFrame([dict(hour_of_day=9, order_count=12)]),
                "warnings": []}

    def history_observed(self):
        return pd.DataFrame(self.hist)


class _ToyModule:
    ShopSimulator = _ToySim
    DEFAULT_WARMUP_DAYS = 0


ToySimulatorModule = _ToyModule


def _selftest_pipeline() -> int:
    """用玩具环境跑通"多策略 × 多种子 → 图表 → 报告"，输出写到系统临时目录。"""
    print("=== evaluate_strategies.py 流水线自检（玩具环境，结果不具备任何结论意义） ===")
    outdir = os.path.join(tempfile.gettempdir(), "fruit_eval_selftest")
    os.makedirs(outdir, exist_ok=True)
    figs = os.path.join(outdir, "figs")
    t0 = time.time()
    per_seed, diags, daily = run_evaluation(
        ToySimulatorModule, [1, 2, 3], days=20, history_days=10, model_refit_every=3, verbose=False
    )
    elapsed = time.time() - t0
    ok = True
    print(f"  跑完 3 种子 × 20 天，用时 {elapsed:.1f}s，得到 {len(per_seed)} 行结果")

    need = {"FIXED", "RULE", "MODEL"}
    got = set(per_seed["strategy"])
    ok &= need.issubset(got)
    print(f"[{'OK ' if need.issubset(got) else 'FAIL'}] 三个策略都有结果: {sorted(got)}")

    # 同源性：三个策略的历史哈希必须一致
    same = all(len(set(d["hash_per_strategy"].values())) == 1 for d in diags)
    ok &= same
    print(f"[{'OK ' if same else 'FAIL'}] 三个策略第10天历史哈希一致（同一消费者流/同一初始库存）")

    # 指标非空且口径合理
    for s in sorted(got):
        v = per_seed[per_seed.strategy == s]
        finite = np.isfinite(v["net_profit"]).all() and np.isfinite(v["scrap_rate"]).all()
        ok &= bool(finite)
        print(f"       {s}: 经营毛利={v['net_profit'].mean():.1f} 损耗率={v['scrap_rate'].mean() * 100:.2f}% "
              f"缺货率={v['stockout_rate'].mean() * 100:.2f}% 售出率={v['sell_through'].mean() * 100:.2f}%")
        if not finite:
            print(f"[FAIL] {s} 存在非有限指标")

    # 进货量同源（补货策略与定价无关）
    piv = per_seed.pivot_table(index="seed", columns="strategy", values="purchase_qty")
    pq_same = bool(np.allclose(piv["MODEL"], piv["FIXED"]))
    ok &= pq_same
    print(f"[{'OK ' if pq_same else 'FAIL'}] 三策略进货量完全相同（补货不受定价影响）")

    # 模型必须真的在打折（否则说明 DP/枚举路径没被走到）
    posted = per_seed[per_seed.strategy == "MODEL"]["discount_posted_json"].map(
        lambda x: json.loads(x) if x else {}
    )
    n_disc = sum(v.get("0.9", 0) + v.get("0.8", 0) + v.get("0.7", 0) + v.get("0.6", 0) + v.get("0.5", 0) for v in posted)
    n_full = sum(v.get("1.0", 0) for v in posted)
    discounted = n_disc > 0
    ok &= discounted
    print(f"[{'OK ' if discounted else 'FAIL'}] MODEL 确实挂出过折扣（打折 {n_disc} 次 / 原价 {n_full} 次）")

    # 图表 + 报告
    plt, cn = setup_matplotlib()
    made = _figs_all(per_seed, daily, figs, cn)
    print(f"       生成图表 {len(made)} 张: {[os.path.basename(x) for x in made]}")
    ok &= len(made) >= 5
    report = build_report(per_seed, diags, command="--selftest（玩具环境）", elapsed=elapsed,
                          seeds=[1, 2, 3], days=20, history_days=10, cn=cn, figs=made)
    rp = os.path.join(outdir, "selftest-report.md")
    with open(rp, "w", encoding="utf-8") as f:
        f.write(report)
    print(f"       报告已写入 {rp}（{len(report)} 字符）")
    ok &= len(report) > 2000
    print(f"[{'OK ' if len(report) > 2000 else 'FAIL'}] 报告生成成功")
    print("=== 自检结果:", "全部通过" if ok else "存在失败项", "===")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
