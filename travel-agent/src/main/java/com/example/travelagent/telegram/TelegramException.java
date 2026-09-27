package com.example.travelagent.telegram;

/**
 * 텔레그램 API 호출 실패.
 *
 * <p>보안: 원인 예외(cause)를 일부러 담지 않는다. HTTP 클라이언트 예외 메시지에는
 * 요청 URL(= https://api.telegram.org/bot<토큰>/...)이 들어 있어 로그에 토큰이 새어 나갈 수 있기 때문이다.
 */
public class TelegramException extends RuntimeException {

    private final int statusCode;

    public TelegramException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /** HTTP 상태 코드. 네트워크 오류처럼 응답이 없으면 0. */
    public int statusCode() {
        return statusCode;
    }
}
