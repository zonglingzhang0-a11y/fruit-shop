"""只读导出参考架构 Excel 的全部内容（不改动原文件）。

用法: & 'C:\\Python314\\python.exe' scripts/_read_ref_arch.py
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd

SRC = Path(r"C:\Users\zzl\Downloads\水果店临期定价系统设计.xlsx")


def main() -> int:
    xl = pd.ExcelFile(SRC)
    print(f"文件: {SRC.name}")
    print(f"工作表 {len(xl.sheet_names)} 个: {xl.sheet_names}")
    for name in xl.sheet_names:
        print("\n" + "=" * 100)
        print(f"【工作表】{name}")
        print("=" * 100)
        raw = xl.parse(name, header=None, dtype=object)
        print(f"形状: {raw.shape[0]} 行 × {raw.shape[1]} 列")
        raw = raw.dropna(how="all").dropna(axis=1, how="all")
        for i, row in raw.iterrows():
            cells = []
            for v in row.tolist():
                if v is None or (isinstance(v, float) and pd.isna(v)):
                    continue
                s = str(v).strip()
                if not s or s == "nan":
                    continue
                cells.append(s)
            if cells:
                print(f"  [{i:>3}] " + " | ".join(cells))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
