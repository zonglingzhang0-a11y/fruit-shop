"""业务流程图（新系统 To-Be）：按业务处理单位分泳道（店主 / 店员 / 系统 / 顾客·会员），画出闭环
“到货登记 → 临期识别 → 生成定价建议 → 店主确认/改价 → H5 特价页 / 推送 → 销售回流 → 效果看板”。

内容取自 docs/04 §4（目标流程、三条“不许”）与实际代码（接口、规则、表名），与 tfd_asis.py 成对：
同一套泳道、符号、配色、图例和脚注版式。数据存储编号与 DFD 一致（D2 批次台账、D3 定价建议、
D4 定价决策、D7 推送计划与日志、D8 销售流水、D9 策略日统计）。

用法（仓库根目录）：python3 scripts/mis/tfd_tobe.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from svgkit import FILL, INK, INK2, MUTED, OUT_DIR, Svg  # noqa: E402

W, H = 1600, 900
GREEN = "#2b7a4b"        # 三条“不许”编号、“店主拍板”标签（只用于强调，不承载符号含义）
LANE_FILL = ("#fafbfc", "#ffffff")


def tw(s: str, size: float) -> float:
    """粗略估计文字宽度：中文按 1 个字号，ASCII 按 0.56 个字号。"""
    return sum(size * (0.56 if ord(c) < 128 else 1.0) for c in s)


# ---------------------------------------------------------------- 本图自带的附加形状
def proc(svg: Svg, cx, cy, title, detail="", w=128, h=66):
    """业务处理：圆角矩形，第一行处理名（加粗），下面是做法说明（灰色小字）。返回 (左, 上, 右, 下)。"""
    x, y = cx - w / 2, cy - h / 2
    svg.rect(x, y, w, h, fill=FILL["process"], stroke=INK, sw=1.6, rx=12)
    rows = detail.split("\n") if detail else []
    n = 1 + len(rows)
    lh = 17
    y0 = cy - (n - 1) * lh / 2 + 5
    svg.text(cx, y0, title, size=14, anchor="middle", weight=600)
    for i, r in enumerate(rows):
        svg.text(cx, y0 + (i + 1) * lh, r, size=13, color=INK2, anchor="middle")
    return x, y, x + w, y + h


WAVE = 0.14


def doc(svg: Svg, cx, cy, title, detail="", w=124, h=64):
    """单据 / 报表：底边为波浪线的矩形；第一行名称（加粗），下面是内容说明（灰色小字）。"""
    x, y = cx - w / 2, cy - h / 2
    svg.document(x, y, w, h, "", size=1)
    rows = detail.split("\n") if detail else []
    n = 1 + len(rows)
    lh = 16
    mid = y + (h - 1.15 * h * WAVE) / 2 - 1   # 波浪底边右侧上翘，文字整体略上移
    y0 = mid - (n - 1) * lh / 2 + 5
    svg.text(cx, y0, title, size=14, anchor="middle", weight=600)
    for i, r in enumerate(rows):
        svg.text(cx, y0 + (i + 1) * lh, r, size=12.5, color=INK2, anchor="middle")
    return x, y, x + w, y + h


def doc_bottom(cx, cy, w, h, px):
    """单据波浪底边在横坐标 px 处的纵坐标（箭头从下方接到单据时用）。"""
    x, y = cx - w / 2, cy - h / 2
    wave = h * WAVE
    p = [(x + w, y + h - wave), (x + w * 0.75, y + h - 2.6 * wave), (x + w * 0.25, y + h + 0.8 * wave),
         (x, y + h - wave)]

    def bez(t, k):
        return ((1 - t) ** 3 * p[0][k] + 3 * (1 - t) ** 2 * t * p[1][k] + 3 * (1 - t) * t ** 2 * p[2][k]
                + t ** 3 * p[3][k])

    lo, hi = 0.0, 1.0
    for _ in range(40):            # x(t) 单调递减
        m = (lo + hi) / 2
        if bez(m, 0) > px:
            lo = m
        else:
            hi = m
    return bez((lo + hi) / 2, 1)


def store(svg: Svg, x, y, w, h, sid, name, tables, repeat=False, cell=30):
    """数据存储：左格编号 + 上下两条边、右侧开口；正文为中文名 + 表名（灰色小字）。
    repeat=True 时左端多画一条竖线，表示同一数据存储在图上重复画出（避免长线交叉）。"""
    svg.rect(x, y, w, h, fill=FILL["store"], stroke="none", sw=0)
    svg.line(x, y, x + w, y, stroke=INK, sw=1.6)
    svg.line(x, y + h, x + w, y + h, stroke=INK, sw=1.6)
    svg.line(x, y, x, y + h, stroke=INK, sw=1.6)
    svg.line(x + cell, y, x + cell, y + h, stroke=INK, sw=1.2)
    if repeat:
        svg.line(x + 6, y, x + 6, y + h, stroke=INK, sw=1.2)
    svg.text(x + cell / 2 + (3 if repeat else 0), y + h / 2 + 4.5, sid, size=12, color=INK2, anchor="middle",
             weight=600)
    rows = [(name, 13, INK, 600)] + [(t, 11, INK2, 400) for t in tables]
    lhs = [16 if i == 0 else 13.5 for i in range(len(rows))]
    total = sum(lhs[1:])
    cx = x + cell + (w - cell) / 2
    yy = y + h / 2 - total / 2 + 4.5
    for (s, sz, col, wt), lh in zip(rows, [0] + lhs[1:]):
        yy += lh
        svg.text(cx, yy, s, size=sz, color=col, anchor="middle", weight=wt)
    return x, y, x + w, y + h


def callout(svg: Svg, cx, cy, n, r=11):
    """三条“不许”编号：绿底白字小圆。"""
    svg.raw(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r}" fill="{GREEN}" stroke="#fff" stroke-width="2"/>')
    svg.text(cx, cy + 4.5, str(n), size=13, color="#fff", anchor="middle", weight=700)


def label(svg: Svg, x, y, s, anchor="start", size=13):
    svg.text(x, y, s, size=size, color=INK2, halo=True, anchor=anchor)


def lane(svg: Svg, i, y0, y1, name, role):
    svg.rect(20, y0, W - 40, y1 - y0, fill=LANE_FILL[i % 2], stroke="none", sw=0)
    svg.line(20, y1, W - 20, y1, stroke="#c0c4cc", sw=1.2)
    cy = (y0 + y1) / 2
    svg.entity(32, cy - 30, 92, 42, name, size=15)
    svg.text(78, cy + 32, role, size=12, color=MUTED, anchor="middle")


def h_legend(svg: Svg, x, y):
    """横排图例（与 svgkit / tfd_asis 同一套符号）。y 为符号顶边。"""
    svg.text(x, y + 15, "图例", size=13, color=MUTED)
    x += 44
    gap = 26

    def lab(s):
        nonlocal x
        svg.text(x, y + 15, s, size=13, color=INK2)
        x += tw(s, 13) + gap

    svg.rect(x + 3, y + 3, 34, 20, fill="#dcdfe6", stroke="none", sw=0)
    svg.rect(x, y, 34, 20, fill=FILL["entity"], stroke=INK, sw=1.3)
    x += 44
    lab("业务处理单位（泳道）")
    svg.rect(x, y, 34, 20, fill=FILL["process"], stroke=INK, sw=1.3, rx=6)
    x += 44
    lab("业务处理")
    svg.document(x, y - 1, 34, 23, "", size=1)
    x += 44
    lab("单据/报表")

    def mini_store(rep=False):
        nonlocal x
        svg.rect(x, y, 48, 20, fill=FILL["store"], stroke="none", sw=0)
        svg.line(x, y, x + 48, y, stroke=INK, sw=1.3)
        svg.line(x, y + 20, x + 48, y + 20, stroke=INK, sw=1.3)
        svg.line(x, y, x, y + 20, stroke=INK, sw=1.3)
        svg.line(x + 22, y, x + 22, y + 20, stroke=INK, sw=1)
        if rep:
            svg.line(x + 5, y, x + 5, y + 20, stroke=INK, sw=1)
        x += 58

    mini_store()
    lab("数据存储（左格为编号）")
    mini_store(rep=True)
    lab("左端加竖线：同一存储重复画出")
    svg.arrow([(x, y + 10), (x + 34, y + 10)])
    x += 44
    lab("信息/实物传递")
    svg.arrow([(x, y + 10), (x + 34, y + 10)], dash="6 4")
    x += 44
    lab("次日循环")
    callout(svg, x + 11, y + 10, 1, r=10)
    x += 30
    lab("三条“不许”（见下）")


# ---------------------------------------------------------------- 画布与泳道
svg = Svg(W, H, title="业务流程图（新系统 To-Be）",
          subtitle="系统识别临期、给出带理由的定价建议，店主拍板后才公示和推送；销售回流进看板，每天闭环一轮")

tag = "系统只建议 · 店主拍板"
tx = 32 + tw("业务流程图（新系统 To-Be）", 24) + 8
svg.rect(tx, 21, tw(tag, 15) + 20, 28, fill="#eefaf4", stroke=GREEN, sw=1.4, rx=14)
svg.text(tx + 10, 40, tag, size=15, color=GREEN, weight=600)
svg.text(W - 32, 70, "依据：docs/04 §4 目标流程与三条“不许”、docs/07，以及后端/前端实际代码", size=13, color=MUTED,
         anchor="end")

LANES = [  # (名称, 角色说明, y0, y1)
    ("店主", "决策者", 100, 250),
    ("店员", "执行者", 250, 370),
    ("系统", "工作台 + 后端", 370, 652),
    ("顾客·会员", "H5 匿名 / 会员", 652, 774),
]
for i, (nm, role, y0, y1) in enumerate(LANES):
    lane(svg, i, y0, y1, nm, role)
svg.rect(20, 100, W - 40, 674, fill="none", stroke="#909399", sw=1.4)
svg.line(136, 100, 136, 774, stroke="#c0c4cc", sw=1.2)

# 列中心
X1, X2, X3, X4, X5, X6, X7, X8 = 214, 372, 530, 686, 842, 1004, 1186, 1394
Y_OWN = 175        # 店主泳道中心
Y_CLK = 310        # 店员泳道中心
Y_SYS = 445        # 系统泳道处理行中心
Y_CUS = 713        # 顾客·会员泳道中心
ST, SH = 546, 54   # 数据存储行顶边、高度
SB = ST + SH
SM = ST + SH / 2   # 数据存储行中线

# ---------------------------------------------------------------- 店主泳道
o_gen = proc(svg, X2, Y_OWN, "发起定价", "看清单、选策略\n点「生成」")
DOC_W, DOC_H = 128, 74
d_sug = doc(svg, X3, Y_OWN, "定价建议单", "建议价·折扣档\n每条附理由", w=DOC_W, h=DOC_H)
o_dec = proc(svg, X4, Y_OWN, "确认 / 改价", "采纳·改价·不采纳\n写原因（留痕）")
o_push = proc(svg, X6, Y_OWN, "推送预览与发送", "看高峰时段、看预览\n确认后点「发送」", w=136)
o_eff = proc(svg, X7, Y_OWN, "看板复盘", "对比三种策略\n与推送效果")

# ---------------------------------------------------------------- 店员泳道
c_in = proc(svg, X1, Y_CLK, "到货登记", "单品·到货日\n保质期·数量·进价", h=64)
d_list = doc(svg, X2, Y_CLK, "临期/风险清单", "剩余 ≤2 天\n按天数排序", w=DOC_W, h=DOC_H)
c_sale = proc(svg, X8, Y_CLK, "销售回流登记", "批次·数量·单价\n会员号（可选）", h=64)

# ---------------------------------------------------------------- 系统泳道：处理
s_scan = proc(svg, X2, Y_SYS, "临期识别", "每小时只读扫描\n算新鲜度、风险", h=72)
s_gen = proc(svg, X3, Y_SYS, "生成定价建议", "6 档折扣·底线 0.60\n不涨价·附理由", h=72)
s_dec = proc(svg, X4, Y_SYS, "校验并留痕", "校验底线与次数\n写入决策记录", h=72)
s_h5 = proc(svg, X5, Y_SYS, "H5 特价页", "只显示店主确认价\n在售且有库存", h=72)
s_push = proc(svg, X6, Y_SYS, "生成预览·发送", "每人每天 ≤2 条\n22:00–08:00 静默\n退订会员拦截", h=84, w=136)
d_eff = doc(svg, X7, Y_SYS, "效果看板", "损耗率·毛利·营收\n策略对比·推送转化", w=136, h=76)
s_back = proc(svg, X8, Y_SYS, "回流处理", "扣库存·写流水\n记策略日统计\n3 小时推送归因", h=84)

# ---------------------------------------------------------------- 系统泳道：数据存储
st_batch = store(svg, 152, ST, 236, SH, "D2", "批次台账", ["fruit_batch"])
st_price = store(svg, 512, ST, 196, SH, "D3/4", "定价建议·决策", ["pricing_suggestion", "pricing_decision"],
                 cell=40)
st_push = store(svg, 984, ST, 128, SH, "D7", "推送计划·日志", ["push_task", "push_log"])
st_stat = store(svg, 1124, ST, 156, SH, "D9", "策略日统计", ["strategy_daily_stat"])
st_batch2 = store(svg, 1292, ST, 106, SH, "D2", "批次台账", ["fruit_batch"], repeat=True)
st_sale = store(svg, 1406, ST, 108, SH, "D8", "销售流水", ["sales_record"])

# ---------------------------------------------------------------- 顾客·会员泳道
u_view = proc(svg, X5, Y_CUS, "看当日特价", "H5 匿名访问", h=56)
d_msg = doc(svg, X6, Y_CUS, "推送文案", "站内消息\n优先用确认价", w=DOC_W, h=72)
u_buy = proc(svg, X8, Y_CUS, "到店购买", "会员号可选", h=56)

# ================================================================ 箭头
# ① 到货登记 → D2
svg.arrow([(X1, c_in[3]), (X1, ST)])
label(svg, X1 + 8, 428, "登记批次")
label(svg, X1 + 8, 446, "算到期日")

# ② 临期识别 ↔ D2；识别 → 清单 → 店主
svg.arrow([(340, s_scan[3]), (340, ST)], both=True)
label(svg, 334, 524, "写派生指标", anchor="end", size=12.5)
svg.arrow([(X2, s_scan[1]), (X2, doc_bottom(X2, Y_CLK, DOC_W, DOC_H, X2))])
svg.arrow([(X2, d_list[1]), (X2, o_gen[3])])

# ③ 店主发起 → 生成；D2 → 生成；生成 → D3/4、→ 建议单
gx = (o_gen[2] + s_gen[0]) / 2
svg.arrow([(o_gen[2], Y_OWN), (gx, Y_OWN), (gx, Y_SYS), (s_gen[0], Y_SYS)])
label(svg, gx + 6, 296, "生成请求", size=12.5)
svg.arrow([(st_batch[2], SM), (488, SM), (488, s_gen[3])])
svg.arrow([(560, s_gen[3]), (560, ST)])
label(svg, 566, 524, "写建议", size=12.5)
svg.arrow([(X3, s_gen[1]), (X3, doc_bottom(X3, Y_OWN, DOC_W, DOC_H, X3))])
label(svg, X3 + 6, 336, "建议 + 理由", size=12.5)

# ④ 建议单 → 店主确认 → 校验留痕 → D3/4
svg.arrow([(d_sug[2], Y_OWN), (o_dec[0], Y_OWN)])
svg.arrow([(X4, o_dec[3]), (X4, s_dec[1])])
label(svg, X4 + 6, 296, "最终价 + 原因", size=12.5)
svg.arrow([(660, s_dec[3]), (660, ST)])
label(svg, 666, 524, "写决策", size=12.5)

# ⑤ D3/4 → H5 → 顾客
svg.arrow([(st_price[2], SM), (806, SM), (806, s_h5[3])])
label(svg, 757, SM - 7, "确认价", anchor="middle", size=12.5)
svg.arrow([(874, s_h5[3]), (874, u_view[1])])
label(svg, 868, 632, "当日特价", anchor="end", size=12.5)

# ⑥ 店主 ⇄ 推送；推送 → D7、→ 文案 → 会员
svg.arrow([(984, o_push[3]), (984, s_push[1])])
label(svg, 978, 296, "确认发送", anchor="end", size=12.5)
svg.arrow([(1032, s_push[1]), (1032, o_push[3])])
label(svg, 1038, 336, "预览", size=12.5)
svg.arrow([(1040, s_push[3]), (1040, ST)])
svg.arrow([(960, s_push[3]), (960, d_msg[1])])
label(svg, 966, 632, "发给会员", size=12.5)

# ⑦ 顾客到店 → 店员回流登记 → 回流处理 → D2'/D8/D9/D7
svg.arrow([(d_msg[2], Y_CUS), (u_buy[0], Y_CUS)])
label(svg, (d_msg[2] + u_buy[0]) / 2, Y_CUS - 7, "会员到店", anchor="middle", size=12.5)
svg.arrow([(X5, u_view[3]), (X5, 760), (1362, 760), (1362, u_buy[3])])
svg.arrow([(u_buy[2], Y_CUS), (1554, Y_CUS), (1554, Y_CLK), (c_sale[2], Y_CLK)])
label(svg, 1506, Y_CUS - 7, "结账", anchor="middle", size=12.5)
svg.arrow([(X8, c_sale[3]), (X8, s_back[1])])
svg.arrow([(1342, s_back[3]), (1342, 520), (1262, 520), (1262, ST)])
svg.arrow([(1368, s_back[3]), (1368, ST)])
svg.arrow([(1446, s_back[3]), (1446, ST)])
svg.arrow([(s_back[2], 430), (1526, 430), (1526, 632), (1050, 632), (1050, SB)])
label(svg, 1290, 626, "3 小时内同会员同单品 → 记为转化", anchor="middle", size=12.5)

# ⑧ D9 / D7 → 效果看板 → 店主复盘
svg.arrow([(X7, ST), (X7, doc_bottom(X7, Y_SYS, 136, 76, X7))])
svg.arrow([(1104, ST), (1104, Y_SYS), (d_eff[0], Y_SYS)])
svg.arrow([(X7, d_eff[1]), (X7, o_eff[3])])

# 次日循环（虚线）
svg.arrow([(X7, o_eff[1]), (X7, 120), (X2, 120), (X2, o_gen[1])], dash="6 4")
label(svg, (X2 + X7) / 2, 115, "次日再来一轮：新到货与回流销量进入下一轮识别、定价", anchor="middle", size=12.5)

# ---------------------------------------------------------------- 三条“不许”编号
callout(svg, s_gen[2] - 4, s_gen[1] + 2, 1)
callout(svg, s_scan[2] - 4, s_scan[1] + 2, 2)
callout(svg, s_push[2] - 4, s_push[1] + 2, 3)

# ---------------------------------------------------------------- 图例与脚注
h_legend(svg, 32, 790)

fy = 850
x = 32
head = "三条“不许”（docs/04 §4.2）："
svg.text(x, fy, head, size=14, weight=600)
x += tw(head, 14) - 22   # 弯引号实际比中文字窄
for n, s in ((1, "不自动改价：系统只写建议，挂出的价格只来自店主决策"),
             (2, "不自动淘汰：每小时扫描只算指标，不改批次状态"),
             (3, "不自动推送：店主点「发送」才发")):
    callout(svg, x + 10, fy - 5, n, r=10)
    x += 26
    svg.text(x, fy, s, size=14, color=INK)
    x += tw(s, 14) + 22
svg.text(32, 880, "注：策略 FIXED＝原价，RULE＝剩余天数·剩余库存比例·剩余寿命打分查表，MODEL＝离线脚本产出的模型建议（缺失时退回 RULE）；"
         "推送只是站内消息，不接微信/短信；单品档案、会员、客流等只读数据未画。", size=12.5, color=MUTED)

out = svg.save(OUT_DIR / "tfd_tobe.svg")
print(f"[tfd_tobe] → {out}")
