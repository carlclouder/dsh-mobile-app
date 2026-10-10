"""把工作区切换连拍帧合成 GIF（规则 18：切换类场景需 ≥10 帧动图佐证）。

帧序：f1-f3 初始态（全部）→ 点击工作区 → f4-f12 切换后态（过滤为该工作区）。
帧间隔 600ms 与拍摄间隔一致；为控制体积统一缩放到 40%。
"""
from pathlib import Path

from PIL import Image

base = Path(r"D:\AI任务\dsh-mobile-app\test-evidence\workspace-stream-20260916")
frames_dir = base / "frames"
out = base / "workspace_switch.gif"

frames = []
for index in range(1, 13):
    path = frames_dir / f"f{index}.png"
    if not path.exists():
        raise SystemExit(f"缺帧: {path}")
    img = Image.open(path).convert("RGB")
    frames.append(img.resize((432, 960)))

frames[0].save(
    out,
    save_all=True,
    append_images=frames[1:],
    duration=600,
    loop=0,
    optimize=True,
)
print("GIF 写出:", out)
print("帧数:", len(frames), "| 体积(MB):", round(out.stat().st_size / 1024 / 1024, 2))
