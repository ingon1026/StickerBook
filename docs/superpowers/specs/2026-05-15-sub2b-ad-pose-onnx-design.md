# Sub-2b Design — AD pose estimator ONNX 변환 + 갤탭 통합

> 작성일: 2026-05-15
> Phase 2 의 세 번째 sub-project (Sub-5/Sub-1/Sub-2 후속)
> 마스터 spec: `2026-05-14-phase2-master-design.md`
> 선행: `2026-05-14-sub1-mask-rcnn-mobile-design.md`, `2026-05-14-sub2-mediapipe-pose-design.md`
> 사용자 결정: 옵션 [A] = AD 의 mmpose 408MB pose estimator 통째 ONNX 변환 후 ONNX Runtime Mobile 통합

---

## 1. 목표 / 책임 / 비책임

### 책임

- PC: AD pose `.mar` 의 `.pth` (408MB) → MMDeploy 로 ONNX export → fix script 적용된 단일 `.onnx`
- Android: ONNX Runtime Mobile 로 inference 하는 `AdPoseEstimator` 구현
- `PoseEstimator` interface 도입 — MediaPipe 와 AD-pose 두 backend swap 가능
- `SkeletonData` 에 `backend` field + variable keypoints 지원 (mediapipe-33 / ad-coco-17)
- `PoseDetectionRigger` 수정: detector 의 bbox → AD-pose 의 crop 입력 → 좌표 원본 frame 으로 unproject
- 손그림 시연에서 17 관절이 의도된 위치에 잡히는지 시각 검증

### 비책임 (다른 sub 또는 후속 task)

- Quantization (Sub-1+2b 공통 quantization sub-task 로 후속)
- ARAP mesh 변형 (Sub-3) — Sub-2b 는 좌표만 제공
- BVH retarget (Sub-4) — Sub-2b skeleton schema 안정화만 보장
- MediaPipe 제거 (interface 로 보존)
- 라이브 streaming pose — 캡처 시 1회만

### 최소 PASS 기준

갤탭에서 캡처한 손그림 1장에 대해:
(a) detector 의 bbox crop 통과
(b) `AdPoseEstimator.estimate` 가 17 keypoints 반환 ≥ 1개 visibility > 0.3
(c) skeleton overlay 가 사람 형태 (머리/팔/다리 위치) 와 일치하게 표시

---

## 2. 컨텍스트 — AD pose 의 실체

### 원본 파일

`/home/ingon/AR_book/AnimatedDrawings/torchserve/model-store/drawn_humanoid_pose_estimator.mar` (374MB zip)

### 내부 구조 (`unzip -l` 결과)

| 파일 | 크기 | 역할 |
|---|---|---|
| `config.py` | 36KB | mmpose model + dataset + pipeline 정의 |
| `best_AP_epoch_72.pth` | 408MB | 가중치 (FP32) |
| `mmpose_handler.py` | 2.7KB | TorchServe handler (Android 에선 불필요) |
| `MAR-INF/MANIFEST.json` | 299B | 메타데이터 |

### AD pose 의 모델 아키텍처 (M2b.1 에서 실제 확인 필요)

- Framework: **mmpose 2.x** (mmdet 와 마찬가지로 OpenMMLab 계열)
- Task: **top-down 2D pose estimation** (detector bbox crop 입력 → keypoints heatmap)
- Backbone: **HRNet-w48** 또는 **ResNet-50** 추정
- Dataset: AD 자체 humanoid 손그림 데이터셋 + COCO keypoint 정의 (17 joints)
- Input: `256 x 192` RGB crop, bbox padding ratio 1.25
- Output: `17 x 64 x 48` heatmap → argmax + sub-pixel offset → 17 keypoints (x, y, confidence)

### COCO 17 keypoint 정의 (schema=`"ad-coco-17"`)

```
0:nose  1:l_eye  2:r_eye  3:l_ear  4:r_ear
5:l_shoulder  6:r_shoulder  7:l_elbow  8:r_elbow  9:l_wrist  10:r_wrist
11:l_hip  12:r_hip  13:l_knee  14:r_knee  15:l_ankle  16:r_ankle
```

### 핵심 도전

1. mmpose 2.x → 3.x config incompatibility — Sub-1 mmdet 와 동일 원인. patch 필요
2. heatmap → keypoint 후처리가 ONNX 안에 들어갈지 / Android Kotlin 에서 할지 (M2b.1 에서 결정)
3. detector crop 좌표 ↔ 원본 frame 좌표 affine 변환 — 정확하게

---

## 3. 변환 path (단계별 PoC, M2b.1~M2b.5)

### 작업 디렉토리

`/home/ingon/AR_book/sub2b_workdir/` (Sub-1 의 `sub1_workdir` 와 평행. git 추적 X, .mar/.pth/.onnx 중간 산출물 보관)

### M2b.1 — PC: `.mar` 추출 + MMDeploy ONNX export

```bash
cd /home/ingon/AR_book/sub2b_workdir
mkdir -p pose_src
unzip /home/ingon/AR_book/AnimatedDrawings/torchserve/model-store/drawn_humanoid_pose_estimator.mar -d pose_src/
# pose_src/config.py — 실제 backbone/dataset/pipeline 확인 (Section 2 의 가정 검증)
```

`run_export.py` (Sub-1 의 그것 mirror, mmpose 용으로 patch):

1. `model.pretrained = None` — 학습 시점 의존성 제거
2. `data_preprocessor` field 가 mmpose 3.x 에서 추가됨 → 명시
3. `test_pipeline` 의 `TopdownAffine` argument 호환 (mmpose 2.x 의 `bbox_thr` 등)
4. `model._scope_ = 'mmpose'` 강제 (Sub-1 의 mmdet 와 동일 issue)
5. opset 16 + `dynamic_axes` (batch=1 고정)

산출: `out/end2end.onnx` (~400MB, FP32)

### M2b.2 — ONNX fix + 검증 (`fix_onnx.py`, `verify_onnx.py`)

- mmpose 의 heatmap argmax 가 ONNX 안 `TopK` / `ArgMax` op 로 export 되면 그대로 OK
- 만약 후처리가 Python 에 남으면 Android Kotlin 으로 옮김 (heatmap argmax + sub-pixel 보정)
- Where node Cast 같은 mmdet 류 issue 는 mmpose 에선 안 나올 가능성 높음

검증: 손그림 샘플 1장 → onnxruntime CPU → 17 keypoints. PyTorch eager 와 좌표 차이 < 1px.

### M2b.3 — Android: `AdPoseEstimator` Kotlin wrapper

`app/app/src/main/assets/models/ad_pose.onnx` 에 산출물 배치.

```kotlin
class AdPoseEstimator(context: Context) : PoseEstimator {
    init {
        val modelPath = ensureModelOnDisk()  // Sub-1 pattern
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            // NO NNAPI (Sub-1 lesson)
        }
        session = env.createSession(modelPath, opts)
    }

    override suspend fun estimate(image: Bitmap, bbox: BBox?): SkeletonData {
        requireNotNull(bbox) { "AdPoseEstimator requires bbox" }
        // 1. crop image to bbox + padding (1.25 ratio)
        // 2. resize to 256x192
        // 3. ImageNet normalize (mean, std)
        // 4. ONNX run → heatmap [17, 64, 48]
        // 5. argmax + sub-pixel → 17 keypoints (crop space)
        // 6. affine inverse → 17 keypoints (original frame space)
        // 7. return SkeletonData(backend=AD_COCO_17, ...)
    }
}
```

### M2b.4 — 갤탭 end-to-end demo

`PoseDetectionRigger` 수정:

- detector → bbox 1개 선택
- `AdPoseEstimator.estimate(image, bbox)` 호출
- skeleton.json 저장 (`backend: "ad-coco-17"`, 17 landmarks)
- SkeletonOverlay 가 17 keypoints + COCO connection 그림

시연 절차:

1. 앱 → + FAB → 카메라 → 손그림 캡처
2. ▶ → 모션 선택 → 만들기 ▶
3. 1-2분 spinner 대기 (Sub-1 dominant)
4. 그리드 복귀 + 새 `pose_<ts>` 카드
5. 카드 탭 → 누끼 + 17 관절 + COCO connection 표시
6. logcat:
   ```bash
   adb logcat -s "AdPoseEstimator" "PoseDetectionRigger" "MaskRcnnDetector"
   ```

### M2b.5 — 결과 doc + memory 업데이트

- `docs/sub2b_results.md` (Sub-1/2 결과 doc 패턴)
- memory `project_phase2_progress.md` 업데이트 (Sub-2b ✅, Sub-3 차례)

---

## 4. 모듈 분해 + 데이터 계약 + 파일 변경 목록

### 모듈 분해

```
rig/
├── CharacterRigger.kt          (unchanged) - interface, Sub-5
├── RigResult.kt                (unchanged) - data class
├── PoseDetectionRigger.kt      (수정)  - estimator 주입, bbox 전달
│
├── MaskRcnnDetector.kt         (unchanged) - Sub-1
├── BBox.kt                     (unchanged) - Sub-1
├── Mask.kt                     (unchanged) - Sub-1
│
├── PoseEstimator.kt            (BREAKING) - class → interface
├── MediaPipePoseEstimator.kt   (신규, rename) - 기존 class 본체 이동
├── AdPoseEstimator.kt          (신규) - ONNX wrapper, Sub-2b 핵심
│
├── Landmark.kt                 (수정) - SkeletonData.backend field 추가
├── SkeletonOverlay.kt          (수정) - 17/33 keypoints 분기
│
└── (existing) StubRigger, DetectionOnlyRigger ... (unchanged)
```

### 데이터 계약

```kotlin
// PoseEstimator.kt (Sub-2b 신규 interface)
interface PoseEstimator {
    /**
     * @param bbox optional. AD backend 는 필수, MediaPipe 는 무시
     */
    suspend fun estimate(image: Bitmap, bbox: BBox? = null): SkeletonData
}

// Landmark.kt (수정)
enum class PoseBackend { MEDIAPIPE_33, AD_COCO_17 }

data class SkeletonData(
    val backend: PoseBackend,          // 신규
    val landmarks: List<Landmark>,
    val imageWidth: Int,
    val imageHeight: Int,
)
```

### JSON wire format

backward-compat — 기존 `pose_<ts>/skeleton.json` 파일은 backend 누락 가정 → `MEDIAPIPE_33` 로 fallback parse.

```json
{
  "backend": "ad-coco-17",
  "image_width": 1920, "image_height": 1080,
  "landmarks": [{"x": 540, "y": 200, "visibility": 0.92}, ...]
}
```

### PoseDetectionRigger 시그니처 변경

```kotlin
class PoseDetectionRigger(
    private val context: Context,
    private val detector: MaskRcnnDetector,
    private val estimator: PoseEstimator,   // 신규: AD 또는 MediaPipe 주입
) : CharacterRigger {
    companion object {
        fun realAd(context: Context) = PoseDetectionRigger(
            context, MaskRcnnDetector(context), AdPoseEstimator(context),
        )
        fun realMediaPipe(context: Context) = PoseDetectionRigger(
            context, MaskRcnnDetector(context), MediaPipePoseEstimator(context),
        )
    }

    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        val detection = detector.detect(image)
        val bbox = detection.bboxes.firstOrNull()
        val skeleton = estimator.estimate(image, bbox)
        // skeleton.json 저장 + overlay 합성 + RigResult 반환
    }
}
```

`AppNavHost.kt:43` 의 `PoseDetectionRigger.real(ctx)` → `PoseDetectionRigger.realAd(ctx)` 로 한 줄 swap.

### 파일 변경 요약

| 파일 | 변경 종류 | 비고 |
|---|---|---|
| `rig/PoseEstimator.kt` | class → interface (BREAKING) | 본체는 새 파일로 이동 |
| `rig/MediaPipePoseEstimator.kt` | 신규 | 기존 PoseEstimator class 본체 + `: PoseEstimator` |
| `rig/AdPoseEstimator.kt` | 신규 | ONNX wrapper, Sub-1 hot-fix 3개 적용 |
| `rig/Landmark.kt` | 수정 | `backend` field + `PoseBackend` enum |
| `rig/SkeletonOverlay.kt` | 수정 | backend 별 connection list 분기 |
| `rig/PoseDetectionRigger.kt` | 수정 | estimator DI + bbox 전달 + factory rename |
| `ui/nav/AppNavHost.kt:43` | 1줄 수정 | `realAd(ctx)` |
| `assets/models/ad_pose.onnx` | 신규 | ~400MB |
| `test/.../AdPoseEstimatorTest.kt` | 신규 | mock session, crop math 검증 |
| `test/.../LandmarkTest.kt` | 수정 | backend field round-trip |
| `test/.../SkeletonOverlayTest.kt` | 수정 | 17 keypoint path |
| `test/.../PoseDetectionRiggerTest.kt` | 수정 | estimator DI 테스트 |

---

## 5. 리스크 + 검증 + 마일스톤

### 리스크 매트릭스

| # | 리스크 | 영향 | 대응 |
|---|---|---|---|
| R1 | mmpose 2.x→3.x config incompat (Sub-1 의 mmdet 와 동일류) | M2b.1 막힘 | `run_export.py` patch 5개 (§3 M2b.1) + 별도 patch 발견 시 spec append |
| R2 | heatmap 후처리가 ONNX 밖에 남음 | Kotlin 후처리 코드 필요 | Sub-1 mask postprocess 패턴 (`PoseHeatmapDecoder.kt` 같은 helper) |
| R3 | bbox crop ↔ 원본 frame 좌표 변환 버그 | 17 점이 엉뚱한 위치 | `AdPoseEstimatorTest` 에 known input/output round-trip 검증 |
| R4 | ONNX 모델 OOM at load (400MB byte[]) | 갤탭 crash | Sub-1 hot-fix #1: `ensureModelOnDisk` + `createSession(filePath)` + `largeHeap=true` |
| R5 | NNAPI native crash | 갤탭 crash | Sub-1 hot-fix #2: NNAPI off, CPU only, BASIC_OPT |
| R6 | UI thread freeze (ANR) | 갤탭 1분+ 응답 X | Sub-1 hot-fix #3: `withContext(Dispatchers.Default)` 유지 |
| R7 | 손그림 도메인 외 (사람 사진) 입력 → AD pose 어색 | demo UX 저하 | scope 외. 이 sub 는 손그림만 전제 |
| R8 | APK 780MB+ | install/배포 부담 | scope 외, quantization sub-task 로 후속. README 명시 |
| R9 | AD 의 keypoint 정의가 COCO 17 가 아닐 가능성 | 매핑 어긋남 | M2b.1 에서 config.py 의 `dataset_info` 확인 단계 추가. 다르면 spec append |

### 검증

#### Unit (Robolectric + JUnit4, 기존 패턴)

- `AdPoseEstimatorTest`:
  - crop math: bbox (100,200,300,500) + 1.25 padding 입력 → 256x192 crop 좌표 정확
  - inverse affine: keypoint at crop(128, 96) → 원본 frame 좌표 정확
  - mock OrtSession 으로 fake heatmap → keypoint argmax 정확
- `LandmarkTest`: SkeletonData backend round-trip (mediapipe-33 / ad-coco-17 둘 다 JSON 왕복)
- `SkeletonOverlayTest`: 17 keypoint backend 입력 → 17 점 + COCO connection 만 그림 (33 path 호출 안 됨)
- `PoseDetectionRiggerTest`: stub estimator 주입 → rig() 호출 → estimator 가 bbox 받음 확인

#### 통합 (M2b.4)

- 손그림 1장 갤탭 캡처 → 1-2분 inference (Sub-1 dominant) → skeleton.json 저장
- logcat `AdPoseEstimator: pose detected: 17 landmarks` 보이면 PASS

#### M2b PASS 기준

§1 의 최소 PASS 와 동일:
- 갤탭에서 실 손그림 1장
- 17 keypoints ≥ 1개 visibility > 0.3
- 시각적으로 머리/팔/다리 위치가 그림과 일치

### 마일스톤 체크리스트

- [ ] **M2b.1** PC export — `out/end2end.onnx` 생성, Python verify (좌표 차이 < 1px)
- [ ] **M2b.2** ONNX fix + 검증 — verify_onnx.py PASS
- [ ] **M2b.3** Android wrapper — `AdPoseEstimator` + unit tests 21+ 회귀 PASS
- [ ] **M2b.4** 갤탭 end-to-end — 시각 검증 PASS
- [ ] **M2b.5** 결과 doc (`docs/sub2b_results.md`) + memory 업데이트

### 진입 조건 (이미 충족)

- Sub-1 완료, Sub-2 완료 (인프라)
- `.mar` 파일 위치 확인
- Sub-1 의 `run_export.py` / `fix_onnx.py` reference code 존재
- Android 작업 디렉토리 `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/`

### Sub-2b 가 풀어줄 후속

- **Sub-3 (ARAP)**: AD-coco-17 keypoints + Sub-1 mask 로 mesh 변형 시작 가능
- **Quantization sub-task**: Sub-1+2b 동시 FP16 또는 INT8 작업 자연스럽게 가능
- **Sub-2 doc 의 known 한계** 해소 (손그림 인식)
