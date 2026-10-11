# -*- coding: utf-8 -*-
"""
规则折扣基线（策略名 RULE）—— 把"店主经验"显式化成一张可审计的决策表。

定位
----
本文件是 `scripts/evaluate_strategies.py` 里三个被比较策略中的**基线 2**。
它回答的问题是："一个凭经验看剩余天数和货架剩余量的店主，会怎么打折？"
它的价值不在聪明，而在**可比性**：任何模型版的改进都必须先赢过它。

业务规则来源
------------
`docs/01-技术方案.md` 第 3 节：
  * 候选折扣档固定为 {1.00, 0.90, 0.80, 0.70, 0.60, 0.50}，不做连续搜索；
  * 不建议涨价，即折扣系数 <= 1.0；
  * days_left = floor((expire_date - biz_date) / 1天)，到货当天 days_left = shelf_life_days。

算法（两步打分，查表出折扣）
---------------------------
1. 紧迫度 urgency —— 由"还剩几天"决定：临期就是硬约束，越近越急。
2. 库存压力 pressure —— 由"剩余量 / 到货量"决定：剩得越多，越需要清货；
   但**只在临期窗口内才计入**（刚到货的新批次不该打折，这是新手最容易写错的地方）。

   score = urgency + pressure (+1 如果剩余可售天数比例 <= 0.20)
   discount = SCORE_TO_RATE[score]

硬性护栏（避免规则出现反常识决策）
----------------------------------
  * 折扣系数必须落在候选档内，且 0.50 <= d <= 1.00；
  * days_left <= 1 且仍有库存 -> 至少 0.70（临期必促）；
  * days_left >= shelf_life_days（刚到货）-> 1.00（新鲜货不打折）。

为什么故意不做得更聪明
----------------------
如果规则版也去估计弹性、也算期望收益，那它就不再是"店主经验"基线，
对照实验就变成了"我的模型 A vs 我的模型 B"。**故意保持它朴素**是方法论要求。

运行自检：
    C:\\Python314\\python.exe scripts/pricing_rule.py --selftest
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Sequence

# ---------------------------------------------------------------------------
# 常量（与 docs/01 第 3 节一致，改动必须同步文档）
# ---------------------------------------------------------------------------

#: 候选折扣档（相对原价），算法只能在其中挑选
DISCOUNT_GRID: Sequence[float] = (1.00, 0.90, 0.80, 0.70, 0.60, 0.50)

#: 临期阈值（天）：与 app.pricing.near-expiry-days 默认值一致
NEAR_EXPIRY_DAYS = 2

#: 库存压力只在"剩余天数 <= PRESSURE_WINDOW_DAYS"时才计入，
#: 否则刚到货的新批次也会被打折。
PRESSURE_WINDOW_DAYS = 3

#: 综合得分（0~8）-> 折扣档。这张表就是"店主经验"的全部内容，刻意写得扁平、可解释。
SCORE_TO_RATE: Dict[int, float] = {
    0: 1.00,
    1: 0.90,
    2: 0.90,
    3: 0.80,
    4: 0.70,
    5: 0.70,
    6: 0.60,
    7: 0.50,
    8: 0.50,
}

#: 临期兜底：还剩 1 天及以内且仍有货，至少打到这个档
LAST_CHANCE_RATE = 0.70


@dataclass(frozen=True)
class RuleDecision:
    """规则版对单个批次给出的决策，附带可读理由（后端 reason 字段直接用它）。"""

    discount_rate: float
    urgency: int
    pressure: int
    reason: str

    @property
    def score(self) -> int:
        return self.urgency + self.pressure


def snap_to_grid(rate: float, grid: Sequence[float] = DISCOUNT_GRID) -> float:
    """把任意折扣系数吸附到最近的候选档（float 安全，永远返回档位原值）。"""
    return min(grid, key=lambda g: abs(g - float(rate)))


def urgency_score(days_left: int) -> int:
    """紧迫度分档（0~4）：由剩余可售天数直接决定。"""
    d = int(days_left)
    if d <= 0:
        return 4
    if d == 1:
        return 3
    if d == 2:
        return 2
    if d == 3:
        return 1
    return 0


def stock_pressure_score(
    remain_qty: float,
    init_qty: Optional[float],
    days_left: int,
    pressure_window_days: int = PRESSURE_WINDOW_DAYS,
) -> int:
    """库存压力分档（0~3）：剩余比例越高，越需要清货。

    只有进入临期窗口（days_left <= pressure_window_days）才计入压力，
    否则"刚到货、库存 100%"会被误判成"压力最大"。
    """
    if int(days_left) > int(pressure_window_days):
        return 0
    if remain_qty is None or remain_qty <= 0:
        return 0
    init = float(init_qty) if init_qty else 0.0
    if init <= 0:
        # 拿不到到货量时退化成绝对量分档（取 20 件为经验满仓量）
        ratio = min(float(remain_qty) / 20.0, 1.0)
    else:
        ratio = max(0.0, min(float(remain_qty) / init, 1.0))
    if ratio >= 0.70:
        return 3
    if ratio >= 0.50:
        return 2
    if ratio >= 0.30:
        return 1
    return 0


def rule_discount(
    days_left: int,
    remain_qty: float,
    init_qty: Optional[float] = None,
    shelf_life_days: Optional[int] = None,
    base_price: Optional[float] = None,
    cost_price: Optional[float] = None,
    min_discount_rate: Optional[float] = None,
) -> RuleDecision:
    """对单个批次给出规则折扣与理由。

    参数
    ----
    days_left        : 剩余可售天数（0 = 今天到期）
    remain_qty       : 现存可售量
    init_qty         : 该批次到货量（用于算剩余比例）
    shelf_life_days  : 保质期天数（用于"刚到货不打折"与剩余寿命比例）
    base_price/cost  : 仅用于理由文案与（可选的）成本护栏提示，不改变决策
    min_discount_rate: **店主设定的最低折扣线**（价格比下限，默认 None = 不限）。
                       与系统侧 `app.pricing.min-discount-rate`（默认 0.60）同一口径：
                       建议价不得低于该比例，触底时在理由里说明"本可以更低但被底线拦住"。
    """
    days_left = int(days_left)
    remain_qty = float(remain_qty or 0.0)

    # --- 无货可卖：不打折（折扣对 0 库存没有意义，返回 1.0 让上游忽略） ---
    if remain_qty <= 0:
        return RuleDecision(1.00, 0, 0, "无现存可售量，无需定价")

    u = urgency_score(days_left)
    p = stock_pressure_score(remain_qty, init_qty, days_left)
    score = u + p

    # 剩余寿命比例极低（<= 20%）再补一档，避免"保质期很长的单品临期了还不促"
    life_note = ""
    if shelf_life_days and shelf_life_days > 0:
        life_ratio = days_left / float(shelf_life_days)
        if life_ratio <= 0.20:
            score += 1
            life_note = f"；剩余寿命仅 {life_ratio:.0%}"

    score = max(0, min(score, max(SCORE_TO_RATE)))
    rate = SCORE_TO_RATE[score]

    # --- 护栏 1：刚到货/未进入临期窗口 -> 原价 ---
    if shelf_life_days and days_left >= int(shelf_life_days) and days_left > PRESSURE_WINDOW_DAYS:
        rate = 1.00
        life_note = "；刚到货未临期"

    # --- 护栏 2：最后一天且有货 -> 至少 LAST_CHANCE_RATE ---
    if days_left <= 1 and rate > LAST_CHANCE_RATE:
        rate = LAST_CHANCE_RATE
        life_note += "；已是最后可售日"

    rate = snap_to_grid(rate)
    rate = float(min(max(rate, DISCOUNT_GRID[-1]), DISCOUNT_GRID[0]))

    # --- 护栏 3：店主设定的最低折扣线（与系统侧 app.pricing.min-discount-rate 同口径）---
    # 触底时保留原始档位并说明，便于事后统计"有多少次是被底线拦住的"。
    floor_note = ""
    if min_discount_rate is not None:
        floor = float(min_discount_rate)
        if rate < floor:
            floor_note = f"；本可降到{rate * 10:.0f}折，已触到店主设定的最低折扣线{floor * 10:.0f}折"
            rate = snap_to_grid(floor, DISCOUNT_GRID) if floor in DISCOUNT_GRID else floor

    # --- 人话理由：店主一眼能看懂，后端 pricing_suggestion.reason 直接落库 ---
    init_txt = f"{init_qty:.0f}" if init_qty else "?"
    pct_txt = (f"{remain_qty / float(init_qty):.0%}" if init_qty else "未知")
    reason = (
        f"规则折扣：剩余{days_left}天(紧迫度{u})，"
        f"库存{remain_qty:.0f}/{init_txt}(剩余{pct_txt}，压力{p})"
        f"{life_note} → 打{rate * 10:.0f}折清货{floor_note}"
    )
    if base_price:
        reason += f"（原价{base_price:.1f}元 → 建议{base_price * rate:.1f}元）"
    if cost_price and base_price and base_price * rate < cost_price:
        reason += "｜注意：折后价低于成本，属清货止损"

    return RuleDecision(rate, u, p, reason)


def rule_decide_day(
    batches: Iterable[dict],
    near_expiry_days: int = NEAR_EXPIRY_DAYS,
    min_discount_rate: Optional[float] = None,
) -> Dict[str, float]:
    """一天一次决策：对当天所有在售批次出价，返回 {item_code: discount_rate}。

    单一货架价约束
    --------------
    同一单品当天只能挂一个价签。同一单品有多个批次时，取**最深的那个折扣**
    （先清最急的货），理由取该批次。这与 `docs/01` 第 3 节"给单品出建议"的口径一致。

    最低折扣线
    ----------
    `min_discount_rate` 与系统侧 `app.pricing.min-discount-rate` 同口径（默认 0.60）。
    注意是**按单品**兜底：先取最深折扣，再抬到不低于底线（因为一只货架只有一个价签）。
    """
    best: Dict[str, RuleDecision] = {}
    for b in batches:
        code = b.get("item_code")
        if not code:
            continue
        days_left = b.get("days_left")
        if days_left is None and b.get("expire_date") is not None:
            days_left = _days_left_from_dates(b.get("arrive_date"), b.get("expire_date"))
        dec = rule_discount(
            days_left=int(days_left or 0),
            remain_qty=float(b.get("remain_qty") or 0.0),
            init_qty=b.get("init_qty"),
            shelf_life_days=b.get("shelf_life_days"),
            base_price=b.get("base_price"),
            cost_price=b.get("cost_price"),
            min_discount_rate=min_discount_rate,
        )
        cur = best.get(code)
        if cur is None or dec.discount_rate < cur.discount_rate:
            best[code] = dec
    return {code: dec.discount_rate for code, dec in best.items()}


def rule_decisions_detailed(
    batches: Iterable[dict],
    min_discount_rate: Optional[float] = None,
) -> Dict[str, RuleDecision]:
    """同 `rule_decide_day`，但返回完整决策对象（带 reason，供建议表落库用）。"""
    best: Dict[str, RuleDecision] = {}
    for b in batches:
        code = b.get("item_code")
        if not code:
            continue
        dec = rule_discount(
            days_left=int(b.get("days_left") or 0),
            remain_qty=float(b.get("remain_qty") or 0.0),
            init_qty=b.get("init_qty"),
            shelf_life_days=b.get("shelf_life_days"),
            base_price=b.get("base_price"),
            cost_price=b.get("cost_price"),
            min_discount_rate=min_discount_rate,
        )
        cur = best.get(code)
        if cur is None or dec.discount_rate < cur.discount_rate:
            best[code] = dec
    return best


def make_rule_pricing_fn(
    near_expiry_days: int = NEAR_EXPIRY_DAYS,
    min_discount_rate: Optional[float] = None,
):
    """包装成 `ShopSimulator.step_day(pricing_fn)` 需要的签名。

    pricing_fn(biz_date, day_index, state) -> {item_code: discount_rate}

    `min_discount_rate` 传店主设定的最低折扣线（系统侧默认 0.60）；
    不传则不限（用于"约束前 vs 约束后"的对照实验）。
    """

    def _fn(biz_date, day_index, state):  # noqa: ARG001 - 签名由 sim 契约冻结
        batches = state.get("batches")
        rows = _to_records(batches)
        return rule_decide_day(
            rows,
            near_expiry_days=near_expiry_days,
            min_discount_rate=min_discount_rate,
        )

    _fn.strategy_name = "RULE"  # type: ignore[attr-defined]
    _fn.min_discount_rate = min_discount_rate  # type: ignore[attr-defined]
    return _fn


# ---------------------------------------------------------------------------
# 小工具（不引入 pandas 依赖，保证本文件可以被单测直接 import）
# ---------------------------------------------------------------------------


def _to_records(obj) -> List[dict]:
    """把 DataFrame / list[dict] / dict-of-lists 统一成 list[dict]。"""
    if obj is None:
        return []
    if isinstance(obj, list):
        return [dict(x) for x in obj]
    if hasattr(obj, "to_dict"):  # pandas.DataFrame
        try:
            return obj.to_dict("records")
        except TypeError:
            return []
    if isinstance(obj, dict):
        keys = list(obj.keys())
        if not keys:
            return []
        n = len(obj[keys[0]]) if hasattr(obj[keys[0]], "__len__") else 0
        return [{k: obj[k][i] for k in keys} for i in range(n)]
    return []


def _days_left_from_dates(arrive_date, expire_date) -> int:
    import datetime as _dt

    def _p(x):
        if isinstance(x, _dt.date):
            return x
        return _dt.date.fromisoformat(str(x)[:10])

    try:
        return (_p(expire_date) - _p(arrive_date)).days
    except Exception:
        return 0


# ---------------------------------------------------------------------------
# 自检：跑几个典型场景，确认规则不会给出反常识的折扣
# ---------------------------------------------------------------------------


def _selftest() -> int:
    print("=== pricing_rule.py 自检（不依赖任何数据文件） ===")
    cases = [
        # (days_left, remain, init, shelf_life, 期望折扣区间, 说明)
        (7, 40, 40, 7, (1.00, 1.00), "刚到货，新鲜货不打折"),
        (5, 40, 40, 7, (1.00, 1.00), "未进临期窗口，不打折"),
        (3, 38, 40, 7, (0.60, 0.90), "进临期窗口，库存几乎没动，压力最大"),
        (2, 10, 40, 7, (0.80, 1.00), "临期但只剩 25% 库存，压力小"),
        (2, 35, 40, 7, (0.50, 0.80), "临期且库存压手，重折"),
        (1, 5, 40, 7, (0.70, 0.70), "最后可售日兜底至少 7 折"),
        (0, 20, 40, 7, (0.50, 0.70), "今天到期，最急"),
        (1, 0, 40, 7, (1.00, 1.00), "无库存，无需定价"),
    ]
    ok = True
    for days_left, remain, init, life, (lo, hi), note in cases:
        d = rule_discount(days_left, remain, init, life, base_price=10.0, cost_price=6.0)
        good = lo - 1e-9 <= d.discount_rate <= hi + 1e-9
        ok &= good
        print(
            f"[{'OK ' if good else 'FAIL'}] days_left={days_left} remain={remain}/{init} "
            f"-> {d.discount_rate:.2f} (期望 {lo:.2f}~{hi:.2f}) | {note}"
        )
        print(f"        reason: {d.reason}")

    # 档位合法性
    rates = {rule_discount(d, 30, 40, 7).discount_rate for d in range(0, 9)}
    in_grid = rates.issubset(set(DISCOUNT_GRID))
    ok &= in_grid
    print(f"[{'OK ' if in_grid else 'FAIL'}] 所有输出都在候选档 {DISCOUNT_GRID} 内：{sorted(rates)}")

    # 单调性：剩余天数越少，折扣不应更浅
    prev = 1.01
    mono = True
    for d in range(7, -1, -1):
        r = rule_discount(d, 40, 40, 7).discount_rate
        mono &= r <= prev + 1e-9
        prev = r
    ok &= mono
    print(f"[{'OK ' if mono else 'FAIL'}] 单调性：剩得越少折扣不更浅")

    # 一天多批次的单品级决策（单一货架价）
    batches = [
        {"item_code": "A", "days_left": 5, "remain_qty": 30, "init_qty": 30, "shelf_life_days": 7},
        {"item_code": "A", "days_left": 1, "remain_qty": 8, "init_qty": 10, "shelf_life_days": 7},
        {"item_code": "B", "days_left": 6, "remain_qty": 20, "init_qty": 20, "shelf_life_days": 7},
    ]
    day = rule_decide_day(batches)
    print(f"       单品级决策: {day}")
    same = day.get("A") == rule_discount(1, 8, 10, 7).discount_rate
    ok &= same
    print(f"[{'OK ' if same else 'FAIL'}] 同单品多批次取最深折扣（先清最急的货）")

    print("=== 自检结果:", "全部通过" if ok else "存在失败项", "===")
    return 0 if ok else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="规则折扣基线（RULE）")
    ap.add_argument("--selftest", action="store_true", help="跑内置场景自检")
    args = ap.parse_args()
    if args.selftest:
        raise SystemExit(_selftest())
    ap.print_help()
