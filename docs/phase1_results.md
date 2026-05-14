# Phase 1 결과 — Stickerbook 갤탭 Native MVP

날짜: <측정 시점 채우기>
대상 기기: Galaxy Tab S9 FE+ (SM-X610)
Android 버전: <확인 후 채우기>
앱 버전: 0.1.0 (debug)

## 성능 측정

| 항목 | 측정값 | 목표 | 통과? |
|---|---|---|---|
| PNG seq 재생 FPS (~229–249 frames @ 500px) | __fps | ≥ 30 | ☐ |
| 콜드 스타트 (앱 실행 → 그리드 표시) | __초 | ≤ 3 | ☐ |
| 메모리 피크 (manifest 의 3 스티커 로드) | __MB | ≤ 1024 | ☐ |
| 5 분 연속 재생 후 thermal | 정상/throttle | 정상 | ☐ |
| GIF 저장 시간 | __ms | ≤ 500 | ☐ |

**측정 방법:**
- FPS — `adb logcat -s "Stickerbook.FPS"` 로 1초 단위 평균 확인. 정상 상태 약 1분 평균.
- 콜드 스타트 — 앱 강제 종료 (`adb shell am force-stop com.k3i.stickerbook`) 후 런처에서 탭, 스톱워치 측정.
- 메모리 — Android Studio Profiler 의 Memory 탭, 스티커 상세 화면 진입 후 peak.
- Thermal — `adb shell dumpsys thermalservice` 또는 갤탭 설정 → 배터리 → 발열 알림 여부.
- GIF 저장 — UI 의 "갤러리에 저장" 탭 → Toast 까지의 체감 시간.

## Open Question 답 (Phase 2 입력)

- 자산 전송 (Open Q #1) — ADB push / APK assets/ 중 실제 사용 경험: __
- 출력 포맷 (Open Q #2) — PNG seq 와 GIF 중 재생 부드러움: __
- Phase 1.5 카메라 오버레이 (Open Q #3) — 필요/불필요: __

## 알려진 이슈 / 후속

- 카드 썸네일 (source.png) 디코드가 main thread 에서 일어남 → 다수 스티커 시 스크롤 jank 가능 (현 manifest 3 개라 미관측)
- 모션 1개만 표시 (manifest schema 가 sticker 당 motion 1개로 고정) — 다중 모션은 schema v2 필요
- AnimationPlayer 의 `drawImage` 가 native pixel size 로 렌더 (scale 계산은 현재 dead code) — 큰 PNG (예: 1024px) 면 잘릴 수 있음. MVP 자산 (500px) 에서는 무해
- Smoke test (Task 15) 는 list-screen 존재만 검증 (카드 클릭 흐름은 카드 라벨 자동생성이라 hardcoded 라벨 매칭 어려움)

## Phase 2 진입 조건 체크

- ☐ PNG seq 재생이 갤탭에서 30fps 안정
- ☐ 콜드 스타트 < 3초
- ☐ 메모리 사용 < 1GB
- ☐ 5분 연속 재생 시 thermal throttle 없음
- ☐ GIF 저장이 갤러리에서 보임
- ☐ 재사용 컴포넌트 식별 완료 (data layer / AnimationPlayer)

이 조건들이 Phase 2 (Unity + ARCore) brainstorm 의 input 이 됨.
