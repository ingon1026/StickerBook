package com.k3i.adclient.net

/**
 * 통신 관련 상수 모음.
 *
 * BASE_URL 한 줄만 바꾸면 PC 개발 서버 ↔ 회사 서버 swap.
 * 코드 다른 곳 영향 X. 이것이 endpoint 추상화의 실전 가치.
 */
object Config {
    // ⚠️ PC 의 LAN IP 로 교체 필요. Windows cmd 에서 `ipconfig` 의
    //    Wireless LAN adapter Wi-Fi 의 IPv4 Address.
    //    상품화 시 "https://api.k3i.co.kr" 같은 도메인으로 교체.
    const val BASE_URL = "http://192.168.68.176:8000"

    // AD 처리 최대 5분 — read timeout 300초.
    const val READ_TIMEOUT_SEC = 300L
    const val CONNECT_TIMEOUT_SEC = 10L
}
