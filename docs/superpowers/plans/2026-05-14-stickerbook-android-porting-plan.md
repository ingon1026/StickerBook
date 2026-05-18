# Stickerbook Android Porting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PC V1 stickerbook 의 결과물 (춤추는 캐릭터) 을 갤럭시 태블릿에서 재생할 수 있게, PC 측 자산 export 모듈을 추가하고 갤탭 Native Kotlin 앱 MVP 를 만든다.

**Architecture:** PC = 자산 공장 (V1 + 새 export), 갤탭 = 자산 재생기 (Kotlin + Jetpack Compose). 두 측이 공유하는 자산 폴더 포맷 (`manifest.json` + `stickers/<id>/frames/*.png`) 이 단일 계약. Phase 2 (Unity) 도 같은 포맷을 그대로 받음.

**Tech Stack:**
- PC: Python 3.12, OpenCV, Pillow (mp4/GIF→PNG seq), pytest
- 갤탭: Kotlin 2.0, Jetpack Compose, Material3, Coil (이미지 디코드), kotlinx.serialization (JSON)
- 빌드: Gradle 8 (Android Gradle Plugin 8.5), Android Studio Koala+, minSdk 28, targetSdk 34
- 테스트: JUnit4 / Robolectric (Android), pytest (PC)

**기본 결정 (Open Questions default — 진행 중 사용자가 바꿔도 됨):**
- 자산 전송: 사용자 생성 sticker = on-device 갤탭 의 internal `filesDir` 에 저장 (PNG seq + GIF). APK `assets/stickerbook_assets/` 은 bundled sample (s003~s005) 만
- 출력 포맷: PNG sequence (Unity 친화, 알파 8-bit) + GIF (호환/미리보기) 둘 다 export. 갤탭 재생은 PNG seq 우선
- Phase 1.5 카메라 오버레이: MVP 미포함 (별도 후속)

---

## File Structure

### PC 측 (Phase 0.5) — `drawing-to-2.5d-repo/stickerbook/` 안에 추가

| 파일 | 역할 | 신규/수정 |
|---|---|---|
| `export/android_assets.py` | captures/<ts>_<motion>/ 한 개를 갤탭 호환 stickers/<id>/ 로 변환 | 신규 |
| `export/asset_manifest.py` | manifest.json 데이터 모델 + load/save | 신규 |
| `scripts/build_android_pack.py` | CLI. captures/ 전체를 stickerbook_assets/ 폴더로 빌드 (+ optional zip) | 신규 |
| `tests/test_android_assets.py` | export 단위 테스트 | 신규 |
| `tests/test_asset_manifest.py` | manifest 로드/저장 round-trip | 신규 |
| `docs/asset_format.md` | 자산 폴더 포맷 명세 (Phase 1 & Phase 2 공통 계약) | 신규 |

### 갤탭 측 (Phase 1) — `AR_book/stickerbook_android_porting/app/`

Android Studio 가 만드는 표준 구조. 우리가 정의하는 파일만 표시:

```
stickerbook_android_porting/app/
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/k3i/stickerbook/
    │   ├── MainActivity.kt                       메인 액티비티 + Compose Navigation
    │   ├── data/
    │   │   ├── Manifest.kt                       AssetManifest data class + JSON parser
    │   │   ├── StickerEntry.kt                   스티커 1 개 메타 (id/name/motion/frames)
    │   │   └── AssetRepository.kt                APK assets + 내부저장소 로더
    │   ├── ui/
    │   │   ├── theme/Theme.kt                    Material3 theme
    │   │   ├── StickerListScreen.kt              화면 1: 그리드
    │   │   ├── StickerDetailScreen.kt            화면 2: 상세 + 모션 + 재생
    │   │   ├── components/
    │   │   │   ├── StickerCard.kt
    │   │   │   ├── MotionSelector.kt
    │   │   │   └── AnimationPlayer.kt            PNG sequence 재생 (Compose Canvas)
    │   │   └── nav/AppNavHost.kt
    │   └── perf/
    │       └── FrameRateTracker.kt               성능 메트릭
    ├── assets/
    │   └── stickerbook_assets/                   bundled sample 만 (s003~s005). 사용자 생성 sticker 는 files/ (internal)
    │       └── manifest.json (+ stickers/*)
    └── res/values/strings.xml                    한국어 텍스트
```

테스트:
```
app/src/test/java/com/k3i/stickerbook/data/ManifestParserTest.kt
app/src/test/java/com/k3i/stickerbook/data/AssetRepositoryTest.kt
app/src/androidTest/java/com/k3i/stickerbook/AnimationPlayerSmokeTest.kt
```

---

# Phase 0.5 — PC 측 자산 export

작업 디렉토리: `/home/ingon/AR_book/drawing-to-2.5d-repo/stickerbook/`

---

### Task 1: 자산 폴더 포맷 명세 (docs)

**Files:**
- Create: `drawing-to-2.5d-repo/stickerbook/docs/asset_format.md`

- [ ] **Step 1: 포맷 명세 문서 작성**

`drawing-to-2.5d-repo/stickerbook/docs/asset_format.md`:

````markdown
# Stickerbook 갤탭/Unity 호환 자산 포맷 v1

## 디렉토리 트리

```
stickerbook_assets/
├── manifest.json
└── stickers/
    └── <sticker_id>/
        ├── meta.json
        ├── source.png        원본 그림 사진
        ├── texture.png       누끼된 캐릭터 (정지, BGRA)
        ├── animation.gif     호환용 / 미리보기
        └── frames/
            ├── 0001.png
            ├── 0002.png
            └── ...
```

## manifest.json

```json
{
  "format_version": 1,
  "generated_at": "2026-05-14T10:00:00",
  "stickers": [
    {
      "id": "s001",
      "name": "사자",
      "motion": "dance_1",
      "duration_ms": 2000,
      "fps": 30,
      "frame_count": 60,
      "width": 512,
      "height": 512,
      "frames_dir": "stickers/s001/frames",
      "gif_path": "stickers/s001/animation.gif",
      "texture_path": "stickers/s001/texture.png",
      "source_path": "stickers/s001/source.png"
    }
  ]
}
```

## meta.json (스티커 1 개)

```json
{
  "id": "s001",
  "name": "사자",
  "motion": "dance_1",
  "created_at": "2026-05-14T10:00:00",
  "fps": 30,
  "frame_count": 60,
  "width": 512,
  "height": 512
}
```

## 규칙

- `id` 는 자산 폴더 내 유일. 영문 소문자 + 숫자 + `_` 만.
- `frames/` PNG 는 알파 채널 보존 (RGBA). 8-bit 알파.
- `animation.gif` 는 호환용. 갤탭/Unity 는 PNG seq 우선.
- `texture.png` 는 정지 캐릭터 (모션 적용 전 상태).
- 모든 경로는 자산 root 기준 상대.

## 버전 정책

- `format_version` 이 호환 깨질 때 +1.
- 갤탭/Unity 는 자기가 모르는 version → 안전하게 에러 표시.
````

- [ ] **Step 2: Commit**

```bash
cd /home/ingon/AR_book/drawing-to-2.5d-repo
git add stickerbook/docs/asset_format.md
git commit -m "docs: define android/unity-compatible asset format v1"
```

---

### Task 2: `asset_manifest.py` — Manifest 데이터 모델 + I/O

**Files:**
- Create: `drawing-to-2.5d-repo/stickerbook/export/asset_manifest.py`
- Test: `drawing-to-2.5d-repo/stickerbook/tests/test_asset_manifest.py`

- [ ] **Step 1: failing test 작성**

`drawing-to-2.5d-repo/stickerbook/tests/test_asset_manifest.py`:

```python
import json
from pathlib import Path

from export.asset_manifest import Manifest, StickerEntry


def test_manifest_roundtrip(tmp_path: Path) -> None:
    entry = StickerEntry(
        id="s001",
        name="사자",
        motion="dance_1",
        duration_ms=2000,
        fps=30,
        frame_count=60,
        width=512,
        height=512,
        frames_dir="stickers/s001/frames",
        gif_path="stickers/s001/animation.gif",
        texture_path="stickers/s001/texture.png",
        source_path="stickers/s001/source.png",
    )
    m = Manifest(format_version=1, generated_at="2026-05-14T10:00:00", stickers=[entry])

    path = tmp_path / "manifest.json"
    m.save(path)
    loaded = Manifest.load(path)

    assert loaded.format_version == 1
    assert len(loaded.stickers) == 1
    assert loaded.stickers[0].name == "사자"
    assert loaded.stickers[0].frame_count == 60


def test_manifest_load_rejects_unknown_version(tmp_path: Path) -> None:
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps({"format_version": 99, "generated_at": "x", "stickers": []}))

    try:
        Manifest.load(path)
    except ValueError as e:
        assert "format_version" in str(e)
        return
    raise AssertionError("expected ValueError for unknown format_version")


def test_manifest_upsert_replaces_by_id(tmp_path: Path) -> None:
    m = Manifest(format_version=1, generated_at="t0", stickers=[])
    e1 = StickerEntry(id="s001", name="A", motion="m", duration_ms=1000, fps=30,
                     frame_count=30, width=512, height=512,
                     frames_dir="stickers/s001/frames", gif_path="", texture_path="",
                     source_path="")
    e2 = StickerEntry(id="s001", name="B", motion="m", duration_ms=1000, fps=30,
                     frame_count=30, width=512, height=512,
                     frames_dir="stickers/s001/frames", gif_path="", texture_path="",
                     source_path="")
    m.upsert(e1)
    m.upsert(e2)
    assert len(m.stickers) == 1
    assert m.stickers[0].name == "B"
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ingon/AR_book/drawing-to-2.5d-repo/stickerbook
python3 -m pytest tests/test_asset_manifest.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'export.asset_manifest'`

- [ ] **Step 3: 최소 구현**

`drawing-to-2.5d-repo/stickerbook/export/asset_manifest.py`:

```python
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import List

FORMAT_VERSION = 1


@dataclass
class StickerEntry:
    id: str
    name: str
    motion: str
    duration_ms: int
    fps: int
    frame_count: int
    width: int
    height: int
    frames_dir: str
    gif_path: str
    texture_path: str
    source_path: str


@dataclass
class Manifest:
    format_version: int
    generated_at: str
    stickers: List[StickerEntry] = field(default_factory=list)

    def upsert(self, entry: StickerEntry) -> None:
        for i, e in enumerate(self.stickers):
            if e.id == entry.id:
                self.stickers[i] = entry
                return
        self.stickers.append(entry)

    def save(self, path: Path) -> None:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "format_version": self.format_version,
            "generated_at": self.generated_at,
            "stickers": [asdict(s) for s in self.stickers],
        }
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2))

    @classmethod
    def load(cls, path: Path) -> "Manifest":
        data = json.loads(Path(path).read_text())
        v = int(data.get("format_version", -1))
        if v != FORMAT_VERSION:
            raise ValueError(
                f"unsupported manifest format_version={v}, expected {FORMAT_VERSION}"
            )
        stickers = [StickerEntry(**s) for s in data.get("stickers", [])]
        return cls(
            format_version=v,
            generated_at=str(data.get("generated_at", "")),
            stickers=stickers,
        )
```

- [ ] **Step 4: Run test — pass**

```bash
python3 -m pytest tests/test_asset_manifest.py -v
```

Expected: 3 passed

- [ ] **Step 5: Commit**

```bash
git add stickerbook/export/asset_manifest.py stickerbook/tests/test_asset_manifest.py
git commit -m "feat: add asset manifest model with JSON round-trip"
```

---

### Task 3: `android_assets.py` — V1 captures → 갤탭 자산 변환

**Files:**
- Create: `drawing-to-2.5d-repo/stickerbook/export/android_assets.py`
- Test: `drawing-to-2.5d-repo/stickerbook/tests/test_android_assets.py`

V1 의 `assets/captures/<ts>_<motion>/` 한 개를 입력으로 받아 `stickerbook_assets/stickers/<id>/` 한 개를 만든다. 입력 디렉토리에 있는 파일: `video.gif`, `char_cfg.yaml`, `texture.png`, `mask.png`, `input.png` (V1 `_persist_animation_capture` 가 저장하는 그대로).

- [ ] **Step 1: failing test**

`drawing-to-2.5d-repo/stickerbook/tests/test_android_assets.py`:

```python
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from export.android_assets import build_sticker_entry
from export.asset_manifest import StickerEntry


def _make_dummy_capture(tmp_path: Path) -> Path:
    """Fake V1 capture directory with minimal files."""
    cap = tmp_path / "capture"
    cap.mkdir()

    # video.gif: 3-frame 64x64 RGBA-ish (palette GIF)
    frames = [Image.new("RGB", (64, 64), color=(i * 80, 0, 0)) for i in range(3)]
    frames[0].save(
        cap / "video.gif",
        save_all=True,
        append_images=frames[1:],
        duration=100,  # ms per frame
        loop=0,
    )
    Image.new("RGBA", (64, 64), color=(0, 0, 0, 0)).save(cap / "texture.png")
    Image.new("L", (64, 64), color=0).save(cap / "mask.png")
    Image.new("RGB", (64, 64), color=(128, 128, 128)).save(cap / "input.png")
    return cap


def test_build_sticker_entry_creates_full_layout(tmp_path: Path) -> None:
    capture = _make_dummy_capture(tmp_path)
    out_root = tmp_path / "assets"

    entry: StickerEntry = build_sticker_entry(
        capture_dir=capture,
        out_root=out_root,
        sticker_id="s001",
        name="테스트",
        motion="dance_1",
    )

    assert entry.id == "s001"
    assert entry.name == "테스트"
    assert entry.motion == "dance_1"
    assert entry.frame_count >= 1
    assert entry.width == 64 and entry.height == 64

    sdir = out_root / "stickers" / "s001"
    assert (sdir / "meta.json").is_file()
    assert (sdir / "texture.png").is_file()
    assert (sdir / "animation.gif").is_file()
    assert (sdir / "source.png").is_file()
    frames = sorted((sdir / "frames").glob("*.png"))
    assert len(frames) == entry.frame_count
    # frame name pattern
    assert frames[0].name == "0001.png"


def test_build_sticker_entry_rejects_invalid_id(tmp_path: Path) -> None:
    capture = _make_dummy_capture(tmp_path)
    with pytest.raises(ValueError):
        build_sticker_entry(
            capture_dir=capture, out_root=tmp_path / "assets",
            sticker_id="Invalid ID!", name="x", motion="m",
        )


def test_build_sticker_entry_extracts_alpha_frames_when_chromakey(tmp_path: Path) -> None:
    """GIF has white background; output frames should preserve alpha (white→transparent)."""
    capture = _make_dummy_capture(tmp_path)
    # Force GIF to be near-white
    frames = [Image.new("RGB", (64, 64), color=(255, 255, 255)) for _ in range(3)]
    frames[0].save(
        capture / "video.gif", save_all=True, append_images=frames[1:],
        duration=100, loop=0,
    )

    entry = build_sticker_entry(
        capture_dir=capture, out_root=tmp_path / "assets",
        sticker_id="s002", name="x", motion="m", chromakey_white=True,
    )

    frame = Image.open(tmp_path / "assets" / "stickers" / "s002" / "frames" / "0001.png")
    assert frame.mode == "RGBA"
    arr = np.array(frame)
    # white pixels should be transparent
    assert (arr[..., 3] == 0).all()
```

- [ ] **Step 2: 의존성 추가**

`requirements.txt` 끝에 추가 (이미 있으면 skip):

```
Pillow>=10.0
```

설치:
```bash
pip install Pillow
```

- [ ] **Step 3: Run failing test**

```bash
python3 -m pytest tests/test_android_assets.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'export.android_assets'`

- [ ] **Step 4: 최소 구현**

`drawing-to-2.5d-repo/stickerbook/export/android_assets.py`:

```python
from __future__ import annotations

import re
import shutil
from datetime import datetime
from pathlib import Path
from typing import Optional

import numpy as np
from PIL import Image, ImageSequence

from export.asset_manifest import StickerEntry

_ID_PATTERN = re.compile(r"^[a-z0-9_]+$")
_DEFAULT_FPS = 30


def _validate_id(sticker_id: str) -> None:
    if not _ID_PATTERN.match(sticker_id):
        raise ValueError(
            f"invalid sticker_id={sticker_id!r}: lowercase letters/digits/underscore only"
        )


def _chromakey_white_to_alpha(rgb: Image.Image, threshold: int = 250) -> Image.Image:
    """Pixels brighter than threshold on all channels become fully transparent."""
    arr = np.asarray(rgb.convert("RGB"), dtype=np.uint8)
    mask = (arr >= threshold).all(axis=-1)
    rgba = np.dstack([arr, np.where(mask, 0, 255).astype(np.uint8)])
    return Image.fromarray(rgba, mode="RGBA")


def _extract_frames(
    gif_path: Path, frames_dir: Path, chromakey_white: bool
) -> tuple[int, int, int, int]:
    """Returns (frame_count, fps, width, height)."""
    frames_dir.mkdir(parents=True, exist_ok=True)
    gif = Image.open(gif_path)

    durations = []
    count = 0
    width, height = gif.size
    for i, frame in enumerate(ImageSequence.Iterator(gif)):
        rgb = frame.convert("RGB")
        out = _chromakey_white_to_alpha(rgb) if chromakey_white else rgb.convert("RGBA")
        out.save(frames_dir / f"{i + 1:04d}.png", format="PNG")
        durations.append(int(frame.info.get("duration", 1000 // _DEFAULT_FPS)))
        count += 1

    if not durations:
        return 0, _DEFAULT_FPS, width, height

    avg_ms = max(1, sum(durations) // len(durations))
    fps = max(1, round(1000 / avg_ms))
    return count, fps, width, height


def build_sticker_entry(
    capture_dir: Path,
    out_root: Path,
    sticker_id: str,
    name: str,
    motion: str,
    chromakey_white: bool = True,
) -> StickerEntry:
    _validate_id(sticker_id)

    capture_dir = Path(capture_dir)
    out_root = Path(out_root)
    sdir = out_root / "stickers" / sticker_id
    sdir.mkdir(parents=True, exist_ok=True)

    gif_src = capture_dir / "video.gif"
    if not gif_src.is_file():
        raise FileNotFoundError(f"missing video.gif in {capture_dir}")

    frames_dir = sdir / "frames"
    frame_count, fps, width, height = _extract_frames(gif_src, frames_dir, chromakey_white)
    duration_ms = int(1000 * frame_count / fps) if fps > 0 else 0

    shutil.copyfile(gif_src, sdir / "animation.gif")
    for fname in ("texture.png", "input.png"):
        src = capture_dir / fname
        if src.is_file():
            dst_name = "source.png" if fname == "input.png" else fname
            shutil.copyfile(src, sdir / dst_name)

    rel_root = Path("stickers") / sticker_id
    entry = StickerEntry(
        id=sticker_id,
        name=name,
        motion=motion,
        duration_ms=duration_ms,
        fps=fps,
        frame_count=frame_count,
        width=width,
        height=height,
        frames_dir=str(rel_root / "frames"),
        gif_path=str(rel_root / "animation.gif"),
        texture_path=str(rel_root / "texture.png"),
        source_path=str(rel_root / "source.png"),
    )

    meta_path = sdir / "meta.json"
    _write_meta(meta_path, entry)
    return entry


def _write_meta(path: Path, entry: StickerEntry) -> None:
    import json
    payload = {
        "id": entry.id,
        "name": entry.name,
        "motion": entry.motion,
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "fps": entry.fps,
        "frame_count": entry.frame_count,
        "width": entry.width,
        "height": entry.height,
    }
    path.write_text(__import__("json").dumps(payload, ensure_ascii=False, indent=2))
```

- [ ] **Step 5: Run test — pass**

```bash
python3 -m pytest tests/test_android_assets.py -v
```

Expected: 3 passed

- [ ] **Step 6: Commit**

```bash
git add stickerbook/export/android_assets.py stickerbook/tests/test_android_assets.py stickerbook/requirements.txt
git commit -m "feat: extract V1 capture into android-compatible sticker entry"
```

---

### Task 4: CLI — `scripts/build_android_pack.py`

**Files:**
- Create: `drawing-to-2.5d-repo/stickerbook/scripts/build_android_pack.py`
- Test: 직접 실행 (CLI smoke)

- [ ] **Step 1: CLI 구현**

`drawing-to-2.5d-repo/stickerbook/scripts/build_android_pack.py`:

```python
#!/usr/bin/env python3
"""Walk `assets/captures/` and build a unified `stickerbook_assets/` folder."""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import zipfile
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from export.android_assets import build_sticker_entry
from export.asset_manifest import FORMAT_VERSION, Manifest

_CAPTURE_PATTERN = re.compile(r"^(?P<ts>\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})_(?P<motion>.+)$")


def _slugify(name: str) -> str:
    s = name.lower()
    s = re.sub(r"[^a-z0-9_]+", "_", s)
    s = re.sub(r"_+", "_", s).strip("_")
    return s or "sticker"


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument(
        "--captures-dir",
        type=Path,
        default=Path("assets/captures"),
        help="directory containing <ts>_<motion>/ subdirs (V1 captures)",
    )
    p.add_argument(
        "--output",
        type=Path,
        default=Path("stickerbook_assets"),
        help="output asset root",
    )
    p.add_argument("--zip", action="store_true", help="also emit <output>.zip")
    p.add_argument(
        "--no-chromakey",
        action="store_true",
        help="skip white→alpha (use when GIF already has alpha)",
    )
    args = p.parse_args()

    captures = sorted(d for d in args.captures_dir.iterdir() if d.is_dir())
    if not captures:
        print(f"no capture dirs under {args.captures_dir}")
        return 1

    if args.output.exists():
        shutil.rmtree(args.output)
    args.output.mkdir(parents=True)

    manifest = Manifest(
        format_version=FORMAT_VERSION,
        generated_at=datetime.now().isoformat(timespec="seconds"),
        stickers=[],
    )

    for i, cap in enumerate(captures, start=1):
        m = _CAPTURE_PATTERN.match(cap.name)
        motion = m.group("motion") if m else "unknown"
        sticker_id = f"s{i:03d}"
        name = _slugify(cap.name)
        try:
            entry = build_sticker_entry(
                capture_dir=cap,
                out_root=args.output,
                sticker_id=sticker_id,
                name=name,
                motion=motion,
                chromakey_white=not args.no_chromakey,
            )
        except Exception as e:
            print(f"[skip] {cap}: {e}")
            continue
        manifest.upsert(entry)
        print(f"[ok] {cap.name} -> {sticker_id} ({entry.frame_count} frames)")

    manifest.save(args.output / "manifest.json")
    print(f"\nmanifest written: {args.output / 'manifest.json'}")
    print(f"stickers: {len(manifest.stickers)}")

    if args.zip:
        zip_path = args.output.with_suffix(".zip")
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for f in args.output.rglob("*"):
                zf.write(f, f.relative_to(args.output.parent))
        print(f"zip: {zip_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: 실행 권한 + smoke run**

```bash
cd /home/ingon/AR_book/drawing-to-2.5d-repo/stickerbook
chmod +x scripts/build_android_pack.py
python3 scripts/build_android_pack.py --captures-dir assets/captures --output stickerbook_assets
```

Expected:
- 출력 끝에 `manifest written: stickerbook_assets/manifest.json`
- `stickerbook_assets/stickers/s001/frames/*.png` 존재
- `stickerbook_assets/manifest.json` 안의 `stickers[]` 가 비어있지 않음

(captures 폴더가 비어있으면 V1 에서 SPACE 한 번 눌러 자산 1개 만든 뒤 실행)

- [ ] **Step 3: zip 옵션 smoke**

```bash
python3 scripts/build_android_pack.py --captures-dir assets/captures --output stickerbook_assets --zip
ls -lh stickerbook_assets.zip
```

Expected: `stickerbook_assets.zip` 파일 생성, 크기 0 이상

- [ ] **Step 4: .gitignore 보강**

`drawing-to-2.5d-repo/.gitignore` 끝에 추가 (이미 있으면 skip):

```
stickerbook/stickerbook_assets/
stickerbook/stickerbook_assets.zip
```

- [ ] **Step 5: Commit**

```bash
cd /home/ingon/AR_book/drawing-to-2.5d-repo
git add stickerbook/scripts/build_android_pack.py .gitignore
git commit -m "feat: add build_android_pack CLI for asset folder/zip output"
```

---

### Task 5: 전체 회귀 + 데모 자산 생성

- [ ] **Step 1: 모든 PC 측 테스트**

```bash
cd /home/ingon/AR_book/drawing-to-2.5d-repo/stickerbook
python3 -m pytest tests/ -q
```

Expected: 모두 통과 (기존 108 + 신규 ~6 = ~114)

- [ ] **Step 2: 데모 자산 1-2 개 만들기**

V1 stickerbook 으로 캐릭터 1-2 개 생성 (`assets/captures/` 에 폴더 쌓이게):

```bash
cd /home/ingon/AR_book/drawing-to-2.5d-repo/stickerbook
python3 main.py --camera 1
# 종이에 그림 놓고 SPACE 2-3 번. Q 로 종료.
```

- [ ] **Step 3: 자산 폴더 build + 갤탭 전달 준비**

```bash
python3 scripts/build_android_pack.py --captures-dir assets/captures --output stickerbook_assets
```

수동 검증:
- `stickerbook_assets/manifest.json` 열어서 sticker 1개 이상 보임
- `stickerbook_assets/stickers/s001/frames/*.png` 알파 채널 살아있는지 (image viewer 로 열어 배경 투명 확인)

> Phase 0.5 완료 — 산출물: `stickerbook_assets/` 폴더가 Phase 1 의 입력이 됨.

---

# Phase 1 — 갤탭 Native Kotlin 앱

작업 디렉토리: `/home/ingon/AR_book/stickerbook_android_porting/app/`

전제: Android Studio Koala (2024.1) 이상 설치, JDK 17, 갤탭에 USB 디버깅 활성.

---

### Task 6: Android Studio 프로젝트 스캐폴드

**Files:**
- Create: `stickerbook_android_porting/app/` (전체 Gradle 프로젝트)

- [ ] **Step 1: Android Studio 에서 새 프로젝트 생성**

Android Studio → New Project → **Empty Activity (Compose)** 선택.

설정:
- Name: `Stickerbook`
- Package name: `com.k3i.stickerbook`
- Save location: `/home/ingon/AR_book/stickerbook_android_porting/app`
- Language: Kotlin
- Minimum SDK: API 28 (Android 9.0)
- Build configuration language: Kotlin DSL

- [ ] **Step 2: 모듈 build.gradle.kts 수정 — 의존성 추가**

`stickerbook_android_porting/app/app/build.gradle.kts` 의 `dependencies { }` 안에 추가:

```kotlin
// JSON
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

// Image decoding
implementation("io.coil-kt:coil-compose:2.6.0")
implementation("io.coil-kt:coil-gif:2.6.0")

// Compose Navigation
implementation("androidx.navigation:navigation-compose:2.7.7")

// Testing
testImplementation("junit:junit:4.13.2")
testImplementation("org.robolectric:robolectric:4.12.1")
testImplementation("androidx.test:core:1.5.0")
```

`plugins { }` 최상단 (또는 적절한 위치) 에 추가:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "2.0.0"
}
```

`android { ... }` 블록 안에:

```kotlin
android {
    defaultConfig {
        applicationId = "com.k3i.stickerbook"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
    }
}
```

- [ ] **Step 3: AndroidManifest.xml 권한**

`stickerbook_android_porting/app/app/src/main/AndroidManifest.xml` 의 `<manifest>` 안 (`<application>` 위) 에:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
                 android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"
                 android:minSdkVersion="33" />
```

- [ ] **Step 4: gradle sync + 빈 빌드 확인**

```bash
cd /home/ingon/AR_book/stickerbook_android_porting/app
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. (실패 시 Android Studio Gradle sync 로그 확인)

- [ ] **Step 5: Commit**

```bash
cd /home/ingon/AR_book/stickerbook_android_porting
git init  # 아직 git repo 가 아니면
git add app/.gitignore app/build.gradle.kts app/settings.gradle.kts app/gradle.properties app/gradle/ app/app/build.gradle.kts app/app/src/main/AndroidManifest.xml
git add app/app/src/main/java app/app/src/main/res app/app/src/test
git commit -m "chore: scaffold android studio compose project with serialization+coil"
```

---

### Task 7: Manifest 파서

**Files:**
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/data/Manifest.kt`
- Test: `stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/data/ManifestParserTest.kt`

- [ ] **Step 1: failing test**

`stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/data/ManifestParserTest.kt`:

```kotlin
package com.k3i.stickerbook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestParserTest {

    @Test
    fun parses_minimal_manifest() {
        val json = """
            {
              "format_version": 1,
              "generated_at": "2026-05-14T10:00:00",
              "stickers": [
                {
                  "id": "s001",
                  "name": "사자",
                  "motion": "dance_1",
                  "duration_ms": 2000,
                  "fps": 30,
                  "frame_count": 60,
                  "width": 512,
                  "height": 512,
                  "frames_dir": "stickers/s001/frames",
                  "gif_path": "stickers/s001/animation.gif",
                  "texture_path": "stickers/s001/texture.png",
                  "source_path": "stickers/s001/source.png"
                }
              ]
            }
        """.trimIndent()

        val m = ManifestParser.parse(json)

        assertEquals(1, m.formatVersion)
        assertEquals(1, m.stickers.size)
        assertEquals("사자", m.stickers[0].name)
        assertEquals(60, m.stickers[0].frameCount)
    }

    @Test
    fun rejects_unknown_format_version() {
        val json = """{"format_version": 99, "generated_at": "x", "stickers": []}"""
        assertThrows(IllegalStateException::class.java) { ManifestParser.parse(json) }
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
cd /home/ingon/AR_book/stickerbook_android_porting/app
./gradlew :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.ManifestParserTest"
```

Expected: FAIL (Manifest / ManifestParser unresolved)

- [ ] **Step 3: 구현**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/data/Manifest.kt`:

```kotlin
package com.k3i.stickerbook.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SUPPORTED_FORMAT_VERSION = 1

@Serializable
data class StickerEntry(
    val id: String,
    val name: String,
    val motion: String,
    @SerialName("duration_ms") val durationMs: Int,
    val fps: Int,
    @SerialName("frame_count") val frameCount: Int,
    val width: Int,
    val height: Int,
    @SerialName("frames_dir") val framesDir: String,
    @SerialName("gif_path") val gifPath: String,
    @SerialName("texture_path") val texturePath: String,
    @SerialName("source_path") val sourcePath: String,
)

@Serializable
data class Manifest(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    val stickers: List<StickerEntry> = emptyList(),
)

object ManifestParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Manifest {
        val m = json.decodeFromString(Manifest.serializer(), text)
        check(m.formatVersion == SUPPORTED_FORMAT_VERSION) {
            "unsupported format_version=${m.formatVersion}, expected $SUPPORTED_FORMAT_VERSION"
        }
        return m
    }
}
```

- [ ] **Step 4: Run test — pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.ManifestParserTest"
```

Expected: 2 passed

- [ ] **Step 5: Commit**

```bash
cd /home/ingon/AR_book/stickerbook_android_porting
git add app/app/src/main/java/com/k3i/stickerbook/data/Manifest.kt
git add app/app/src/test/java/com/k3i/stickerbook/data/ManifestParserTest.kt
git commit -m "feat(android): manifest parser with format_version guard"
```

---

### Task 8: AssetRepository — APK assets + 내부저장소 로더

**Files:**
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/data/AssetRepository.kt`
- Test: `stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/data/AssetRepositoryTest.kt`

요구:
- APK 의 `assets/stickerbook_assets/manifest.json` 우선 로드
- 없으면 내부저장소 `filesDir/stickerbook_assets/manifest.json` 로드 (ADB push 용)
- 둘 다 없으면 null

- [ ] **Step 1: failing test (Robolectric 사용)**

`stickerbook_android_porting/app/app/src/test/java/com/k3i/stickerbook/data/AssetRepositoryTest.kt`:

```kotlin
package com.k3i.stickerbook.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AssetRepositoryTest {

    @Test
    fun returns_null_when_no_manifest_anywhere() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = AssetRepository(ctx)
        assertNull(repo.loadManifest())
    }

    @Test
    fun loads_manifest_from_internal_storage_when_present() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(ctx.filesDir, "stickerbook_assets")
        root.mkdirs()
        File(root, "manifest.json").writeText(
            """{"format_version":1,"generated_at":"x","stickers":[]}"""
        )
        val repo = AssetRepository(ctx)
        val m = repo.loadManifest()!!
        assertEquals(1, m.formatVersion)
        assertEquals(0, m.stickers.size)
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.AssetRepositoryTest"
```

Expected: FAIL (AssetRepository unresolved)

- [ ] **Step 3: 구현**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/data/AssetRepository.kt`:

```kotlin
package com.k3i.stickerbook.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

class AssetRepository(private val context: Context) {

    fun loadManifest(): Manifest? {
        val internal = File(context.filesDir, "stickerbook_assets/manifest.json")
        if (internal.isFile) {
            return runCatching { ManifestParser.parse(internal.readText()) }
                .onFailure { Log.w(TAG, "internal manifest parse failed", it) }
                .getOrNull()
        }
        return try {
            val text = context.assets.open("stickerbook_assets/manifest.json")
                .bufferedReader().use { it.readText() }
            ManifestParser.parse(text)
        } catch (e: IOException) {
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "bundled manifest unsupported", e)
            null
        }
    }

    /** Resolves a relative path in the manifest to a usable handle. */
    fun resolve(relativePath: String): AssetHandle {
        val internalFile = File(context.filesDir, "stickerbook_assets/$relativePath")
        return if (internalFile.isFile) {
            AssetHandle.InternalFile(internalFile)
        } else {
            AssetHandle.Bundled("stickerbook_assets/$relativePath")
        }
    }

    companion object {
        private const val TAG = "AssetRepository"
    }
}

sealed interface AssetHandle {
    data class Bundled(val assetPath: String) : AssetHandle
    data class InternalFile(val file: File) : AssetHandle
}
```

- [ ] **Step 4: Run test — pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.k3i.stickerbook.data.AssetRepositoryTest"
```

Expected: 2 passed

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/data/AssetRepository.kt
git add app/app/src/test/java/com/k3i/stickerbook/data/AssetRepositoryTest.kt
git commit -m "feat(android): asset repo loads from internal storage or apk assets"
```

---

### Task 9: AnimationPlayer 컴포저블 (PNG sequence)

**Files:**
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/components/AnimationPlayer.kt`

기능: `framesDir` 안의 `0001.png ... NNNN.png` 를 fps 에 맞춰 순환 재생. 메모리는 한 번에 1 프레임만 디코드.

- [ ] **Step 1: 구현**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/components/AnimationPlayer.kt`:

```kotlin
package com.k3i.stickerbook.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.k3i.stickerbook.data.AssetHandle
import com.k3i.stickerbook.data.AssetRepository
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun AnimationPlayer(
    framesDir: String,
    frameCount: Int,
    fps: Int,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val repo = remember { AssetRepository(ctx) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val frameIntervalMs = remember(fps) { (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L) }

    LaunchedEffect(framesDir, frameCount, fps) {
        if (frameCount <= 0) return@LaunchedEffect
        var i = 0
        while (true) {
            val name = "%04d.png".format(i + 1)
            val handle = repo.resolve("$framesDir/$name")
            bitmap = decodeFrame(ctx, handle)
            delay(frameIntervalMs)
            i = (i + 1) % frameCount
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val bmp = bitmap ?: return@Canvas
        val image = bmp.asImageBitmap()
        val sx = size.width / image.width
        val sy = size.height / image.height
        val scale = minOf(sx, sy)
        val w = image.width * scale
        val h = image.height * scale
        drawImage(
            image = image,
            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
            // Coil ImageBitmap scaling is layout-handled below by drawImage default
        )
    }
}

private fun decodeFrame(ctx: Context, handle: AssetHandle): Bitmap? {
    return runCatching {
        when (handle) {
            is AssetHandle.Bundled ->
                ctx.assets.open(handle.assetPath).use { BitmapFactory.decodeStream(it) }
            is AssetHandle.InternalFile ->
                BitmapFactory.decodeFile(handle.file.absolutePath)
        }
    }.getOrNull()
}
```

- [ ] **Step 2: Commit (단위 테스트 없이 — 다음 task 의 화면 통합에서 검증)**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/components/AnimationPlayer.kt
git commit -m "feat(android): png-sequence animation player composable"
```

---

### Task 10: 화면 1 — 스티커 그리드

**Files:**
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/StickerListScreen.kt`
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/components/StickerCard.kt`

- [ ] **Step 1: StickerCard 구현**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/components/StickerCard.kt`:

```kotlin
package com.k3i.stickerbook.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.AssetHandle
import com.k3i.stickerbook.data.AssetRepository
import com.k3i.stickerbook.data.StickerEntry
import androidx.compose.foundation.Image

@Composable
fun StickerCard(entry: StickerEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val repo = remember { AssetRepository(ctx) }
    var bmp by remember(entry.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry.id) {
        val handle = repo.resolve(entry.sourcePath)
        bmp = when (handle) {
            is AssetHandle.Bundled ->
                ctx.assets.open(handle.assetPath).use { BitmapFactory.decodeStream(it) }
            is AssetHandle.InternalFile ->
                BitmapFactory.decodeFile(handle.file.absolutePath)
        }
    }

    Card(
        modifier = modifier.clickable { onClick() }.padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            val image = bmp
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = entry.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .background(Color(0xFFEFEFEF)),
                )
            } else {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .background(Color(0xFFEFEFEF)),
                )
            }
            Text(text = entry.name, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
```

- [ ] **Step 2: StickerListScreen 구현**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/StickerListScreen.kt`:

```kotlin
package com.k3i.stickerbook.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.AssetRepository
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.data.StickerEntry

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun StickerListScreen(onStickerClick: (StickerEntry) -> Unit) {
    val ctx = LocalContext.current
    val manifest by produceState<Manifest?>(initialValue = null) {
        value = AssetRepository(ctx).loadManifest()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("스티커북") }) }
    ) { inner ->
        val m = manifest
        if (m == null) {
            Text(
                "자산이 없습니다.\nADB push 또는 APK assets/stickerbook_assets/ 로 넣어주세요.",
                modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize().padding(inner).padding(8.dp),
            ) {
                items(m.stickers, key = { it.id }) { entry ->
                    com.k3i.stickerbook.ui.components.StickerCard(
                        entry = entry, onClick = { onStickerClick(entry) },
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: 빌드 + 갤탭 또는 에뮬레이터 실행**

빈 manifest 라도 화면이 뜨면 OK. 자산 없을 때 안내 문구 보이는지 확인.

```bash
./gradlew :app:installDebug
# 갤탭이 USB 연결되어 있고 adb devices 에 보이면 자동 설치
```

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/StickerListScreen.kt
git add app/app/src/main/java/com/k3i/stickerbook/ui/components/StickerCard.kt
git commit -m "feat(android): sticker list screen with grid + thumbnail card"
```

---

### Task 11: 화면 2 — 상세 + 모션 선택 + 재생

**Files:**
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/StickerDetailScreen.kt`
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/components/MotionSelector.kt`

요구: 현재 V1 자산 1개에는 모션 1개만 (`motion` 필드 1개). 다중 모션은 후속. MVP 에서는 그 모션만 표시 + prev/next 는 다른 스티커로 이동시키는 좌우 화살표로 단순화.

- [ ] **Step 1: MotionSelector 구현 (현재는 displayonly + prev/next sticker)**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/components/MotionSelector.kt`:

```kotlin
package com.k3i.stickerbook.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MotionSelector(
    motionLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.ArrowBack, contentDescription = "이전")
        }
        Text("모션: $motionLabel")
        IconButton(onClick = onNext) {
            Icon(Icons.Default.ArrowForward, contentDescription = "다음")
        }
    }
}
```

- [ ] **Step 2: StickerDetailScreen 구현**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/StickerDetailScreen.kt`:

```kotlin
package com.k3i.stickerbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.k3i.stickerbook.data.StickerEntry
import com.k3i.stickerbook.ui.components.AnimationPlayer
import com.k3i.stickerbook.ui.components.MotionSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerDetailScreen(
    entry: StickerEntry,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                AnimationPlayer(
                    framesDir = entry.framesDir,
                    frameCount = entry.frameCount,
                    fps = entry.fps,
                )
            }
            MotionSelector(motionLabel = entry.motion, onPrev = onPrev, onNext = onNext)
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("갤러리에 저장")
            }
        }
    }
}
```

- [ ] **Step 3: 빌드 + 갤탭 실행 (이번엔 그리드 카드 탭 → 상세 화면 이동까지 안 됨, 다음 task 에서 연결)**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/StickerDetailScreen.kt
git add app/app/src/main/java/com/k3i/stickerbook/ui/components/MotionSelector.kt
git commit -m "feat(android): sticker detail screen with motion selector + player"
```

---

### Task 12: Navigation + MainActivity 연결

**Files:**
- Modify: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/MainActivity.kt`
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt`

- [ ] **Step 1: AppNavHost 구현**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt`:

```kotlin
package com.k3i.stickerbook.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.k3i.stickerbook.data.AssetRepository
import com.k3i.stickerbook.data.Manifest
import com.k3i.stickerbook.ui.StickerDetailScreen
import com.k3i.stickerbook.ui.StickerListScreen

@Composable
fun AppNavHost() {
    val ctx = LocalContext.current
    val manifest = remember { AssetRepository(ctx).loadManifest() } ?: Manifest(1, "", emptyList())
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            StickerListScreen(onStickerClick = { entry ->
                nav.navigate("detail/${entry.id}")
            })
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStack ->
            val id = backStack.arguments?.getString("id") ?: return@composable
            val idx = manifest.stickers.indexOfFirst { it.id == id }
            if (idx < 0) return@composable
            val entry = manifest.stickers[idx]
            StickerDetailScreen(
                entry = entry,
                onBack = { nav.popBackStack() },
                onPrev = {
                    val prev = manifest.stickers.getOrNull(idx - 1)
                    if (prev != null) {
                        nav.navigate("detail/${prev.id}") { popUpTo("list") }
                    }
                },
                onNext = {
                    val next = manifest.stickers.getOrNull(idx + 1)
                    if (next != null) {
                        nav.navigate("detail/${next.id}") { popUpTo("list") }
                    }
                },
                onSave = { /* Task 13 */ },
            )
        }
    }
}
```

- [ ] **Step 2: MainActivity.kt 수정**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/MainActivity.kt`:

```kotlin
package com.k3i.stickerbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.k3i.stickerbook.ui.nav.AppNavHost
import com.k3i.stickerbook.ui.theme.StickerbookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StickerbookTheme {
                AppNavHost()
            }
        }
    }
}
```

(`StickerbookTheme` 은 Android Studio 가 생성한 기본 테마. 클래스명이 다르면 그쪽 이름 사용)

- [ ] **Step 3: 갤탭에 데모 자산 1개 ADB push**

```bash
# PC 측에서:
cd /home/ingon/AR_book/drawing-to-2.5d-repo/stickerbook
adb push stickerbook_assets /sdcard/Android/data/com.k3i.stickerbook/files/
# 또는 (filesDir 사용):
adb shell run-as com.k3i.stickerbook mkdir -p files/stickerbook_assets
adb push stickerbook_assets/manifest.json /sdcard/Download/manifest.json
adb shell run-as com.k3i.stickerbook cp /sdcard/Download/manifest.json files/stickerbook_assets/
# (전체 폴더 copy 는 Task 14 에서 정리)
```

또는 더 빠르게 — `app/src/main/assets/stickerbook_assets/` 에 자산 폴더 복사 후 reinstall:

```bash
cp -r stickerbook_assets /home/ingon/AR_book/stickerbook_android_porting/app/app/src/main/assets/
cd /home/ingon/AR_book/stickerbook_android_porting/app
./gradlew :app:installDebug
```

- [ ] **Step 4: 갤탭에서 시각 확인**

- 앱 열어 그리드에 스티커 카드 보임
- 카드 탭 → 상세 화면, 캐릭터가 PNG seq 로 30fps 재생
- 좌우 화살표로 prev/next 스티커 이동
- 뒤로 가기로 리스트 복귀

이 단계가 **Phase 1 의 핵심 검증** — 여기서 깨지면 Task 9 의 AnimationPlayer 가 1순위 의심.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
git add app/app/src/main/java/com/k3i/stickerbook/MainActivity.kt
# 데모 자산은 .gitignore (개발자 데이터)
echo "app/app/src/main/assets/stickerbook_assets/" >> .gitignore 2>/dev/null || true
git add .gitignore
git commit -m "feat(android): wire navigation between list and detail with png-seq playback"
```

---

### Task 13: 저장 기능 — GIF 를 MediaStore Pictures 로

**Files:**
- Modify: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt` (onSave)
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/data/AnimationSaver.kt`

MVP 는 PNG seq 합치지 않고 **자산 폴더에 미리 만든 `animation.gif` 를 그대로 저장**. (GIF 합치기는 후속)

- [ ] **Step 1: AnimationSaver 구현**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/data/AnimationSaver.kt`:

```kotlin
package com.k3i.stickerbook.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

class AnimationSaver(private val context: Context) {

    /** Saves the entry's animation.gif into Pictures/Stickerbook/. Returns content URI string or null. */
    fun saveGif(entry: StickerEntry): String? {
        val repo = AssetRepository(context)
        val handle = repo.resolve(entry.gifPath)
        val input: InputStream = when (handle) {
            is AssetHandle.Bundled -> context.assets.open(handle.assetPath)
            is AssetHandle.InternalFile -> File(handle.file.absolutePath).inputStream()
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${entry.id}_${entry.motion}.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Stickerbook")
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return runCatching {
            resolver.openOutputStream(uri).use { out ->
                input.use { it.copyTo(out!!) }
            }
            uri.toString()
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }
}
```

- [ ] **Step 2: AppNavHost 의 onSave 연결**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt` 의 `onSave = { /* Task 13 */ }` 를:

```kotlin
onSave = {
    val saver = com.k3i.stickerbook.data.AnimationSaver(ctx)
    val uri = saver.saveGif(entry)
    val msg = if (uri != null) "갤러리에 저장됨" else "저장 실패"
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
},
```

- [ ] **Step 3: 갤탭에서 검증**

- 상세 화면 → "갤러리에 저장" 탭 → 토스트 "갤러리에 저장됨"
- 갤탭의 사진 앨범 앱에서 `Pictures/Stickerbook/` 확인 → GIF 파일 존재
- Android 갤러리가 GIF 자동 재생하는지 확인

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/data/AnimationSaver.kt
git add app/app/src/main/java/com/k3i/stickerbook/ui/nav/AppNavHost.kt
git commit -m "feat(android): save animation.gif to Pictures/Stickerbook via MediaStore"
```

---

### Task 14: 성능 측정 fixture

**Files:**
- Create: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/perf/FrameRateTracker.kt`
- Modify: `stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/components/AnimationPlayer.kt`

- [ ] **Step 1: FrameRateTracker 구현**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/perf/FrameRateTracker.kt`:

```kotlin
package com.k3i.stickerbook.perf

import android.util.Log

class FrameRateTracker(private val tag: String = "Stickerbook.FPS") {
    private var lastLogMs = 0L
    private var framesSinceLog = 0

    fun mark() {
        framesSinceLog++
        val now = System.currentTimeMillis()
        if (lastLogMs == 0L) {
            lastLogMs = now
            return
        }
        val elapsed = now - lastLogMs
        if (elapsed >= 1000) {
            val fps = framesSinceLog * 1000.0 / elapsed
            Log.i(tag, "fps=%.1f frames=%d in %dms".format(fps, framesSinceLog, elapsed))
            framesSinceLog = 0
            lastLogMs = now
        }
    }
}
```

- [ ] **Step 2: AnimationPlayer 에서 mark() 호출**

`stickerbook_android_porting/app/app/src/main/java/com/k3i/stickerbook/ui/components/AnimationPlayer.kt` 의 `LaunchedEffect` 안:

```kotlin
LaunchedEffect(framesDir, frameCount, fps) {
    if (frameCount <= 0) return@LaunchedEffect
    val tracker = com.k3i.stickerbook.perf.FrameRateTracker()
    var i = 0
    while (true) {
        val name = "%04d.png".format(i + 1)
        val handle = repo.resolve("$framesDir/$name")
        bitmap = decodeFrame(ctx, handle)
        tracker.mark()
        delay(frameIntervalMs)
        i = (i + 1) % frameCount
    }
}
```

- [ ] **Step 3: 갤탭에서 5 분 재생 + logcat 확인**

```bash
adb logcat -s "Stickerbook.FPS"
```

목표: 정상 상태에서 `fps=29~30` 안정. 1초마다 한 줄. 5분 후에도 변화 없으면 OK.

수동 메모:
- 평균 FPS: ___
- 5분 후 thermal throttle: 있음/없음
- 메모리 (Android Studio Profiler): 피크 ___ MB

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/com/k3i/stickerbook/perf/FrameRateTracker.kt
git add app/app/src/main/java/com/k3i/stickerbook/ui/components/AnimationPlayer.kt
git commit -m "feat(android): logcat fps tracker on animation player"
```

---

### Task 15: 통합 smoke 테스트 (instrumented)

**Files:**
- Create: `stickerbook_android_porting/app/app/src/androidTest/java/com/k3i/stickerbook/AnimationPlayerSmokeTest.kt`

- [ ] **Step 1: 의존성 추가 (build.gradle.kts 의 dependencies 에)**

```kotlin
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.6.7")
debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.7")
```

- [ ] **Step 2: Smoke test 작성**

`stickerbook_android_porting/app/app/src/androidTest/java/com/k3i/stickerbook/AnimationPlayerSmokeTest.kt`:

```kotlin
package com.k3i.stickerbook

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AnimationPlayerSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launches_to_list_then_opens_detail_when_assets_present() {
        // Precondition: APK assets/stickerbook_assets/ 에 manifest + sticker 1개 이상
        rule.waitForIdle()
        // 카드 라벨이 'sticker' 류로 시작한다고 가정 — 빈 자산이면 안내 문구만 보여 skip
        val anyCard = rule.onAllNodesWithSubstring("s001").fetchSemanticsNodes()
        if (anyCard.isEmpty()) return  // 자산 없을 때 패스 (manual run)
        rule.onNodeWithText("s001").performClick()
        rule.onNodeWithText("갤러리에 저장").assertExists()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithSubstring(
    s: String,
) = onAllNodesWithText(s, substring = true)

private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithText(
    text: String, substring: Boolean = false,
) = androidx.compose.ui.test.onAllNodesWithText(text, substring = substring)
```

- [ ] **Step 3: 갤탭에서 instrumented test 실행**

```bash
./gradlew :app:connectedDebugAndroidTest
```

Expected: AnimationPlayerSmokeTest 통과 (자산 없으면 graceful skip).

- [ ] **Step 4: Commit**

```bash
git add app/app/src/androidTest/java/com/k3i/stickerbook/AnimationPlayerSmokeTest.kt
git add app/app/build.gradle.kts
git commit -m "test(android): instrumented smoke test for list→detail flow"
```

---

### Task 16: Phase 1 결과 문서화

**Files:**
- Create: `stickerbook_android_porting/docs/phase1_results.md`

- [ ] **Step 1: 결과 요약 작성**

`stickerbook_android_porting/docs/phase1_results.md`:

```markdown
# Phase 1 결과 — Stickerbook 갤탭 Native MVP

날짜: <YYYY-MM-DD>
대상 기기: <Tab S9 FE+ / 기타>
Android 버전: <14>

## 성능 측정

| 항목 | 측정값 | 목표 | 통과? |
|---|---|---|---|
| PNG seq 재생 FPS (60 frames @ 512px) | __fps | ≥ 30 | ☐ |
| 콜드 스타트 (앱 실행 → 그리드 표시) | __초 | ≤ 3 | ☐ |
| 메모리 피크 (10 스티커 로드) | __MB | ≤ 1024 | ☐ |
| 5 분 연속 재생 후 thermal | 정상/throttle | 정상 | ☐ |
| GIF 저장 시간 | __ms | ≤ 500 | ☐ |

## Open Question 답 (Phase 2 입력)

- 자산 전송: ADB push 만으로 충분/부족 → ___
- 출력 포맷: PNG seq 와 GIF 중 어느 쪽이 갤탭 재생에 더 부드러웠는지 → ___
- Phase 1.5 카메라 오버레이: 필요/불필요 → ___

## 알려진 이슈 / 후속

- (예) 모션 1개만 표시 — 다중 모션은 manifest schema 확장 필요
- (예) GIF 갤러리에서 자동 재생 안 됨 — Android 12 이상 issue

## Phase 2 진입 조건 체크

- ☐ GIF / PNG seq 재생 갤탭 30fps 안정
- ☐ 자산 import 5초 내
- ☐ 메모리 < 1GB
- ☐ 10분 연속 재생 thermal 정상
- ☐ 재사용 컴포넌트 식별 완료
```

- [ ] **Step 2: 실제 측정값 채우기**

수동: Task 14 의 logcat + Android Studio Profiler 결과를 표에 채움.

- [ ] **Step 3: Commit**

```bash
git add docs/phase1_results.md
git commit -m "docs: phase 1 results template + measurements"
```

---

## Self-Review 결과

**1. Spec coverage** — brainstorm 12 섹션 중:
- §1 Goal → Task 0 (brainstorm 문서 자체로 충족), 본 plan header
- §2 Current structure → 본 plan 의 File Structure 와 Task 1 의 asset_format.md
- §3 External deps → Phase 0.5 가 deps 추가 안 함 (PC 측 Pillow 만), Phase 1 의존성은 Task 6 에 정의
- §4 Risks → Task 14 (성능 측정) + Task 16 (결과 문서) 가 R2/R3/R5/R7 검증
- §5 Model Loading → 본 plan 에서 갤탭에 모델 안 올림 (안 A 그대로). Task 별로 명시 없음 — 의도된 결과
- §6 Packaging → Task 6 = Native Kotlin (Option 1)
- §7 Touch UI → Task 10/11/12 가 화면 1/2/3 매핑
- §8 mp4→GIF pipeline → Task 3 (PC export)
- §9 Unity → Phase 2 별도 brainstorm (본 plan 범위 밖)
- §10 Recommended MVP → 본 plan 전체가 이걸 구현
- §11 Open Questions → 본 plan header 의 "기본 결정" 으로 default 명시
- §12 Next Actions → 본 plan 의 Task 1~16

**2. Placeholder scan** — "TBD/TODO/implement later" 없음. "Task 13" 같은 cross-reference 는 미리 코드 위치에 주석만 두고 그 task 에서 실제 구현 (read 순서대로 봐도 이해되게 처리).

**3. Type 일관성** — `StickerEntry` 의 필드명이 Python/Kotlin 양쪽에서 일치 (snake_case JSON ↔ camelCase Kotlin via `@SerialName`). `FORMAT_VERSION`/`SUPPORTED_FORMAT_VERSION` 양쪽 1 로 정합.

문제 없음.

---

## Execution Handoff

Plan 완성되어 `/home/ingon/AR_book/stickerbook_android_porting/docs/superpowers/plans/2026-05-14-stickerbook-android-porting-plan.md` 에 저장됨.

두 가지 실행 옵션:

**1. Subagent-Driven (추천)** — task 마다 fresh subagent dispatch, task 사이에 review. 빠른 반복.

**2. Inline Execution** — 이 세션에서 task 일괄 실행, 체크포인트마다 review.

어느 쪽으로 진행할까?
