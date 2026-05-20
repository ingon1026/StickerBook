# Sub-AD-Render-Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ad_runner 의 stub 을 *진짜 AD 호출* 로 교체. AD 의 헤드리스 MesaView (osmesa) 를 활성화해 WSL2 에서 video render 단계를 통과시킨다. 갤탭 데모에서 dummy.gif 가 *진짜 캐릭터 모션 GIF* 로 바뀌는 게 끝.

**Architecture:** AD 의 `image_to_animation` 직접 호출 안 함. ad_runner 가 (1) `image_to_annotations` 호출 (torchserve 사용) + (2) mvc_cfg yaml 우리가 직접 생성 (`view: USE_MESA: True` 포함) + (3) `animated_drawings.render.start()` 직접 호출. routes ↔ ad_runner 인터페이스 무변경.

**Tech Stack:** Python 3.8 (animated_drawings conda env), AnimatedDrawings (mesa_view), PyOpenGL + osmesa, libosmesa6 (Mesa offscreen rendering), torchserve 0.12, FastAPI, pytest.

Spec: `ad-server/docs/superpowers/specs/2026-05-19-ad-render-fix-design.md`

---

## 🎓 Learning-First Mode (구현자 필독)

선행 spec §0 와 동일 — 사용자가 WSL2 / OpenGL / 시스템 패키지 첫 접근. 따라서:

1. **새 개념 등장하는 step 앞에 "📚 새 개념"** 박스로 1-3줄 설명
2. **시스템 명령어 옵션의 의미** 한 줄씩 (apt install 의 패키지 의미, OSMesaCreateContextExt 의 인자 등)
3. **에러 발생 시 *원인 먼저* 설명** → 그 다음 수정
4. **각 Task 끝에 "✅ 방금 한 일 / 배운 것"** 정리
5. **위험 발생 시 fallback 까지 같이 명시** — 막히면 멈추고 의사결정 받기

---

## File Structure (이 plan 에서 만들 / 수정할 파일)

```
ad-server/  (Windows: /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/ad-server/
             WSL 원본: /home/ingon/AR_book/ad-server/   ─── 둘 다 동기화)
│
├── server/app/ad_runner.py          (Task 3 큰 수정: stub → real)
├── server/tests/test_ad_runner.py   (Task 4 slow integration test 부활)
├── README.md                        (Task 6 dependency + 활성화 표기 추가)
└── docs/superpowers/specs/2026-05-19-ad-render-fix-design.md  (이미 있음)
```

**파일 책임 원칙**:
- `ad_runner.py` 만 변경. routes, motion_registry, settings 무수정 (인터페이스 유지)
- AD repo (`/home/ingon/AR_book/AnimatedDrawings/`) 무수정

---

## Task 1: 시스템 의존성 — libosmesa6 + osmesa python import

**Files:**
- 없음 (시스템 + conda env 셋업만)

📚 **새 개념 — Mesa**: 오픈소스 OpenGL 구현. 그래픽카드 없이도 *CPU 로* OpenGL 명령 그릴 수 있는 software renderer 포함.

📚 **새 개념 — OSMesa (Off-Screen Mesa)**: 화면 없이 (off-screen) OpenGL 렌더링하는 Mesa 의 변종. 결과를 메모리 버퍼에 그려 나중에 파일로 저장 가능. WSL2 헤드리스에 적합.

📚 **새 개념 — libosmesa6 패키지**: Ubuntu/Debian 의 OSMesa 동적 라이브러리. 설치하면 `libOSMesa.so.6` (또는 .8) 가 `/usr/lib/x86_64-linux-gnu/` 에 생김. PyOpenGL 의 osmesa 모듈이 이걸 찾아 import.

- [ ] **Step 1: 현재 osmesa 상태 확인 (설치 전)**

WSL 터미널:
```bash
ldconfig -p | grep -i osmesa
```
Expected: 빈 결과 (libosmesa 미설치 상태 — spec §4.1 의 진단과 동일)

- [ ] **Step 2: libosmesa6 설치**

```bash
sudo apt update
sudo apt install -y libosmesa6 libosmesa6-dev
```
Expected: 두 패키지 설치 (수 MB). `Setting up libosmesa6:amd64 (...)` 같은 로그.

📚 **`libosmesa6-dev`**: 헤더 파일 + 빌드 시 필요한 심볼릭 링크 (`libOSMesa.so` → `libOSMesa.so.6.x`). PyOpenGL 이 런타임에 `libOSMesa.so` 이름으로 찾으면 dev 패키지가 필요한 경우 많음.

- [ ] **Step 3: 시스템 lib 확인**

```bash
ldconfig -p | grep -i osmesa
```
Expected: 두 줄 정도 보임, 예:
```
libOSMesa.so.8 (libc6,x86-64) => /lib/x86_64-linux-gnu/libOSMesa.so.8
libOSMesa.so (libc6,x86-64) => /lib/x86_64-linux-gnu/libOSMesa.so
```

⚠ Ubuntu 24.04 에서는 SOVERSION 이 `.8` 일 수 있음. `libosmesa6` 패키지 이름은 그대로 — 패키지명과 SO 버전이 다른 게 정상.

- [ ] **Step 4: conda env 안에서 osmesa python import**

```bash
source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate animated_drawings
PYTHONPATH= AMENT_PREFIX_PATH= python -c "from OpenGL import osmesa; print('osmesa python OK')"
```
Expected: `osmesa python OK`

⚠ **실패 케이스 흔한 거**:
- `ImportError: ('Unable to load OpenGL library', 'libOSMesa.so')` → Step 2/3 다시 (libosmesa6-dev 누락)
- `ModuleNotFoundError: No module named 'OpenGL'` → conda env 잘못 활성화 (`pip list | grep -i pyopengl` 로 확인)
- `OpenGL.error.NullFunctionError` → PyOpenGL 재설치 `pip install --force-reinstall PyOpenGL PyOpenGL-accelerate`

✅ **방금 한 일**: Mesa software OpenGL 라이브러리 설치 + Python binding 동작 확인.
**배운 것**: Mesa / OSMesa 의 의미, 시스템 lib 와 Python binding 의 관계, ldconfig 로 동적 lib 확인하는 법.

---

## Task 2: 헤드리스 OpenGL Sanity — OSMesa context 생성 직접 시험

**Files:**
- 없음 (one-shot 검증)

📚 **왜 이 task 가 별도?**: AD 코드를 통째 부르기 전에 *OpenGL context 자체가 만들어지는지* 가장 작은 단위로 검증. 만약 여기서 막히면 AD 무관 OS/lib 문제 → 빠른 진단.

📚 **새 개념 — OpenGL context**: GL 상태 (텍스처, 셰이더, 버퍼 등) 의 컨테이너. context 없이 GL 함수 호출 = 우리가 본 그 에러 (`Attempt to retrieve context when no valid context`). `OSMesaCreateContextExt` 는 OSMesa 의 context 생성 함수.

- [ ] **Step 1: headless GL sanity 스크립트 실행**

```bash
source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate animated_drawings
PYTHONPATH= AMENT_PREFIX_PATH= python <<'PY'
import os
os.environ['PYOPENGL_PLATFORM'] = 'osmesa'
os.environ['MESA_GL_VERSION_OVERRIDE'] = '3.3'
from OpenGL import GL, osmesa
import ctypes

# OSMesaCreateContextExt(format, depthBits, stencilBits, accumBits, sharelist)
ctx = osmesa.OSMesaCreateContextExt(
    osmesa.OSMESA_RGBA, 24, 8, 0, None
)
assert ctx is not None, "OSMesa context creation returned None"

# 작은 4x4 RGBA buffer 만들고 attach
W, H = 4, 4
buf = (ctypes.c_ubyte * (W * H * 4))()
ok = osmesa.OSMesaMakeCurrent(ctx, buf, GL.GL_UNSIGNED_BYTE, W, H)
assert ok, "OSMesaMakeCurrent failed"

# 컨텍스트 활성 — GL string 가져오기
vendor = GL.glGetString(GL.GL_VENDOR)
renderer = GL.glGetString(GL.GL_RENDERER)
version = GL.glGetString(GL.GL_VERSION)
print(f"GL_VENDOR   : {vendor.decode() if vendor else None}")
print(f"GL_RENDERER : {renderer.decode() if renderer else None}")
print(f"GL_VERSION  : {version.decode() if version else None}")

osmesa.OSMesaDestroyContext(ctx)
print("OSMesa sanity OK")
PY
```

Expected:
```
GL_VENDOR   : Mesa/X.org (또는 Mesa)
GL_RENDERER : llvmpipe (LLVM ...) 또는 softpipe
GL_VERSION  : 3.3 (Core Profile) Mesa 25.x ...
OSMesa sanity OK
```

📚 **인자 의미 (`OSMesaCreateContextExt`)**:
- `OSMESA_RGBA` — 픽셀 포맷 (RGBA 8888)
- `24` — depth buffer 비트수
- `8` — stencil buffer 비트수
- `0` — accumulation buffer 비트수 (사용 X)
- `None` — share list (다른 context 와 자원 공유 X)

📚 **`llvmpipe`**: Mesa 의 LLVM 기반 *CPU SW renderer*. GPU 없이 OpenGL 명령을 CPU 로 처리. 우리 케이스 의도.

⚠ **막힐 가능성 + 처리**:
- `OSMesaCreateContextExt returned None` → Mesa 버전/빌드 문제. `apt install --reinstall libosmesa6 libosmesa6-dev` 후 재시도
- `glGetString` 이 None → context active 안 됨. OSMesaMakeCurrent 결과 확인
- 둘 다 실패 → **spec §7.1 fallback trigger 1 또는 2 발동**. 멈추고 사용자에게 보고 후 Xvfb sub-task 로 전환 검토

✅ **방금 한 일**: Mesa SW renderer 가 우리 환경에서 OpenGL 3.3 context 만들 수 있다는 걸 *AD 무관* 으로 직접 검증.
**배운 것**: OpenGL context 의 의미, OSMesa context 생성 인자, llvmpipe 가 CPU SW renderer 라는 것.

---

## Task 3: ad_runner.py 의 stub → 실제 AD 호출로 교체

**Files:**
- Modify: `/home/ingon/AR_book/ad-server/server/app/ad_runner.py` (full rewrite)
- (참고: Windows 측 stickerbook repo 의 동일 파일은 Task 6 에서 동기화)

📚 **이번 task 의 핵심**: AD 의 `image_to_animation` 함수를 통째 부르는 대신, *분해된 두 단계* (`image_to_annotations` + 우리가 만든 mvc_cfg 로 `animated_drawings.render.start()`) 직접 호출. mvc_cfg 에 `view: USE_MESA: True` 한 줄이 핵심.

📚 **새 개념 — AD 의 mvc_cfg**: AnimatedDrawings 의 *render pipeline* 설정 dict. scene (캐릭터/모션), controller (mode/output), view (window 또는 mesa) 세 섹션. yaml 파일로 저장 후 `render.start(path)` 가 읽어 실행.

- [ ] **Step 1: ad_runner.py 전체 재작성**

`/home/ingon/AR_book/ad-server/server/app/ad_runner.py`:

```python
"""AnimatedDrawings 호출 wrapper — 외부 의존성을 이 파일 안에 격리.

호출자(routes)는 `run(image_path, motion_id, work_dir) -> gif_path` 만 본다.

🔥 Real call 모드 (이전 stub 에서 전환):
    - image_to_annotations: torchserve 호출 → mask + skeleton 생성
    - 우리가 mvc_cfg yaml 직접 생성 + view.USE_MESA=true (헤드리스 핵심)
    - animated_drawings.render.start(mvc_cfg.yaml) → MesaView → gif

전제 조건 (이 모듈 import 시점 X, run() 호출 시점에 필요):
    - torchserve 가 :8080 에 떠 있음 (drawn_humanoid_detector + pose_estimator)
    - libosmesa6 + libosmesa6-dev 설치됨
    - PyOpenGL osmesa 모듈 import 가능
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

from app import settings
from app.motion_registry import get_motion_yaml, UnknownMotionError  # noqa: F401

# AD 의 examples/ 폴더를 import path 에 추가.
# examples/ 는 패키지가 아니어서 자동 import 안 됨.
_AD_EXAMPLES = settings.AD_REPO_ROOT / "examples"
if str(_AD_EXAMPLES) not in sys.path:
    sys.path.insert(0, str(_AD_EXAMPLES))

# 기본 retarget config — 캐릭터 skeleton ↔ motion skeleton 매핑.
_RETARGET_CFG = _AD_EXAMPLES / "config" / "retarget" / "fair1_ppf.yaml"


class AdError(RuntimeError):
    """AD 처리 실패."""


def run(image_path: Path, motion_id: str, work_dir: Path) -> Path:
    """이미지 → motion 적용 → gif 경로 반환.

    Args:
        image_path: 입력 이미지 (jpg/png)
        motion_id: motion_registry 등록 id
        work_dir: 중간 산출물 저장 디렉토리 (호출자가 만들어서 줌)

    Returns:
        생성된 gif 파일의 절대 경로
    """
    # 1) 입력 검증 (stub 시점과 동일)
    if not image_path.exists():
        raise FileNotFoundError(f"image not found: {image_path}")

    if not _looks_like_image(image_path):
        raise AdError(f"input is not a valid image: {image_path}")

    motion_yaml = get_motion_yaml(motion_id)  # UnknownMotionError 가능

    if not _RETARGET_CFG.exists():
        raise AdError(f"retarget config missing: {_RETARGET_CFG}")

    work_dir.mkdir(parents=True, exist_ok=True)
    char_anno_dir = work_dir / "annotations"
    char_anno_dir.mkdir(exist_ok=True)

    # 2) annotation 생성 — torchserve 호출
    try:
        from image_to_annotations import image_to_annotations
    except ImportError as e:
        raise AdError(f"cannot import AD examples.image_to_annotations: {e}") from e

    try:
        image_to_annotations(str(image_path), str(char_anno_dir))
    except Exception as e:
        raise AdError(f"image_to_annotations failed: {e}") from e

    # 3) mvc_cfg dict 만들기 — view.USE_MESA=true 가 핵심
    output_gif = char_anno_dir / "video.gif"
    mvc_cfg = {
        "scene": {
            "ANIMATED_CHARACTERS": [{
                "character_cfg": str((char_anno_dir / "char_cfg.yaml").resolve()),
                "motion_cfg": str(motion_yaml.resolve()),
                "retarget_cfg": str(_RETARGET_CFG.resolve()),
            }],
        },
        "controller": {
            "MODE": "video_render",
            "OUTPUT_VIDEO_PATH": str(output_gif.resolve()),
        },
        "view": {
            "USE_MESA": True,  # ← 헤드리스 OpenGL 핵심
        },
    }

    # 4) mvc_cfg yaml 저장
    mvc_cfg_path = char_anno_dir / "mvc_cfg.yaml"
    with open(mvc_cfg_path, "w") as f:
        yaml.dump(mvc_cfg, f)

    # 5) render 실행 — MesaView 가 osmesa context 만들어 그림
    try:
        from animated_drawings import render
    except ImportError as e:
        raise AdError(f"cannot import animated_drawings.render: {e}") from e

    try:
        render.start(str(mvc_cfg_path))
    except Exception as e:
        raise AdError(f"AD render failed: {e}") from e

    # 6) 결과 gif 경로 반환
    if not output_gif.exists():
        raise AdError(f"AD produced no output at {output_gif}")
    if output_gif.stat().st_size < 100:
        raise AdError(f"AD output suspiciously small: {output_gif.stat().st_size}B")
    return output_gif


def _looks_like_image(p: Path) -> bool:
    """파일의 첫 바이트로 jpeg/png 매직 확인."""
    head = p.read_bytes()[:4]
    return head.startswith(b"\xff\xd8") or head.startswith(b"\x89PNG")
```

📚 **변경 요약 (stub 대비)**:
- 입력 검증 부분 동일
- `shutil.copy(DUMMY_GIF, ...)` 제거
- AD 호출 5단계 추가 (annotations → mvc_cfg dict → yaml dump → render.start → 결과 검증)
- 모든 외부 호출을 try/except 으로 wrapping → AdError 로 통일 (routes 의 에러 매핑은 그대로 동작)

- [ ] **Step 2: 기존 fast tests 가 여전히 통과하는지 확인**

```bash
cd /home/ingon/AR_book/ad-server/server
source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate animated_drawings
PYTHONPATH= AMENT_PREFIX_PATH= pytest tests/test_ad_runner.py -v -m "not slow"
```
Expected:
```
tests/test_ad_runner.py::test_run_is_callable PASSED
tests/test_ad_runner.py::test_run_raises_ad_error_for_invalid_image PASSED
tests/test_ad_runner.py::test_run_raises_file_not_found_for_missing_image PASSED
tests/test_ad_runner.py::test_run_returns_gif_path  ← 이건 stub 가정이라 변경 필요 (Task 4)
tests/test_ad_runner.py::test_run_raises_unknown_motion PASSED
```

⚠ `test_run_returns_gif_path` 가 *실제 dab.yaml + SAMPLE_DRAWING* 로 ad_runner.run() 호출하는데 — 진짜 호출은 torchserve 가 떠 있어야 가능. 이 테스트는 *slow* marker 로 옮길 것 (Task 4). 지금은 일시 FAIL 또는 SKIP 예상.

✅ **방금 한 일**: ad_runner.py 를 진짜 AD 호출 모드로 교체. mvc_cfg 에 view.USE_MESA=true 박음.
**배운 것**: AD 의 mvc_cfg 구조, image_to_animation 의 내부 분해, exception chaining 의 traceback 보존.

---

## Task 4: slow integration test 부활 + 실제 호출 검증

**Files:**
- Modify: `/home/ingon/AR_book/ad-server/server/tests/test_ad_runner.py`

📚 **이번 task**: 진짜 AD 호출 검증. torchserve + osmesa + render 모두 동작해야 PASS. 1-2분 소요.

- [ ] **Step 1: test_ad_runner.py 의 test_run_returns_gif_path 를 slow 로 변환**

`server/tests/test_ad_runner.py` 의 마지막 두 테스트 (`test_run_returns_gif_path`, `test_run_raises_unknown_motion`) 을 다음으로 교체:

```python
def test_run_raises_unknown_motion(tmp_path: Path):
    """알 수 없는 motion id → UnknownMotionError. 입력 검증 단계라 slow X."""
    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample drawing missing: {SAMPLE_DRAWING}")
    from app.motion_registry import UnknownMotionError
    try:
        ad_run(SAMPLE_DRAWING, "no_such_motion_xyz", tmp_path / "work")
        raise AssertionError("expected UnknownMotionError")
    except UnknownMotionError:
        pass


@pytest.mark.slow
def test_run_real_call_produces_gif(tmp_path: Path):
    """torchserve + osmesa 가 떠 있어야 PASS. 1-2분 소요.

    검증: 진짜 AD 가 도는지 + 결과 GIF 가 정상 크기인지.
    """
    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample drawing missing: {SAMPLE_DRAWING}")
    gif = ad_run(SAMPLE_DRAWING, "dab", tmp_path / "work")
    assert gif.exists(), f"gif missing: {gif}"
    assert gif.suffix == ".gif"
    assert gif.stat().st_size > 1000, f"gif too small: {gif.stat().st_size}B"
```

- [ ] **Step 2: fast tests 전체 PASS 확인**

```bash
cd /home/ingon/AR_book/ad-server/server
source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate animated_drawings
PYTHONPATH= AMENT_PREFIX_PATH= pytest tests/ -v -m "not slow"
```
Expected: 13개 PASS (test_health 1 + test_motion_registry 3 + test_process 5 + test_ad_runner 4).

📚 **왜 13 → 4 가 된 ad_runner**: test_run_is_callable, test_run_raises_ad_error_for_invalid_image, test_run_raises_file_not_found_for_missing_image, test_run_raises_unknown_motion. test_run_returns_gif_path 가 test_run_real_call_produces_gif (slow) 로 이동.

- [ ] **Step 3: torchserve 띄움**

```bash
/home/ingon/AR_book/ad-server/server/scripts/run_torchserve.sh
```
Background 또는 별도 터미널. Healthy 확인:
```bash
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ping)
  if [ "$code" = "200" ]; then echo "torchserve READY after ${i}s"; break; fi
  sleep 1
done
curl -s http://localhost:8081/models | head -20
```
Expected: `READY` + 두 모델 (drawn_humanoid_detector, drawn_humanoid_pose_estimator) 로드.

- [ ] **Step 4: slow integration test 실행 — 진짜 GIF 생성**

```bash
cd /home/ingon/AR_book/ad-server/server
source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate animated_drawings
PYTHONPATH= AMENT_PREFIX_PATH= pytest tests/test_ad_runner.py::test_run_real_call_produces_gif -v -m slow
```
Expected (1-2분 소요): `1 passed`.

⚠ **흔한 실패 시나리오**:
- `AdError: image_to_annotations failed: ...torchserve...` → torchserve 안 떠 있음. Step 3 확인
- `AdError: AD render failed: ...osmesa...` → Task 1/2 의 osmesa 셋업 미흡. ldconfig 확인
- `AdError: AD output suspiciously small: ...` → render 가 부분적으로만 작동. AD log 확인 (`server/logs/`)
- timeout 5분 초과 → 멈추고 사용자에게 보고. SW renderer 가 너무 느리면 Xvfb sub-task 검토

- [ ] **Step 5: 결과 GIF 눈으로 검증**

slow test 가 tmp_path 에 만든 결과는 자동 정리됨. 검증용으로 직접 호출:

```bash
cd /home/ingon/AR_book/ad-server/server
source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate animated_drawings
PYTHONPATH= AMENT_PREFIX_PATH= python <<'PY'
from pathlib import Path
from app.ad_runner import run
gif = run(
    Path("/home/ingon/AR_book/AnimatedDrawings/examples/drawings/garlic.png"),
    "dab",
    Path("/tmp/ad-render-fix-check"),
)
print(f"GIF: {gif} size={gif.stat().st_size}B")
PY
```
Expected: 결과 `/tmp/ad-render-fix-check/annotations/video.gif`.

확인:
```bash
file /tmp/ad-render-fix-check/annotations/video.gif
ls -lh /tmp/ad-render-fix-check/annotations/
xdg-open /tmp/ad-render-fix-check/annotations/video.gif  # WSLg 가 image viewer 띄움
```
Expected: GIF image data, 보통 수십 KB, viewer 에서 garlic 캐릭터가 dab 모션 하는 게 *눈으로 보임*.

- [ ] **Step 6: commit (원본 ad-server 측 git)**

```bash
cd /home/ingon/AR_book/ad-server
git add server/app/ad_runner.py server/tests/test_ad_runner.py
git commit -m "feat(ad-server): activate real AD invocation via MesaView (osmesa)

ad_runner.run() no longer returns dummy.gif. It now:
  1) image_to_annotations (torchserve detection + pose)
  2) builds mvc_cfg with view.USE_MESA=true
  3) calls animated_drawings.render.start()
  4) returns the generated video.gif

Sub-AD-Render-Fix complete. Verified end-to-end with garlic.png + dab.
"
```

✅ **방금 한 일 (Task 4 완료)**: 진짜 AD 호출이 도는 걸 자동 테스트 + 눈으로 검증.
**배운 것**: pytest slow marker 의 활용, torchserve + osmesa + AD 의 전체 dependency 사슬, integration test 의 가치.

---

## Task 5: routes end-to-end curl 검증 — uvicorn 통해서도 작동하는지

**Files:**
- 없음 (검증만)

- [ ] **Step 1: uvicorn 띄움**

```bash
cd /home/ingon/AR_book/ad-server/server
./scripts/run.sh
```
(별도 터미널, background 가능)

확인:
```bash
curl http://localhost:8000/health
# {"status":"ok"}
```

- [ ] **Step 2: torchserve 도 떠 있는지 확인**

Task 4 의 Step 3 와 동일. 안 떠 있으면:
```bash
/home/ingon/AR_book/ad-server/server/scripts/run_torchserve.sh
```

- [ ] **Step 3: curl 실제 호출**

```bash
curl -X POST http://localhost:8000/process \
  -F "image=@/home/ingon/AR_book/AnimatedDrawings/examples/drawings/garlic.png" \
  -F "motion=dab" \
  --output /tmp/real_dab.gif \
  -w "status=%{http_code} ctype=%{content_type} size=%{size_download}B time=%{time_total}s\n"
```
Expected:
```
status=200 ctype=image/gif size=20000+B time=60-120s
```

- [ ] **Step 4: 결과 시각 검증**

```bash
file /tmp/real_dab.gif
# GIF image data, version 89a, ...

xdg-open /tmp/real_dab.gif  # WSLg
```
Expected: garlic 캐릭터가 dab 하는 GIF.

⚠ **이전 단계 (Task 4) 의 결과와 차이**:
- Task 4 = ad_runner.run() 직접 호출
- Task 5 = HTTP 경유, routes.py 가 ad_runner 호출
- 두 결과 GIF 내용 같아야 함 (같은 image + motion + seed)

✅ **방금 한 일**: routes ↔ ad_runner 통합도 진짜 모드에서 정상 동작 확인.
**배운 것**: integration 의 *층위 분리* 검증 — 함수 직접 호출 vs HTTP 경유.

---

## Task 6: README 갱신 + stickerbook repo 동기화 + commit/push

**Files:**
- Modify: `/home/ingon/AR_book/ad-server/README.md` (원본)
- Modify: `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/ad-server/README.md` (Windows)
- Modify: `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/ad-server/server/app/ad_runner.py` (sync)
- Modify: `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/ad-server/server/tests/test_ad_runner.py` (sync)

- [ ] **Step 1: README 의 "현재 동작 상태" 섹션 갱신**

`/home/ingon/AR_book/ad-server/README.md` 의 다음 부분 교체:

기존:
```markdown
## 현재 동작 상태 (2026-05-19)

- ✅ 통신 파이프라인 완성 — 갤탭 → Wi-Fi → portproxy → uvicorn → 갤탭
- ⚠️ AD 호출은 *stub* — 입력 검증 후 dummy.gif 반환. 진짜 GIF 는 `Sub-AD-Render-Fix` (WSL2 OpenGL render) 해결 후 자동 활성화
- 클라이언트 입장에선 응답 형식 동일 → 호출자 코드 변경 X
```

신규:
```markdown
## 현재 동작 상태 (2026-05-19)

- ✅ 통신 파이프라인 완성 — 갤탭 → Wi-Fi → portproxy → uvicorn → 갤탭
- ✅ AD 진짜 호출 활성화 (Sub-AD-Render-Fix 완료) — MesaView (osmesa) 헤드리스 렌더링
- 의존성 추가: system `libosmesa6` + `libosmesa6-dev` (sudo apt install)
- torchserve 가 떠 있어야 진짜 GIF 생성 — `./scripts/run_torchserve.sh`
```

- [ ] **Step 2: README 에 의존성 섹션 추가**

`## PC 재부팅 후 재현 절차` 위에 추가:

```markdown
## 의존성 (한 번만 박는 설정)

### system 패키지

```bash
sudo apt update
sudo apt install -y libosmesa6 libosmesa6-dev
```
확인:
\`\`\`bash
ldconfig -p | grep -i osmesa
# libOSMesa.so.6 또는 .8 보이면 OK
\`\`\`

### conda env 확인

```bash
conda activate animated_drawings
python -c "from OpenGL import osmesa; print('ok')"
```

설치/확인 한 번이면 영구. PC 재부팅 후에도 살아 있음.
```

- [ ] **Step 3: README 의 셋업 절차 B 에 torchserve 단계 추가**

`### B. 매 PC 재부팅 시 셋업` 의 *step 4 (FastAPI 서버 띄움)* 앞에:

```markdown
#### 3.5) torchserve 띄움 (AD 호출 의존성)

```bash
/home/ingon/AR_book/ad-server/server/scripts/run_torchserve.sh
```
(별도 터미널 — Healthy 까지 30초~1분)

검증:
\`\`\`bash
curl -s http://localhost:8080/ping
# {"status":"Healthy"}

curl -s http://localhost:8081/models | grep -E "modelName"
# drawn_humanoid_detector + drawn_humanoid_pose_estimator
\`\`\`

torchserve 가 떠 있어야 `image_to_annotations` 가 가능. 안 떠 있으면 `/process` 가 422 `AD failed`.
```

- [ ] **Step 4: README 의 알려진 한계 갱신**

기존:
```markdown
- **AD 진짜 호출은 stub 상태**: `ad_runner.py` 가 dummy.gif 반환. ...
```

신규 (이 줄 삭제 + 다른 한계 그대로):
```markdown
- **torchserve + osmesa 모두 떠 있어야 진짜 GIF**: 어느 하나 빠지면 422. 셋업 절차 B 참조.
- **AD render 시간이 SW renderer 라 느림**: 30초~수 분/요청 (CPU 코어 수에 비례).
```

- [ ] **Step 5: 원본 ad-server 의 변경을 stickerbook repo 측으로 동기화**

```bash
# 원본 → Windows
cp /home/ingon/AR_book/ad-server/server/app/ad_runner.py \
   /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/ad-server/server/app/ad_runner.py
cp /home/ingon/AR_book/ad-server/server/tests/test_ad_runner.py \
   /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/ad-server/server/tests/test_ad_runner.py
cp /home/ingon/AR_book/ad-server/README.md \
   /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/ad-server/README.md
```

확인:
```bash
diff -q /home/ingon/AR_book/ad-server/server/app/ad_runner.py \
        /mnt/c/Users/leesa/AR_book/stickerbook_android_porting/ad-server/server/app/ad_runner.py
# 빈 결과 = 동일
```

- [ ] **Step 6: stickerbook repo 측 commit + push main**

```bash
cd /mnt/c/Users/leesa/AR_book/stickerbook_android_porting
git add ad-server/server/app/ad_runner.py \
        ad-server/server/tests/test_ad_runner.py \
        ad-server/README.md
git status
git commit -m "feat(ad-server): activate real AD invocation via MesaView (Sub-AD-Render-Fix)

ad_runner.run() activates AD's built-in MesaView by injecting
view.USE_MESA=true into the mvc_cfg dict it builds. AD's image_to_animation
not called directly; instead we call image_to_annotations + render.start
with our own mvc_cfg yaml.

Interface (run/UnknownMotion/AdError) unchanged → routes.py untouched.
Tests: existing 4 fast tests still pass; test_run_real_call_produces_gif
added as @pytest.mark.slow (requires torchserve + osmesa).

README updated:
  - dependency section: libosmesa6 + libosmesa6-dev
  - per-boot setup adds step 3.5 (run_torchserve.sh)
  - known limits: torchserve+osmesa both needed, SW renderer is slow

End-to-end verified with garlic.png + dab → real dab motion GIF.
"
git push origin main 2>&1 | tail -5
```

✅ **방금 한 일 (Task 6 완료)**: 원본 ↔ Windows repo 동기화 + GitHub 반영. 잔디 1칸 더.
**배운 것**: mono-source vs 사본의 관리, 변경 흐름 (원본 first → Windows 측 cp → commit).

---

## Task 7: 갤탭 데모 검증 (선택, 사용자 손)

**Files:**
- 없음 (사용자 시연)

이 task 는 *전체 통신 파이프라인 + 진짜 AD* 가 다 도는지 최종 확인. 사용자 손 필요 (PC Wi-Fi 켜기 + portproxy 갱신 + 갤탭 실행).

- [ ] **Step 1: PC Wi-Fi 켜기 (현재 꺼져 있음)**

작업표시줄 우측 Wi-Fi 아이콘 → 갤탭과 같은 SSID 연결.

PowerShell:
```powershell
(Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'Wi-Fi').IPAddress
```
이전과 같은 `192.168.68.176` 이면 Config.kt 그대로 사용. 다르면 갱신.

- [ ] **Step 2: portproxy 갱신 (WSL IP 가 재부팅 이후 동일 가정)**

WSL:
```bash
hostname -I
# 예: 172.26.167.1 (또는 변경됐을 수 있음)
```

WSL IP 가 README 의 portproxy 값 (`172.26.167.1`) 과 다르면 PowerShell **관리자**:
```powershell
netsh interface portproxy delete v4tov4 listenport=8000 listenaddress=0.0.0.0
netsh interface portproxy add v4tov4 listenport=8000 listenaddress=0.0.0.0 connectport=8000 connectaddress=<현재 WSL IP>
```

- [ ] **Step 3: torchserve + uvicorn 둘 다 떠 있는지**

```bash
curl -s http://localhost:8080/ping  # torchserve Healthy
curl -s http://localhost:8000/health  # uvicorn ok
curl -s http://192.168.68.176:8000/health  # 외부 NIC 도달
```
셋 다 OK 면 진행.

- [ ] **Step 4: 갤탭 AD Client 앱 실행**

USB 연결 → Android Studio ▶ Run (Shift+F10) — *Debug 아님*.

- [ ] **Step 5: 시연**

1. **이미지 선택** → 갤러리 → 막대 사람 그림 또는 garlic.png 비슷한 거 (jpeg/png)
2. **Motion Spinner** → `dab` 또는 `wave_hello` 또는 `jumping`
3. **합치기** 버튼
4. "서버 처리 중…" → **30초~수 분** (SW rendering)
5. 하단 회색 칸에 **캐릭터가 모션 하는 GIF** 가 자동 재생

이전과 차이: 색 변하는 dummy 가 아니라 **선택한 그림이 움직임**.

⚠ 실패 시 statusText 메시지 보고:
- `에러 -1: failed to connect ...` → 네트워크 (Wi-Fi/IP/portproxy)
- `에러 422: AD failed: image_to_annotations failed: ...` → torchserve 안 떠 있음
- `에러 422: AD failed: AD render failed: ...osmesa...` → libosmesa6 미설치 또는 PyOpenGL 문제
- `에러 422: AD failed: input is not a valid image` → 갤러리에서 jpeg/png 선택했는지

✅ **Sub-AD-Render-Fix 최종 검증 완료**: 갤탭에서 *진짜 캐릭터 모션* 을 보는 것.

---

# Self-Review 결과 (구현자 참고용)

spec 의 §6 검증 절차 8단계 ↔ plan task 매핑:

| spec §6 단계 | plan task |
|---|---|
| 1. apt install libosmesa6 | Task 1 |
| 2. osmesa python import | Task 1 |
| 3. torchserve 시작 + 두 모델 | Task 4 Step 3 (및 Task 5 Step 2, Task 7 Step 3) |
| 4. headless GL sanity | Task 2 |
| 5. ad_runner fast tests | Task 3 Step 2 + Task 4 Step 2 |
| 6. ad_runner slow integration | Task 4 Step 4 |
| 7. routes end-to-end curl | Task 5 Step 3 |
| 8. 갤탭 데모 | Task 7 |

spec §9 검수 체크리스트 ↔ task 매핑:

| spec §9 항목 | task |
|---|---|
| libosmesa6 설치 + ldconfig | Task 1 |
| osmesa import | Task 1 |
| torchserve mar 2개 | Task 4 Step 3 |
| fast pytest 5개 PASS | Task 4 Step 2 (정확히는 ad_runner 4 + 전체 13) |
| slow pytest test_run_real_call_produces_gif | Task 4 Step 4 |
| curl POST /process 진짜 GIF | Task 5 Step 3-4 |
| 갤탭 데모 | Task 7 |
| README 갱신 | Task 6 Step 1-4 |

Gap: 없음.

---

# 후속 작업 (이 plan 밖)

- AD upstream 으로 `view.USE_MESA` default 변경 PR (OSS 기여 — 옵션)
- Xvfb fallback sub-task (이 plan 실패 시)
- 결과 GIF 캐싱 (image+motion 해시 기반)
- AD render 성능 측정 + 가능 시 GPU passthrough 검토
- 비동기 job 패턴 (5분 초과 요청)