# ad-client Server Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ad-client (갤탭 Android 앱) 의 BASE_URL 을 회사 서버(`http://211.194.140.28:58000`) 로 바꾸고 X-API-Key 헤더 인증 적용 → 갤탭에서 실제 GIF 받기 1회 성공.

**Architecture:** 기존 코드 90% 유지. 변경 = (1) `local.properties` 에 API 키 1줄, (2) `app/build.gradle.kts` 에 BuildConfig 설정 ~6줄, (3) `Config.kt` 의 BASE_URL/API_KEY 2줄, (4) `AdApi.kt` 의 헤더 1줄. 총 ~10줄 + 빌드 설정 ~6줄.

**Tech Stack:** Kotlin / Android Studio / OkHttp / Gradle Kotlin DSL / Glide (GIF)

**선행 문서:** [`../specs/2026-05-27-server-integration-design.md`](../specs/2026-05-27-server-integration-design.md)

---

## File Structure

```
ad-client/
├── local.properties              ← Task 1 (1줄 추가, git 미추적)
├── app/
│   ├── build.gradle.kts          ← Task 2 (~6줄 추가)
│   └── src/main/java/com/k3i/adclient/
│       ├── net/
│       │   ├── Config.kt         ← Task 3 (2줄 변경/추가)
│       │   └── AdApi.kt          ← Task 4 (1줄 추가)
│       └── MainActivity.kt       ← Task 5 (선택, 에러 메시지 보강)
└── docs/superpowers/             ← spec + plan (이 문서)
```

**테스트 전략:** Android 단위 테스트 비용 > 가치 (실 통신은 갤탭+사내 wifi 필요). 검증 = **Android Studio Gradle Sync + 컴파일 + 갤탭 실기기 실행** (manual integration test). 각 task 의 검증은 "build 성공" + 마지막 통합 검증은 갤탭 시연 (Task 6).

**Git 정책:** ad-client 는 현재 git 미관리. 이번 plan 에선 commit 단계 없음. spec §7 에 따라 git init 은 별도 sprint.

---

## Task 1: `local.properties` 에 API 키 추가

API 키를 코드 밖에 두고 BuildConfig 로 전달하는 표준 Android 패턴. Android Studio 가 만든 `local.properties` 는 기본 .gitignore 처리됨.

**Files:**
- Modify: `local.properties` (1줄 추가)

- [ ] **Step 1: 현재 local.properties 확인**

Run:
```bash
cat /mnt/c/Users/leesa/AR_book/ad-client/local.properties
```

기존 내용은 보통 `sdk.dir=...` 한 줄만 있음.

- [ ] **Step 2: API 키 1줄 추가**

`local.properties` 파일 끝에 한 줄 추가:

```properties
AD_SERVER_API_KEY=7e350c43d6346a7410662ca44f0527cc85dcf26f4cb890ba956f9598bb6fcad1
```

> 키 값은 회사 서버의 `.env` 에 있는 현재 키. 변경되면 이 줄도 갱신.

- [ ] **Step 3: 추가 확인**

Run:
```bash
grep AD_SERVER_API_KEY /mnt/c/Users/leesa/AR_book/ad-client/local.properties
```

Expected: `AD_SERVER_API_KEY=7e350c43...` 가 출력됨.

- [ ] **Step 4: .gitignore 자동 적용 확인 (참고)**

ad-client 가 아직 git 미관리지만, 향후 `git init` 시 Android Studio 의 기본 `.gitignore` 가 `local.properties` 를 자동 제외하는지 확인:

Run:
```bash
grep -q "local.properties" /mnt/c/Users/leesa/AR_book/ad-client/.gitignore && echo "OK: local.properties is gitignored" || echo "⚠️ local.properties NOT in .gitignore — 추가 필요"
```

Expected: `OK: local.properties is gitignored` (Android Studio 기본 .gitignore 에 포함됨).

만약 `⚠️` 가 떴으면 `.gitignore` 에 `local.properties` 한 줄 추가.

---

## Task 2: `app/build.gradle.kts` 에 BuildConfig 설정

local.properties 의 키를 컴파일 타임에 `BuildConfig.AD_SERVER_API_KEY` 상수로 노출. Android Gradle Plugin 의 `buildConfigField` 사용.

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 현재 build.gradle.kts 의 `android { }` 블록 위치 확인**

Run:
```bash
grep -n "android {" /mnt/c/Users/leesa/AR_book/ad-client/app/build.gradle.kts
grep -n "defaultConfig {" /mnt/c/Users/leesa/AR_book/ad-client/app/build.gradle.kts
grep -n "buildFeatures" /mnt/c/Users/leesa/AR_book/ad-client/app/build.gradle.kts
```

Expected: `android { ... defaultConfig { ... } }` 구조 확인. `buildFeatures` 가 이미 있으면 그 블록에 한 줄만 추가, 없으면 새로 만듦.

- [ ] **Step 2: 파일 상단 import 영역에 `java.util.Properties` 사용 안내**

Kotlin DSL 에서는 `java.util.Properties` 를 inline 으로 쓸 수 있어 별도 import 불필요. 그대로 Step 3 진행.

- [ ] **Step 3: `android { ... }` 블록에 `buildFeatures { buildConfig = true }` 추가**

`android { }` 블록 안 (다른 sub-block 들과 같은 레벨) 에 추가:

```kotlin
android {
    // ... 기존 내용 ...

    buildFeatures {
        buildConfig = true
    }

    // ... 기존 내용 ...
}
```

이미 `buildFeatures { }` 가 있으면 그 안에 `buildConfig = true` 한 줄만 추가.

- [ ] **Step 4: `defaultConfig { }` 블록 안에 `buildConfigField` 추가**

`defaultConfig { }` 블록 안 (보통 `applicationId`, `minSdk` 같은 게 있는 곳) 마지막에 추가:

```kotlin
defaultConfig {
    // ... 기존 applicationId/minSdk/targetSdk/etc ...

    // local.properties 의 AD_SERVER_API_KEY 를 BuildConfig 로 노출.
    // 키 없으면 빈 문자열 → 서버에서 401 (UI 에서 친근 메시지로 처리).
    val props = java.util.Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(f.inputStream())
    }
    buildConfigField(
        "String",
        "AD_SERVER_API_KEY",
        "\"${props.getProperty("AD_SERVER_API_KEY") ?: ""}\"",
    )
}
```

> 이중 따옴표 escape 정확히 — `"\"${...}\""` 형태. BuildConfig 의 String 필드는 컴파일 시 그대로 코드에 박히므로 따옴표를 값에 포함시켜야 함.

- [ ] **Step 5: Android Studio 에서 Gradle Sync 실행 (사용자 직접)**

Android Studio:
- File → Sync Project with Gradle Files
- 또는 상단의 코끼리 아이콘 클릭

Expected:
- Sync 성공 (하단 빌드 창에 `BUILD SUCCESSFUL` 또는 `Sync finished`)
- 에러 시: 보통 따옴표 escape 실수. Step 4 의 코드 다시 확인.

- [ ] **Step 6: BuildConfig 생성 확인**

Sync 끝나면 `app/build/generated/source/buildConfig/.../BuildConfig.java` (또는 `.kt`) 가 생성됨:

Run:
```bash
find /mnt/c/Users/leesa/AR_book/ad-client/app/build/generated -name "BuildConfig.*" 2>/dev/null | head -3
```

Expected: 경로 1개 이상 출력.

Run:
```bash
grep "AD_SERVER_API_KEY" $(find /mnt/c/Users/leesa/AR_book/ad-client/app/build/generated -name "BuildConfig.*" 2>/dev/null | head -1)
```

Expected: `public static final String AD_SERVER_API_KEY = "7e350c43...";` 가 출력됨.

만약 안 떴으면 → Sync 가 진짜 끝났는지 확인, 또는 Step 4 의 코드 위치/문법 재점검.

---

## Task 3: `Config.kt` 갱신 — BASE_URL 변경 + API_KEY 추가

**Files:**
- Modify: `app/src/main/java/com/k3i/adclient/net/Config.kt`

- [ ] **Step 1: 현재 Config.kt 확인**

Run:
```bash
cat /mnt/c/Users/leesa/AR_book/ad-client/app/src/main/java/com/k3i/adclient/net/Config.kt
```

기존:
```kotlin
const val BASE_URL = "http://192.168.68.176:8000"
```

- [ ] **Step 2: 파일 전체를 아래로 교체**

```kotlin
package com.k3i.adclient.net

import com.k3i.adclient.BuildConfig

/**
 * 통신 관련 상수 모음.
 *
 * BASE_URL 한 줄만 바꾸면 PC 개발 서버 ↔ 회사 서버 swap.
 * API_KEY 는 local.properties 의 AD_SERVER_API_KEY 에서 BuildConfig 로 주입.
 */
object Config {
    // 회사 서버 (k3ilab, 사내 wifi 에서만 도달). 포트 58000 (8000 아님 — 외부 노출 포트).
    // 상품화 시 "https://api.k3i.co.kr" 같은 도메인 + HTTPS.
    const val BASE_URL = "http://211.194.140.28:58000"

    // ad-server X-API-Key 헤더 값. local.properties 에 AD_SERVER_API_KEY 가 없으면 빈 문자열.
    val API_KEY: String = BuildConfig.AD_SERVER_API_KEY

    // AD 처리 최대 5분 — read timeout 300초.
    const val READ_TIMEOUT_SEC = 300L
    const val CONNECT_TIMEOUT_SEC = 10L
}
```

> 변경점 3가지:
> 1. `import com.k3i.adclient.BuildConfig` 추가
> 2. `BASE_URL` 값 변경 (`192.168.68.176:8000` → `211.194.140.28:58000`)
> 3. `API_KEY` 한 줄 추가

- [ ] **Step 3: 컴파일 확인 (Android Studio Build 메뉴)**

Android Studio:
- Build → Make Project (`Ctrl+F9`)

Expected:
- `BUILD SUCCESSFUL`
- 만약 `unresolved reference: BuildConfig` 에러 → Task 2 의 `buildConfig = true` 가 빠졌거나 Sync 가 안 끝남. Task 2 Step 5~6 재확인.

---

## Task 4: `AdApi.kt` 의 `process()` 에 X-API-Key 헤더 추가

**Files:**
- Modify: `app/src/main/java/com/k3i/adclient/net/AdApi.kt`

- [ ] **Step 1: 현재 `process()` 의 Request.Builder 부분 확인**

Run:
```bash
sed -n '70,75p' /mnt/c/Users/leesa/AR_book/ad-client/app/src/main/java/com/k3i/adclient/net/AdApi.kt
```

Expected:
```kotlin
val req = Request.Builder()
    .url("${Config.BASE_URL}/process")
    .post(body)
    .build()
```

- [ ] **Step 2: `.addHeader("X-API-Key", Config.API_KEY)` 한 줄 추가**

해당 4줄을:

```kotlin
val req = Request.Builder()
    .url("${Config.BASE_URL}/process")
    .addHeader("X-API-Key", Config.API_KEY)
    .post(body)
    .build()
```

> `.url(...)` 다음에 `.addHeader(...)` 한 줄 끼우는 식. `.post(body)` 와 `.build()` 는 그대로.

- [ ] **Step 3: (선택) `health()` 함수에도 동일하게 추가할지 결정**

현재 `health()`:
```kotlin
val req = Request.Builder().url("${Config.BASE_URL}/health").build()
```

서버 `/health` 는 인증 면제이므로 헤더 안 넣어도 됨 (spec §3.4). 다만 일관성 위해 추가하고 싶으면:

```kotlin
val req = Request.Builder()
    .url("${Config.BASE_URL}/health")
    .addHeader("X-API-Key", Config.API_KEY)
    .build()
```

→ 이 step 은 skip 가능. 안 넣어도 동작에 영향 없음.

- [ ] **Step 4: Make Project 로 컴파일 확인**

Android Studio: Build → Make Project

Expected: `BUILD SUCCESSFUL`. 에러 시 Task 3 의 `Config.API_KEY` 가 제대로 정의됐는지 재확인.

---

## Task 5 (선택): 에러 메시지 친근하게 보강

spec §4 의 401/422/500/-1 분기 메시지. 현재 MainActivity 는 `"에러 ${result.code}: ${result.message}"` 그대로 출력 — 그래도 동작은 하지만, 1차 데모/시연 톤에 맞게 친근하게.

> ⏱️ 시간 남으면. 핵심 시나리오(Task 1~4 + Task 6 시연) 가 우선.

**Files:**
- Modify: `app/src/main/java/com/k3i/adclient/MainActivity.kt`

- [ ] **Step 1: 현재 `Result.Err` 분기 확인**

Run:
```bash
sed -n '121,124p' /mnt/c/Users/leesa/AR_book/ad-client/app/src/main/java/com/k3i/adclient/MainActivity.kt
```

Expected:
```kotlin
is AdApi.Result.Err -> {
    statusText.text = "에러 ${result.code}: ${result.message}"
}
```

- [ ] **Step 2: 분기별 친근 메시지로 교체**

해당 3줄을:

```kotlin
is AdApi.Result.Err -> {
    statusText.text = when (result.code) {
        401 -> "인증 실패 (401) — API 키가 잘못됐어요. local.properties 확인."
        422 -> "그림에서 캐릭터를 못 찾았어요 — 사람 형체가 더 명확한 그림으로 시도."
        500, 503 -> "서버 에러 (${result.code}) — 잠시 후 다시."
        -1 -> "네트워크 오류 — 사내 wifi 연결 확인."
        else -> "에러 ${result.code}: ${result.message}"
    }
}
```

- [ ] **Step 3: Make Project 로 컴파일 확인**

Android Studio: Build → Make Project

Expected: `BUILD SUCCESSFUL`.

---

## Task 6: 갤탭에 빌드 + 설치 + 시연 (사용자 직접 — Android Studio)

> 이 task 는 **사용자가 Android Studio GUI 에서 직접 진행**. Claude 가 자동 수행 불가.

**Files:** 없음 (검증 단계)

- [ ] **Step 1: 갤탭 USB 연결 + 개발자 옵션 확인**

- 갤탭 (SM-X610 등) USB 케이블로 PC 연결
- 갤탭 화면: 설정 → 휴대전화 정보 → 빌드 번호 7번 탭 → 개발자 옵션 활성화
- 개발자 옵션 → **USB 디버깅 ON**
- 첫 연결 시 "이 컴퓨터에서 USB 디버깅 허용" 팝업 → "허용"

Expected: Android Studio 우상단 디바이스 선택기에 갤탭 이름 (예: `SM-X610`) 표시.

- [ ] **Step 2: 갤탭이 회사 사내 wifi 에 연결됐는지 확인**

갤탭 설정 → wifi → 회사 SSID (`K3I_...` 같은) 에 연결.

> 외부 wifi 면 211.194.140.28:58000 에 도달 안 됨. spec §8 위험.

- [ ] **Step 3: Android Studio 에서 Run**

- 상단 메뉴: Run → Run 'app' (`Shift+F10`)
- 또는 녹색 ▶ 버튼

Expected:
- Build 진행 → "Installing APK..." → 갤탭에 ad-client 자동 설치 + 실행
- 갤탭 화면에 앱 켜짐 (이미지 picker 버튼 + motion Spinner + 합치기 버튼 UI)

- [ ] **Step 4: 시나리오 1 — 핵심 검증 (garlic + dab)**

갤탭 앱에서:
1. "이미지 선택" 버튼 → 갤러리에서 단순한 캐릭터 그림 선택 (예: garlic 같은 손그림)
2. Spinner 에서 `dab` 선택
3. "합치기" 버튼 누름
4. statusText 가 "처리 중..." 같은 메시지 → 17~40초 대기
5. 완료 후 GIF 가 화면에 표시되고 statusText 에 `완료 · XX.Xs · XXX KB`

Expected: HTTP 200, GIF 표시, 시간 ~20~40초 (네트워크 왕복 + 모델 cold start 영향).

- [ ] **Step 5: 시나리오 2 — 다른 motion (확장 검증)**

같은 그림 + 다른 motion (`wave_hello`, `dance_1`, `my_dance` 중 하나) → 합치기 → GIF 가 그 motion 으로 움직이는지.

Expected: 정상 GIF, motion 시각적으로 다름.

- [ ] **Step 6 (선택): 시나리오 3 — 401 에러 (보안 헤더 동작)**

> Task 5 의 친근 메시지를 검증하려면 이걸 한 번 해 보면 좋음. 시간 없으면 skip.

- 갤탭 앱 닫기
- `local.properties` 의 키를 일부러 1글자 잘못된 값으로 변경 (예: 끝에 `X` 한 글자 추가)
- Android Studio: Run 다시 (앱 재설치)
- 갤탭에서 합치기 시도

Expected: statusText 에 `인증 실패 (401) — API 키가 잘못됐어요. ...` (Task 5 메시지). HTTP 401 응답.

확인 후 `local.properties` 의 키를 원래대로 복구하고 Run 다시.

- [ ] **Step 7: 결과 기록**

검증 끝나면 ~/devlog/ 의 오늘 일지 (`2026-05-27.md`) 에 결과 적기:
- 시나리오 1: ✅/❌ + 시간/KB
- 시나리오 2: ✅/❌ + 어떤 motion
- 시나리오 3: ✅/❌ (선택)
- 발견된 문제 / 다음 sprint 안건

---

## 완료 기준

- [ ] Task 1~4 + Task 6 의 Step 1~5 까지 통과
- [ ] 갤탭에서 GIF 1회 받기 성공 (시나리오 1)
- [ ] (선택) Task 5 + Task 6 Step 6 으로 보안 헤더 동작 확인

여기까지 = ad-client 회사 서버 연동 1차 데모 완료. spec §1 의 성공 기준 충족.

---

## 다음 sprint 후보 (이 plan 밖)

spec §7 에 정리됨. 우선순위 안건:
1. HTTPS + 도메인 (Cloudflare Tunnel 또는 nginx)
2. 부하 측정 (`wrk`/`hey`)
3. 유니티 C# 포팅 가이드 (10월 production 준비)
4. ad-client git repo 화 (production 전 필수)
