# STOG Backend Integration Guide v1.1

> 대상: Spring Boot 백엔드 팀
> 
> 
> 범위: **STOG Qwen 성향/대화 모델 + Recommendation Service + STOG Planner + Google Maps Platform + PostgreSQL** 연동
> 
> 기준일: **2026-08-26**
> 
> Planner 기준: **P12 + Canonical Preference 6축 정합성 수정 완료 / Cloud Run 배포 완료**
> 
> 원칙: AI와 Planner는 DB에 직접 접근하지 않는다. **Spring Boot가 모든 서비스와 데이터를 조율하는 Orchestrator**다.
> 

---

# 1. 최종 구조 한눈에 보기

```
Android
   │
   │ 사용자 메시지 / 설문 / 여행 요청
   ▼
Spring Boot
   │
   ├─────────────── 1) STOG Qwen Chat (Private Cloud Run)
   │                    │
   │                    ├─ 자연어 답변
   │                    ├─ Preference Evidence
   │                    ├─ TravelStyle Evidence
   │                    └─ TripContext delta
   │
   ├─────────────── PostgreSQL
   │                    ├─ Preference snapshot
   │                    ├─ TravelStyle snapshot
   │                    ├─ TripContext
   │                    ├─ Evidence
   │                    ├─ Trip / Cart
   │                    └─ Place
   │
   ├─────────────── 2) Recommendation Service
   │                    │
   │                    └─ 추천 후보 + final_score
   │
   ├─────────────── 3) Google Maps Platform
   │                    │
   │                    └─ directed travel time / distance / route data
   │
   └─────────────── 4) STOG Planner
                        │
                        ├─ 방문 장소 선택
                        ├─ mandatory 보존
                        ├─ optional drop
                        ├─ 방문 순서
                        ├─ 이동수단
                        ├─ 도착/대기/방문/출발 시각
                        └─ 미배치 사유

Spring Boot
   │
   ├─ 결과 DB 저장
   └─ Android에 최종 결과 전달
```

핵심 역할은 다음과 같다.

| 구성요소 | 책임 |
| --- | --- |
| Android | UI, 사용자 입력, 현재 위치 전달, 지도 표시 |
| Spring Boot | 인증, DB, 서비스 호출, 데이터 병합, 영속화 |
| STOG Qwen | 대화, Preference/TravelStyle Evidence 추출, TripContext 대화 정보 추출 |
| Recommendation Service | Preference 기반 후보 장소 및 추천 점수 계산 |
| STOG Planner | 현실 제약을 만족하는 실행 가능한 최종 일정 계산 |
| Google Maps Platform | 실제 이동시간/거리/경로 데이터 제공 |
| PostgreSQL | 모든 영속 데이터의 Source of Truth |

---

# 2. 현재 서비스 상태

## 2.1 STOG Qwen Chat

이미 배포되어 있다.

```
Service       : stog-preference-chat
Platform      : Google Cloud Run
Region        : asia-southeast1
GPU           : NVIDIA L4
Model         : Qwen3-4B-Instruct-2507 + QLoRA
Authentication: Private Cloud Run / Google ID Token
```

Base URL:

```
https://stog-preference-chat-qoeu5cmuxq-as.a.run.app
```

API:

```
GET  /health
POST /chat
```

## 2.2 STOG Planner

현재 STOG Planner는 **P12까지 구현 완료**되었고, Canonical Preference 6축 정합성 수정까지 완료된 최신 이미지가 **Private Cloud Run**에 배포되어 있다.

```
Service       : stog-planner
Platform      : Google Cloud Run
Region        : asia-northeast3
Runtime       : FastAPI + OR-Tools CP-SAT
Compute       : CPU
Authentication: Private Cloud Run / Google ID Token
Canonical     : plan_itinerary()
Compatibility : optimize_itinerary() wrapper 유지
```

Base URL:

```
https://stog-planner-qoeu5cmuxq-du.a.run.app
```

배포 이미지:

```
asia-northeast3-docker.pkg.dev/team-05-504502/stog-training/stog-planner:v1.0-canonical6
```

API:

```
GET  /health
POST /v1/itineraries/optimize
POST /v1/itineraries/alternatives
POST /v1/itineraries/replan
POST /v1/itineraries/parse-edit
```

Planner는 다음을 직접 호출하지 않는다.

```
DB             X
Recommendation X
Google Maps    X
```

모든 입력은 Spring Boot가 준비해서 Planner에 전달한다. Planner는 GPU가 필요하지 않으며 현재 CPU Cloud Run 서비스로 운영한다.

## 2.3 Canonical Preference 계약

Qwen 성향 서비스, Recommendation Service, STOG Planner가 사용하는 Preference 6축은 다음으로 통일한다.

```
nature
culture
food
shopping
experience
relaxation
```

Planner의 `TraitScores`와 `Place.traits`도 위 6축만 허용한다.

다음 legacy key는 지원하지 않는다.

```
forest
time
local
festival
archive
```

legacy Preference payload에 대한 backward alias는 제공하지 않으며, 해당 key가 Planner 요청에 포함되면 Pydantic/FastAPI validation에 의해 **HTTP 422**로 거부한다.

TravelStyle은 별도 계약이다.

```
localness
crowd_tolerance
pace
spontaneity
activity_intensity
novelty_seeking
travel_effort_tolerance
```

Preference 6축과 TravelStyle 7변수를 혼합하지 않는다.

---

# 3. 백엔드 연결 작업 순서

권장 구현 순서는 다음과 같다.

```
STEP 1  Qwen /health 연결
STEP 2  Qwen /chat 연결
STEP 3  Qwen 결과 DB 저장
STEP 4  설문 Profile 저장/조회
STEP 5  Recommendation Service 연결
STEP 6  Google Maps 이동 데이터 수집
STEP 7  Planner 입력 생성
STEP 8  STOG Planner 호출
STEP 9  Planner 결과 DB 저장
STEP 10 Android 최종 응답 연결
```

한 번에 전체를 연결하지 말고 이 순서대로 하나씩 검증한다.

---

# 4. STEP 1 — Spring Boot에서 Qwen Health Check 연결

먼저 AI 서비스가 살아 있는지만 확인한다.

## 요청

```
GET /health
Authorization: Bearer <Google ID Token>
```

## 정상 응답

```json
{
  "status": "ok",
  "model_loaded": true
}
```

`model_loaded=true`이면 Qwen + LoRA inference 준비가 끝난 상태다.

---

# 5. STEP 2 — Spring Boot에서 Qwen `/chat` 연결

## 요청

```
POST /chat
Authorization: Bearer <Google ID Token>
Content-Type: application/json
```

```json
{
  "session_id": "trip-100-user-42",
  "message": "오늘 가족이랑 같이 다니고 있어."
}
```

## 응답

```json
{
  "session_id": "trip-100-user-42",
  "message": "...",
  "user_understanding": {
    "preference_evidence": [],
    "style_evidence": [],
    "context_update": {
      "companion_type": "FAMILY"
    }
  },
  "current_trip_context": {
    "companion_type": "FAMILY"
  }
}
```

---

# 6. STEP 2-1 — `session_id` 규칙

`session_id`는 같은 대화 중 계속 유지한다.

```
Trip A / User 42

Turn 1 ─┐
Turn 2 ─┼─→ trip-100-user-42
Turn 3 ─┘
```

잘못된 방식:

```
Turn 1 → session-001
Turn 2 → session-002
Turn 3 → session-003
```

그룹 여행이더라도 AI 대화 세션은 사용자별로 분리하는 것을 권장한다.

```
Trip 100
├─ User A → session A
├─ User B → session B
└─ User C → session C
```

사용자의 Evidence가 서로 섞이면 안 되기 때문이다.

---

# 7. STEP 2-2 — Private Cloud Run 인증

Spring Boot는 일반 STOG JWT가 아니라 **Google ID Token**으로 Qwen과 Planner의 Private Cloud Run을 호출한다.

백엔드 Service Account에는 두 서비스 모두에 다음 권한이 필요하다.

```
roles/run.invoker
```

권한 부여는 **로컬 Windows가 아니라 GCP Cloud Shell에서 실행**한다.

```bash
BACKEND_SA="실제-백엔드-SA@team-05-504502.iam.gserviceaccount.com"

gcloud run services add-iam-policy-binding \
  stog-preference-chat \
  --region=asia-southeast1 \
  --member="serviceAccount:${BACKEND_SA}" \
  --role="roles/run.invoker"

gcloud run services add-iam-policy-binding \
  stog-planner \
  --region=asia-northeast3 \
  --member="serviceAccount:${BACKEND_SA}" \
  --role="roles/run.invoker"
```

환경변수:

```
STOG_AI_BASE_URL=https://stog-preference-chat-qoeu5cmuxq-as.a.run.app
STOG_PLANNER_BASE_URL=https://stog-planner-qoeu5cmuxq-du.a.run.app
```

`application.yml`:

```yaml
stog:
ai:
base-url: ${STOG_AI_BASE_URL}
planner:
base-url: ${STOG_PLANNER_BASE_URL}
```

각 Cloud Run ID Token의 audience는 **호출 대상 서비스의 Base URL**을 사용한다.

---

# 8. STEP 2-3 — Spring Boot Qwen Client 구조

권장 패키지 예시:

```
com.stog.ai
├─ StogAiClient
├─ CloudRunIdTokenProvider
└─ dto
   ├─ ChatRequest
   ├─ ChatResponse
   ├─ UserUnderstandingDto
   └─ EvidenceDto
```

백엔드 흐름:

```
Controller
   ↓
사용자 인증
   ↓
session_id 조회/생성
   ↓
StogAiClient.chat()
   ↓
Google ID Token 생성
   ↓
POST Cloud Run /chat
   ↓
ChatResponse
```

---

# 9. STEP 3 — Qwen 응답을 DB에 저장

AI 서버의 세션 메모리는 영구 저장소가 아니다.

```
Cloud Run restart
revision 변경
instance 종료
```

등이 발생하면 사라질 수 있다.

따라서:

```
AI Session = 임시 상태
PostgreSQL = Source of Truth
```

로 사용한다.

## 9.1 Preference Evidence

예:

```json
{
  "axis": "food",
  "polarity": 1.0,
  "confidence": 0.94,
  "source_message_id": "msg-123",
  "evidence_type": "EXPLICIT"
}
```

최소 저장 필드:

```
user_id
session_id
source_message_id
profile_type
axis
polarity
confidence
evidence_type
model_version
created_at
```

## 9.2 중요한 규칙

Evidence를 받았다고 바로:

```
food = 1.0
```

으로 덮어쓰지 않는다.

올바른 구조:

```
Qwen Evidence
     ↓
profile_evidence DB 저장
     ↓
Profile Aggregation
     ↓
user_preferences / user_travel_styles 갱신
```

`Evidence → 최종 점수` aggregation 공식은 별도의 정책으로 관리한다.

---

# 10. STEP 3-1 — TripContext 저장

AI 응답의 두 필드는 역할이 다르다.

## `context_update`

이번 턴에서 새로 변경된 값만 들어 있는 delta.

```json
{
  "companion_type": "FAMILY"
}
```

DB 업데이트에는 이것을 사용한다.

## `current_trip_context`

AI 세션이 현재 알고 있는 전체 snapshot.

```json
{
  "weather": "RAIN",
  "companion_type": "FAMILY"
}
```

이 값은 세션 동기화/검증 용도로 사용한다.

즉:

```
DB update      ← context_update
AI state check ← current_trip_context
```

---

# 11. STEP 3-2 — 시스템 데이터가 AI보다 우선

다음은 AI 추출값에만 의존하지 않는다.

```
날씨       → 기상 API
현재 위치   → Android / Backend
혼잡도     → 외부 API
운영시간   → DB / 외부 API
```

예:

```
사용자: "비 오는 것 같아"
AI: weather=RAIN
기상 API: CLEAR
```

실제 Planner/Recommendation에 사용할 값은 Backend의 authoritative data를 우선한다.

```
AI context
    +
System context
    ↓
Spring Boot merge
    ↓
Canonical TripContext
```

---

# 12. STEP 4 — 설문 Profile 연결

설문 점수 계산은 Qwen이 아니라 Spring Boot가 담당한다.

```
Android 설문 응답
       ↓
Spring Boot
       ├─ 1~5 검증
       ├─ 정방향 계산
       ├─ 역채점
       ├─ Preference 6축 계산
       └─ TravelStyle 7변수 계산
       ↓
PostgreSQL
```

## Preference 6축

```
nature
culture
food
shopping
experience
relaxation
```

## TravelStyle 7변수

```
localness
crowd_tolerance
pace
spontaneity
activity_intensity
novelty_seeking
travel_effort_tolerance
```

정밀 설문 완료 결과를 canonical initial profile로 저장한다.

---

# 13. STEP 5 — Preference를 Recommendation Service에 전달

Recommendation Service에는 사용자의 최신 **Preference 6축 snapshot**을 전달한다.

예:

```json
{
  "user_id": "user-42",
  "preference": {
    "nature": 0.82,
    "culture": 0.51,
    "food": 0.91,
    "shopping": 0.27,
    "experience": 0.63,
    "relaxation": 0.58
  },
  "profile_version": "preference-v1"
}
```

Recommendation Service의 책임:

```
Preference
    ↓
장소 적합성 계산
    ↓
match_score
predicted_score
final_score
    ↓
Top-N 후보
```

기본 후보 결과 예:

```json
{
  "place_id": "place-001",
  "match_score": 0.91,
  "predicted_score": 4.62,
  "final_score": 0.915,
  "transition_score": 0.42
}
```

`transition_score`는 과거 이동패턴 보조 정보이며 **최종 방문순서를 강제하지 않는다.**

---

# 14. 그룹 여행에서 Recommendation 처리

Recommendation Model 자체에 별도 그룹 모델이 없어도 된다. 기존 단일 사용자 추천을 **멤버별로 독립 호출**한다.

```
User A Preference → Recommendation
User B Preference → Recommendation
User C Preference → Recommendation
```

Spring Boot가 결과를 취합하여 Planner 요청의 `member_recommendations`에 멤버별 장소 점수를 전달한다.

중요:

```
그룹의 사용자 Preference를 DB에서 하나의 평균 Profile로 합쳐 저장 X
각 사용자 Profile을 독립적으로 유지 O
```

현재 Planner는 P10 그룹 로직을 통해 멤버별 Recommendation `final_score`를 실제 일정 선택 가치에 반영한다.

```
멤버별 final_score
      +
mandatory / completed 성향 충족
      ↓
residual preference
      ↓
멤버별 effective score
      ↓
그룹 평균 만족도 + 최저 멤버 만족도
      ↓
그룹 balance score
```

`transition_score`는 과거 이동패턴 보조/trace 정보이며 **최종 방문순서를 강제하지 않는다.**

---

# 15. STEP 6 — Google Maps Platform에서 Planner용 이동 데이터 생성

Planner가 Google Maps API를 직접 호출하면 안 된다.

Spring Boot가 필요한 장소 후보를 확보한 뒤 Google Maps Platform에서 이동 데이터를 조회한다.

```
Candidate A
Candidate B
Candidate C
Current Location
       ↓
Google Maps Platform
       ↓
mode별 directed travel data
```

예:

```json
[
  {
    "from": "place-A",
    "to": "place-B",
    "mode": "WALK",
    "minutes": 25
  },
  {
    "from": "place-A",
    "to": "place-B",
    "mode": "TRANSIT",
    "minutes": 12
  }
]
```

## 반드시 directed edge로 저장한다.

```
A → B = 12분
B → A = 16분
```

일 수 있다.

따라서 절대로 다음처럼 가정하지 않는다.

```
travel(A,B) == travel(B,A)
```

---

# 16. STEP 7 — Planner 입력을 Spring Boot가 준비

Recommendation 결과만으로 Planner를 호출할 수 없다.

Spring Boot가 다음을 결합해야 한다.

```
Recommendation candidates
+
Place DB
+
Trip / Member constraints
+
Cart / Mandatory
+
Opening Hours
+
Dwell Time
+
TravelStyle / TripContext에서 필요한 조건
+
Google directed travel_minutes
```

Preference/Place trait는 반드시 다음 canonical 6축으로 조립한다.

```
nature / culture / food / shopping / experience / relaxation
```

legacy `forest/time/local/festival/archive` key를 포함한 요청은 Planner에서 HTTP 422로 거부된다.

결과가 Planner의 기존 `PlanningRequest` 계약을 만족해야 한다.

---

# 17. Recommendation Adapter의 위치

Planner에는 Recommendation 결과와 Backend enrichment를 `PlanningRequest`로 정규화하는 adapter가 존재한다.

```
app/adapters/recommendation_adapter.py
```

역할:

```
RecommendationResult
+
PlannerEnrichment
       ↓
build_planning_request()
       ↓
PlanningRequest
```

Adapter는 다음을 하지 않는다.

```
DB 조회 X
Google API X
Recommendation 호출 X
Solver 호출 X
```

오직 validation / mapping / normalization만 수행한다.

---

# 18. 현재 HTTP 연결 방식

백엔드 연결에는 기존 외부 Planner API를 그대로 사용한다.

```
POST /v1/itineraries/optimize
```

Spring Boot가 Recommendation 결과, 장소 DB, 여행 제약, 장바구니/mandatory, 운영시간, Google directed travel data를 결합하여 최종 `PlanningRequest`를 만든 뒤 `/optimize`를 호출한다.

```
Spring Boot
   ↓
PlanningRequest 생성
   ↓
Google ID Token 생성
   ↓
POST /v1/itineraries/optimize
   ↓
STOG Planner
```

이 방식은 현재 즉시 사용 가능하며 별도 integration endpoint가 필요하지 않다.

추후 mapping 책임을 Planner HTTP boundary로 이동해야 할 필요가 생길 때만 별도 endpoint 추가를 검토한다.

---

# 19. STEP 8 — Spring Boot에서 STOG Planner 호출

Planner Base URL은 현재 다음으로 확정되어 있다.

```
STOG_PLANNER_BASE_URL=https://stog-planner-qoeu5cmuxq-du.a.run.app
```

`application.yml`:

```yaml
stog:
planner:
base-url: ${STOG_PLANNER_BASE_URL}
```

호출:

```
POST /v1/itineraries/optimize
Content-Type: application/json
Authorization: Bearer <Google ID Token>
```

Planner는 Private Cloud Run이므로 Authorization header는 필수다.

Planner 내부 흐름:

```
HTTP PlanningRequest
       ↓
plan_itinerary()
       ↓
CP-SAT
       ↓
PlanningResponse
```

추가 API가 필요한 경우 같은 Base URL을 사용한다.

```
POST /v1/itineraries/alternatives
POST /v1/itineraries/replan
POST /v1/itineraries/parse-edit
```

---

# 20. Planner가 담당하는 것

Planner는 다음을 최종 결정한다.

```
방문할 장소
mandatory 장소
optional drop
방문 순서
leg 이동수단
arrival time
waiting time
visit start
visit departure
여행 종료시각 만족 여부
미배치 이유
```

Planner가 하지 않는 것:

```
장소 DB 검색
Google Maps 호출
사용자 인증
날씨 API 호출
Recommendation inference
실제 지도 rendering
```

---

# 21. Recommendation Score와 Planner의 현재 관계

현재 score 계약은 다음과 같다.

```
match_score       → trace / 진단
predicted_score   → trace / 진단
final_score       → 멤버별 후보 만족도 및 그룹 일정 선택 가치에 실제 사용
transition_score  → trace-only
```

`transition_score`는 최종 방문순서를 강제하지 않는다.

`final_score` 역시 hard constraint를 대체하지 않는다.

```
final_score
→ optional candidate 선택 가치 O
→ 그룹 만족도 계산 O
→ 방문 순서 강제 X
→ travel time 대체 X
→ mandatory/운영시간/시간창 제약 무시 X
```

현재 Planner는 mandatory/completed 장소가 이미 충족한 성향을 residual preference에 반영하고, 멤버별 effective score를 바탕으로 그룹 평균 만족도와 최저 멤버 만족도를 함께 고려한다.

즉 feasibility가 항상 우선이다.

```
Hard Constraints
      ↓
Feasible solutions
      ↓
Recommendation / Group / Cart utility
      ↓
Best itinerary
```

---

# 22. STEP 9 — Planner 결과 DB 저장

Recommendation 결과와 Planner 결과를 분리해서 저장한다.

## Recommendation

```
recommendation_runs
recommendation_candidates
```

추천 candidate 예시 필드:

```
recommendation_run_id
place_id
rank
match_score
predicted_score
final_score
transition_score
selected_for_planning
```

## Planner

```
planning_runs / itineraries
itinerary_stops
itinerary_legs
itinerary_unplaced_places
```

### itinerary_stops

예:

```
place_id
day_number
visit_order
mandatory
arrival_at
visit_start_at
departure_at
waiting_minutes
dwell_minutes
status
```

### itinerary_legs

예:

```
from_stop_id
to_stop_id
transport_mode
distance_m
travel_minutes
route provider
```

### itinerary_unplaced_places

예:

```
place_id
reason_code
reason_detail
```

Recommendation 결과와 Planner 결과를 같은 테이블에 섞지 않는다.

---

# 23. STEP 10 — Android에 최종 결과 전달

백엔드는 Planner 결과를 저장한 뒤 Android용 API 응답으로 변환한다.

예:

```json
{
  "trip_id": "trip-100",
  "status": "SUCCESS",
  "itinerary": {
    "stops": [
      {
        "place_id": "place-A",
        "visit_order": 1,
        "arrival_at": "2026-09-10T10:10:00+09:00",
        "visit_start_at": "2026-09-10T10:10:00+09:00",
        "departure_at": "2026-09-10T11:30:00+09:00"
      }
    ],
    "legs": [
      {
        "from": "place-A",
        "to": "place-B",
        "mode": "TRANSIT",
        "travel_minutes": 12
      }
    ]
  }
}
```

Android는 Planner가 결정한 stop/leg를 바탕으로 Google Maps에서 실제 route를 렌더링한다.

---

# 24. Spring Boot 서비스 구조 권장안

```
backend
├─ ai
│  ├─ StogAiClient
│  ├─ AiConversationService
│  └─ dto
│
├─ profile
│  ├─ SurveyProfileService
│  ├─ ProfileAggregationService
│  └─ repository
│
├─ recommendation
│  ├─ RecommendationClient
│  └─ RecommendationService
│
├─ map
│  ├─ GoogleRoutesClient
│  └─ TravelMatrixService
│
├─ planner
│  ├─ StogPlannerClient
│  ├─ PlanningOrchestrator
│  └─ dto
│
├─ trip
│  ├─ TripService
│  ├─ CartService
│  └─ repository
│
└─ itinerary
   ├─ ItineraryPersistenceService
   └─ repository
```

가장 중요한 클래스는 개념적으로 `PlanningOrchestrator`다.

```
PlanningOrchestrator
   │
   ├─ 사용자/여행 DB 조회
   ├─ 최신 Preference/TravelStyle 조회
   ├─ Recommendation 호출
   ├─ Place enrichment
   ├─ Google travel data 수집
   ├─ PlanningRequest 생성
   ├─ Planner 호출
   └─ 결과 저장
```

---

# 25. 전체 백엔드 Orchestration 예시

```
[여행 일정 생성 요청]
          ↓
1. 사용자 인증
          ↓
2. trip_id 조회
          ↓
3. trip_members 조회
          ↓
4. 사용자 Preference / TravelStyle 조회
          ↓
5. Recommendation Service 호출
          ↓
6. 후보 place_id 수집
          ↓
7. 장소 DB enrichment
   - opening hours
   - dwell
   - traits
   - cart / mandatory
          ↓
8. Google Maps 이동 데이터 조회
          ↓
9. PlanningRequest 생성
          ↓
10. STOG Planner 호출
          ↓
11. PlanningResponse 검증
          ↓
12. itinerary DB 저장
          ↓
13. Android 응답
```

---

# 26. 실패 처리 기준

## AI Service 실패

AI 대화와 Planner 실행은 서로 독립적으로 실패 처리할 수 있어야 한다.

```
AI Service timeout
→ 대화 요청 실패 처리
→ DB/Planner 전체 장애로 확산시키지 않음
```

## Recommendation 실패

```
Recommendation unavailable
→ 일정 생성 실패 또는 사전에 정의된 fallback 정책 사용
```

임의의 AI 추천 결과를 Backend가 만들어내지 않는다.

## Google Maps travel data 부족

```
필수 travel edge 누락
→ Planner 호출 전 검증
또는 Planner의 기존 missing edge 계약으로 처리
```

## Planner INFEASIBLE

HTTP 500으로 숨기지 않는다.

Planner의 domain response를 사용해:

```
mandatory 장소 운영시간 충돌
이동 edge 부족
시간 부족
```

등의 사유를 Android가 사용자에게 설명할 수 있게 한다.

---

# 27. 현재 배포 구조

```
GCP
│
├─ Spring Boot Backend
│
├─ Private Cloud Run
│   └─ stog-preference-chat
│      Qwen + LoRA + L4
│
├─ Private Cloud Run
│   └─ stog-planner
│      FastAPI + OR-Tools CP-SAT / CPU
│
├─ Recommendation Service
│
└─ PostgreSQL
```

서비스 간 호출:

```
Spring Boot
  ├─ ID Token → Qwen Cloud Run
  └─ ID Token → Planner Cloud Run
```

Android가 두 서비스를 직접 호출하지 않는다.

---

# 28. 실제 구현 체크리스트

## Phase A — Qwen

- [ ]  Backend Service Account 결정
- [ ]  `roles/run.invoker` 부여
- [ ]  `GET /health` 성공
- [ ]  `POST /chat` 성공
- [ ]  동일 session_id 대화 성공
- [ ]  `preference_evidence` parsing
- [ ]  `style_evidence` parsing
- [ ]  `context_update` parsing
- [ ]  Evidence DB 저장
- [ ]  TripContext DB 업데이트

## Phase B — Profile

- [ ]  설문 API
- [ ]  Preference 6축 계산
- [ ]  TravelStyle 7변수 계산
- [ ]  Profile DB 저장
- [ ]  최신 Profile 조회 API/Service

## Phase C — Recommendation

- [ ]  Preference snapshot 전달
- [ ]  canonical `place_id` 수신
- [ ]  `final_score` 수신
- [ ]  Recommendation 결과 DB 저장

## Phase D — Google Maps

- [ ]  후보 좌표 조회
- [ ]  이동수단별 travel time 조회
- [ ]  directed edge 생성
- [ ]  stale/missing edge 검증

## Phase E — Planner

- [x]  Planner 컨테이너 Cloud Run 배포
- [x]  Canonical Preference 6축 적용
- [ ]  `GET /health` 인증 호출 확인
- [ ]  Backend Service Account `roles/run.invoker` 설정
- [ ]  PlanningRequest DTO 구현
- [ ]  Planner Client 구현
- [ ]  Recommendation + DB + Maps → PlanningRequest 변환
- [ ]  `/v1/itineraries/optimize` SUCCESS 테스트
- [ ]  INFEASIBLE 테스트
- [ ]  alternatives/replan 필요 흐름 테스트
- [ ]  결과 DB 저장

## Phase F — Android

- [ ]  최종 itinerary API
- [ ]  stops 표시
- [ ]  legs 표시
- [ ]  Google Maps route rendering
- [ ]  unplaced 이유 표시

---

# 29. 최소 E2E 테스트 시나리오

## 테스트 1 — AI

```
사용자: "나는 여행 가면 맛집 찾는 게 중요해"
        ↓
/chat
        ↓
food Preference Evidence 추출
        ↓
DB Evidence 저장
```

## 테스트 2 — TripContext

```
사용자: "가족이랑 다니고 있어"
        ↓
context_update.companion_type = FAMILY
        ↓
trip_context DB 업데이트
```

## 테스트 3 — Recommendation

```
Preference 6축
        ↓
Recommendation
        ↓
5개 이상의 canonical place_id 후보
```

## 테스트 4 — Planner

```
추천 후보
+ 운영시간
+ dwell
+ mandatory
+ directed travel_minutes
        ↓
STOG Planner
        ↓
SUCCESS
```

## 테스트 5 — 현실 제약

운영시간 때문에 recommendation rank와 다른 방문순서가 나와도 정상이다.

```
Recommendation: A > B > C
Planner Route : A → C → B
```

## 테스트 6 — INFEASIBLE

```
mandatory 장소가 여행 시간 내 방문 불가능
        ↓
Planner INFEASIBLE
        ↓
백엔드가 사유를 그대로 사용자 응답으로 변환
```

---

# 30. 현재 남아 있는 백엔드 연결 항목

Planner 자체의 P10 그룹 utility, P11 alternatives, P12 replanning, Canonical Preference 6축 정합성 수정은 완료되어 있으므로 별도 Planner 알고리즘 개발 항목으로 보지 않는다.

## 30.1 Evidence → Profile 반영 정책/영속화 확인

Qwen은 Evidence를 추출하지만, 백엔드에서는 원본 Evidence를 먼저 저장하고 canonical Preference/TravelStyle snapshot 갱신 정책을 명확히 적용해야 한다.

```
Qwen Evidence
      ↓
profile_evidence 원본 저장
      ↓
정해진 aggregation/update 정책
      ↓
user_preferences / user_travel_styles snapshot
```

Evidence 원본을 버리지 않고 추적 가능하게 유지한다.

## 30.2 Planner 실제 호출 연결

현재 필요한 것은 새 Planner 알고리즘 개발이 아니라 Spring Boot 호출부 구현이다.

```
Backend Service Account invoker 권한
        +
PlanningRequest DTO
        +
Google ID Token
        +
POST /v1/itineraries/optimize
        ↓
PlanningResponse 저장
```

## 30.3 별도 Integration Endpoint

현재는 필요하지 않다. Spring Boot가 `PlanningRequest`를 생성해 기존 `/v1/itineraries/optimize`를 호출하는 방식으로 연결한다.

별도 integration endpoint는 mapping 책임을 Planner 쪽으로 옮길 필요가 생겼을 때만 후속 검토한다.

---

# 31. 최종 원칙

```
Qwen
= 사용자를 이해한다.

Recommendation
= 사용자가 좋아할 장소를 찾는다.

STOG Planner
= 그 장소들을 실제로 갈 수 있는 일정으로 만든다.

Google Maps
= 실제 이동 정보를 제공하고 경로를 표시한다.

Spring Boot
= 이 모든 것을 연결하고 저장한다.
```

따라서 전체 시스템의 중앙 Orchestrator는 **Spring Boot**다.

```
Android
   ↓
Spring Boot
   ├─ Qwen
   ├─ DB
   ├─ Recommendation
   ├─ Google Maps
   └─ STOG Planner
```

AI와 Planner가 서로 직접 호출하거나 DB를 직접 조회하도록 만들지 않는다.
