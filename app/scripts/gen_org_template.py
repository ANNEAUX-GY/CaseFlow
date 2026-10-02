"""生成「员工图谱」Excel 模板 / 示例数据。

用法：
    .venv\\Scripts\\python.exe scripts/gen_org_template.py               # 生成示例（含 4 层级示例数据）
    .venv\\Scripts\\python.exe scripts/gen_org_template.py --blank       # 只生成表头
    .venv\\Scripts\\python.exe scripts/gen_org_template.py --out x.xlsx  # 指定输出路径
"""
import argparse

from openpyxl import Workbook

from _common import DATA_DIR, ensure_dir

HEADERS = ["姓名", "工号", "上级工号", "部门", "职务", "手机", "邮箱"]
DEMO = [
    ("王总", "E001", "", "刑事侦查大队", "领导", "13800000001", "wang@example.com"),
    ("李副总", "E002", "E001", "刑事侦查大队", "副领导", "13800000002", "li@example.com"),
    ("张组长", "E003", "E002", "侦查一组", "组长", "13800000003", "zhang@example.com"),
    ("陈组员", "E004", "E003", "侦查一组", "组员", "13800000004", "chen@example.com"),
    ("刘组员", "E005", "E003", "侦查一组", "组员", "13800000005", "liu@example.com"),
    ("赵组长", "E006", "E002", "侦查二组", "组长", "13800000006", "zhao@example.com"),
    ("孙组员", "E007", "E006", "侦查二组", "组员", "13800000007", "sun@example.com"),
    ("周组员", "E008", "E006", "侦查二组", "组员", "13800000008", "zhou@example.com"),
]


def build(path, blank=False):
    wb = Workbook()
    ws = wb.active
    ws.title = "员工图谱"
    ws.append(HEADERS)
    if not blank:
        for row in DEMO:
            ws.append(list(row))
    widths = [12, 12, 12, 18, 12, 16, 24]
    for i, w in enumerate(widths, start=1):
        ws.column_dimensions[chr(64 + i)].width = w
    for cell in ws[1]:
        cell.font = cell.font.copy(bold=True)
    wb.save(path)
    return path


def run():
    ap = argparse.ArgumentParser()
    ap.add_argument("--blank", action="store_true", help="只输出表头")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    ensure_dir(DATA_DIR)
    out = args.out or str(DATA_DIR / ("员工图谱模板.xlsx" if args.blank else "员工图谱示例.xlsx"))
    build(out, args.blank)
    print(f"已生成：{out}")


if __name__ == "__main__":
    run()
