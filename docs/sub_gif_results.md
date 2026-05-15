# Sub-GIF 결과 — Animated GIF encoding

날짜: 2026-05-15
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.6.0 (debug)

## MG.1 — AnimatedGifEncoder + helpers 추가

3 파일 Kotlin port (의존성 0, Bitmap API + OutputStream 만 사용):

| 파일 | lines | source |
|---|---|---|
| AnimatedGifEncoder.kt | 246 | bumptech/glide (Kevin Weiner FM Software) |
| NeuQuant.kt | 316 | rtyley/animated-gif-lib-for-java (Anthony Dekker 1994) |
| LzwEncoder.kt | 186 | bumptech/glide (K Weiner 12/00) |
| **합계** | **748** | all public domain |

Kotlin port 핵심 처리:
- `int[][]` → `Array<IntArray>`
- LZW labeled break/continue → `outer@` syntax
- byte/int 변환: `.toInt() and 0xff` / `.toByte()` 명시
- `>>>` (unsigned right shift) → `ushr`
- Glide 의 `getImagePixels()` canvas bug fix (`drawBitmap(temp, ...)` → `drawBitmap(image!!, 0f, 0f, null)`)

## MG.2 — Unit test PASS

| Test | 결과 |
|---|---|
| GIF89a magic 시작 | ✅ |
| image descriptors count | ✅ (small bitmap 한정 검증) |
| Netscape "NETSCAPE2.0" extension | ✅ |
| frame rate → delay (30 fps → 3 1/100s) | ✅ |

기존 64 + 4 = **68 tests PASS**, 0 fail.

## MG.3 — ArapRigger 의 GIF 합성

기존 placeholder (single PNG copyTo) → AnimatedGifEncoder 호출. 30 frame, 30 fps, infinite loop.

## MG.4 — 갤탭 시연 (PASS)

### sticker `arap_1778834983208_phone_1`

| 항목 | 측정값 | 비교 |
|---|---|---|
| GIF byte size | **12.0 MB** | 이전 placeholder ~600 KB → 30 frame 의 NeuQuant + LZW 압축 정상 |
| Real frame count (PIL 파싱) | **30** | ✅ |
| Frame size | 912 × 1224 | character bitmap 그대로 |
| Loop | 0 (infinite) | ✅ |
| Duration per frame | 30 ms (≈ 33 fps) | ≈ 30 fps 설정값 |

### 갤러리 export

갤탭 의 `Pictures/Stickerbook/` 폴더에 GIF 파일 정상 저장. Photos / Samsung Gallery 에서 thumbnail 미리보기 + 클릭 시 애니메이션 재생.

### 이전 sticker 와 size 비교

| sticker | timestamp | GIF size | type |
|---|---|---|---|
| arap_1778834983208_phone_1 | 2026-05-15 17:51 | **12.0 MB** | ✅ animated (30 frame) |
| arap_1778831667138_dance_1 | 16:55 | 658 KB | placeholder (single PNG) |
| arap_1778830559519_dance_1 | 16:36 | 386 KB | placeholder |

## 알려진 이슈 / Follow-up

- ⚠️ GIF 크기 12 MB — 손그림 character 의 PNG frame 이 컬러 다양 → NeuQuant 압축 한계. 더 작게 만들려면 mask 영역만 quantize 또는 grayscale
- ⚠️ GIF encoding 시간 ~수 초 (30 frame × NeuQuant) — 전체 latency 의 일부
- ⚠️ 모바일 messaging app (KakaoTalk, Slack) 별 GIF 미리보기 동작 — 추가 검증 follow-up

## Sub-GIF commits

```text
20b5bc6  docs(gif): design
7872bc3  docs(gif): implementation plan
dab45d8  feat(gif): AnimatedGifEncoder + NeuQuant + LzwEncoder Kotlin port
69907b7  test(gif): TDD — magic / structure / Netscape / delay
c552ed3  feat(gif): ArapRigger encodes real animated GIF
```

## 다음 우선 sub

- **Sub-AR**: 갤탭 camera preview 위에 sticker 가 종이/그림 위에 "벌떡 서서" motion 따라 움직이는 view (master plan §1 의 핵심 시나리오)
