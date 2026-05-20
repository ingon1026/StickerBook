# ad-server MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PC FastAPI 서버 + Android 테스트 앱으로 "그림+motion → GIF" 의 client-server 데모를 만든다. Spec: `docs/superpowers/specs/2026-05-19-ad-server-design.md`

**Architecture:** 동기 HTTP. 클라이언트가 multipart 로 이미지+motion 송신 → 서버가 AnimatedDrawings 를 in-process 호출해 GIF 생성 → image/gif binary 로 응답. PC ↔ 회사 서버 swap 은 클라이언트의 `BASE_URL` 한 줄로 처리.

**Tech Stack:** Python 3.10+, FastAPI, uvicorn, python-multipart, AnimatedDrawings (로컬 `/home/ingon/AR_book/AnimatedDrawings/`), imageio (gif 변환), pytest. Android: Kotlin, OkHttp, Glide.

---

## 🎓 Learning-First Mode (구현자 필독)

이 plan 의 사용자는 client-server / HTTP / 백엔드를 **거의 처음** 다룬다. spec §0 가 명시한 작업 모드를 plan 전체에 적용한다:

1. **새 개념이 등장하는 step 앞에 "📚 새 개념"** 박스로 1-3줄 설명
2. **CLI 옵션은 의미를 한 줄씩**
3. **에러 발생 시 원인 먼저 설명 → 그 다음 수정**
4. **각 Task 끝에 "✅ 방금 한 일 / 배운 것"** 1줄씩 정리
5. **잘게 쪼갠 commit** — 각 task 끝마다 한 번씩. 학습 흔적이 git log 에 남게

---

## File Structure (이 plan 에서 만들 / 수정할 파일)

```
ad-server/
├── README.md                            (Task 1 생성)
├── .gitignore                           (Task 1 생성)
├── server/
│   ├── requirements.txt                 (Task 1 생성)
│   ├── app/
│   │   ├── __init__.py                  (Task 2 생성)
│   │   ├── main.py                      (Task 2 생성, Task 4 수정)
│   │   ├── routes.py                    (Task 2 생성, Task 4·8 수정)
│   │   ├── settings.py                  (Task 2 생성)
│   │   ├── motion_registry.py           (Task 5 생성)
│   │   └── ad_runner.py                 (Task 6 생성, Task 7 확장)
│   ├── assets/dummy.gif                 (Task 4 생성)
│   ├── jobs/                            (런타임 생성, gitignore)
│   ├── scripts/run.sh                   (Task 2 생성)
│   └── tests/
│       ├── __init__.py                  (Task 2 생성)
│       ├── test_health.py               (Task 2 생성)
│       ├── test_process_stub.py         (Task 4 생성)
│       ├── test_motion_registry.py      (Task 5 생성)
│       └── test_ad_runner.py            (Task 6·7 생성)
│
├── android-client/                      (Task 10~ Android Studio 자동 생성)
│   └── app/src/main/
│       ├── AndroidManifest.xml          (Task 11 수정)
│       ├── res/layout/activity_main.xml (Task 13 수정)
│       └── java/com/k3i/adclient/
│           ├── MainActivity.kt          (Task 13·14 수정)
│           ├── net/Config.kt            (Task 12 생성)
│           └── net/AdApi.kt             (Task 12 생성)
│
└── shared/API.md                        (Task 9 생성 — 서버 완성 시점)
```

**파일 책임 원칙**:
- `routes.py` 는 HTTP 만 — AD 인자/내부를 모름
- `ad_runner.py` 가 AD 와의 유일한 접점 — 외부 의존성을 경계에 격리
- `Config.kt` 가 endpoint swap 의 유일 지점

---

# Part A — 서버 (M1 ~ M3)

## Task 1: 프로젝트 폴더 + venv + requirements

**Files:**
- Create: `ad-server/README.md`
- Create: `ad-server/.gitignore`
- Create: `ad-server/server/requirements.txt`

📚 **새 개념 — venv (virtual environment)**: Python 의존성을 시스템 전역이 아닌 폴더별로 격리하는 표준 방식. `python -m venv .venv` 로 만들고 `source .venv/bin/activate` 로 진입.

📚 **새 개념 — requirements.txt**: pip 으로 설치할 패키지 목록. `pip install -r requirements.txt` 로 한 번에 설치.

- [ ] **Step 1: `.gitignore` 작성**

```gitignore
# Python
__pycache__/
*.py[cod]
.venv/
*.egg-info/

# 작업 임시 폴더
server/jobs/

# Android
android-client/.gradle/
android-client/build/
android-client/app/build/
android-client/.idea/
android-client/local.properties

# OS
.DS_Store
Thumbs.db
```

- [ ] **Step 2: `requirements.txt` 작성**

```text
fastapi==0.115.0
uvicorn[standard]==0.30.6
python-multipart==0.0.12
imageio==2.36.0
imageio-ffmpeg==0.5.1
pytest==8.3.3
httpx==0.27.2
```

> 옵션 설명:
> - `fastapi` — 서버 프레임워크
> - `uvicorn[standard]` — `[standard]` 옵션이 websocket/reload 같은 extras 포함 (없으면 기본 기능만)
> - `python-multipart` — FastAPI 가 파일 업로드 받으려면 필수
> - `imageio` + `imageio-ffmpeg` — AD 가 mp4 만 뱉을 경우 GIF 변환
> - `httpx` — pytest 에서 FastAPI 호출용 (TestClient 의존성)

- [ ] **Step 3: `README.md` 작성**

```markdown
# ad-server

태블릿 → PC 서버 → AnimatedDrawings → GIF 반환 데모.

## Quickstart

### 서버 (PC)
\`\`\`bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
# AnimatedDrawings 설치 (로컬 경로)
pip install -e ../../AnimatedDrawings
./scripts/run.sh
\`\`\`

### 클라이언트 (Android)
Android Studio 에서 `android-client/` 열기 → `app/src/main/java/com/k3i/adclient/net/Config.kt` 의 `BASE_URL` 을 PC IP 로 수정 → 갤탭에 빌드/설치.

### LAN 검증
\`\`\`bash
curl http://<PC-IP>:8000/health
\`\`\`
```

- [ ] **Step 4: venv 생성 + 설치**

Run:
```bash
cd /home/ingon/AR_book/ad-server/server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```
Expected: 모든 패키지가 에러 없이 설치됨. 마지막 줄에 `Successfully installed ...`

> ⚠ AnimatedDrawings 자체 설치는 Task 7 에서 진행 (M3 에서 처음 필요).

- [ ] **Step 5: commit (사용자 허락 후)**

ad-server/ 가 git repo 가 아니면 먼저 init 여부를 사용자에게 묻는다. init 후:
```bash
cd /home/ingon/AR_book/ad-server
git add README.md .gitignore server/requirements.txt
git commit -m "chore: initial project skeleton (server requirements + gitignore)"
```

✅ **방금 한 일**: 폴더 만들고, venv 생성, 의존성 설치, README 작성.
**배운 것**: venv 의 의미, requirements.txt 의 역할, uvicorn 의 `[standard]` extras 가 뭔지.

---

## Task 2: FastAPI 골격 + GET /health (M1 핵심)

**Files:**
- Create: `server/app/__init__.py` (빈 파일)
- Create: `server/app/main.py`
- Create: `server/app/routes.py`
- Create: `server/app/settings.py`
- Create: `server/scripts/run.sh`
- Create: `server/tests/__init__.py` (빈 파일)
- Test: `server/tests/test_health.py`

📚 **새 개념 — FastAPI app 인스턴스**: `app = FastAPI()` 한 줄이 HTTP 서버의 모든 routing 을 들고 있는 객체. uvicorn 은 이 객체를 받아 listen 시킴 (`uvicorn app.main:app` 의 `app.main:app` 이 곧 "main.py 의 app 변수").

📚 **새 개념 — APIRouter**: 라우트를 여러 파일로 분리하는 도구. `app.include_router(router)` 로 등록. 단일 파일 main.py 도 가능하지만, 라우트 늘면 분리하는 게 표준.

📚 **새 개념 — TestClient**: 진짜 서버 띄우지 않고 메모리 안에서 FastAPI 앱을 호출. unit test 에서 사용. 실제 HTTP 와 동일한 결과 (status, body) 를 빠르게 확인.

- [ ] **Step 1: `server/tests/test_health.py` 작성 (failing test 먼저)**

```python
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_health_returns_ok():
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run:
```bash
cd /home/ingon/AR_book/ad-server/server
source .venv/bin/activate
pytest tests/test_health.py -v
```
Expected: `ModuleNotFoundError: No module named 'app'` (아직 코드 없음). 이 에러는 *정상* — 다음 step 에서 만든다.

📚 **에러 해석**: `ModuleNotFoundError` = 그 이름의 파이썬 패키지/파일이 import 경로에 없음. 곧 만들 거라 OK.

- [ ] **Step 3: `server/app/__init__.py` + `server/tests/__init__.py` 빈 파일 생성**

```bash
touch /home/ingon/AR_book/ad-server/server/app/__init__.py
touch /home/ingon/AR_book/ad-server/server/tests/__init__.py
```

📚 **새 개념**: 빈 `__init__.py` 가 폴더를 "Python 패키지" 로 만든다. 없으면 `from app.main import app` 같은 import 안 됨.

- [ ] **Step 4: `server/app/settings.py` 작성**

```python
"""런타임 설정 — 환경변수가 우선, 기본값으로 fallback."""
import os
from pathlib import Path

HOST = os.getenv("AD_SERVER_HOST", "0.0.0.0")
PORT = int(os.getenv("AD_SERVER_PORT", "8000"))

# 작업 임시 디렉토리
JOBS_DIR = Path(os.getenv("AD_JOBS_DIR", "/tmp/ad-server-jobs"))
JOBS_DIR.mkdir(parents=True, exist_ok=True)

# AD 경로 (Task 6 에서 사용)
AD_REPO_ROOT = Path(os.getenv("AD_REPO_ROOT", "/home/ingon/AR_book/AnimatedDrawings"))
```

📚 **새 개념 — 환경변수 기반 설정**: 코드를 안 고쳐도 PORT 같은 값을 바꿀 수 있게 `os.getenv()` 사용. 회사 서버 이관 시 유용.

- [ ] **Step 5: `server/app/routes.py` 작성**

```python
"""HTTP 라우트 정의."""
from fastapi import APIRouter

router = APIRouter()


@router.get("/health")
def health():
    return {"status": "ok"}
```

- [ ] **Step 6: `server/app/main.py` 작성**

```python
"""FastAPI 앱 진입점.

실행: uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from fastapi import FastAPI

from app.routes import router

app = FastAPI(title="ad-server", version="0.1.0")
app.include_router(router)
```

- [ ] **Step 7: 테스트 재실행 → 통과 확인**

Run:
```bash
cd /home/ingon/AR_book/ad-server/server
pytest tests/test_health.py -v
```
Expected: `1 passed`.

📚 **에러 시 대처**: 만약 여전히 `ModuleNotFoundError: app` → 현재 디렉토리 확인 (`server/` 안에서 실행해야 함). `conftest.py` 가 필요하면 빈 파일로 추가.

- [ ] **Step 8: `server/scripts/run.sh` 작성**

```bash
#!/usr/bin/env bash
# 서버 실행 스크립트.
# uvicorn 옵션:
#   --host 0.0.0.0  : 모든 NIC 노출 (LAN 의 다른 기기에서 접속 가능)
#                     127.0.0.1 이면 같은 PC 안에서만 접속됨
#   --port 8000     : listen 포트
#   --reload        : 코드 수정 시 자동 재시작 (개발 편의)
set -euo pipefail
cd "$(dirname "$0")/.."
source .venv/bin/activate
exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

Run:
```bash
chmod +x /home/ingon/AR_book/ad-server/server/scripts/run.sh
```

- [ ] **Step 9: 서버 실행 + 로컬 curl 검증**

Terminal 1:
```bash
cd /home/ingon/AR_book/ad-server/server
./scripts/run.sh
```
Expected: `Uvicorn running on http://0.0.0.0:8000`.

Terminal 2:
```bash
curl http://localhost:8000/health
```
Expected: `{"status":"ok"}`

- [ ] **Step 10: LAN 검증 (다른 기기에서 호출)**

PC 의 LAN IP 확인:
```bash
ip addr show | grep "inet " | grep -v 127.0.0.1
```
예: `inet 192.168.1.50/24`.

다른 기기 (스마트폰/갤탭/다른 PC) 의 브라우저 또는 터미널에서:
```
http://192.168.1.50:8000/health
```
Expected: 동일한 `{"status":"ok"}`.

⚠ **막힐 가능성** — Windows 방화벽이 8000 포트를 차단. WSL 의 경우 *Windows 측* 방화벽 인바운드 규칙에서 포트 8000 허용. 갤탭과 PC 가 *같은 SSID* 인지도 확인.

- [ ] **Step 11: commit**

```bash
cd /home/ingon/AR_book/ad-server
git add server/app server/tests server/scripts/run.sh
git commit -m "feat(server): FastAPI skeleton with GET /health (M1)"
```

✅ **방금 한 일 (M1 완료)**: FastAPI 앱 띄움, /health 엔드포인트, LAN 노출 검증.
**배운 것**: FastAPI 의 app/router 구조, `0.0.0.0` 의 의미, TestClient, env var 기반 설정, LAN 통신 검증법.

---

## Task 3: POST /process stub + dummy GIF (M2 핵심)

**Files:**
- Create: `server/assets/dummy.gif`
- Modify: `server/app/routes.py`
- Modify: `server/app/settings.py` (assets 경로 추가)
- Test: `server/tests/test_process_stub.py`

📚 **새 개념 — multipart/form-data**: HTML form 이 파일+텍스트를 함께 업로드할 때 쓰는 인코딩. boundary 라는 구분자로 필드를 나눠 보낸다. JSON 으론 바이너리(이미지) 못 보냄.

📚 **새 개념 — FastAPI 의 `UploadFile` / `Form`**: route 인자에 `image: UploadFile = File(...)`, `motion: str = Form(...)` 로 선언하면 FastAPI 가 multipart 알아서 파싱.

📚 **새 개념 — `FileResponse` / `StreamingResponse`**: 응답 body 를 파일 또는 stream 으로 반환. `Content-Type: image/gif` 헤더 자동 설정.

- [ ] **Step 1: 더미 GIF 만들기**

```bash
cd /home/ingon/AR_book/ad-server/server
mkdir -p assets
python -c "
import imageio.v3 as iio
import numpy as np
# 5 프레임, 64x64 컬러 변화하는 더미 gif
frames = [np.full((64,64,3), [i*50 % 255, 100, 200], dtype=np.uint8) for i in range(5)]
iio.imwrite('assets/dummy.gif', frames, duration=200, loop=0)
print('dummy.gif created')
"
```
Expected: `dummy.gif created`. `ls -lh assets/dummy.gif` 로 파일 존재 확인 (수 KB).

📚 **새 개념 — `imageio` 의 `iio.imwrite`**: numpy array 리스트를 GIF/MP4/PNG 등으로 저장. duration 은 프레임당 ms.

- [ ] **Step 2: `server/tests/test_process_stub.py` 작성 (failing test)**

```python
"""POST /process 의 stub 동작 검증 — 어떤 입력이든 dummy GIF 반환."""
import io
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_process_accepts_multipart_and_returns_gif():
    fake_image = io.BytesIO(b"\xff\xd8\xff\xd9")  # 최소한의 jpeg signature
    response = client.post(
        "/process",
        files={"image": ("drawing.jpg", fake_image, "image/jpeg")},
        data={"motion": "wave"},
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("image/gif")
    assert len(response.content) > 0


def test_process_rejects_missing_image():
    response = client.post("/process", data={"motion": "wave"})
    assert response.status_code in (400, 422)


def test_process_rejects_missing_motion():
    fake_image = io.BytesIO(b"\xff\xd8\xff\xd9")
    response = client.post(
        "/process",
        files={"image": ("drawing.jpg", fake_image, "image/jpeg")},
    )
    assert response.status_code in (400, 422)
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run:
```bash
pytest tests/test_process_stub.py -v
```
Expected: 첫 번째 테스트 `404 Not Found` (라우트 없음) 또는 비슷한 실패.

- [ ] **Step 4: `settings.py` 에 assets 경로 추가**

`server/app/settings.py` 끝에 추가:
```python
ASSETS_DIR = Path(__file__).resolve().parent.parent / "assets"
DUMMY_GIF = ASSETS_DIR / "dummy.gif"
```

📚 **새 개념 — `Path(__file__).resolve().parent`**: 현재 파일의 절대경로 → 부모 디렉토리. 작업 디렉토리에 의존 않고 파일 위치 기준으로 경로 계산.

- [ ] **Step 5: `routes.py` 에 POST /process stub 추가**

`server/app/routes.py` 전체:
```python
"""HTTP 라우트 정의."""
import uuid
from pathlib import Path

from fastapi import APIRouter, File, Form, UploadFile, HTTPException
from fastapi.responses import FileResponse

from app import settings

router = APIRouter()


@router.get("/health")
def health():
    return {"status": "ok"}


@router.post("/process")
async def process(
    image: UploadFile = File(...),
    motion: str = Form(...),
):
    """이미지 + motion 받아 GIF 반환 — 현재는 stub (dummy.gif)."""
    if not image.filename:
        raise HTTPException(status_code=400, detail="missing field: image")

    # 작업 디렉토리 만들고 input 저장 (학습/디버깅용 — 실제 처리는 Task 7 에서)
    job_id = uuid.uuid4().hex[:8]
    job_dir = settings.JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    input_path = job_dir / image.filename
    input_path.write_bytes(await image.read())

    print(f"[/process] job={job_id} motion={motion} image={image.filename} "
          f"size={input_path.stat().st_size}B")

    # 현재 stub: 처리 없이 dummy gif 반환
    return FileResponse(
        path=settings.DUMMY_GIF,
        media_type="image/gif",
        filename="result.gif",
    )
```

📚 **새 개념 — `File(...)` 와 `Form(...)` 의 `...`**: FastAPI 의 "필수 인자" 표시 (Ellipsis literal). 없으면 422 응답 자동 발생.

📚 **새 개념 — `async def` 와 `await image.read()`**: FastAPI 는 async 기반. 파일 stream 읽기는 await 으로. 작은 파일은 동기 read 해도 무방하지만 표준 패턴은 await.

- [ ] **Step 6: 테스트 재실행 → 통과 확인**

Run:
```bash
pytest tests/test_process_stub.py -v
```
Expected: `3 passed`.

- [ ] **Step 7: 서버 띄워 curl 검증**

Terminal 1: `./scripts/run.sh`

Terminal 2:
```bash
# 아무 이미지나 사용 (없으면 dummy.gif 자체를 보내도 됨)
curl -X POST http://localhost:8000/process \
  -F "image=@assets/dummy.gif" \
  -F "motion=wave" \
  --output /tmp/result.gif

file /tmp/result.gif
# Expected: /tmp/result.gif: GIF image data, version 89a, 64 x 64
```

`xdg-open /tmp/result.gif` 또는 image viewer 로 열어 색 변하는 더미 애니메이션 확인.

- [ ] **Step 8: 다른 기기에서 curl 검증 (LAN round-trip)**

스마트폰/갤탭 또는 다른 PC 에서:
```bash
curl -X POST http://<PC-IP>:8000/process \
  -F "image=@some.jpg" \
  -F "motion=jump" \
  --output result.gif
```
Expected: result.gif 가 dummy 와 동일.

📚 **이 시점 의미**: client-server 통신 골격이 완성. AD 가 망가져도 통신은 안전. 이게 *통신과 처리의 분리* 의 실전 이점.

- [ ] **Step 9: commit**

```bash
git add server/app/routes.py server/app/settings.py server/assets server/tests/test_process_stub.py
git commit -m "feat(server): POST /process stub returns dummy GIF (M2)"
```

✅ **방금 한 일 (M2 완료)**: multipart 받기, dummy GIF 반환, LAN round-trip 검증.
**배운 것**: multipart/form-data 의 동작, FastAPI 의 `UploadFile`/`Form`, `FileResponse`, 통신 골격과 처리 로직의 분리.

---

## Task 4: motion_registry.py — motion id → yaml 경로

**Files:**
- Create: `server/app/motion_registry.py`
- Test: `server/tests/test_motion_registry.py`

📚 **새 개념 — registry 패턴**: 이름(id)을 실제 자원(path/객체)에 매핑하는 dict 의 얇은 wrapper. 호출자가 내부 경로 구조 몰라도 됨. 새 motion 추가 시 이 파일만 수정.

- [ ] **Step 1: `tests/test_motion_registry.py` 작성**

```python
import pytest
from app.motion_registry import get_motion_yaml, list_motions, UnknownMotionError


def test_known_motion_returns_yaml_path():
    p = get_motion_yaml("dab")
    assert str(p).endswith("dab.yaml")
    assert p.exists()


def test_unknown_motion_raises():
    with pytest.raises(UnknownMotionError):
        get_motion_yaml("nonexistent_motion_xyz")


def test_list_motions_returns_ids():
    ids = [m["id"] for m in list_motions()]
    assert "dab" in ids
    assert len(ids) >= 1
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

```bash
pytest tests/test_motion_registry.py -v
```
Expected: `ModuleNotFoundError: app.motion_registry`.

- [ ] **Step 3: AD 의 motion config 디렉토리 확인**

```bash
ls /home/ingon/AR_book/AnimatedDrawings/examples/config/motion/
```
Expected: `dab.yaml`, `jumping.yaml`, `wave_hello.yaml` 같은 파일들 나열.

📚 **확인 포인트**: 실제 파일명이 가정과 다를 수 있음. registry 에선 *실제 존재하는* yaml 파일만 등록한다. spec 의 표 (dab/wave/jump) 와 실제 파일명이 다르면 실제 파일명에 맞춰 registry 채우기.

- [ ] **Step 4: `server/app/motion_registry.py` 작성**

```python
"""Motion id ↔ AD yaml 경로 매핑.

새 motion 추가 시 _REGISTRY 만 수정. 호출자는 yaml 경로 구조 모름.
"""
from pathlib import Path
from typing import TypedDict

from app import settings


class UnknownMotionError(KeyError):
    """알 수 없는 motion id."""


class MotionInfo(TypedDict):
    id: str
    label: str
    yaml: Path


_MOTION_CFG_DIR = settings.AD_REPO_ROOT / "examples" / "config" / "motion"

# Step 3 에서 ls 한 실제 파일명에 맞춰 보정.
_REGISTRY: dict[str, MotionInfo] = {
    "dab": {
        "id": "dab",
        "label": "Dab",
        "yaml": _MOTION_CFG_DIR / "dab.yaml",
    },
    "wave_hello": {
        "id": "wave_hello",
        "label": "Wave Hello",
        "yaml": _MOTION_CFG_DIR / "wave_hello.yaml",
    },
    "jumping": {
        "id": "jumping",
        "label": "Jumping",
        "yaml": _MOTION_CFG_DIR / "jumping.yaml",
    },
}


def get_motion_yaml(motion_id: str) -> Path:
    info = _REGISTRY.get(motion_id)
    if info is None:
        raise UnknownMotionError(f"unknown motion: {motion_id}")
    yaml = info["yaml"]
    if not yaml.exists():
        raise FileNotFoundError(f"motion yaml missing on disk: {yaml}")
    return yaml


def list_motions() -> list[MotionInfo]:
    return list(_REGISTRY.values())
```

📚 **새 개념 — TypedDict**: dict 의 키 구조를 타입으로 표현. 호출자가 어떤 키가 있는지 IDE 자동완성으로 확인 가능.

- [ ] **Step 5: 테스트의 motion id 보정**

Step 3 에서 확인한 실제 파일명에 따라 `tests/test_motion_registry.py` 의 `"dab"` 도 그대로 두거나 다른 id 로 변경. 테스트는 실제 존재하는 motion 으로 검증해야 함.

- [ ] **Step 6: 테스트 재실행 → 통과 확인**

```bash
pytest tests/test_motion_registry.py -v
```
Expected: `3 passed`.

⚠ **실패 케이스**: `FileNotFoundError: motion yaml missing on disk` → AD repo 경로 (`AD_REPO_ROOT`) 가 settings 에 잘못 박혔거나 실제 yaml 파일 이름이 다름. Step 3 의 ls 결과로 보정.

- [ ] **Step 7: commit**

```bash
git add server/app/motion_registry.py server/tests/test_motion_registry.py
git commit -m "feat(server): motion registry (id → yaml path)"
```

✅ **방금 한 일**: motion id 와 AD yaml 사이의 매핑 단일 진입점.
**배운 것**: registry 패턴, AD 의 motion config 가 어디 있는지, TypedDict.

---

## Task 5: ad_runner.py — 인터페이스 정의 + AD 미설치 fallback

**Files:**
- Create: `server/app/ad_runner.py`
- Test: `server/tests/test_ad_runner.py`

이 task 는 *인터페이스* 만 먼저 만든다. 실제 AD 호출은 Task 6. 이렇게 분리하면 routes 통합 (Task 7) 을 stub 으로 먼저 검증할 수 있다.

📚 **새 개념 — interface-first**: 외부 라이브러리에 의존하기 전에 "내가 원하는 함수 시그니처" 를 먼저 정의. 호출자가 미리 작성 가능. 나중에 구현체만 갈아끼움.

- [ ] **Step 1: `tests/test_ad_runner.py` 작성 (인터페이스 검증)**

```python
"""ad_runner 의 인터페이스 검증. 실제 AD 호출은 Task 6 의 별도 테스트."""
from pathlib import Path

from app.ad_runner import run as ad_run, AdError


def test_run_signature():
    # 함수가 (image_path, motion_id) 받고 Path 반환하는지
    # 실제 호출은 Task 6 에서.
    assert callable(ad_run)


def test_run_raises_ad_error_for_invalid_image(tmp_path: Path):
    bogus = tmp_path / "not_an_image.txt"
    bogus.write_text("hello")
    try:
        ad_run(bogus, "dab", tmp_path / "out")
        raise AssertionError("expected AdError")
    except AdError:
        pass
```

📚 **새 개념 — pytest fixture `tmp_path`**: 테스트마다 격리된 임시 디렉토리 자동 제공. 정리도 pytest 가 함.

- [ ] **Step 2: 테스트 실행 → 실패 확인**

```bash
pytest tests/test_ad_runner.py -v
```
Expected: `ModuleNotFoundError: app.ad_runner`.

- [ ] **Step 3: `server/app/ad_runner.py` 작성 (skeleton)**

```python
"""AnimatedDrawings 호출 wrapper — 외부 의존성을 이 파일 안에 격리.

호출자(routes)는 `run(image_path, motion_id, work_dir) -> gif_path` 만 본다.
AD 가 바뀌어도 이 파일만 수정.
"""
from pathlib import Path

from app.motion_registry import get_motion_yaml, UnknownMotionError


class AdError(RuntimeError):
    """AD 처리 실패 (라이브러리 호출 안 풀림, 캐릭터 인식 실패 등)."""


def run(image_path: Path, motion_id: str, work_dir: Path) -> Path:
    """이미지를 motion 으로 애니메이트해 GIF 경로 반환.

    Args:
        image_path: 입력 이미지 (jpg/png)
        motion_id: motion_registry 에 등록된 id
        work_dir: AD 가 중간 산출물 저장할 디렉토리 (호출자가 만들어서 줌)

    Returns:
        생성된 gif 파일의 절대 경로

    Raises:
        AdError: AD 처리 실패
        UnknownMotionError: motion_id 미등록
        FileNotFoundError: image_path 없음
    """
    if not image_path.exists():
        raise FileNotFoundError(f"image not found: {image_path}")

    motion_yaml = get_motion_yaml(motion_id)  # raises UnknownMotionError

    # Task 6 에서 실제 AD 호출로 교체. 지금은 input 이 이미지가 아니면 AdError.
    if not _looks_like_image(image_path):
        raise AdError(f"input is not a valid image: {image_path}")

    raise NotImplementedError("Task 6 에서 AD 호출 구현")


def _looks_like_image(p: Path) -> bool:
    """파일의 첫 바이트로 jpeg/png 매직 확인."""
    head = p.read_bytes()[:4]
    return head.startswith(b"\xff\xd8") or head.startswith(b"\x89PNG")
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

```bash
pytest tests/test_ad_runner.py -v
```
Expected: `2 passed`. (signature test 는 callable 만 확인, AdError test 는 magic 검사로 raise.)

- [ ] **Step 5: commit**

```bash
git add server/app/ad_runner.py server/tests/test_ad_runner.py
git commit -m "feat(server): ad_runner interface (skeleton, raises NotImplementedError)"
```

✅ **방금 한 일**: AD 호출의 *인터페이스* 만 확정. routes 가 미리 의존 가능.
**배운 것**: interface-first 설계, pytest `tmp_path`, 매직 바이트로 파일 타입 빠른 검사.

---

## Task 6: ad_runner — 실제 AD 호출 (M3 핵심)

**Files:**
- Modify: `server/app/ad_runner.py`
- Modify: `server/tests/test_ad_runner.py` (real call test 추가)
- Modify: `server/requirements.txt` (AD pip install 명시 옵션)

📚 **새 개념 — in-process import vs subprocess**: 같은 Python 프로세스에서 import 하면 함수 호출이 일반 함수처럼 빠름. subprocess 는 OS 프로세스 새로 띄우는 거라 느리고 디버깅 어려움. AD 는 in-process 가 정답.

📚 **새 개념 — AD 의 두 단계**:
1. `image_to_annotations(img_fn, char_anno_dir)`: 이미지 → mask + skeleton json
2. `annotations_to_animation(char_anno_dir, motion_cfg, retarget_cfg)`: annotation + motion → mp4

- [ ] **Step 1: AD 설치**

```bash
cd /home/ingon/AR_book/ad-server/server
source .venv/bin/activate
pip install -e /home/ingon/AR_book/AnimatedDrawings
```

📚 **`-e` (editable install)**: AD 소스 경로를 그대로 import 가능하게. AD 코드 수정 시 재설치 불필요.

⚠ **실패 가능**: AD 가 무거운 deps (torch, mmcv) 설치 시도. 이미 시스템에 있으면 skip. 디스크/CUDA 의존성 충돌 가능 → 사용자에게 보고하고 해결책 상의.

검증:
```bash
python -c "from examples.image_to_animation import image_to_animation; print('AD ok')"
```
Expected: `AD ok`. 단, AD examples 디렉토리가 sys.path 에 있어야 함 — 다음 step 에서 처리.

- [ ] **Step 2: AD examples 디렉토리를 import 가능하게**

AD 의 `examples/` 폴더는 패키지 아니라 standalone scripts. 우리 코드에서 import 하려면 sys.path 에 추가 필요.

`server/app/ad_runner.py` 상단에 추가:
```python
import sys
from app import settings

_AD_EXAMPLES = settings.AD_REPO_ROOT / "examples"
if str(_AD_EXAMPLES) not in sys.path:
    sys.path.insert(0, str(_AD_EXAMPLES))
```

검증:
```bash
python -c "
import sys; sys.path.insert(0, '/home/ingon/AR_book/AnimatedDrawings/examples')
from image_to_animation import image_to_animation
print('ok')
"
```
Expected: `ok`.

- [ ] **Step 3: `ad_runner.run` 본체 구현**

`server/app/ad_runner.py` 전체 (Task 5 skeleton 교체):
```python
"""AnimatedDrawings 호출 wrapper — 외부 의존성을 이 파일 안에 격리."""
import sys
from pathlib import Path

from app import settings
from app.motion_registry import get_motion_yaml, UnknownMotionError

_AD_EXAMPLES = settings.AD_REPO_ROOT / "examples"
if str(_AD_EXAMPLES) not in sys.path:
    sys.path.insert(0, str(_AD_EXAMPLES))

# AD config 의 default retarget — Sub-1 시점 기준 fair1_ppf 가 표준
_RETARGET_CFG = _AD_EXAMPLES / "config" / "retarget" / "fair1_ppf.yaml"


class AdError(RuntimeError):
    """AD 처리 실패."""


def run(image_path: Path, motion_id: str, work_dir: Path) -> Path:
    """이미지 → motion 적용 → gif 경로 반환."""
    if not image_path.exists():
        raise FileNotFoundError(f"image not found: {image_path}")

    if not _looks_like_image(image_path):
        raise AdError(f"input is not a valid image: {image_path}")

    motion_yaml = get_motion_yaml(motion_id)

    if not _RETARGET_CFG.exists():
        raise AdError(f"retarget config missing: {_RETARGET_CFG}")

    work_dir.mkdir(parents=True, exist_ok=True)
    char_anno_dir = work_dir / "annotations"
    char_anno_dir.mkdir(exist_ok=True)

    try:
        from image_to_animation import image_to_animation
    except ImportError as e:
        raise AdError(f"cannot import AD: {e}") from e

    try:
        # AD 가 char_anno_dir 안에 mp4/gif 생성 (보통 ./output.mp4 또는 video.mp4)
        image_to_animation(
            img_fn=str(image_path),
            char_anno_dir=str(char_anno_dir),
            motion_cfg_fn=str(motion_yaml),
            retarget_cfg_fn=str(_RETARGET_CFG),
        )
    except Exception as e:
        raise AdError(f"AD pipeline failed: {e}") from e

    # AD 결과물 찾기 (mp4 또는 gif)
    output = _find_output(char_anno_dir)
    if output is None:
        raise AdError(f"AD produced no output in {char_anno_dir}")

    if output.suffix.lower() == ".gif":
        return output

    # mp4 → gif 변환
    gif_path = work_dir / "result.gif"
    _mp4_to_gif(output, gif_path)
    return gif_path


def _looks_like_image(p: Path) -> bool:
    head = p.read_bytes()[:4]
    return head.startswith(b"\xff\xd8") or head.startswith(b"\x89PNG")


def _find_output(dir_: Path) -> Path | None:
    """AD 가 만든 video/gif 파일 찾기."""
    for ext in (".gif", ".mp4"):
        files = list(dir_.glob(f"*{ext}"))
        if files:
            return files[0]
    return None


def _mp4_to_gif(mp4: Path, gif: Path) -> None:
    """imageio 로 mp4 → gif 변환."""
    import imageio.v3 as iio
    frames = iio.imread(mp4)  # (T, H, W, 3)
    iio.imwrite(gif, frames, duration=int(1000/24), loop=0)
```

📚 **새 개념 — `try/except ... raise AdError(...) from e`**: 원본 예외를 새 예외로 wrap 하면서 traceback 보존. 호출자(routes)는 `AdError` 한 가지만 분기하면 됨.

📚 **새 개념 — `Path.glob`**: 와일드카드 패턴으로 파일 검색. 디렉토리 안 모든 `.mp4` 찾기 등.

- [ ] **Step 4: 실제 AD 호출 통합 테스트 작성**

`tests/test_ad_runner.py` 에 추가:
```python
import pytest
from pathlib import Path

AD_REPO = Path("/home/ingon/AR_book/AnimatedDrawings")
SAMPLE_DRAWING = AD_REPO / "examples" / "drawings" / "garlic.png"


@pytest.mark.slow
def test_run_with_real_drawing(tmp_path: Path):
    """실제 AD 가 도는지. 30초-수분 걸림 — pytest -m slow 로만 실행."""
    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample drawing missing: {SAMPLE_DRAWING}")
    from app.ad_runner import run
    gif = run(SAMPLE_DRAWING, "dab", tmp_path / "work")
    assert gif.exists()
    assert gif.suffix == ".gif"
    assert gif.stat().st_size > 1000
```

📚 **새 개념 — `pytest.mark.slow`**: 느린 테스트에 marker. 평소 빠른 테스트만 돌리고, `pytest -m slow` 로 명시적으로 실행.

`server/pytest.ini` 생성:
```ini
[pytest]
markers =
    slow: 느린 테스트 (실제 AD 호출 등)
```

- [ ] **Step 5: AD sample 이미지 확인**

```bash
ls /home/ingon/AR_book/AnimatedDrawings/examples/drawings/
```
Expected: `garlic.png` 또는 비슷한 sample. 다른 이름이면 테스트의 `SAMPLE_DRAWING` 수정.

- [ ] **Step 6: 빠른 테스트 → 통과 (slow 제외)**

```bash
pytest tests/test_ad_runner.py -v -m "not slow"
```
Expected: 기존 2개 테스트 PASS.

- [ ] **Step 7: 느린 통합 테스트 실행**

```bash
pytest tests/test_ad_runner.py -v -m slow
```
Expected: `test_run_with_real_drawing` PASS (시간 30초-수분). 결과 gif 가 tmp_path 안에 생성.

⚠ **실패 케이스 흔한 거**:
- `AdError: cannot import AD` → step 1 의 pip install -e 실패. 재시도.
- `AdError: AD pipeline failed: ...` → AD 가 캐릭터 인식 실패. 다른 sample 로 재시도.
- 30분 이상 멈춤 → AD 가 CUDA 없는데 GPU 모드 시도? AD config 확인.
- 사용자에게 보고하고 해결책 상의 — 자동 retry 금지.

- [ ] **Step 8: commit**

```bash
git add server/app/ad_runner.py server/tests/test_ad_runner.py server/pytest.ini
git commit -m "feat(server): real AD invocation (image_to_animation + mp4→gif)"
```

✅ **방금 한 일**: AD 가 진짜로 도는 wrapper 완성. 실제 그림으로 gif 생성 검증.
**배운 것**: in-process import, AD 의 2단계 파이프라인, exception chaining, mp4→gif 변환, pytest marker.

---

## Task 7: routes.py 를 ad_runner 호출로 교체 (M3 통합)

**Files:**
- Modify: `server/app/routes.py`
- Modify: `server/tests/test_process_stub.py` → `test_process.py` 로 이름 변경 + 확장

📚 **새 개념 — 에러 분류 + HTTP 매핑**: 도메인 에러(UnknownMotionError, AdError) 를 HTTP status 로 변환. 클라이언트는 도메인 에러 모르고 status code 만 본다.

- [ ] **Step 1: 기존 test_process_stub 의 dummy 가정 제거**

`server/tests/test_process_stub.py` 를 `test_process.py` 로 이동:
```bash
git mv tests/test_process_stub.py tests/test_process.py
```

내용 전체 교체:
```python
"""POST /process 의 실제 동작 검증."""
import io
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

AD_REPO = Path("/home/ingon/AR_book/AnimatedDrawings")
SAMPLE_DRAWING = AD_REPO / "examples" / "drawings" / "garlic.png"


def test_process_rejects_missing_image():
    response = client.post("/process", data={"motion": "dab"})
    assert response.status_code in (400, 422)


def test_process_rejects_missing_motion():
    fake = io.BytesIO(b"\xff\xd8\xff\xd9")
    response = client.post(
        "/process",
        files={"image": ("x.jpg", fake, "image/jpeg")},
    )
    assert response.status_code in (400, 422)


def test_process_rejects_unknown_motion():
    fake = io.BytesIO(b"\xff\xd8\xff\xd9")
    response = client.post(
        "/process",
        files={"image": ("x.jpg", fake, "image/jpeg")},
        data={"motion": "no_such_motion_xyz"},
    )
    assert response.status_code == 400


def test_process_rejects_non_image_input():
    response = client.post(
        "/process",
        files={"image": ("x.txt", io.BytesIO(b"hello"), "text/plain")},
        data={"motion": "dab"},
    )
    assert response.status_code == 422  # AdError → 422


@pytest.mark.slow
def test_process_real_drawing_returns_gif():
    if not SAMPLE_DRAWING.exists():
        pytest.skip(f"sample missing: {SAMPLE_DRAWING}")
    with SAMPLE_DRAWING.open("rb") as f:
        response = client.post(
            "/process",
            files={"image": ("garlic.png", f, "image/png")},
            data={"motion": "dab"},
        )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("image/gif")
    assert len(response.content) > 1000
```

- [ ] **Step 2: 빠른 테스트 실행 → 실패 확인**

```bash
pytest tests/test_process.py -v -m "not slow"
```
Expected: 일부 테스트 실패 (예: `test_process_rejects_unknown_motion` 가 200 받음 — 현재 routes 는 motion 무시).

- [ ] **Step 3: `routes.py` 교체**

```python
"""HTTP 라우트 정의."""
import uuid
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse

from app import settings, ad_runner
from app.motion_registry import UnknownMotionError

router = APIRouter()


@router.get("/health")
def health():
    return {"status": "ok"}


@router.post("/process")
async def process(
    image: UploadFile = File(...),
    motion: str = Form(...),
):
    if not image.filename:
        raise HTTPException(status_code=400, detail="missing field: image")

    job_id = uuid.uuid4().hex[:8]
    job_dir = settings.JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    input_path = job_dir / image.filename
    input_path.write_bytes(await image.read())

    print(f"[/process] job={job_id} motion={motion} image={image.filename}")

    try:
        gif_path = ad_runner.run(input_path, motion, job_dir / "ad")
    except UnknownMotionError as e:
        raise HTTPException(status_code=400, detail=f"unknown motion: {motion}") from e
    except FileNotFoundError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except ad_runner.AdError as e:
        raise HTTPException(status_code=422, detail=f"AD failed: {e}") from e

    return FileResponse(
        path=gif_path,
        media_type="image/gif",
        filename="result.gif",
    )
```

📚 **에러 매핑 규약**:
- `UnknownMotionError` → 400 (클라이언트가 잘못된 id 보냄)
- `FileNotFoundError` → 400 (입력 빠짐)
- `AdError` → 422 (입력은 형식 맞는데 AD 가 처리 실패)
- 기타 → 500 (FastAPI 기본)

- [ ] **Step 4: 빠른 테스트 재실행 → 통과**

```bash
pytest tests/test_process.py -v -m "not slow"
```
Expected: 4개 빠른 테스트 PASS.

- [ ] **Step 5: 느린 end-to-end 테스트**

```bash
pytest tests/test_process.py -v -m slow
```
Expected: `test_process_real_drawing_returns_gif` PASS (수 분).

- [ ] **Step 6: curl 로 실제 그림 end-to-end 검증**

서버 실행 후:
```bash
curl -X POST http://localhost:8000/process \
  -F "image=@/home/ingon/AR_book/AnimatedDrawings/examples/drawings/garlic.png" \
  -F "motion=dab" \
  --output /tmp/garlic_dab.gif

file /tmp/garlic_dab.gif
```
Expected: `GIF image data, version 89a, ...`. viewer 로 열어 캐릭터 움직임 확인.

- [ ] **Step 7: commit**

```bash
git add server/app/routes.py server/tests/test_process.py
git commit -m "feat(server): /process integrates ad_runner (M3 complete)"
```

✅ **방금 한 일 (M3 완료)**: HTTP 입력 → 실제 GIF. 서버 완성.
**배운 것**: 도메인 예외 → HTTP status 매핑, exception chaining 의 traceback 보존, end-to-end 검증.

---

## Task 8: shared/API.md 작성 (서버 완성 시점의 single source of truth)

**Files:**
- Create: `shared/API.md`

서버 완성됐으니 클라이언트 작업 전에 API 를 명문화. Android 측 구현이 이 문서를 참조한다.

- [ ] **Step 1: `shared/API.md` 작성**

```bash
mkdir -p /home/ingon/AR_book/ad-server/shared
```

```markdown
# ad-server API

Base URL: `http://<HOST>:<PORT>` (M1: `http://<PC-IP>:8000`)

## GET /health

응답 `200`:
\`\`\`json
{ "status": "ok" }
\`\`\`

## POST /process

요청 `multipart/form-data`:

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| `image` | file (jpeg/png) | ✅ | 아이 그림 |
| `motion` | text | ✅ | motion id (예: `dab`, `wave_hello`, `jumping`) |

응답 — 성공 `200`:
- `Content-Type: image/gif`
- body = GIF binary

응답 — 실패:

| status | 의미 |
|---|---|
| 400 | image 누락, motion 누락, 알 수 없는 motion id |
| 422 | AD 처리 실패 (이미지에서 캐릭터 인식 실패 등) |
| 500 | 서버 내부 오류 |

처리 시간: CPU 기준 30초 ~ 수 분. 클라이언트 read timeout **300초** 권장.

## 동작 가능 motion

- `dab` — Dab
- `wave_hello` — Wave Hello
- `jumping` — Jumping

(M5 옵션에서 `GET /motions` 로 동적 조회 가능해질 예정)
```

> Step 4 의 motion id 확정 후 위 목록을 실제 등록값으로 교체.

- [ ] **Step 2: commit**

```bash
git add shared/API.md
git commit -m "docs: API.md as client-server contract"
```

✅ **방금 한 일**: 서버/클라이언트 동시 참조 가능한 API 명세 확정.

---

# Part B — Android 클라이언트 (M4)

## Task 9: Android 새 프로젝트 + manifest

**Files:**
- Create: `android-client/` 전체 (Android Studio 가 생성)
- Modify: `android-client/app/src/main/AndroidManifest.xml`
- Modify: `android-client/app/build.gradle.kts`

📚 **새 개념 — minSdk vs targetSdk**: minSdk = 이 앱을 설치 가능한 최저 안드로이드 버전. targetSdk = 앱이 *대상으로 삼은* 버전 (이 버전 동작을 의도). 갤탭 SM-X610 은 Android 13+ 이라 minSdk 26 OK.

📚 **새 개념 — cleartext HTTP**: Android 9+ 부터 평문 HTTP 차단이 기본값. LAN 의 PC 서버로 HTTPS 없이 접속하려면 manifest 옵션 또는 NetworkSecurityConfig 필요.

📚 **새 개념 — `INTERNET` 권한**: 인터넷 사용엔 manifest 선언만 필요 (런타임 권한 X). 갤러리는 별도.

- [ ] **Step 1: Android Studio 새 프로젝트 생성 (수동)**

Android Studio 에서:
- File → New → New Project → "Empty Views Activity"
- Name: `ad-client`
- Package: `com.k3i.adclient`
- Save location: `/home/ingon/AR_book/ad-server/android-client`
- Language: Kotlin
- minSdk: API 26 (Android 8.0)
- Build configuration: Kotlin DSL (`*.kts`)

⚠ **사용자 수동 단계**: GUI 진행이라 자동화 어려움. 진행 후 폴더 구조 확인:
```bash
ls /home/ingon/AR_book/ad-server/android-client/app/src/main/
```
Expected: `AndroidManifest.xml`, `java/`, `res/`.

- [ ] **Step 2: `AndroidManifest.xml` 수정**

`android-client/app/src/main/AndroidManifest.xml` (기존 manifest 의 `<application>` 위에 `<uses-permission>` 추가, `<application>` 에 `usesCleartextTraffic` 추가):
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.AdClient">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: `app/build.gradle.kts` 에 의존성 추가**

`android-client/app/build.gradle.kts` 의 `dependencies { }` 안에 추가:
```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // HTTP 클라이언트
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 코루틴
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // GIF 표시
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
```

📚 **새 개념 — OkHttp**: Square 의 표준 HTTP 클라이언트. multipart 빌더 내장. Retrofit 도 내부에선 OkHttp 사용.

📚 **새 개념 — Glide**: 이미지/GIF 디스플레이 라이브러리. ByteArray → ImageView 한 줄로 표시 가능.

- [ ] **Step 4: Gradle sync 확인**

Android Studio 의 "Sync Now" → 의존성 다운로드 + 빌드 가능 상태 확인.

⚠ **실패 시**: 인터넷 X 거나 maven 차단. settings.gradle.kts 의 `repositories { google(); mavenCentral() }` 확인.

- [ ] **Step 5: commit**

```bash
cd /home/ingon/AR_book/ad-server
git add android-client/
git commit -m "chore(android): new project skeleton + manifest + deps"
```

✅ **방금 한 일**: Android 프로젝트 만들고 권한/cleartext/deps 설정.
**배운 것**: minSdk/targetSdk, cleartext HTTP 의 이유, OkHttp/Glide.

---

## Task 10: Config.kt + AdApi.kt — HTTP 클라이언트

**Files:**
- Create: `android-client/app/src/main/java/com/k3i/adclient/net/Config.kt`
- Create: `android-client/app/src/main/java/com/k3i/adclient/net/AdApi.kt`

📚 **새 개념 — Kotlin object**: `object Config { ... }` 은 싱글톤. 인스턴스 없이 `Config.BASE_URL` 로 접근. 상수 모아두기 좋음.

📚 **새 개념 — `suspend fun`**: 코루틴에서만 호출 가능한 일시 중단 함수. 네트워크 호출 = 메인 스레드 막으면 안 됨 → suspend + `Dispatchers.IO`.

- [ ] **Step 1: `Config.kt` 작성**

```kotlin
package com.k3i.adclient.net

object Config {
    // PC 의 LAN IP 로 교체. 회사 서버 이관 시 이 한 줄만 변경.
    const val BASE_URL = "http://192.168.1.50:8000"

    // 5분 read timeout (AD 처리 최대 시간)
    const val READ_TIMEOUT_SEC = 300L
    const val CONNECT_TIMEOUT_SEC = 10L
}
```

- [ ] **Step 2: `AdApi.kt` 작성**

```kotlin
package com.k3i.adclient.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object AdApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Ok(val gifBytes: ByteArray) : Result()
        data class Err(val code: Int, val message: String) : Result()
    }

    /** GET /health — 서버 연결 확인. */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${Config.BASE_URL}/health").build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    /** POST /process — image bytes + motion id → GIF bytes. */
    suspend fun process(
        imageBytes: ByteArray,
        imageFilename: String,
        motionId: String,
    ): Result = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "image",
                filename = imageFilename,
                body = imageBytes.toRequestBody("image/*".toMediaType()),
            )
            .addFormDataPart("motion", motionId)
            .build()

        val req = Request.Builder()
            .url("${Config.BASE_URL}/process")
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    Result.Ok(bytes)
                } else {
                    Result.Err(resp.code, resp.body?.string() ?: "")
                }
            }
        } catch (e: IOException) {
            Result.Err(-1, e.message ?: "network error")
        }
    }
}
```

📚 **새 개념 — `sealed class Result`**: 가능한 결과를 *제한된 집합* 으로 표현. `when (r) { is Ok -> ...; is Err -> ... }` 가 exhaustive — 컴파일러가 모든 케이스 처리 강제.

📚 **새 개념 — `.use { }`**: Kotlin 의 try-with-resources. Response 같은 closeable 을 자동으로 닫음.

- [ ] **Step 3: 빌드 확인**

Android Studio: Build → Make Project (Ctrl+F9). 컴파일 에러 없으면 OK.

- [ ] **Step 4: commit**

```bash
git add android-client/app/src/main/java/com/k3i/adclient/net/
git commit -m "feat(android): Config + AdApi (OkHttp multipart, suspend fn)"
```

✅ **방금 한 일**: HTTP 클라이언트 골격. UI 와 분리.
**배운 것**: OkHttp multipart, sealed class, `Dispatchers.IO`, Kotlin `object` singleton, swap 지점(`Config.BASE_URL`).

---

## Task 11: UI layout + 갤러리 picker + Spinner

**Files:**
- Modify: `android-client/app/src/main/res/layout/activity_main.xml`
- Modify: `android-client/app/src/main/res/values/strings.xml`
- Modify: `android-client/app/src/main/java/com/k3i/adclient/MainActivity.kt`

- [ ] **Step 1: `strings.xml` 수정**

`res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">AD Client</string>
    <string name="pick_image">이미지 선택</string>
    <string name="combine">합치기</string>
    <string name="motion_label">Motion</string>
    <string name="status_idle">대기 중</string>
    <string name="status_loading">서버 처리 중… (수 분 소요)</string>
</resources>
```

- [ ] **Step 2: `activity_main.xml` 작성**

`res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <ImageView
        android:id="@+id/imagePreview"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="#EEEEEE"
        android:scaleType="fitCenter"
        android:contentDescription="picked image" />

    <Button
        android:id="@+id/btnPick"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/pick_image"
        android:layout_marginTop="8dp" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/motion_label"
        android:layout_marginTop="8dp" />

    <Spinner
        android:id="@+id/spinnerMotion"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />

    <Button
        android:id="@+id/btnCombine"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/combine"
        android:layout_marginTop="8dp" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/status_idle" />

    <ImageView
        android:id="@+id/gifResult"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:layout_marginTop="8dp"
        android:background="#DDDDDD"
        android:scaleType="fitCenter"
        android:contentDescription="result gif" />
</LinearLayout>
```

- [ ] **Step 3: `MainActivity.kt` 작성 (갤러리 picker + Spinner 만, 합치기는 Task 12)**

`MainActivity.kt`:
```kotlin
package com.k3i.adclient

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var gifResult: ImageView
    private lateinit var spinnerMotion: Spinner
    private lateinit var statusText: TextView
    private var pickedUri: Uri? = null

    // M1~M4: motion 목록 하드코딩 (M5 에서 서버 GET /motions 로 전환)
    private val motions = listOf("dab", "wave_hello", "jumping")

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            imagePreview.setImageURI(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imagePreview = findViewById(R.id.imagePreview)
        gifResult = findViewById(R.id.gifResult)
        spinnerMotion = findViewById(R.id.spinnerMotion)
        statusText = findViewById(R.id.statusText)

        spinnerMotion.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            motions,
        )

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImage.launch("image/*")
        }

        findViewById<Button>(R.id.btnCombine).setOnClickListener {
            // Task 12 에서 구현
            statusText.text = "TODO (다음 task 에서 구현)"
        }
    }
}
```

📚 **새 개념 — `ActivityResultContracts.GetContent()`**: 갤러리에서 파일 1개 받아오는 표준 contract. 권한 별도 요청 불필요 (사용자가 명시적으로 선택).

- [ ] **Step 4: 빌드 + 갤탭 설치 → 갤러리 picker 동작 확인**

```
Android Studio → Run (Shift+F10) → 갤탭 선택
```
앱 실행 후:
1. "이미지 선택" 버튼 → 갤러리 열림 → 이미지 선택 → 미리보기에 표시
2. Spinner 에 dab/wave_hello/jumping 보임
3. "합치기" 누르면 "TODO" 메시지

- [ ] **Step 5: commit**

```bash
git add android-client/app/src/main/res android-client/app/src/main/java/com/k3i/adclient/MainActivity.kt
git commit -m "feat(android): UI layout + gallery picker + motion spinner"
```

✅ **방금 한 일**: UI 골격 + 갤러리 통합.
**배운 것**: `ActivityResultContracts`, `ArrayAdapter`, Spinner, View binding 기본.

---

## Task 12: 합치기 버튼 → AdApi 호출 → GIF 표시 (M4 핵심)

**Files:**
- Modify: `android-client/app/src/main/java/com/k3i/adclient/MainActivity.kt`

📚 **새 개념 — `lifecycleScope.launch`**: Activity 라이프사이클에 묶인 코루틴 scope. Activity 가 죽으면 자동 cancel. UI 에서 네트워크 호출 시 표준.

- [ ] **Step 1: `MainActivity.kt` 의 `btnCombine` 리스너 교체**

기존 `findViewById<Button>(R.id.btnCombine).setOnClickListener { ... }` 부분을:
```kotlin
findViewById<Button>(R.id.btnCombine).setOnClickListener {
    val uri = pickedUri
    if (uri == null) {
        statusText.text = "이미지 먼저 선택"
        return@setOnClickListener
    }
    val motion = spinnerMotion.selectedItem as String

    lifecycleScope.launch {
        statusText.text = getString(R.string.status_loading)

        val bytes = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: ByteArray(0)
        }

        if (bytes.isEmpty()) {
            statusText.text = "이미지 읽기 실패"
            return@launch
        }

        val result = AdApi.process(bytes, "drawing.jpg", motion)
        when (result) {
            is AdApi.Result.Ok -> {
                statusText.text = "완료 (${result.gifBytes.size} B)"
                Glide.with(this@MainActivity)
                    .asGif()
                    .load(result.gifBytes)
                    .into(gifResult)
            }
            is AdApi.Result.Err -> {
                statusText.text = "에러 ${result.code}: ${result.message}"
            }
        }
    }
}
```

상단 import 추가:
```kotlin
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.k3i.adclient.net.AdApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

📚 **새 개념 — `Glide.with(...).asGif().load(byteArray).into(view)`**: ByteArray 를 GIF 애니메이션으로 디코딩해 ImageView 에 표시. 한 줄.

- [ ] **Step 2: 빌드 + Config.kt 의 BASE_URL 을 실제 PC IP 로 수정**

```bash
ip addr show | grep "inet " | grep -v 127.0.0.1
```
확인된 IP 로 `Config.kt` 의 BASE_URL 수정 (예: `http://192.168.1.50:8000`).

- [ ] **Step 3: 서버 실행**

```bash
cd /home/ingon/AR_book/ad-server/server
./scripts/run.sh
```

- [ ] **Step 4: 갤탭에서 데모 실행**

1. Android Studio → Run → 갤탭
2. 아이 그림 사진 갤러리에서 선택 (또는 미리 갤탭에 옮긴 garlic.png)
3. motion 선택 (dab 추천)
4. "합치기" → "서버 처리 중…" → 30초~수 분 → GIF 가 화면에 재생

⚠ **실패 시 분기**:
- 즉시 에러: 네트워크 — `Config.BASE_URL` IP 오타, 같은 Wi-Fi 아님, 방화벽
- 30초 후 timeout: read timeout 너무 짧음 또는 AD 가 멈춤 → 서버 로그 확인
- 422: AD 가 캐릭터 인식 실패 → 다른 이미지로 재시도
- 400 "unknown motion": Spinner 의 motion id 와 registry 의 id 가 다름 → motion_registry.py 와 motions 리스트 일치 확인

- [ ] **Step 5: Config swap 검증 (회사 서버 이관 가능성 확인)**

`Config.kt` 의 BASE_URL 을 가짜 IP (예: `http://10.0.0.99:8000`) 로 변경 → 빌드 → 합치기 → "에러 -1: network error" 같은 메시지 확인.

원복 후 다시 정상 동작 확인.

📚 **검증 의미**: BASE_URL 한 줄 변경만으로 endpoint 가 바뀌고, 코드 다른 곳 영향 없음 — 회사 서버 이관 가능성 입증.

- [ ] **Step 6: commit**

```bash
git add android-client/app/src/main/java/com/k3i/adclient/MainActivity.kt
git commit -m "feat(android): combine button → AdApi → display GIF (M4 complete)"
```

✅ **방금 한 일 (M4 완료)**: 갤탭에서 데모 가능한 상태.
**배운 것**: `lifecycleScope`, `Dispatchers.IO`, Glide GIF, content resolver 로 갤러리 파일 읽기, BASE_URL 한 줄 swap 검증.

---

## Task 13: README + 최종 정리

**Files:**
- Modify: `ad-server/README.md`

- [ ] **Step 1: README 에 데모 시나리오 정리**

`README.md` 끝에 추가:
```markdown
## 데모 시나리오 (M1~M4 완료 시점)

1. PC: `cd server && ./scripts/run.sh`
2. 갤탭: AD Client 앱 실행
3. 갤러리에서 아이 그림 선택
4. motion 선택 (dab / wave_hello / jumping)
5. "합치기" 버튼 → 30초~수 분 대기 → GIF 자동 재생

## 회사 서버 이관 (예정)

`android-client/app/src/main/java/com/k3i/adclient/net/Config.kt` 의 `BASE_URL` 만 회사 서버 주소로 변경 후 빌드.

## 트러블슈팅

- 갤탭에서 서버 못 봄 → 같은 Wi-Fi SSID 인지 확인 + PC 방화벽 8000 인바운드 허용
- 422 응답 → 입력 이미지에서 캐릭터 인식 실패. 더 선명한 그림으로 재시도
- 300초 timeout → AD 가 CPU 환경에서 너무 느림. 더 작은 입력 또는 GPU 환경
```

- [ ] **Step 2: 전체 테스트 실행**

```bash
cd /home/ingon/AR_book/ad-server/server
pytest tests/ -v -m "not slow"
```
Expected: 빠른 테스트 전부 PASS.

- [ ] **Step 3: 체크리스트 (spec §8 기준)**

- [ ] M1: 다른 기기에서 `/health` 응답
- [ ] M2: curl 로 dummy GIF 받기
- [ ] M3: 실제 그림으로 curl → 모션 적용된 GIF
- [ ] M3: 잘못된 motion → 422
- [ ] M4: 갤탭에서 데모 완주
- [ ] M4: BASE_URL swap 검증
- [ ] `shared/API.md` 와 실제 서버 일치
- [ ] README 의 실행 방법 정확

- [ ] **Step 4: commit**

```bash
git add README.md
git commit -m "docs: README demo scenario + troubleshooting"
```

✅ **MVP 완성**. PC ↔ 갤탭 데모 가능, 회사 서버 이관은 `Config.kt` 한 줄.

---

# 후속 작업 (이 plan 밖)

- **M5**: `GET /motions` + 클라이언트 동적 Spinner
- **비동기 job 패턴**: 처리 5분 초과 시 polling 으로 전환
- **회사 서버 실배포**: HTTPS, 인증, 환경 분리
- **결과 캐싱**: 같은 (image, motion) 해시 → 캐시된 gif

---

# Self-Review 결과

이 plan 을 spec 과 대조 (구현자 참고용 — 새로 발견 시 task 추가):

| spec 요구사항 | 담당 task |
|---|---|
| §2 아키텍처 (동기 HTTP, in-process AD, image/gif binary) | Task 3, 6, 7 |
| §3 폴더 구조 | Task 1, 2, 5, 6, 8, 9, 10 |
| §4.1 GET /health | Task 2 |
| §4.2 GET /motions | M5 (이 plan 밖) — spec 에서 옵션으로 명시 |
| §4.3 POST /process | Task 3 (stub), 7 (real) |
| §5 M1 | Task 1, 2 |
| §5 M2 | Task 3 |
| §5 M3 | Task 4, 5, 6, 7 |
| §5 M4 | Task 9, 10, 11, 12 |
| §6 위험 — cleartext | Task 9 (manifest) |
| §6 위험 — mp4→gif | Task 6 (`_mp4_to_gif`) |
| §6 위험 — read timeout | Task 10 (`Config.READ_TIMEOUT_SEC=300`) |
| §8 검수 체크리스트 | Task 13 |

발견된 gap: 없음. 모든 spec 요구사항이 적어도 한 task 에 매핑됨.
