# Sub-AD-Render-Fix — Design Spec

작성일: 2026-05-19
작성자: ingon (k3i_ai5@k3i.co.kr)
저장소: `/mnt/c/Users/leesa/AR_book/stickerbook_android_porting/ad-server/` (= GitHub `ingon1026/StickerBook` `ad-server/`)
원본: `/home/ingon/AR_book/ad-server/`

선행 spec: `2026-05-19-ad-server-design.md`
선행 blocker: ad_runner stub 보류 (commit `af085b7`)

---

## 0. 작업 모드 — 학습 우선 (이전 spec §0 와 동일)

사용자는 client-server / WSL2 / OpenGL 의 처음. implementation 진행 시:

1. 새 개념 등장하면 1-3줄 설명 후 코드
2. 환경변수/시스템 패키지 셋업 시 의미 한 줄씩
3. 에러 만나면 *원인 먼저* → 수정
4. 각 Task 끝에 "방금 한 일/배운 것" 정리

---

## 1. 목적

ad-server M3 의 *진짜* AD 호출 활성화. 갤탭 데모에서 dummy.gif 대신 *실제 캐릭터 모션 GIF* 반환.

비-목표:
- AD 자체의 성능 개선
- AD 의 다른 view backend 비교 (WindowView, custom)
- WSL2 의 X server / WSLg 디버그
- AD upstream 코드 수정

---

## 2. 컨텍스트 — 무엇이 막혔는지

ad-server M3 시도 결과 (commit `cd9bb46` 의 ad_runner.py):
- ✅ torchserve 0.12 native 띄움 (drawn_humanoid_detector, drawn_humanoid_pose_estimator mar 로드)
- ✅ `image_to_annotations` 성공 — mask + skeleton json 생성
- ❌ `annotations_to_animation` 의 *video render* 단계 실패
  - 에러: `AdError: AD pipeline failed: Attempt to retrieve context when no valid context`
  - WSL2 의 DISPLAY=:0 / WSLg 활성화 상태인데도 GLFW window 생성 실패
- 결과: `ad_runner` 를 stub 으로 보류 (`af085b7`)

### 2.1 원인 — AD 가 두 view backend 중 어느 걸 쓰는가

```
AnimatedDrawings/animated_drawings/view/
├── view.py            ── View.create_view(cfg) 가 use_mesa flag 로 분기
├── window_view.py     ── GLFW 기반, visible window 필요 (← 우리가 실패한 모드)
└── mesa_view.py       ── osmesa (software OpenGL), headless 친화 ← 이게 답
```

mesa_view.py 본문:
```python
os.environ['PYOPENGL_PLATFORM'] = "osmesa"
os.environ['MESA_GL_VERSION_OVERRIDE'] = "3.3"
from OpenGL import GL, osmesa
```

AD 작성자가 이미 헤드리스 환경 대비. 우리는 그걸 *활성화* 만 하면 됨.

### 2.2 왜 자동 활성화 안 됐나

`examples/annotations_to_animation.py` 가 mvc_cfg dict 만들 때 `view` 섹션을 *생략*. AD config 의 기본값이 `USE_MESA=false` → WindowView 시도. mvc_cfg 에 `view: USE_MESA: true` 한 줄 추가하면 MesaView 사용.

---

## 3. 아키텍처 — ad_runner 변경

### 3.1 이전 (stub, `af085b7`)

```python
def run(image_path, motion_id, work_dir):
    # 입력 검증 (existence + magic bytes + motion id)
    ...
    # 진짜 호출 대신 dummy.gif 복사
    shutil.copy(settings.DUMMY_GIF, work_dir / "result.gif")
    return work_dir / "result.gif"
```

### 3.2 이후 (이 spec)

```python
def run(image_path, motion_id, work_dir):
    # 1) 입력 검증 (현재와 동일)
    ...

    # 2) AD examples 를 import path 에 추가
    sys.path.insert(0, str(AD_EXAMPLES))

    # 3) annotation 생성 (torchserve 호출)
    from image_to_annotations import image_to_annotations
    char_anno_dir = work_dir / "annotations"
    image_to_annotations(str(image_path), str(char_anno_dir))

    # 4) mvc_cfg 직접 생성 — view 섹션 포함이 핵심
    import yaml
    mvc_cfg = {
        'scene': {
            'ANIMATED_CHARACTERS': [{
                'character_cfg': str((char_anno_dir / 'char_cfg.yaml').resolve()),
                'motion_cfg': str(motion_yaml.resolve()),
                'retarget_cfg': str(RETARGET_CFG.resolve()),
            }],
        },
        'controller': {
            'MODE': 'video_render',
            'OUTPUT_VIDEO_PATH': str((char_anno_dir / 'video.gif').resolve()),
        },
        'view': {'USE_MESA': True},   # ← 핵심
    }
    mvc_cfg_path = char_anno_dir / 'mvc_cfg.yaml'
    with open(mvc_cfg_path, 'w') as f:
        yaml.dump(mvc_cfg, f)

    # 5) render
    from animated_drawings import render
    render.start(str(mvc_cfg_path))

    # 6) 결과 gif 경로 반환
    output = char_anno_dir / 'video.gif'
    if not output.exists():
        raise AdError(f"AD produced no output at {output}")
    return output
```

핵심 변경: AD 의 `image_to_animation` 호출 X. 그 함수가 하는 두 단계 (`image_to_annotations` + `annotations_to_animation`) 중 두 번째를 우리가 *직접 풀어서* 호출. `annotations_to_animation` 안 부르고 그 내부 동작 (mvc_cfg 만들기 + render.start) 우리가 함.

### 3.3 결합 경계 유지

- routes ↔ ad_runner 인터페이스 변경 X (`run(image_path, motion_id, work_dir) -> Path`)
- ad_runner ↔ motion_registry 인터페이스 변경 X
- AD repo 무수정

즉 변경 영향이 ad_runner.py 한 파일에 갇힘.

---

## 4. 의존성

### 4.1 system (sudo 필요)

```bash
sudo apt install libosmesa6
```

확인:
```bash
ldconfig -p | grep -i osmesa
# libOSMesa.so.6 또는 .8 등이 보이면 OK
```

⚠️ 현재 시스템 `ldconfig -p | grep -i osmesa` 결과 빈 줄 → 설치 필요할 가능성 큼.

### 4.2 conda env (animated_drawings)

```bash
conda activate animated_drawings
python -c "from OpenGL import osmesa; print('osmesa python OK')"
```

성공이면 추가 작업 X. 실패 시:
```bash
pip install --force-reinstall PyOpenGL PyOpenGL-accelerate
```

### 4.3 torchserve

이전 commit `af085b7` 의 자산 (`server/scripts/run_torchserve.sh`, `server/torchserve_config/config.properties`) 그대로 사용.

---

## 5. 폴더/파일 변경

| 파일 | 변경 |
|---|---|
| `ad-server/server/app/ad_runner.py` | stub → 실제 호출 (§3.2 참조) |
| `ad-server/server/tests/test_ad_runner.py` | slow integration test 부활 (`@pytest.mark.slow`) |
| `ad-server/README.md` | "AD 진짜 호출 활성화 완료" 로 한 줄 갱신, dependency 섹션 추가 |
| `ad-server/server/requirements.txt` | (변경 X — osmesa 는 system lib + 기존 PyOpenGL) |

---

## 6. 검증 절차

순서 (각 단계 통과해야 다음):

1. **시스템 의존성**: `apt install libosmesa6` 후 `ldconfig -p | grep -i osmesa` 확인
2. **conda env**: `python -c "from OpenGL import osmesa; print('ok')"` PASS
3. **torchserve**: `./scripts/run_torchserve.sh` → `/ping` Healthy + 두 모델 로드
4. **headless OpenGL 직접 검증** (옵션, 빠른 sanity):
   ```python
   import os; os.environ['PYOPENGL_PLATFORM']='osmesa'
   from OpenGL import GL, osmesa
   ctx = osmesa.OSMesaCreateContextExt(osmesa.OSMESA_RGBA, 24, 8, 0, None)
   # ctx 가 None 이 아니면 OK
   ```
5. **ad_runner unit test (fast)**: pytest `-m "not slow"` 4개 PASS (입력 검증)
6. **ad_runner slow integration**: `pytest -m slow` — garlic.png 로 진짜 GIF 생성
7. **routes end-to-end**: curl `POST /process` → 진짜 GIF (dummy 가 아닌 캐릭터 모션)
8. **갤탭 데모**: 합치기 → 진짜 캐릭터 모션 GIF 재생

각 단계 검증 명령어는 implementation plan 에 박힘.

---

## 7. 위험과 한계

| 위험 | 영향 | 완화 |
|---|---|---|
| libosmesa6 가 sudo 권한 막힘 | 진행 불가 | sudo 권한 확보 (사용자 환경 / IT) |
| MesaView 의 SW rendering 이 일부 shader 안 지원 | AD pipeline 일부 실패 | failure 시 fallback 경로 = Xvfb 우회 (별도 sub-task) |
| osmesa context 생성 자체 실패 (libosmesa version 차이) | render 안 됨 | 에러 메시지 분석 → libOSMesa.so 버전 확인 |
| AD render 시간 길어짐 (SW) | 1요청 5분 초과 가능 | uvicorn read_timeout 이미 large. Android 클라이언트는 300초 timeout |
| torchserve 가 떠 있어야 함 | M3 의존성 사슬 길어짐 | README 의 셋업 절차에 torchserve 단계 추가 |
| WSL IP 변경 (이전 spec 의 함정) | render 와 무관 | 영향 X — 우리 작업은 PC 내부 |

### 7.1 fallback 경로 (이 spec 밖)

다음 중 하나라도 *실제 검증 단계에서* 막히면 Xvfb 우회 sub-task 로 전환:
- `from OpenGL import osmesa` import 자체 실패 (PyOpenGL 재설치도 안 됨)
- `OSMesaCreateContextExt` 가 None 반환
- AD render 가 osmesa 활성화 후 다른 GL 에러 (shader 비호환 등)

이번 시도 실패해도 `ad_runner.py` 다시 stub 으로 cherry-pick (`af085b7`) 으로 즉시 복귀 가능. Xvfb 도 단일 sub-task spec 으로 별도 진행.

---

## 8. 비-목표 / Out of scope

- AD 의 다른 mvc 옵션 (camera_pos, background_image 등) 튜닝
- 진짜 호출의 *재시도/timeout* 로직 — 한 번 시도, 실패 시 AdError
- 다중 동시 요청 처리 (uvicorn worker 1개 그대로)
- 결과 GIF 의 화질/프레임 수 변경
- WSL2 OpenGL 환경 자체의 디버그 (GLFW window mode 작동 시도 안 함)

---

## 9. 검수 체크리스트 (구현 완료 시점)

- [ ] `libosmesa6` 설치 + `ldconfig` 에 보임
- [ ] `from OpenGL import osmesa` import 성공
- [ ] torchserve 띄우면 mar 2개 로드
- [ ] fast pytest 5개 다 PASS (기존 유지)
- [ ] slow pytest 의 `test_run_with_real_drawing` PASS — garlic.png → 캐릭터 움직이는 gif 생성
- [ ] curl POST /process (real PNG + dab motion) → 200 image/gif, 결과를 file viewer 에서 캐릭터 움직임 확인
- [ ] 갤탭에서 합치기 → 진짜 GIF 재생
- [ ] README 갱신 (dependency 섹션 + AD 진짜 호출 활성화 표기)

---

## 10. 후속 작업 (이 spec 밖)

- Xvfb fallback (Mesa 실패 시)
- AD upstream 으로 view.USE_MESA default 변경 PR (오픈소스 기여)
- 결과 GIF 캐싱 (image+motion 해시)
- 비동기 job 패턴 (5분 초과 요청)

---

끝.