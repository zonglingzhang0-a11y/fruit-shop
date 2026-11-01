"""业务流程图（现状 As-Is）：按业务处理单位分泳道，画出“到货 → 判断临期 → 定价 → 改价签 / 群发 → 顾客获知 → 收银 → 剩货处理 → 效果判断”。

内容全部取自 docs/04 §2.1–§2.2（角色）与 §3（现状），这些都是 C 类“待访谈验证”的假设，图上明确标注。
不画供应商 / 进货单（docs/04 角色表里没有，§10 把供应链与补货排除在范围外）。

用法（仓库根目录）：python3 scripts/mis/tfd_asis.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from svgkit import FILL, INK, INK2, MUTED, OUT_DIR, Svg  # noqa: E402

W, H = 1600, 900
RED = "#c0392b"          # 痛点编号、假设标签（只用于强调，不承载符号含义）
LANE_FILL = ("#fafbfc", "#ffffff")


def tw(s: str, size: float) -> float:
    """粗略估计文字宽度：中文按 1 个字号，ASCII 按 0.56 个字号。"""
    return sum(size * (0.56 if ord(c) < 128 else 1.0) for c in s)


# ---------------------------------------------------------------- 本图自带的附加形状
def proc(svg: Svg, cx, cy, title, detail="", w=126, h=66):
    """业务处理：圆角矩形，第一行处理名（加粗），下面是做法说明（灰色小字）。"""
    x, y = cx - w / 2, cy - h / 2
    svg.rect(x, y, w, h, fill=FILL["process"], stroke=INK, sw=1.6, rx=12)
    rows = detail.split("\n") if detail else []
    n = 1 + len(rows)
    lh = 17
    y0 = cy - (n - 1) * lh / 2 + 5
    svg.text(cx, y0, title, size=14, anchor="middle", weight=600)
    for i, r in enumerate(rows):
        svg.text(cx, y0 + (i + 1) * lh, r, size=13, color=INK2, anchor="middle")
    return x, y, w, h


def missing_store(svg: Svg, x, y, w, h, label, sid="无"):
    """现状缺失的数据存储：同数据存储符号，但用虚线、无底色，表示“本该有却没有”。"""
    dash = "6 4"
    svg.line(x, y, x + w, y, stroke=INK2, sw=1.5, dash=dash)
    svg.line(x, y + h, x + w, y + h, stroke=INK2, sw=1.5, dash=dash)
    svg.line(x, y, x, y + h, stroke=INK2, sw=1.5, dash=dash)
    svg.line(x + 40, y, x + 40, y + h, stroke=INK2, sw=1.2, dash=dash)
    svg.text(x + 20, y + h / 2 + 5, sid, size=12, color=RED, anchor="middle", weight=600)
    svg.lines(x + 40 + (w - 40) / 2, y + h / 2, label, size=13, color=INK2)


def callout(svg: Svg, cx, cy, n, r=11):
    """痛点编号：红底白字小圆。"""
    svg.raw(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r}" fill="{RED}" stroke="#fff" stroke-width="2"/>')
    svg.text(cx, cy + 4.5, str(n), size=13, color="#fff", anchor="middle", weight=700)


def lane(svg: Svg, i, y0, y1, name, role):
    svg.rect(20, y0, W - 40, y1 - y0, fill=LANE_FILL[i % 2], stroke="none", sw=0)
    svg.line(20, y1, W - 20, y1, stroke="#c0c4cc", sw=1.2)
    cy = (y0 + y1) / 2
    svg.entity(32, cy - 30, 92, 42, name, size=15)
    svg.text(78, cy + 32, role, size=12, color=MUTED, anchor="middle")


def h_legend(svg: Svg, x, y):
    """横排图例（与 svgkit 同一套符号）。y 为符号顶边。"""
    svg.text(x, y + 15, "图例", size=13, color=MUTED)
    x += 44
    gap = 30

    def label(s):
        nonlocal x
        svg.text(x, y + 15, s, size=13, color=INK2)
        x += tw(s, 13) + gap

    svg.rect(x + 3, y + 3, 34, 20, fill="#dcdfe6", stroke="none", sw=0)
    svg.rect(x, y, 34, 20, fill=FILL["entity"], stroke=INK, sw=1.3)
    x += 44
    label("业务处理单位（泳道）")
    svg.rect(x, y, 34, 20, fill=FILL["process"], stroke=INK, sw=1.3, rx=6)
    x += 44
    label("业务处理")
    svg.document(x, y - 1, 34, 23, "", size=1)
    x += 44
    label("单据")
    svg.rect(x, y, 48, 20, fill=FILL["store"], stroke="none", sw=0)
    svg.line(x, y, x + 48, y, stroke=INK, sw=1.3)
    svg.line(x, y + 20, x + 48, y + 20, stroke=INK, sw=1.3)
    svg.line(x, y, x, y + 20, stroke=INK, sw=1.3)
    svg.line(x + 22, y, x + 22, y + 20, stroke=INK, sw=1)
    x += 58
    label("数据存储（左格为存放处）")
    svg.arrow([(x, y + 10), (x + 34, y + 10)])
    x += 44
    label("信息/实物传递")
    d = "5 3"
    svg.line(x, y, x + 34, y, stroke=INK2, sw=1.3, dash=d)
    svg.line(x, y + 20, x + 34, y + 20, stroke=INK2, sw=1.3, dash=d)
    svg.line(x, y, x, y + 20, stroke=INK2, sw=1.3, dash=d)
    x += 44
    label("虚线：现状没有记录")
    callout(svg, x + 11, y + 10, 1, r=10)
    x += 30
    label("痛点编号（见下）")


# ---------------------------------------------------------------- 画布与泳道
svg = Svg(W, H, title="业务流程图（现状 As-Is）",
          subtitle="店里现在怎么处理临期果品：信息只留在记忆、价格牌、微信群和收款 App 里，没有台账")

# 标题右侧的“假设”标签
tag = "（假设，待访谈验证）"
tx = 32 + tw("业务流程图（现状）", 24) + 64  # “As-Is” 实际比估算窄
svg.rect(tx, 21, tw(tag, 15) + 20, 28, fill="#fdf0ef", stroke=RED, sw=1.4, rx=14)
svg.text(tx + 10, 40, tag, size=15, color=RED, weight=600)
svg.text(W - 32, 70, "依据：docs/04 §2 角色表、§3 现状（全部为 C 类假设）", size=13, color=MUTED, anchor="end")

LANES = [  # (名称, 角色说明, y0, y1)
    ("店主", "决策者", 100, 352),
    ("店员", "执行者", 352, 532),
    ("常客会员", "微信群成员", 532, 652),
    ("路过客", "不入系统", 652, 772),
]
for i, (nm, role, y0, y1) in enumerate(LANES):
    lane(svg, i, y0, y1, nm, role)
svg.rect(20, 100, W - 40, 672, fill="none", stroke="#909399", sw=1.4)
svg.line(136, 100, 136, 772, stroke="#c0c4cc", sw=1.2)

# 列中心
C = {i: 222 + (i - 1) * 158 for i in range(1, 10)}
BW = 126
R1 = 181              # 店主泳道第一行（处理）中心
R2T, R2H = 270, 46    # 店主泳道第二行（数据存储）顶边与高度
Y_CLERK = 442         # 店员泳道中心
Y_MEM = 592           # 常客会员泳道中心
Y_PASS = 712          # 路过客泳道中心
TOP = R1 - 33         # 第一行处理框顶边
BOT = R1 + 33         # 第一行处理框底边

# ---------------------------------------------------------------- 店主泳道
proc(svg, C[1], R1, "到货上架", "不登记批次/到货日\n（店主或店员）")
proc(svg, C[2], R1, "判断临期", "看着不新鲜\n才处理")
proc(svg, C[3], R1, "定降价幅度", "凭手感：\n看货·看天·看心情")
proc(svg, C[4], R1, "群发通知", "到货新品、特价\n（或干脆不发）")
svg.document(C[5] - 63, R1 - 30, 126, 62, "微信群消息", size=14)
proc(svg, C[8], R1, "剩货处理", "降价/搭售\n送人自吃/扔掉")
proc(svg, C[9], R1, "判断效果", "群里有人回吗？\n当天有人来吗？")

svg.store(160, R2T, 400, R2H, "到货日 · 新鲜度判断 · 降价手感（只在店主脑子里）", sid="记忆", size=13)
svg.store(790, R2T, 300, R2H, "顾客微信群（消息、回复）", sid="微信", size=13)
missing_store(svg, 1250, R2T, 180, R2H, "批次台账\n损耗记录")

# 第一行顺序
for a, b in ((1, 2), (2, 3), (3, 4), (4, 5)):
    svg.arrow([(C[a] + BW / 2, R1), (C[b] - BW / 2, R1)])
# 记忆
svg.arrow([(C[1], BOT), (C[1], R2T)])
svg.text(C[1] + 8, 247, "靠记", size=13, color=INK2, halo=True)
svg.arrow([(C[2], R2T), (C[2], BOT)])
svg.arrow([(520, R2T), (520, BOT)])
svg.text(C[2] + 8, 247, "凭经验", size=13, color=INK2, halo=True)
# 微信
svg.arrow([(C[5], R1 + 26), (C[5], R2T)])
svg.text(C[5] + 8, 247, "发到群里", size=13, color=INK2, halo=True)
svg.arrow([(1060, R2T), (1060, 124), (C[9], 124), (C[9], TOP)])
svg.text(1270, 118, "看群里有没有人回", size=13, color=INK2, halo=True, anchor="middle")
# 剩货 → 无记录
svg.arrow([(C[8], BOT), (C[8], R2T)], dash="6 4", stroke=INK2)
svg.text(C[8] + 8, 247, "不记录", size=13, color=INK2, halo=True)

# ---------------------------------------------------------------- 店员泳道
proc(svg, C[4], Y_CLERK, "改价格牌", "划掉原价写现价\n或直接写新价")
svg.document(C[5] - 63, Y_CLERK - 32, 126, 64, "价格牌\n手写/白板/打印", size=13)
proc(svg, C[7], Y_CLERK, "收银", "扫码收款")
svg.document(C[8] - 63, Y_CLERK - 32, 126, 64, "收款记录\n（仅金额）", size=13)
svg.store(C[9] - 63, Y_CLERK - 23, 126, 46, "收款 App", sid="App", size=13)

svg.arrow([(585, BOT), (585, Y_CLERK), (C[4] - BW / 2, Y_CLERK)])
svg.text(577, 400, "口头告知新价", size=13, color=INK2, halo=True, anchor="end")
svg.arrow([(C[4] + BW / 2, Y_CLERK), (C[5] - 63, Y_CLERK)])
svg.arrow([(C[7] + BW / 2, Y_CLERK), (C[8] - 63, Y_CLERK)])
svg.arrow([(C[8] + 63, Y_CLERK - 4), (C[9] - 63, Y_CLERK - 4)])
# 收银 → 剩货处理
svg.arrow([(C[7], Y_CLERK - 33), (C[7], R1), (C[8] - BW / 2, R1)])
svg.text(C[7] + 8, 247, "卖不完的货", size=13, color=INK2, halo=True)

# ---------------------------------------------------------------- 顾客泳道
proc(svg, C[6], Y_MEM, "获知特价", "看群消息\n或到店问", h=62)
proc(svg, C[6], Y_PASS, "获知价格", "只看价格牌", h=54)

svg.arrow([(1012, R2T + R2H), (1012, Y_MEM - 31)])
svg.arrow([(C[5], Y_CLERK + 26), (C[5], Y_PASS), (C[6] - BW / 2, Y_PASS)])
svg.arrow([(C[6] + BW / 2, Y_MEM), (1140, Y_MEM), (1140, Y_CLERK + 33)])
svg.text(1107, Y_MEM - 8, "到店购买", size=13, color=INK2, halo=True, anchor="middle")
svg.arrow([(C[6] + BW / 2, Y_PASS), (1200, Y_PASS), (1200, Y_CLERK + 33)])
svg.text(1137, Y_PASS - 8, "购买", size=13, color=INK2, halo=True, anchor="middle")

# ---------------------------------------------------------------- 痛点编号
callout(svg, C[2] + BW / 2 - 4, TOP + 2, 1)
callout(svg, C[3] + BW / 2 - 4, TOP + 2, 2)
callout(svg, 1430 - 4, R2T + 2, 3)

# ---------------------------------------------------------------- 图例与脚注
h_legend(svg, 32, 790)

fy = 852
x = 32
head = "痛点（均为假设，待访谈验证）："
svg.text(x, fy, head, size=14, weight=600)
x += tw(head, 14) + 4
for n, s in ((1, "靠“看着不新鲜”才发现临期，处理时已晚"),
             (2, "降价凭手感：降多了心疼，降少了卖不掉"),
             (3, "到货、剩货都不记录，没有任何一步留下可分析的数据")):
    callout(svg, x + 10, fy - 5, n, r=10)
    x += 26
    svg.text(x, fy, s, size=14, color=INK)
    x += tw(s, 14) + 22
svg.text(32, 880, "注：本图只画店内环节；供应商与进货不在本项目范围（docs/04 §10），不画。", size=12.5, color=MUTED)

out = svg.save(OUT_DIR / "tfd_asis.svg")
print(f"[tfd_asis] → {out}")
