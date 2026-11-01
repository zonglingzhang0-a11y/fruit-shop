"""管理信息系统（MIS）答辩图的极简 SVG 绘图工具：只用标准库，图形符号按国内 MIS 教材的常用画法。

各图的生成脚本（scripts/mis/*.py）都用这里的形状，保证同一套符号、同一套配色：
  - 业务流程图（TFD）：业务处理单位、业务处理、单据/报表、数据存储、信息传递（箭头）
  - 数据流程图（DFD）：外部实体、处理（带编号）、数据存储（左侧编号、右侧开口）、数据流（带名称的箭头）
  - E-R 图（Chen 记法）：实体（矩形）、联系（菱形）、连线上标 1 / N
  - 功能结构图：树状方框

坐标单位是 SVG 像素；所有文字用正文色，颜色只用来区分符号类别（浅底色），打印成黑白也能分辨（靠形状）。
PNG 由浏览器渲染 SVG 得到（见 scripts/mis/README 或 docs/08）。
"""
from __future__ import annotations

from pathlib import Path

FONT = "'Microsoft YaHei','PingFang SC','Noto Sans CJK SC','WenQuanYi Zen Hei',sans-serif"
INK, INK2, MUTED, LINE = "#303133", "#606266", "#909399", "#606266"
# 符号底色（浅）：外部实体 / 处理 / 数据存储 / 单据 / 联系
FILL = {
    "entity": "#eef4fd",    # 外部实体、业务处理单位、E-R 实体
    "process": "#eefaf4",   # 处理、业务处理
    "store": "#fdf6ec",     # 数据存储
    "doc": "#fdf0ef",       # 单据、报表
    "rel": "#f4f4f5",       # E-R 联系
    "plain": "#ffffff",
}


def esc(s: str) -> str:
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


class Svg:
    def __init__(self, width: int, height: int, title: str | None = None, subtitle: str | None = None):
        self.w, self.h = width, height
        self.parts: list[str] = []
        self.top = 0
        if title:
            self.text(32, 42, title, size=24, weight=600)
            self.top = 56
        if subtitle:
            self.text(32, 70, subtitle, size=14, color=INK2)
            self.top = 86

    # ------------------------------------------------------------------ 基础
    def raw(self, s: str):
        self.parts.append(s)

    def text(self, x, y, s, size=14, color=INK, anchor="start", weight=400, halo=False):
        h = ' paint-order="stroke" stroke="#fff" stroke-width="4" stroke-linejoin="round"' if halo else ""
        self.parts.append(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" fill="{color}" text-anchor="{anchor}" '
                          f'font-weight="{weight}"{h}>{esc(s)}</text>')

    def lines(self, cx, cy, text, size=14, color=INK, weight=400, gap=1.3, anchor="middle", x=None):
        """多行文字，整体在 (cx, cy) 处垂直居中；用 \\n 分行。"""
        rows = str(text).split("\n")
        lh = size * gap
        y0 = cy - (len(rows) - 1) * lh / 2 + size * 0.35
        for i, r in enumerate(rows):
            self.text(cx if x is None else x, y0 + i * lh, r, size=size, color=color, anchor=anchor, weight=weight)

    def rect(self, x, y, w, h, fill="#fff", stroke=LINE, sw=1.5, rx=0, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{rx}" fill="{fill}" '
                          f'stroke="{stroke}" stroke-width="{sw}"{d}/>')

    def line(self, x1, y1, x2, y2, stroke=LINE, sw=1.5, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.parts.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="{stroke}" '
                          f'stroke-width="{sw}"{d}/>')

    # ------------------------------------------------------------------ 箭头（信息传递 / 数据流）
    def arrow(self, points, label=None, label_at=0.5, label_dx=0, label_dy=-6, stroke=LINE, sw=1.5, dash=None,
              size=12, both=False):
        """折线箭头。points=[(x,y), ...]；label 标在第 label_at 段比例处（按段索引+比例）。"""
        pts = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
        d = f' stroke-dasharray="{dash}"' if dash else ""
        start = ' marker-start="url(#arrowStart)"' if both else ""
        self.parts.append(f'<polyline points="{pts}" fill="none" stroke="{stroke}" stroke-width="{sw}"{d} '
                          f'marker-end="url(#arrow)"{start}/>')
        if label:
            # 标签放在最长那一段的中点（或指定比例），默认在线上方
            segs = list(zip(points, points[1:]))
            seg = max(segs, key=lambda s: abs(s[1][0] - s[0][0]) + abs(s[1][1] - s[0][1]))
            (ax, ay), (bx, by) = seg
            lx, ly = ax + (bx - ax) * label_at + label_dx, ay + (by - ay) * label_at + label_dy
            for i, row in enumerate(str(label).split("\n")):
                self.text(lx, ly + i * (size * 1.25), row, size=size, color=INK2, anchor="middle", halo=True)

    # ------------------------------------------------------------------ 符号
    def entity(self, x, y, w, h, label, size=15):
        """外部实体 / 业务处理单位：带右下阴影的方框。"""
        self.rect(x + 5, y + 5, w, h, fill="#dcdfe6", stroke="none", sw=0)
        self.rect(x, y, w, h, fill=FILL["entity"], stroke=INK, sw=1.6)
        self.lines(x + w / 2, y + h / 2, label, size=size, weight=600)

    def process(self, x, y, w, h, label, pid=None, size=14):
        """处理 / 业务处理：圆角矩形；有编号时顶部分一栏写编号（DFD 记法）。"""
        self.rect(x, y, w, h, fill=FILL["process"], stroke=INK, sw=1.6, rx=12)
        if pid:
            self.line(x, y + 24, x + w, y + 24, stroke=INK, sw=1.2)
            self.text(x + w / 2, y + 17, pid, size=12, color=INK2, anchor="middle", weight=600)
            self.lines(x + w / 2, y + 24 + (h - 24) / 2, label, size=size)
        else:
            self.lines(x + w / 2, y + h / 2, label, size=size)

    def store(self, x, y, w, h, label, sid=None, size=13):
        """数据存储：左侧编号格 + 上下两条边、右侧开口。"""
        self.rect(x, y, w, h, fill=FILL["store"], stroke="none", sw=0)
        self.line(x, y, x + w, y, stroke=INK, sw=1.6)
        self.line(x, y + h, x + w, y + h, stroke=INK, sw=1.6)
        self.line(x, y, x, y + h, stroke=INK, sw=1.6)
        if sid:
            self.line(x + 40, y, x + 40, y + h, stroke=INK, sw=1.2)
            self.text(x + 20, y + h / 2 + 5, sid, size=12, color=INK2, anchor="middle", weight=600)
            self.lines(x + 40 + (w - 40) / 2, y + h / 2, label, size=size)
        else:
            self.lines(x + w / 2, y + h / 2, label, size=size)

    def document(self, x, y, w, h, label, size=13):
        """单据 / 报表：底边为波浪线的矩形。"""
        wave = h * 0.14
        d = (f"M{x:.1f},{y:.1f} H{x + w:.1f} V{y + h - wave:.1f} "
             f"C{x + w * 0.75:.1f},{y + h - 2.6 * wave:.1f} {x + w * 0.25:.1f},{y + h + 0.8 * wave:.1f} "
             f"{x:.1f},{y + h - wave:.1f} Z")
        self.parts.append(f'<path d="{d}" fill="{FILL["doc"]}" stroke="{INK}" stroke-width="1.6"/>')
        self.lines(x + w / 2, y + (h - wave) / 2, label, size=size)

    def diamond(self, cx, cy, w, h, label, size=13, fill=None):
        """判断（流程图）/ 联系（E-R 图）。"""
        pts = f"{cx:.1f},{cy - h / 2:.1f} {cx + w / 2:.1f},{cy:.1f} {cx:.1f},{cy + h / 2:.1f} {cx - w / 2:.1f},{cy:.1f}"
        self.parts.append(f'<polygon points="{pts}" fill="{fill or FILL["rel"]}" stroke="{INK}" stroke-width="1.6"/>')
        self.lines(cx, cy, label, size=size)

    def box(self, x, y, w, h, label, size=14, weight=400, fill="#fff", rx=4, sw=1.5):
        """普通方框（功能结构图、E-R 实体）。"""
        self.rect(x, y, w, h, fill=fill, stroke=INK, sw=sw, rx=rx)
        self.lines(x + w / 2, y + h / 2, label, size=size, weight=weight)

    def vbox(self, x, y, w, h, label, size=14, weight=400, fill="#fff"):
        """竖排文字方框（功能结构图的叶子模块常用竖排）。"""
        self.rect(x, y, w, h, fill=fill, stroke=INK, sw=1.4, rx=3)
        chars = list(str(label))
        lh = size * 1.18
        y0 = y + h / 2 - (len(chars) - 1) * lh / 2 + size * 0.35
        for i, c in enumerate(chars):
            self.text(x + w / 2, y0 + i * lh, c, size=size, anchor="middle", weight=weight)

    # ------------------------------------------------------------------ 输出
    def legend(self, x, y, items, title="图例", size=13):
        """items: [(kind, 说明)]，kind ∈ entity/process/store/doc/diamond/arrow/box。"""
        self.text(x, y, title, size=size, color=MUTED)
        yy = y + 16
        for kind, desc in items:
            if kind == "entity":
                self.rect(x + 3, yy + 3, 34, 20, fill="#dcdfe6", stroke="none", sw=0)
                self.rect(x, yy, 34, 20, fill=FILL["entity"], stroke=INK, sw=1.3)
            elif kind == "process":
                self.rect(x, yy, 34, 20, fill=FILL["process"], stroke=INK, sw=1.3, rx=6)
            elif kind == "store":
                self.rect(x, yy, 34, 20, fill=FILL["store"], stroke="none", sw=0)
                self.line(x, yy, x + 34, yy, stroke=INK, sw=1.3)
                self.line(x, yy + 20, x + 34, yy + 20, stroke=INK, sw=1.3)
                self.line(x, yy, x, yy + 20, stroke=INK, sw=1.3)
            elif kind == "doc":
                self.document(x, yy, 34, 22, "", size=1)
            elif kind == "diamond":
                self.diamond(x + 17, yy + 10, 34, 20, "", size=1)
            elif kind == "box":
                self.rect(x, yy, 34, 20, fill="#fff", stroke=INK, sw=1.3, rx=3)
            elif kind == "arrow":
                self.arrow([(x, yy + 10), (x + 34, yy + 10)])
            self.text(x + 44, yy + 15, desc, size=size, color=INK2)
            yy += 30
        return yy

    def save(self, path: Path | str):
        defs = (
            '<defs>'
            f'<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" '
            f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{LINE}"/></marker>'
            f'<marker id="arrowStart" viewBox="0 0 10 10" refX="1" refY="5" markerWidth="8" markerHeight="8" '
            f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{LINE}"/></marker>'
            '</defs>'
        )
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" width="{self.w}" '
               f'height="{self.h}" font-family="{FONT}">{defs}<rect width="{self.w}" height="{self.h}" fill="#fff"/>'
               + "\n".join(self.parts) + "</svg>")
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(svg, encoding="utf-8")
        return path


OUT_DIR = Path(__file__).resolve().parent.parent.parent / "docs" / "figs" / "mis"
