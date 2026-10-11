"""环境自检辅助脚本（供 scripts/setup.ps1 调用，保持 PowerShell 侧不含引号陷阱）。

用法:
    python scripts/_pycheck.py          # 打印本解释器里各数据依赖的版本
退出码恒为 0，缺失的依赖打印 MISSING。
"""
import importlib
import sys

PACKAGES = ["numpy", "pandas", "openpyxl", "scipy", "matplotlib", "sklearn", "statsmodels"]


def main() -> int:
    print("PYTHON " + sys.version.split()[0])
    for name in PACKAGES:
        try:
            mod = importlib.import_module(name)
            print(f"{name}={getattr(mod, '__version__', 'ok')}")
        except Exception:
            print(f"{name}=MISSING")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
