# Sub-AR-MVP Design — Camera overlay sticker (no tracking)

> 작성일: 2026-05-15
> Master plan §1 시나리오의 첫 부분. Tracking 은 follow-up Sub-AR-Tracking
> 선행: Sub-3 (ArapRigger 30 frame 생성), Sub-GIF (export)
> 사용자 결정: MVP 먼저 (화면 중앙/터치 + 간단 tilt) + 갤러리 진입

---

## 1. 목표 / 책임 / 비책임

### 책임

- 상세 화면에 **"AR 로 보기"** 버튼 추가 → `ArViewScreen` 진입
- `ArViewScreen`: CameraX preview + Compose Canvas overlay 로 sticker frame sequence 시간 cycle 재생
- 사용자 **화면 터치** 위치 = sticker anchor (drag 으로 위치 조정)
- **단순 tilt 효과** — sticker bottom 약간 좁힘 (trapezoid perspective)
- **그림자** — sticker 아래 ellipse (depth 감)
- 종이 추적 X — 카메라 움직이면 sticker 도 같이 움직임 (MVP 한계)

### 비책임

- 종이/그림 추적 (homography ORB/AKAZE) — Sub-AR-Tracking 별도
- ARCore plane detection
- Multi-sticker (여러 sticker 동시) — single anchor 만
- 카메라 frame 분석 (capture/processing)
- AR view 의 sticker 추가 export (이미 갤러리에 GIF 저장됨)

### 최소 PASS 기준

1. 갤러리 의 sticker 선택 → "AR 로 보기" 버튼 → ArViewScreen 진입
2. CameraX preview 위에 sticker frame sequence cycle 재생
3. Sticker 의 trapezoid perspective + ellipse shadow → "벌떡 서있는" 시각 효과
4. 사용자 터치 위치 변경 시 sticker 즉시 이동
5. 뒤로가기 → 상세 화면 복귀

---

## 2. 모듈 분해

```
ui/
├── ArViewScreen.kt              (신규) - CameraX preview + Canvas overlay
└── components/
    └── ArStickerOverlay.kt      (신규) - sticker frame cycle + tilt + shadow

ui/nav/
└── AppNavHost.kt                (수정) - arview/{stickerId} 라우트 추가

ui/
└── StickerDetailScreen.kt       (수정) - "AR 로 보기" 버튼 추가
```

---

## 3. Data flow

```
[StickerDetailScreen]
   ↓ "AR 로 보기" 클릭 → nav.navigate("arview/${entry.id}")

[ArViewScreen(stickerId)]
   ↓ AssetRepository 로 sticker entry load
   ↓ CameraXPreview (full screen)
   ↓ Box overlay: ArStickerOverlay(framesDir, frameCount, fps)
   ↓ Modifier.pointerInput { detectTapGestures } — 터치 → anchor state 변경

[ArStickerOverlay]
   - LaunchedEffect: 30 fps timer → currentFrame state cycle (mod frameCount)
   - Canvas.drawBitmapMesh(...) — trapezoid tilt
   - Canvas.drawOval(shadow) below sticker
```

### Anchor / size

- Anchor state: `var anchor by remember { mutableStateOf(Offset(centerX, centerY)) }`
- Sticker size: 화면 width 의 30-40% (사용자 시각 적절)
- Anchor 가 sticker bottom-center 와 일치 (지면 닿는 점)

### Frame cycle

- LaunchedEffect 가 `delay(33ms)` (= 30 fps) 후 `currentFrame = (currentFrame + 1) % frameCount`
- Lazy bitmap load: 매 frame 의 PNG 를 BitmapFactory.decodeFile (LRU cache 또는 미리 다 load)

미리 다 load: 30 × 912×1224 ARGB_8888 = 30 × 4.4MB ≈ **132 MB** — OOM 위험.

Lazy load: 매 frame 의 PNG decode (~10-30ms per frame) → 30 fps OK. 단순.

→ **Lazy load 채택**. 30 fps timer 안에서 매 tick decodeFile + recycle.

---

## 4. Tilt 효과 (벌떡 서있는)

ARCore 없이 perspective:

`Canvas.drawBitmapMesh` 로 sticker 의 4 vertex trapezoid 변형:

```
        top-left  ┌───┐  top-right        ← width × 1.0
                  │   │
                  │   │
       bottom-left │ │ bottom-right       ← width × 0.85 (narrower, perspective)
                   │ │
              ← anchor point (지면 닿는 점)
```

- 1×1 grid mesh (4 vertex)
- 위쪽 폭 100%, 아래쪽 폭 85% (양 옆 7.5% 씩 좁아짐)
- 효과: sticker 가 약간 lean back, 지면 위 standing 느낌

추가: **Ellipse shadow** sticker 아래
- Center: anchor (bottom-center)
- Width: sticker width × 0.7
- Height: sticker height × 0.05
- Color: ARGB(80, 0, 0, 0) (alpha 80/255 = ~30%)

---

## 5. 단계적 PoC (MA.1 ~ MA.5)

| M | 내용 |
|---|---|
| **MA.1** | `ArStickerOverlay.kt` 신규 — frame cycle + drawBitmapMesh tilt + ellipse shadow |
| **MA.2** | `ArViewScreen.kt` 신규 — CameraXPreview + ArStickerOverlay + touch anchor |
| **MA.3** | AppNavHost 라우트 + StickerDetailScreen 의 "AR 로 보기" 버튼 |
| **MA.4** | 갤탭 시연 — 갤러리 sticker 선택 → AR view → 터치 anchor → 30 frame cycle 확인 |
| **MA.5** | 결과 doc |

---

## 6. 리스크

| # | 리스크 | 대응 |
|---|---|---|
| R1 | Compose Box 의 z-order — CameraX 위 overlay 그릴 때 | 단순 Box 안 layering (preview 먼저, overlay 나중) |
| R2 | 30 fps frame cycle 시 메모리 — Bitmap 30개 동시 OOM | Lazy load (매 tick decode + recycle) |
| R3 | 터치 좌표계 (Compose Offset vs Canvas pixels) | density (dp ↔ px) 변환 |
| R4 | Mesh vertex 변환 의 perspective math | 단순 4 vertex hardcode (top 100%, bottom 85%) |
| R5 | CameraX 의 surface lifecycle (rotation 등) | Sub-5 의 기존 CameraXPreview 패턴 그대로 |

---

## 7. 검증

### Unit (Robolectric)

- `ArStickerOverlayTest`:
  - 초기 frame index = 0
  - Timer tick → currentFrame increment, wrap-around
  - Tilt vertex 의 4점 계산 정확

### 갤탭 시연 (MA.4)

1. 기존 sticker (Sub-GIF 시연 의 arap_1778834983208_phone_1) 의 상세 화면 → "AR 로 보기"
2. 카메라 preview 보이는지
3. 화면 중앙 (initial) 에 sticker 30 frame cycle 재생
4. 화면 다른 위치 터치 → sticker 이동
5. 뒤로 → 상세 복귀

PASS 기준:
- Sticker cycle 자연스러움 (jitter 없음)
- Shadow + tilt 의 "벌떡 서있는" 느낌
- 터치 anchor 즉시 반응
- 30 fps 유지 (lazy decode 너무 느리면 fallback: 미리 일부 frame cache)

---

## 8. Sub-AR-MVP 후 후속

- **Sub-AR-Tracking**: ORB/AKAZE 종이 추적, 카메라 움직여도 sticker 가 종이 위 고정
- **Sub-AR-Multi**: 여러 sticker 동시 (각 다른 anchor)
- **Sub-AR-Polish**: shadow blur, fade-in, scale gesture
- 사용자 motion 녹화 (PC 작업)
