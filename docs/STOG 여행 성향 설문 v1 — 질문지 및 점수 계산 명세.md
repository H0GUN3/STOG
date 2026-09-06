\## 1. 목적



STOG의 초기 사용자 프로필을 다음 두 영역으로 생성한다.



\### Preference 6축 — 무엇을 좋아하는가



\- `nature`

\- `culture`

\- `food`

\- `shopping`

\- `experience`

\- `relaxation`



\### TravelStyle 7변수 — 어떻게 여행하는가



\- `localness`

\- `crowd\_tolerance`

\- `pace`

\- `spontaneity`

\- `activity\_intensity`

\- `novelty\_seeking`

\- `travel\_effort\_tolerance`



모든 최종 점수는 `0.0 \~ 1.0` 범위로 저장한다.



\---



\## 2. 설문 구조



\### 2.1 초기 5문항



초기 5문항은 정밀 설문을 완료하기 전 빠른 Cold-start 프로필을 만들기 위한 용도이다.



정밀 설문 완료 후에는 초기 점수와 정밀 점수를 합산하지 않고, 정밀 설문 결과를 canonical profile로 사용한다.



\#### Q1. 지금 가장 하고 싶은 여행은?



선택지:



\- 자연

\- 역사·문화

\- 쇼핑

\- 미식

\- 체험

\- 휴식



임시 Preference는 모든 축을 `0.5`로 시작하고 선택한 축만 `0.75`로 설정한다.



예: 미식 선택



```json

{

&#x20; "nature": 0.5,

&#x20; "culture": 0.5,

&#x20; "food": 0.75,

&#x20; "shopping": 0.5,

&#x20; "experience": 0.5,

&#x20; "relaxation": 0.5

}

```



\#### Q2. 둘 다 갈 수 있다면 더 가고 싶은 곳은?



\- 대표 명소 → `localness = 0.25`

\- 로컬 명소 → `localness = 0.75`



\#### Q3. 하루 여행은 어떻게 즐기고 싶나요?



\- 천천히 여유롭게 → `pace = 0.25`

\- 알차게 꽉 차게 → `pace = 0.75`



\#### Q4. 마음에 드는 곳을 발견하면?



\- 계획대로 이동 → `spontaneity = 0.25`

\- 바로 가보기 → `spontaneity = 0.75`



\#### Q5. 더 선호하는 활동은?



\- 편하게 즐기기 → `activity\_intensity = 0.25`

\- 몸으로 즐기기 → `activity\_intensity = 0.75`



초기 질문에서 측정하지 않는 값은 중립값 `0.5`를 사용한다.



```

crowd\_tolerance = 0.5

novelty\_seeking = 0.5

travel\_effort\_tolerance = 0.5

```



\---



\## 3. 정밀 13문항



정밀 설문은 `Preference 6축 + TravelStyle 7변수`와 정확히 1:1 대응한다.



응답 척도:



| 응답 | 의미 |

| --- | --- |

| 1 | 전혀 아니다 |

| 2 | 조금 아니다 |

| 3 | 보통이다 |

| 4 | 그런 편이다 |

| 5 | 매우 그렇다 |



\### 3.1 Preference 6축



| 번호 | 질문 | 저장 변수 |

| --- | --- | --- |

| Q1 | 숲·공원·바다처럼 자연을 느낄 수 있는 장소를 선호하나요? | `nature` |

| Q2 | 박물관·문화유산·전통거리처럼 역사와 문화를 느낄 수 있는 장소를 좋아하나요? | `culture` |

| Q3 | 여행에서 맛집이나 카페를 찾아가는 것이 중요한 편인가요? | `food` |

| Q4 | 쇼핑이나 기념품·지역 상품을 구경하고 구매하는 것을 즐기나요? | `shopping` |

| Q5 | 공연·축제·액티비티·체험 프로그램처럼 직접 경험하는 활동을 즐기나요? | `experience` |

| Q6 | 여행 중 충분히 쉬거나 풍경을 천천히 즐기는 시간을 중요하게 생각하나요? | `relaxation` |



\### 3.2 TravelStyle 7변수



| 번호 | 질문 | 저장 변수 | 방향 |

| --- | --- | --- | --- |

| Q7 | 유명하고 대표적인 관광지를 우선해서 방문하는 편인가요? | `localness` | 역채점 |

| Q8 | 사람이 많더라도 가고 싶은 인기 장소라면 방문하는 편인가요? | `crowd\_tolerance` | 정방향 |

| Q9 | 하루에 여러 장소를 둘러보는 알찬 일정을 선호하나요? | `pace` | 정방향 |

| Q10 | 여행 중에도 미리 정한 계획을 가능한 그대로 따르는 편인가요? | `spontaneity` | 역채점 |

| Q11 | 걷기·등산·자전거·액티비티처럼 몸을 사용하는 여행 활동을 즐기나요? | `activity\_intensity` | 정방향 |

| Q12 | 잘 알려지고 익숙한 곳보다 처음 접하는 독특하고 새로운 장소를 찾아가고 싶나요? | `novelty\_seeking` | 정방향 |

| Q13 | 정말 가고 싶은 장소라면 이동 시간이 길거나 많이 걸어야 해도 방문할 의향이 있나요? | `travel\_effort\_tolerance` | 정방향 |



\---



\## 4. 점수 계산식



\### 4.1 정방향 문항



응답값을 `a`라고 할 때:



```

score = (a - 1) / 4

```



| 응답 | 점수 |

| --- | --- |

| 1 | 0.00 |

| 2 | 0.25 |

| 3 | 0.50 |

| 4 | 0.75 |

| 5 | 1.00 |



\### 4.2 역채점 문항



```

score = (5 - a) / 4

```



| 응답 | 점수 |

| --- | --- |

| 1 | 1.00 |

| 2 | 0.75 |

| 3 | 0.50 |

| 4 | 0.25 |

| 5 | 0.00 |



\---



\## 5. Preference 계산식



```

nature     = (Q1 - 1) / 4

culture    = (Q2 - 1) / 4

food       = (Q3 - 1) / 4

shopping   = (Q4 - 1) / 4

experience = (Q5 - 1) / 4

relaxation = (Q6 - 1) / 4

```



\---



\## 6. TravelStyle 계산식



```

localness               = (5 - Q7) / 4

crowd\_tolerance         = (Q8 - 1) / 4

pace                    = (Q9 - 1) / 4

spontaneity             = (5 - Q10) / 4

activity\_intensity      = (Q11 - 1) / 4

novelty\_seeking         = (Q12 - 1) / 4

travel\_effort\_tolerance = (Q13 - 1) / 4

```



\---



\## 7. TravelStyle 값의 의미



| 변수 | 0에 가까움 | 1에 가까움 |

| --- | --- | --- |

| `localness` | 유명·대표 관광지 선호 | 로컬·숨은 장소 선호 |

| `crowd\_tolerance` | 혼잡 회피 | 혼잡 허용 |

| `pace` | 느긋한 일정 | 촘촘한 일정 |

| `spontaneity` | 계획 중심 | 즉흥적 변경 선호 |

| `activity\_intensity` | 정적·편한 활동 | 활동적인 여행 |

| `novelty\_seeking` | 익숙하고 검증된 장소 선호 | 새롭고 독특한 장소 선호 |

| `travel\_effort\_tolerance` | 이동 부담 회피 | 긴 이동·도보 감수 |



이 방향은 시스템 전체에서 동일하게 유지한다.



\---



\## 8. 계산 예시



응답:



```

Q1 = 5

Q2 = 3

Q3 = 5

Q4 = 2

Q5 = 4

Q6 = 4

Q7 = 2

Q8 = 3

Q9 = 4

Q10 = 2

Q11 = 3

Q12 = 5

Q13 = 4

```



결과:



```json

{

&#x20; "preference": {

&#x20;   "nature": 1.0,

&#x20;   "culture": 0.5,

&#x20;   "food": 1.0,

&#x20;   "shopping": 0.25,

&#x20;   "experience": 0.75,

&#x20;   "relaxation": 0.75

&#x20; },

&#x20; "travel\_style": {

&#x20;   "localness": 0.75,

&#x20;   "crowd\_tolerance": 0.5,

&#x20;   "pace": 0.75,

&#x20;   "spontaneity": 0.75,

&#x20;   "activity\_intensity": 0.5,

&#x20;   "novelty\_seeking": 1.0,

&#x20;   "travel\_effort\_tolerance": 0.75

&#x20; }

}

```



\---



\## 9. 초기 설문과 정밀 설문의 관계



```

초기 5문항

&#x20;   ↓

temporary cold-start profile

&#x20;   ↓

정밀 설문 미완료 상태에서만 사용



정밀 13문항 완료

&#x20;   ↓

canonical initial profile

&#x20;   ↓

초기 5문항 점수를 대체

```



초기 점수와 정밀 점수를 단순 가중 평균하지 않는다.



\---



\## 10. Qwen 성향 모델과의 관계



정밀 설문은 최초 canonical profile을 만든다.



Qwen + LoRA는 이후 사용자 대화에서 다음을 추출한다.



```

Preference Evidence

TravelStyle Evidence

TripContext Update

```



Qwen이 설문 점수 계산식을 수행하지 않는다.



또한 Qwen Evidence가 곧바로 기존 점수를 덮어쓰지 않는다.



```

설문

&#x20; ↓

canonical initial profile

&#x20; ↓

DB



사용자 대화

&#x20; ↓

Qwen / LoRA

&#x20; ↓

Evidence

&#x20; ↓

DB 저장

&#x20; ↓

별도 Profile Aggregation 정책

&#x20; ↓

장기 프로필 보정

```



Evidence → 최종 Profile 갱신 공식은 설문 점수 계산과 분리해 별도로 관리한다.



\---



\## 11. 구현 책임



\### Spring Boot — canonical 계산 및 저장 책임



설문 점수 계산은 결정론적인 비즈니스 규칙이므로 Spring Boot에서 canonical하게 처리한다.



Spring Boot 책임:



\- 설문 응답 검증

\- 응답값 `1\~5` 범위 검증

\- 정방향/역방향 정규화

\- Preference 6축 계산

\- TravelStyle 7변수 계산

\- PostgreSQL 저장

\- 최신 Profile Snapshot 제공

\- Recommendation Service에 Preference 전달

\- STOG Planner에 필요한 TravelStyle/TripContext 전달



프론트엔드에서도 화면 미리보기용 계산은 가능하지만 최종 저장값은 Backend가 다시 계산·검증한다.



\### Qwen / LoRA — 설문 계산 책임 아님



Qwen은 다음을 담당한다.



\- 자연어 대화

\- Preference Evidence 추출

\- TravelStyle Evidence 추출

\- TripContext delta 추출



설문 13문항의 숫자 계산을 Qwen에 맡기지 않는다.



\### Recommendation Service



Recommendation Service는 DB의 최신 Preference 6축을 입력으로 사용한다.



```

Preference 6축

&#x20;   ↓

Recommendation Service

&#x20;   ↓

장소별 추천 점수

```



\### STOG Planner



Planner는 설문 원본이나 설문 계산을 처리하지 않는다.



Planner는 Backend가 정리한 다음 결과를 소비한다.



```

TravelStyle

TripContext

Recommendation Score

Cart / Mandatory

Opening Hours

Travel Time

```



\---



\## 12. 구현 예시



```python

def normalize(answer: int) -> float:

&#x20;   if answer < 1 or answer > 5:

&#x20;       raise ValueError("answer must be between 1 and 5")

&#x20;   return (answer - 1) / 4.0



def reverse\_normalize(answer: int) -> float:

&#x20;   if answer < 1 or answer > 5:

&#x20;       raise ValueError("answer must be between 1 and 5")

&#x20;   return (5 - answer) / 4.0

```



```python

preference = {

&#x20;   "nature": normalize(q1),

&#x20;   "culture": normalize(q2),

&#x20;   "food": normalize(q3),

&#x20;   "shopping": normalize(q4),

&#x20;   "experience": normalize(q5),

&#x20;   "relaxation": normalize(q6),

}



travel\_style = {

&#x20;   "localness": reverse\_normalize(q7),

&#x20;   "crowd\_tolerance": normalize(q8),

&#x20;   "pace": normalize(q9),

&#x20;   "spontaneity": reverse\_normalize(q10),

&#x20;   "activity\_intensity": normalize(q11),

&#x20;   "novelty\_seeking": normalize(q12),

&#x20;   "travel\_effort\_tolerance": normalize(q13),

}

```



\---



\## 13. 최종 데이터 흐름



```

Android

&#x20; ↓

설문 응답

&#x20; ↓

Spring Boot

&#x20; ├─ 응답 검증

&#x20; ├─ 정규화

&#x20; ├─ Preference 6축 계산

&#x20; └─ TravelStyle 7변수 계산

&#x20; ↓

PostgreSQL

&#x20; ├─ user\_preferences

&#x20; └─ user\_travel\_styles

&#x20;       │

&#x20;       ├──────────────→ Recommendation Service

&#x20;       │                 Preference 6축 사용

&#x20;       │

&#x20;       └──────────────→ STOG Planner

&#x20;                         TravelStyle / TripContext 등 사용



사용자 대화

&#x20; ↓

STOG Qwen + LoRA

&#x20; ↓

Evidence / Context Update

&#x20; ↓

Spring Boot

&#x20; ↓

DB 저장 및 장기 프로필 보정

```



\---



\## 14. 핵심 원칙



1\. 설문 점수 계산은 LLM 추론이 아니라 결정론적 비즈니스 로직이다.

2\. 최종 계산과 저장 책임은 Spring Boot에 둔다.

3\. Qwen은 설문 계산이 아니라 대화 기반 Evidence 추출을 담당한다.

4\. Recommendation Service는 Preference 6축을 소비한다.

5\. STOG Planner는 설문 원본을 보지 않고 Backend가 정리한 Profile/Context를 소비한다.

6\. 정밀 13문항 결과가 canonical initial profile이다.

7\. Qwen Evidence는 이후 이 초기 프로필을 장기적으로 보정하기 위한 근거로 사용한다.

