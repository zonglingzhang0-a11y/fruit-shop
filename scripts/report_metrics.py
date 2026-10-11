"""从最新评测报告读出三策略数字，算出报告/文档要用的派生指标。

为什么单独一个脚本：报告里的"每降 1pp 损耗要付多少毛利"是 README 与演示手册里
反复引用的数字，必须由数据算出来而不是手抄——否则改一次评测就要改四处文档。

用法：
    & 'C:\\Python314\\python.exe' scripts/report_metrics.py

实现说明：**只在"## 3. 主对比表"这一节内解析**（表格形如
`| 指标 | FIXED | RULE | MODEL |`，单元格可能是 `68,369.7 ± 2,381.5`）。
取每个单元格的第一个数字 = 均值；不要跨节抓，否则会抓到附录里的逐种子明细。
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "release" / "evaluation-report.md"

NUM = re.compile(r"([\d,]+(?:\.\d+)?)")


def first_number(cell: str) -> float | None:
    m = NUM.search(cell)
    if not m:
        return None
    try:
        return float(m.group(1).replace(",", ""))
    except ValueError:
        return None


def main_table(text: str) -> dict[str, dict[str, float]]:
    """返回 {指标名: {策略: 均值}}，只解析"主对比表"那一节。"""
    # 定位"## 3. 主对比表"到下一个 "## " 之间
    start = text.find("## 3. 主对比表")
    if start < 0:
        raise SystemExit("报告里找不到'## 3. 主对比表'，请检查 report 结构")
    end = text.find("\n## ", start + 5)
    section = text[start: end if end > 0 else len(text)]

    rows: dict[str, dict[str, float]] = {}
    header: list[str] | None = None
    for line in section.splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 4:
            continue
        if set(cells[1:4]) >= {"FIXED", "RULE", "MODEL"} or {"FIXED", "RULE", "MODEL"} <= set(cells):
            header = cells
            continue
        if header is None or set(cells[0]) <= set("-: "):
            continue
        # 找到三个策略列的位置
        idx = {}
        for name in ("FIXED", "RULE", "MODEL"):
            if name in header:
                idx[name] = header.index(name)
        if len(idx) < 3:
            continue
        metric = cells[0]
        vals = {}
        for name, i in idx.items():
            if i < len(cells):
                v = first_number(cells[i])
                if v is not None:
                    vals[name] = v
        if len(vals) == 3:
            rows[metric] = vals
    return rows


def main() -> int:
    text = REPORT.read_text(encoding="utf-8")
    rows = main_table(text)

    def pick(keyword: str) -> dict[str, float] | None:
        for metric, vals in rows.items():
            if keyword in metric:
                return vals
        return None

    profit = pick("经营毛利")
    scrap = pick("损耗率")
    if not profit or not scrap:
        print("解析失败。当前解析到的指标行：")
        for k in rows:
            print("  -", k)
        return 2

    fixed_p, rule_p, model_p = profit["FIXED"], profit["RULE"], profit["MODEL"]
    fixed_s, rule_s, model_s = scrap["FIXED"], scrap["RULE"], scrap["MODEL"]

    print("=== 从 release/evaluation-report.md 解析（已含最低折扣线约束）===")
    for name, p, s in (("FIXED", fixed_p, fixed_s), ("RULE", rule_p, rule_s), ("MODEL", model_p, model_s)):
        print(f"  {name:6s} 经营毛利={p:>12,.1f} 元   损耗率={s:>6.2f}%")

    print("\n=== 相对 FIXED 的清货代价（每降 1pp 损耗要付多少毛利）===")
    per_pp: dict[str, float] = {}
    for name, p, s in (("RULE", rule_p, rule_s), ("MODEL", model_p, model_s)):
        cost = fixed_p - p
        drop = fixed_s - s
        per_pp[name] = cost / drop if drop else float("nan")
        print(f"  {name:6s} 毛利代价={cost:>10,.0f} 元  损耗率下降={drop:>5.2f} pp  "
              f"每 pp 代价={per_pp[name]:>9,.0f} 元/pp")

    print("\n=== 结论（可直接引用到 README / docs）===")
    print(f"  MODEL vs FIXED：毛利 {model_p - fixed_p:+,.0f} 元（{(model_p - fixed_p) / fixed_p * 100:+.2f}%），"
          f"损耗率 {fixed_s:.2f}% → {model_s:.2f}%（{model_s - fixed_s:+.2f} pp）")
    print(f"  MODEL vs RULE ：毛利 {model_p - rule_p:+,.0f} 元（{(model_p - rule_p) / rule_p * 100:+.2f}%），"
          f"损耗率 {rule_s:.2f}% → {model_s:.2f}%（{model_s - rule_s:+.2f} pp）")
    if per_pp.get("RULE"):
        print(f"  清货效率：MODEL {per_pp['MODEL']:,.0f} 元/pp  vs  RULE {per_pp['RULE']:,.0f} 元/pp "
              f"→ MODEL 便宜 {(1 - per_pp['MODEL'] / per_pp['RULE']) * 100:.1f}%")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
