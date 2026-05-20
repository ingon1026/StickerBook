# StickerBook

> 아이가 그린 그림을 캐릭터 스티커로 만들어 움직이는 GIF 로 합성하는 **2.5D 증강현실** 프로젝트.

Meta 의 [AnimatedDrawings](https://github.com/facebookresearch/AnimatedDrawings) 위에 *PC 데모* + *Android 태블릿 앱* + *PC ↔ 태블릿 클라이언트-서버* 세 트랙을 통합한 mono-repo.

---

## 트랙 비교

| 트랙 | 위치 | 동작 환경 | 상태 |
|---|---|---|---|
| **V2 Android (on-device)** | `app/` | Galaxy Tab S9 FE+ on-device. 인터넷 X | Sub-1/2b/3 + Quantize + GIF + AR-MVP + AR-Tracking 완료 |
| **PC ↔ Android (client-server)** | `ad-server/` + `ad-client/` | PC 의 FastAPI + AnimatedDrawings + 갤탭 클라이언트 | M1~M4 + Sub-AD-Render-Fix 완료 (real GIF 동작) |
| V1 PC PoC (참고) | branch `v1-archive` | PC + 웹캠 + AD subprocess | 아카이브 (V2 로 진화) |

### 트랙별 핵심 흐름

```
V2 Android:        그림 사진 → 갤탭 내부 ONNX (detection + pose + ARAP) → AR overlay
PC ↔ Android:       그림 사진 → 갤탭 HTTP POST → PC FastAPI → AD (torchserve + Mesa render) → GIF → 갤탭
```

---

## 폴더 구조

```
StickerBook/
├── app/                                 # V2 Android (on-device) — Sub-1~4 + AR-MVP/Tracking
│   ├── src/main/java/...                # Kotlin 본체
│   ├── src/main/assets/                 # ONNX 모델 + BVH motion
│   └── ...
├── ad-server/                           # PC ↔ Android: PC 측 FastAPI 서버
│   ├── server/                          # FastAPI + ad_runner + motion_registry
│   ├── shared/API.md                    # 클라이언트/서버 공통 계약
│   ├── docs/superpowers/                # spec + implementation plan
│   └── README.md                        # 서버 셋업 + 트러블슈팅
├── ad-client/                           # PC ↔ Android: 갤탭 Android 클라이언트
│   ├── app/src/main/java/.../net/       # Config + AdApi (OkHttp multipart + 코루틴)
│   ├── app/src/main/java/.../util/      # ImageRotator (사용자 회전)
│   └── README.md                        # 클라이언트 빌드/실행
├── docs/                                # 공용 design 문서
├── android_tablet_porting_brainstorm.md
└── README.md                            # (이 파일)
```

---

## Quickstart — PC ↔ Android 데모

### 1. PC 서버 셋업
```bash
# WSL Ubuntu
sudo apt install -y libosmesa6 libosmesa6-dev
conda activate animated_drawings   # AnimatedDrawings 가 깔린 conda env

# torchserve 띄움 (AD 모델 서빙)
ad-server/server/scripts/run_torchserve.sh

# FastAPI 띄움
ad-server/server/scripts/run.sh
```

### 2. Windows 측 1회 설정 (관리자 PowerShell)
```powershell
# 방화벽 8000 인바운드 허용
New-NetFirewallRule -DisplayName "ad-server 8000" -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Allow

# WSL2 외부 노출 (WSL IP 는 hostname -I 로 확인)
netsh interface portproxy add v4tov4 listenport=8000 listenaddress=0.0.0.0 connectport=8000 connectaddress=<WSL_IP>
```

### 3. Android 클라이언트
1. Android Studio 로 `ad-client/` 열기
2. `app/src/main/java/com/k3i/adclient/net/Config.kt` 의 `BASE_URL` 을 PC Wi-Fi IP 로
3. 갤탭 USB 연결 후 ▶ Run

### 4. 시연
이미지 선택 → (필요 시 ↻ 회전) → Motion 선택 (11종: dab, wave_hello, jumping, jumping_jacks, zombie, dance_1/2/3, my_dance, my_dance_2/3) → 합치기 → GIF 자동 재생

자세한 셋업 + 트러블슈팅은 [`ad-server/README.md`](ad-server/README.md).

---

## Quickstart — V2 Android (on-device)

`app/` 을 Android Studio 로 열어 갤탭 (Galaxy Tab S9 FE+) 에 빌드. 인터넷 의존성 없음. 자세한 흐름은 [`docs/`](docs/) 참조.

---

## 기술 스택

| 영역 | 기술 |
|---|---|
| 캐릭터 검출 + 자세 추정 | AnimatedDrawings (Mask R-CNN + mmpose 기반) |
| 헤드리스 렌더링 | Mesa OffScreen (osmesa) + PyOpenGL |
| PC 서버 | FastAPI + uvicorn + python-multipart |
| ML 서빙 | TorchServe 0.12 |
| Android 클라이언트 | Kotlin + OkHttp + Glide + 코루틴 |
| Android on-device | ONNX Runtime + Canvas drawBitmapMesh (ARAP) |
| 종이 추적 | OpenCV ORB + RANSAC + homography |
| Motion 포맷 | BVH (MediaPipe 33 → 24 joint 변환) |

---

## 참고

| 외부 자료 | 용도 |
|---|---|
| [facebookresearch/AnimatedDrawings](https://github.com/facebookresearch/AnimatedDrawings) | 캐릭터 추출 + 모션 retargeting (MIT) |
| [google-ai-edge/mediapipe](https://github.com/google-ai-edge/mediapipe) | 사용자 자세 33 landmarks (Apache 2.0) |
| [tatsuya-ogawa/RakugakiAR](https://github.com/tatsuya-ogawa/RakugakiAR) | 2.5D AR 스티커 컨셉 참고 |

---

## 브랜치

- `main` — 현재 작업 통합 (V2 + ad-server + ad-client)
- `android-port` — V2 Android 의 다른 트랙 (Sub-AR-Pipeline 등 별도 작업)
- `v1-archive` — V1 PC PoC history 보존