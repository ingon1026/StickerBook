# ad-server

태블릿 → PC 서버 → AnimatedDrawings → GIF 반환 데모.

## Quickstart

### 서버 (PC)

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
# AnimatedDrawings 설치 (Task 6 에서 진행)
# pip install -e ../../AnimatedDrawings
./scripts/run.sh
```

### 클라이언트 (Android)

Android Studio 에서 `android-client/` 열기 → `app/src/main/java/com/k3i/adclient/net/Config.kt` 의 `BASE_URL` 을 PC IP 로 수정 → 갤탭에 빌드/설치.

### LAN 검증

```bash
curl http://<PC-IP>:8000/health
```

## 문서

- 설계 spec: `docs/superpowers/specs/2026-05-19-ad-server-design.md`
- Implementation plan: `docs/superpowers/plans/2026-05-19-ad-server-mvp.md`
- API 명세 (Task 8 에서 추가): `shared/API.md`
