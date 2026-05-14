# Phase 2 마스터 Design — V2 정식 (갤탭 단독 on-device)

> 작성일: 2026-05-14
> 상태: 마스터 (각 sub-project 의 detailed spec 은 별도)
> 진입 조건: Phase 1 (자산 재생기 + PC export) 완료 — `../plans/2026-05-14-stickerbook-android-porting-plan.md`
> 출구 조건: 갤탭이 카메라로 그림 인식 + 캐릭터 추출 + 모션 적용 + AR 합성 모두 on-device 로 수행

---

## 목표

**갤럭시 태블릿이 단독으로** 다음 흐름을 처리:

```
[갤탭 카메라 미리보기]
   ↓ 캡처 탭
[캡처된 그림 사진]
   ↓ 캐릭터 추출 (Sub-1 + Sub-2)
[캐릭터 mesh + skeleton]
   ↓ PC 에서 전달받은 BVH 모션 선택 + 적용 (Sub-3 + Sub-4)
[rigged + animated character frames]
   ↓ 렌더 (Sub-5)
[갤탭 화면에 표시 + 라이브러리 저장]
```

- 네트워크 불필요
- PC 의존성: BVH 모션 파일만 사전 전달 (`assets/motions/library/*.bvh`). 모션 자체는 PC 가 만들고, 갤탭은 받기만.

---

## 왜 큰 작업인가

V1 PC 의 **AnimatedDrawings** (Detectron2 + AlphaPose + ARAP + Desktop OpenGL) 를 모바일에서 재현해야 함. brainstorm 단계 (2026-05-14 `../../android_tablet_porting_brainstorm.md`) 에서 "비현실" 로 평가됐던 길. 사용자 OPT-1 결정: 단기 deadline 없이 정식 R&D 트랙으로 진행.

추정 작업량: 5 sub-project 합쳐 **수 개월**.

---

## 5 sub-project 분해

| sub | 책임 | 핵심 도전 |
|---|---|---|
| **Sub-5** | 갤탭 UI + CameraX 캡처 + Android GLES 렌더 | Phase 1 코드 일부 재사용. 카메라 권한 / orientation / preview ↔ 캡처 안정성 |
| **Sub-1** | 그림 detection (humanoid 영역 인식) | Detectron2 → 모바일 (TFLite / ONNX). 또는 Annotated Drawings 데이터셋으로 YOLO retrain |
| **Sub-2** | pose estimation (그림 캐릭터 관절 추정) | AlphaPose → 모바일 변환 어려움. fallback: 사용자 탭으로 관절 위치 지정 (RakugakiAR 방식) |
| **Sub-3** | ARAP mesh 변형 | Python scipy 의 sparse LU → Kotlin/C++ 재구현. 핵심 알고리즘 (~수천 줄). NDK 권장 |
| **Sub-4** | BVH parser + retarget | Python → Kotlin. 상대적으로 단순 (~수백 줄). 회전 보간 / Rokoko cm convention 처리 |

---

## 의존성 + 권장 순서

```
Sub-5 (UI 셸 + 카메라 캡처)
   ─ 가장 단순, 다른 sub 의존 0
   ─ Phase 1 의 Manifest/AssetRepository/AnimationPlayer 재사용
   ─ 다른 sub 의 Stub interface 제공 (placeholder)
       ↓
Sub-1 (detection) + Sub-2 (pose)   ─ 위험 가장 큼. 둘 다 ML 모델 변환
   ─ Sub-2 가 어려우면 사용자 탭 fallback 으로 우회
       ↓
Sub-3 (ARAP)   ─ Sub-1/2 결과 (캐릭터 영역 + 관절) 가 input
   ─ 알고리즘 큰 작업이지만 의존성 단순
       ↓
Sub-4 (BVH retarget)   ─ Sub-3 의 rigged mesh 위에 모션 적용
   ─ PC 의 .bvh 파일 + Sub-3 의 skeleton mapping
       ↓
[전체 통합 → Sub-5 의 Stub interface 를 실제 구현으로 교체]
```

권장 순서: **Sub-5 → Sub-1 + Sub-2 (병렬) → Sub-3 → Sub-4 → 통합**.

사용자 선택 (2026-05-14): **Sub-5 부터 시작**.

---

## Risks + Mitigation

| # | Risk | 대응 |
|---|---|---|
| R1 | Detectron2 의 모바일 op 미지원 → Sub-1 막힘 | Annotated Drawings 데이터셋으로 YOLOv8 retrain (모바일 친화) 으로 우회 |
| R2 | AlphaPose 변환 불가 → Sub-2 막힘 | 사용자 탭 기반 관절 지정 (RakugakiAR 방식). UI 추가 작업 발생 |
| R3 | ARAP Kotlin 재구현 성능 부족 | NDK + Eigen (sparse LU 라이브러리). 또는 Compute Shader |
| R4 | BVH retarget 의 회전 보간 정확도 | PC 의 `bvh_writer.py` 코드 참고. Rokoko cm convention 그대로 유지 |
| R5 | 5 sub 통합 시 인터페이스 불일치 | Sub-5 단계에서 모든 sub 의 interface (Stub) 미리 정의. 후속 sub 는 그 interface 채우기만 |
| R6 | 단기 deadline 압박 → OPT-1 포기 → OPT-2 (탭 기반) 로 전환 | 분해 구조가 OPT-1 ↔ OPT-2 양립 가능. Sub-2 만 fallback 으로 교체. 다른 sub 는 그대로 |

---

## Milestone

| M | Sub | 검증 |
|---|---|---|
| M1 | Sub-5 (UI + 카메라) | 갤탭에서 카메라 → 캡처 → 모션 선택 → Stub 결과 만들기 → 라이브러리 추가 흐름 동작 |
| M2 | Sub-1 (detection) | 그림 1장 → humanoid bbox 정확도 ≥ 70% (Annotated Drawings test set) |
| M3 | Sub-2 (pose) | bbox 안에서 관절 17개 (또는 reduced set) 추정. 또는 사용자 탭 UI fallback |
| M4 | Sub-3 (ARAP) | dummy character + 사전 정의 skeleton 으로 ARAP 변형 동작. 갤탭 60fps |
| M5 | Sub-4 (BVH) | 1 BVH 모션 + Sub-3 결과 → 한 cycle 재생 |
| M6 | 통합 | Sub-5 의 StubRigger 를 실제 AdRigger 로 교체. end-to-end 동작 |

---

## Sub-project 별 spec 위치

각 sub 의 detailed spec 은 별도 파일로 생성 예정:

- Sub-5: `2026-05-14-sub5-camera-ui-design.md` (현재 작성 중)
- Sub-1: `<date>-sub1-detection-design.md` (M1 완료 후)
- Sub-2: `<date>-sub2-pose-design.md`
- Sub-3: `<date>-sub3-arap-design.md`
- Sub-4: `<date>-sub4-bvh-design.md`

각 spec → 별도 plan → 별도 implementation cycle.

---

## 비포함 (명시적)

- iOS / Web / Unity 다른 플랫폼 — Android 전용
- 네트워크 / 클라우드 호출 — on-device 만
- 사용자 동작 녹화 (M키 BVH 생성) — PC 측 책임 유지
- 다중 캐릭터 동시 추적 / AR 합성 — Sub-3 이후 후속 단계
