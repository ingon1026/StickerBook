# Sub-GIF Design — Animated GIF encoding

> 작성일: 2026-05-15
> Sub-Quantize 완료 후 두 번째 follow-up. 다음 = Sub-AR (overlay on camera)
> 선행: `2026-05-15-sub3-arap-mesh-design.md`
> 사용자 결정: AndroidGifEncoder source 포함 (public domain, 의존성 0)

---

## 1. 목표 / 책임 / 비책임

### 책임

- `gif/AnimatedGifEncoder.kt` — public domain Java code 의 Kotlin port (~200 line, 의존성 0)
- `ArapRigger.rig()` 의 `animation.gif` placeholder (현재 첫 frame PNG copy) 를 진짜 animated GIF 로 합성
- 기존 `AnimationSaver.saveGif()` 그대로 활용 — 갤러리 export 자동 작동
- Unit test: encoder 결과의 GIF89a header + frame count 검증

### 비책임

- AR overlay (다음 sub)
- Audio / video 형식 (MP4 등)
- 사용자 정의 fps / loop count UI (hardcoded 30fps, infinite loop)
- 이미 존재하는 sticker 의 GIF 재생성 (다음 캡처부터만 적용)

### 최소 PASS 기준

1. ArapRigger 결과 sticker 의 `animation.gif` 가 진짜 animated GIF (30 frame, 30 fps, 1초 길이, 무한 loop)
2. 갤탭 상세 화면 "갤러리에 저장" → MediaStore Pictures/Stickerbook 폴더
3. PC image viewer 에서 30 frame 애니메이션 재생
4. 갤탭 의 다른 앱 (Slack, KakaoTalk) 에서 GIF 공유 시 정상 재생

---

## 2. 모듈 분해 + 데이터 계약

### 모듈 구조

```
rig/
└── (existing) ArapRigger.kt — 수정 (animation.gif 합성 부분)

gif/                                    (신규 sub-package)
└── AnimatedGifEncoder.kt              (신규) - public domain port
```

### ArapRigger 변경

기존 placeholder:
```kotlin
File(framesDir, "0001.png").copyTo(File(sDir, "animation.gif"), overwrite = true)
```

새 합성:
```kotlin
val gifFile = File(sDir, "animation.gif")
val encoder = AnimatedGifEncoder()
gifFile.outputStream().use { os ->
    encoder.start(os)
    encoder.setRepeat(0)  // infinite loop
    encoder.setFrameRate(30f)
    for (i in 1..FRAME_COUNT) {
        val name = i.toString().padStart(4, '0') + ".png"
        val frame = BitmapFactory.decodeFile(File(framesDir, name).absolutePath)
        encoder.addFrame(frame)
        frame.recycle()
    }
    encoder.finish()
}
```

### AnimatedGifEncoder API

```kotlin
class AnimatedGifEncoder {
    fun start(os: OutputStream): Boolean
    fun setRepeat(count: Int)         // 0 = infinite
    fun setFrameRate(fps: Float)      // → delay in 1/100 seconds per frame
    fun addFrame(bmp: Bitmap): Boolean
    fun finish(): Boolean
}
```

NeuQuant color quantization (256 colors per frame) + LZW compression. 표준 GIF89a 출력.

### 파일 변경 요약

| 파일 | 종류 |
|---|---|
| `app/app/src/main/java/com/k3i/stickerbook/gif/AnimatedGifEncoder.kt` | 신규 (public domain port) |
| `app/app/src/main/java/com/k3i/stickerbook/gif/NeuQuant.kt` | 신규 (NeuQuant 알고리즘 helper, encoder 의 일부) |
| `app/app/src/main/java/com/k3i/stickerbook/gif/LzwEncoder.kt` | 신규 (LZW compression, encoder 의 일부) |
| `app/app/src/main/java/com/k3i/stickerbook/rig/ArapRigger.kt` | 수정 (animation.gif 합성) |
| `app/app/src/test/java/com/k3i/stickerbook/gif/AnimatedGifEncoderTest.kt` | 신규 (header + frame count 검증) |
| `docs/sub_gif_results.md` | 신규 |

---

## 3. 단계적 PoC (MG.1 ~ MG.5)

| M | 내용 | 검증 |
|---|---|---|
| **MG.1** | AnimatedGifEncoder + NeuQuant + LzwEncoder 추가 (public domain port) | compile PASS |
| **MG.2** | Unit test: 5 frame 의 작은 GIF encode → header + frame count 검증 | TDD PASS |
| **MG.3** | ArapRigger 의 placeholder copyTo → 실 encoder 호출 | unit test 회귀 PASS |
| **MG.4** | APK build + 갤탭 시연 | sticker 만들고 갤러리 save → image viewer 에서 30 frame 애니메이션 재생 |
| **MG.5** | 결과 doc + memory | docs/sub_gif_results.md |

---

## 4. 리스크 + 검증

### 리스크 매트릭스

| # | 리스크 | 영향 | 대응 |
|---|---|---|---|
| R1 | GIF encoding 시간 (30 frame × NeuQuant) | 추가 1-3초 latency | scope 외, acceptable |
| R2 | AnimatedGifEncoder 의 Bitmap 처리 OOM | crash | 매 frame `recycle()` 후 다음 frame |
| R3 | 일부 갤러리 앱 의 GIF preview 미지원 | UX 저하 | 표준 GIF89a 사용. 거의 모든 viewer 호환 |
| R4 | Color quantization 256 colors 손실 | 색 어색 | 손그림 색 적어 영향 작음 |
| R5 | Public domain source 의 Kotlin port 중 type 변환 (int → Int 등) 실수 | encoding fail | unit test 가 잡음 |

### 검증

**Unit (Robolectric + JUnit4)**
- `AnimatedGifEncoderTest`:
  - 5 frame fake bitmap → encode → output 의 GIF89a magic (0x47 0x49 0x46 0x38 0x39 0x61) 확인
  - frame count = 5 검증 (output 의 image descriptor 의 갯수)
  - loop count = 0 (infinite) 의 Netscape extension block 검증

**통합 (MG.4)**
- ArapRigger 결과 sticker 의 animation.gif file 의 byte size > 첫 frame PNG byte size (= placeholder 시절) 확인
- ImageDecoder (Android Q+) 또는 Glide 로 GIF load 시 frame 30+ 확인

**갤탭 시연 (MG.4)**
1. sticker 생성 (캡처 → motion 선택 → 만들기)
2. 상세 화면 "갤러리에 저장" 클릭
3. 갤탭 의 Photos 앱 또는 Files 앱 → Pictures/Stickerbook 폴더
4. GIF thumbnail 클릭 → 30 frame 애니메이션 재생 확인
5. Slack/KakaoTalk 에 공유 → 다른 device 에서도 정상 재생

---

## 5. Sub-GIF 진입 조건 (이미 충족)

- ✅ Sub-3 ArapRigger 가 30 PNG frame 생성
- ✅ AnimationSaver.saveGif() 가 MediaStore export 작동
- ✅ Public domain AnimatedGifEncoder Java code 가 잘 알려짐 (source.net 등)

## 6. 후속 영향

- 사용자가 갤러리/Slack/KakaoTalk 등으로 sticker 공유 가능
- 다음 sub (Sub-AR): camera preview 위에 sticker 가 살아남. GIF 가 input 으로 활용 가능 (또는 frame PNG sequence 그대로)
