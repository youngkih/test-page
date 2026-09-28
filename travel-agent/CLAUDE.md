# travel-agent 작업 맥락 (Claude 세션 인계용)

이 파일은 이 폴더에서 Claude Code 를 실행하면 자동으로 읽힌다. 이전 클라우드 세션에서 한 작업과 남은 일을 정리했다.

## 사용자
- 서울 거주 한국인. 아내, 14개월 아들과 함께 산다. Java/Spring 백엔드 개발자이고 시니어 개발자를 목표로 공부 중이다.
- AI 활용에 익숙해지는 중이라 설명은 한국어로, 쉽고 단계별로 한다.
- 원래 요구사항: 계획 전에 질문하기, 여러 예약 사이트 가격 비교, 매일 아이 놀이 1~2시간, 유아의자·금연·웨이팅 적은 식당(구글 4.0+ / 리뷰 100+), 구글 지도 링크, 휴무일 피하기, 시간대별 일정표.

## 프로젝트
- Java 21 + Spring Boot 3.5 + Anthropic Java SDK. 모델 `claude-opus-5`, adaptive thinking, 스트리밍, 서버 도구 `web_search_20260209` / `web_fetch_20260209`.
- 에이전트 루프: `agent/TravelAgentService.java` (tool_use / pause_turn 처리, 대화 기록은 append-only, 프롬프트 캐싱).
- 자체 도구 (`tool/`): `booking_search_links`, `google_maps_links`, `trip_calendar`, `save_price_comparison`, `save_itinerary`. 요일·최저가처럼 틀리면 안 되는 계산은 코드로 하고, 일정표 검사(놀이 60분, 지도 링크, 휴무 확인)는 경고로 모델에 돌려준다.
- 에이전트 성격·규칙: `src/main/resources/prompts/system-prompt.md`
- 화면 출력은 `AgentEventListener` 로 분리: 웹(SSE, `web/`)과 텔레그램(`telegram/`) 두 구현이 있다.
- 텔레그램: 롱 폴링이라 포트 개방·VPN이 필요 없다. 허용된 사용자 ID 한 명의 1:1 채팅만 응답하고, 허용 ID를 정하기 전에는 설정 모드로 ID만 알려준다. 토큰은 오류 메시지에서 가린다.
- 웹 UI는 기본적으로 127.0.0.1 에만 바인딩된다 (`SERVER_ADDRESS` 로 변경).
- 코드 주석은 한국어, 테스트 메서드 이름도 한국어.

## 명령어
```bash
mvn test                       # 34개 테스트, API 키 없이 실행됨
mvn package -DskipTests        # target/travel-agent-0.1.0.jar
java -jar target/travel-agent-0.1.0.jar
```
필요한 환경 변수: `ANTHROPIC_API_KEY`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_USER_ID`. 값은 절대 파일에 커밋하지 않는다.

## 진행 상황
- PR #1 (웹 에이전트), PR #2 (텔레그램 봇): 둘 다 main 에 병합 완료.
- 실제 Claude API 와 실제 텔레그램으로는 아직 한 번도 실행해 보지 않았다 (클라우드 세션에 키가 없었음). 가짜 서버로만 검증했다.
- 봇 생성 완료: @travel_agent_telegram_bot
- ⚠️ 사용자가 이전 채팅에 봇 토큰을 붙여 넣었다. BotFather `/revoke` 로 재발급하도록 권장했다. 재발급했는지 먼저 확인할 것. 토큰은 채팅에 붙이지 말라고 안내한다.

## 남은 일 (맥미니)
1. 토큰 재발급(`/revoke`), BotFather `/setjoingroups` → Disable, `/setcommands` 로 new / plan / prices / help 등록
2. `brew install openjdk@21 maven` 후 빌드
3. `TELEGRAM_BOT_TOKEN` 만 넣고 실행 → 폰에서 봇에게 메시지 → 사용자 ID 확인 (설정 모드)
4. `TELEGRAM_ALLOWED_USER_ID` 설정 후 재시작 → 실제 대화로 동작 확인. 문제가 있으면 로그를 보고 고친다
5. README 의 launchd plist 로 상시 실행 등록 (plist 권한 600, 에너지 설정, 자동 로그인)
6. 권장: Claude 콘솔 월 사용 한도, 텔레그램 2단계 인증, FileVault

## 다음 아이디어
Google Places API 도구(평점·영업시간 정확도), 대화 영속화(JPA/Redis), 항공권 가격 API, 샘플 요청 기반 평가(eval).
