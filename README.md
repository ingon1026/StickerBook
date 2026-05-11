# 스티커북 (Stickerbook)

> 아이가 그린 그림을 웹캠으로 인식해, 사용자의 동작을 따라 춤추는 **2.5D 증강현실 스티커** 로 만들어 카메라 위에 합성하는 PC Python PoC.

<img width="800" height="450" alt="1" src="https://github.com/user-attachments/assets/09a227f3-2fbd-4da0-a28c-5c0cd65b710d" />

---

## 개요

종이 위에 그린 졸라맨이 카메라 앞에 서면 **종이 위치에 고정된 캐릭터** 가 되어 사용자가 녹화한 동작을 따라 움직인다. 모든 처리는 PC 로컬 (오프라인) 에서 진행.

- **입력**: 일반 웹캠 (1280×720) + 종이 위 손그림 + 사용자 동작 (선택)
- **출력**: 라이브 카메라 영상 + 종이 위에 합성된 2.5D 캐릭터 스티커
- **자산 출력**: 매 캡처마다 `assets/captures/<timestamp>_<motion>/` 에 `video.gif` (춤추는 캐릭터) + `char_cfg.yaml` (AnimatedDrawings 호환) + `input.png` (원본 그림) 자동 보존

---

## 핵심 기능

- **그림 → 캐릭터 자동화**: 스페이스바 한 번으로 그림에서 캐릭터 뼈대 추출 → 모션 적용 → AR 합성
- **사용자 동작 캡처**: M키 녹화 → MediaPipe Pose 로 33개 관절 추출 → BVH 표준 모션 파일 자동 생성
- **모션 라이브러리**: 사용자가 녹화한 모션 + 메타 제공 모션(dab 등) + Rokoko mocap 모션을 한 라이브러리에서 관리
- **월드 고정 추적**: Homography (ORB + RANSAC) 로 종이 위치를 매 프레임 추적 → 종이가 움직여도 스티커가 따라감
- **다중 스티커 + 깊이감**: 슬롯 시스템으로 여러 캐릭터를 자동 배치 (중앙 / 좌·우 / 대각선) + 깊이 스케일

---

## 작동 원리

```
[웹캠 frame]
   ↓  M키 녹화 (또는 외부 MP4)
[프레임 시퀀스]
   ↓  MediaPipe Pose (33 landmarks per frame)
[관절 시퀀스]
   ↓  bvh_writer (33→24 매핑, y-flip, z=0 frontal projection, m→cm)
[BVH 파일]  → 라이브러리 등록 (assets/motions/library/)
   ↓
SPACE 키 → AD subprocess 호출
   ↓
[흰배경 캐릭터 애니메이션 MP4/GIF]
   ↓  chroma-key (흰 → 투명)
[BGRA 프레임]
   ↓  HomographyAnchor + TiltRenderer (warpPerspective + popup + 그림자)
[합성된 AR 스티커] → 라이브 카메라 오버레이
```

**핵심 매개체는 BVH 파일**. MediaPipe 가 추출한 *사람 뼈대* 와 AnimatedDrawings 가 그림에서 추출한 *캐릭터 뼈대* 가 다른 구조여도, BVH 라는 표준 모션 포맷을 통해 동작이 전달된다.

---

## 참고 라이브러리 / 레포

| 분야 | Repo | 라이선스 | 비고 |
|---|---|---|---|
| 그림 → 캐릭터 뼈대 + 모션 retargeting | [facebookresearch/AnimatedDrawings](https://github.com/facebookresearch/AnimatedDrawings) | MIT | 메타 공식. subprocess 로 호출 |
| 사용자 자세 추정 (33 landmarks) | [google-ai-edge/mediapipe](https://github.com/google-ai-edge/mediapipe) | Apache 2.0 | Pose Tasks API (`pose_landmarker_heavy.task`) |
| 2.5D 딱지 AR 컨셉 | [tatsuya-ogawa/RakugakiAR](https://github.com/tatsuya-ogawa/RakugakiAR) | — | Swift/ARKit. 합성·앵커링 방식 참고 |
| 동작 평면 정합 (Rokoko 호환) | [my_dance.yaml retarget config](https://github.com/facebookresearch/AnimatedDrawings/blob/main/examples/config/retarget/my_dance.yaml) | MIT | Rokoko cm convention 의 PCA-projection 기반 retargeting |

**Note**: AnimatedDrawings, MediaPipe 는 PoC 의존성으로만 사용. 본 레포는 위 라이브러리들의 *조합 + 통합 레이어* 이며 모델 가중치는 포함하지 않음 (각 원 레포에서 다운로드).

---

## 작업 환경

| 항목 | 값 |
|---|---|
| OS | WSL2 Ubuntu 24.04 (Windows 11 호스트) |
| Python | 3.12 |
| 인퍼런스 | CPU only (GPU 없어도 동작) |
| 웹캠 | 일반 USB 웹캠 1280×720 |
| 디스플레이 | EGL/Wayland (PyOpenGL GLX, WSL2 EGL 우회 패치 적용) |

**핵심 의존성**: OpenCV, MediaPipe (Tasks API), NumPy, SciPy, PyYAML, AnimatedDrawings (별도 conda env), TorchServe (AD 모델 서빙).

---

## 설치

### 1. 본 레포 클론
```bash
git clone <THIS_REPO_URL> stickerbook
cd stickerbook
```

### 2. Python 환경
```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 3. AnimatedDrawings (별도 conda env)
```bash
# 메타 AnimatedDrawings clone
git clone https://github.com/facebookresearch/AnimatedDrawings.git ~/AR_book/AnimatedDrawings

# conda env (메타 공식 안내)
cd ~/AR_book/AnimatedDrawings
conda create -n animated_drawings python=3.8.13 -y
conda activate animated_drawings
pip install -e .

# TorchServe 모델 다운로드
wget -P torchserve/model-store/ \
  https://github.com/facebookresearch/AnimatedDrawings/releases/download/v0.0.1/drawn_humanoid_detector.mar \
  https://github.com/facebookresearch/AnimatedDrawings/releases/download/v0.0.1/drawn_humanoid_pose_estimator.mar
```

### 4. MediaPipe Pose 모델
```bash
# Tasks API 모델은 pip 패키지에 번들되어 별도 다운로드 불필요.
# 만약 명시적으로 받으려면:
# wget https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/1/pose_landmarker_heavy.task
```

### 5. 환경변수 (선택)
기본값으로 동작하지만 경로가 다르면 export:
```bash
export STICKERBOOK_AD_REPO=$HOME/AR_book/AnimatedDrawings
export STICKERBOOK_AD_PYTHON=$HOME/miniconda3/envs/animated_drawings/bin/python
export STICKERBOOK_TORCHSERVE_BIN=$HOME/miniconda3/envs/animated_drawings/bin/torchserve
```

---

## 사용법

### 실행
```bash
python3 main.py --camera 1
```
> WSL2 에서 웹캠 index 가 안 맞으면 0, 2, 3 순으로 시도.

### 키 매핑

| 입력 | 동작 |
|---|---|
| **SPACE** | 현재 프레임을 AnimatedDrawings 로 전송 → 활성 모션 적용 → 종이 위에 춤추는 2.5D 스티커 합성 |
| **M** | 모션 녹화 토글 (시작/종료). 종료 시 라이브러리 등록 이름 입력 다이얼로그 표시 |
| **L** | 모션 라이브러리 overlay 표시/숨김 |
| **1-9, 0** | 라이브러리 첫 10개 모션 중 N번째 활성화 (0 = 10번) |
| **`[` / `]`** | 라이브러리 prev/next 모션 |
| **S** | 현재 스티커 자산을 `assets/captures/<timestamp>/` 로 저장 (PNG + cfg) |
| **R** | 모든 스티커 리셋 |
| **Q / Esc** | 종료. 콘솔에 성능 통계 출력 |

### 외부 영상 → 모션 등록
M키 라이브 녹화 외에 미리 찍은 영상 (폰 등) 도 변환 가능:
```bash
python3 scripts/video_to_bvh.py /path/to/input.mp4 --name dance_custom
```

### 메타 + Rokoko 예시 모션 시드 (1회)
```bash
python3 scripts/seed_library.py
# → dab, dance_1, dance_2, dance_3 등 자동 등록
```

---

## 프로젝트 구조

```
stickerbook/
├── capture/         # 웹캠 추상 (cv2.VideoCapture 래퍼)
├── track/           # WorldAnchor Protocol + HomographyAnchor (ORB + RANSAC)
├── render/          # tilt_renderer (warpPerspective + popup) + animated_sticker_renderer (GIF 디코드)
├── animate/         # AnimatedDrawings subprocess runner + TorchServe runtime
├── motion/          # MediaPipe Pose → BVH → MotionLibrary
│   ├── pose_estimator.py   # Tasks API 래퍼
│   ├── bvh_writer.py       # 33→24 매핑 + y-flip + z=0 + m→cm
│   ├── library.py          # BVH + AD config 자동 등록
│   └── pipeline.py         # M키 entry
├── export/          # AD 호환 자산 저장 (texture/mask/cfg)
├── scripts/         # seed_library, video_to_bvh, scale_legacy_bvhs (m→cm 일괄 변환)
├── assets/          # captures/ (자동 저장), motions/library/ (사용자 BVH)
├── tests/           # 108 tests (pytest 단위 + mock 기반 통합)
├── docs/DESIGN.md   # 설계 + 마일스톤 + 리스크
├── app.py / main.py # 메인 루프 + CLI
└── config.py        # 경로 / 파라미터
```

---

## 테스트

```bash
python3 -m pytest tests/ -q
# → 108 passed
```

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| 카메라가 안 잡힘 (`CameraError`) | WSL2 가 가상 장치를 index 0 으로 노출 | `--camera 1`, `--camera 2` 순으로 시도 |
| 첫 SPACE 가 5초 넘게 걸림 | AnimatedDrawings 모델 cold-start (TorchServe + torch 로딩) | 정상. 두 번째부터는 ~3-5초 |
| 캐릭터가 거의 안 움직임 | BVH 좌표 단위 (m vs cm) 또는 단안 z 노이즈 | `scripts/scale_legacy_bvhs.py` 로 ×100 변환. 새 BVH 는 `bvh_writer.py` 가 자동 적용 |
| 캐릭터가 거꾸로 | MediaPipe `+y down` 좌표를 AD `+y up` 에 그대로 넘김 | `bvh_writer.py` 가 자동으로 y-flip 처리 (자체 BVH 만 해당) |
| 종이를 따라 안 움직임 | 종이/배경 대비 약해서 ORB feature 부족 | 종이에 라인·텍스트가 많을수록 잘 동작. 조명 개선 |
| AD 서브프로세스가 timeout | 모델 cold-start 또는 그림 인식 실패 | `STICKERBOOK_AD_WORK_DIR` 의 stderr 로그 확인. 그림이 작거나 흐릿하면 더 또렷이 |

---

## 라이선스

내부 R&D 용 PoC. 외부 라이브러리 사용분의 라이선스는 각 원 레포 참조:
- [AnimatedDrawings (MIT)](https://github.com/facebookresearch/AnimatedDrawings/blob/main/LICENSE)
- [MediaPipe (Apache 2.0)](https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE)

## 감사

- Meta AI Research — AnimatedDrawings 의 *그림 → 뼈대 → 애니메이션* 파이프라인이 본 PoC 의 핵심 backbone
- Google MediaPipe team — Pose Landmarker Tasks API (CPU 인퍼런스로도 30fps 안정)
- Rokoko + Blender community — Rokoko BVH cm convention 및 Blender FBX→BVH 변환 검증
