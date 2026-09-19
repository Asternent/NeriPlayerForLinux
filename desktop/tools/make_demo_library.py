#!/usr/bin/env python3
"""生成用于运行测试的本地音乐库（带标签与内嵌封面的 mp3/flac）。"""

import math
import os
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFont

ROOT = sys.argv[1] if len(sys.argv) > 1 else "work/demo-music"
COVERS = os.path.join(ROOT, ".covers")
os.makedirs(COVERS, exist_ok=True)

ALBUMS = [
    {
        "album": "夜航星",
        "artist": "风又音理",
        "color": (72, 61, 139),
        "accent": (201, 168, 251),
        "tracks": [
            ("夜航星", 220.0),
            ("星屑のワルツ", 261.6),
            ("雨のち晴れ", 293.7),
        ],
    },
    {
        "album": "夏日回声",
        "artist": "海滨信号",
        "color": (0, 105, 148),
        "accent": (140, 220, 235),
        "tracks": [
            ("夏日回声", 329.6),
            ("海岸线", 349.2),
            ("潮汐记忆", 392.0),
        ],
    },
    {
        "album": "无声电台",
        "artist": "凌晨四点",
        "color": (120, 40, 70),
        "accent": (240, 170, 190),
        "tracks": [
            ("无声电台", 174.6),
            ("凌晨四点", 196.0),
            ("静默频率", 207.7),
            ("失落讯号", 233.1),
        ],
    },
]


def make_cover(path: str, title: str, color, accent) -> None:
    size = 600
    image = Image.new("RGB", (size, size), color)
    draw = ImageDraw.Draw(image)
    for y in range(size):
        ratio = y / size
        blend = tuple(
            int(color[i] + (accent[i] - color[i]) * (0.25 + 0.5 * math.sin(ratio * math.pi)))
            for i in range(3)
        )
        draw.line([(0, y), (size, y)], fill=blend)
    for i in range(6):
        r = 60 + i * 45
        draw.ellipse(
            [size / 2 - r, size / 2 - r, size / 2 + r, size / 2 + r],
            outline=(255, 255, 255, 40),
        )
    try:
        font = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", 58)
    except Exception:
        font = ImageFont.load_default()
    draw.text((44, size - 118), title, fill=(255, 255, 255), font=font)
    image.save(path, "JPEG", quality=90)


def encode(track_path: str, cover: str, meta: dict, duration: int, freq: float) -> None:
    command = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi",
        "-i", f"sine=frequency={freq}:duration={duration}:sample_rate=44100",
        "-i", cover,
        "-map", "0:a", "-map", "1:v", "-c:a", "libmp3lame", "-b:a", "192k",
        "-c:v", "mjpeg", "-disposition:v", "attached_pic",
        "-id3v2_version", "3",
        "-metadata", f"title={meta['title']}",
        "-metadata", f"artist={meta['artist']}",
        "-metadata", f"album={meta['album']}",
        "-metadata", f"track={meta['track']}",
        track_path,
    ]
    subprocess.run(command, check=True)


def main() -> None:
    count = 0
    for album in ALBUMS:
        cover = os.path.join(COVERS, f"{album['album']}.jpg")
        make_cover(cover, album["album"], album["color"], album["accent"])
        album_dir = os.path.join(ROOT, album["artist"], album["album"])
        os.makedirs(album_dir, exist_ok=True)
        for index, (title, freq) in enumerate(album["tracks"], start=1):
            target = os.path.join(album_dir, f"{index:02d} {title}.mp3")
            encode(
                target,
                cover,
                {"title": title, "artist": album["artist"], "album": album["album"], "track": index},
                duration=150 + index * 12,
                freq=freq,
            )
            count += 1
            if index == 1:
                lrc = os.path.splitext(target)[0] + ".lrc"
                lines = [f"[ti:{title}]", f"[ar:{album['artist']}]", "[offset:0]"]
                for step in range(1, 13):
                    seconds = step * 8
                    lines.append(f"[{seconds // 60:02d}:{seconds % 60:02d}.00]这是第 {step} 句演示歌词 —— {title}")
                with open(lrc, "w", encoding="utf-8") as handle:
                    handle.write("\n".join(lines) + "\n")
    print(f"generated {count} tracks under {ROOT}")


if __name__ == "__main__":
    main()
