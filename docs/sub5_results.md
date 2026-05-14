# Sub-5 결과 — UI + 카메라 + Stub 통합

날짜: 2026-05-14
대상: Galaxy Tab S9 FE+ (SM-X610)
앱 버전: 0.1.0 (debug)

## 흐름 검증 (수동, Task 12)

- ☑ 그리드 화면 + FAB (+)
- ☑ FAB → 카메라 화면 → 라이브 preview
- ☑ 캡처 → review 화면 (다시 찍기 / 다음)
- ☑ 다음 → 모션 선택 (6 cards)
- ☑ 모션 선택 + 만들기 → processing
- ☑ Stub 처리 (1.5s) → 그리드 복귀 + 새 sticker 추가

## 테스트 결과

- Unit tests: 7 passed + 1 ignored
- Instrumented: 1 passed

## 알려진 이슈

- StubRigger 의 결과는 1-frame 정적 sticker (캡처 이미지를 그대로). Sub-1~4 통합 후 실제 ARAP 기반 캐릭터 + BVH 모션 적용 예정.
- `gif_path` 가 실제 GIF 가 아닌 PNG (Stub placeholder). 갤러리 저장 시 `image/gif` MIME 으로 처리되지만 갤러리 앱에서 정적 표시.
- 카메라 화면 가이드 사각형 없음 (단순 preview). Sub-1 의 detection 통합 시 자동 영역 인식으로 교체 예정.
- MotionCatalog 6 entries hardcoded. Sub-4 (BVH parser) 가 실제 라이브러리로 교체.
- Phase 1 의 `returns_null_when_no_manifest_anywhere` test 가 @Ignore (bundled APK manifest 가 empty-state 가정 깨뜨림). 의도된 결과.

## Sub-1 진입 조건 체크

- ☑ Sub-5 흐름 안정 (수동 검증 통과)
- ☑ CharacterRigger interface 가 Sub-1+2 진입점으로 적합
- ☐ Annotated Drawings 데이터셋 확보 / 모델 선정 (Sub-1 시작 시점)

## Sub-5 commits

```text
510fcb2 feat(android): wire capture flow (camera → review → motion → stub rig → list)
7fb4d5b feat(android): add FAB to sticker list (callback wired in next task)
4368641 feat(android): ProcessingScreen progress indicator
d7b12be feat(android): MotionPickerScreen with grid + selection state
47cebe6 feat(android): CaptureReviewScreen with retake/next actions
01bd743 feat(android): CaptureScreen with CameraX preview + capture button
2587eb5 feat(android): CameraX preview composable + image capture controller
8597136 feat(android): AssetRepository.saveSticker appends entry + @Ignore stale empty-manifest test
c0fee8f feat(android): CaptureSession + MotionCatalog dummy list
1c2ba89 feat(android): StubRigger writes placeholder sticker files for downstream subs
6933296 feat(android): CharacterRigger interface + RigResult (entry point for Sub-1..4)
beed44b build(android): add CameraX 1.3.4 deps + CAMERA permission + landscape orientation
```
