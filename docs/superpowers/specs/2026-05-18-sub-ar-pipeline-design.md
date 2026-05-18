# Sub-AR-Pipeline Design — A 버튼 one-shot 흐름

> 작성일: 2026-05-18
> Sub-AR-MVP + Sub-AR-Tracking 의 ArViewScreen + 기존 캡처/processing 흐름 을 PC 식 one-shot 으로 통합
> 선행: Sub-5, Sub-1, Sub-2b, Sub-3, Sub-4, Sub-AR-MVP, Sub-AR-Tracking

---

## 1. 목표 / 책임 / 비책임

### 책임

- **그리드 화면 에 두 번째 FAB (라벨 "AR")** 추가
- **Nav arg `mode: NavMode` (NORMAL | AR_AUTO)** 도입. 캡처/모션/processing 화면 들 이 forward
- `AR_AUTO` 일 때 capture → motion picker → processing → **ArViewScreen 자동 진입** (review skip)
- ArViewScreen 진입 시 새로 만든 `stickerId` 가 자동 표시

### 비책임

- ArViewScreen UX/PaperTracker 변경 (그대로)
- 갤러리/sticker 저장 로직 변경 (그대로)
- Mask refinement (별도 sub-task 진행 중)
- 새 디자인/아이콘 production 폴리시

### 최소 PASS 기준

- 그리드 의 "AR" FAB 누르면 → 카메라 → motion → processing → AR view 자동 진입
- 기존 카메라 FAB 흐름 (review 포함) 그대로 작동
- 새 sticker 가 갤러리 에 저장됨 + AR view 에서 표시 됨
- AR back → 그리드 (방금 sticker 갤러리 에 보임)

---

## 2. Nav 구조

기존:
```
Grid ──FAB(카메라)──▶ Camera ──capture──▶ Review ──pick──▶ MotionPicker ──pick──▶ Processing ──finish──▶ Grid
Grid ──sticker tap──▶ ArView(stickerId)
```

신규:
```
                           ┌──FAB(카메라)──▶ Camera(NORMAL) ─▶ Review ─▶ MotionPicker(NORMAL) ─▶ Processing(NORMAL) ──▶ Grid
Grid                       │
                           └──FAB(AR)─────▶ Camera(AR_AUTO) ──────────▶ MotionPicker(AR_AUTO) ──▶ Processing(AR_AUTO) ──▶ ArView(newStickerId)
                          ───sticker tap─▶ ArView(stickerId)
```

핵심: 같은 Composable 들 (Camera, MotionPicker, Processing) 재사용, `mode` arg 만 다름.

---

## 3. 모듈 분해

### 신규
- `ui/nav/NavMode.kt` — enum `NORMAL`, `AR_AUTO`

### 수정
- `ui/nav/AppNavHost.kt` — nav graph 의 Camera/MotionPicker/Processing route 에 `mode` arg 추가, route forward
- `ui/screens/StickerGridScreen.kt` (또는 동등) — 두 번째 FAB 추가
- `ui/screens/CameraScreen.kt` — capture 후 nextRoute 가 `mode` 에 따라 분기 (NORMAL → Review, AR_AUTO → MotionPicker)
- `ui/screens/ProcessingScreen.kt` (또는 처리 끝 nav 부분 AppNavHost.kt) — 처리 끝 시 `mode` 에 따라 popUpTo(Grid) vs nav to ArView(stickerId)

### 변경 없음
- `ArViewScreen.kt`, `PaperTracker`, `MaskRcnnDetector`, `AdPoseEstimator`, `ArapRigger`, `JsonMotionSource`, `Sticker` 데이터 모델

---

## 4. 단계 (AR.1 ~ AR.5)

| M | 내용 | 검증 |
|---|---|---|
| **AR.1** | NavMode enum + AppNavHost route 의 mode arg | route forward 컴파일 PASS |
| **AR.2** | Grid 두 번째 FAB "AR" 추가 | UI 빌드 후 두 FAB 표시 |
| **AR.3** | Camera 의 capture 후 nextRoute 분기 (NORMAL → Review, AR_AUTO → MotionPicker) | NORMAL FAB 흐름 회귀 PASS + AR FAB 시 review skip |
| **AR.4** | Processing 끝 의 nav 분기 (NORMAL → Grid, AR_AUTO → ArView(stickerId)) | AR FAB → AR view 자동 진입 |
| **AR.5** | 갤탭 시연 — AR FAB 흐름 end-to-end + back navigation | E2E PASS |

---

## 5. 리스크

| # | 리스크 | 대응 |
|---|---|---|
| R1 | nav back-stack pollution (AR_AUTO 흐름 도중 back 누르면 의도치 않은 화면) | popUpTo(Grid) 명시. 모션 picker 에서 back → Grid 직진 |
| R2 | ArViewScreen 이 stickerId arg 받게 돼 있는지 (현재) | 기존 코드 점검. 안 받으면 추가 (단순 변경) |
| R3 | Mock motion 선택 으로 AR_AUTO 흐름 도 review 가 사라져서 잘못 모션 선택 위험 | motion picker 가 명시적 선택 단계 라 사용자 의도 보장 |
| R4 | Camera 의 capture 결과 review 없이 모션 선택 → 흐릿/실패 캡처 가 그대로 진행 | AR_AUTO 의 trade-off 로 수용. 사용자 의 한 번 더 capture 필요 시 back |

---

## 6. 검증

### 회귀 (NORMAL 흐름)

- 기존 카메라 FAB → 캡처 → review → motion → processing → grid 그대로 작동
- Sticker tap → ArView 그대로

### 신규 (AR_AUTO 흐름)

- AR FAB → 캡처 → motion picker → processing → ArView (new sticker) 자동 진입
- AR view 에서 sticker 가 카메라 frame 위 추적 (기존 PaperTracker)
- back → 그리드, 새 sticker 갤러리 에 보임

### Unit (가능한 범위)

- `NavMode` parsing (toString/valueOf)
- nav graph route 의 mode arg 전달 (Compose nav 의 navigation testing 패턴, 가능 시)
- UI 테스트 가 무거우면 갤탭 시연 으로 대체 (기존 Sub-AR-MVP 패턴)

---

## 7. 진입 조건 (이미 충족)

- ✅ Sub-5 (nav 구조 존재)
- ✅ Sub-AR-MVP + Tracking (ArViewScreen 존재)
- ✅ Sub-1~4 (전체 rig 파이프라인 통합 완료)

## 8. 후속 영향

- PC 시연 의 one-shot UX 가 갤탭 에서 동일 → 데모 임팩트 ↑
- 디버그/단계 별 흐름 유지 → R&D 디버깅 용도 그대로
