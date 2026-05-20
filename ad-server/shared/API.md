# ad-server API

서버/클라이언트 공통 계약. 변경 시 server, android-client 동시 갱신.

**Base URL**: `http://<HOST>:<PORT>` (개발: `http://<PC-IP>:8000`, 상품: `https://<domain>`)

---

## GET /health

용도: 서버 살아 있는지 ping.

요청: 없음

응답 `200`:
```json
{ "status": "ok" }
```

---

## POST /process

용도: 그림 + motion → GIF.

### 요청

`Content-Type: multipart/form-data`

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| `image` | file (jpeg / png) | ✅ | 매직바이트 검사. gif/text 등은 422 |
| `motion` | text | ✅ | motion id (아래 표 참조) |

### 응답 — 성공 `200`

- `Content-Type: image/gif`
- `Content-Disposition: attachment; filename="result.gif"`
- body = GIF binary

### 응답 — 실패

| status | 발생 조건 | body |
|---|---|---|
| `400` | image 누락 | `{"detail": "missing field: image"}` |
| `400` | 알 수 없는 motion id | `{"detail": "unknown motion: <id>"}` |
| `422` | image 가 jpeg/png 아님 | `{"detail": "AD failed: input is not a valid image: <path>"}` |
| `422` | AD 처리 실패 (캐릭터 인식 실패 등) | `{"detail": "AD failed: <reason>"}` |
| `500` | 서버 내부 오류 | (FastAPI 기본 응답) |

처리 시간: CPU 기준 30초 ~ 수 분. 클라이언트 **read timeout 300초** 권장.

### 동작 가능 motion (M1 ~ M4)

클라이언트는 이 목록을 하드코딩. M5(옵션) 에서 `GET /motions` API 로 동적 조회 전환 예정.

| id | label | AD yaml |
|---|---|---|
| `dab` | Dab | `config/motion/dab.yaml` |
| `wave_hello` | Wave Hello | `config/motion/wave_hello.yaml` |
| `jumping` | Jumping | `config/motion/jumping.yaml` |

---

## curl 빠른 검증

```bash
# health
curl http://<PC-IP>:8000/health

# 정상 호출
curl -X POST http://<PC-IP>:8000/process \
    -F "image=@drawing.png" \
    -F "motion=dab" \
    --output result.gif

# 잘못된 motion
curl -X POST http://<PC-IP>:8000/process \
    -F "image=@drawing.png" \
    -F "motion=ghost" \
    -w "\nstatus=%{http_code}\n"
```

---

## 현재 동작 상태 (2026-05-19)

- ✅ 모든 endpoint 정상 동작
- ⚠️ `POST /process` 의 GIF 가 *임시 dummy* (5프레임 색 변화 723B). 진짜 AD 결과는 `Sub-AD-Render-Fix` (WSL2 OpenGL render) 해결 후 자동 활성화. 클라이언트 입장에선 응답 형식 동일 → 변경 영향 X.

---

## 변경 이력

- 2026-05-19: 초안 작성 (M3 routes/ad_runner 통합 시점)
