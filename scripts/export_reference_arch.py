"""把参考架构 Excel（docs/reference/*.xlsx）导出成可检索的 Markdown。

为什么要它：xlsx 是二进制，在仓库里 diff 不了、也搜不到；
导出一份 Markdown 后，`docs/07` 的逐条对照可以直接引用行内容，
将来 Excel 若有更新，重跑本脚本即可对比差异。

用法：
    python scripts/export_reference_arch.py
输出：
    docs/reference/水果店临期定价系统设计.md
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
REF_DIR = ROOT / "docs" / "reference"
SOURCE = REF_DIR / "水果店临期定价系统设计.xlsx"
OUT = REF_DIR / "水果店临期定价系统设计.md"


def sheet_to_markdown(raw: pd.DataFrame) -> str:
    """把一张工作表转成 Markdown：首行非空行当表头，其余按行输出。

    参考架构的表都是"标题行 + 空行 + 表头 + 数据"，所以这里做宽松处理：
    找到第一行"像表头"的行（非空单元格 >= 2）作为表头，之前的内容作为说明段落。
    """
    df = raw.dropna(how="all").dropna(axis=1, how="all")
    if df.empty:
        return "（空工作表）\n"

    rows = [[("" if pd.isna(v) else str(v).strip()) for v in row] for _, row in df.iterrows()]
    # 去掉每行末尾的空单元格
    rows = [r[: max(1, len(r) - sum(1 for c in reversed(r) if c == ""))] if any(r) else [] for r in rows]

    header_idx = None
    for i, r in enumerate(rows):
        if sum(1 for c in r if c) >= 2:
            header_idx = i
            break

    out: list[str] = []
    if header_idx is None:
        return "\n".join(" ".join(r) for r in rows if r) + "\n"

    # 表头之前的行作为引言
    for r in rows[:header_idx]:
        if any(r):
            out.append("> " + " ".join(c for c in r if c))
    if out:
        out.append("")

    header = rows[header_idx]
    width = max(len(header), max((len(r) for r in rows[header_idx + 1 :]), default=0))
    header = header + [""] * (width - len(header))
    out.append("| " + " | ".join(c or " " for c in header) + " |")
    out.append("|" + "---|" * width)

    for r in rows[header_idx + 1 :]:
        if not any(r):
            continue
        r = r + [""] * (width - len(r))
        out.append("| " + " | ".join((c or "").replace("|", "\\|") for c in r) + " |")
    out.append("")
    return "\n".join(out)


def main() -> int:
    if not SOURCE.exists():
        print(f"找不到参考架构文件：{SOURCE}")
        return 2

    xl = pd.ExcelFile(SOURCE)
    parts: list[str] = []
    parts.append("# 参考架构：水果店临期定价系统设计（Excel 导出）\n")
    parts.append(f"> 来源：`docs/reference/{SOURCE.name}`（原始 Excel 一并保存在同目录，未做任何修改）。")
    parts.append("> 本文件由 `scripts/export_reference_arch.py` 自动导出，**不要手工编辑**；")
    parts.append("> 与当前实现的逐条对照见 [`docs/07-与参考架构的差异与补齐.md`](../07-与参考架构的差异与补齐.md)。\n")
    parts.append(f"工作表共 {len(xl.sheet_names)} 个：{'、'.join(xl.sheet_names)}\n")

    for name in xl.sheet_names:
        parts.append(f"\n---\n\n## {name}\n")
        parts.append(sheet_to_markdown(xl.parse(name, header=None, dtype=object)))

    OUT.write_text("\n".join(parts) + "\n", encoding="utf-8")
    print(f"已写出 {OUT.relative_to(ROOT)}")
    print(f"工作表 {len(xl.sheet_names)} 个：{xl.sheet_names}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
