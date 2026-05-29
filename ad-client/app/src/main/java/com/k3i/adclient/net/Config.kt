package com.k3i.adclient.net

import com.k3i.adclient.BuildConfig

/**
 * 통신 관련 상수 모음.
 *
 * BASE_URL 한 줄만 바꾸면 PC 개발 서버 ↔ 회사 서버 swap.
 * API_KEY 는 local.properties 의 AD_SERVER_API_KEY → BuildConfig 로 주입.
 */
object Config {
    // 회사 서버 (k3ilab, 사내 wifi 에서만 도달). 외부 노출 포트 = 58000.
    // 상품화 시 "https://api.k3i.co.kr" 같은 도메인 + HTTPS.
    const val BASE_URL = "http://211.194.140.28:58000"

    // ad-server X-API-Key 헤더 값. local.properties 누락 시 빈 문자열 → 서버 401.
    val API_KEY: String = BuildConfig.AD_SERVER_API_KEY

    // AD 처리 최대 5분 — read timeout 300초.
    const val READ_TIMEOUT_SEC = 300L
    const val CONNECT_TIMEOUT_SEC = 10L
}
