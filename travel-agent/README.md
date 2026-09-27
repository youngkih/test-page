# 👶✈️ 가족 여행 에이전트 (Travel Agent)

14개월 아기와 함께하는 가족 여행을 **조사 → 가격 비교 → 식당/놀이/쇼핑 추천 → 시간대별 일정표**까지 도와주는 AI 에이전트입니다.
Java 21 + Spring Boot 3.5 + [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java)(Claude API)로 만들었습니다.

## 빠른 시작

### 1. Claude API 키 발급
1. <https://platform.claude.com> 에 가입하고 결제 수단을 등록합니다.
2. **API Keys** 메뉴에서 키를 만듭니다 (`sk-ant-...`).

### 2. 실행
```bash
cd travel-agent
export ANTHROPIC_API_KEY=sk-ant-...   # Windows PowerShell: $env:ANTHROPIC_API_KEY="sk-ant-..."
mvn spring-boot:run
```
브라우저에서 <http://localhost:8080> 을 엽니다. 휴대폰에서도 쓰려면 같은 와이파이에서 `http://<내 PC IP>:8080` 으로 접속하세요.

### 3. 이렇게 말해 보세요
> 10월 둘째 주에 3박 4일로 일본 가고 싶어. 예산은 250만 원 정도야.

에이전트가 먼저 **질문**(출발 공항, 숙소 스타일, 아이 낮잠 시간 등)을 하고, 답을 받은 뒤 조사를 시작합니다.
오른쪽 패널에 **일정표 / 가격 비교표 / 진행 기록**이 채워집니다.

## 요구사항 반영 방식

| 요구사항 (Travel_agent.md) | 구현 |
|---|---|
| 계획 전에 모호한 점 질문 | 시스템 프롬프트 1단계 "질문하기" (선택지와 기본값을 함께 제시) |
| 여행지 없으면 직접 조사 | 아이 동반 기준(비행시간·시차·위생·유모차)으로 후보 2~3곳 제안 |
| 여러 사이트 가격 비교 | `web_search`/`web_fetch`로 조사 → `save_price_comparison` 도구가 **코드로** 최저가 계산 |
| 예약 방법 | `booking_search_links` 도구가 스카이스캐너·네이버항공·아고다·부킹닷컴·호텔스닷컴·익스피디아·에어비앤비·트립닷컴·클룩 등 **날짜/인원이 채워진 링크** 생성 |
| 매일 1~2시간 아이 놀이 | `save_itinerary` 도구가 하루 `kids_play` 합계 60분 미만이면 경고 → 모델이 스스로 수정 |
| 수유실 있는 쇼핑몰 + 동네 가게 | 프롬프트 지침 (赤ちゃん休憩室, 授乳室 등 현지어 검색어 포함) |
| 유아의자·금연·웨이팅 적은 식당, 구글 4.0+ / 리뷰 100+ | 프롬프트 지침 + 현지어 검색어 (キッズチェア, 禁煙 등) |
| 구글 지도 위치 | `google_maps_links` 도구 (공식 Maps URL 형식, API 키 불필요). 식당에 링크가 없으면 일정표 저장 시 경고 |
| 휴무일 방문 방지 | `trip_calendar` 도구가 요일·일본 공휴일을 **코드로** 계산 → 모델이 定休日과 대조. 휴무 미확인 시 경고 |
| 시간대별 일정표 | `save_itinerary` → 오른쪽 "일정표" 탭 |

> **왜 요일·최저가 계산을 코드로 하나요?** LLM은 "2026-10-14가 무슨 요일인지", "598,000 < 620,000" 같은 계산을 가끔 틀립니다.
> 틀리면 안 되는 부분은 도구(코드)로 빼고, 모델은 판단·조사·글쓰기에 집중시키는 것이 에이전트 설계의 핵심 패턴입니다.

## 구조

```
travel-agent/
├── src/main/java/com/example/travelagent/
│   ├── agent/
│   │   ├── TravelAgentService.java   ★ 에이전트 루프 (여기부터 읽으세요)
│   │   ├── StreamForwarder.java      스트리밍 이벤트 → 화면 이벤트(텍스트, "웹 검색 중...")
│   │   ├── ClaudeGateway.java        Claude 호출 인터페이스 (테스트에서 가짜로 교체)
│   │   ├── AnthropicClaudeGateway.java
│   │   ├── Conversation(Store).java  대화 기록 (메모리 저장)
│   │   └── SystemPromptFactory.java  프롬프트 템플릿에 가족 프로필·오늘 날짜 주입
│   ├── tool/                         우리 서버가 실행하는 도구들 (AgentTool 구현체)
│   ├── web/                          REST + SSE 컨트롤러
│   └── config/                       설정 (application.yml ↔ AgentProperties)
├── src/main/resources/
│   ├── prompts/system-prompt.md      ★ 에이전트 성격·규칙. 여기를 고치면 행동이 바뀝니다
│   ├── application.yml               모델, 비용 한도, 가족 프로필
│   └── static/                       웹 채팅 UI (index.html, app.js, style.css)
└── src/test/                         단위 테스트 (API 키 없이 실행)
```

### 에이전트 루프가 동작하는 방식

```
사용자 메시지
   └▶ Claude 호출 (스트리밍) ──▶ stop_reason?
          ├ tool_use   → 우리 도구 실행 → 결과를 대화에 추가 → 다시 호출
          ├ pause_turn → (웹 검색을 오래 해서 서버가 잠시 멈춤) 그대로 다시 호출
          └ end_turn   → 완료
```

- **서버 도구** (`web_search`, `web_fetch`): Anthropic 서버가 대신 실행합니다. 우리 코드엔 실행 로직이 없습니다.
- **클라이언트 도구** (`tool/` 패키지): 모델이 "이 도구를 이 입력으로 불러줘"라고 요청하면 우리 서버가 실행하고 결과를 돌려줍니다.
- Claude API는 상태가 없어서(stateless) 매번 **전체 대화 기록**을 보냅니다. 비용을 줄이려고 **프롬프트 캐싱**을 켰습니다 (반복되는 앞부분은 약 10% 가격).

### 새 도구 추가하기
`AgentTool`을 구현하고 `@Component`만 붙이면 자동 등록됩니다 (Spring이 `List<AgentTool>`로 주입).
예: 환율 조회, 날씨 예보, 사내 캘린더 연동 등.

## 설정 (`application.yml`)

| 키 | 기본값 | 설명 |
|---|---|---|
| `travel-agent.model` | `claude-opus-5` | 사용할 모델. 비용을 줄이려면 `claude-sonnet-5` |
| `travel-agent.effort` | `high` | 생각 깊이 `low`~`max`. 낮출수록 싸고 빠름 |
| `travel-agent.max-web-searches` | `15` | 한 번 호출당 웹 검색 최대 횟수 |
| `travel-agent.max-web-fetches` | `20` | 한 번 호출당 웹 페이지 읽기 최대 횟수 |
| `travel-agent.max-agent-iterations` | `25` | 메시지 1건당 도구↔모델 왕복 최대 횟수 |
| `travel-agent.family.*` | 아빠·엄마·14개월 아들 | 가족 프로필. 아이가 자라면 여기만 수정 |

## 비용

- `claude-opus-5`: 입력 100만 토큰당 $5, 출력 100만 토큰당 $25. 웹 검색은 1,000회당 $10 별도.
- 웹 페이지를 많이 읽기 때문에 **여행 1건을 끝까지 계획하는 데 대략 몇 달러** 정도 예상됩니다 (조사량에 따라 달라짐).
  <https://platform.claude.com> 의 Usage 메뉴에서 실제 사용량을 확인하고, 필요하면 월 사용 한도를 걸어 두세요.

## 한계 (꼭 읽어 주세요)

- **예약·결제는 하지 않습니다.** 대부분의 예약 사이트는 개인 개발자에게 예약 API를 주지 않습니다. 에이전트는 날짜·인원이 채워진 검색 링크와 예약 순서를 안내하고, 최종 예약은 사람이 합니다.
- **가격은 조사 시점의 참고값입니다.** 웹에 공개된 정보 기준이라 실제 결제 가격과 다를 수 있습니다. 비교표의 링크에서 꼭 다시 확인하세요.
- **구글 평점·리뷰 수는 웹 검색으로 확인한 값**입니다. 더 정확하게 하려면 Google Places API 도구를 추가하면 됩니다 (아래 "다음 단계").
- 예약 사이트 링크 형식은 사이트 사정에 따라 바뀔 수 있습니다. 깨지면 `BookingLinksTool.java`만 고치면 됩니다.
- 대화는 서버 메모리에만 저장됩니다. 서버를 재시작하면 사라집니다.
- `TripCalendarTool`의 일본 공휴일 데이터는 2026년 1월 ~ 2027년 2월분입니다. 그 밖의 기간은 에이전트가 웹 검색으로 확인합니다.

## 테스트

```bash
mvn test
```
API 키 없이 실행됩니다. `TravelAgentServiceTest`는 가짜 Claude(`FakeGateway`)로 에이전트 루프(도구 실행, pause_turn, 오류 처리)를 검증합니다.

## 다음 단계 아이디어 (공부용)

1. **Google Places API 도구**: 평점·리뷰 수·영업시간·`goodForChildren` 필드를 정확히 조회 (키 필요)
2. **대화 영속화**: `ConversationStore`를 Spring Data JPA 또는 Redis 구현으로 교체
3. **항공권 가격 API**: SerpApi(Google Flights) 같은 유료 API를 도구로 추가하면 웹 검색보다 정확
4. **평가(eval)**: 샘플 요청 10개를 만들어 "일정표 경고 0개", "식당 모두 지도 링크 있음" 같은 기준으로 자동 채점
