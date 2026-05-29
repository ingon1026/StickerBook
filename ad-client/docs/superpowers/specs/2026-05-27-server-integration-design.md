# ad-client — 회사 서버(ad-server) 연동 (1차 데모)

> **작성일**: 2026-05-27
> **대상**: 사용자 본인 갤탭에서 ad-server 호출 동작 검증 (1차 시연)
> **선행 문서**: `../../../HANDOFF.md` (회사 서버 측, ad-server API/포트/API 키 변경 사항)

---

## 1. 목적

회사 서버에 떠 있는 ad-server (`http://211.194.140.28:58000`) 를 **본인 갤탭에서 호출해 GIF 받는 것 입증**.

성공 기준 (둘 다):
- `합치기` 버튼 1회 → 17~40초 후 GIF 표시. statusText 에 시간/KB 출력.
- 잘못된 API 키일 때 401 + 친근한 메시지 (선택).

비-목표:
- AR 합성 (Phase 2 본체 영역, 별 트랙)
- 카메라 캡처 (이미 갤러리 picker 로 충분)
- 다중 사용자 / 부하 (별 sprint — HANDOFF.md §6)

## 2. 현재 상태 (변경 X)

`ad-client` 가 이미 90% 완성:
- `MainActivity.kt` — 이미지 picker + 회전 + motion Spinner (11개, 서버 motion_registry 와 일치) + 합치기 버튼 + GIF 표시 (Glide)
- `AdApi.kt` — OkHttp + multipart + 코루틴 + `sealed Result.Ok/Err`
- `Config.kt` — BASE_URL + read 300s/connect 10s 타임아웃
- `ImageRotator.kt` — JPEG bytes 변환 + 90° 회전
- `AndroidManifest.xml` — INTERNET 권한 + `usesCleartextTraffic=true` (사내망 HTTP 허용)

→ **UI/통신 골격은 그대로**. 변경할 것은 (1) 서버 주소 (2) 보안 헤더 두 가지만.

## 3. 변경 사항 (4 파일, ~15줄)

### 3.1 `local.properties` — API 키 보관 (1줄)

```properties
AD_SERVER_API_KEY=7e350c43d6346a7410662ca44f0527cc85dcf26f4cb890ba956f9598bb6fcad1
```

- Android Studio 의 기본 `.gitignore` 에 이미 포함되어 있어 git 에 안 들어감.
- 키 자체는 사내망 + private repo + 1인 데모 단계라 critical 위협 아님 (HANDOFF.md 의 평가 참고).

### 3.2 `app/build.gradle.kts` — BuildConfig 로 키 노출 (~6줄)

`android { }` 블록 안에:

```kotlin
buildFeatures {
    buildConfig = true
}
```

`defaultConfig { }` 블록 안에:

```kotlin
val props = java.util.Properties().apply {
    load(rootProject.file("local.properties").inputStream())
}
buildConfigField(
    "String",
    "AD_SERVER_API_KEY",
    "\"${props.getProperty("AD_SERVER_API_KEY") ?: ""}\"",
)
```

→ 컴파일 시 `BuildConfig.AD_SERVER_API_KEY` 로 코드에서 접근 가능.

### 3.3 `Config.kt` — BASE_URL 갱신 + 키 노출 (2줄)

```kotlin
package com.k3i.adclient.net

import com.k3i.adclient.BuildConfig   // ← 추가

object Config {
    // 회사 서버 (k3ilab, 사내 wifi 에서만 도달 가능)
    const val BASE_URL = "http://211.194.140.28:58000"   // ← 변경

    val API_KEY: String = BuildConfig.AD_SERVER_API_KEY   // ← 추가

    const val READ_TIMEOUT_SEC = 300L
    const val CONNECT_TIMEOUT_SEC = 10L
}
```

### 3.4 `AdApi.kt` — 헤더 한 줄 추가

`process()` 안의 `Request.Builder()` 체이닝에 한 줄:

```kotlin
val req = Request.Builder()
    .url("${Config.BASE_URL}/process")
    .addHeader("X-API-Key", Config.API_KEY)   // ← 추가
    .post(body)
    .build()
```

(선택) `health()` 에도 동일하게 헤더 추가 — 단 서버 `/health` 가 인증 면제(HANDOFF.md §3 참고)라 안 해도 됨.

## 4. 에러 처리

기존 `sealed class Result.Err(code, message)` 그대로 활용 — 추가 코드 없음.

가능한 시나리오:
- `200` → `Result.Ok(gifBytes)` → Glide 로 표시
- `401 Unauthorized` → 키 잘못. statusText: "API 키 인증 실패 (401)"
- `422 Unprocessable Entity` → 그림에서 사람 형체 인식 실패 (AD 모델 한계). statusText: "그림에서 캐릭터를 못 찾았어요 — 사람 형체가 더 명확한 그림으로 시도"
- `500/503` → 서버 내부 에러. statusText: "서버 에러 (%d) — 잠시 후 다시"
- `-1` (네트워크) → "네트워크 오류: 사내 wifi 연결 확인"

→ MainActivity 의 `Result.Err` 분기에 짧은 `when (result.code)` 으로 친근한 메시지. ~10줄 추가.

## 5. 배포 흐름 (사용자가 Windows Android Studio 에서)

1. `local.properties` 에 키 한 줄 추가
2. Android Studio → File → Sync Project with Gradle Files
3. 갤탭 USB 연결 (개발자 모드 + USB 디버깅 ON. 메모리 검증: SM-X610)
4. Run → ad-client APK 자동 설치
5. **사내 wifi 연결된 갤탭**에서 앱 실행 → 그림 선택 → motion → 합치기

## 6. 검증 시나리오

| # | 입력 | 기대 출력 | 검증 포인트 |
|---|---|---|---|
| 1 | garlic.png (또는 갤러리의 단순 그림) + `dab` | HTTP 200, GIF 표시, ~17~40초 | end-to-end 동작 |
| 2 | 다른 motion (`wave_hello`, `dance_1` 등) | 위 동일, motion 적용 다른 GIF | 11개 motion 정상 라우팅 |
| 3 | 일부러 잘못된 키 (local.properties 임시 수정) | 401, 메시지 표시 | 보안 헤더 동작 |
| 4 | (선택) 그림 아닌 풍경 사진 | 422 + 친근 메시지 | 에러 분기 |

## 7. 10월 production 까지 남은 것 (이번 sprint 밖)

이 design 이 1차 데모만 다룸. 다음 sprint 후보:

- **HTTPS + 도메인** — `https://api.k3i.co.kr` 같은 형태. Cloudflare Tunnel 또는 회사 nginx + Let's Encrypt. `usesCleartextTraffic=true` 제거.
- **부하 측정** — `wrk`/`hey` 로 동시접속 5~20명 latency / 503 / GPU 메모리. HANDOFF.md §6.
- **유니티 C# 포팅 가이드** — 유니티 개발자가 받아서 짤 수 있게 API 사용 패턴 문서화. 우리 `AdApi.kt` 가 reference.
- **ad-client git repo 화** — 지금 git 관리 X. production 가기 전 git init + GitHub repo + push 필요. 그 시점에 **이 spec md 의 §3.1 평문 키 라인을 `AD_SERVER_API_KEY=<from .env>` 같은 placeholder 로 교체** (실제 키는 `local.properties` 만 — Android Studio 기본 .gitignore 가 보호).

## 8. 위험

| 위험 | 영향 | 완화 |
|---|---|---|
| 사내 wifi 가 211.194.140.28:58000 으로 도달 안 됨 | 모든 호출 실패 | 갤탭 wifi `K3I_xxx` 같은 사내 SSID 인지 확인. PC 에서 `curl http://211.194.140.28:58000/health` 가 되는 wifi 와 동일해야 |
| BuildConfig 생성 안 됨 | 컴파일 에러 | Sync 후 `app/build/generated/source/buildConfig/.../BuildConfig.java` 생성 확인. import 못 찾으면 Rebuild Project |
| local.properties 의 키 따옴표 처리 | 컴파일 시 키가 빈 문자열 | `buildConfigField` 의 `"\"${...}\""` 이중 따옴표 정확히 유지 |
| 갤탭 SM-X610 의 Android 버전이 OkHttp 5 호환 안 됨 | 빌드/실행 에러 | 기존 코드가 이미 동작하던 상태라 호환 입증됨 (메모리: 5-19 Android 데모 완료). 추가 검증 불필요 |
