# ad-server — Design Spec

작성일: 2026-05-19
작성자: ingon (k3i_ai5@k3i.co.kr)
저장소: `/home/ingon/AR_book/ad-server/`

---

## 0. 작업 모드 — 학습 우선 (중요, 구현자 필독)

이 프로젝트는 사용자의 **client-server / HTTP / 백엔드 학습** 을 겸한 R&D 트랙이다.
사용자는 다음에 대해 **거의 처음** 이다:
- HTTP 서버 직접 구현 (FastAPI, uvicorn)
- 클라이언트-서버 multipart 파일 업로드
- Android 측 HTTP 클라이언트 (OkHttp, multipart, 코루틴)
- 서버에서 외부 Python 라이브러리(AnimatedDrawings) 를 in-process 호출

따라서 implementation 진행 시:

1. **새 개념이 나오면 1-2줄로 먼저 설명한 뒤 코드 작성**
   - 예: `UploadFile` 처음 등장 → "FastAPI 가 multipart 파일을 받아주는 타입. async stream 형태로 들어옴" 한 줄 설명 후 사용
2. **각 마일스톤 끝에 "방금 한 일/배운 것" 짧게 정리**
3. **에러 만나면 "왜 났는지" 를 코드 수정 전에 먼저 설명**
4. **CLI 명령어 줄 때 옵션의 의미도 한 줄씩** (예: `--host 0.0.0.0` = 모든 NIC, LAN 노출)
5. **선택 가능한 path 가 있을 땐 trade-off 짧게 보여주고 결정**

목표: 코드 동작뿐 아니라, 왜 이 구조인지 사용자가 설명할 수 있게 되는 것.

---

## 1. 목적

태블릿 앱에서 사용자가 (a) 아이 그림 이미지를 선택하고 (b) motion 을 선택해 "합치기" 버튼을 누르면,
PC 서버가 AnimatedDrawings (이하 AD) 로 GIF 를 생성해 태블릿에 돌려주는 **client-server 데모** 를 만든다.

핵심 비-목표 (M1 단계에서):
- 다중 동시 사용자 처리
- 인증/보안
- 진행률 표시 / 비동기 job 큐
- 회사 서버 실배포 (이번엔 base URL swap 검증까지만)

장기 목표: M4 까지 끝나면 PC ↔ 회사 서버 갈아끼우기는 `Config.kt` 한 줄 변경으로 가능해야 한다.

참고:
- AnimatedDrawings: https://github.com/facebookresearch/AnimatedDrawings (로컬 `/home/ingon/AR_book/AnimatedDrawings/`)
- 기존 on-device 구현: `stickerbook_android_porting/` (이 프로젝트와는 별개 — 코드 재사용 X, 학습 분리 목적)

---

## 2. 아키텍처

```
┌──────────────────────────┐                ┌──────────────────────────────┐
│   Android Test App       │                │   PC Server  (FastAPI)       │
│   (ad-client)            │                │   uvicorn on :8000           │
│                          │                │                              │
│  ┌────────────────────┐  │  POST /process │  ┌────────────────────────┐  │
│  │ ① 이미지 선택       │──┼───multipart───▶│  │ ② Receive               │  │
│  │ ② Motion 선택       │  │  image + motion│  │   - 임시 파일 저장      │  │
│  │ ③ "합치기" 버튼     │  │  (HTTP POST)   │  │   - motion → yaml 매핑  │  │
│  └────────────────────┘  │                │  └────────┬───────────────┘  │
│                          │                │           │                  │
│  ┌────────────────────┐  │                │  ┌────────▼───────────────┐  │
│  │ ⑤ GIF 표시          │◀─┼──image/gif─────│  │ ③ AnimatedDrawings     │  │
│  │   (ImageView)       │  │  binary body   │  │   image_to_animation() │  │
│  └────────────────────┘  │                │  │   (in-process import)  │  │
│                          │                │  └────────┬───────────────┘  │
│  BASE_URL =              │                │           │                  │
│   "http://<PC-IP>:8000"  │                │  ┌────────▼───────────────┐  │
│  ← config 한 곳에서만    │                │  │ ④ GIF 파일 읽어         │  │
│   바꾸면 회사 서버로 swap│                │  │   response body 로 반환 │  │
│                          │                │  └────────────────────────┘  │
└──────────────────────────┘                └──────────────────────────────┘
        같은 Wi-Fi LAN (테스트)                    Python + AD 환경
```

### 2.1 핵심 결정 사항

| 결정 | 선택 | 이유 |
|---|---|---|
| 클라이언트 | 새 간단한 Android Studio 프로젝트 | 기존 stickerbook_android_porting 은 on-device pipeline 이 박혀있어 흐름 분기 복잡. 학습 분리 목적 |
| 통신 패턴 | **동기 HTTP** (단일 request, 긴 timeout) | 학습 단순. M1 에선 충분. 폴링 패턴은 M4 끝나고 필요 시 |
| AD 호출 방식 | **Python in-process import** (`from examples.image_to_animation import image_to_animation`) | subprocess/docker 보다 단순. 이미 venv 에 설치되어 있음 |
| GIF 반환 방식 | **응답 body 에 image/gif binary 직접** | API 1개로 끝, 가장 단순 |
| 서버 endpoint 추상화 | 클라이언트 `Config.kt` 에 `BASE_URL` 상수 1개만 분리 | PC ↔ 회사 서버 교체 시 1줄 변경 |
| motion 표현 | 클라이언트는 motion id 문자열만 송신, 서버가 yaml 경로 매핑 | resource referencing — 클라이언트가 AD 내부 구조 모름 |

### 2.2 학습 개념 메모

- **HTTP method 의미**: GET = 읽기, POST = 만들기/처리. `/health`, `/motions` = GET. `/process` = POST.
- **multipart/form-data**: 이미지(바이너리) + motion(텍스트) 한 요청에 묶는 표준 인코딩. JSON 으론 이미지 못 보냄.
- **Content-Type 헤더**: body 가 무슨 형식인지 알려주는 헤더. 요청은 `multipart/form-data`, 응답은 `image/gif`.
- **0.0.0.0 vs 127.0.0.1**: `0.0.0.0` 으로 bind = 모든 NIC 노출 (LAN 의 다른 기기에서 접근 가능). `127.0.0.1` = 같은 PC 안에서만.
- **dependency boundary**: AD 호출을 `ad_runner.py` 한 곳에 격리 → 라우트 코드가 AD 인자 안 봐도 됨. AD 바꿀 때 한 파일만 수정.

---

## 3. 폴더 구조

```
/home/ingon/AR_book/ad-server/
├── README.md
├── docs/superpowers/specs/
│   └── 2026-05-19-ad-server-design.md   (이 문서)
│
├── server/                         ─────  PC 서버  ─────
│   ├── requirements.txt            fastapi, uvicorn, python-multipart, (+AD deps)
│   ├── app/
│   │   ├── __init__.py
│   │   ├── main.py                 FastAPI 앱 + uvicorn 진입점
│   │   ├── routes.py               GET /health, GET /motions, POST /process
│   │   ├── ad_runner.py            AnimatedDrawings 호출 wrapper
│   │   ├── motion_registry.py      motion id → yaml 경로 매핑
│   │   └── settings.py             포트, AD 경로, motion 디렉토리
│   ├── jobs/                       작업별 임시 디렉토리 (gitignore)
│   ├── motions/                    motion yaml 사본 / AD config 링크
│   ├── scripts/run.sh              uvicorn 한 줄
│   └── tests/test_smoke.py         /health, /process dummy 검증
│
├── android-client/                 ─────  Android Studio 프로젝트  ─────
│   ├── build.gradle.kts (project)
│   ├── settings.gradle.kts
│   └── app/
│       ├── build.gradle.kts        OkHttp 의존성
│       └── src/main/
│           ├── AndroidManifest.xml INTERNET, usesCleartextTraffic=true
│           ├── res/layout/activity_main.xml  미리보기+Spinner+버튼+GIF view
│           ├── java/com/k3i/adclient/
│           │   ├── MainActivity.kt
│           │   ├── net/AdApi.kt              process(image,motion):ByteArray
│           │   ├── net/Config.kt             BASE_URL 한 줄  ← swap 포인트
│           │   └── ui/GifView.kt
│           └── assets/             M1 motion 목록 JSON (임시 하드코딩용)
│
└── shared/
    └── API.md                      API 스펙 single source of truth
```

### 3.1 학습 개념 메모

- **mono-repo 의 장점**: API 변경 시 server / android-client 동시 commit. 스펙 drift 방지.
- **Config.kt 가 swap 포인트**: 추후 `BuildConfig` 로 dev/prod 환경 분리 가능. M1 은 상수로 충분.
- **wrapper 가 외부 의존성을 경계에 가둔다**: `routes.py` 는 `ad_runner.run(image_path, motion_id) -> gif_path` 라는 추상만 본다. AD 가 바뀌어도 routes 는 안 바뀐다.

---

## 4. API 스펙

### 4.1 `GET /health`

요청: 없음
응답 `200 OK`:
```json
{ "status": "ok", "ad_loaded": true }
```
용도: 클라이언트가 서버 연결됐는지 먼저 확인. curl/Postman 으로 5초 검증.

### 4.2 `GET /motions`

요청: 없음
응답 `200 OK`:
```json
{
  "motions": [
    { "id": "dab",  "label": "Dab",  "yaml": "config/motion/dab.yaml" },
    { "id": "wave", "label": "Wave", "yaml": "config/motion/wave.yaml" },
    { "id": "jump", "label": "Jump", "yaml": "config/motion/jump.yaml" }
  ]
}
```
구현 시점: **M5(옵션)** — M1~M4 동안 server/client 양쪽 모두 motion 목록을 하드코딩 사용. 이 endpoint 자체도 M5 에서 서버에 추가한다. 4.2 정의는 미리 합의해둔 스펙.

### 4.3 `POST /process`  (핵심)

요청 `Content-Type: multipart/form-data`:

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `image` | file (jpeg/png) | ✅ | 아이 그림 사진 1장 |
| `motion` | text | ✅ | motion id (예: `"wave"`) |

curl 검증 예:
```bash
curl -X POST http://192.168.x.x:8000/process \
  -F "image=@/path/to/drawing.jpg" \
  -F "motion=wave" \
  --output result.gif
```

응답 — 성공 `200 OK`:
```
Content-Type: image/gif
Content-Disposition: attachment; filename="result.gif"
<GIF 바이트>
```

응답 — 실패:

| status | 의미 | body |
|---|---|---|
| `400 Bad Request` | image 누락 / 잘못된 파일 / motion id 없음 | `{"error": "missing field: image"}` |
| `422 Unprocessable Entity` | AD 처리 실패 (annotation 못 만듦, 캐릭터 인식 실패) | `{"error": "AD failed: <reason>"}` |
| `500 Internal Server Error` | 예상 못한 서버 에러 | `{"error": "internal error"}` |

처리 시간: CPU 기준 30초 ~ 수 분. 클라이언트 OkHttp **read timeout 300초** 로 설정.

### 4.4 학습 개념 메모

- **status code 가 분기 키**: 200/400/422/500 으로 클라이언트 UI 메시지 결정. body 안 까봐도 분류 됨.
- **idempotent vs not**: `/process` 는 매번 새 GIF (비-idempotent). `/motions`, `/health` 는 idempotent.
- **GIF binary 를 그냥 박는 법**: `Content-Type: image/gif` 만 헤더로 명시하면 OkHttp 가 ByteArray 로 받아옴.

---

## 5. 마일스톤

각 단계는 그 자체로 "돌아가는 무언가" 가 되도록 분리. 각 단계 끝에 명확한 검증 명령어가 있다.

```
M1 (서버 Hello)  →  M2 (POST stub)  →  M3 (AD 진짜)  →  M4 (Android UI)  →  [M5 옵션]
   ↑ curl 1줄         ↑ curl 2줄         ↑ 진짜 GIF        ↑ 태블릿에서 사용        ↑ /motions 연동
```

### M1 — 서버 Hello World

예상: 30분~1시간
목표: uvicorn 으로 FastAPI 서버 띄우고 `GET /health` 응답
작업:
- `server/requirements.txt`
- `pip install -r requirements.txt`
- `server/app/main.py` — FastAPI 앱 + `/health`
- `server/scripts/run.sh` — `uvicorn app.main:app --host 0.0.0.0 --port 8000`

학습 포인트: FastAPI 가 어떻게 함수 1개를 HTTP 엔드포인트로 노출하는지, `0.0.0.0` vs `127.0.0.1`, LAN 통신 + 방화벽

검증:
```bash
curl http://localhost:8000/health
curl http://192.168.x.x:8000/health   # 태블릿 또는 다른 기기에서
```

### M2 — POST /process stub

예상: 1~2시간
목표: 이미지+motion 받아 echo 하고 **고정 dummy GIF** 반환 (AD 안 부르고)
작업:
- `routes.py` 에 `POST /process` 추가 — `UploadFile`, `Form` 사용
- `/tmp/jobs/<uuid>/input.jpg` 저장
- `assets/dummy.gif` 를 응답으로 반환

학습 포인트: multipart 파싱, `FileResponse` vs `StreamingResponse`, 임시 디렉토리 패턴, **통신 골격과 처리 로직 분리** 의 이점

검증:
```bash
curl -X POST http://192.168.x.x:8000/process \
  -F "image=@drawing.jpg" -F "motion=wave" \
  --output result.gif
# result.gif 가 dummy.gif 와 동일한지 확인
```

### M3 — AD 실제 통합

예상: 2~4시간
목표: `ad_runner.py` 가 `image_to_animation` 호출, 실제 GIF 생성
작업:
- `motion_registry.py` — motion id → yaml 경로 dict
- `ad_runner.run(image_path, motion_id) -> gif_path`
  - 작업 dir → `image_to_annotations` → `annotations_to_animation` → mp4/gif 경로
  - AD 가 mp4 만 뱉으면 imageio/ffmpeg 으로 GIF 변환
- `routes.py` 에서 stub → `ad_runner.run(...)` 교체
- 에러 → 422 매핑

학습 포인트: in-process import, AD yaml config 시스템, 에러 분류 (input 잘못 vs 처리 실패 vs 서버 버그)

검증:
```bash
curl -X POST http://192.168.x.x:8000/process \
  -F "image=@real_kid_drawing.jpg" -F "motion=wave" \
  --output result.gif
# 결과 GIF 를 열어서 캐릭터가 모션 하는지 눈으로 확인
```
**이 시점에서 서버 완성** — curl 만으로 데모 가능.

### M4 — Android 클라이언트

예상: 4~6시간
목표: 태블릿 앱 — 갤러리 → motion 선택 → 합치기 → GIF 표시
작업:
- Android Studio 새 프로젝트 (Kotlin, minSdk 26+)
- `AndroidManifest.xml`: `INTERNET`, `usesCleartextTraffic="true"`
- `Config.kt` — `BASE_URL` 상수
- `AdApi.kt` — OkHttp `MultipartBody` 로 이미지+motion 송신, `ByteArray` 수신
- `MainActivity.kt` — 갤러리 picker + Spinner + 버튼 + Glide/AnimatedImageDrawable

학습 포인트: Android 권한 모델, cleartext HTTP 차단 이유, OkHttp multipart 빌더, 코루틴 `Dispatchers.IO`, GIF 애니메이션 디코딩

검증: 갤탭 SM-X610 설치 → 그림 선택 → motion 선택 → 30초 내외 대기 후 GIF 재생.
**데모 가능 상태** — 회사 서버 이관은 `Config.kt` 1줄만 수정.

### M5 (옵션) — /motions API + 동적 Spinner

목표: 새 motion 추가 시 클라이언트 재빌드 불필요
작업: 서버 `GET /motions` 구현 + 클라이언트 `onCreate` 에서 호출 → Spinner 채우기
언제 할지: M4 끝나고 motion 추가 자주 일어날 때

---

## 6. 위험과 한계

| 위험 | 영향 | 완화 |
|---|---|---|
| AD 처리 시간 5분 초과 | client timeout | M1 에선 read timeout 300초. 그 이상이면 비동기 job 패턴으로 전환 (M4 끝나고 필요 시) |
| 갤탭과 PC 가 다른 Wi-Fi | 통신 안 됨 | M1 검증 단계에서 잡힘 — `curl` 로 ping. 같은 SSID 확인 |
| PC 방화벽이 8000 차단 | LAN 접속 안 됨 | M1 단계에서 노출. Windows defender 인바운드 규칙 추가 |
| AD 가 mp4 만 뱉음 | GIF 변환 추가 필요 | imageio 또는 ffmpeg subprocess. M3 에서 처리 |
| 이미지 크기가 너무 큼 | 업로드 느림, AD OOM | M1 클라이언트에서 다운스케일 (예: 최대 1024px). M2 검증 |
| cleartext HTTP 차단 (Android 9+) | 앱이 서버 못 봄 | `usesCleartextTraffic="true"` 또는 NetworkSecurityConfig. M4 에서 처리 |
| BASE_URL 하드코딩으로 swap 시 빌드 재배포 | 운영 부담 | M4 까진 OK. 이후 `BuildConfig.BASE_URL` 또는 런타임 설정 화면으로 발전 |

---

## 7. 비-목표 / Out of scope

- 인증/사용자 관리 — M1 에선 anonymous
- HTTPS — 회사 서버 이관 시점에 도입 (LAN 테스트는 HTTP)
- 다중 동시 사용자 — uvicorn worker 1개로 시작. 동시성 필요해지면 worker 늘리거나 job queue
- 진행률 표시 — 동기 응답이라 % 없음. 필요해지면 async job 패턴
- 캐싱 — 같은 image+motion 결과 재사용 등. 필요해지면 hash 기반 캐시 도입
- 회사 서버 실배포 자체 — 이번 spec 은 *swap 가능성 검증* 까지만

---

## 8. 검수 체크리스트 (구현 완료 시점)

- [ ] M1: `curl http://<PC-IP>:8000/health` 가 LAN 의 다른 기기에서 200 응답
- [ ] M2: curl 로 dummy GIF 받기 성공 (input image 와 무관하게)
- [ ] M3: 실제 아이 그림으로 curl 호출 → 캐릭터가 motion 하는 GIF 생성
- [ ] M3: 잘못된 motion id 또는 인식 안 되는 이미지 → 422 응답
- [ ] M4: 갤탭에서 데모 완주 (선택 → 버튼 → GIF 재생)
- [ ] M4: `Config.kt` 의 BASE_URL 만 다른 IP 로 바꿔도 빌드 후 동작 (가짜 서버 IP 로 검증)
- [ ] `shared/API.md` 가 실제 server 동작과 일치
- [ ] README.md 에 실행 방법 정리 (server, client 각각)

---

## 9. 후속 작업 (이 spec 밖)

- M5 동적 motion 목록
- 비동기 job 패턴 (`POST /jobs` + polling)
- 회사 서버 실배포 + HTTPS + 인증
- 사용자가 PC 에서 직접 녹화한 BVH motion 업로드 (master plan §2)
- 결과 캐싱

---

끝.
