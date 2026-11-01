"""损耗率–经营毛利取舍图：同样降损耗，哪个策略少亏（答辩主图）。

数据只来自 release/evaluation-report.md 附录 A 的逐种子结果（20 个种子 × 3 个策略），
本脚本不重跑仿真、不依赖第三方库（只用标准库），输出 SVG；PNG 用浏览器把 SVG 截图得到。

读法：
  - 横轴 = 损耗率（越靠左越好），纵轴 = 经营毛利（越高越好），每个小点是一个种子；
  - 灰线 = FIXED（不降价）与 RULE（经验折扣）均值的连线，代表"凭经验在两者之间取舍"能达到的位置；
  - MODEL 落在灰线上方 = 同样的损耗率下毛利更高。逐种子算"高出灰线多少"，给出均值与胜负。

用法:
    python scripts/plot_tradeoff.py                       # 写 release/figs/eval_tradeoff.svg
    python scripts/plot_tradeoff.py --out some/where.svg
"""
from __future__ import annotations

import argparse
import math
import re
import statistics as st
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "release" / "evaluation-report.md"
DEFAULT_OUT = ROOT / "release" / "figs" / "eval_tradeoff.svg"

# 与系统看板一致的策略配色（已用 dataviz 校验脚本验过三色的色盲可分辨性）；文字一律用正文色
STRATEGIES = [
    ("FIXED", "固定价（不降价）", "#2a78d6"),
    ("RULE", "规则折扣（店主经验）", "#eb6834"),
    ("MODEL", "模型定价（本项目）", "#1baf7a"),
]
INK, INK2, MUTED, GRID = "#303133", "#606266", "#909399", "#ebeef5"
HALO = 'paint-order="stroke" stroke="#fff" stroke-width="5" stroke-linejoin="round"'  # 压在点上的文字加白描边
FONT = "'Microsoft YaHei','PingFang SC','Noto Sans CJK SC','Noto Sans SC',sans-serif"

ROW = re.compile(r"\| (\d{8}) \| (FIXED|MODEL|RULE) \| ([\d,.-]+) \| [\d,.-]+ \| [\d,.-]+ \| ([\d.]+)% \|")


def load(report: Path) -> dict[str, dict[str, tuple[float, float]]]:
    """seed → strategy → (经营毛利, 损耗率%)。"""
    rows: dict[str, dict[str, tuple[float, float]]] = {}
    for line in report.read_text(encoding="utf-8").splitlines():
        m = ROW.match(line)
        if m:
            rows.setdefault(m[1], {})[m[2]] = (float(m[3].replace(",", "")), float(m[4]))
    complete = {s: d for s, d in rows.items() if len(d) == 3}
    if not complete:
        raise SystemExit(f"在 {report} 里没找到附录 A 的逐种子结果")
    return complete


def summarize(rows):
    gaps, wins = [], 0
    for d in rows.values():
        (pf, sf), (pr, sr), (pm, sm) = d["FIXED"], d["RULE"], d["MODEL"]
        line = pf + (pr - pf) * (sf - sm) / (sf - sr)  # 该种子 FIXED→RULE 连线在 MODEL 损耗率处的毛利
        gaps.append(pm - line)
        wins += pm > line
    means = {k: (st.mean(d[k][0] for d in rows.values()), st.mean(d[k][1] for d in rows.values()))
             for k, _, _ in STRATEGIES}
    return gaps, wins, means


def nice_ticks(lo, hi, n=5):
    raw = (hi - lo) / n
    mag = 10 ** math.floor(math.log10(raw))
    step = min((s * mag for s in (1, 2, 2.5, 5, 10) if s * mag >= raw), default=10 * mag)
    ticks = [math.floor(lo / step) * step]
    while ticks[-1] < hi:  # 最后一个刻度必须盖住最大值，否则点会画到绘图区外
        ticks.append(round(ticks[-1] + step, 10))
    return ticks


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def render(rows) -> str:
    gaps, wins, means = summarize(rows)
    n = len(rows)
    W, H = 1200, 760
    L, R, T, B = 96, 250, 128, 92
    pw, ph = W - L - R, H - T - B

    xs = [v[1] for d in rows.values() for v in d.values()]
    ys = [v[0] for d in rows.values() for v in d.values()]
    xt = nice_ticks(min(xs) - 0.5, max(xs) + 0.5, 6)
    yt = nice_ticks(min(ys) - 1500, max(ys) + 1500, 5)
    x0, x1, y0, y1 = xt[0], xt[-1], yt[0], yt[-1]

    def X(v):
        return L + (v - x0) / (x1 - x0) * pw

    def Y(v):
        return T + ph - (v - y0) / (y1 - y0) * ph

    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
           f'font-family="{FONT}">',
           f'<rect width="{W}" height="{H}" fill="#ffffff"/>']
    gap_mean = st.mean(gaps)
    out.append(f'<text x="{L}" y="44" font-size="28" font-weight="600" fill="{INK}">'
               f'同样的损耗率，模型定价比凭经验取舍平均多赚 {gap_mean:,.0f} 元</text>')
    out.append(f'<text x="{L}" y="76" font-size="16" fill="{INK2}">{n} 个随机种子 × 30 天测试期，每个小点是一个种子；'
               f'越靠左损耗越低，越靠上毛利越高。{wins}/{n} 个种子上模型定价都在灰线上方。</text>')

    # 网格与坐标轴（细、实线、退后）
    for t in yt:
        out.append(f'<line x1="{L}" x2="{L + pw}" y1="{Y(t):.1f}" y2="{Y(t):.1f}" stroke="{GRID}" stroke-width="1"/>')
        out.append(f'<text x="{L - 10}" y="{Y(t) + 5:.1f}" font-size="14" fill="{MUTED}" text-anchor="end">'
                   f'{t / 10000:g}万</text>')
    for t in xt:
        out.append(f'<line x1="{X(t):.1f}" x2="{X(t):.1f}" y1="{T}" y2="{T + ph}" stroke="{GRID}" stroke-width="1"/>')
        out.append(f'<text x="{X(t):.1f}" y="{T + ph + 24}" font-size="14" fill="{MUTED}" text-anchor="middle">{t:g}%</text>')
    out.append(f'<text x="{L + pw / 2}" y="{H - 36}" font-size="15" fill="{INK2}" text-anchor="middle">'
               f'损耗率（报废 ÷ 进货）← 越低越好</text>')
    out.append(f'<text transform="translate(28,{T + ph / 2}) rotate(-90)" font-size="15" fill="{INK2}" '
               f'text-anchor="middle">经营毛利（元，已扣报废成本）↑ 越高越好</text>')

    # 经验折中线：FIXED 均值 ↔ RULE 均值
    (pf, sf), (pr, sr), (pm, sm) = means["FIXED"], means["RULE"], means["MODEL"]
    out.append(f'<line x1="{X(sf):.1f}" y1="{Y(pf):.1f}" x2="{X(sr):.1f}" y2="{Y(pr):.1f}" '
               f'stroke="#b1b3b8" stroke-width="2"/>')
    line_at_m = pf + (pr - pf) * (sf - sm) / (sf - sr)

    # 逐种子小点（2px 白环，重叠处仍可分辨）
    for key, _, color in STRATEGIES:
        for d in rows.values():
            p, s = d[key]
            out.append(f'<circle cx="{X(s):.1f}" cy="{Y(p):.1f}" r="5" fill="{color}" fill-opacity="0.55" '
                       f'stroke="#fff" stroke-width="2"/>')

    # MODEL 高出灰线的那段
    out.append(f'<line x1="{X(sm):.1f}" x2="{X(sm):.1f}" y1="{Y(line_at_m):.1f}" y2="{Y(pm) + 10:.1f}" '
               f'stroke="{INK}" stroke-width="1.5"/>')
    out.append(f'<circle cx="{X(sm):.1f}" cy="{Y(line_at_m):.1f}" r="4" fill="#fff" stroke="{INK}" stroke-width="1.5"/>')
    mid = (Y(line_at_m) + Y(pm)) / 2
    out.append(f'<text x="{X(sm) - 14:.1f}" y="{mid - 4:.1f}" font-size="15" fill="{INK}" text-anchor="end" {HALO}>'
               f'同损耗率下</text>')
    out.append(f'<text x="{X(sm) - 14:.1f}" y="{mid + 18:.1f}" font-size="16" font-weight="600" fill="{INK}" '
               f'text-anchor="end" {HALO}>平均 +{gap_mean:,.0f} 元</text>')

    # 均值大点 + 直接标注（文字用正文色，颜色只在点上）
    for key, label, color in STRATEGIES:
        p, s = means[key]
        out.append(f'<circle cx="{X(s):.1f}" cy="{Y(p):.1f}" r="10" fill="{color}" stroke="#fff" stroke-width="3"/>')
        out.append(f'<text x="{X(s) + 16:.1f}" y="{Y(p) - 4:.1f}" font-size="16" font-weight="600" fill="{INK}" {HALO}>'
                   f'{esc(label)}</text>')
        out.append(f'<text x="{X(s) + 16:.1f}" y="{Y(p) + 16:.1f}" font-size="14" fill="{INK2}" {HALO}>'
                   f'损耗 {s:.2f}% · 毛利 {p:,.0f} 元</text>')

    # 图例（≥2 个系列常驻）
    lx = L + pw + 28
    out.append(f'<text x="{lx}" y="{T + 6}" font-size="14" fill="{MUTED}">图例</text>')
    for i, (key, label, color) in enumerate(STRATEGIES):
        yy = T + 36 + i * 30
        out.append(f'<circle cx="{lx + 8}" cy="{yy - 5}" r="7" fill="{color}" stroke="#fff" stroke-width="2"/>')
        out.append(f'<text x="{lx + 24}" y="{yy}" font-size="14" fill="{INK}">{esc(label)}</text>')
    yy = T + 36 + 3 * 30
    out.append(f'<circle cx="{lx + 8}" cy="{yy - 5}" r="4" fill="{MUTED}" fill-opacity="0.55"/>')
    out.append(f'<text x="{lx + 24}" y="{yy}" font-size="14" fill="{INK2}">小点 = 单个种子</text>')
    out.append(f'<text x="{lx + 24}" y="{yy + 22}" font-size="14" fill="{INK2}">大点 = {n} 个种子均值</text>')
    yy += 56
    out.append(f'<line x1="{lx}" x2="{lx + 16}" y1="{yy - 5}" y2="{yy - 5}" stroke="#b1b3b8" stroke-width="2"/>')
    out.append(f'<text x="{lx + 24}" y="{yy}" font-size="14" fill="{INK2}">灰线 = 在不降价与</text>')
    out.append(f'<text x="{lx + 24}" y="{yy + 20}" font-size="14" fill="{INK2}">经验折扣之间取舍</text>')
    out.append(f'<text x="{lx + 24}" y="{yy + 40}" font-size="14" fill="{INK2}">能达到的位置</text>')

    out.append(f'<text x="{L}" y="{H - 10}" font-size="12" fill="{MUTED}">数据：release/evaluation-report.md 附录 A'
               f'（同一批顾客、同一初始库存、同一进货量的仿真对照）；生成：scripts/plot_tradeoff.py</text>')
    out.append("</svg>")
    return "\n".join(out)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--report", default=str(REPORT))
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    args = ap.parse_args(argv)
    rows = load(Path(args.report))
    gaps, wins, means = summarize(rows)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(render(rows), encoding="utf-8")
    print(f"[tradeoff] {len(rows)} 个种子；MODEL 高出经验折中线 平均 {st.mean(gaps):,.0f} 元"
          f"（{min(gaps):,.0f} ~ {max(gaps):,.0f}），{wins}/{len(rows)} 个种子在线上方 → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
