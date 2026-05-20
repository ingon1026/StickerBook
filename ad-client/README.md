# ad-client — ad-server 의 Android 클라이언트

태블릿 앱: 그림 선택 → motion 선택 → "합치기" → 서버에서 GIF 받아 표시.

## Open in Android Studio

`File → Open` → 이 폴더 (`ad-client/`) 선택. Gradle sync 5–10분.

## 한 번만 박는 설정

`app/src/main/java/com/k3i/adclient/net/Config.kt` 의 `BASE_URL` 을 PC 의 Wi-Fi IP 로 변경:

```kotlin
const val BASE_URL = "http://192.168.68.176:8000"
//                          ^^^^^^^^^^^^^^
//                          PC ipconfig 결과의 무선 LAN 어댑터 IPv4
```

## 실행

1. 갤탭 USB 연결 (USB 디버깅 허용)
2. Android Studio 에서 **▶ Run** (`Shift+F10`) — **Debug 아님**
3. 갤탭에 자동 설치 + 실행

## 시연

1. **이미지 선택** → 갤러리 (jpeg/png)
2. **Motion** 드롭다운 → `dab` / `wave_hello` / `jumping`
3. **합치기** 버튼
4. 하단에 결과 GIF 재생

## 트러블슈팅

서버 측 셋업 (방화벽 / portproxy / WSL IP) 은 `../ad-server/README.md` 참조.

| 증상 | 해결 |
|---|---|
| `에러 -1: failed to connect ... after 10000ms` | 같은 Wi-Fi SSID 확인 + Config.kt 의 BASE_URL 검증 + 서버 측 portproxy 갱신 |
| `에러 422: AD failed: input is not a valid image` | jpeg/png 만 가능. GIF/WebP/HEIC 거부 |
| `에러 400: unknown motion: ...` | `MainActivity.kt` 의 motion 리스트와 server `motion_registry.py` 동기화 |
| 갤탭 인식 안 됨 | USB 디버깅 켜기 + USB 케이블 데이터 지원 확인 |

## 폴더 구조

```
ad-client/
├── app/src/main/
│   ├── AndroidManifest.xml         — INTERNET, cleartext 허용
│   ├── res/layout/activity_main.xml
│   └── java/com/k3i/adclient/
│       ├── MainActivity.kt         — UI + 갤러리 + Spinner + 합치기
│       └── net/
│           ├── Config.kt           — BASE_URL ← swap 포인트
│           └── AdApi.kt            — OkHttp multipart + 코루틴
├── build.gradle.kts                — AGP 8.5, Kotlin 1.9.24
├── settings.gradle.kts
└── gradle/, gradlew, gradlew.bat   — Gradle 8.7 wrapper

설치 후 자동 생성 (gitignore):
└── local.properties                — sdk.dir=<Android SDK 경로>
```

## 첫 빌드 시 local.properties 필요

Android Studio 가 자동 생성하지만 안 되면 수동:

`ad-client/local.properties`:
```
sdk.dir=C\:\\Users\\leesa\\AppData\\Local\\Android\\Sdk
```

(역슬래시 두 개 + 콜론 앞에도 역슬래시 — Java properties 형식)