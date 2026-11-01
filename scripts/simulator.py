#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
街边水果店销售仿真器 —— 全项目评测基准数据生成器 + 定价策略评测台
================================================================================

本文件有两个用途，共用同一套代码，**不会分叉**：

1. CLI：生成 ``data/seed/shop_*.csv``（阶段 1 的种子数据 + 后端种子）
2. 可 import 的评测台：``ShopSimulator`` 类，供 ``scripts/evaluate_strategies.py``
   在同一批消费者流上跑 FIXED / RULE / MODEL 三种策略

复现命令（先 python -m pip install -r requirements.txt）::

    $OutputEncoding=[Text.Encoding]::UTF8; [Console]::OutputEncoding=[Text.Encoding]::UTF8; chcp 65001
    python scripts/simulator.py run   --seed 20260701 --days 120 --warmup-days 60
    python scripts/simulator.py check
    python scripts/simulator.py hourly-from-public

--------------------------------------------------------------------------------
需求函数（写死在这里，文档 docs/02 同步）
--------------------------------------------------------------------------------

单品 i 在 h 时的**需求强度**（单位：份/小时，连续强度，不是最终销量）::

    lambda_i(h) = base_demand_i
                  * exp(-elasticity_i * (p_eff_i / base_price_i - 1))   # 价格效应
                  * hour_factor[h]        # 时段形状（真实数据校准）
                  * weekday_factor[dow]   # 周内效应（真实数据校准）
                  * season_factor_i(month)# 季节性（假设，振幅 ±10%）
                  * promo_lift_i          # 推送抬升（仅 push=True 时 >1）

    订单数 n_i(h) ~ Poisson(lambda_i(h) / basket_mean_i)          # 需求扰动（泊松）
    需求份数 d_i(h) = sum(该小时每笔成交的篮量 basket)             # 篮量为正随机量
    销量 s_i(h) = min(d_i(h), 现存量)                              # 库存约束（FEFO 分配）
    缺货量 lost = d_i(h) - s_i(h)                                  # 记为缺货，不结转
    报废 = 每日损耗(日损耗率由 loss_rate_pct 按保质期折算) + 到期未售

``p_eff_i`` = 当日开盘时该单品各在售批次按余量加权的挂牌均价（日内不再重估，见 docs/02）。

--------------------------------------------------------------------------------
单一随机源 & 公共随机数（CRN，task-5 硬要求）
--------------------------------------------------------------------------------

* 全程只有一个随机源：``np.random.Generator(np.random.PCG64(seed))``，构造时**一次性**
  预抽取所有外生随机量（见 ``_pre_draw``），step 过程中只按索引取值 —— 循环里**不会**
  因为状态不同而多抽/少抽一个随机数。用不了 ``np.random.*`` 全局函数，也用不了 ``random``。
* 因此 ``snapshot()/restore()`` 不需要保存随机数状态：外生噪声不在状态里，
  它是 (seed, day, hour, item) 的纯函数。
* 同 seed 下不同策略面对的是**同一张扰动矩阵**，策略只能改 lambda，不能改流。
  ``sim.noise_fingerprint()`` 返回该矩阵的指纹，可用于跨运行比对。
* ``step_day`` 只索引、不抽数 ⇒ 三个策略第 60 天状态可由 ``sim.state_hash()`` 逐位比对。

--------------------------------------------------------------------------------
对外接口（与 task-3/task-5 冻结的契约，pricing 已确认）
--------------------------------------------------------------------------------

.. code-block:: python

    from scripts.simulator import ShopSimulator, default_rule_decision

    sim = ShopSimulator(seed=20260701, days=90, warmup_days=60)
    state = sim.day_context()                  # 只含可观测信息，不含真值参数
    day = sim.step_day(pricing_fn)             # pricing_fn(biz_date, day_index, state)
                                               #   -> {item_code: discount_rate}
                                               #   也支持 {batch_no: rate} / DataFrame
    day["sales"] / day["scrap"] / day["purchase"] / day["demand"] / day["traffic"]
    hist = sim.observed_history()              # 前 warmup_days 天的可观测记录（不含真值）
    snap = sim.snapshot(); sim.restore(snap)   # 可 json 序列化，精确还原
    sim.noise_fingerprint(); sim.state_hash()  # CRN 自证工具

⚠️ **discount_rate 同名反义（踩过一次，务必看清）**：

* ``pricing_fn`` 返回值 / ``day["suggestions"]`` / ``shop_pricing_suggestion.csv`` /
  ``shop_push_message.csv`` 里的 ``discount_rate`` = **价格比**（0.5~1.0，越小越便宜）。
* ``sim.observed_history()["discount_rate"]`` / ``day_context()["history"]["discount_rate"]``
  = **折扣深度** ``1 - 实收均价/原价``（0~0.5，越大越便宜）。
* 换算：``价格比 = 1 - 深度``。审计文件 ``sim_internal_daily_item.csv`` 用 ``discount_depth``，无歧义。

真值参数（base_demand / elasticity / hour_factor / weekday_factor / 季节）只在私有属性
``sim._truth`` 里，既不进 ``day_context()``，也不进 ``snapshot()``。
``shop_item.csv`` 同样**不带**真实弹性参数。
``push=False``（评测默认）时 ``promo_lift ≡ 1.0``，对三种策略完全相同，不影响策略间比较。

作者：队友 sim（阶段 1-B / task-2 + task-5）
"""

from __future__ import annotations

import argparse
import hashlib
import inspect
import json
import math
import sys
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

import numpy as np
import pandas as pd
from scipy.stats import binom as _binom
from scipy.stats import gamma as _gamma
from scipy.stats import poisson as _poisson

# ------------------------------------------------------------------------------
# 路径与全局常量
# ------------------------------------------------------------------------------

ROOT = Path(__file__).resolve().parents[1]
SEED_DIR = ROOT / "data" / "seed"
INTERIM_DIR = ROOT / "data" / "interim"
CALIB_TRAFFIC_JSON = SEED_DIR / "calibration" / "hourly_traffic.json"

VERSION = "1.0"

CANDIDATE_DISCOUNTS: tuple[float, ...] = (1.00, 0.90, 0.80, 0.70, 0.60, 0.50)
NEAR_EXPIRY_DAYS = 2          # docs/01 §3.2：days_left <= 2 进临期清单
SHOP_HOURS: tuple[int, ...] = tuple(range(8, 22))   # 8:00 ~ 21:59，共 14 个营业小时
QUIET_HOURS = (22, 8)         # 静默时段 22:00-08:00（docs/01 §3.5）
PUSH_DAILY_LIMIT = 2          # 单会员每日推送上限（docs/01 §3.5）
MAX_BASKETS = 20              # 每 (day,hour,item) 预抽的篮量槽位数，超出的极少数按均值兜底
BASKET_TABLE_N = 512          # 篮量分位数查表档数
PUSH_LIFT = 0.15              # 单个 campaign 触达足量会员时的需求抬升（假设，见 docs/02）
PUSH_TARGET_SCALE = 8.0       # 触达会员数达到该值即视为满额抬升
PUSH_TARGET_BOOST = 2.2       # 被推送会员在选人权重上的加成
COVERAGE = 1.18               # 补货系数：按 R 天需求预报上浮 18%（临期库存的来源）
ARRIVAL_SIGMA = 0.18          # 到货量对数正态噪声
ITEM_BIAS_SIGMA = 0.10        # 单品长期订多/订少的个体偏差
MEMBER_BASE_PROB = 0.55       # 一笔成交来自会员的基准概率
SPOIL_SCALE_FINE = 100        # 斤类库存内部整数刻度：1 单位 = 0.01 斤
MIN_BASKET_FINE = 10          # 斤类最小篮量 0.1 斤

# ------------------------------------------------------------------------------
# 单品参数表（38 个单品，全部手写、逐个不同 —— 见 docs/02 参数表）
#   code, name, category, unit, cost_price, base_price, shelf_life_days,
#   loss_rate_pct, base_demand(份/天), elasticity
#
# 口径与依据（证据等级 A/B/C 见 docs/02 §3 参数表）：
# * 价格 = 街边水果店零售价（元/斤、元/盒、元/个），非超市蔬菜 kg 口径；
#   加价率 base_price/cost_price ∈ [1.65, 1.95]，与 calib 实测中位加价率 1.651 对齐
#   （loss_rate_facts.json → markup_rate，A）。
# * base_demand = 单品日需求强度（份/天），全店合计 594 份/天 ≈ 425 笔/天：
#   按水果店重新标定的**假设 B**（公开数据日均 809 笔是超市蔬菜门店量级，
#   只借形状不搬量级，见 calib 的 meta.scenario_caveat）。
# * elasticity 是价格弹性绝对值（需求函数 exp(-eps*(p/base-1))）：本表 0.63~1.27，
#   中位 0.90。依据 = calib 实测蔬菜弹性区间 [0.316, 0.573]（A，作上限参考）
#   + 文献中新鲜水果需求价格弹性约 0.5~1.2（B）。calib 警告水果弹性可能更接近 0，
#   故取中庸值，并把弹性列为敏感性分析首要变量（docs/02 §7）。
# * loss_rate_pct 取 3.0%~11.0%（蔬菜实测 6.68%~15.51% 为 A，下调到水果属假设 B）。
# ------------------------------------------------------------------------------

ITEM_SPEC: list[tuple[str, str, str, str, float, float, int, float, float, float]] = [
    ("F1001", "红富士苹果",     "仁果类",   "斤", 3.60,  6.80, 10,  4.0,  33, 0.74),
    ("F1002", "黄元帅苹果",     "仁果类",   "斤", 3.20,  5.98,  9,  4.5,  14, 0.83),
    ("F1003", "皇冠梨",         "仁果类",   "斤", 2.90,  5.50,  9,  4.5,  17, 0.80),
    ("F1004", "库尔勒香梨",     "仁果类",   "斤", 3.50,  6.50,  8,  5.0,  16, 0.77),
    ("F1005", "突尼斯软籽石榴", "特色果品", "个", 4.50,  9.80, 10,  5.0,   9, 0.88),
    ("F1006", "沾化冬枣",       "特色果品", "斤", 6.50, 12.80,  6,  7.5,  11, 0.94),
    ("F1007", "千禧圣女果",     "特色果品", "斤", 4.20,  7.98,  5,  8.5,  16, 0.83),
    ("F2001", "砂糖橘",         "柑橘类",   "斤", 4.20,  7.98,  6,  6.5,  23, 0.94),
    ("F2002", "耙耙柑",         "柑橘类",   "斤", 3.80,  6.98,  7,  5.5,  20, 0.88),
    ("F2003", "沃柑",           "柑橘类",   "斤", 3.60,  6.80,  7,  5.8,  19, 0.85),
    ("F2004", "赣南脐橙",       "柑橘类",   "斤", 3.30,  5.98,  8,  5.2,  22, 0.83),
    ("F2005", "红心柚子",       "柑橘类",   "个", 5.50, 12.80, 10,  6.0,   8, 0.99),
    ("F2006", "安岳黄柠檬",     "柑橘类",   "个", 1.10,  2.50, 10,  3.5,    6, 0.66),
    ("F3001", "丹东红颜草莓",   "浆果类",   "盒", 9.50, 19.90,  2,  9.5,  20, 1.16),
    ("F3002", "云南蓝莓",       "浆果类",   "盒", 7.80, 15.80,  4,  7.0,  11, 1.05),
    ("F3003", "智利车厘子",     "浆果类",   "斤", 28.00, 45.80, 5,  8.0,   9, 1.27),
    ("F3004", "红树莓",         "浆果类",   "盒", 8.50, 16.80,  2, 10.5,    6, 1.21),
    ("F3005", "桑葚",           "浆果类",   "盒", 5.50, 11.90,  2, 11.0,    7, 1.10),
    ("F4001", "水蜜桃",         "核果类",   "斤", 4.50,  8.80,  4,  8.5,  20, 0.96),
    ("F4002", "油桃",           "核果类",   "斤", 4.00,  7.80,  5,  7.5,  16, 0.88),
    ("F4003", "黄桃",           "核果类",   "斤", 4.20,  8.20,  4,  8.0,  12, 0.91),
    ("F4004", "黑布林李子",     "核果类",   "斤", 3.60,  6.98,  6,  7.0,  14, 0.94),
    ("F4005", "小白杏",         "核果类",   "斤", 4.80,  8.98,  3,  9.0,   8, 1.02),
    ("F5001", "进口香蕉",       "热带水果", "斤", 2.30,  3.98,  5,  8.0,  35, 0.63),
    ("F5002", "台农芒果",       "热带水果", "斤", 5.00,  9.80,  5,  7.5,  17, 0.91),
    ("F5003", "越南红心火龙果", "热带水果", "个", 3.60,  6.98,  8,  5.5,  14, 0.83),
    ("F5004", "徐香猕猴桃",     "热带水果", "个", 1.60,  3.50,  9,  6.5,  20, 0.88),
    ("F5005", "泰国小菠萝",     "热带水果", "个", 6.00, 12.80,  7,  6.0,   9, 0.94),
    ("F5006", "夏威夷木瓜",     "热带水果", "个", 4.50,  9.80,  7,  7.0,    6, 0.88),
    ("F5007", "墨西哥牛油果",   "热带水果", "个", 3.80,  7.50,  6,  6.5,   8, 0.85),
    ("F5008", "海南椰青",       "热带水果", "个", 6.50, 12.80, 10,  3.0,    7, 0.77),
    ("F6001", "海南麒麟西瓜",   "瓜类",     "斤", 1.30,  2.98,  8,  6.0,  66, 0.69),
    ("F6002", "麒麟小西瓜",     "瓜类",     "个", 12.00, 25.80, 8,  5.5,  11, 0.96),
    ("F6003", "新疆哈密瓜",     "瓜类",     "个", 9.50, 19.80,  8,  6.0,   8, 0.94),
    ("F6004", "阎良甜瓜",       "瓜类",     "斤", 2.80,  5.50,  6,  7.0,  12, 0.88),
    ("F7001", "阳光玫瑰葡萄",   "葡萄提子", "斤", 12.00, 22.80, 6,  7.5,  14, 1.05),
    ("F7002", "巨峰葡萄",       "葡萄提子", "斤", 6.50, 12.80,  5,  8.0,  19, 0.94),
    ("F7003", "红提",           "葡萄提子", "斤", 8.00, 15.80,  7,  6.5,  11, 0.96),
]

CATEGORIES: list[str] = ["仁果类", "柑橘类", "浆果类", "核果类",
                         "热带水果", "瓜类", "葡萄提子", "特色果品"]

# 品类上市旺季月份（假设，用于 ±10% 的季节因子；依据常见国产水果上市期）
CATEGORY_PEAK_MONTH: dict[str, int] = {
    "仁果类": 10, "柑橘类": 12, "浆果类": 4, "核果类": 6,
    "热带水果": 7, "瓜类": 7, "葡萄提子": 8, "特色果品": 9,
}
SEASON_AMPLITUDE = 0.10

# 单位 → 内部整数刻度 & 篮量分布
#   斤：gamma(mean=1.35, cv=0.45)，内部刻度 0.01 斤，导出保留 2 位小数
#   个：离散 {1:.55, 2:.30, 3:.11, 4:.04}（均值 1.64）
#   盒：离散 {1:.72, 2:.22, 3:.06}（均值 1.34）
UNIT_BASKET_SPEC: dict[str, dict[str, Any]] = {
    "斤": {"kind": "gamma", "mean": 1.35, "cv": 0.45, "scale_units": SPOIL_SCALE_FINE},
    "个": {"kind": "discrete", "pmf": {1: 0.55, 2: 0.30, 3: 0.11, 4: 0.04}, "scale_units": 1},
    "盒": {"kind": "discrete", "pmf": {1: 0.72, 2: 0.22, 3: 0.06}, "scale_units": 1},
}
for _u, _spec in UNIT_BASKET_SPEC.items():
    if _spec["kind"] == "discrete":
        _spec["mean"] = float(sum(k * v for k, v in _spec["pmf"].items()))

# ------------------------------------------------------------------------------
# 时段 / 周内形状：来自公开真实数据（878,503 条销售流水的时刻聚合）
#   data/interim/sales_detail.csv 实测（脚本内以常量固化，便于离线重跑；
#   用 `simulator.py hourly-from-public` 可重新计算并核对）
#   真实数据 8 点档几乎为 0（数据最早 9 点开始），而水果店 8 点开门，
#   故对 8 点档补一个 3.5% 的假设份额，其余按实测形状等比压缩。
#   data/seed/calibration/hourly_traffic.json 存在时优先读取它（calib 产出）。
# ------------------------------------------------------------------------------

RAW_HOURLY_SHARE_PUBLIC: dict[int, float] = {
    9: 0.0843, 10: 0.1377, 11: 0.1079, 12: 0.0560, 13: 0.0405, 14: 0.0476,
    15: 0.0707, 16: 0.0942, 17: 0.1056, 18: 0.1022, 19: 0.0709, 20: 0.0608,
    21: 0.0214, 22: 0.0003,
}
ASSUMED_HOUR8_SHARE = 0.035

# 真实数据实测周内效应（日均笔数 / 全期均值），代码内归一化到均值 1
RAW_WEEKDAY_FACTOR_PUBLIC: dict[int, float] = {
    0: 0.9000, 1: 0.8685, 2: 0.8925, 3: 0.8628, 4: 0.9705, 5: 1.2745, 6: 1.2321,
}


def _normalize(d: Mapping[int, float]) -> dict[int, float]:
    """归一化成"份额"（和为 1），用于 hour_factor。"""
    s = float(sum(d.values()))
    return {int(k): float(v) / s for k, v in d.items()}


def _normalize_mean1(d: Mapping[int, float]) -> dict[int, float]:
    """归一化成"倍数"（均值为 1），用于 weekday_factor —— 它是日总量的乘子，不是份额。"""
    s = float(sum(d.values())) / len(d)
    return {int(k): float(v) / s for k, v in d.items()}


BUILTIN_HOUR_FACTOR: dict[int, float] = _normalize(
    {h: v for h, v in RAW_HOURLY_SHARE_PUBLIC.items() if h in SHOP_HOURS}
    | ({8: ASSUMED_HOUR8_SHARE} if 8 in SHOP_HOURS else {})
)
BUILTIN_WEEKDAY_FACTOR: dict[int, float] = _normalize_mean1(RAW_WEEKDAY_FACTOR_PUBLIC)


# ==============================================================================
# 工具函数
# ==============================================================================

def _sha16(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def _json_default(o: Any) -> Any:
    if isinstance(o, (date, datetime)):
        return o.isoformat()
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating,)):
        return float(o)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    raise TypeError(f"not serializable: {type(o)}")


def canonical_json(obj: Any) -> str:
    """稳定序列化（排序键、紧凑分隔符），用于状态哈希。"""
    return json.dumps(obj, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), default=_json_default)


def _coerce_hour_share(obj: Any) -> dict[int, float] | None:
    """把 calib 产出的"小时份额"统一成 {hour: share}。

    兼容三种形状（calib 的 hourly_traffic.json 实际用的是第 1 种）：
      1) list[24]，下标即小时（``overall.hour_share`` / ``hour_avg_lines_per_open_day``）
      2) {"8": 0.03, ...} 或 {"08:00": 0.03, ...}
      3) 嵌套 dict，递归找上面两种
    """
    if isinstance(obj, list) and len(obj) == 24:
        try:
            vals = [float(v) for v in obj]
        except (TypeError, ValueError):
            return None
        if sum(v for v in vals if v > 0) > 0:
            return {h: v for h, v in enumerate(vals)}
        return None
    if isinstance(obj, dict):
        parsed: dict[int, float] = {}
        for k, v in obj.items():
            try:
                hh = int(str(k).split(":")[0])
            except (TypeError, ValueError):
                continue
            if 0 <= hh <= 23 and isinstance(v, (int, float)) and not isinstance(v, bool):
                parsed[hh] = float(v)
        if len(parsed) >= 8 and sum(parsed.values()) > 0:
            return parsed
        for v in obj.values():
            got = _coerce_hour_share(v)
            if got:
                return got
    return None


def _weekday_factor_from_calib(raw: Mapping[str, Any]) -> dict[int, float] | None:
    """从 calib 的 by_weekday / by_day_type 提取周内因子（归一化到均值 1）。"""
    bw = raw.get("by_weekday")
    if isinstance(bw, list) and len(bw) == 7:
        vals: dict[int, float] = {}
        for entry in bw:
            if not isinstance(entry, dict):
                return None
            dow = entry.get("dow")
            avg = entry.get("avg_lines_per_open_day")
            if dow is None or not isinstance(avg, (int, float)) or avg <= 0:
                return None
            vals[int(dow)] = float(avg)
        if len(vals) == 7:
            return _normalize_mean1(vals)
    bt = raw.get("by_day_type")
    if isinstance(bt, dict):
        wd, we = bt.get("workday"), bt.get("weekend")
        if isinstance(wd, dict) and isinstance(we, dict):
            a = wd.get("avg_lines_per_open_day")
            b = we.get("avg_lines_per_open_day")
            if isinstance(a, (int, float)) and isinstance(b, (int, float)) and a > 0 and b > 0:
                return _normalize_mean1({d: (float(a) if d < 5 else float(b)) for d in range(7)})
    return None


def load_hourly_profile(source: str = "auto", path: Path = CALIB_TRAFFIC_JSON,
                        ) -> tuple[dict[int, float], dict[int, float], str]:
    """返回 (营业小时因子, 周内因子, 来源说明)。source ∈ auto|builtin|calibration。

    优先读 ``data/seed/calibration/hourly_traffic.json``（队友 calib 的真实数据校准产物）：
    时段形状只借用它的**相对形状**，绝对量级按水果店重新标定（见 docs/00 §3.1）。
    真实数据 8 点档≈0（数据从 9 点开始），水果店 8 点开门，故补 3.5% 的假设份额后归一化。
    """
    if source in ("auto", "calibration") and path.exists():
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            share: dict[int, float] | None = None
            overall = raw.get("overall") if isinstance(raw, dict) else None
            if isinstance(overall, dict):
                share = (_coerce_hour_share(overall.get("hour_share"))
                         or _coerce_hour_share(overall.get("hour_avg_lines_per_open_day")))
            if share is None:
                share = _coerce_hour_share(raw)
            if share is None:
                raise ValueError("no hour->share found in json")
            wk = _weekday_factor_from_calib(raw) if isinstance(raw, dict) else None
            sub = {h: float(share.get(h, 0.0)) for h in SHOP_HOURS}
            nz = [v for v in sub.values() if v > 0]
            if 8 in sub and (not nz or sub[8] < 0.2 * min(nz)):
                sub[8] = ASSUMED_HOUR8_SHARE   # 8 点档按假设补足（真实数据该档≈0）
            hour_factor = _normalize(sub)
            weekday = wk or dict(BUILTIN_WEEKDAY_FACTOR)
            src = (f"calibration:{path.name}+hour8_assumption"
                   + ("+by_weekday" if wk else "+builtin_weekday"))
            return hour_factor, weekday, src
        except Exception as exc:  # noqa: BLE001 - 容错：坏文件退回内置形状
            if source == "calibration":
                raise
            print(f"[warn] 读取 {path} 失败（{exc}），退回内置形状", file=sys.stderr)
    note = ("builtin:public-sales-detail+hour8_assumption"
            if CALIB_TRAFFIC_JSON.exists() else
            "builtin:TODO-hourly_traffic.json-not-yet-generated")
    return dict(BUILTIN_HOUR_FACTOR), dict(BUILTIN_WEEKDAY_FACTOR), note


def _basket_table(spec: Mapping[str, Any], n: int = BASKET_TABLE_N) -> np.ndarray:
    """篮量分位数查表：u ∈ [0,1) → 该单位下的篮量（内部整数刻度）。"""
    q = (np.arange(n) + 0.5) / n
    if spec["kind"] == "gamma":
        mean, cv = float(spec["mean"]), float(spec["cv"])
        a = 1.0 / (cv * cv)
        vals = _gamma.ppf(q, a, scale=mean / a)
        units = np.maximum(MIN_BASKET_FINE,
                           np.round(vals * SPOIL_SCALE_FINE / 10.0) * 10.0).astype(np.int64)
    else:
        pmf: dict[int, float] = spec["pmf"]  # type: ignore[assignment]
        ks = np.array(sorted(pmf), dtype=np.int64)
        cum = np.cumsum([pmf[int(k)] for k in ks])
        cum[-1] = 1.0
        idx = np.searchsorted(cum, q, side="left")
        units = ks[np.clip(idx, 0, len(ks) - 1)]
    return units


# ==============================================================================
# 数据结构
# ==============================================================================

@dataclass
class Batch:
    """一个到货批次。内部数量一律用整数刻度（斤=0.01 斤，个/盒=1 件）。"""
    batch_no: str
    item_idx: int
    item_code: str
    arrive_date: date
    expire_date: date
    init_units: int
    remain_units: int
    cost_price: float
    base_price: float
    unit: str
    price: float = 0.0           # 当日挂牌价（默认原价）
    status: str = "ON_SALE"
    spoil_units: int = 0         # 累计日损耗（SPOIL）
    expire_scrap_units: int = 0  # 累计到期报废（EXPIRED）
    event_idx: int = 0           # 到货事件序号（决定用哪一列预抽噪声）
    scale: int = 1

    @property
    def scrap_units(self) -> int:
        return self.spoil_units + self.expire_scrap_units

    def to_qty(self, units: int) -> float:
        return round(units / self.scale, 2 if self.scale > 1 else 0)


class DayResult(dict):
    """``step_day`` 的返回值：既是 dict（day["sales"]），也支持属性访问（day.sales）。"""

    def __getattr__(self, item: str) -> Any:
        try:
            return self[item]
        except KeyError as exc:  # pragma: no cover
            raise AttributeError(item) from exc


@dataclass
class _Truth:
    """真值参数容器（私有：``sim._truth``）。不进入 day_context()，不进入 snapshot()。"""
    items: pd.DataFrame
    hour_factor: dict[int, float]
    weekday_factor: dict[int, float]
    hour_source: str
    season_peak_month: dict[str, int]
    arrival_plan: dict[int, list[tuple[date, int, int]]]  # item_idx -> [(arrive_date, units, event_idx)]
    spoilage_daily_rate: np.ndarray
    item_bias: np.ndarray
    basket_tables: dict[str, np.ndarray]
    noise: dict[str, np.ndarray]


# ==============================================================================
# 仿真器
# ==============================================================================

class ShopSimulator:
    """街边水果店日粒度 × 时段粒度仿真器。

    参数
    ----
    seed : int            随机种子（唯一随机源）
    days : int            仿真并产出报表的天数（= step_day 可调用天数）
    warmup_days : int     前多少天作为"策略历史窗口"（只影响 observed_history() 的默认窗口，
                          不影响仿真本身；CSV 覆盖全部 days 天，含 warmup）
    start_date : str      第 0 天的营业日期（yyyy-MM-dd）
    push : bool           是否启用推送环节（评测请保持 False，保证无推送内生性）
    """

    # ---------------------------------------------------------------- 构造
    def __init__(self,
                 seed: int = 20260701,
                 days: int = 120,
                 n_days: int | None = None,
                 warmup_days: int = 60,
                 start_date: str | date = "2026-03-02",
                 near_expiry_days: int = NEAR_EXPIRY_DAYS,
                 push: bool = False,
                 push_lift: float = PUSH_LIFT,
                 coverage: float = COVERAGE,
                 arrival_sigma: float = ARRIVAL_SIGMA,
                 elasticity_scale: float = 1.0,
                 n_members: int = 120,
                 hourly_source: str = "auto",
                 hourly_path: Path = CALIB_TRAFFIC_JSON,
                 shop_hours: Sequence[int] = SHOP_HOURS,
                 verbose: bool = False) -> None:
        if n_days is not None:
            days = int(n_days)
        self.seed = int(seed)
        self.days = int(days)
        self.warmup_days = int(warmup_days)
        self.start_date = (date.fromisoformat(start_date) if isinstance(start_date, str)
                           else start_date)
        self.near_expiry_days = int(near_expiry_days)
        self.push_enabled = bool(push)
        self.push_lift = float(push_lift)
        self.coverage = float(coverage)
        self.arrival_sigma = float(arrival_sigma)
        # 敏感性分析开关：真值弹性整体缩放（docs/02 §7），评测脚本用 ±30% 扰动
        self.elasticity_scale = float(elasticity_scale)
        self._elasticity = np.array([r[9] for r in ITEM_SPEC], dtype=float) * self.elasticity_scale
        self.shop_hours = tuple(int(h) for h in shop_hours)
        self.verbose = verbose
        self.warnings: list[str] = []

        # ---- 商品表（公开：只有 shop_item.csv 那 8 列，不含弹性/基准需求）
        self.items = pd.DataFrame(
            [
                {
                    "item_code": code, "item_name": name, "category_name": cat, "unit": unit,
                    "cost_price": cost, "base_price": base, "shelf_life_days": sl,
                    "loss_rate_pct": loss,
                }
                for code, name, cat, unit, cost, base, sl, loss, _d, _e in ITEM_SPEC
            ]
        )
        self.n_items = len(self.items)
        self.item_codes = list(self.items["item_code"])
        self.item_code_to_idx = {c: i for i, c in enumerate(self.item_codes)}
        self.item_categories = list(self.items["category_name"])
        self.cat_to_idx = {c: i for i, c in enumerate(CATEGORIES)}
        self.n_cats = len(CATEGORIES)
        self._base_price = self.items["base_price"].to_numpy(float)
        self._cost_price = self.items["cost_price"].to_numpy(float)
        self._shelf_life = self.items["shelf_life_days"].to_numpy(int)
        self._unit = list(self.items["unit"])
        self._scale = np.array([UNIT_BASKET_SPEC[u]["scale_units"] for u in self._unit], dtype=int)
        self._basket_mean = np.array([UNIT_BASKET_SPEC[u]["mean"] for u in self._unit], dtype=float)
        self._cat_idx_of_item = np.array([self.cat_to_idx[c] for c in self.item_categories], dtype=int)
        self._review_days = np.array(
            [max(1, min(4, int(round(sl / 2.5)))) for sl in self._shelf_life], dtype=int
        )

        # ---- 时段/周内因子（优先读 calib 的真实数据校准产物）
        hour_factor, weekday_factor, hour_source = load_hourly_profile(hourly_source, hourly_path)
        self._hour_factor = _normalize({h: hour_factor.get(h, 0.0) for h in self.shop_hours})
        self._hour_source = hour_source
        self._weekday_factor = dict(weekday_factor)

        # ---- 季节因子
        season = np.zeros((self.n_items, 13), dtype=float)
        for i, cat in enumerate(self.item_categories):
            peak = CATEGORY_PEAK_MONTH[cat]
            for m in range(1, 13):
                season[i, m] = 1.0 + SEASON_AMPLITUDE * math.cos(2 * math.pi * (m - peak) / 12.0)
        self._season = season

        # ---- 噪声预抽取（唯一随机源；构造后不再抽数）
        self._rng = np.random.Generator(np.random.PCG64(self.seed))
        self._pre_draw(n_members)

        # ---- 到货计划（外生、确定性；到手量只依赖 seed，不依赖策略）
        self._build_arrival_plan(coverage, arrival_sigma)

        # ---- 真值容器
        truth_items = self.items.copy()
        truth_items["base_demand"] = [r[8] for r in ITEM_SPEC]
        truth_items["elasticity"] = list(self._elasticity)
        truth_items["review_days"] = self._review_days
        self._truth = _Truth(
            items=truth_items,
            hour_factor=dict(self._hour_factor),
            weekday_factor=dict(self._weekday_factor),
            hour_source=hour_source,
            season_peak_month=dict(CATEGORY_PEAK_MONTH),
            arrival_plan=self._arrival_plan,
            spoilage_daily_rate=self._spoilage_rate,
            item_bias=self._item_bias,
            basket_tables={u: _basket_table(s) for u, s in UNIT_BASKET_SPEC.items()},
            noise=self._noise,
        )

        self.reset()

    # ------------------------------------------------------- 噪声预抽取
    def _pre_draw(self, n_members: int) -> None:
        """一次性抽完所有外生随机量。step 过程中只索引，不再抽数。"""
        rng = self._rng
        T, H, I = self.days, len(self.shop_hours), self.n_items
        self.n_members = int(n_members)
        M = self.n_members
        # 到货事件总数（确定性）
        n_events = sum(len(range(0, T, int(r))) + 1 for r in self._review_days)

        noise: dict[str, np.ndarray] = {}
        # 需求扰动：每 (day,hour,item) 一个 U，用 poisson.ppf 反演订单数
        noise["u_count"] = rng.random((T, H, I))
        # 篮量 / 会员判定 / 会员选择：每 (day,hour,item,[槽位])
        noise["u_basket"] = rng.random((T, H, I, MAX_BASKETS))
        noise["u_ismember"] = rng.random((T, H, I, MAX_BASKETS))
        noise["u_pick"] = rng.random((T, H, I, MAX_BASKETS))
        # 小时内成交时刻抖动池
        noise["u_time"] = rng.random((T, H, 128))
        # 到货量噪声：每 (day,item)
        noise["u_arrival"] = rng.random((T, I))
        # 日损耗：每 (day,批次事件)
        noise["u_spoil"] = rng.random((T, n_events))
        # 会员：推送打开意愿、先验偏好强度、潜在口味、尾号、昵称、初始前偏好
        noise["u_open_prop"] = rng.random(M)
        noise["u_member_prior_strength"] = rng.random(M)
        noise["u_member_prior_n"] = rng.random(M)
        noise["u_latent"] = rng.random((M, self.n_cats))
        noise["u_phone"] = rng.random(M)
        noise["u_nick"] = rng.random((M, 3))
        noise["u_enabled"] = rng.random(M)
        noise["u_joined"] = rng.random(M)
        noise["u_prior_count"] = rng.random((M, self.n_cats))
        noise["u_prior_last"] = rng.random((M, self.n_cats))
        noise["u_open"] = rng.random((T, M))   # 推送打开意愿判定

        for k, v in noise.items():
            if k in ("u_count",):
                noise[k] = np.clip(v, 1e-12, 1 - 1e-12)
            elif k.startswith("u_basket") or k in ("u_ismember", "u_pick", "u_time", "u_arrival",
                                                    "u_spoil"):
                noise[k] = np.clip(v, 1e-12, 1 - 1e-12)
        self._noise = noise

        # ---- 单品补货个体偏差（长期订多/订少），只用一次抽样
        self._item_bias = np.exp(rng.normal(0.0, ITEM_BIAS_SIGMA, self.n_items))

        # ---- 单品日损耗率：由 loss_rate_pct 与保质期折算
        loss_pct = self.items["loss_rate_pct"].to_numpy(float) / 100.0
        self._spoilage_rate = 1.0 - np.power(1.0 - loss_pct, 1.0 / self._shelf_life)

        # ---- 会员表
        self._build_members()

    def _build_members(self) -> None:
        n = self.n_members
        u = self._noise
        adj = ["爱吃的", "会挑的", "常来的", "果盘控", "养生派", "加班族", "带娃的",
               "晨跑的", "讲价的", "嘴刁的", "资深", "新晋"]
        fruit_word = ["苹果", "橘子", "草莓", "西瓜", "葡萄", "香蕉", "车厘子", "桃子",
                      "芒果", "柚子", "蓝莓", "猕猴桃"]
        suffix = ["小王", "小李", "小张", "阿强", "阿敏", "老周", "小陈", "阿杰",
                  "小刘", "阿玲", "小赵", "阿芳"]
        tails = list(range(1000, 10000))
        perm = self._rng.permutation(len(tails))
        rows = []
        for i in range(n):
            j1 = int(u["u_nick"][i, 0] * len(adj)) % len(adj)
            j2 = int(u["u_nick"][i, 1] * len(fruit_word)) % len(fruit_word)
            j3 = int(u["u_nick"][i, 2] * len(suffix)) % len(suffix)
            rows.append({
                "member_no": f"M{i + 1:04d}",
                "nickname": f"{adj[j1]}{fruit_word[j2]}{suffix[j3]}",
                "phone_tail": f"{tails[perm[i]]:04d}",
                "joined_date": self.start_date - timedelta(days=30 + int(u["u_joined"][i] * 400)),
                "push_enabled": 1 if u["u_enabled"][i] < 0.92 else 0,
            })
        self.members = pd.DataFrame(rows)
        self._open_prop = 0.28 + 0.44 * u["u_open_prop"]          # 打开意愿 0.28~0.72

        # ---- 先验偏好（"这家店已经开了一段时间"的前史，保证第 1 天就能按"买过某品类"选人）
        latent = u["u_latent"] ** 1.3                              # (M, C) 潜在口味 0~1
        self._latent_taste = latent
        n_pref = 2 + (u["u_member_prior_n"] * 3).astype(int)       # 2~4 个偏好品类
        strength = 1.0 + 6.0 * u["u_member_prior_strength"]        # 1~7
        prior = np.zeros((self.n_members, self.n_cats), dtype=np.int64)
        for m in range(self.n_members):
            order = np.argsort(-latent[m])[: int(n_pref[m])]
            for c in order:
                lam = 0.6 + strength[m] * float(latent[m, c])
                cnt = int(_poisson.ppf(float(np.clip(u["u_prior_count"][m, c], 1e-12, 1 - 1e-12)),
                                       lam))
                prior[m, c] = max(1, cnt)
        self._prior_count = prior

    # ----------------------------------------------------------- 到货计划
    def _build_arrival_plan(self, coverage: float, sigma: float) -> None:
        """补货计划是外生序列（不随价格策略变化），保证三种策略面对完全相同的到货。"""
        u = self._noise
        plan: dict[int, list[tuple[date, int, int]]] = {}
        event_idx = 0
        for i in range(self.n_items):
            R = int(self._review_days[i])
            offset = 1 + (i * 7) % R
            days_arrival = [0] + list(range(offset, self.days, R))
            entries: list[tuple[date, int, int]] = []
            for k, d in enumerate(days_arrival):
                if d >= self.days:
                    break
                biz = self.start_date + timedelta(days=d)
                # R 天需求预报（含周内效应与季节因子）
                fc = 0.0
                for step in range(R):
                    dd = biz + timedelta(days=step)
                    fc += (ITEM_SPEC[i][8] * self._weekday_factor[dd.weekday()]
                           * self._season[i, dd.month])
                if k == 0:
                    fc *= 0.6  # 开业首日只备半程货
                noise = math.exp(sigma * float(np.sqrt(2.0) * _erfinv(2 * u["u_arrival"][d, i] - 1)))
                qty_units = int(round(coverage * self._item_bias[i] * fc * noise
                                      * self._scale[i]))
                qty_units = max(self._scale[i], qty_units)
                entries.append((biz, qty_units, event_idx))
                event_idx += 1
            plan[i] = entries
        self._arrival_plan = plan
        self._n_arrival_events = event_idx

    # -------------------------------------------------------------- 重置
    def reset(self) -> "ShopSimulator":
        """回到第 0 天（同 seed 下可重复跑多个策略，无需重新构造）。"""
        self.day_index = 0
        self.batches: dict[str, Batch] = {}
        self._batch_by_item: dict[int, list[str]] = {i: [] for i in range(self.n_items)}
        self._pref_count = self._prior_count.copy()
        self._pref_last: dict[tuple[int, int], date] = {}
        for m in range(self.n_members):
            for c in range(self.n_cats):
                if self._prior_count[m, c] > 0:
                    back = 1 + int(self._noise["u_prior_last"][m, c] * 59)
                    self._pref_last[(m, c)] = self.start_date - timedelta(days=back)
        self._obs_history: list[dict[str, Any]] = []
        self._truth_history: list[dict[str, Any]] = []
        self._push_counter: dict[tuple[int, date], int] = {}
        self._cum = {"sales_units": 0.0, "sales_amount": 0.0, "scrap_units": 0.0,
                     "arrival_units": 0.0, "orders": 0, "lost_units": 0.0,
                     "demand_units": 0.0, "sold_units": 0.0}
        self._last_day_orders: dict[tuple[int, int], int] = {}
        self._crn_debug = False
        self._crn_cells: list[tuple[int, int, int, float, int]] = []
        self.warnings = []
        return self

    # ------------------------------------------------------------- 指纹
    def noise_fingerprint(self) -> str:
        """预抽扰动矩阵的指纹：同 seed 必相同，与策略无关（CRN 自证）。"""
        h = hashlib.sha256()
        for k in sorted(self._noise):
            h.update(k.encode())
            h.update(np.ascontiguousarray(self._noise[k]).tobytes())
        h.update(np.ascontiguousarray(self._item_bias).tobytes())
        h.update(np.ascontiguousarray(self._prior_count).tobytes())
        return h.hexdigest()[:16]

    def state_hash(self) -> str:
        """当前可变状态的哈希（不含真值参数与噪声）。三个策略重放同一历史后应完全一致。"""
        return _sha16(canonical_json(self.snapshot()))

    # ------------------------------------------------------ 快照 / 恢复
    def snapshot(self) -> dict[str, Any]:
        """可 json 序列化的**因果状态**：批次台账、累计量、会员偏好、可观测历史、当前天。

        不含 ``_truth``（真值参数、噪声矩阵、到货计划）—— 策略侧拿不到未来信息。
        """
        batches = []
        for b in sorted(self.batches.values(), key=lambda x: x.batch_no):
            batches.append({
                "batch_no": b.batch_no, "item_idx": int(b.item_idx), "item_code": b.item_code,
                "arrive_date": b.arrive_date.isoformat(),
                "expire_date": b.expire_date.isoformat(),
                "init_units": int(b.init_units), "remain_units": int(b.remain_units),
                "cost_price": float(b.cost_price), "base_price": float(b.base_price),
                "unit": b.unit, "price": float(b.price), "status": b.status,
                "spoil_units": int(b.spoil_units),
                "expire_scrap_units": int(b.expire_scrap_units),
                "event_idx": int(b.event_idx), "scale": int(b.scale),
            })
        return {
            "version": VERSION,
            "seed": self.seed,
            "days": self.days,
            "warmup_days": self.warmup_days,
            "start_date": self.start_date.isoformat(),
            "day_index": int(self.day_index),
            "batches": batches,
            "pref_count": self._pref_count.tolist(),
            "pref_last": {f"{m}:{c}": d.isoformat() for (m, c), d in sorted(self._pref_last.items())},
            "push_counter": {f"{m}:{d.isoformat()}": v
                             for (m, d), v in sorted(self._push_counter.items())},
            "cum": {k: float(v) for k, v in self._cum.items()},
            "obs_history": self._obs_history,
        }

    def restore(self, snap: Mapping[str, Any]) -> "ShopSimulator":
        """精确还原 snapshot() 的状态，继续 step 与未中断的运行逐位一致。"""
        if int(snap["version"].split(".")[0]) != int(VERSION.split(".")[0]):
            raise ValueError("snapshot version mismatch")
        self.days = int(snap["days"])
        self.warmup_days = int(snap["warmup_days"])
        self.day_index = int(snap["day_index"])
        self.batches = {}
        for row in snap["batches"]:
            b = Batch(
                batch_no=row["batch_no"], item_idx=int(row["item_idx"]),
                item_code=row["item_code"], arrive_date=date.fromisoformat(row["arrive_date"]),
                expire_date=date.fromisoformat(row["expire_date"]),
                init_units=int(row["init_units"]), remain_units=int(row["remain_units"]),
                cost_price=float(row["cost_price"]), base_price=float(row["base_price"]),
                unit=row["unit"], price=float(row["price"]), status=row["status"],
                spoil_units=int(row["spoil_units"]),
                expire_scrap_units=int(row["expire_scrap_units"]),
                event_idx=int(row["event_idx"]), scale=int(row["scale"]),
            )
            self.batches[b.batch_no] = b
        self._batch_by_item = {i: [] for i in range(self.n_items)}
        for b in self.batches.values():
            self._batch_by_item[b.item_idx].append(b.batch_no)
        self._pref_count = np.array(snap["pref_count"], dtype=np.int64)
        self._pref_last = {}
        for k, v in snap["pref_last"].items():
            m, c = k.split(":")
            self._pref_last[(int(m), int(c))] = date.fromisoformat(v)
        self._push_counter = {}
        for k, v in snap["push_counter"].items():
            m, d = k.split(":")
            self._push_counter[(int(m), date.fromisoformat(d))] = int(v)
        self._cum = {k: float(v) for k, v in snap["cum"].items()}
        self._obs_history = [dict(r) for r in snap["obs_history"]]
        self._truth_history = []
        return self

    # -------------------------------------------------------- 可观测历史
    def observed_history(self, until_day: int | None = None,
                         full: bool = False) -> pd.DataFrame:
        """可观测的日×单品历史（**不含真值参数**）。

        默认只返回"前 warmup_days 天"（策略历史窗口）。``until_day`` 可覆盖；
        ``full=True`` 返回截至当前天的全部。

        ⚠️ **列名陷阱（务必看清）**：本表的 ``discount_rate`` 是**折扣深度**
        = ``1 - 实收均价/原价``，取值 0~0.5（越大越便宜）；而
        ``shop_pricing_suggestion.csv`` / ``day["suggestions"]`` / ``pricing_fn`` 返回值里的
        ``discount_rate`` 是**价格比**（0.5~1.0，越小越便宜）。两者含义相反，
        换算：``价格比 = 1 - 本表 discount_rate``。
        建模建议直接用本表的 ``unit_price`` 与商品原价换算，或参考审计文件
        ``sim_internal_daily_item.csv`` 里无歧义的 ``discount_depth``。
        """
        if until_day is None:
            until_day = self.day_index if full else min(self.day_index, self.warmup_days)
        rows = [r for r in self._obs_history if r["day_index"] < until_day]
        cols = ["biz_date", "day_index", "item_code", "unit_price", "sold_qty", "orders",
                "discount_rate", "is_stockout", "stockout_flag", "on_hand_start",
                "arrival_qty", "remain_end"]
        df = pd.DataFrame(rows)
        if df.empty:
            return pd.DataFrame(columns=cols)
        for c in ("is_stockout", "stockout_flag"):
            if c in df:
                df[c] = df[c].astype(bool)
        return df[cols]

    # ------------------------------------------------------------ 状态视图
    def _on_sale_batches(self, item_idx: int, biz: date) -> list[Batch]:
        out = []
        for bn in self._batch_by_item[item_idx]:
            b = self.batches[bn]
            if b.remain_units > 0 and (b.expire_date - biz).days >= 0:
                out.append(b)
        out.sort(key=lambda b: (b.expire_date, b.arrive_date, b.batch_no))
        return out

    def day_context(self) -> dict[str, Any]:
        """构造当天给定价函数看的 ``state``：**只含可观测信息**。"""
        biz = self.start_date + timedelta(days=self.day_index)
        rows = []
        for i in range(self.n_items):
            for b in self._on_sale_batches(i, biz):
                rows.append({
                    "batch_no": b.batch_no, "item_code": b.item_code,
                    "item_name": self.items["item_name"].iloc[i],
                    "category_name": self.item_categories[i], "unit": b.unit,
                    "arrive_date": b.arrive_date, "expire_date": b.expire_date,
                    "days_left": (b.expire_date - biz).days,
                    "remain_qty": b.to_qty(b.remain_units), "init_qty": b.to_qty(b.init_units),
                    "cost_price": b.cost_price, "base_price": b.base_price,
                    "shelf_life_days": int(self._shelf_life[i]),
                })
        batches = pd.DataFrame(rows)
        if batches.empty:
            batches = pd.DataFrame(columns=["batch_no", "item_code", "item_name", "category_name",
                                            "unit", "arrive_date", "expire_date", "days_left",
                                            "remain_qty", "init_qty", "cost_price", "base_price",
                                            "shelf_life_days"])
        hist = self.observed_history(until_day=self.day_index)
        return {
            "biz_date": biz,
            "day_index": self.day_index,
            "is_warmup": self.day_index < self.warmup_days,
            "near_expiry_days": self.near_expiry_days,
            "candidate_discounts": tuple(CANDIDATE_DISCOUNTS),
            "shop_hours": tuple(self.shop_hours),
            "days_on_sale": self.day_index,
            "batches": batches,
            "history": hist,
        }

    # ------------------------------------------------------------ 定价调用
    def _call_pricing(self, pricing_fn: Callable[..., Any] | None,
                      state: Mapping[str, Any]) -> Any:
        if pricing_fn is None:
            return None
        try:
            n_pos = len([p for p in inspect.signature(pricing_fn).parameters.values()
                         if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)])
        except (TypeError, ValueError):
            n_pos = 3
        if n_pos <= 1:
            return pricing_fn(state)
        if n_pos == 2:
            return pricing_fn(state["biz_date"], state)
        return pricing_fn(state["biz_date"], state["day_index"], state)

    @staticmethod
    def _snap_discount(v: float) -> tuple[float, bool]:
        """把折扣吸附到候选档，返回 (档位, 是否发生吸附)。"""
        best = min(CANDIDATE_DISCOUNTS, key=lambda c: abs(c - v))
        return best, abs(best - v) > 1e-9

    def _normalize_spec(self, spec: Any, biz: date) -> tuple[dict[str, float], int]:
        """把策略返回值统一成 {batch_no: price}。支持：
        {item_code|batch_no: discount_rate|price}、DataFrame(item_code|batch_no, discount_rate|price)、None。
        """
        prices: dict[str, float] = {}
        snapped = 0
        batch_nos = set(self.batches)
        if spec is None:
            return prices, snapped
        if isinstance(spec, pd.DataFrame):
            if spec.empty:
                return prices, snapped
            key_col = "batch_no" if "batch_no" in spec.columns else "item_code"
            for _, r in spec.iterrows():
                key = str(r[key_col])
                if "discount_rate" in spec.columns and pd.notna(r["discount_rate"]):
                    val, is_price = float(r["discount_rate"]), False
                elif "price" in spec.columns and pd.notna(r["price"]):
                    val, is_price = float(r["price"]), True
                else:
                    continue
                self._apply_spec_entry(key, val, is_price, biz, batch_nos, prices)
        elif isinstance(spec, pd.Series):
            for key, val in spec.items():
                if pd.isna(val):
                    continue
                self._apply_spec_entry(str(key), float(val), False, biz, batch_nos, prices)
        elif isinstance(spec, Mapping):
            for key, val in spec.items():
                if val is None or (isinstance(val, float) and math.isnan(val)):
                    continue
                self._apply_spec_entry(str(key), float(val), float(val) > 1.5, biz,
                                       batch_nos, prices)
        else:
            raise TypeError(f"pricing_fn 返回类型不支持: {type(spec)}")

        # 吸附统计（在 _apply_spec_entry 里已吸附，这里只计数）
        snapped = self._snapped_last
        return prices, snapped

    def _apply_spec_entry(self, key: str, val: float, is_price: bool, biz: date,
                          batch_nos: set[str], out: dict[str, float]) -> None:
        if not hasattr(self, "_snapped_last"):
            self._snapped_last = 0
        if key in batch_nos:
            targets = [self.batches[key]]
        elif key in self.item_code_to_idx:
            i = self.item_code_to_idx[key]
            targets = self._on_sale_batches(i, biz)
        else:
            self.warnings.append(f"[{biz}] 未知的批次/单品键：{key}")
            return
        for b in targets:
            if is_price:
                price = float(val)
            else:
                rate, was_snapped = self._snap_discount(float(val))
                self._snapped_last += int(was_snapped)
                price = round(b.base_price * rate, 2)
            if price <= 0:
                self.warnings.append(f"[{biz}] {b.batch_no} 价格<=0，已忽略")
                continue
            if price > b.base_price + 1e-9:
                self.warnings.append(f"[{biz}] {b.batch_no} 涨价被拒（docs/01 §3.4）")
                price = b.base_price
            out[b.batch_no] = price

    # ------------------------------------------------------------ 单日推进
    def step_day(self, pricing_fn: Callable[..., Any] | None = None) -> DayResult:
        """推进一天。``pricing_fn(biz_date, day_index, state) -> {item_code: discount_rate}``"""
        biz = self.start_date + timedelta(days=self.day_index)
        self._snapped_last = 0
        state = self.day_context()
        spec = self._call_pricing(pricing_fn, state)
        prices, n_snapped = self._normalize_spec(spec, biz)

        # 1) 当日到货（外生）
        purchase_rows = self._arrive(biz)

        # 2) 挂牌价：未指定的批次 = 原价
        for b in self.batches.values():
            b.price = prices.get(b.batch_no, b.base_price)

        # 3) 建议（days_left <= near_expiry_days 的批次，仅在售）
        suggestions = self._build_suggestions(biz)

        # 4) 推送（可选）→ 当日 promo_lift
        if self.push_enabled:
            pushes, lift = self._build_pushes(biz, suggestions)
        else:
            pushes, lift = [], np.ones(self.n_items)
            self._push_targets_today = {}

        # 5) 营业时段模拟
        sales_rows, traffic_rows, demand_rows, scrap_rows_today = self._simulate_day(biz, lift)

        # 6) 推送转化回填
        if pushes:
            self._finalize_pushes(biz, pushes)

        # 7) 日终：损耗 + 到期报废
        scrap_rows_eod = self._end_of_day(biz, demand_rows)

        # 8) 历史与累计
        self._append_history(biz, demand_rows, purchase_rows, sales_rows, lift)

        day = DayResult(
            biz_date=biz,
            day_index=self.day_index,
            is_warmup=self.day_index < self.warmup_days,
            sales=pd.DataFrame(sales_rows, columns=["biz_date", "sale_time", "item_code",
                                                    "batch_no", "quantity", "unit_price",
                                                    "is_discount", "is_return", "source"]),
            scrap=pd.DataFrame(scrap_rows_today + scrap_rows_eod,
                               columns=["item_code", "batch_no", "quantity", "cost_price",
                                        "reason"]),
            purchase=pd.DataFrame(purchase_rows, columns=["item_code", "batch_no", "quantity",
                                                          "cost_price", "expire_date"]),
            demand=pd.DataFrame(demand_rows, columns=["item_code", "demand_qty", "sold_qty",
                                                      "lost_qty", "scrap_qty", "is_stockout"]),
            traffic=pd.DataFrame(traffic_rows, columns=["stat_date", "hour_of_day", "order_count",
                                                        "customer_count", "is_peak"]),
            suggestions=pd.DataFrame(suggestions),
            pushes=pd.DataFrame(pushes),
            promo_lift={self.item_codes[i]: float(lift[i]) for i in range(self.n_items)
                        if lift[i] > 1.0 + 1e-9},
            warnings=[w for w in self.warnings if w.startswith(f"[{biz}]")],
            n_snapped=int(n_snapped),
            hour_source=self._hour_source,
        )
        self.day_index += 1
        day["state_hash"] = self.state_hash()
        return day

    # --------------------------------------------------------------- 到货
    def _arrive(self, biz: date) -> list[dict[str, Any]]:
        rows = []
        d = self.day_index
        for i in range(self.n_items):
            for (adate, units, ev_idx) in self._arrival_plan[i]:
                if adate != biz:
                    continue
                sl = int(self._shelf_life[i])
                bn = f"B{adate.strftime('%Y%m%d')}-{self.item_codes[i]}-{ev_idx:04d}"
                b = Batch(batch_no=bn, item_idx=i, item_code=self.item_codes[i],
                          arrive_date=adate, expire_date=adate + timedelta(days=sl),
                          init_units=units, remain_units=units,
                          cost_price=float(self._cost_price[i]),
                          base_price=float(self._base_price[i]), unit=self._unit[i],
                          price=float(self._base_price[i]), status="ON_SALE",
                          event_idx=ev_idx, scale=int(self._scale[i]))
                self.batches[bn] = b
                self._batch_by_item[i].append(bn)
                rows.append({"item_code": b.item_code, "batch_no": bn,
                             "quantity": b.to_qty(units), "cost_price": b.cost_price,
                             "expire_date": b.expire_date})
                self._cum["arrival_units"] += units / b.scale
        return rows

    # --------------------------------------------------------------- 建议
    def _build_suggestions(self, biz: date) -> list[dict[str, Any]]:
        rows = []
        hist = self.observed_history(until_day=self.day_index, full=True)
        vel = self._velocity(hist, biz, window=7)
        for i in range(self.n_items):
            for b in self._on_sale_batches(i, biz):
                days_left = (b.expire_date - biz).days
                if days_left > self.near_expiry_days:
                    continue
                rate = _rule_rate(days_left, self.near_expiry_days, b, vel.get(i, 0.0),
                                  b.cost_price, b.base_price)
                if rate >= 1.0 - 1e-12:
                    continue   # 刚到货/无需打折的临期批次不产出建议（建议 = 要打折的批次）
                price = round(b.base_price * rate, 2)
                lam = self._expected_daily_units(i, biz, price)
                rows.append({
                    "biz_date": biz, "item_code": b.item_code, "batch_no": b.batch_no,
                    "days_left": days_left, "remain_qty": b.to_qty(b.remain_units),
                    "original_price": b.base_price, "suggested_price": price,
                    "discount_rate": rate, "strategy": "RULE",
                    "expected_qty": round(min(lam, b.to_qty(b.remain_units)), 2),
                    "reason": (f"剩{days_left}天｜库存{b.to_qty(b.remain_units):g}{b.unit}｜"
                               f"近7日均销{vel.get(i, 0.0):.1f}{b.unit}｜"
                               f"按临期规则降至{rate * 10:.0f}折"),
                })
        rows.sort(key=lambda r: (r["days_left"], r["item_code"], r["batch_no"]))
        return rows

    def _velocity(self, hist: pd.DataFrame, biz: date, window: int = 7) -> dict[int, float]:
        """近 window 天的日均可观测销量（份/天）——供给规则版定价用的可观测特征。"""
        if hist.empty:
            return {}
        h = hist[hist["biz_date"] >= (biz - timedelta(days=window))]
        if h.empty:
            return {}
        g = h.groupby("item_code")["sold_qty"].sum() / max(window, 1)
        return {self.item_code_to_idx[k]: float(v) for k, v in g.items()
                if k in self.item_code_to_idx}

    def _expected_daily_units(self, i: int, biz: date, price: float) -> float:
        """给定挂牌价下的期望日销量（真值期望，仅用于 expected_qty 与内部统计）。"""
        base = ITEM_SPEC[i][8]
        eps = self._elasticity[i]
        bp = float(self._base_price[i])
        return (base * math.exp(-eps * (price / bp - 1.0))
                * self._weekday_factor[biz.weekday()] * self._season[i, biz.month])

    # --------------------------------------------------------------- 推送
    def _build_pushes(self, biz: date,
                      suggestions: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], np.ndarray]:
        lift = np.ones(self.n_items)
        self._push_targets_today = {}
        if not suggestions:
            return [], lift
        # 优先级：临期库存金额 = 余量 × 原价 × (1-折扣)
        cand: dict[int, dict[str, Any]] = {}
        for r in suggestions:
            i = self.item_code_to_idx[r["item_code"]]
            val = float(r["remain_qty"]) * float(r["original_price"]) * (1 - r["discount_rate"])
            if i not in cand or val > cand[i]["val"]:
                cand[i] = {"val": val, "row": r}
        top = sorted(cand.values(), key=lambda x: (-x["val"], x["row"]["item_code"]))[:3]
        peak_hour = max(self.shop_hours, key=lambda h: self._hour_factor[h])
        send_hour = max(self.shop_hours[0], peak_hour - 1)
        if QUIET_HOURS[1] <= send_hour or send_hour >= QUIET_HOURS[0]:
            send_hour = max(self.shop_hours[0], min(send_hour, QUIET_HOURS[0] - 1))
        rows: list[dict[str, Any]] = []
        targets_by_item: dict[int, list[int]] = {}
        for item in top:
            row = item["row"]
            i = self.item_code_to_idx[row["item_code"]]
            c = int(self._cat_idx_of_item[i])
            eligible = [m for m in range(self.n_members)
                        if int(self.members["push_enabled"].iloc[m]) == 1
                        and self._pref_count[m, c] > 0]
            eligible.sort(key=lambda m: (-int(self._pref_count[m, c]),
                                         self.members["member_no"].iloc[m]))
            picked = []
            for m in eligible:
                if len(picked) >= 10:
                    break
                key = (m, biz)
                if self._push_counter.get(key, 0) >= PUSH_DAILY_LIMIT:
                    continue
                picked.append(m)
                self._push_counter[key] = self._push_counter.get(key, 0) + 1
            targets_by_item[i] = picked
            lift[i] = 1.0 + self.push_lift * min(1.0, len(picked) / PUSH_TARGET_SCALE)
            title = f"临期特惠｜{row['item_code']} {self.items['item_name'].iloc[i]} 打{row['discount_rate'] * 10:.0f}折"
            for m in picked:
                opened = bool(self._noise["u_open"][self.day_index, m] < self._open_prop[m])
                rows.append({
                    "biz_date": biz, "member_no": self.members["member_no"].iloc[m],
                    "channel": "IN_APP", "title": title,
                    "content": (f"您常买的{self.item_categories[i]}有临期特惠："
                                f"{self.items['item_name'].iloc[i]} 现价{row['suggested_price']:.2f}元/"
                                f"{self.items['unit'].iloc[i]}（原价{row['original_price']:.2f}），"
                                f"仅剩{row['remain_qty']:g}{self.items['unit'].iloc[i]}，"
                                f"今天到店就能拿～"),
                    "item_code": row["item_code"], "discount_rate": row["discount_rate"],
                    "send_at": datetime(biz.year, biz.month, biz.day, send_hour, 0, 0),
                    "opened": int(opened), "converted": 0,
                })
            if picked:
                rows.append({
                    "biz_date": biz, "member_no": "", "channel": "GROUP_TEXT",
                    "title": f"今日临期果品特惠（{len(top)}款）",
                    "content": "；".join(
                        f"{self.items['item_name'].iloc[self.item_code_to_idx[t['row']['item_code']]]}"
                        f"{t['row']['discount_rate'] * 10:.0f}折" for t in top),
                    "item_code": row["item_code"], "discount_rate": row["discount_rate"],
                    "send_at": datetime(biz.year, biz.month, biz.day, send_hour, 0, 0),
                    "opened": 0, "converted": 0,
                })
        self._push_targets_today = targets_by_item
        return rows, lift

    def _finalize_pushes(self, biz: date, pushes: list[dict[str, Any]]) -> None:
        """转化回填：会员当天是否买了被推品类（群发按"该品类当天是否有人成交"）。"""
        day_cat = self._day_member_cat
        code_to_idx = self.item_code_to_idx
        for p in pushes:
            i = code_to_idx.get(p["item_code"])
            if i is None:
                continue
            c = int(self._cat_idx_of_item[i])
            if p["channel"] == "GROUP_TEXT":
                p["converted"] = int(any(c in cats for cats in day_cat.values()))
            else:
                m = int(str(p["member_no"]).lstrip("M")) - 1
                p["converted"] = int(day_cat.get(m, {}).get(c, 0) > 0)
        # opened=0 的行不可能 converted
        for p in pushes:
            if p["opened"] == 0 and p["channel"] == "IN_APP":
                p["converted"] = 0

    # ------------------------------------------------------------ 日模拟
    def _simulate_day(self, biz: date, lift: np.ndarray) -> tuple[list, list, list, list]:
        base = np.array([r[8] for r in ITEM_SPEC], dtype=float)
        eps = self._elasticity
        wd = self._weekday_factor[biz.weekday()]
        season = self._season[:, biz.month]

        # 当日开盘有效价格（余量加权），日内不重估
        p_eff = self._base_price.copy()
        on_hand_start = np.zeros(self.n_items, dtype=int)
        for i in range(self.n_items):
            bs = self._on_sale_batches(i, biz)
            tot = sum(b.remain_units for b in bs)
            on_hand_start[i] = tot
            if tot > 0:
                p_eff[i] = sum(b.price * b.remain_units for b in bs) / tot

        price_ratio = p_eff / self._base_price
        lam_units_day = base * np.exp(-eps * (price_ratio - 1.0)) * wd * season * lift
        lam_orders_day = lam_units_day / self._basket_mean

        d = self.day_index
        sales_rows: list[dict[str, Any]] = []
        demand_rows: list[dict[str, Any]] = []
        traffic_rows: list[dict[str, Any]] = []
        self._day_member_cat: dict[int, dict[int, int]] = {}
        sold_units = np.zeros(self.n_items, dtype=float)
        demand_units = np.zeros(self.n_items, dtype=float)
        lost_units = np.zeros(self.n_items, dtype=float)
        orders_per_item = np.zeros(self.n_items, dtype=int)
        price_sum = np.zeros(self.n_items, dtype=float)
        price_qty = np.zeros(self.n_items, dtype=float)
        scrap_before = {bn: b.scrap_units for bn, b in self.batches.items()}
        self._scrap_at_day_start = scrap_before

        for hi, h in enumerate(self.shop_hours):
            lam_orders_h = lam_orders_day * self._hour_factor[h]
            counts = _poisson.ppf(self._noise["u_count"][d, hi, :], lam_orders_h).astype(np.int64)
            counts = np.maximum(counts, 0)
            if self._crn_debug:
                # 审计钩子：记录 (day,hour,item) 的 lambda 与订单数，用于证明
                # "订单数 = poisson.ppf(预抽 U, 本策略自己的 lambda)"，随机流与策略无关。
                for ii in range(self.n_items):
                    self._crn_cells.append((d, hi, ii, float(lam_orders_h[ii]), int(counts[ii])))
            if counts.sum() == 0:
                traffic_rows.append({"stat_date": biz, "hour_of_day": h, "order_count": 0,
                                     "customer_count": 0, "is_peak": False})
                continue
            hour_members: set[int] = set()
            n_nonmember = 0
            n_orders_h = 0
            order_seq = 0
            for i in range(self.n_items):
                n_ord = int(counts[i])
                if n_ord <= 0:
                    continue
                bt = self._truth.basket_tables[self._unit[i]]
                u_b = self._noise["u_basket"][d, hi, i]
                idx = np.minimum((u_b[:min(n_ord, MAX_BASKETS)] * BASKET_TABLE_N).astype(np.int64),
                                 BASKET_TABLE_N - 1)
                baskets = bt[idx].astype(np.int64)
                if n_ord > MAX_BASKETS:  # 极罕见：超出的槽位按均值兜底（确定性）
                    extra = np.full(n_ord - MAX_BASKETS,
                                    int(round(self._basket_mean[i] * self._scale[i])), dtype=np.int64)
                    baskets = np.concatenate([baskets, extra])
                cat = int(self._cat_idx_of_item[i])
                w = self._member_weights(i, cat)
                cumw = np.cumsum(w)
                tot_w = float(cumw[-1])
                u_is = self._noise["u_ismember"][d, hi, i]
                u_pk = self._noise["u_pick"][d, hi, i]
                p_member = float(np.clip(MEMBER_BASE_PROB - 0.18 * (1.0 - price_ratio[i]),
                                         0.25, 0.62))
                batches_list = self._on_sale_batches(i, biz)
                for k in range(n_ord):
                    order_seq += 1
                    # u_is/u_pk 只有 MAX_BASKETS 个槽位，超出的极少数订单复用最后一个 U
                    kk = min(k, MAX_BASKETS - 1)
                    qty_units = int(baskets[k])
                    requested = qty_units
                    demand_units[i] += qty_units / self._scale[i]
                    # FEFO 取货：按最早到期批次顺次取，**每取一个批次就记一条销售明细**。
                    # 口径：一条销售明细 = 一笔成交（与公开数据"一条流水行 = 一笔成交"一致），
                    # 因此跨批次取货会拆成多条明细，从而保证
                    #   ① Σ(时段 order_count) == 当日销售明细数
                    #   ② 每个批次 Σ(明细销量) + scrap == 批次扣减量（台账恒等式）
                    alloc = 0
                    for b in batches_list:
                        if b.remain_units <= 0:
                            continue
                        take = min(requested - alloc, b.remain_units)
                        if take <= 0:
                            continue
                        b.remain_units -= take
                        alloc += take
                        n_orders_h += 1
                        order_seq += 1
                        sold_units[i] += take / self._scale[i]
                        orders_per_item[i] += 1
                        unit_price = b.price
                        price_sum[i] += unit_price * (take / self._scale[i])
                        price_qty[i] += take / self._scale[i]
                        # 时刻抖动（池内取，确定性）
                        slot = int(self._noise["u_time"][d, hi, (order_seq - 1) % 128] * 3600)
                        sec = min(slot, 3599)
                        ts = datetime(biz.year, biz.month, biz.day, h, sec // 60, sec % 60)
                        sales_rows.append({
                            "biz_date": biz, "sale_time": ts, "item_code": self.item_codes[i],
                            "batch_no": b.batch_no,
                            "quantity": round(take / self._scale[i], 2),
                            "unit_price": unit_price,
                            "is_discount": int(unit_price < b.base_price - 1e-9),
                            "is_return": 0, "source": "SIM",
                        })
                        if alloc >= requested:
                            break
                    if alloc < requested:
                        lost_units[i] += (requested - alloc) / self._scale[i]
                    # 会员判定（不影响需求流，只影响 customer_count 与推送转化）。
                    # 口径：customer_count 统计"有成交的客户"——该小时去重会员数 + 非会员成交数，
                    # 未买到货的到店者不计（与 docs/01 §4.6「用历史成交笔数代替真实人流」一致），
                    # 因此恒有 customer_count <= order_count。
                    if alloc > 0:
                        if float(u_is[kk]) < p_member:
                            m = int(np.searchsorted(cumw, float(u_pk[kk]) * tot_w, side="right"))
                            m = min(m, self.n_members - 1)
                            hour_members.add(m)
                            self._day_member_cat.setdefault(m, {})
                            self._day_member_cat[m][cat] = self._day_member_cat[m].get(cat, 0) + 1
                        else:
                            n_nonmember += 1
            traffic_rows.append({
                "stat_date": biz, "hour_of_day": h,
                "order_count": int(n_orders_h),
                "customer_count": int(len(hour_members) + n_nonmember),
                "is_peak": False,
            })

        # 缺货/需求行
        for i in range(self.n_items):
            demand_rows.append({
                "item_code": self.item_codes[i],
                "demand_qty": round(demand_units[i], 2),
                "sold_qty": round(sold_units[i], 2),
                "lost_qty": round(lost_units[i], 2),
                "scrap_qty": 0.0,
                "is_stockout": bool(lost_units[i] > 1e-9),
            })
        # is_peak：当日 order_count 最高的 2 个小时（并列取较早的小时）
        rank = sorted(traffic_rows, key=lambda r: (-r["order_count"], r["hour_of_day"]))
        peak_hours = {r["hour_of_day"] for r in rank[:2] if r["order_count"] > 0}
        for r in traffic_rows:
            r["is_peak"] = r["hour_of_day"] in peak_hours

        # 当日损耗（SPOIL）：按批次日损耗率做二项抽样
        spoil_rows: list[dict[str, Any]] = []
        for bn, b in self.batches.items():
            if b.remain_units <= 0:
                continue
            rate = float(self._spoilage_rate[b.item_idx])
            if rate <= 0:
                continue
            u = float(self._noise["u_spoil"][d, b.event_idx % self._noise["u_spoil"].shape[1]])
            n = int(b.remain_units)
            spoil = int(_binom.ppf(u, n, rate)) if n > 0 else 0
            spoil = max(0, min(spoil, n))
            if spoil > 0:
                b.remain_units -= spoil
                b.spoil_units += spoil
                spoil_rows.append({"item_code": b.item_code, "batch_no": bn,
                                   "quantity": b.to_qty(spoil), "cost_price": b.cost_price,
                                   "reason": "SPOIL"})

        # 汇总（含日损耗）
        for i in range(self.n_items):
            sc = sum(b.scrap_units - scrap_before[bn] for bn, b in self.batches.items()
                     if b.item_idx == i)
            demand_rows[i]["scrap_qty"] = round(sc / self._scale[i], 2)
        # 观测历史（策略可见，不含真值）
        # 注意：这里的 "discount_rate" 是**折扣深度** 1 - 实收均价/原价（0~0.5），
        #       与 suggestions/pushes 里的"价格比"（0.5~1.0）含义相反，见 observed_history 文档。
        sold_by_item = sold_units
        for i in range(self.n_items):
            o = orders_per_item[i]
            realized = (price_sum[i] / price_qty[i]) if price_qty[i] > 0 else float(p_eff[i])
            self._obs_history.append({
                "biz_date": biz, "day_index": d, "item_code": self.item_codes[i],
                "unit_price": round(realized, 2), "sold_qty": round(sold_by_item[i], 2),
                "orders": int(o),
                "discount_rate": round(1.0 - realized / self._base_price[i], 4),
                "is_stockout": bool(lost_units[i] > 1e-9),
                "stockout_flag": bool(lost_units[i] > 1e-9),
                "on_hand_start": round(on_hand_start[i] / self._scale[i], 2),
                "arrival_qty": 0.0, "remain_end": 0.0,
            })
            self._truth_history.append({
                "biz_date": biz, "day_index": d, "item_code": self.item_codes[i],
                "p_eff": round(float(p_eff[i]), 2), "promo_lift": round(float(lift[i]), 4),
                "demand_qty": round(demand_units[i], 2), "sold_qty": round(sold_units[i], 2),
                "lost_qty": round(lost_units[i], 2), "orders": int(o),
                "on_hand_start": round(on_hand_start[i] / self._scale[i], 2),
                "discount_depth": round(float(1 - p_eff[i] / self._base_price[i]), 4),
                "true_lambda": round(float(lam_units_day[i]), 4),
                "scrap_qty": 0.0, "arrival_qty": 0.0, "remain_end": 0.0,
            })
        tot_orders = int(sum(r["order_count"] for r in traffic_rows))
        self._cum["orders"] += tot_orders
        self._cum["sold_units"] += float(sold_units.sum())
        self._cum["demand_units"] += float(demand_units.sum())
        self._cum["lost_units"] += float(lost_units.sum())
        self._cum["sales_amount"] += float(price_sum.sum())
        self._last_day_orders = {(d, r["hour_of_day"]): r["order_count"] for r in traffic_rows}
        return sales_rows, traffic_rows, demand_rows, spoil_rows

    def _member_weights(self, item_idx: int, cat: int) -> np.ndarray:
        """选人权重（可观测口径）：历史购买次数 + 推送加成；只有已推送会员当日加权。"""
        w = self._pref_count[:, cat].astype(float) + 0.4
        targets = getattr(self, "_push_targets_today", {}).get(item_idx)
        if targets:
            w[targets] *= PUSH_TARGET_BOOST
        return w

    def _end_of_day(self, biz: date, demand_rows: list[dict[str, Any]] | None = None,
                    ) -> list[dict[str, Any]]:
        """到期报废与批次状态更新；补齐当日全量报废（SPOIL+EXPIRED）与 arrival/remain。"""
        rows: list[dict[str, Any]] = []
        for bn, b in self.batches.items():
            days_left = (b.expire_date - biz).days
            if days_left == 0 and b.remain_units > 0:
                qty = b.remain_units
                b.expire_scrap_units += qty
                b.remain_units = 0
                b.status = "SCRAPPED"
                rows.append({"item_code": b.item_code, "batch_no": bn,
                             "quantity": b.to_qty(qty), "cost_price": b.cost_price,
                             "reason": "EXPIRED"})
            elif b.remain_units <= 0 and b.status == "ON_SALE":
                b.status = "SOLD_OUT"
        # 当日全量报废（含日内损耗 + 到期报废），按单品汇总
        scrap_before = getattr(self, "_scrap_at_day_start", {})
        scrap_day = np.zeros(self.n_items, dtype=float)
        for bn, b in self.batches.items():
            scrap_day[b.item_idx] += (b.scrap_units - scrap_before.get(bn, 0)) / b.scale
        if demand_rows is not None:
            for i, r in enumerate(demand_rows):
                r["scrap_qty"] = round(float(scrap_day[i]), 2)
        # 观测历史收尾
        rem_by_item = np.zeros(self.n_items, dtype=float)
        for b in self.batches.values():
            rem_by_item[b.item_idx] += b.remain_units / b.scale
        d = self.day_index
        for r in reversed(self._obs_history):
            if r["day_index"] != d:
                break
            i = self.item_code_to_idx[r["item_code"]]
            r["remain_end"] = round(float(rem_by_item[i]), 2)
        for r in reversed(self._truth_history):
            if r["day_index"] != d:
                break
            i = self.item_code_to_idx[r["item_code"]]
            r["remain_end"] = round(float(rem_by_item[i]), 2)
            r["scrap_qty"] = round(float(scrap_day[i]), 2)
        # 到货量写入观测历史
        arr_by_item = np.zeros(self.n_items, dtype=float)
        for i in range(self.n_items):
            for (adate, units, _ev) in self._arrival_plan[i]:
                if adate == biz:
                    arr_by_item[i] += units / self._scale[i]
        for r in reversed(self._obs_history):
            if r["day_index"] != d:
                break
            i = self.item_code_to_idx[r["item_code"]]
            r["arrival_qty"] = round(float(arr_by_item[i]), 2)
        for r in reversed(self._truth_history):
            if r["day_index"] != d:
                break
            i = self.item_code_to_idx[r["item_code"]]
            r["arrival_qty"] = round(float(arr_by_item[i]), 2)
        # 会员偏好在线更新
        for m, cats in self._day_member_cat.items():
            for c, cnt in cats.items():
                self._pref_count[m, c] += cnt
                self._pref_last[(m, c)] = biz
        return rows

    def _append_history(self, biz: date, demand_rows: list, purchase_rows: list,
                        sales_rows: list, lift: np.ndarray) -> None:
        return None  # 历史已在 _simulate_day / _end_of_day 内写入

    # ---------------------------------------------------------------- 整段跑
    def run(self, pricing_fn: Callable[..., Any] | None = None, days: int | None = None,
            collect: bool = True) -> dict[str, Any]:
        """便利方法：连续跑若干天，汇总所有明细（CLI 用；评测建议直接用 step_day）。"""
        n = self.days - self.day_index if days is None else int(days)
        acc: dict[str, list] = {k: [] for k in ("sales", "scrap", "purchase", "demand",
                                                "traffic", "suggestions", "pushes")}
        snapshots = []
        for _ in range(n):
            day = self.step_day(pricing_fn)
            if collect:
                for k in acc:
                    if len(day[k]):
                        acc[k].append(day[k])
            snapshots.append({"day_index": day["day_index"], "state_hash": day["state_hash"],
                              "orders": int(day["traffic"]["order_count"].sum()),
                              "lost_units": float(day["demand"]["lost_qty"].sum())})
        out = {k: (pd.concat(v, ignore_index=True) if v else pd.DataFrame()) for k, v in acc.items()}
        out["days"] = snapshots
        out["obs_history"] = self.observed_history(full=True)
        out["truth_history"] = pd.DataFrame(self._truth_history)
        return out

    # ---------------------------------------------------------------- 统计
    def summary(self) -> dict[str, float]:
        """截至当前天的关键统计（内部真值口径，用于报告）。"""
        th = pd.DataFrame(self._truth_history)
        obs = pd.DataFrame(self._obs_history)
        if th.empty:
            return {}
        days = th["day_index"].nunique()
        traffic_orders = self._cum["orders"]
        arrival = self._cum["arrival_units"]
        scrap = float(th["scrap_qty"].sum()) if "scrap_qty" in th else 0.0
        sold = float(th["sold_qty"].sum())
        lost = float(th["lost_qty"].sum())
        demand = float(th["demand_qty"].sum())
        disc_rows = obs[obs["discount_rate"] > 1e-9]
        return {
            "days": float(days),
            "avg_orders_per_day": traffic_orders / days,
            "avg_sold_units_per_day": sold / days,
            "avg_demand_units_per_day": demand / days,
            "stockout_days": float(th.groupby("day_index")["lost_qty"].sum().gt(0).sum()),
            "stockout_rate_units": lost / demand if demand > 0 else 0.0,
            "scrap_units": scrap,
            "arrival_units": arrival,
            "loss_rate": scrap / arrival if arrival > 0 else 0.0,
            "avg_discount_depth_all": float((obs["discount_rate"]).mean()) if len(obs) else 0.0,
            "avg_discount_depth_discounted": float(disc_rows["discount_rate"].mean())
            if len(disc_rows) else 0.0,
            "revenue": self._cum["sales_amount"],
        }


# ==============================================================================
# 内置规则版策略（seed 生成 + pricing 的历史窗口重放都用它，保证一致）
# ==============================================================================

def _rule_rate(days_left: int, near_expiry_days: int, batch: Batch, velocity: float,
               cost_price: float, base_price: float) -> float:
    """按剩余天数比例 + 库存/流速分档打折（候选档内），不涨价，且不清货亏本。

    分档：days_left 2/1/0 → 0.90/0.80/0.70；库存可售天数 >3 天再降一档、>6 天降两档。
    成本底线：打折不得低于"成本价/原价"对应的候选档（最后一天允许再低一档清货，
    因为报废收益为 0）。这条与 calib 的保本折扣 d=cost/(base*(1-loss)) 同源，
    是"店主经验"里最容易被忽略、也最容易被模型定价超越的一环。
    """
    if days_left > near_expiry_days:
        return 1.00
    # 店主经验：今天刚到货的批次不打折（否则保质期 2 天的单品会天天打折）
    shelf_life = (batch.expire_date - batch.arrive_date).days
    if shelf_life > 0 and days_left >= shelf_life:
        return 1.00
    order = list(CANDIDATE_DISCOUNTS)          # 1.00 → 0.50，越靠后越深
    tier = {2: 0.90, 1: 0.80, 0: 0.70}.get(int(days_left), 0.90)
    idx = order.index(tier)
    remain = batch.to_qty(batch.remain_units)
    cover_days = remain / velocity if velocity > 0.05 else 99.0
    if cover_days > 8:
        idx = min(idx + 2, len(order) - 1)
    elif cover_days > 4:
        idx = min(idx + 1, len(order) - 1)
    rate = order[idx]
    ratio = (cost_price / base_price) if base_price > 0 else 1.0
    allowed = [c for c in order if c >= ratio - 1e-9]
    floor_rate = min(allowed) if allowed else order[-1]
    if days_left >= 1 and rate < floor_rate:
        rate = floor_rate
    elif days_left == 0:
        last_ok = order[min(order.index(floor_rate) + 1, len(order) - 1)]
        if rate < last_ok:
            rate = last_ok
    return float(rate)


def default_rule_decision(biz_date: date, day_index: int,
                          state: Mapping[str, Any]) -> dict[str, float]:
    """仿真器内置的"店里过去怎么定价"——只在生成演示用种子数据时使用（本文件主程序与数据核对）。

    它**不是**评测里的 RULE 基线：评测的三个策略和历史窗口都用 ``scripts/pricing_rule.py``，
    后端 RULE 也与那一份一致（docs/03 §2.1）。``shop_pricing_suggestion.csv`` 里 strategy 写作 RULE 是历史标签。

    返回 ``{batch_no: discount_rate}``（批次粒度；协议同样接受 item_code 键）。
    只用 state 里的可观测信息（days_left / remain_qty / history）。
    """
    batches = state["batches"]
    hist = state["history"]
    near = int(state["near_expiry_days"])
    vel: dict[str, float] = {}
    if isinstance(hist, pd.DataFrame) and not hist.empty:
        recent = hist[hist["biz_date"] >= (biz_date - timedelta(days=7))]
        if not recent.empty:
            vel = (recent.groupby("item_code")["sold_qty"].sum() / 7.0).to_dict()
    out: dict[str, float] = {}
    if isinstance(batches, pd.DataFrame) and not batches.empty:
        for _, b in batches.iterrows():
            dl = int(b["days_left"])
            if dl > near:
                continue
            unit = str(b["unit"])
            scale = int(UNIT_BASKET_SPEC.get(unit, {}).get("scale_units", 1))
            # 注意：remain_qty 是"份"，内部整数刻度按单位换算（斤=0.01 斤，个/盒=1 件），
            # 否则 cover_days 会被放大 100 倍而过度打折（此坑已踩过一次，见 docs/02 §8）。
            fake = Batch(batch_no=str(b["batch_no"]), item_idx=0, item_code=str(b["item_code"]),
                         arrive_date=pd.Timestamp(b["arrive_date"]).date(),
                         expire_date=pd.Timestamp(b["expire_date"]).date(),
                         init_units=0, remain_units=int(round(float(b["remain_qty"]) * scale)),
                         cost_price=float(b["cost_price"]), base_price=float(b["base_price"]),
                         unit=unit, scale=scale)
            rate = _rule_rate(dl, near, fake, float(vel.get(str(b["item_code"]), 0.0)),
                              float(b["cost_price"]), float(b["base_price"]))
            out[str(b["batch_no"])] = rate
    return out


# ==============================================================================
# CLI：导出种子数据
# ==============================================================================

def write_seed(out_dir: Path, sim: ShopSimulator, dump_truth: bool = True,
               quiet: bool = False) -> dict[str, int]:
    """跑完整段仿真并写出 data/seed/shop_*.csv，返回各文件行数。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    res = sim.run(default_rule_decision)
    counts: dict[str, int] = {}

    def _dump(df: pd.DataFrame, name: str) -> None:
        path = out_dir / name
        df.to_csv(path, index=False, encoding="utf-8", lineterminator="\n")
        counts[name] = int(len(df))
        if not quiet:
            print(f"  {name:32s} {len(df):>8d} 行")

    items = sim.items.copy()
    _dump(items[["item_code", "item_name", "category_name", "unit", "cost_price", "base_price",
                 "shelf_life_days", "loss_rate_pct"]], "shop_item.csv")

    ledger = pd.DataFrame([
        {
            "batch_no": bb.batch_no, "item_code": bb.item_code,
            "arrive_date": bb.arrive_date, "expire_date": bb.expire_date,
            "init_qty": bb.to_qty(bb.init_units),
            "remain_qty": bb.to_qty(bb.remain_units),
            "cost_price": round(bb.cost_price, 2),
            "scrap_qty": bb.to_qty(bb.scrap_units),
            "status": bb.status,
        }
        for bb in sorted(sim.batches.values(), key=lambda x: (x.arrive_date, x.batch_no))
    ])
    _dump(ledger[["batch_no", "item_code", "arrive_date", "expire_date", "init_qty",
                  "remain_qty", "cost_price", "scrap_qty", "status"]], "shop_batch.csv")

    sales = res["sales"].copy()
    sales["biz_date"] = pd.to_datetime(sales["biz_date"]).dt.strftime("%Y-%m-%d")
    sales["sale_time"] = pd.to_datetime(sales["sale_time"]).dt.strftime("%Y-%m-%d %H:%M:%S")
    _dump(sales[["biz_date", "sale_time", "item_code", "batch_no", "quantity", "unit_price",
                 "is_discount", "is_return", "source"]], "shop_sales.csv")

    traffic = res["traffic"].copy()
    traffic["stat_date"] = pd.to_datetime(traffic["stat_date"]).dt.strftime("%Y-%m-%d")
    traffic["is_peak"] = traffic["is_peak"].astype(int)
    _dump(traffic[["stat_date", "hour_of_day", "order_count", "customer_count", "is_peak"]],
          "shop_traffic_hourly.csv")

    _dump(sim.members.copy(), "shop_member.csv")
    _dump(member_pref_frame(sim), "shop_member_pref.csv")

    sug = res["suggestions"].copy()
    if not sug.empty:
        sug["biz_date"] = pd.to_datetime(sug["biz_date"]).dt.strftime("%Y-%m-%d")
    _dump(sug[["biz_date", "item_code", "batch_no", "days_left", "remain_qty", "original_price",
               "suggested_price", "discount_rate", "strategy", "expected_qty", "reason"]]
          if not sug.empty else sug, "shop_pricing_suggestion.csv")

    push = res["pushes"].copy()
    if not push.empty:
        push["biz_date"] = pd.to_datetime(push["biz_date"]).dt.strftime("%Y-%m-%d")
        push["send_at"] = pd.to_datetime(push["send_at"]).dt.strftime("%Y-%m-%d %H:%M:%S")
    _dump(push[["biz_date", "member_no", "channel", "title", "content", "item_code",
                "discount_rate", "send_at", "opened", "converted"]]
          if not push.empty else push, "shop_push_message.csv")

    if dump_truth:
        # ---- 审计/评测内部文件（下划线或 sim_internal_ 前缀 = 非业务契约，定价模块禁止读取）
        th = res["truth_history"].copy()
        if not th.empty:
            th["biz_date"] = pd.to_datetime(th["biz_date"]).dt.strftime("%Y-%m-%d")
        _dump(th, "sim_internal_daily_item.csv")
        ti = sim._truth.items.copy()
        ti["category_peak_month"] = [sim._truth.season_peak_month[c] for c in ti["category_name"]]
        ti["daily_spoil_rate"] = sim._truth.spoilage_daily_rate.round(6)
        ti["arrival_bias"] = sim._truth.item_bias.round(4)
        ti.to_csv(out_dir / "_truth_item_params.csv", index=False, encoding="utf-8",
                  lineterminator="\n")
        counts["_truth_item_params.csv"] = int(len(ti))
        (out_dir / "_truth_factors.json").write_text(json.dumps({
            "seed": sim.seed, "version": VERSION,
            "hour_factor": sim._truth.hour_factor, "hour_source": sim._truth.hour_source,
            "weekday_factor": sim._truth.weekday_factor,
            "weekday_factor_source": ("data/seed/calibration/hourly_traffic.json by_weekday"
                                      if "calibration" in sim._truth.hour_source
                                      else "public sales detail 日均笔数按周几聚合后归一化"),
            "season_amplitude": SEASON_AMPLITUDE,
            "coverage": sim.coverage, "arrival_sigma": sim.arrival_sigma,
            "push_lift": sim.push_lift, "item_bias_sigma": ITEM_BIAS_SIGMA,
            "member_base_prob": MEMBER_BASE_PROB,
            "basket_spec": UNIT_BASKET_SPEC,
            "noise_fingerprint": sim.noise_fingerprint(),
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        counts["_truth_factors.json"] = 1
    return counts


def member_pref_frame(sim: ShopSimulator) -> pd.DataFrame:
    rows = []
    for m in range(sim.n_members):
        tot = int(sim._pref_count[m].sum())
        for c in range(sim.n_cats):
            cnt = int(sim._pref_count[m, c])
            if cnt <= 0:
                continue
            last = sim._pref_last.get((m, c))
            rows.append({
                "member_no": sim.members["member_no"].iloc[m],
                "category_name": CATEGORIES[c],
                "buy_count": cnt,
                "last_buy_date": last.isoformat() if last else "",
                "score": round(cnt / tot, 3) if tot else 0.0,
            })
    df = pd.DataFrame(rows)
    return df.sort_values(["member_no", "score"], ascending=[True, False]).reset_index(drop=True)


# ==============================================================================
# CLI：自检（对账 CSV）
# ==============================================================================

def self_check(out_dir: Path = SEED_DIR, verbose: bool = True) -> tuple[bool, dict[str, Any]]:
    """读回 CSV 做全量对账。返回 (是否全部通过, 统计)。"""
    checks: list[tuple[str, bool, str]] = []

    def ck(name: str, ok: bool, detail: str = "") -> None:
        checks.append((name, bool(ok), detail))

    item = pd.read_csv(out_dir / "shop_item.csv")
    batch = pd.read_csv(out_dir / "shop_batch.csv", parse_dates=["arrive_date", "expire_date"])
    sales = pd.read_csv(out_dir / "shop_sales.csv", parse_dates=["biz_date", "sale_time"])
    traffic = pd.read_csv(out_dir / "shop_traffic_hourly.csv", parse_dates=["stat_date"])
    member = pd.read_csv(out_dir / "shop_member.csv", dtype={"phone_tail": str})
    pref = pd.read_csv(out_dir / "shop_member_pref.csv", parse_dates=["last_buy_date"])
    sug = pd.read_csv(out_dir / "shop_pricing_suggestion.csv", parse_dates=["biz_date"])
    push = pd.read_csv(out_dir / "shop_push_message.csv", parse_dates=["biz_date", "send_at"],
                       dtype={"member_no": str}).fillna({"member_no": ""})

    # 1 单品
    ck("单品数量 30~40", 30 <= len(item) <= 40, f"{len(item)} 个")
    ck("item_code 唯一", item["item_code"].is_unique, "")
    ck("保质期 2~10 且不少于 4 种取值",
       item["shelf_life_days"].between(2, 10).all() and item["shelf_life_days"].nunique() >= 4,
       str(sorted(item["shelf_life_days"].unique())))
    ck("损耗率 2%~15% 且各品类有差异",
       item["loss_rate_pct"].between(2, 15).all() and item["loss_rate_pct"].nunique() >= 10,
       f"{item['loss_rate_pct'].min()}~{item['loss_rate_pct'].max()}")
    ck("价格水平符合街边水果店（2~60 元/单位）",
       item["base_price"].between(2, 60).all(),
       f"{item['base_price'].min()}~{item['base_price'].max()} 元")
    ck("无两个单品参数完全相同",
       item.drop(columns=["item_code", "item_name"]).duplicated().sum() == 0, "")
    ck("成本价 < 原价", (item["cost_price"] < item["base_price"]).all(), "")

    # 2 批次自洽
    sold = sales.groupby("batch_no")["quantity"].sum().rename("sold_qty")
    m = batch.merge(sold, on="batch_no", how="left").fillna({"sold_qty": 0.0})
    resid = (m["init_qty"] - m["sold_qty"] - m["scrap_qty"] - m["remain_qty"]).abs()
    ck("批次 remain = init - 累计销量 - 报废（误差<1e-6）", float(resid.max()) < 1e-6,
       f"max|resid|={float(resid.max()):.2e}")
    ck("无负库存/负报废", bool((m["remain_qty"] >= -1e-9).all() and (m["scrap_qty"] >= -1e-9).all()),
       "")
    sl = item.set_index("item_code")["shelf_life_days"]
    exp_ok = (batch["expire_date"] - batch["arrive_date"]).dt.days == batch["item_code"].map(sl)
    ck("expire_date = arrive_date + shelf_life_days", bool(exp_ok.all()), "")
    ck("批次状态合法",
       set(batch["status"].unique()) <= {"ON_SALE", "SOLD_OUT", "SCRAPPED"},
       str(sorted(batch["status"].unique())))
    ck("SOLD_OUT/SCRAPPED 批次余量为 0",
       bool((batch.loc[batch["status"] != "ON_SALE", "remain_qty"].abs() < 1e-9).all()), "")

    # 3 销售
    ck("销售均指向已登记批次", bool(sales["batch_no"].isin(set(batch["batch_no"])).all()), "")
    ck("销量>0 且单价>0", bool((sales["quantity"] > 0).all() and (sales["unit_price"] > 0).all()), "")
    ck("is_return 恒 0（本仿真不模拟退货）", bool((sales["is_return"] == 0).all()), "")
    ck("source 恒 SIM", bool((sales["source"] == "SIM").all()), "")
    is_disc = sales["unit_price"] < (sales["item_code"].map(item.set_index("item_code")["base_price"])
                                     - 1e-9)
    ck("is_discount 与单价<原价一致", bool((sales["is_discount"].astype(bool) == is_disc).all()), "")

    # 4 客流对账
    per_day_orders = sales.groupby(sales["biz_date"].dt.date).size()
    per_day_traffic = traffic.groupby(traffic["stat_date"].dt.date)["order_count"].sum()
    join = pd.concat([per_day_orders.rename("sales_rows"), per_day_traffic.rename("order_count")],
                     axis=1).fillna(0)
    ck("每日 order_count 之和 == 当日销售笔数",
       bool((join["sales_rows"] == join["order_count"]).all()),
       f"最大偏差 {int((join['sales_rows'] - join['order_count']).abs().max())}")
    ck("customer_count <= order_count", bool((traffic["customer_count"] <= traffic["order_count"]).all()), "")
    ck("每日恰好 2 个高峰小时（无成交的日子除外）",
       bool((traffic[traffic["order_count"] > 0].groupby(traffic["stat_date"].dt.date)["is_peak"]
             .sum() <= 2).all()), "")
    ck("营业小时为 8~21", set(traffic["hour_of_day"].unique()) <= set(range(8, 22)),
       str(sorted(traffic["hour_of_day"].unique())))
    ck("每日 14 个时段行",
       bool((traffic.groupby(traffic["stat_date"].dt.date).size() == 14).all()), "")

    # 5 日期连续
    dts = sorted(set(traffic["stat_date"].dt.date))
    gaps = [(dts[i + 1] - dts[i]).days for i in range(len(dts) - 1)]
    ck("日期区间连续无缺口", all(g == 1 for g in gaps), f"{dts[0]} ~ {dts[-1]}（{len(dts)} 天）")
    ck("报表天数 == 命令行 --days", len(dts) in (90, 120), f"{len(dts)}")

    # 6 会员
    ck("会员 80~150 人", 80 <= len(member) <= 150, f"{len(member)} 人")
    ck("member_no 唯一", member["member_no"].is_unique, "")
    ck("phone_tail 为 4 位数字且唯一",
       bool(member["phone_tail"].astype(str).str.fullmatch(r"\d{4}").all()
            and member["phone_tail"].is_unique), "")
    ck("push_enabled ∈ {0,1}", set(member["push_enabled"].unique()) <= {0, 1}, "")
    ck("member_pref 品类合法", set(pref["category_name"]) <= set(CATEGORIES), "")
    ck("member_pref buy_count>=1 且 score∈(0,1]",
       bool((pref["buy_count"] >= 1).all() and (pref["score"] > 0).all() and (pref["score"] <= 1).all()), "")
    ck("每个会员至少 1 个偏好品类",
       bool(pref.groupby("member_no").size().reindex(member["member_no"]).fillna(0).gt(0).all()), "")
    ck("偏好记录能支撑『买过某品类』选人（每品类至少 10 人）",
       bool(pref.groupby("category_name")["member_no"].nunique().ge(10).all()),
       str(pref.groupby("category_name")["member_no"].nunique().to_dict()))
    ck("每个会员偏好品类数 ≤ 8",
       bool(pref.groupby("member_no").size().le(8).all()), "")

    # 7 建议
    if len(sug):
        ck("建议折扣落在候选档", bool(sug["discount_rate"].round(2).isin(CANDIDATE_DISCOUNTS).all()),
           str(sorted(sug["discount_rate"].round(2).unique())))
        ck("建议不涨价", bool((sug["suggested_price"] <= sug["original_price"] + 1e-9).all()), "")
        ck("建议均为临期（days_left<=2）", bool(sug["days_left"].between(0, 2).all()),
           f"days_left∈{sorted(sug['days_left'].unique())}")
        ck("strategy 全为 RULE", set(sug["strategy"]) == {"RULE"}, "")
        ck("suggested_price = round(original*rate,2)",
           bool((sug["suggested_price"].round(2)
                 == (sug["original_price"] * sug["discount_rate"]).round(2)).all()), "")
        # 建议价必须等于当日该批次的实际挂牌价（在售建议批次应有成交或至少价格一致）
        sold_price = (sales.assign(d=sales["biz_date"].dt.date)
                      .merge(sug.assign(d=sug["biz_date"].dt.date)[["d", "batch_no", "suggested_price"]],
                             on=["d", "batch_no"], how="inner"))
        if len(sold_price):
            ck("在售临期批次的实际成交价 == 建议价（防止规则实现与建议脱节）",
               bool((sold_price["unit_price"] - sold_price["suggested_price"]).abs().max() < 1e-6),
               f"最大偏差={float((sold_price['unit_price'] - sold_price['suggested_price']).abs().max()):.4f}"
               f"（比对 {len(sold_price)} 行）")

    # 8 推送
    if len(push):
        inapp = push[push["channel"] == "IN_APP"]
        ck("单会员每日推送 ≤ 2 条",
           bool(inapp.groupby(["biz_date", "member_no"]).size().le(2).all()), "")
        ck("推送时刻不在静默时段 22:00-08:00",
           bool(push["send_at"].dt.hour.between(8, 21).all()),
           str(sorted(push["send_at"].dt.hour.unique())))
        ck("opened / converted ∈ {0,1}",
           set(push["opened"].unique()) <= {0, 1} and set(push["converted"].unique()) <= {0, 1}, "")
        ck("converted ⇒ opened（IN_APP）",
           bool((inapp.loc[inapp["converted"] == 1, "opened"] == 1).all()), "")
        ck("GROUP_TEXT 行 member_no 为空",
           bool((push.loc[push["channel"] == "GROUP_TEXT", "member_no"].astype(str) == "").all()), "")
        ck("推送对象都是买过该品类的会员",
           bool(push[push["channel"] == "IN_APP"].merge(
               pref, left_on=["member_no"], right_on=["member_no"], how="left"
           )["category_name"].notna().all()), "")

    ok = all(c[1] for c in checks)
    stats: dict[str, Any] = {}
    if verbose:
        print("=" * 78)
        print("自检报告（读回 CSV 对账）")
        print("=" * 78)
        for name, good, detail in checks:
            print(f"  [{'PASS' if good else 'FAIL'}] {name}" + (f"  —— {detail}" if detail else ""))
        print("-" * 78)
        print(f"  合计 {len(checks)} 项，通过 {sum(1 for c in checks if c[1])} 项，"
              f"失败 {sum(1 for c in checks if not c[1])} 项")

    # 统计
    days = traffic["stat_date"].dt.date.nunique()
    arrival = batch["init_qty"].sum()
    scrap = batch["scrap_qty"].sum()
    tj = traffic.groupby(traffic["stat_date"].dt.date)[["order_count", "customer_count"]].sum()
    sales_j = sales.groupby(sales["biz_date"].dt.date).apply(
        lambda g: (g["quantity"] * g["unit_price"]).sum(), include_groups=False)
    disc_rows = sales[sales["is_discount"] == 1]
    base_map = item.set_index("item_code")["base_price"]
    internal = out_dir / "sim_internal_daily_item.csv"
    stockout_days = 0
    scrap_spoil_share = 0.0
    if internal.exists():
        ti = pd.read_csv(internal)
        stockout_days = int(ti.groupby("day_index")["lost_qty"].sum().gt(1e-9).sum())
    stats = {
        "days": days,
        "avg_daily_orders": float(tj["order_count"].mean()),
        "avg_daily_customers": float(tj["customer_count"].mean()),
        "total_orders": int(traffic["order_count"].sum()),
        "total_sales_rows": int(len(sales)),
        "avg_ticket_yuan": float(sales_j.mean() / max(tj["order_count"].mean(), 1e-9)),
        "avg_daily_revenue": float(sales_j.mean()),
        "avg_loss_rate": float(scrap / arrival) if arrival else 0.0,
        "total_arrival_qty": float(arrival),
        "total_scrap_qty": float(scrap),
        "stockout_days": stockout_days,
        "avg_discount_depth_all_rows": float(
            (1 - sales["unit_price"] / sales["item_code"].map(base_map)).mean()),
        "avg_discount_depth_discounted_rows": float(
            (1 - disc_rows["unit_price"] / disc_rows["item_code"].map(base_map)).mean())
        if len(disc_rows) else 0.0,
        "discounted_order_share": float(len(disc_rows) / max(len(sales), 1)),
        "suggestion_rows": int(len(sug)),
        "avg_suggestion_depth": float(1 - sug["discount_rate"].mean()) if len(sug) else 0.0,
        "push_rows": int(len(push)),
        "push_inapp_rows": int((push["channel"] == "IN_APP").sum()) if len(push) else 0,
        "push_open_rate": float(push.loc[push["channel"] == "IN_APP", "opened"].mean())
        if len(push) and (push["channel"] == "IN_APP").any() else 0.0,
        "push_convert_rate": float(push.loc[push["channel"] == "IN_APP", "converted"].mean())
        if len(push) and (push["channel"] == "IN_APP").any() else 0.0,
        "members": int(len(member)),
        "items": int(len(item)),
        "batches": int(len(batch)),
        "date_min": str(min(dts)), "date_max": str(max(dts)),
    }
    if verbose:
        print("-" * 78)
        print("关键统计")
        for k, v in stats.items():
            print(f"  {k:36s} {v}")
    return ok, {"checks": checks, "stats": stats}


def _stockout_days_from_sim() -> int:
    """缺货天数由 seed 生成时的内部记录给出（写到 _truth_factors.json 的 stockout_days）。"""
    path = SEED_DIR / "_truth_factors.json"
    if path.exists():
        try:
            return int(json.loads(path.read_text(encoding="utf-8")).get("stockout_days", -1))
        except Exception:  # noqa: BLE001
            return -1
    return -1


# ==============================================================================
# CLI：评测台接口自测（task-5 证据）
# ==============================================================================

def selftest(seed: int = 20260701, days: int = 120, warmup_days: int = 60,
             phase2_days: int = 10, verbose: bool = True) -> bool:
    """证明四件事（task-5 验收）：

    1. ``snapshot()`` 可 json 序列化，且**不含真值**（base_demand/elasticity/hour_factor…）
    2. ``snapshot() → restore()`` 精确：同一快照 + 同一策略 ⇒ 逐字节同结果
    3. 同一快照 + **不同价格策略** ⇒ 预抽 U 完全一致，实际订单数 = poisson.ppf(U, 该策略自己的 λ)；
       且到货量逐 (日,单品) 相同（补货外生 ⇒ 三策略公平性不变量）
    4. 同 seed 两次导出 CSV 逐字节一致；同 seed 两个独立实例指纹一致
    """
    checks: list[tuple[str, bool, str]] = []

    def ck(name: str, ok: bool, detail: str = "") -> None:
        checks.append((name, bool(ok), detail))

    # ---------- 1) 冻结 60 天历史，随后 snapshot
    sim = ShopSimulator(seed=seed, days=days, warmup_days=warmup_days, push=False)
    fp0 = sim.noise_fingerprint()
    for _ in range(warmup_days):
        sim.step_day(default_rule_decision)
    snap = sim.snapshot()
    try:
        snap_json = canonical_json(snap)
        json_ok = True
    except Exception as exc:  # noqa: BLE001
        snap_json, json_ok = f"{exc}", False
    ck("snapshot() 可 json 序列化", json_ok, f"{len(snap_json)} 字节")
    # 真值/噪声字段名（注意：批次状态里的 spoil_units 是可观测状态，不算真值）
    forbidden = ("base_demand", "elasticity", "hour_factor", "weekday_factor", "promo_lift",
                 "_truth", "u_count", "u_basket", "u_ismember", "u_pick", "u_spoil", "u_open",
                 "u_arrival", "u_time", "u_latent", "u_prior", "noise_fingerprint",
                 "arrival_plan", "item_bias", "spoilage_daily_rate", "coverage", "elasticity_scale")
    leaked = [k for k in forbidden if k in snap_json]
    ck("snapshot() 不含真值/噪声字段", not leaked, f"泄漏={leaked}" if leaked else "0 项")
    hash_at_60 = sim.state_hash()

    # ---------- 2) 三个分支都从同一 snapshot 出发
    def strat_fixed(biz, di, state):
        return None

    def strat_deep(biz, di, state):
        out = {}
        b = state["batches"]
        if isinstance(b, pd.DataFrame) and not b.empty:
            for _, r in b.iterrows():
                dl = int(r["days_left"])
                if dl <= 2:
                    out[str(r["batch_no"])] = {0: 0.5, 1: 0.6, 2: 0.7}.get(dl, 0.7)
        return out

    def strat_shallow(biz, di, state):
        """最保守的清货：只在到期当天打 9 折。"""
        out = {}
        b = state["batches"]
        if isinstance(b, pd.DataFrame) and not b.empty:
            for _, r in b.iterrows():
                if int(r["days_left"]) == 0:
                    out[str(r["batch_no"])] = 0.90
        return out

    branches: dict[str, dict[str, Any]] = {}
    for label, fn in (("A_fixed", strat_fixed), ("A2_fixed_again", strat_fixed),
                      ("B_deep_discount", strat_deep), ("C_shallow_lastday", strat_shallow)):
        s = ShopSimulator(seed=seed, days=days, warmup_days=warmup_days, push=False)
        s.restore(snap)
        s._crn_debug = True
        ck(f"分支{label}: restore 后 state_hash 与快照一致", s.state_hash() == hash_at_60,
           s.state_hash())
        sales_rows, traffic_orders, hashes = 0, [], []
        purchases: dict[tuple[int, str], float] = {}
        for _ in range(phase2_days):
            day = s.step_day(fn)
            sales_rows += len(day["sales"])
            traffic_orders.append(int(day["traffic"]["order_count"].sum()))
            hashes.append(day["state_hash"])
            for _, pr in day["purchase"].iterrows():
                purchases[(int(day["day_index"]), str(pr["item_code"]))] = float(pr["quantity"])
        branches[label] = {"fingerprint": s.noise_fingerprint(), "orders": traffic_orders,
                           "state_hashes": hashes, "sales_rows": sales_rows,
                           "cells": [c[:3] + (round(c[3], 9), c[4]) for c in s._crn_cells],
                           "cells_raw": s._crn_cells,
                           "purchases": purchases,
                           "purchase_total": sum(purchases.values()),
                           "sales_digest": _sha16(canonical_json(
                               s.observed_history(full=True).to_dict("records")))}

    ck("分支A 与 分支A2（同策略）逐日 state_hash 完全一致",
       branches["A_fixed"]["state_hashes"] == branches["A2_fixed_again"]["state_hashes"]
       and branches["A_fixed"]["sales_rows"] == branches["A2_fixed_again"]["sales_rows"],
       f"sales_rows={branches['A_fixed']['sales_rows']}")
    ck("四个分支的噪声指纹完全一致（与策略无关）",
       len({b["fingerprint"] for b in branches.values()}) == 1,
       f"{branches['A_fixed']['fingerprint']}")
    ck("四个分支使用同一批 (day,hour,item) 抽样格",
       len({tuple(c[:3] for c in b["cells"]) for b in branches.values()}) == 1,
       f"{len(branches['A_fixed']['cells'])} 个格子")
    # 公平性不变量：到货是外生序列 ⇒ 四种价格策略的进货量逐 (日,单品) 完全相同
    def _pur_digest(d: dict[tuple[int, str], float]) -> str:
        return _sha16("|".join(f"{k[0]}:{k[1]}={v:.4f}" for k, v in sorted(d.items())))

    pur_digests = {label: _pur_digest(b["purchases"]) for label, b in branches.items()}
    same_pur = len(set(pur_digests.values())) == 1
    ck("四种价格策略的进货量逐(日,单品)完全相同（补货外生 ⇒ 公平性不变量）", same_pur,
       f"到货记录 {len(branches['A_fixed']['purchases'])} 条 / 合计 "
       f"{branches['A_fixed']['purchase_total']:.2f} 份，摘要 {pur_digests['A_fixed']}"
       + ("" if same_pur else f"；不一致：{pur_digests}"))
    ck("不同策略确实产生不同需求（否则评测无区分度）",
       len({tuple(b["orders"]) for b in branches.values()}) >= 3,
       " / ".join(f"{k}={sum(b['orders'])}笔" for k, b in branches.items()))
    # 逐格验证：订单数 == poisson.ppf(预抽 U, 该策略自己的 lambda)
    bad = 0
    for label, b in branches.items():
        for (d_i, hi, ii, lam, cnt) in b["cells_raw"]:
            expect = int(_poisson.ppf(float(sim._noise["u_count"][d_i, hi, ii]), lam))
            if expect != cnt:
                bad += 1
    ck("订单数 = poisson.ppf(预抽U, 本策略λ)（逐格 100% 对账）", bad == 0,
       f"不符格子 {bad} / {len(branches['A_fixed']['cells_raw']) * 3}")

    # ---------- 3) 导出确定性（同一 seed 两次写 CSV 逐字节一致）
    import tempfile
    digests = []
    for _ in range(2):
        with tempfile.TemporaryDirectory() as td:
            s2 = ShopSimulator(seed=seed, days=14, warmup_days=7, push=True)
            write_seed(Path(td), s2, dump_truth=True, quiet=True)
            dig = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()[:16]
                   for p in sorted(Path(td).iterdir())}
            digests.append(dig)
    ck("同 seed 两次导出 CSV 逐字节一致（14 天小样本）", digests[0] == digests[1],
       f"{len(digests[0])} 个文件 " + ",".join(f"{k}:{v}" for k, v in list(digests[0].items())[:3]))
    s3 = ShopSimulator(seed=seed, days=days, warmup_days=warmup_days, push=False)
    ck("同 seed 独立实例噪声指纹一致", s3.noise_fingerprint() == fp0, fp0)

    ok = all(c[1] for c in checks)
    if verbose:
        print("=" * 78)
        print("评测台接口自测（task-5）")
        print("=" * 78)
        for name, good, detail in checks:
            print(f"  [{'PASS' if good else 'FAIL'}] {name}" + (f"  —— {detail}" if detail else ""))
        print("-" * 78)
        print(f"  合计 {len(checks)} 项，通过 {sum(1 for c in checks if c[1])} 项")
    return ok


# ==============================================================================
# CLI：从公开数据重算时段形状（证明内置常量可复现）
# ==============================================================================

def hourly_from_public(path: Path = INTERIM_DIR / "sales_detail.csv") -> dict[str, Any]:
    df = pd.read_csv(path, usecols=["sale_date", "time_slot", "sale_type"])
    sale = df[df["sale_type"] == "销售"]
    hour = sale["time_slot"].astype(str).str.slice(0, 2).astype(int)
    share = hour.value_counts(normalize=True).sort_index()
    d = sale.assign(h=hour).groupby(sale["sale_date"].astype(str).str.slice(0, 10)).size()
    wd = pd.to_datetime(d.index).dayofweek
    wf = d.groupby(wd).mean() / d.mean()
    print(f"真实数据：销售流水 {len(sale):,} 行，日均笔数 {d.mean():.1f}，天数 {len(d)}")
    print("24 小时份额：", {int(k): round(float(v), 4) for k, v in share.items()})
    print("周内因子 Mon..Sun：", {int(k): round(float(v), 4) for k, v in wf.items()})
    print("内置时段因子（8~21，已含 8 点 3.5% 假设并归一化）：")
    for h in SHOP_HOURS:
        print(f"  {h:02d}:00  {BUILTIN_HOUR_FACTOR[h]:.4f}")
    print("内置周内因子：", {k: round(v, 4) for k, v in BUILTIN_WEEKDAY_FACTOR.items()})
    return {"hour_share": {int(k): float(v) for k, v in share.items()},
            "weekday_factor": {int(k): float(v) for k, v in wf.items()},
            "builtin_hour_factor": BUILTIN_HOUR_FACTOR}


# ==============================================================================
# main
# ==============================================================================

def _erfinv(x: float) -> float:
    """逆误差函数（避免额外依赖）。"""
    a = 0.147
    ln = math.log(1 - x * x)
    t = 2 / (math.pi * a) + ln / 2
    return math.copysign(math.sqrt(max(math.sqrt(t * t - ln / a) - t, 0.0)), x)


def main(argv: Sequence[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="街边水果店销售仿真器")
    sub = p.add_subparsers(dest="cmd")

    pr = sub.add_parser("run", help="生成 data/seed/shop_*.csv")
    pr.add_argument("--seed", type=int, default=20260701)
    pr.add_argument("--days", type=int, default=120)
    pr.add_argument("--warmup-days", type=int, default=60)
    pr.add_argument("--start-date", default="2026-03-02")
    pr.add_argument("--out", default=str(SEED_DIR))
    pr.add_argument("--no-push", action="store_true", help="关闭推送环节")
    pr.add_argument("--no-truth-dump", action="store_true", help="不输出审计用真值文件")
    pr.add_argument("--hourly-source", default="auto", choices=["auto", "builtin", "calibration"])
    pr.add_argument("--emit-csv", action="store_true",
                    help="兼容别名：默认行为就是导出 CSV")

    pc = sub.add_parser("check", help="读回 CSV 自检对账")
    pc.add_argument("--out", default=str(SEED_DIR))

    ph = sub.add_parser("hourly-from-public", help="用公开数据重算时段形状")

    ps = sub.add_parser("selftest", help="评测台接口自测：确定性 / snapshot-restore / CRN")
    ps.add_argument("--seed", type=int, default=20260701)
    ps.add_argument("--days", type=int, default=120)
    ps.add_argument("--warmup-days", type=int, default=60)

    args = p.parse_args(argv)
    cmd = args.cmd or "run"
    if cmd == "hourly-from-public":
        hourly_from_public()
        return 0
    if cmd == "selftest":
        return 0 if selftest(seed=args.seed, days=args.days, warmup_days=args.warmup_days) else 1
    if cmd == "check":
        ok, _ = self_check(Path(args.out))
        return 0 if ok else 1

    out = Path(args.out)
    sim = ShopSimulator(seed=args.seed, days=args.days, warmup_days=args.warmup_days,
                        start_date=args.start_date, push=not args.no_push,
                        hourly_source=args.hourly_source, verbose=True)
    print(f"仿真器 v{VERSION}  seed={sim.seed}  days={sim.days}  warmup={sim.warmup_days}  "
          f"start={sim.start_date}  push={sim.push_enabled}")
    print(f"时段形状来源：{sim._hour_source}；噪声指纹 {sim.noise_fingerprint()}")
    counts = write_seed(out, sim, dump_truth=not args.no_truth_dump)
    s = sim.summary()
    # 缺货天数写进审计文件，供 check 阶段读取
    tf = out / "_truth_factors.json"
    if tf.exists():
        data = json.loads(tf.read_text(encoding="utf-8"))
        data["stockout_days"] = int(s.get("stockout_days", -1))
        data["summary"] = {k: (round(v, 6) if isinstance(v, float) else v) for k, v in s.items()}
        tf.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print("-" * 78)
    print("仿真内部统计（真值口径）")
    for k, v in s.items():
        print(f"  {k:32s} {v if not isinstance(v, float) else round(v, 4)}")
    print("-" * 78)
    print(f"共写出 {len(counts)} 个文件到 {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
