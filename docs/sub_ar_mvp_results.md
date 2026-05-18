# Sub-AR-MVP 결과 — Camera overlay sticker

날짜: 2026-05-18
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.7.0 (debug)

## MA.1 — ArStickerMath 단위 (TDD)

3 tests PASS:
- trapezoid vertices (top wider, bottom narrower)
- frame index cyclic wrap-around
- shadow ellipse rect

71 tests PASS 합계 (Sub-3 ~ Sub-AR-MVP).

## MA.2 — ArStickerOverlay Composable

AnimationPlayer 패턴 + `Canvas.drawBitmapMesh` (1×1 trapezoid) + ellipse shadow. 매 frame `decodeFile` lazy (OOM 방지).

## MA.3 — ArViewScreen

CameraXPreview + Box pointerInput + ArStickerOverlay. 카메라 권한 launcher. 터치 → anchor state.

## MA.4 — 상세 화면 진입점 + nav 라우트

- StickerDetailScreen 에 "AR 로 보기" 버튼 추가 (onAR 콜백)
- AppNavHost 에 `arview/{stickerId}` route

## MA.5 — 갤탭 시연 (PASS)

### 결과 (screenshot 2026-05-18 09:19)

| 항목 | 결과 |
|---|---|
| 카메라 preview | ✅ |
| Sticker frame cycle 재생 | ✅ (30 frame, jitter 없음) |
| Trapezoid tilt + shadow → "벌떡 서있는" | ✅ (책상 위 종이 위에 sticker 가 standing) |
| 터치 anchor 즉시 반응 | ✅ |
| 뒤로 복귀 | ✅ |

### 의도된 MVP 한계

- 화면 위 anchor 만 — 카메라 움직이면 sticker 도 화면 좌표 그대로 (종이 좌표 고정 X)
- 진짜 AR (homography tracking) = Sub-AR-Tracking 의 영역

## Sub-AR-MVP commits

```text
cb1c3b4  docs(ar-mvp): design
7b646ab  docs(ar-mvp): implementation plan
0500779  feat(ar): ArStickerMath — trapezoid vertices + frame cycle + shadow rect (TDD)
ebe1ce5  feat(ar): ArStickerOverlay Composable — frame cycle + trapezoid + shadow
b66b424  feat(ar): ArViewScreen — CameraX preview + touch anchor + sticker overlay
a177974  feat(ar): wire AR view from detail screen + nav route arview/{id}
```

## 알려진 이슈 / Follow-up

- ⚠️ 종이 추적 X — Sub-AR-Tracking 별도 (ORB/AKAZE homography)
- ⚠️ Sticker 고정 size (200dp) — pinch-to-zoom 미지원
- ⚠️ Sticker 의 frame decode 30 fps CPU 사용량 측정 미흡

## 다음 sub 후보

- Sub-AR-Tracking: ORB/AKAZE 종이 추적, 진짜 AR
- Sub-AR-Multi: 여러 sticker 동시
- 사용자 motion 녹화 (PC)
- Sub-1 mask 정확도
