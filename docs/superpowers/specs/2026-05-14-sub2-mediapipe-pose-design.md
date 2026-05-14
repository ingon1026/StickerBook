# Sub-2 Design — MediaPipe Pose 통합 (그림 캐릭터 관절 추정)

> 작성일: 2026-05-14
> Phase 2 의 세 번째 sub-project (Sub-5, Sub-1 완료 후)
> 마스터 spec: `2026-05-14-phase2-master-design.md`
> 이전 sub 결과: `docs/sub5_results.md`, `docs/sub1_results.md`
> 사용자 결정: 옵션 [B] = MediaPipe Pose Tasks (V1 PC 가 이미 사용 중)

---

## 1. 목표

캡처된 그림 → 캐릭터 위 관절 33개 추정 → `skeleton.json` 저장 + 캐릭터 frame 위 overlay. Sub-3 (ARAP) 의 입력이 되고, 사용자가 갤탭에서 시각으로 "관절 잘 잡혔다" 확인 가능.

### 이번 sub 의 책임

- 갤탭 on-device pose estimation (모바일 친화)
- Sub-1 의 detection 결과 (bbox/mask) 와 통합
- `skeleton.json` 저장 (Sub-3 ARAP 입력 포맷)
- 캐릭터 frame 위 관절 마킹 overlay (사용자 시각 검증)
- Sub-5 의 `CharacterRigger` 인터페이스 유지 + `DetectionOnlyRigger` → `PoseDetectionRigger` 교체

### 비책임

- 33 → AD skeleton (24-joints) 매핑 — Sub-3 ARAP 책임
- BVH retarget — Sub-4 책임
- 사용자 탭 fallback (master spec 의 backup) — PoC 후 별도 결정
- Real-time pose tracking — 캡처 시점 1회만

---

## 2. 컨텍스트 — AD pose estimator vs MediaPipe Pose

### AD 의 pose estimator (참고용, 이번에 안 씀)

`.mar` 추출 결과:
- **MMPose** framework (AlphaPose 아님 — brainstorm 시 잘못 표기)
- **Top-down** approach (input: bbox crop, output: heatmaps)
- COCO 17 keypoints (nose, eyes, ears, shoulders, elbows, wrists, hips, knees, ankles)
- 모델 `best_AP_epoch_72.pth` = **408MB** (Sub-1 의 168MB 대비 2.4×)
- Annotated Drawings 데이터셋으로 손그림 fine-tune 추정

### 선택한 모델: MediaPipe Pose Tasks (선택 사유)

**선택 이유:**
- V1 PC stickerbook 의 `motion/pose_estimator.py` 가 이미 같은 모델 (`pose_landmarker_heavy.task`) 사용 → 알려진 호환성
- 갤탭 모바일 최적화 (Google 공식 Tasks Vision SDK, NNAPI/GPU delegate)
- Inference < 100ms (Sub-1 의 1-2분 vs)
- 모델 크기 ~20MB (heavy) — APK 부담 작음
- 33 keypoints (BlazePose 형식, COCO 17 포함 + 추가 face/hand 관절)

**손그림 도메인 위험 (받아들임):**
- MediaPipe Pose 는 사람 사진 도메인에 학습됨. 손그림에서 정확도 떨어질 수 있음
- Mitigation: 시각 검증 (skeleton overlay) → 사용자가 보고 판단 → 부족 시 fallback (사용자 탭, Sub-2 후속 sub-task)

---

## 3. Architecture + 데이터 흐름

```
[Bitmap (캡처 이미지)]
    │
    ├──► Sub-1: MaskRcnnDetector.detect(image)
    │              → List<Detection>(bbox, 28×28 mask, score)
    │
    ├──► Sub-2: PoseEstimator.estimate(image)
    │              → List<Landmark>(x, y, z, visibility, presence) × 33
    │
    ▼
[PoseDetectionRigger.rig(image, motion)]
    │
    ├── applyMaskWithSkeletonOverlay(image, top.mask, top.bbox, landmarks)
    │       → Bitmap (누끼된 캐릭터 + 관절 점·선 overlay)
    │
    ├── writePng(character, frames/0001.png)
    ├── writePng(character, texture.png, animation.gif, source.png)
    └── writeJson(skeleton.json) ← 33 landmarks (픽셀 좌표)
    │
    ▼
[RigResult (+ skeletonPath)]
    │
    ▼
[AppNavHost: saveSticker(entry)] ← StickerEntry 에 skeletonPath 추가
    │
    ▼
[그리드 화면 → 새 카드 "det_<ts>"]
    │
    ▼
[사용자가 카드 탭 → 상세 화면 → 누끼된 캐릭터 + 관절 overlay 표시]
```

---

## 4. 핵심 컴포넌트

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `rig/PoseEstimator.kt` | MediaPipe `PoseLandmarker` wrapper. `Bitmap → List<Landmark>` | 신규 |
| `rig/SkeletonOverlay.kt` | Bitmap + landmarks → Bitmap. Canvas 로 관절 점 + 연결선 그리기. 33 keypoint 의 BlazePose connections | 신규 |
| `rig/Landmark.kt` | `data class Landmark(x, y, z, visibility, presence)` + kotlinx.serialization JSON | 신규 |
| `rig/PoseDetectionRigger.kt` | `CharacterRigger` 구현체. Sub-1 detector + Sub-2 pose 통합. `applyMaskWithSkeletonOverlay` + skeleton.json 저장 | 신규 |
| `data/StickerEntry.kt` | `skeletonPath: String?` 추가 (nullable, optional) | **수정** |
| `ui/nav/AppNavHost.kt` | `DetectionOnlyRigger.real(ctx)` → `PoseDetectionRigger.real(ctx)` | **수정** |
| `app/app/src/main/assets/models/` | `pose_landmarker_heavy.task` 모델 번들 (~20MB) | 신규 자산 |
| `app/app/build.gradle.kts` | MediaPipe Tasks Vision dependency | **수정** |

### 신규 테스트

```
app/app/src/test/java/com/k3i/stickerbook/rig/
├── LandmarkTest.kt              # JSON round-trip
├── SkeletonOverlayTest.kt       # 33 landmarks → Bitmap 변경 확인 (Robolectric)
└── PoseDetectionRiggerTest.kt   # full path: stub detector + stub pose → expected files
```

`PoseEstimator` 자체는 실 MediaPipe 의존 → unit test 대신 통합 시점 시각 검증.

---

## 5. 단계적 PoC

| M | 작업 | 검증 |
|---|---|---|
| **M2.1** | MediaPipe Tasks Vision Gradle dep + `pose_landmarker_heavy.task` 모델 번들 | gradle build SUCCESS, APK 에 모델 포함됨 |
| **M2.2** | `PoseEstimator` wrapper — sample Bitmap → 33 landmarks log 출력 | 갤탭 logcat 으로 33 landmark 좌표 확인 |
| **M2.3** | `SkeletonOverlay` + `Landmark` data class + unit test | 점·선 Bitmap 그리기 검증 |
| **M2.4** | `PoseDetectionRigger` 통합 + `AppNavHost` 교체 + 갤탭 시각 검증 | 사용자 캡처 → 누끼 캐릭터 + 관절 overlay |

---

## 6. 데이터 모델

### Landmark (kotlinx.serialization)

```kotlin
@Serializable
data class Landmark(
    val x: Float,     // 픽셀 좌표 (또는 image width 0-1 normalized — 결정: 픽셀)
    val y: Float,     // 픽셀 좌표
    val z: Float,     // depth (정확도 낮음, 그래도 저장)
    val visibility: Float,  // 0-1
    val presence: Float,    // 0-1
)

@Serializable
data class SkeletonData(
    val landmarks: List<Landmark>,    // 33 entries (BlazePose 표준)
    val imageWidth: Int,
    val imageHeight: Int,
)
```

**좌표 단위**: 픽셀 (image width/height 곱한 절대값). Sub-3 가 ARAP mesh 의 vertex 좌표와 비교하기 편함. JSON 에 `imageWidth/Height` 도 같이 저장해서 normalize 필요 시 변환 가능.

### StickerEntry 확장 (수정)

```kotlin
@Serializable
data class StickerEntry(
    // ... existing 12 fields (id, name, motion, ...) ...
    @SerialName("skeleton_path") val skeletonPath: String? = null,  // 신규, nullable
)
```

`Manifest` 의 schema v1 형식이 유지됨 (optional 필드는 backward-compatible). format_version 안 올림.

### MediaPipe 33 keypoint connections (SkeletonOverlay 가 그리는 선)

BlazePose 표준 connections (head/torso/arms/legs). 약 35 pairs. 코드에 list 박혀있음:
```kotlin
private val POSE_CONNECTIONS = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 7,         // left eye + ear
    0 to 4, 4 to 5, 5 to 6, 6 to 8,         // right eye + ear
    9 to 10,                                  // mouth
    11 to 12, 11 to 13, 13 to 15,            // left arm
    12 to 14, 14 to 16,                      // right arm
    11 to 23, 12 to 24, 23 to 24,            // torso
    23 to 25, 25 to 27, 27 to 29, 29 to 31,  // left leg
    24 to 26, 26 to 28, 28 to 30, 30 to 32,  // right leg
    // 등등
)
```

---

## 7. Sub-1 lessons learned 반영

| Lesson | Sub-2 적용 |
|---|---|
| OOM at 168MB byte[] load | MediaPipe Tasks 가 자체 모델 관리. byte[] 안 노출. `largeHeap=true` 이미 적용됨 |
| NNAPI native crash on Mask R-CNN ops | MediaPipe Pose 는 NNAPI/GPU delegate 정식 지원 (Google 공식). 자동 사용 |
| ANR (UI freeze) | MediaPipe Tasks 자체 thread 처리. 그래도 `withContext(Dispatchers.Default)` 유지 |
| Detector cache via `remember` | `PoseDetectionRigger` 도 `remember { PoseDetectionRigger.real(ctx) }` 동일 패턴 |
| Inference latency 1-2분 | MediaPipe Pose < 100ms 예상. Sub-1 의 무거운 inference 와 합쳐도 1-2분 + 100ms ≈ 동일 (Sub-1 이 dominant) |

---

## 8. Risks + Mitigation

| Risk | Mitigation |
|---|---|
| MediaPipe Pose 손그림 정확도 부족 | 시각 검증 (overlay) 후 사용자 판단. fallback: 탭 입력 (Sub-2 후속 sub-task) |
| 33 landmarks 중 일부 무효 (visibility < 0.5) | overlay 에서 visibility 낮은 landmark dim 표시. JSON 에는 모두 저장 (Sub-3 가 필터) |
| Sub-1 detection 과 Sub-2 pose 가 서로 가리키는 인물 다름 | full image 모두 처리. detection top bbox 와 pose landmarks 의 중심이 IoU < 0.3 → warning Toast |
| `pose_landmarker_heavy.task` 모델 다운로드 | MediaPipe Tasks 가 자동 다운로드 안 함. APK assets 에 사전 번들. V1 PC 와 동일 파일 |
| APK 추가 ~20MB (현 299 + 20 = 319MB) | 무시 가능. Sub-1 의 168MB 가 dominant. Sub-2 의 20MB 는 minor |
| MediaPipe Tasks Android SDK 의존성 충돌 (kotlinx serialization 등) | gradle resolve 시 확인. 보통 호환성 좋음 |

---

## 9. CharacterRigger 인터페이스 사용 (변경 없음)

Sub-5 (commit `6933296`) 의 `CharacterRigger` 그대로:

```kotlin
interface CharacterRigger {
    suspend fun rig(image: Bitmap, motion: String): RigResult
}
```

구현체 교체 history:
- Sub-5: `StubRigger` (placeholder, 1-frame raw bitmap)
- Sub-1: `DetectionOnlyRigger` (bbox + mask 적용)
- Sub-2: `PoseDetectionRigger` ← **이번 sub. AppNavHost 가 이걸 사용**

`StubRigger`, `DetectionOnlyRigger` 는 historical 로 보존 (Sub-2 가 실패해도 fallback 가능).

---

## 10. Open Questions

1. **Pose model variant** — heavy vs lite?
   - 추천: **heavy** (V1 PC 와 일치, 정확도 우위, 갤탭에서도 <100ms)

2. **Landmark 좌표 단위** — 픽셀 vs normalized (0-1)?
   - 추천: **픽셀** (Sub-3 ARAP 가 mesh vertex 좌표와 비교하기 편함). JSON 에 image size 도 저장

3. **사용자 탭 fallback 시점** — Sub-2 PoC 후 정확도 부족 발견 시 추가 결정. 지금 plan 안 다룸

4. **Skeleton.json 의 schema versioning** — 미래 33→17 또는 33→24 매핑 추가 시 schema v2 필요. 지금은 v1 (raw 33). 

5. **Visibility threshold** — overlay 표시 시 visibility < 0.5 landmark 처리?
   - 추천: **dim 표시** (alpha 0.3). 모두 그림. 사용자가 어디 안 잡혔는지 시각으로 봄

6. **Failure feedback** — pose 추정 자체 실패 (예외 throw) 시 어떻게?
   - 추천: **catch → Sub-1 결과만 사용** (DetectionOnlyRigger 의 결과로 fallback). 사용자에게 Toast "관절 인식 실패, 누끼만 적용"

---

## 11. Next Action

이 spec → `writing-plans` 스킬로 단계별 implementation plan 작성 → 사용자 review → subagent-driven-development 로 실행.

추정 task 수: 8-10 (MediaPipe dep 추가 + 4 새 파일 TDD + manifest schema 확장 + 통합 + 시각 검증).

예상 작업 시간: **2-3 일** (Sub-1 의 1-2 주 대비 빠름 — MediaPipe Tasks 가 모바일 친화 라이브러리라 통합 마찰 적음).
