# Stickerbook Android Tablet Porting Brainstorm

> 작성일: 2026-05-14
> 대상 하드웨어 (가정): Galaxy Tab S9 FE+ (Android 14)
> 전제: V1 PC Python PoC (`AR_book/drawing-to-2.5d-repo/stickerbook/`) 가 검증된 상태
> 결정된 큰 방향:
> - **안 A** (PC 가 자산 생성, 갤탭은 재생/표시)
> - **Phase 1** 갤탭 Native Kotlin 으로 먼저 → 성능 테스트 → **Phase 2** Unity 포팅

---

## 1. Goal

V1 stickerbook (PC Python PoC) 를 **갤럭시 태블릿** 으로 이식하는 전략을 정한다. 이번 문서의 목적은 **MVP 까지의 최단 경로** 를 정의하는 것이며, 구현은 후속 plan 문서에서 다룬다.

**MVP 정의 (안 A 기준):**
- 입력: PC 에서 미리 만든 "스티커 자산 폴더" (스티커북 V1 의 결과물)
- 출력: 갤탭 화면에 **춤추는 스티커 GIF 가 재생** + 갤러리에 저장 가능
- 비목표 (Phase 1 단계):
  - 갤탭에서 그림 인식 (AD 엔진 포팅) → ❌
  - 갤탭 카메라로 livestream AR 합성 → ❌ (Phase 2 의 Unity + ARCore 가 담당)
  - 갤탭에서 사용자 동작 녹화 → ❌ (PC 에서 사전 등록)

**최종 목표 (Phase 2 이후):**
- 갤탭 단독 on-device (Unity + ARCore + 사전 자산). 네트워크/서버 없음.

**왜 단계적인가:**
1. AD 엔진 (Detectron2 + AlphaPose + ARAP + BVH + Desktop OpenGL) 갤탭 포팅은 **현실성 0** 에 가까움. 모델 변환만으로는 안 됨.
2. "PC = 자산 공장 / 갤탭 = 재생기" 로 분담하면 V1 의 검증된 알고리즘을 그대로 활용 가능.
3. Phase 1 (Native) 으로 먼저 **GIF/PNG seq 디코드·렌더링 성능과 호환성** 을 측정한 뒤, Phase 2 (Unity) 에서 AR 합성을 얹는 게 위험을 줄이는 길.

---

## 2. Current Python Stickerbook Structure

V1 (검증 완료) 의 흐름:

```
[웹캠 또는 mp4]
   ↓  M키 녹화 또는 video_to_bvh.py
[프레임 시퀀스]
   ↓  motion/pose_estimator.py  (MediaPipe Pose Tasks API, 33 landmarks)
[관절 시퀀스]
   ↓  motion/bvh_writer.py  (33→24 매핑, y-flip, z=0 frontal proj, m→cm)
[BVH 파일]  → assets/motions/library/ 에 등록
   ↓
SPACE 키 → animate/animated_drawings_runner.py (subprocess)
   ↓  AnimatedDrawings (Detectron2 + AlphaPose + ARAP + BVH retarget + Desktop OpenGL)
[흰 배경 캐릭터 애니메이션 MP4/GIF]
   ↓  chroma-key (흰 → 투명)
[BGRA 프레임 시퀀스]
   ↓  HomographyAnchor (ORB + RANSAC) + TiltRenderer (warpPerspective + popup + 그림자)
[합성된 AR 스티커] → 라이브 카메라 오버레이
   ↓  S 키 → assets/captures/<ts>_<motion>/ 자동 저장
```

핵심 매개체는 **BVH 파일** (MediaPipe 의 사람 뼈대와 AnimatedDrawings 의 캐릭터 뼈대를 BVH 표준으로 연결).

폴더 구조 요약:
```
stickerbook/
├── capture/   웹캠 추상 (cv2.VideoCapture)
├── track/     HomographyAnchor (ORB + RANSAC)
├── render/    tilt_renderer + animated_sticker_renderer (GIF 디코드)
├── animate/   AnimatedDrawings subprocess + TorchServe runtime
├── motion/    pose_estimator + bvh_writer + library + pipeline
├── export/    AD 호환 자산 저장 (texture/mask/cfg)
├── scripts/   seed_library, video_to_bvh, scale_legacy_bvhs
├── assets/    captures/ (자동 저장), motions/library/ (사용자 BVH)
├── app.py     메인 루프 + 키 입력 핸들러
└── main.py    CLI
```

---

## 3. External Models / Libraries Used (실제 코드 기준 조사 결과)

### 3.1 Python package (requirements.txt)

| package | 용도 | 갤탭 가능? |
|---|---|---|
| `opencv-python>=4.9` | 카메라, warpPerspective, ORB, GIF write | ✅ OpenCV Android SDK 존재 (안 A 에선 거의 불필요) |
| `mediapipe>=0.10` | Pose Tasks API (33 landmarks) | ✅ MediaPipe Tasks Android SDK 존재 |
| `torch>=2.1` | AnimatedDrawings 가 내부적으로 사용 | ❌ 갤탭 추론용 PyTorch Mobile 별도. 단 AD 가 subprocess 라 격리됨 |
| `numpy>=1.26`, `scipy>=1.10`, `pyyaml`, `ultralytics`, `mobile-sam` | YOLO/SAM 은 **V1 에서 폐기됨** (frame whole → AD). requirements 에 남아있지만 import 안 됨 | 무시 가능 |

### 3.2 외부 GitHub repo (subprocess)

| repo | 위치 | 역할 | 갤탭 가능? |
|---|---|---|---|
| [facebookresearch/AnimatedDrawings](https://github.com/facebookresearch/AnimatedDrawings) | `~/AR_book/AnimatedDrawings/` | 그림 → 캐릭터 뼈대 + 모션 retargeting | ❌ **포팅 불가 수준** |

AD 가 내부적으로 쓰는 컴포넌트:
- **Detectron2** (그림 영역 humanoid 감지) — PyTorch 기반. 모바일 변환 매우 어려움
- **AlphaPose** (그림 캐릭터 pose) — 동일
- **ARAP (As-Rigid-As-Possible) mesh 변형** — Python + scipy
- **BVH 로더 + retarget**
- **Desktop OpenGL 렌더링** (PyOpenGL/GLX) — 모바일 GL 과 호환 안 됨

→ AD 는 **PC 에 남기는 게 유일한 현실적 선택**.

### 3.3 Pretrained model 파일

| 파일 | 위치 | 크기 | 출처 |
|---|---|---|---|
| `drawn_humanoid_detector.mar` | `~/AR_book/AnimatedDrawings/torchserve/model-store/` | ~수십 MB | AD release 다운로드 |
| `drawn_humanoid_pose_estimator.mar` | 동일 | ~수십 MB | 동일 |
| `pose_landmarker_heavy.task` | MediaPipe pip 패키지 번들 | ~수 MB | mediapipe 자동 |
| `yolo26n.pt`, `mobile_sam.pt` | `stickerbook/models/` | — | **V1 에서 폐기됨 → 디스크에서도 삭제됨** |

**TorchServe** 가 `.mar` 파일을 서빙. 갤탭에는 TorchServe 없음 → AD 전체와 함께 PC 에만.

### 3.4 런타임 다운로드

- AD 의 TorchServe `.mar` 는 사전 다운로드 (run-time 아님)
- MediaPipe Tasks 모델 (`pose_landmarker_heavy.task`) 은 pip 패키지에 번들
- 런타임에 인터넷이 필요한 의존성은 **없음**

### 3.5 갤탭에서 그대로 로딩 불가능한 요소 (요약)

| 요소 | 사유 |
|---|---|
| AnimatedDrawings 전체 | Detectron2 + AlphaPose + ARAP + Desktop OpenGL 조합 |
| TorchServe `.mar` 모델 서빙 | 서버 런타임 자체가 모바일 미지원 |
| Python subprocess 모델 (`subprocess.run(["python", ...])`) | Android 에 시스템 Python 없음 |
| PyOpenGL (GLX/EGL) | 모바일 GL ES 와 다름 |

---

## 4. Android Tablet Porting Risks

| # | 리스크 | 영향 | 대응 (안 A) |
|---|---|---|---|
| R1 | AD 엔진 갤탭 포팅 불가 | Phase 1 자체 좌초 | **PC 에 남김**. 갤탭은 자산 재생만 |
| R2 | GIF 디코드가 갤탭에서 느림 | 재생 끊김 | Coil/Glide 의 ImageDecoder 사용. 안 되면 PNG sequence + ExoPlayer 로 전환 |
| R3 | PNG sequence 가 메모리 폭발 | OOM | 프레임 수 cap (예: 60 프레임/2s @ 30fps), 해상도 cap (512×512) |
| R4 | 자산 폴더 전송 (PC→갤탭) UX 마찰 | 데모 시연 시간 길어짐 | ADB push (개발) / Google Drive / USB 케이블 / 무선 (다음 단계) |
| R5 | 갤탭 화면 비율 다양 | 레이아웃 깨짐 | Compose 의 BoxWithConstraints + 16:10 가정. Tab S9 FE+ (2560×1600) 기준 |
| R6 | Phase 2 Unity 로 옮길 때 자산 호환 안 됨 | Phase 2 재작업 | Phase 1 자산 포맷을 Unity 친화 (**PNG sequence + JSON metadata**) 로 잡음 |
| R7 | 발열 / 배터리 (GIF 무한 루프) | 데모 5분에 thermal throttle | 화면 켜진 시간 cap, GIF 정지 옵션 |
| R8 | Phase 2 ARCore 합성 시 alpha 채널 손실 | AR 에서 캐릭터 배경 흰색 | PNG seq 는 알파 보존. GIF 는 1-bit 알파만 → PNG 우선 |

---

## 5. Model Loading Feasibility

**원칙: 갤탭이 실행 가능한 형태의 모델은 올린다.** 다만 AD 와 그 의존성 (Detectron2 / AlphaPose / ARAP / Desktop OpenGL) 은 Python + Desktop GL 형태로만 동작하므로, 모바일에서 실행 가능한 형태 (TFLite / ONNX / PyTorch Mobile) 로 변환하는 비용이 막대해 **PC 에 남긴다**. 결과적으로 Phase 1 (안 A) 에서는 갤탭이 자산 재생만 하므로 모델 로딩이 거의 필요 없을 뿐, 정책상 막은 게 아님.

| 모델 | 갤탭 필요? | 방식 |
|---|---|---|
| AD detector/pose (`.mar`) | ❌ | PC TorchServe 그대로 |
| MediaPipe Pose | ❌ (Phase 1) / ⚠️ (Phase 2 옵션) | Phase 2 에서 갤탭 카메라로 동작 녹화 추가 시 MediaPipe Tasks Android SDK 사용 |
| Detectron2 / AlphaPose | ❌ | PyTorch Mobile 변환 시도 가능하지만 ARAP/BVH 가 같이 필요 → 포팅 비용 막대. **포기** |
| YOLO/MobileSAM (구) | — | V1 에서 폐기됨 |

**Phase 2 가 되어 Unity 에서 모델이 필요해지면:**
- ARCore (Plane detection, Anchor) — Unity AR Foundation 으로 사용 (별도 모델 로딩 없음)
- MediaPipe Tasks Unity 플러그인 — 필요 시. 그 외엔 굳이 ONNX/TFLite 변환 안 함

**TFLite / ONNX / PyTorch Mobile 비교 (만약 미래에 모델을 옮긴다면):**

| 런타임 | 장점 | 단점 |
|---|---|---|
| TFLite | Android 친화, NNAPI/GPU delegate | PyTorch 모델 변환 (ONNX→TFLite) 정확도 손실 가능 |
| ONNX Runtime Mobile | PyTorch 변환 직관적, Unity ML-Agents 와 호환 | 모델 op set 호환성 깨질 때 디버깅 어려움 |
| PyTorch Mobile / ExecuTorch | 원본 PyTorch 그대로 | APK 크기, Detectron2/AlphaPose 같은 복잡 모델 미지원 op 다수 |

→ Phase 1 (안 A) 에서는 갤탭이 자산 재생만 하므로 셋 다 즉시 필요 없음. 갤탭 추론이 필요해지는 시점 (Phase 2 / 3) 에 우선순위대로 검토.

---

## 6. App Packaging Options

| # | 옵션 | 구현 난이도 | 실사용 가능성 | 모델 로딩 안정성 | 속도 | 유지보수성 | Unity 연동 | MVP 적합 |
|---|---|---|---|---|---|---|---|---|
| 1 | **Android Native (Kotlin)** | 낮음~중 | ✅ 높음 | N/A (모델 없음) | 빠름 (네이티브) | 좋음 | Phase 2 에서 → Plugin 으로 전환 가능 | ✅ **추천** |
| 2 | Unity App (처음부터) | 중~높음 | 갤탭 OK | TFLite/Sentis 별도 | 빠름~중 | Unity 학습 곡선 | (그 자체가 Unity) | △ Phase 2 |
| 3 | Python Runtime on Android (Chaquopy, BeeWare) | 매우 높음 | 🚫 비현실 | OpenCV/MediaPipe 갤탭 빌드 문제 | 매우 느림 | 나쁨 | 거의 불가능 | ❌ |
| 4 | 서버 연동 (PC + WiFi) | 낮음 (V2-test 안) | 데모 환경 의존 | PC OK | 네트워크 지연 | 좋음 (PC 코드 재사용) | 그대로 | △ 별도 트랙 |

**Phase 1 = 옵션 1 (Native Kotlin)** 로 진행.

이유:
- 옵션 3 (Chaquopy) 은 OpenCV·MediaPipe 의 native 의존성 + PyTorch 무게 때문에 실용성 0
- 옵션 2 를 처음부터 잡으면 "Unity 학습" + "GIF 재생 검증" 이 섞여 디버깅 어려움. Phase 1 에서 native 로 GIF 재생/UX 검증부터.
- 옵션 4 는 V2-test (별도 트랙) 에서 이미 다룸 — 본 문서 범위 밖

---

## 7. Touch UI Replacement Plan

### 7.1 V1 키 매핑 → 갤탭 터치

| V1 키 | 동작 | 갤탭 UI |
|---|---|---|
| **SPACE** | 그림 캡처 → AD 호출 | Phase 1 에선 PC 에서만 발생 (갤탭에 노출 안 함) |
| **L** | 모션 라이브러리 표시 | 화면 1 의 "모션 목록" 탭, 또는 상세 화면의 BottomSheet |
| **`[` / `]`** | prev/next 모션 | 모션 카드 가로 스와이프 또는 좌우 화살표 버튼 |
| **1-9, 0** | 라이브러리 N번째 | 그리드 탭 |
| **M** | 동작 녹화 토글 | Phase 1 비노출 (PC 에서만) |
| **S** | 자산 저장 | "저장" 버튼 (재생 화면 하단) |
| **R** | 리셋 | "다시 선택" 버튼 |
| **Q / Esc** | 종료 | 시스템 back |

### 7.2 Phase 1 화면 흐름 (MVP)

```
[화면 1] 스티커 목록
  ┌──────────────────────────────────────┐
  │  📁 스티커북                          │
  │  ┌────┐ ┌────┐ ┌────┐ ┌────┐         │
  │  │ 🦁 │ │ 🐷 │ │ 🦊 │ │ 🐸 │ ...     │
  │  │이름│ │이름│ │이름│ │이름│         │
  │  └────┘ └────┘ └────┘ └────┘         │
  │                                      │
  │  [ + 새 자산 폴더 import ]           │
  └──────────────────────────────────────┘
       ↓ 스티커 카드 탭

[화면 2] 스티커 상세 + 모션 선택
  ┌──────────────────────────────────────┐
  │  ← 뒤로         🐷 미리보기           │
  │  ┌────────────────────────────────┐  │
  │  │                                │  │
  │  │     [GIF 재생 영역]            │  │
  │  │                                │  │
  │  └────────────────────────────────┘  │
  │                                      │
  │  모션:  ◀  [ dance_1 ▼ ]  ▶          │
  │                                      │
  │  [ 재생 ]  [ 저장 ]  [ 다시 선택 ]   │
  └──────────────────────────────────────┘
       ↓ 저장

[화면 3] 저장 결과 (Snackbar)
  "갤러리에 저장되었습니다 → Pictures/Stickerbook/"
```

### 7.3 (옵션) Phase 1.5 카메라 오버레이

Phase 1 검증 후 Phase 2 진입 전 중간 단계:
- 갤탭 카메라 미리보기 위에 선택한 GIF 를 화면 중앙에 오버레이 (homography 추적 없음, 그냥 화면 좌표)
- 손가락 드래그로 위치 이동, 핀치로 크기
- Unity 의 AR 합성과 비교할 baseline 으로 활용

---

## 8. MP4 to Sticker GIF Pipeline

### 8.1 PC 측 흐름 (기존 V1 그대로 + export 추가)

```
mp4 (또는 카메라) → scripts/video_to_bvh.py → motions/library/<name>.bvh
                                                      ↓
                            그림 캡처 (V1 SPACE)
                                       ↓
                            AnimatedDrawings subprocess
                                       ↓
                  out/video.mp4 + char_cfg.yaml + texture.png + mask.png
                                       ↓
                       ★ 새 export: stickerbook_assets/<id>/
                            ├── meta.json
                            ├── source.png
                            ├── texture.png        (BGRA, alpha)
                            ├── animation.gif      (호환용)
                            ├── frames/            (PNG sequence, Unity 친화)
                            │   ├── 0001.png
                            │   └── ...
                            └── motion_used.bvh   (참고용)
```

`stickerbook/export/animated_drawings.py` 가 이미 일부 자산을 저장. **새 export 함수** (예: `export_for_android.py`) 를 추가해 만족스러운 폴더 포맷으로 정리.

### 8.2 갤탭 측 (Phase 1)

- 폴더 (`stickerbook_assets/`) 를 USB / Google Drive 로 갤탭에 복사
- 앱 시작 시 폴더 위치 선택 (또는 APK assets 번들)
- `manifest.json` 으로 인덱스 로드 → 화면 1 리스트
- 상세 화면에서 `frames/*.png` 또는 `animation.gif` 재생

### 8.3 mp4 입력 → GIF 직접 변환은 안 함

**중요한 분리:**
- "사용자 mp4" → BVH (사용자 동작 추출) 는 **PC** 가 함
- "그림 → 캐릭터 뼈대 + 모션 적용" 도 **PC** 가 함
- 갤탭은 **결과만** 재생

즉 갤탭에는 mp4 디코딩이 직접 필요 없음. 입력은 항상 GIF/PNG seq.

---

## 9. Unity Integration Options

Phase 2 시점에 결정. 본 문서는 비교 후 추천만.

### 9.1 옵션 비교

| # | 구조 | 장점 | 단점 |
|---|---|---|---|
| 1 | **Unity 가 전체 처리** (mp4 입력, 모델 실행, 생성) | 단일 런타임 | 모델 변환 (ONNX/Sentis) 필요. 안 A 와 정합 안 됨 (PC 가 이미 다 함) |
| 2 | **Native Plugin** (Android 가 AI/Vision, Unity 는 UI/렌더) | Phase 1 native 코드 재사용 가능, AR Foundation 으로 ARCore | Plugin 인터페이스 (JNI/Kotlin↔C#) 설계 필요 |
| 3 | **별도 Android 앱에서 사전 처리 → Unity 가 자산만 import** | MVP 가장 빠름. 안 A 와 가장 정합 | "한 앱" 이 아닌 두 앱 워크플로우 |

**Phase 2 추천:**
- 짧은 데모용: **옵션 3** (Phase 1 자산 → Unity 임포트)
- 사용자 시나리오 (한 앱에서 다): **옵션 2** (Phase 1 자산 로더를 Native Plugin 으로)

옵션 1 은 안 A 의 전제 (PC = 자산 공장) 와 충돌하므로 제외.

### 9.2 Unity 모델 런타임 (참고)

- **Unity Sentis** (Barracuda 후속) — ONNX 직접 실행. ARCore 와 함께 쓸 때 유리
- **TFLite Unity Plugin** — 별도 플러그인 필요
- **AR Foundation** — Plane / Anchor / Camera. 본 프로젝트에서 가장 중요

→ 모델은 거의 안 쓰고 AR Foundation 만 핵심.

---

## 10. Recommended MVP Architecture

```
═══════════════════════════════════════════════════════════════════
   Phase 0 (이미 완료) — PC V1 stickerbook
═══════════════════════════════════════════════════════════════════
   AR_book/drawing-to-2.5d-repo/stickerbook/  (검증됨)

═══════════════════════════════════════════════════════════════════
   Phase 0.5 — PC 에 자산 export 모듈 추가
═══════════════════════════════════════════════════════════════════
   추가할 것:
   - stickerbook/export/android_assets.py
      → V1 의 captures/<ts>_<motion>/ 를 갤탭/Unity 친화 포맷으로 변환
      → PNG sequence 추출 (GIF 디코드 → frames/)
      → manifest.json 인덱스 작성
   - scripts/build_android_pack.py
      → 폴더 통째로 .zip 패키징 (갤탭으로 보낼 단위)

═══════════════════════════════════════════════════════════════════
   Phase 1 — 갤탭 Native Kotlin 앱 (스티커 재생기)
═══════════════════════════════════════════════════════════════════
   새 폴더: stickerbook-android-native/  (또는 기존 stickerbook-android/ 재사용)

   기술 스택:
   - Kotlin 2.0, Jetpack Compose
   - Coil 또는 Glide (GIF/PNG seq 디코드)
   - DocumentFile / SAF (자산 폴더 import)
   - minSdk 28, Target API 34, Galaxy Tab S9 FE+

   화면:
   - 화면 1: 스티커 목록 (manifest.json 인덱스)
   - 화면 2: 상세 + 모션 선택 + 재생
   - 화면 3: 저장 (MediaStore Pictures/Stickerbook/)

   성능 테스트 항목:
   - GIF 디코드 FPS (target 30fps 이상)
   - PNG seq 디코드 FPS (target 30fps)
   - 메모리 (Android Profiler, 100 frames @ 512px 기준)
   - 발열 / 배터리 (10분 연속 재생 기준)
   - 콜드 스타트 시간

═══════════════════════════════════════════════════════════════════
   Phase 2 — Unity 포팅 (Phase 1 검증 후)
═══════════════════════════════════════════════════════════════════
   동일 자산 폴더 + AR Foundation + ARCore Plane Anchor
   세부 설계는 별도 brainstorm.
```

**자산 폴더 포맷 (Phase 1 / Phase 2 공통):**

```
stickerbook_assets/
├── manifest.json
│     {
│       "version": 1,
│       "stickers": [
│         {"id": "s001", "name": "사자", "motion": "dance_1",
│          "duration_ms": 2000, "fps": 30, "frame_count": 60,
│          "size": [512, 512]}
│       ]
│     }
├── stickers/
│   └── s001/
│       ├── meta.json     ← stick 1개 상세
│       ├── source.png    ← 원본 그림 사진
│       ├── texture.png   ← 누끼된 캐릭터 (정지)
│       ├── animation.gif ← 호환용 / 미리보기
│       └── frames/       ← Unity 친화 (알파 보존)
│           ├── 0001.png
│           └── ...
└── motions/
    └── dance_1.bvh        ← 참고용 (갤탭은 미사용)
```

이 포맷은 Phase 1 (Kotlin Coil) 과 Phase 2 (Unity Sprite Animation) 둘 다 그대로 읽을 수 있게 설계.

---

## 11. Open Questions

다음 단계 (writing-plans) 들어가기 전 확인 필요:

1. **자산 전송 방식** — Phase 1 MVP 에서 갤탭이 자산 폴더를 받는 방식:
   - (a) APK assets/ 번들 (작은 데모용, 자산 ~수십 MB)
   - (b) ADB push 후 내부 저장소 (개발 단계 추천)
   - (c) Google Drive / 사용자 선택 폴더 (실제 사용)
   - → 어디까지 지원할지

2. **출력 포맷 우선순위**:
   - GIF (단순, 알파 1-bit) vs PNG sequence (알파 8-bit, 무손실, 폴더 비대)
   - WebP / WebM with alpha (압축 + 알파) 도 검토할 가치
   - → MVP 는 PNG seq + 폴백 GIF 두 방식 모두 export 권장

3. **카메라 오버레이 (Phase 1.5)** 포함 여부:
   - 갤탭 카메라 미리보기 + GIF 오버레이 (homography 없음) 까지 Phase 1 에 넣을지
   - Unity 로 가기 전 baseline 측정에 유용하지만 범위 증가

4. **자산 수명 / 캐싱** — 사용자가 새 자산 폴더 import 했을 때 기존 것 유지/덮어쓰기 정책

5. **다국어 / 한글 표시** — 사용자 / 어린이가 한글 캐릭터 이름 입력할 가능성

6. **갤탭 모델 정확화** — Tab S9 FE+ vs 다른 Tab. RAM 6GB/8GB 차이가 PNG seq 메모리 캡에 영향

7. **AnimatedDrawings 자산 라이선스** — AD 의 MIT 라이선스는 갤탭/Unity 빌드에도 그대로 적용. 명시 필요

---

## 12. Next Action Items

작성 시점 (2026-05-14) 기준 다음 작업:

| # | 작업 | 담당 | 우선순위 |
|---|---|---|---|
| 1 | **이 brainstorm 문서 user review** | user | 🔴 즉시 |
| 2 | 결정 사항 반영 후 spec → implementation plan 작성 (`writing-plans`) | claude | 🔴 그다음 |
| 3 | PC 측: `stickerbook/export/android_assets.py` 모듈 추가 | claude | 🟡 plan 후 |
| 4 | PC 측: `scripts/build_android_pack.py` (zip 패키지) | claude | 🟡 |
| 5 | 갤탭 Native: Android Studio 프로젝트 스캐폴드 (Kotlin + Compose) | claude | 🟡 |
| 6 | 갤탭: 화면 1 (스티커 목록 + manifest.json 로드) | claude | 🟢 plan M1 |
| 7 | 갤탭: 화면 2 (상세 + GIF/PNG seq 재생) | claude | 🟢 plan M2 |
| 8 | 갤탭: 화면 3 (저장) + 성능 측정 fixture | claude | 🟢 plan M3 |
| 9 | Phase 1 성능 결과 doc → Phase 2 (Unity) brainstorm 진입 | user + claude | 🔵 Phase 1 완료 후 |

**즉시 결정 필요한 한 가지 — Open Questions 1, 2, 3** 답해주면 그대로 implementation plan 으로 넘어감.

---

## Appendix A — 폐기/보류한 옵션 및 사유

| 옵션 | 사유 |
|---|---|
| 갤탭 단독 + AD 엔진 포팅 | Detectron2/AlphaPose/ARAP/Desktop OpenGL 조합. 모델 변환 + 알고리즘 재구현 부담 막대. **불가능** 에 가까움 |
| 갤탭 + 사전 ONNX 모델 (YOLO+MobileSAM) | V1 에서 YOLO/MobileSAM 자체가 폐기됨 → 갤탭에서도 같은 한계 그대로 |
| Chaquopy / BeeWare (Python on Android) | OpenCV/PyTorch native 의존성 + 무게. 실용성 0 |
| Python+ONNX 변환된 단순 모델 갤탭 탑재 | 안 A 에서 갤탭에는 모델 자체가 필요 없음. 미래 Phase 3 에서 카메라로 사용자 동작 녹화할 때나 검토 |
| 갤탭 thin client + PC 서버 (V2-test) | 별도 트랙 (`stickerbook-android/` README 참조). 본 문서는 on-device 트랙 |
| 갤탭에서 mp4 직접 디코딩 → AD 호출 | AD 가 갤탭에 없어서 불가능 |

---

## Appendix B — Phase 2 진입 조건 (예고)

Phase 1 이 다음 기준을 만족하면 Phase 2 (Unity) 진입:

- ☐ GIF / PNG seq 재생이 갤탭에서 30fps 안정
- ☐ 자산 폴더 import 가 사용자 입력 5초 내 완료
- ☐ 메모리 사용 < 1GB (10개 스티커 동시 로드 기준)
- ☐ 10분 연속 재생 시 thermal throttle 없음
- ☐ Phase 1 코드 중 재사용할 컴포넌트 식별 완료 (자산 로더 / 디코더 / 모션 메타)

이 조건들이 Phase 2 brainstorm 의 input 이 됨.
