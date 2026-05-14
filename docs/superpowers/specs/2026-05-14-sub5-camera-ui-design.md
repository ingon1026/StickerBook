# Sub-5 Design — UI + 카메라 캡처 + 라이브러리 통합

> 작성일: 2026-05-14
> Phase 2 의 첫 sub-project. 마스터 spec: `2026-05-14-phase2-master-design.md`
> 다른 sub (1~4) 가 채울 자리는 Stub interface 로 미리 정의

---

## 1. 목표

갤탭에서 **카메라 → 캡처 → 모션 선택 → (Stub) 결과 → 라이브러리 추가** 의 사용자 흐름을 완전히 동작시킨다. 실제 ML / ARAP / BVH 처리는 다음 sub 들이 채우지만, **인터페이스는 이 sub 에서 확정**.

### MVP 범위 ([B] 옵션, 사용자 합의)

- 화면 1: 라이브러리 그리드 + FAB (+)  ← Phase 1 `StickerListScreen` 수정
- 화면 2: 카메라 미리보기 + 캡처
- 화면 3: 캡처 사진 review + 다시/다음
- 화면 4: 모션 선택 그리드 + 만들기
- "처리 중" 다이얼로그 → Stub 호출 → 결과 라이브러리에 추가 → 그리드로 복귀

### 비목표

- 실제 캐릭터 추출 / 모션 적용 (Sub-1 ~ Sub-4 책임)
- AR 합성 (현 화면 표시만 — AR 은 마지막 통합 단계)
- 다중 캡처 / batch 모드

---

## 2. Architecture

```
┌────────────────────────────────────────────────────────────┐
│                    MainActivity                           │
│                       ↓                                    │
│                   AppNavHost                              │
│   ┌──────────────────┬─────────────────────────────┐      │
│   │ list (기존)      │ capture / review / motion / │      │
│   │                  │ processing (신규)            │      │
│   └──────────────────┴─────────────────────────────┘      │
│       ↓ entry                ↓ FAB +                       │
│   StickerListScreen      CaptureScreen → ReviewScreen      │
│   (Phase 1 수정)              ↓               ↓             │
│                          (CameraX)      MotionPickerScreen │
│                                              ↓             │
│                                       ProcessingScreen     │
│                                              ↓             │
│                                       CharacterRigger      │
│                                       (Stub → 다음 sub)    │
└────────────────────────────────────────────────────────────┘
        ↓                                       ↓
[CaptureSession] (Activity scoped)      [AssetRepository]
- Bitmap                                (Phase 1 재사용,
- selected motion                        manifest 추가/저장)
```

### 모듈 경계

- `ui/` — Compose screens. 다른 모듈 직접 호출 X
- `data/` — Manifest / Asset 로딩 (Phase 1 재사용) + `CaptureSession` (신규)
- `rig/` — `CharacterRigger` interface + `StubRigger`. Sub-1~4 가 실제 구현으로 교체
- `camera/` — CameraX 추상 (신규, Compose binding)

---

## 3. UI 흐름 + Wireframe

### 화면 1: 라이브러리 그리드 (Phase 1 수정)

```
┌──────────────────────────────────────┐
│ ☰   스티커북                          │
│                                      │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐         │
│  │ s1 │ │ s2 │ │ s3 │ │ s4 │ ...    │
│  └────┘ └────┘ └────┘ └────┘         │
│                                      │
│                              ┌─────┐ │
│                              │  +  │ │ ← FAB (신규)
│                              └─────┘ │
└──────────────────────────────────────┘
```

- FAB 탭 → `CaptureScreen` 으로 navigate
- 기존 카드 탭 → 기존 detail (Phase 1) 유지

### 화면 2: 카메라 미리보기 + 캡처

```
┌──────────────────────────────────────┐
│ ←   새 스티커 만들기                  │
│                                      │
│  ┌────────────────────────────────┐  │
│  │                                │  │
│  │  [라이브 카메라 preview]        │  │
│  │  + 가이드 사각형 (overlay)      │  │
│  │                                │  │
│  └────────────────────────────────┘  │
│                                      │
│                                      │
│              ┌──────────┐            │
│              │   캡처    │             │
│              └──────────┘            │
└──────────────────────────────────────┘
```

- CameraX `Preview` UseCase + `ImageCapture` UseCase
- 첫 진입 시 권한 요청 (없으면 메시지)
- 가이드 사각형: 종이/그림 영역 안내 (단순 stroke 오버레이)
- 캡처 → Bitmap → `CaptureSession.image` 저장 → `ReviewScreen`

### 화면 3: 캡처 사진 review

```
┌──────────────────────────────────────┐
│ ←   캡처 확인                         │
│                                      │
│  ┌────────────────────────────────┐  │
│  │                                │  │
│  │   [캡처된 사진 정적 표시]      │  │
│  │   (Image, aspect 유지)         │  │
│  │                                │  │
│  └────────────────────────────────┘  │
│                                      │
│  [ 다시 찍기 ]      [ 다음 ▶ ]        │
└──────────────────────────────────────┘
```

- "다시 찍기" → `CameraScreen` 으로 navigate up
- "다음" → `MotionPickerScreen` 으로

### 화면 4: 모션 선택

```
┌──────────────────────────────────────┐
│ ←   모션 선택                         │
│                                      │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐         │
│  │dab │ │d_1 │ │d_2 │ │d_3 │ ...    │
│  └────┘ └────┘ └────┘ └────┘         │
│                                      │
│  선택: dance_1                       │
│                                      │
│              [ 만들기 ▶ ]             │
└──────────────────────────────────────┘
```

- manifest 의 stickers list 가 아니라 **별도 motion list** 필요
- 임시: APK assets 의 motion `.bvh` 파일들 (or 이름만 dummy list 로 시작)
- 사용자 선택 → `CaptureSession.motion` 저장 → "만들기" 탭 → `ProcessingScreen`

### 처리 다이얼로그 + 결과

```
┌──────────────────────────────────────┐
│  ⠿  스티커 만드는 중...               │
│                                      │
│  ┌─────────────────────────────┐     │
│  │     [CircularProgress]      │     │
│  └─────────────────────────────┘     │
│                                      │
│  잠시 기다려주세요...                  │
└──────────────────────────────────────┘
```

- `CharacterRigger.rig(bitmap, motion)` 호출 (현재 StubRigger)
- 완료 → manifest 에 새 sticker entry 추가 → 그리드 (`list`) 로 navigate (popUpTo)
- Toast: "새 스티커 만들어짐"

---

## 4. 데이터 모델

### CaptureSession (Activity scoped, in-memory)

```kotlin
class CaptureSession {
    var image: Bitmap? = null
    var motion: String? = null
    fun reset() { image = null; motion = null }
}
```

- ViewModel / `staticCompositionLocalOf` 또는 Hilt 단일 instance. 단순화 위해 `staticCompositionLocalOf` 권장.

### MotionEntry (신규)

```kotlin
data class MotionEntry(
    val id: String,           // 파일명 기준 (예: "dance_1")
    val displayName: String,  // 사용자 표시 이름
    val bvhPath: String,      // assets/motions/library/<id>.bvh 경로
)
```

- Sub-5 에선 dummy hardcoded list 로 시작. Sub-4 가 실제 BVH parsing 추가.

### RigResult (Stub 결과 + 미래 통합)

```kotlin
data class RigResult(
    val framesDir: String,    // manifest 의 framesDir 형식 동일
    val fps: Int,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val texturePath: String,  // 정적 texture
    val gifPath: String,      // 호환용
    val sourcePath: String,   // 원본 캡처 그림
)
```

- Phase 1 의 `StickerEntry` 와 호환. RigResult → StickerEntry 변환 함수 추가.

---

## 5. CharacterRigger Stub Interface

```kotlin
// rig/CharacterRigger.kt
interface CharacterRigger {
    /**
     * 갤탭에서 그림을 캐릭터로 만들고 모션을 적용한 frame sequence 를 반환.
     * Sub-1+2+3+4 가 통합되면 실제 구현으로 교체.
     */
    suspend fun rig(image: Bitmap, motion: String): RigResult
}

// rig/StubRigger.kt
class StubRigger(private val context: Context) : CharacterRigger {
    override suspend fun rig(image: Bitmap, motion: String): RigResult {
        kotlinx.coroutines.delay(1500)  // fake 처리 시간
        // 임시: 캡처 이미지를 source.png + texture.png 로 사용,
        //       frames 는 한 장 (정적), gif 는 wrapped 단일 frame
        // 실제 file write → 내부 storage → manifest 등록
        val stickerId = "stub_${System.currentTimeMillis()}"
        // ... (write source.png, texture.png, frames/0001.png, animation.gif)
        return RigResult(
            framesDir = "stickers/$stickerId/frames",
            fps = 30,
            frameCount = 1,
            width = image.width,
            height = image.height,
            texturePath = "stickers/$stickerId/texture.png",
            gifPath = "stickers/$stickerId/animation.gif",
            sourcePath = "stickers/$stickerId/source.png",
        )
    }
}
```

Sub-1~4 가 완성되면 `class AdRigger(...) : CharacterRigger` 로 교체.

---

## 6. Phase 1 코드 재사용 + 수정 매트릭스

| 컴포넌트 | 변경 | 상세 |
|---|---|---|
| `MainActivity.kt` | 그대로 | 변경 없음 |
| `ui/nav/AppNavHost.kt` | **수정** | route 4개 추가 (capture, review, motion, processing) |
| `ui/StickerListScreen.kt` | **수정** | Scaffold 에 FAB 추가, onCaptureClick callback |
| `data/Manifest.kt`, `data/StickerEntry.kt` | 그대로 | 재사용 |
| `data/AssetRepository.kt` | **수정** | manifest 에 새 entry 추가하는 `saveSticker(entry: StickerEntry)` 메서드 추가 (내부 storage 의 manifest.json 갱신) |
| `data/AnimationSaver.kt`, `ui/components/AnimationPlayer.kt`, `StickerCard.kt` | 그대로 | 재사용 |
| `ui/StickerDetailScreen.kt` | 그대로 | 새 sticker 도 같은 detail 화면 사용 |

### 신규 파일

```
app/app/src/main/java/com/k3i/stickerbook/
├── camera/
│   ├── CameraXPreview.kt            CameraX Preview UseCase + AndroidView wrap
│   └── ImageCaptureController.kt    ImageCapture UseCase + 캡처 함수
├── ui/
│   ├── CaptureScreen.kt              화면 2
│   ├── CaptureReviewScreen.kt        화면 3
│   ├── MotionPickerScreen.kt         화면 4
│   └── ProcessingScreen.kt           처리 다이얼로그
├── data/
│   ├── CaptureSession.kt             Activity scoped 임시
│   └── MotionEntry.kt                모션 메타
├── rig/
│   ├── CharacterRigger.kt            interface
│   ├── RigResult.kt
│   └── StubRigger.kt                 placeholder 구현
```

---

## 7. 권한 + 에러 처리

### 카메라 권한

- 첫 `CaptureScreen` 진입 시 `rememberLauncherForActivityResult(RequestPermission)`
- 거부 시: 화면에 "카메라 권한이 필요합니다" 메시지 + Settings 이동 버튼
- 영구 거부 (Don't ask again): Settings 가이드만

### 에러 케이스

| 케이스 | 동작 |
|---|---|
| 카메라 권한 거부 | 권한 안내 화면 |
| 캡처 실패 (CameraX exception) | Toast "캡처 실패, 다시 시도" + 화면 유지 |
| StubRigger 실패 (디스크 full 등) | Toast "스티커 만들기 실패" + 라이브러리로 복귀 |
| manifest 저장 실패 | Toast + 라이브러리로 복귀 (RigResult 폐기) |

---

## 8. 테스트 전략

| 테스트 | 종류 | 위치 |
|---|---|---|
| `CaptureSession` reset / state 보존 | Unit | `test/data/CaptureSessionTest.kt` |
| `MotionPickerScreen` motion list 표시 + 선택 | Robolectric | `test/ui/MotionPickerScreenTest.kt` |
| `StubRigger` 가 RigResult + 파일들 정상 생성 | Robolectric | `test/rig/StubRiggerTest.kt` |
| `AssetRepository.saveSticker` round-trip (저장 → 로드) | Robolectric | `test/data/AssetRepositoryTest.kt` (기존 파일 확장) |
| Capture → Review → Motion → Processing 전체 흐름 | Instrumented | `androidTest/CaptureFlowSmokeTest.kt` (카메라 없이 모킹 가능한 부분만) |

UI 의 카메라 미리보기 자체는 실 디바이스/에뮬레이터에서 수동 검증.

---

## 9. Open Questions

1. **모션 라이브러리 source** — Sub-5 단계에서:
   - (a) APK assets 의 `motions/<id>.bvh` 파일 목록을 hardcoded 로 (3-5 개 dummy entries)
   - (b) Phase 1 의 manifest 에 motion list 추가 (schema v2)
   - (c) Sub-4 가 완성될 때까지 mock list 만 (StubRigger 는 motion ID 무시)
   - **추천: (c) 가장 단순**

2. **CaptureSession scope** — `staticCompositionLocalOf` vs ViewModel vs SavedStateHandle
   - **추천: `staticCompositionLocalOf`** 단순. SavedStateHandle 은 process death 대응 필요 시. MVP 는 process death 미고려.

3. **카메라 화면 orientation** — 갤탭 가로/세로 둘 다 지원 vs 가로 고정
   - **추천: 가로 고정** (sticker 캡처 = 종이 가로) — `android:screenOrientation="landscape"` on Activity

4. **가이드 사각형** — 단순 stroke 표시만 vs ML 기반 그림 영역 자동 인식
   - **추천: 단순 stroke** Sub-5 에서. ML 인식은 Sub-1 에서 자연 통합

5. **StubRigger 결과 표시** — Sub-5 단계에서 어떻게 보일지
   - 현재 안: 캡처 이미지를 그대로 1 frame sticker 로 (정적). Sub-1~4 통합 후 진짜 모션 적용.

---

## 10. Next Action

이 spec → `writing-plans` 스킬로 단계별 implementation plan 작성 → 사용자 review → subagent-driven-development 로 실행.

추정 task 수: 10-15 (CameraX 추가, 4 screens, CharacterRigger interface + Stub, navigation 수정, FAB 추가, AssetRepository.saveSticker, MotionEntry, tests).

예상 작업 시간: 2-3 일 (단순한 sub 라서 빠름. CameraX 통합이 가장 비싼 부분).
