# -*- coding: utf-8 -*-
"""
模型定价（策略名 MODEL）—— 用"只能看到的历史"估计需求，在候选折扣档里挑期望收益最大的那个。

================================================================================
一、这条边界叫"不作弊"，是全项目最容易被答辩追问的地方
================================================================================
本文件**只**使用以下两类输入：

  A. 可观测台账：`state["batches"]`（批次号/到货日/到期日/剩余天数/现存/到货量/成本/原价/保质期）
  B. 可观测历史：`state["history"]`（已发生日期的 单品 实收均价 折扣 销量 是否断货 期初库存 到货量 期末库存）

**本文件绝对不会、也不允许去读**：
  * `ShopSimulator._truth`（含 `base_demand`、`elasticity`、`hour_factor`、`weekday_factor` 真值）
  * `data/seed/_truth_item_params.csv`（真值审计文件，sim 已明确标注"定价模块禁止读取"）
  * `type(day_record)["demand"]`（未截断需求 demand_qty 是仿真器内部真值，只能由评测脚本
    用于**事后算指标**，绝不能回流给定价模型）

见 `_assert_no_truth_access()`：它会主动扫描输入对象，一旦发现真值字段就直接抛异常，
把这条规矩从"注释"变成"会失败的测试"。

================================================================================
二、模型：对数线性需求 + 单品固定效应 + 周内效应
================================================================================
    log(sold_qty[i,t]) = alpha_i + beta_i * log(price_ratio[i,t]) + gamma_{dow(t)} + e

  * price_ratio = 实收均价 / 原价（业务上就等于当天的折扣系数）
  * alpha_i     单品固定效应（吃掉"这个单品天生好卖/难卖"）
  * beta_i      自身价格弹性，**预期为负**
  * gamma_dow   周内效应（周一~周日），中心化到均值为 0

### 内生性：历史价格本身就是策略选出来的，怎么办？
这是本项目必须直面的问题，有两条偏差，方向**一致**：

1. **策略内生性**：历史 60 天用的是规则折扣——库存压手、明天就到期的货才会被打折。
   于是"低价格"总是伴随"本来就更难卖的库存状态"。回归会把这份不利状态算到价格头上，
   使 |beta| **被低估**（价格看起来没那么管用）。
   处理：单品内去均值（固定效应/within 估计），把"这个单品天生难卖"这一层吸掉；
   用 cluster-by-item 稳健标准误，避免把单品内相关性当成样本量。

2. **缺货截断（censoring）**：某天断货时，观测到的 sold_qty 被库存封顶，
   真实需求更高但我们看不到。深折日最容易断货 → 高需求点被压平 → |beta| **再次被低估**。
   处理：`is_stockout=True` 或 `sold_qty >= 0.98 * (期初库存 + 当日到货)` 的样本**整行剔除**；
   同时在报告里给出被剔除的比例。

**两条偏差同向 → |beta| 偏小 → 模型低估"打折能带来多少增量"→ 打折偏保守、清货偏少。**
所以本评测里的模型定价是**偏保守**的一方：它赢得难看是真赢，赢不了也不代表模型思路错。
这个方向必须写进 docs/03 与评测报告，不能只写"我们处理了内生性"。

3. 单品弹性样本太少时，用**经验贝叶斯收缩**向池化弹性靠拢：
   `beta_i = (n_i * beta_i^own + K * beta_pooled) / (n_i + K)`
   样本越少越向池化值靠，避免某个单品 3 条记录就估出 -8 的离谱弹性。

================================================================================
三、决策：候选档枚举 + 有限期动态规划（把报废成本算进去）
================================================================================
朴素做法是"选让今天期望收入最大的折扣"，但那会**系统性地打得太浅**：
今天没卖掉的货明天还能卖，明天卖不掉就报废。所以用倒推的价值表：

    W[0][q] = max_d { p(d) * E[min(Poisson(lam_d), q)] - c * (q - 卖出) }      # 今天到期，剩的全报废
    W[k][q] = max_d { p(d) * E[min(Poisson(lam_d), q)] + W[k-1][q - 卖出] }   # 还有 k+1 天可卖
    决策(k, q) = argmax_d 上式

  * `q` 按整数件离散化（仿真里一件=一个可售单位）
  * `lam_d` 是该折扣档下的预测需求
  * `W[k-1]` 就是"留到明天的机会价值"，报废成本通过 W[0] 里的 `-c*剩余` 进入目标函数
  * 折扣只在 {1.00,0.90,0.80,0.70,0.60,0.50} 里选（docs/01 第 3 节），不做连续搜索

**已知近似（写进局限）**：单批次的 DP 假设"该批次在单品内的需求份额保持不变"，
不建模同单品多批次之间的互相蚕食。因为其他批次也在消耗，真实份额会随时间上升，
所以这个近似对"留到明天"的价值是**略微保守**的。

运行自检：
    python scripts/pricing_model.py --selftest
"""

from __future__ import annotations

import argparse
import datetime as _dt
import math
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

import numpy as np

from pricing_rule import DISCOUNT_GRID, snap_to_grid

# ---------------------------------------------------------------------------
# 先验与护栏（都是显式常量，答辩时可以直接指着说"这是我们自己加的约束"）
# ---------------------------------------------------------------------------

#: 弹性取值区间。上界 -0.05 表示"不可能完全无弹性"，下界 -5 防止指数外推爆炸。
ELASTICITY_CLIP = (-5.0, -0.05)

#: 收缩强度 K：单品样本数 n_i 与 K 的比值决定"信自己多少"。K=40 ≈ 40 天才敢信单品自己的斜率。
SHRINKAGE_K = 40.0

#: 单品自身的斜率至少要这么多条可用观测才单独估计
MIN_OBS_PER_ITEM = 12

#: 缺少价格变化时的先验弹性（同时记录触发次数，报告里要如实写）
PRIOR_ELASTICITY = -1.5

#: 判定"当天被库存截断"的销量/可售量阈值
CENSOR_RATIO = 0.98

#: 价值表的最大天数与最大件数（防止异常输入把 DP 撑爆）
MAX_DP_DAYS = 14
MAX_DP_QTY = 400

#: 真值字段黑名单：任何输入对象里出现这些键名，立即报错（见 _assert_no_truth_access）
TRUTH_KEYS = frozenset(
    {
        "base_demand",
        "elasticity",
        "eps",
        "eps_i",
        "hour_factor",
        "weekday_factor",
        "promo_lift",
        "_truth",
        "truth_params",
        "demand_qty",  # 未截断需求，只允许评测脚本用于事后计分
    }
)

#: 公开真实数据（2023 数模 C 题蔬菜流水，46,595 条 单品×日，246 个单品）校准出的弹性结论。
#: 来源：data/seed/calibration/price_elasticity.json（calib 的 task-1 产物，Lead 独立核验）。
#:   * 单品均值层 between  : -0.982  (n=246, R²=0.406)  ← 横截面成分："均价高的单品本来就卖得少"
#:   * 朴素 pooled OLS         : -0.788  (n=46,595, R²=0.163)  ← 混了组间+组内，**高估**了弹性
#:   * 单品固定效应+周内/月度/趋势: -0.523  (n=46,595, R²=0.108)  ← 主估计
#:   * IV（工具=ln 批发价）     : -0.366  (一阶段 F=58,946)       ← 稳健性对照
#: 推荐给仿真器真值弹性的锚定区间取自 FE（中心）与 IV 的交集：[IV, FE] = [-0.573, -0.316]，
#: 中心 -0.523；分品类 -0.283（食用菌）~ -0.823（茄类）。
#: 注意：这是**蔬菜**的估计，本项目仿真的是**水果**，两者不完全可比（见 docs/03）。
PUBLIC_ELASTICITY_RANGE = (-0.573, -0.316)
PUBLIC_ELASTICITY_CENTER = -0.523
PUBLIC_ELASTICITY_BY_CATEGORY = (-0.823, -0.283)
PUBLIC_BETWEEN_ELASTICITY = -0.982
PUBLIC_NAIVE_POOLED_OLS = -0.788
PUBLIC_FE_WITHIN_ELASTICITY = -0.523
PUBLIC_IV_ELASTICITY = -0.366


def price_ratio_series(df, base_price_map: Optional[Dict[str, float]] = None):
    """从观测历史里取出**价格比 p/base**。返回 (Series, 口径说明) 或 (None, 原因)。

    ★ 这里有个必须小心的口径坑（实测踩过）：
      `ShopSimulator.observed_history()["discount_rate"]` 是**折扣深度** `1 − 实收均价/原价`
      （取值 0.00 ~ 0.50，0 表示没打折），**不是**价格比；
      而 `day["sales"]` / `pricing_suggestion` 里的 `discount_rate` 是**价格比**（0.50 ~ 1.00）。
      同一个列名在两处含义相反，直接拿来当回归自变量会让弹性**符号翻转**。

    取值优先级（从最不容易出错的开始）：
      1. `unit_price / base_price`（有 base_price_map 时）——物理意义明确，首选
      2. `1 − discount_rate`，当该列最大值 ≤ 0.55（判定为"折扣深度"口径）
      3. `discount_rate`，当该列取值落在 [0.5, 1.0+]（判定为"价格比"口径）
      4. 表内自带的 `unit_price / base_price` 两列
    """
    import pandas as pd

    if df is None or len(df) == 0:
        return None, "空历史"

    if base_price_map and "unit_price" in df.columns and "item_code" in df.columns:
        bp = df["item_code"].astype(str).map(base_price_map)
        pr = pd.to_numeric(df["unit_price"], errors="coerce") / pd.to_numeric(bp, errors="coerce")
        if pr.notna().mean() > 0.5:
            return pr, "unit_price/base_price（台账原价）"

    if "discount_rate" in df.columns and df["discount_rate"].notna().any():
        dr = pd.to_numeric(df["discount_rate"], errors="coerce")
        mx = float(dr.dropna().max()) if dr.notna().any() else float("nan")
        if np.isfinite(mx) and mx <= 0.55:
            return 1.0 - dr, "1−discount_rate（折扣深度口径 → 换算成价格比）"
        return dr, "discount_rate（已是价格比口径）"

    if {"unit_price", "base_price"}.issubset(df.columns):
        return (
            pd.to_numeric(df["unit_price"], errors="coerce")
            / pd.to_numeric(df["base_price"], errors="coerce"),
            "unit_price/base_price",
        )
    return None, "无可用价格列"


def naive_pooled_ols(history, base_price_map: Optional[Dict[str, float]] = None) -> Dict[str, float]:
    """【诊断，不参与定价】三种"偷懒"估计的对比：between / 朴素 pooled / 单品内去均值。

    这是"为什么必须做组内识别"的书面证据，复刻 calib 在公开真实数据上的发现：

      * **单品均值层 between**（只用每个单品的均价与平均销量，246 个点）→ 约 -0.98
        它衡量的是"均价高的单品本来就卖得少"，是**横截面**成分，不是价格弹性。
      * **朴素 pooled OLS**（所有观测混在一起 + 常数项）→ 约 -0.79
        它把 -0.98 的横截面成分混了进来，于是把真实的组内弹性**高估**了约 0.27
        （相对 FE 的 -0.52 偏高约 50%）。
      * **单品内去均值（within / 固定效应）** → 约 -0.52，这才是可用来定价的弹性。

    真实的内生性风险表现为：组内 -0.52 与工具变量 -0.37 之间还差 0.16，方向是
    "价格内生偏高 → |弹性| 被高估"，但**无法进一步区分**哪个更接近真相——
    本项目如实报告这个不确定性，不编因果。

    另一个估计陷阱（写进 docs/03）：**回归漏掉常数项会强迫过原点**，
    此时 pooled 斜率可能翻成正号、R² 变负。calib 正是先看到负 R² 才发现设定错误的。
    `naive_pooled_ols` 用去均值实现，等价于含常数项，不会再犯这个错。

    本函数只打印/记录结果，**绝不会**被 `fit_demand_model` 或定价逻辑使用。
    """
    import pandas as pd

    out: Dict[str, float] = {
        "beta_naive_pooled": float("nan"),
        "beta_between": float("nan"),
        "beta_within": float("nan"),
        "r2_pooled": float("nan"),
        "r2_between": float("nan"),
        "n_obs": 0,
        "n_items": 0,
    }
    df = _to_frame(history)
    if df.empty or "sold_qty" not in df.columns:
        return out
    price, how = price_ratio_series(df, base_price_map)
    if price is None:
        return out
    out["price_basis"] = how  # type: ignore[assignment]
    q = pd.to_numeric(df["sold_qty"], errors="coerce")
    m = (price > 0) & (q > 0)
    if int(m.sum()) < 8:
        return out
    x = np.log(price[m].to_numpy(dtype=float))
    y = np.log(q[m].to_numpy(dtype=float))
    item = df.loc[m, "item_code"].astype(str).to_numpy() if "item_code" in df.columns else np.array(["all"] * int(m.sum()))

    def _ols(xx, yy):
        xc, yc = xx - xx.mean(), yy - yy.mean()
        sxx = float((xc ** 2).sum())
        if sxx <= 1e-12:
            return float("nan"), float("nan")
        b = float((xc * yc).sum() / sxx)
        resid = yc - b * xc
        r2 = 1.0 - float((resid ** 2).sum()) / max(float((yc ** 2).sum()), 1e-12)
        return b, r2

    b_pool, r2_pool = _ols(x, y)
    # between：先对每个单品取均值，再在做横截面上回归
    tmp = pd.DataFrame({"item": item, "x": x, "y": y})
    g = tmp.groupby("item")[["x", "y"]].mean()
    b_btw, r2_btw = _ols(g["x"].to_numpy(), g["y"].to_numpy()) if len(g) >= 3 else (float("nan"), float("nan"))
    # within：单品内去均值
    tmp["xd"] = tmp["x"] - tmp.groupby("item")["x"].transform("mean")
    tmp["yd"] = tmp["y"] - tmp.groupby("item")["y"].transform("mean")
    b_win, _ = _ols(tmp["xd"].to_numpy(), tmp["yd"].to_numpy())

    out.update(
        {
            "beta_naive_pooled": b_pool,
            "beta_between": b_btw,
            "beta_within": b_win,
            "r2_pooled": r2_pool,
            "r2_between": r2_btw,
            "n_obs": int(m.sum()),
            "n_items": int(len(g)),
        }
    )
    return out


def elasticity_plausibility(beta: float) -> str:
    """把估计出的弹性对照公开真实数据的校准区间给出可读判断（只报告，不裁剪）。"""
    lo, hi = PUBLIC_ELASTICITY_RANGE
    if not np.isfinite(beta):
        return "无法判断"
    if beta > 0:
        return (
            f"⚠ 符号为正（{beta:+.3f}），与需求定律矛盾——几乎一定是设定错误"
            f"（漏常数项 / 共线性 / 样本过少），需回头检查而不是直接使用"
        )
    if lo <= beta <= hi:
        return f"✅ 落在公开真实数据校准区间 [{lo}, {hi}] 内（中心 {PUBLIC_ELASTICITY_CENTER}）"
    if beta < lo:
        return (
            f"⚠ 比公开校准区间 [{lo}, {hi}] 更陡（{beta:+.3f}）。"
            f"可能原因：① 本仿真的需求函数弹性由仿真器设定，天然可能更大；"
            f"② 水果是标品（按个/盒）而公开数据是蔬菜散称（按 kg），计量口径不同；"
            f"③ 单品固定效应后残余的内生性未完全消除。**不裁剪**，但需在 docs/03 解释差异。"
        )
    return f"⚠ 比公开校准区间 [{lo}, {hi}] 更平（{beta:+.3f}），打折倾向偏保守"


class TruthLeakError(RuntimeError):
    """定价模型试图访问仿真器真值参数时抛出。这条异常的存在本身就是"不作弊"的证据。"""


def _assert_no_truth_access(obj, where: str, allow: Iterable[str] = ()) -> None:
    """扫描输入对象（dict / DataFrame 列名）里是否混入了真值字段。

    注意 `demand_qty` 在 `state["history"]` 里不存在，只在 `day["demand"]` 里存在；
    评测脚本会用 `allow={"demand_qty"}` 显式放行**只用于计分**的那一处。
    """
    allow_set = set(allow)
    keys: set = set()
    if obj is None:
        return
    if isinstance(obj, dict):
        keys = set(obj.keys())
    elif hasattr(obj, "columns"):  # DataFrame
        keys = set(map(str, obj.columns))
    else:
        return
    hit = (keys & TRUTH_KEYS) - allow_set
    if hit:
        raise TruthLeakError(
            f"真值泄漏于 {where}: {sorted(hit)}。定价模型只允许使用可观测台账与观测历史，"
            f"禁止读取仿真器真值参数（base_demand/elasticity/hour_factor/未截断需求）。"
        )


# ---------------------------------------------------------------------------
# 需求模型
# ---------------------------------------------------------------------------


@dataclass
class DemandModel:
    """从"观测历史"里估出来的需求模型，可序列化、可打印、可审计。"""

    alpha: Dict[str, float] = field(default_factory=dict)          # 单品对数截距
    beta: Dict[str, float] = field(default_factory=dict)           # 收缩后的单品弹性
    beta_own: Dict[str, float] = field(default_factory=dict)       # 单品内去均值的原始斜率
    beta_pooled: float = PRIOR_ELASTICITY                          # 池化（within）弹性
    beta_pooled_se: float = float("nan")                           # cluster-by-item 稳健标准误
    dow_coef: Dict[int, float] = field(default_factory=dict)       # 0=周一，中心化到均值 0
    hour_factor: Dict[int, float] = field(default_factory=dict)    # 时段系数（若可获得）
    item_obs: Dict[str, int] = field(default_factory=dict)
    meta: Dict[str, object] = field(default_factory=dict)

    # -- 预测 -----------------------------------------------------------------
    def log_lambda(self, item_code: str, price_ratio: float, dow: int) -> float:
        a = self.alpha.get(item_code)
        b = self.beta.get(item_code)
        if a is None or b is None or price_ratio <= 0:
            return float("nan")
        return a + b * math.log(price_ratio) + self.dow_coef.get(int(dow), 0.0)

    def predict_lambda(self, item_code: str, price_ratio: float, dow: int) -> float:
        v = self.log_lambda(item_code, price_ratio, dow)
        if not np.isfinite(v):
            return 0.0
        return float(np.exp(min(v, 12.0)))  # exp(12)≈1.6e5，纯粹是防爆上界

    def summary(self) -> str:
        m = self.meta
        return (
            f"DemandModel(obs={m.get('n_obs_used')}/{m.get('n_obs_raw')}, "
            f"items={len(self.alpha)}, pooled_beta={self.beta_pooled:.3f}"
            f"±{self.beta_pooled_se:.3f}, "
            f"dropped_stockout={m.get('n_dropped_stockout')}, "
            f"beta_range=[{min(self.beta.values(), default=float('nan')):.2f},"
            f"{max(self.beta.values(), default=float('nan')):.2f}], "
            f"status={m.get('status')})"
        )


def _to_frame(history) -> "object":
    """把 state["history"] 统一成 DataFrame。"""
    import pandas as pd

    if history is None:
        return pd.DataFrame()
    if isinstance(history, pd.DataFrame):
        return history.copy()
    if isinstance(history, (list, tuple)):
        return pd.DataFrame(list(history))
    if isinstance(history, dict):
        return pd.DataFrame(history)
    return pd.DataFrame()


def fit_demand_model(
    history,
    *,
    base_price_map: Optional[Dict[str, float]] = None,
    min_obs_per_item: int = MIN_OBS_PER_ITEM,
    shrinkage_k: float = SHRINKAGE_K,
    censor_ratio: float = CENSOR_RATIO,
    clip: Tuple[float, float] = ELASTICITY_CLIP,
) -> DemandModel:
    """只用观测历史拟合需求模型。详见模块 docstring 第二节。

    必需列：item_code, sold_qty, 以及 (discount_rate 或 unit_price+base_price)
    可选列：biz_date（推 dow）、is_stockout / stockout_flag、on_hand_start、arrival_qty、
            hour_of_day（有则估时段系数）
    `base_price_map`：item_code -> 原价（来自台账），能显著降低价格口径出错的风险。
    """
    _assert_no_truth_access(history, "fit_demand_model(history)")
    import pandas as pd

    df = _to_frame(history)
    model = DemandModel()
    model.meta.update(
        {
            "n_obs_raw": int(len(df)),
            "n_dropped_stockout": 0,
            "n_dropped_noprice": 0,
            "n_dropped_nonpositive": 0,
            "n_items_own_beta": 0,
            "n_items_prior_level": 0,
            "price_basis": "n/a",
            "status": "empty",
        }
    )
    if df.empty:
        return model

    # --- 1. 价格口径：见 price_ratio_series 的口径坑说明 -----------------------
    price, how = price_ratio_series(df, base_price_map)
    if price is None:
        model.meta["status"] = "no_price_column"
        model.meta["price_basis"] = how
        return model
    df["price_ratio"] = price
    model.meta["price_basis"] = how

    df["sold_qty"] = pd.to_numeric(df.get("sold_qty"), errors="coerce")

    # --- 2. 周内效应：从日期取 dow -------------------------------------------
    if "biz_date" in df.columns:
        d = pd.to_datetime(df["biz_date"], errors="coerce")
        df["dow"] = d.dt.dayofweek
    else:
        df["dow"] = 0

    n_raw = len(df)
    df = df[(df["price_ratio"] > 0) & (df["price_ratio"] <= 1.5)].copy()
    model.meta["n_dropped_noprice"] = int(n_raw - len(df))

    # --- 3. 剔除缺货截断样本（内生性处理之二） --------------------------------
    n_before = len(df)
    mask_stock = pd.Series(False, index=df.index)
    for col in ("is_stockout", "stockout_flag"):
        if col in df.columns:
            mask_stock |= df[col].fillna(False).astype(bool)
    if {"on_hand_start", "arrival_qty"}.issubset(df.columns):
        avail = pd.to_numeric(df["on_hand_start"], errors="coerce").fillna(0) + pd.to_numeric(
            df["arrival_qty"], errors="coerce"
        ).fillna(0)
        mask_stock |= (avail > 0) & (df["sold_qty"] >= censor_ratio * avail)
    df = df[~mask_stock].copy()
    model.meta["n_dropped_stockout"] = int(n_before - len(df))

    # 销量必须为正才能取对数
    n_before = len(df)
    df = df[df["sold_qty"] > 0].copy()
    model.meta["n_dropped_nonpositive"] = int(n_before - len(df))
    df["logq"] = np.log(df["sold_qty"].astype(float))
    df["logp"] = np.log(df["price_ratio"].astype(float))

    if len(df) < 8:
        model.meta["status"] = "insufficient_obs"
        model.beta_pooled = PRIOR_ELASTICITY
        model.meta["n_obs_used"] = int(len(df))
        return model

    # --- 4. 池化 within 弹性 + 周内效应（LSDV，cluster-by-item 稳健标准误） ----
    beta_pooled, beta_se, dow_coef = _fit_pooled(df)
    beta_pooled = float(np.clip(beta_pooled, clip[0], clip[1]))
    model.beta_pooled = beta_pooled
    model.beta_pooled_se = float(beta_se)
    model.dow_coef = dow_coef

    # --- 5. 单品内去均值斜率 + 经验贝叶斯收缩 --------------------------------
    for item, g in df.groupby("item_code"):
        item = str(item)
        n_i = int(len(g))
        model.item_obs[item] = n_i
        x = g["logp"].to_numpy(dtype=float)
        y = g["logq"].to_numpy(dtype=float)
        xc, yc = x - x.mean(), y - y.mean()
        var_x = float((xc ** 2).mean())
        if n_i >= min_obs_per_item and var_x > 1e-6:
            raw = float((xc * yc).mean() / var_x)
            model.beta_own[item] = raw
            w = n_i / (n_i + shrinkage_k)
            b = w * float(np.clip(raw, clip[0] * 1.5, clip[1] * 1.5)) + (1 - w) * beta_pooled
            model.meta["n_items_own_beta"] = int(model.meta["n_items_own_beta"]) + 1
        else:
            b = beta_pooled
        model.beta[item] = float(np.clip(b, clip[0], clip[1]))

    # --- 6. 截距：用"该单品在观测到的 dow 下、按 beta_i 折算回原价"的均值 -------
    df["_resid"] = df["logq"] - df["logp"] * df["item_code"].astype(str).map(model.beta).fillna(
        beta_pooled
    ) - df["dow"].map(lambda x: model.dow_coef.get(int(x) if pd.notna(x) else 0, 0.0))
    for item, g in df.groupby("item_code"):
        model.alpha[str(item)] = float(g["_resid"].mean())

    # 出现但没进 df 的单品（比如全程断货）给一个保守的池化水平
    if not df.empty:
        pooled_level = float(df["_resid"].mean())
        for item in df["item_code"].astype(str).unique():
            model.alpha.setdefault(item, pooled_level)

    # --- 7. 时段系数（有 hour_of_day 才估；日粒度定价里它是常数，不改变 argmax） --
    if "hour_of_day" in df.columns:
        model.hour_factor = _estimate_hour_factor(df)

    model.meta["n_obs_used"] = int(len(df))
    model.meta["status"] = "ok"
    return model


def _fit_pooled(df) -> Tuple[float, float, Dict[int, float]]:
    """LSDV 回归 logq ~ logp + C(item) + C(dow)，返回 (beta, cluster-robust SE, dow 系数)。

    statsmodels 不可用时退化为 numpy lstsq（仍然可用，只是没有标准误）。
    """
    try:
        import statsmodels.formula.api as smf

        res = smf.ols("logq ~ logp + C(item_code) + C(dow)", data=df).fit(
            cov_type="cluster", cov_kwds={"groups": df["item_code"].astype(str)}
        )
        beta = float(res.params.get("logp", PRIOR_ELASTICITY))
        se = float(res.bse.get("logp", float("nan")))
        raw = {}
        for j in range(7):
            key = f"C(dow)[T.{j}]"
            raw[j] = float(res.params[key]) if key in res.params else 0.0
    except Exception:  # pragma: no cover - 依赖缺失时的兜底
        beta, se, raw = _fit_pooled_numpy(df)

    # 周内效应中心化：均值 0，这样 alpha 表示"平均一周的那一天"
    mean_c = float(np.mean(list(raw.values())))
    dow_coef = {j: raw[j] - mean_c for j in raw}
    return beta, se, dow_coef


def _fit_pooled_numpy(df) -> Tuple[float, float, Dict[int, float]]:
    y = df["logq"].to_numpy(dtype=float)
    n = len(y)
    items = sorted(df["item_code"].astype(str).unique())
    cols = [np.ones(n), df["logp"].to_numpy(dtype=float)]
    for it in items[1:]:
        cols.append((df["item_code"].astype(str) == it).to_numpy(dtype=float))
    for j in range(1, 7):
        cols.append((df["dow"].astype(int) == j).to_numpy(dtype=float))
    X = np.column_stack(cols)
    coef, *_ = np.linalg.lstsq(X, y, rcond=None)
    raw = {0: 0.0}
    for idx, j in enumerate(range(1, 7)):
        raw[j] = float(coef[2 + len(items) - 1 + idx])
    mean_c = float(np.mean(list(raw.values())))
    return float(coef[1]), float("nan"), {j: raw[j] - mean_c for j in raw}


def _estimate_hour_factor(df) -> Dict[int, float]:
    """小时系数：把每个小时的销量份额除以"均匀分布份额"，再取对数（均值 0）。"""
    g = df.groupby("hour_of_day")["sold_qty"].sum()
    total = float(g.sum())
    if total <= 0:
        return {}
    share = g / total
    base = 1.0 / max(len(share), 1)
    lf = np.log((share / base).clip(lower=1e-6))
    lf = lf - lf.mean()
    return {int(h): float(v) for h, v in lf.items()}


# ---------------------------------------------------------------------------
# 期望销量与价值表
# ---------------------------------------------------------------------------


def expected_sold(lam: float, q: np.ndarray) -> np.ndarray:
    """E[ min(Poisson(lam), q) ]，对数组 q 向量化。

    用 log-pmf（gammaln）避免直接连乘溢出；lam<=0 时需求为 0。
    """
    from scipy.special import gammaln

    q = np.asarray(q, dtype=float)
    if lam is None or not np.isfinite(lam) or lam <= 1e-12:
        return np.zeros_like(q)
    lam = float(min(lam, 300.0))
    ks = np.arange(0, int(q.max()) + 2, dtype=float)
    logpmf = -lam + np.where(ks > 0, ks * math.log(lam), 0.0) - gammaln(ks + 1.0)
    pmf = np.exp(logpmf)
    cdf = np.cumsum(pmf)                       # P(X <= k)
    ex = np.cumsum(ks * pmf)                   # E[X * 1{X<=k}]
    idx = q.astype(int)
    idx = np.clip(idx, 0, len(pmf) - 1)
    s = ex[idx] + q * (1.0 - cdf[idx])
    s[q <= 0] = 0.0
    return np.maximum(s, 0.0)


@dataclass
class ValueTable:
    """倒推价值表 W[j][q] 与最优折扣索引 D[j][q]。"""

    W: np.ndarray          # shape (K+1, qmax+1)
    D: np.ndarray          # shape (K+1, qmax+1)，值是 DISCOUNT_GRID 的下标
    grid: Sequence[float]
    qs: np.ndarray
    base_price: float
    cost_price: float

    def best_discount(self, days_left: int, qty: float) -> float:
        k = int(max(0, min(days_left, self.W.shape[0] - 1)))
        q = int(max(0, min(round(qty), self.W.shape[1] - 1)))
        return float(self.grid[int(self.D[k, q])])

    def continuation_value(self, days_left: int, qty: float) -> float:
        """把 qty 件留到明天（明天 days_left = days_left − 1）的价值。

        days_left == 0 时今天就是最后一天，剩下的一律报废 → 价值 = −成本×数量。
        """
        q = max(float(qty), 0.0)
        if days_left <= 0:
            return -self.cost_price * q
        k = int(min(days_left, self.W.shape[0] - 1))
        return float(np.interp(q, self.qs, self.W[k - 1]))

    def one_step_value(self, days_left: int, qty: float, di: int) -> float:
        """以候选档 grid[di] 出价时，这一批次的"今日收入 + 剩余货的续期价值"。"""
        k = int(max(0, min(days_left, self.W.shape[0] - 1)))
        lam = float(self._lam_for(k, di))
        q = np.array([max(float(qty), 0.0)])
        s = float(expected_sold(lam, q)[0])
        return float(self.base_price * self.grid[di] * s) + self.continuation_value(k, q[0] - s)

    # 供 one_step_value 使用的当日各档需求（由 decide_item 回填）
    _lam_row: Optional[np.ndarray] = None

    def _lam_for(self, k: int, di: int) -> float:
        if self._lam_row is None:
            return 0.0
        return float(self._lam_row[di])


def build_value_table(
    base_price: float,
    cost_price: float,
    lam_by_day: np.ndarray,
    grid: Sequence[float] = DISCOUNT_GRID,
    qmax: int = 40,
) -> ValueTable:
    """有限期倒推价值表。

    参数
    ----
    base_price : 原价（元/件）
    cost_price : 进货成本（元/件），进 W[0] 的报废惩罚
    lam_by_day : shape (K+1, len(grid))，`lam_by_day[j, i]` = 剩余 j 天那天的预测需求
    qmax       : 库存离散化上界
    """
    grid = tuple(grid)
    K = lam_by_day.shape[0] - 1
    qs = np.arange(0, qmax + 1, dtype=float)
    W = np.zeros((K + 1, qmax + 1), dtype=float)
    D = np.zeros((K + 1, qmax + 1), dtype=int)

    for j in range(K + 1):
        cand = np.full(qmax + 1, -np.inf)
        best_i = np.zeros(qmax + 1, dtype=int)
        for di, d in enumerate(grid):
            p = base_price * d
            s = expected_sold(lam_by_day[j, di], qs)
            rem = qs - s
            if j == 0:
                # 今天到期：没卖掉的全部报废，成本 c 记在目标函数里
                val = p * s - cost_price * rem
            else:
                cont = np.interp(rem, qs, W[j - 1])
                val = p * s + cont
            better = val > cand
            cand = np.where(better, val, cand)
            best_i = np.where(better, di, best_i)
        W[j] = cand
        D[j] = best_i
    return ValueTable(W=W, D=D, grid=grid, qs=qs, base_price=float(base_price),
                      cost_price=float(cost_price))


# ---------------------------------------------------------------------------
# 定价器
# ---------------------------------------------------------------------------


@dataclass
class ModelDecision:
    item_code: str
    discount_rate: float
    expected_qty: float
    reason: str
    days_left: int
    remain_qty: float


class ModelPricer:
    """模型定价策略：滚动重估需求模型 + 逐单品/逐批次折扣枚举。"""

    def __init__(
        self,
        refit_every: int = 5,
        grid: Sequence[float] = DISCOUNT_GRID,
        shrink_qmax: int = 40,
        verbose: bool = False,
        fallback_to_rule: bool = True,
        min_discount_rate: Optional[float] = None,
    ) -> None:
        self.refit_every = int(refit_every)
        self.grid = tuple(grid)
        self.shrink_qmax = int(shrink_qmax)
        self.verbose = verbose
        #: 模型还没拟合出来（历史样本不足/无价格变化）时，回退成规则折扣而不是"维持原价"。
        #: 理由：真实店主在没有模型时也会凭经验打折；静默返回 1.00 会让 MODEL 退化成 FIXED，
        #: 在评测里制造"两个策略完全一样"的假象。回退次数会记在 self.n_fallback_days 里如实报告。
        self.fallback_to_rule = bool(fallback_to_rule)
        #: 店主设定的最低折扣线（价格比下限）。与系统侧 app.pricing.min-discount-rate 同口径：
        #: 模型可以不给折扣，但不能给到低于这条线；触底会被抬回并计入 self.n_floor_hits。
        self.min_discount_rate = (float(min_discount_rate)
                                  if min_discount_rate is not None else None)
        self.model: Optional[DemandModel] = None
        self._last_fit_day: int = -10 ** 9
        self._base_price_map: Dict[str, float] = {}
        self.fit_history: List[str] = []
        self.last_decisions: Dict[str, ModelDecision] = {}
        self.n_fallback_days: int = 0
        self.n_model_days: int = 0
        #: 被最低折扣线抬回的次数（可核对的"底线拦住了多少次"）
        self.n_floor_hits: int = 0

    def _apply_floor(self, rate: float) -> float:
        """把折扣率抬到不低于最低折扣线；触底计数。"""
        r = float(rate)
        if self.min_discount_rate is not None and r < self.min_discount_rate:
            self.n_floor_hits += 1
            return float(self.min_discount_rate)
        return r

    def _model_usable(self) -> bool:
        return self.model is not None and bool(self.model.beta) and bool(self.model.alpha)

    def update_base_prices(self, batches) -> None:
        """从当日台账刷新 item_code -> 原价 映射（价格口径的首选依据）。"""
        for b in _to_records(batches):
            code = b.get("item_code")
            bp = b.get("base_price")
            if code is not None and bp is not None:
                try:
                    self._base_price_map[str(code)] = float(bp)
                except (TypeError, ValueError):
                    pass

    # -- 学习 ---------------------------------------------------------------
    def maybe_refit(self, day_index: int, history) -> None:
        """按固定节奏从**观测历史**重估模型（滚动窗口）。"""
        if self.model is not None and (day_index - self._last_fit_day) < self.refit_every:
            return
        m = fit_demand_model(history, base_price_map=self._base_price_map or None)
        if m.meta.get("status") == "ok" or self.model is None:
            self.model = m
        self._last_fit_day = day_index
        self.fit_history.append(f"day{day_index}: {m.summary()}")

    # -- 决策 ---------------------------------------------------------------
    def decide_item(
        self,
        item_code: str,
        batch_rows: List[dict],
        dow_today: int,
        horizon_days: int = MAX_DP_DAYS,
    ) -> Optional[ModelDecision]:
        """对**一个单品**的所有在售批次联合决策：选一个使该单品总期望收益最大的折扣。

        为什么必须联合决策（这是一个真实踩过的坑）
        ------------------------------------------
        早先的实现是"逐批次各自求最优，再取最深折扣作为货架价"。它有一个系统性偏差：
        最临期的那一批会算出很深的折扣，然后把**整个单品**的货架价一起拖下去——
        包括那些还有好几天、本来不该打折的批次。结果是模型过度打折、毛利被拖低。
        现在把"单一货架价"这条业务约束**放进优化里**：对一个候选折扣 d，
        算出该单品所有批次在这个统一价下的总期望收益 `Σ_b [收入_b(d) + 续期价值_b(d)]`，
        再在候选档里取最大者。这样每个批次都能"投自己的一票"，而不是被最急的批次代表。
        """
        if not self._model_usable():
            return None
        rows = [
            b
            for b in batch_rows
            if float(b.get("remain_qty") or 0.0) > 0 and float(b.get("base_price") or 0.0) > 0
        ]
        if not rows:
            return None

        total_q = sum(float(b.get("remain_qty") or 0.0) for b in rows)
        if total_q <= 0:
            return None

        tables: List[ValueTable] = []
        for b in rows:
            q_b = float(b.get("remain_qty") or 0.0)
            base_b = float(b.get("base_price") or 0.0)
            cost_b = float(b.get("cost_price") or 0.0)
            k_b = int(max(0, min(int(b.get("days_left") or 0), horizon_days - 1)))
            share_b = q_b / total_q  # 该批次在单品当日可售量中的份额

            # lam_by_day[j, di]：该批次在"剩余 j 天"那天、候选档 di 下的预测需求
            lam_by_day = np.zeros((k_b + 1, len(self.grid)), dtype=float)
            for j in range(k_b + 1):
                dow_j = int((dow_today + (k_b - j)) % 7)
                for di, d in enumerate(self.grid):
                    lam_by_day[j, di] = (
                        self.model.predict_lambda(item_code, float(d), dow_j) * share_b
                    )
            qmax_b = int(max(2, min(MAX_DP_QTY, math.ceil(q_b) + 1)))
            vt = build_value_table(base_b, cost_b, lam_by_day, self.grid, qmax=qmax_b)
            vt._lam_row = lam_by_day[k_b]  # 供 one_step_value 使用当日各档需求
            vt.days_left_b = k_b            # type: ignore[attr-defined]
            vt.qty_b = q_b                  # type: ignore[attr-defined]
            tables.append(vt)

        # ---- 候选档枚举：单品总期望收益最大 ----
        totals = np.zeros(len(self.grid), dtype=float)
        for vt in tables:
            k_b = int(getattr(vt, "days_left_b", 0))
            q_b = float(getattr(vt, "qty_b", 0.0))
            for di in range(len(self.grid)):
                totals[di] += vt.one_step_value(k_b, q_b, di)
        di_star = int(np.argmax(totals))
        rate = float(self.grid[di_star])

        # ---- 汇总期望销量与反事实对照，生成人话理由 ----
        exp_qty = 0.0
        exp_full = 0.0
        lam_star_sum = 0.0
        for vt in tables:
            k_b = int(getattr(vt, "days_left_b", 0))
            q_b = float(getattr(vt, "qty_b", 0.0))
            lam_star_sum += float(vt._lam_row[di_star])
            exp_qty += float(expected_sold(float(vt._lam_row[di_star]), np.array([q_b]))[0])
            exp_full += float(expected_sold(float(vt._lam_row[0]), np.array([q_b]))[0])

        days_left_min = min(int(b.get("days_left") or 0) for b in rows)
        d_txt = "维持原价" if rate >= 0.999 else f"打{rate * 10:.0f}折"
        # 选的就是原价时"不打折则…"与前一句完全相同，不重复写
        counterfactual = "" if rate >= 0.999 else f"、不打折则{exp_full:.1f}件"
        reason = (
            f"模型定价：单品{len(rows)}个批次共{total_q:.0f}件（最临期剩余{days_left_min}天）；"
            f"估计当日需求{lam_star_sum:.1f}件，{d_txt}期望售出{exp_qty:.1f}件{counterfactual}；"
            f"按含报废成本的单品总期望收益选{d_txt}"
            f"（估计弹性{self.model.beta.get(item_code, float('nan')):.2f}）"
        )
        return ModelDecision(
            item_code=item_code,
            discount_rate=rate,
            expected_qty=exp_qty,
            reason=reason,
            days_left=days_left_min,
            remain_qty=total_q,
        )

    def decide_batch(
        self,
        item_code: str,
        days_left: int,
        remain_qty: float,
        base_price: float,
        cost_price: float,
        dow_today: int,
        item_total_remain: Optional[float] = None,
        horizon_days: int = MAX_DP_DAYS,
    ) -> ModelDecision:
        """单批次决策（保留给单测与后端单点建议用；评测走联合决策 `decide_item`）。"""
        if self.model is None or remain_qty <= 0 or base_price <= 0:
            return ModelDecision(item_code, 1.00, 0.0, "模型未就绪/无库存，维持原价",
                                 int(days_left), float(remain_qty))
        share = 1.0
        if item_total_remain and item_total_remain > 0:
            share = float(min(max(remain_qty / float(item_total_remain), 0.0), 1.0))
        k = int(max(0, min(days_left, horizon_days - 1)))
        lam_by_day = np.zeros((k + 1, len(self.grid)), dtype=float)
        for j in range(k + 1):
            dow_j = int((dow_today + (k - j)) % 7)
            for di, d in enumerate(self.grid):
                lam_by_day[j, di] = self.model.predict_lambda(item_code, float(d), dow_j) * share
        qmax = int(max(2, min(MAX_DP_QTY, math.ceil(float(remain_qty)) + 1)))
        vt = build_value_table(base_price, cost_price, lam_by_day, self.grid, qmax=qmax)
        vt._lam_row = lam_by_day[k]
        rate = vt.best_discount(k, remain_qty)

        q_arr = np.array([float(remain_qty)])
        di_star = list(self.grid).index(rate)
        exp_qty = float(expected_sold(lam_by_day[k, di_star], q_arr)[0])
        exp_full = float(expected_sold(lam_by_day[k, 0], q_arr)[0])
        d_txt = "维持原价" if rate >= 0.999 else f"打{rate * 10:.0f}折"
        counterfactual = "" if rate >= 0.999 else (
            f"不打折则期望售出{exp_full:.1f}件、多留{max(remain_qty - exp_full, 0):.1f}件待报废；")
        reason = (
            f"模型定价：剩余{k}天、现存{remain_qty:.0f}件；"
            f"估计需求{lam_by_day[k, di_star]:.1f}件，{d_txt}期望售出{exp_qty:.1f}件；"
            f"{counterfactual}"
            f"按含报废成本的期望收益选{d_txt}"
            f"（估计弹性{self.model.beta.get(item_code, float('nan')):.2f}）"
        )
        return ModelDecision(item_code, float(rate), exp_qty, reason, k, float(remain_qty))

    def decide_day(self, biz_date, day_index: int, batches) -> Dict[str, float]:
        """一天一次：**按单品联合决策**（单一货架价的约束在优化内部，见 decide_item）。"""
        rows = _to_records(batches)
        if not rows:
            self.last_decisions = {}
            return {}
        dow = _dow(biz_date)

        # ---- 模型不可用时的显式回退：用规则折扣，并计数（绝不静默返回原价）----
        if not self._model_usable():
            if not self.fallback_to_rule:
                self.last_decisions = {}
                return {}
            self.n_fallback_days += 1
            from pricing_rule import rule_decisions_detailed

            best_fb: Dict[str, ModelDecision] = {}
            for code, dec in rule_decisions_detailed(
                    rows, min_discount_rate=self.min_discount_rate).items():
                best_fb[code] = ModelDecision(
                    item_code=code,
                    discount_rate=dec.discount_rate,
                    expected_qty=float("nan"),
                    reason=f"模型样本不足，回退经验规则：{dec.reason}",
                    days_left=0,
                    remain_qty=0.0,
                )
            self.last_decisions = best_fb
            return {c: self._apply_floor(snap_to_grid(d.discount_rate, self.grid))
                    for c, d in best_fb.items()}
        self.n_model_days += 1

        by_item: Dict[str, List[dict]] = {}
        for b in rows:
            by_item.setdefault(str(b.get("item_code")), []).append(b)

        best: Dict[str, ModelDecision] = {}
        for code, brows in by_item.items():
            dec = self.decide_item(code, brows, dow)
            if dec is not None:
                best[code] = dec
        self.last_decisions = best
        return {c: self._apply_floor(snap_to_grid(d.discount_rate, self.grid))
                for c, d in best.items()}

    # -- 对外：给后端/建议表用的完整建议（含 expected_qty 与 reason） ----------
    def suggestions(self) -> List[dict]:
        return [
            {
                "item_code": d.item_code,
                "days_left": d.days_left,
                "remain_qty": d.remain_qty,
                "discount_rate": d.discount_rate,
                "expected_qty": round(d.expected_qty, 4),
                "strategy": "MODEL",
                "reason": d.reason,
            }
            for d in self.last_decisions.values()
        ]


# ---------------------------------------------------------------------------
# 对接 ShopSimulator.step_day(pricing_fn)
# ---------------------------------------------------------------------------


def make_model_pricing_fn(
    pricer: Optional[ModelPricer] = None,
    refit_every: int = 5,
    min_discount_rate: Optional[float] = None,
):
    """包装成 `pricing_fn(biz_date, day_index, state) -> {item_code: discount_rate}`。

    `min_discount_rate`：店主设定的最低折扣线（系统侧默认 0.60），透传给 ModelPricer。
    """
    pricer = pricer or ModelPricer(
        refit_every=refit_every, min_discount_rate=min_discount_rate)

    def _fn(biz_date, day_index, state):
        # 真值边界：state 只允许有 batches(台账) 与 history(观测)，出现真值字段立即报错
        _assert_no_truth_access(state, "state (pricing_fn)")
        _assert_no_truth_access(state.get("batches") if isinstance(state, dict) else None, "state['batches']")
        _assert_no_truth_access(state.get("history") if isinstance(state, dict) else None, "state['history']")
        batches = state.get("batches") if isinstance(state, dict) else None
        pricer.update_base_prices(batches)
        pricer.maybe_refit(day_index, state.get("history") if isinstance(state, dict) else None)
        return pricer.decide_day(biz_date, day_index, batches)

    _fn.strategy_name = "MODEL"  # type: ignore[attr-defined]
    _fn.pricer = pricer  # type: ignore[attr-defined]
    return _fn


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------


def _to_records(obj) -> List[dict]:
    if obj is None:
        return []
    if isinstance(obj, list):
        return [dict(x) for x in obj]
    if hasattr(obj, "to_dict"):
        try:
            return obj.to_dict("records")
        except TypeError:
            return []
    return []


def _dow(biz_date) -> int:
    if isinstance(biz_date, _dt.datetime):
        return biz_date.weekday()
    if isinstance(biz_date, _dt.date):
        return biz_date.weekday()
    try:
        import pandas as pd

        return int(pd.to_datetime(biz_date).dayofweek)
    except Exception:
        return 0


# ---------------------------------------------------------------------------
# 自检：用**自造的小样本**验证估计器与 DP 的方向性，不依赖任何项目数据
# ---------------------------------------------------------------------------


def _make_synthetic_history(seed: int = 7, n_items: int = 6, n_days: int = 60):
    """造一份"已知真值"的小样本，检验估计器能不能把弹性估回来。

    这只是**单元测试夹具**，不参与任何评测结论。
    """
    import pandas as pd

    rng = np.random.default_rng(seed)
    true_eps = {f"i{k}": -(0.8 + 0.35 * k) for k in range(n_items)}
    rows = []
    base = _dt.date(2026, 3, 2)
    for k in range(n_items):
        code = f"i{k}"
        for t in range(n_days):
            dow = (base + _dt.timedelta(days=t)).weekday()
            # 内生的历史价格：库存压力大 -> 折扣深（这正是内生性的来源）
            pressure = rng.random()
            d = 1.00 if pressure < 0.5 else (0.80 if pressure < 0.8 else 0.60)
            lam = 20.0 * (d ** true_eps[code]) * (1.25 if dow >= 5 else 1.0)
            sold = rng.poisson(lam)
            # 故意让一部分日子库存不足 -> 制造缺货截断，检验剔除逻辑真的在工作
            on_hand = max(int(lam * rng.uniform(0.80, 1.30)) + 1, sold)
            sold = min(sold, on_hand)
            stockout = sold >= 0.98 * on_hand and sold > 0
            rows.append(
                dict(
                    biz_date=base + _dt.timedelta(days=t),
                    item_code=code,
                    # 夹具沿用 sim 的 observed_history 口径：discount_rate 是**折扣深度**
                    # （1 − 价格比），用来验证 price_ratio_series() 能正确识别并换算。
                    discount_rate=round(1.0 - d, 4),
                    unit_price=10.0 * d,
                    sold_qty=float(sold),
                    on_hand_start=float(on_hand),
                    arrival_qty=0.0,
                    is_stockout=bool(stockout),
                    remain_end=float(max(on_hand - sold, 0)),
                )
            )
    return pd.DataFrame(rows), true_eps


def _selftest() -> int:
    import pandas as pd

    ok = True
    print("=== pricing_model.py 自检（自造小样本，不依赖项目数据） ===")

    hist, true_eps = _make_synthetic_history()
    model = fit_demand_model(hist)
    print("  " + model.summary())
    print(f"  真值弹性: { {k: round(v, 2) for k, v in true_eps.items()} }")
    print(f"  估计弹性: { {k: round(v, 2) for k, v in model.beta.items()} }")

    # 0) 诊断：between / 朴素 pooled / within 三种估计的对比（冻结"为什么要做组内识别"）
    diag = naive_pooled_ols(hist, base_price_map={"i%d" % k: 10.0 for k in range(6)})
    print(
        f"  诊断估计: 价格口径={diag.get('price_basis')}  between={diag['beta_between']:+.3f}"
        f"(R²={diag['r2_between']:.3f}) pooled={diag['beta_naive_pooled']:+.3f}"
        f"(R²={diag['r2_pooled']:.3f}) within={diag['beta_within']:+.3f}  n={diag['n_obs']}"
    )
    print(f"  与公开真实数据校准区间的对照: {elasticity_plausibility(model.beta_pooled)}")
    diag_ok = np.isfinite(diag["beta_naive_pooled"]) and diag["r2_pooled"] > -0.05
    ok &= diag_ok
    print(f"[{'OK ' if diag_ok else 'FAIL'}] 诊断回归含常数项（R²={diag['r2_pooled']:.3f} 不为负，"
          f"这正是 calib 发现漏常数项设定错误的那个信号）")

    # 1) 每条估计弹性都应为负，且与真值同向
    neg = all(v < 0 for v in model.beta.values())
    ok &= neg
    print(f"[{'OK ' if neg else 'FAIL'}] 所有单品弹性为负")

    # 2) 排序相关性：估计出的弹性序应与真值序一致（方向对了才可能定对价）
    est = [model.beta[c] for c in sorted(true_eps)]
    tru = [true_eps[c] for c in sorted(true_eps)]
    corr = float(np.corrcoef(est, tru)[0, 1])
    good = corr > 0.5
    ok &= good
    print(f"[{'OK ' if good else 'FAIL'}] 估计弹性与真值弹性的相关系数 = {corr:.3f} (>0.5)")

    # 3) 单条估计的绝对误差（收缩会把它拉向池化值，1 个弹性单位内的误差是可接受的）
    errs = [abs(model.beta[c] - true_eps[c]) for c in true_eps]
    within = max(errs) < 0.6
    ok &= within
    print(f"[{'OK ' if within else 'FAIL'}] 最大绝对误差 = {max(errs):.2f} 弹性单位 (<0.6)，均值 {np.mean(errs):.2f}")

    # 4) 缺货截断样本必须被剔除
    dropped = model.meta["n_dropped_stockout"]
    truly_censored = int(hist["is_stockout"].sum())
    drop_ok = dropped == truly_censored
    ok &= drop_ok
    print(f"[{'OK ' if drop_ok else 'FAIL'}] 剔除缺货截断样本 {dropped} 行（应为 {truly_censored}）")

    # 5) 价值表方向性：越接近到期、折扣应越深（或至少不更浅）
    lam_by_day = np.array(
        [[20.0 * (d ** -1.5) for d in DISCOUNT_GRID] for _ in range(5)], dtype=float
    )
    vt = build_value_table(base_price=10.0, cost_price=6.0, lam_by_day=lam_by_day, qmax=40)
    rates = [vt.best_discount(k, 30) for k in range(5)]
    print(f"  价值表在 days_left=0..4 的建议折扣: {rates}")
    mono = all(rates[i] <= rates[i + 1] + 1e-9 for i in range(len(rates) - 1))
    ok &= mono
    print(f"[{'OK ' if mono else 'FAIL'}] 越临期折扣不更浅（报废成本被计入目标函数）")

    # 6) 严重压货 + 最后一天：即使折后价低于成本也应清货（清货优于报废）
    #    这里 q=40 远大于原价下的需求(20)，才构成"必须清货"的场景；
    #    若库存本身就不够卖（λ>q），不打折才是对的，这是模型应有的行为。
    vt2 = build_value_table(base_price=10.0, cost_price=9.5, lam_by_day=lam_by_day, qmax=60)
    last = vt2.best_discount(0, 40)
    sell_ok = last <= 0.70
    ok &= sell_ok
    print(f"[{'OK ' if sell_ok else 'FAIL'}] 压货 40 件、最后一天：折后低于成本仍清货（{last:.2f}）")
    # 反向对照：库存不足时不应无脑打折
    no_panic = vt2.best_discount(0, 6) >= 0.80
    ok &= no_panic
    print(f"[{'OK ' if no_panic else 'FAIL'}] 库存仅 6 件（不够卖）：不打深折（{vt2.best_discount(0, 6):.2f}）")

    # 7) 真值红线：输入里混入真值字段必须抛异常
    leaked = False
    try:
        _assert_no_truth_access({"batches": [], "elasticity": {"i0": -1.2}}, "test")
    except TruthLeakError:
        leaked = True
    ok &= leaked
    print(f"[{'OK ' if leaked else 'FAIL'}] 真值字段（elasticity）混入输入时抛 TruthLeakError")

    leaked2 = False
    try:
        _assert_no_truth_access(pd.DataFrame({"demand_qty": [1.0]}), "test")
    except TruthLeakError:
        leaked2 = True
    ok &= leaked2
    print(f"[{'OK ' if leaked2 else 'FAIL'}] 未截断需求 demand_qty 混入输入时抛 TruthLeakError")

    # 8) 端到端：ModelPricer 在模拟 state 上能出合法折扣档
    pricer = ModelPricer(refit_every=1)
    pricer.maybe_refit(60, hist)
    batches = [
        dict(item_code="i0", batch_no="B1", days_left=2, remain_qty=12, init_qty=20,
             base_price=10.0, cost_price=6.0, shelf_life_days=5),
        dict(item_code="i3", batch_no="B2", days_left=0, remain_qty=25, init_qty=30,
             base_price=10.0, cost_price=6.0, shelf_life_days=7),
    ]
    out = pricer.decide_day("2026-05-01", 60, batches)
    legal = set(out.values()).issubset(set(DISCOUNT_GRID)) and len(out) == 2
    ok &= legal
    print(f"[{'OK ' if legal else 'FAIL'}] 决策落在候选档内: {out}")
    for s in pricer.suggestions():
        print(f"        {s['item_code']}: {s['discount_rate']:.2f} 期望售出 {s['expected_qty']:.1f}")
        print(f"          reason: {s['reason']}")

    print("=== 自检结果:", "全部通过" if ok else "存在失败项", "===")
    return 0 if ok else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="模型定价（MODEL）")
    ap.add_argument("--selftest", action="store_true", help="跑内置自检（自造小样本）")
    args = ap.parse_args()
    if args.selftest:
        raise SystemExit(_selftest())
    ap.print_help()
