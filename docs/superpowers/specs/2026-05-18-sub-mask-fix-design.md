# Sub-Mask-Fix Design — Mask boundary 후처리

> 작성일: 2026-05-18
> Sub-1 detector 의 알려진 한계 (boundary 짤림) 후처리 sub-task
> 선행: Sub-1, Sub-3 (ArapRigger 의 applyMask 호출)
> 사용자 결정: OpenCV grayscale + mask 합집합 (이미 OpenCV dependency 있음)

---

## 1. 목표 / 책임 / 비책임

### 책임

- `MaskRefiner` 신규 — Mask R-CNN 의 mask + 원본 image grayscale 기반 후처리
- ArapRigger 의 `applyMask` 호출 전 `MaskRefiner.refine()` 적용
- OpenCV (이미 dependency) 활용

### 비책임

- 모델 재학습 (큰 별도 작업)
- 그림 의 종이 노이즈 (메모, 라인) 분리 — bbox 영역 한정 으로 단순화
- Sub-2b pose keypoint 정확도 — pose 는 mask 와 무관
- AR overlay 정확도 — Sub-AR-Tracking 그대로

### 최소 PASS 기준

- 이전 짤림 사례 (V-pose 막대 사람 의 다리 끝) → mask 가 다리 끝까지 포함
- 다른 그림 들 도 mask 자연스러움 (mask 영역 의 over-extension X)
- 후처리 latency < 100ms (전체 1-2분 inference 의 noise)

---

## 2. 알고리즘

```
입력: image (RGB Bitmap, 캡처 원본), bbox, mask (Mask R-CNN output, 0/1)

1. ROI = image[bbox area]                       — bbox 안만
2. gray = ROI → grayscale
3. binMask = (gray < threshold 100)             — 검은 선 = 1
4. expandedMask = dilate(mask, 5×5 kernel)      — mask 약간 확장
5. combinedMask = (binMask AND expandedMask)    — bbox 안 + mask 영역 근처 의 검은 선
6. finalMask = (mask OR combinedMask)           — 원래 mask + 검은 선 union
7. finalMask = close(finalMask, 5×5 kernel)     — 작은 구멍 채움

return finalMask
```

핵심: **원래 mask 의 dilation 안** 에 있는 **검은 선** 만 mask 에 추가. bbox 밖 또는 mask 멀리 의 노이즈 (다른 그림, 메모) 는 제외.

---

## 3. 모듈 분해

```
rig/
├── (existing) MaskRcnnDetector.kt   — 그대로
├── MaskRefiner.kt                   (신규) - 후처리 algorithm (OpenCV)
└── (existing) ArapRigger.kt         — applyMask 전에 MaskRefiner.refine() 추가 (1 줄 변경)
```

### MaskRefiner API

```kotlin
object MaskRefiner {
    /**
     * Refines a Mask R-CNN mask using grayscale contours within the bbox.
     *
     * @param image full-frame source bitmap (RGB)
     * @param bbox  detection bbox in image coordinates
     * @param mask  Mask R-CNN output bitmap (any size; will be scaled to bbox)
     * @return refined Bitmap mask (same size as bbox, single channel via alpha)
     */
    fun refine(image: Bitmap, bbox: RectF, mask: Bitmap): Bitmap
}
```

### ArapRigger 변경 (1줄)

기존:
```kotlin
val character = if (top != null) {
    applyMask(image, top.mask, top.bbox)
} else {
    image
}
```

수정:
```kotlin
val character = if (top != null) {
    val refinedMask = com.k3i.stickerbook.rig.MaskRefiner.refine(image, top.bbox, top.mask)
    applyMask(image, refinedMask, top.bbox)
} else {
    image
}
```

---

## 4. 단계적 PoC (MR.1 ~ MR.4)

| M | 내용 | 검증 |
|---|---|---|
| **MR.1** | MaskRefiner.kt + Robolectric unit test (mock image + mock mask → expected union) | 3 tests PASS |
| **MR.2** | ArapRigger 의 applyMask 전 호출 1줄 통합 | unit test 회귀 PASS |
| **MR.3** | 갤탭 시연 — 이전 다리 짤림 그림 으로 비교 | mask 가 다리 끝까지 포함 |
| **MR.4** | 결과 doc + memory | docs/sub_mask_fix_results.md |

---

## 5. 리스크

| # | 리스크 | 대응 |
|---|---|---|
| R1 | grayscale threshold 100 이 그림에 안 맞을 수 | Otsu adaptive 도 시도 가능. 시연 후 조정 |
| R2 | bbox 안 의 다른 메모/라인 도 포함 위험 | expandedMask AND 가 mask 근처만 한정 |
| R3 | 캐릭터 내부 흰 영역 안 채워짐 | morphology closing (5×5) 으로 작은 구멍 채움. 큰 영역 은 의도 (ARAP mesh 가 grid 로 cover) |
| R4 | OpenCV native init overhead | 이미 PaperTracker 에서 init. 동일 dependency |
| R5 | 후처리 의 latency 가 전체 보다 크게 | 100ms 이하 예상 — 1-2분 dominant 의 noise |

---

## 6. 검증

### Unit (Robolectric + JUnit4)

- `MaskRefinerTest`:
  - 검은 사각형 이미지 + 작은 mask → refined mask 가 검은 사각형 전체 포함
  - mask 멀리 (bbox 밖) 의 검은 픽셀 → refined mask 에 포함 X
  - 빈 mask (모두 0) → 빈 mask 그대로

### 갤탭 시연 (MR.3)

이전 시연 의 V-pose 막대 사람 그림 (다리 잘림 사례) 재캡처 → mask 가 다리 끝까지 포함 시각 확인.

PASS: 이전 다리 잘림 사례 가 해소됨.

---

## 7. Sub-Mask-Fix 진입 조건 (이미 충족)

- ✅ Sub-1 + Sub-3 완료
- ✅ OpenCV dependency (Sub-AR-Tracking 에서 추가)

## 8. 후속 영향

- 캐릭터 outline 이 더 완전 → ARAP mesh 가 더 정확 → motion 결과 도 자연스러움
- Sub-AR overlay 의 sticker boundary 도 같이 개선
