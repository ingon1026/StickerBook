# ad-server — client-server 데모

태블릿(Android) → PC(FastAPI) → AnimatedDrawings → GIF → 태블릿. 학습용 R&D 트랙. 회사 서버 이관 시 `Config.kt` 의 `BASE_URL` 1줄 변경.

## 폴더 구조

```
ad-server/
├── server/                 — PC 측 FastAPI 서버
├── shared/API.md           — 서버/클라이언트 공통 계약
└── docs/superpowers/       — design spec + implementation plan

../ad-client/               — Android 클라이언트 (별도 폴더)
```

## 현재 동작 상태 (2026-05-19)

- ✅ 통신 파이프라인 완성 — 갤탭 → Wi-Fi → portproxy → uvicorn → 갤탭
- ✅ AD 진짜 호출 활성화 (Sub-AD-Render-Fix 완료) — MesaView (osmesa) 헤드리스 렌더링
  - 의존성: system `libosmesa6` + `libosmesa6-dev` (한 번 sudo apt install)
  - torchserve 가 떠 있어야 진짜 GIF 생성 — `./scripts/run_torchserve.sh`
- 클라이언트 입장에선 응답 형식 동일 → 호출자 코드 변경 X

---

## PC 재부팅 후 재현 절차

### A. 한 번만 박는 설정 (이미 했으면 skip)

#### 0) AD 렌더링 의존성 (Sub-AD-Render-Fix 결과)

WSL 터미널:
```bash
sudo apt update
sudo apt install -y libosmesa6 libosmesa6-dev
```
확인:
```bash
ldconfig -p | grep -i osmesa
# libOSMesa.so.6 또는 .8 보이면 OK
```

conda env 검증:
```bash
conda activate animated_drawings
PYOPENGL_PLATFORM=osmesa python -c "from OpenGL import osmesa; print('ok')"
```

#### 1) Windows 방화벽 — 포트 8000 인바운드 허용

PowerShell **관리자**:

```powershell
New-NetFirewallRule -DisplayName "ad-server 8000" -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Allow
```

검증:
```powershell
Get-NetFirewallRule -DisplayName "ad-server 8000" | Select-Object DisplayName, Enabled, Action
# Enabled=True, Action=Allow 면 OK
```

→ 영구 적용. PC 재부팅 후에도 살아 있음.

---

### B. 매 PC 재부팅 시 셋업 (5분)

#### 1) PC Wi-Fi 가 갤탭과 같은 SSID 에 연결됐는지

```powershell
ipconfig
```
- "무선 LAN 어댑터 Wi-Fi" 의 IPv4 주소 확인
- `169.254.x.x` (APIPA) 면 Wi-Fi 가 *연결됐지만 IP 못 받음* — Wi-Fi 다시 연결
- `192.168.x.x` 또는 `10.x.x.x` 같은 정상 사설 IP 면 OK

**PC Wi-Fi IP 기억해 두기** (예: `192.168.68.176`). 클라이언트의 `Config.kt` 와 일치해야 함.

#### 2) WSL IP 확인 (재부팅마다 바뀔 수 있음)

WSL 터미널:
```bash
hostname -I
# 예: 172.26.167.1
```

#### 3) WSL2 portproxy 갱신 — PowerShell **관리자**

```powershell
netsh interface portproxy delete v4tov4 listenport=8000 listenaddress=0.0.0.0
netsh interface portproxy add v4tov4 listenport=8000 listenaddress=0.0.0.0 connectport=8000 connectaddress=<WSL_IP>
netsh interface portproxy show v4tov4
```

`<WSL_IP>` 자리에 step 2 의 값 (예: `172.26.167.1`).

기대 출력:
```
0.0.0.0  8000  →  172.26.167.1  8000
```

> 📚 이 명령이 하는 일: 외부 NIC `<PC-Wi-Fi-IP>:8000` 으로 들어온 패킷을 WSL 안 uvicorn 으로 forward. WSL2 의 자동 localhost forwarding 이 작동 안 하는 환경 대처.

#### 3.5) torchserve 띄움 (AD 호출 의존성)

WSL 터미널 (별도):
```bash
/home/ingon/AR_book/ad-server/server/scripts/run_torchserve.sh
```
Healthy 까지 1~30초 (모델 mar cache 영향).

검증:
```bash
curl -s http://localhost:8080/ping
# {"status": "Healthy"}

curl -s http://localhost:8081/models | grep modelName
# drawn_humanoid_detector + drawn_humanoid_pose_estimator
```

⚠ torchserve 가 떠 있지 않으면 `/process` 가 422 (`AD failed: image_to_annotations failed`). uvicorn 띄우기 전에 torchserve 부터.

#### 4) FastAPI 서버 띄움

WSL 터미널:
```bash
cd /home/ingon/AR_book/ad-server/server
./scripts/run.sh
```

기대:
```
INFO: Uvicorn running on http://0.0.0.0:8000
```

서버는 이 터미널 살아있는 동안 도는 중. 끄려면 `Ctrl+C`.

#### 5) PC 자체에서 검증

다른 터미널:
```bash
curl http://localhost:8000/health
# {"status":"ok"}

curl http://<PC-Wi-Fi-IP>:8000/health
# 같은 응답 — 외부 NIC 통과 확인
```

---

### C. Android 클라이언트 측

#### 1) `Config.kt` 의 BASE_URL 이 PC Wi-Fi IP 와 일치하는지

`ad-client/app/src/main/java/com/k3i/adclient/net/Config.kt`:
```kotlin
const val BASE_URL = "http://192.168.68.176:8000"
```

PC Wi-Fi IP 가 바뀌면 이 줄 갱신 후 Android Studio 에서 Run.

#### 2) 갤탭이 PC 와 *같은 Wi-Fi (SSID)* 연결

설정 → Wi-Fi → PC 가 접속한 같은 SSID

#### 3) Android Studio 에서 ▶ Run (Shift+F10)

- Debug (`Shift+F9`) 말고 **Run** 사용
- 갤탭에 자동 설치 + 실행

---

## 시연 시나리오

1. 갤탭에서 **AD Client** 앱 켜기
2. **"이미지 선택"** → 갤러리 → 그림 (jpeg/png) 선택 → 상단 미리보기에 표시
3. **Motion Spinner** → `dab` / `wave_hello` / `jumping` 중 하나
4. **"합치기"** → "서버 처리 중…" → 잠시 후 "완료 (N bytes)"
5. 하단 회색 칸에 GIF 자동 재생 — 선택한 그림 캐릭터가 motion 따라 움직임

---

## 트러블슈팅

| 증상 | 진단 | 해결 |
|---|---|---|
| 합치기 → `에러 -1: failed to connect to ... after 10000ms` | 네트워크 미도달 | 갤탭과 PC 가 같은 Wi-Fi SSID 인지 / PC Wi-Fi IP 가 Config.kt 와 일치하는지 / portproxy 갱신됐는지 |
| Config.kt 갱신했는데 그대로 | 빌드 캐시 | Android Studio Run 다시 (앱 강제 종료 후 다시 켜는 것과 다름 — Build → Make + Run) |
| `에러 422: AD failed: input is not a valid image` | jpeg/png 가 아님 | GIF/WebP/HEIC 등은 거부됨. 갤러리에서 jpeg/png 선택 |
| `에러 400: unknown motion: ...` | Spinner ↔ server 의 motion id 불일치 | `MainActivity.kt` 의 `motions` 리스트와 `server/app/motion_registry.py` 의 `_REGISTRY` 동기화 |
| Windows curl 으로 `localhost:8000/health` 는 되는데 `<Wi-Fi-IP>:8000/health` 는 timeout | portproxy connectaddress 가 잘못된 WSL IP 또는 방화벽 차단 | step B.3 portproxy 갱신 + step A.1 방화벽 룰 확인 |
| Wi-Fi IP 가 `169.254.x.x` | DHCP 실패 (APIPA) | Wi-Fi 해제 후 재연결. 안 되면 다른 Wi-Fi 시도 |
| uvicorn 실행 시 `ModuleNotFoundError: app` | 작업 디렉토리 잘못 | `cd ad-server/server` 후 `./scripts/run.sh` |
| pytest 실행 시 ROS2 lark 에러 | PYTHONPATH 침범 | `PYTHONPATH= AMENT_PREFIX_PATH= pytest ...` (run.sh / pytest.ini 가 이미 처리하지만 직접 호출 시) |
| 갤탭 처음 실행 시 "Waiting for debugger" | Debug 모드로 Run | ⏹ Stop → ▶ Run (`Shift+F9` 가 아니라 `Shift+F10`) |

---

## 알려진 한계

- **torchserve + osmesa 모두 필요**: 어느 하나 빠지면 422. 셋업 절차 A.0 + B.3.5 참조.
- **AD render 가 SW renderer (llvmpipe)**: 30 frame GIF 8초 ~ 수십 초 (CPU 코어 수에 비례). 더 빠른 GPU 패스는 별도 작업.
- **WSL IP 가 재부팅 시 변경 가능**: portproxy 매번 갱신 필요. 영구 해결책은 `.wslconfig` 의 `networkingMode=mirrored` (별도 셋업).
- **HTTP cleartext**: 회사 서버 이관 시 HTTPS + 인증서 필요. 지금은 LAN 데모 한정.
- **동시 1 요청만**: uvicorn worker 1개. 다중 사용자 시 worker 늘리거나 비동기 job 패턴.

---

## API 명세

`shared/API.md` 참조.

## 설계 문서

- spec: `docs/superpowers/specs/2026-05-19-ad-server-design.md`
- plan: `docs/superpowers/plans/2026-05-19-ad-server-mvp.md`

---

## 환경 정보 (참고)

- conda env: `animated_drawings` (Python 3.8.13, torch 2.0.0+cpu, AD installed)
- AD repo: `/home/ingon/AR_book/AnimatedDrawings/`
- Android Studio Koala+ + JBR 21
- 갤탭: Galaxy Tab S9 FE+ (SM-X610), device id `R54X4008CET`
- Windows ADB: `C:\Users\leesa\AppData\Local\Android\Sdk\platform-tools\adb.exe`
