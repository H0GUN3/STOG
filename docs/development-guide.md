# STOG 개발 가이드

> 이 문서는 검증 연구를 구현 순서와 확인 항목으로 압축한 안내서다. 정식 명세를 대체하지 않는다. 용어와 결정의 기준은 `glossary.yaml` → `product.yaml` → `architecture.yaml` → `conventions.yaml` 순서다. 다만 현재 YAML에는 쉼표가 있는 flow mapping 때문에 의미가 `null`로 분해된 항목이 있다. 의미 수리와 schema validation이 끝난 뒤에도 YAML이 canonical 기준이다.
>
> 이 문서의 제안 패턴은 현재 보장이 아니다. 별도 결정과 canonicalization이 끝나기 전에는 **요구사항 pending canonicalization**으로 취급한다.

## 1. 시작 순서

1. `glossary.yaml`을 읽고 코드, API, DB, UI에 en 식별자를 그대로 적용한다. `avoid` 표현은 쓰지 않는다.
2. `product.yaml`에서 P0와 P1의 사용자 흐름, source가 user인 decisions, tuning_parameters를 확인한다. 4주와 5명이라는 일정은 검증된 보장이 아니다.
3. `architecture.yaml`에서 API, 스키마, 알고리즘, 외부 연동을 확인한다. `conventions.yaml`의 변경 절차와 역할 표시를 함께 적용한다.
4. 착수 전에 YAML의 의미 수리를 완료한다. `product.yaml`의 D-11, D-15, D-18, D-20, D-32와 `architecture.yaml`의 timeout 항목을 block mapping 또는 인용된 scalar로 고친다. schema로 unknown key와 예상 밖 `null`을 거부하고, decision, open item, feature, tuning 참조를 검사하며, parsed semantic diff를 CI에서 확인한다.
5. 다음 Gate 0를 확인한다. P0/P1 중 F-11, F-25의 persistence/API 계약과 F-24, F-07의 request/response 의미를 보완한다. A-02, C-01, C-02는 답을 추측하지 않고 미결정으로 유지하며, 결정에 의존하는 구현은 시작하지 않는다. private photo read path, Cloud SQL/IAM/secrets/cost, ownership 집중 위험은 별도 blocker로 남긴다.
6. 그 뒤 Git, PR 검사, DB migration, 동작하는 API revision, signed upload와 authorized read의 최소 흐름을 만든다. 이후 `plan`, `record`, `profile` 작업을 계약 단위로 병렬 진행한다.
7. 마지막으로 release build, seeded DB, provider release credentials, 로그인, 위치, upload/read, archive, feed와 실패 흐름을 실제 표면에서 리허설한다. 이 순서는 의존성 계획이지 4주 완료 보장이 아니다.

## 2. 도메인 책임과 경계

| 도메인 | 책임 | 경계와 주의점 |
|---|---|---|
| `space` | `cell`, `cell_id`, `coords`, 지도 표현 | `cell`은 좌표에서 계산한다. `cells` 테이블을 만들지 않는다. 콘텐츠가 있는 셀만 표시한다. |
| `record` | `trip`, `trip_mode`, `visit`, `pass`, `trail`, `photo`, `archive` | 앱이 위치를 먼저 저장하고 `cell_transition` 중심으로 전송한다. `photo`는 private/group/public 의미와 authorized read 계약이 필요하다. |
| `plan` | `place`, `basket`, `share_import`, `link_block`, `itinerary_item`, `itinerary` | 받은 원본과 미리보기를 먼저 보존하고, 매핑은 뒤로 미룬다. 사용자 확인 없이는 장소를 확정하지 않는다. |
| `social` | `group_trip`, `member`, `visibility`, `like`, `public_trail` | `owner_id`가 관리 권한의 기준이다. 모든 참여자의 일정 수정과 그룹장의 관리 권한을 분리한다. |
| `reward` | `honey`, `partner`, `coupon`, `coupon_redeem`, `street_candidate`, `street` | 쿠폰과 원장은 동시성, idempotency, quota, settlement 계약이 먼저다. 제휴 여부는 셀 판정과 좋아요 집계에 영향을 주지 않는다. |
| `profile` | `axis`, `traveler_type`, `onboarding_survey`, 추천 입력 | 현재 `traveler_type` 표현은 상위 2축 조합을 담지 못한다. representation과 추천 feature 계약 전에는 구현을 확정하지 않는다. |

도메인 사이의 공용 식별자는 `cell_id`와 `trip` 등 canonical 용어로만 연결한다. 한 역할이 다른 도메인의 writer가 되지 않도록 API와 migration 소유자를 PR에서 표시한다.

## 3. 구현 불변식

### 공간과 기록

* 유효한 H3 `cell`은 h3-java 4.4.0에서 양수 `long`, PostgreSQL `BIGINT`, 소문자 16진 API 문자열 사이를 무손실 왕복한다. 입력 경계에서 양수가 아니거나 `!isValidCell`이면 거부한다. parse 성공이나 `NOT NULL`만으로 유효성을 판단하지 않는다.
* 해상도 10의 전북 10개 표본에서 맞은편 변 중심 간격은 111.664에서 112.935m, 꼭짓점 간격은 128.959에서 130.424m였다. 이는 이름을 붙인 표본의 local approximation일 뿐, 전역 고정 크기나 정확한 metric radius 규칙이 아니다.
* `coords`와 `cell_id`는 공간 데이터에 함께 둔다. `cell_transition`은 같은 셀이 연속 2회 확인될 때만 인정한다. 위치는 Room에 먼저 저장하고 재전송 가능하게 한다. Android background location delivery의 일반 보장은 없으므로 foreground-service, reboot, dormant 복귀 계약을 별도로 확정한다.
* viewport는 단순 위도 경계만으로 처리하지 않는다. antimeridian, H3 후보 생성, 결과 집합의 포함 의미와 source를 정해야 한다. `gridDisk`의 k를 정확한 meter 반경으로 간주하지 말고, metric radius의 후보 범위와 completeness 규칙을 별도 결정한다.

### 계획과 외부 연동

* `share_import`는 Android 공유 carrier를 정규화하고 임시 URI 콘텐츠를 즉시 STOG 소유 저장소로 복사한다. `original_url`, 원본 텍스트, 미리보기를 보존한다. provider 검색은 뒤로 미루고 후보는 사용자 확인 뒤에만 `place`로 확정한다.
* `unresolved` `link_block`은 `basket`에 남기되 동선 계산에서 제외한다. 외부 provider의 HTTP status만으로 성공을 판정하지 않는다. body 수준 오류와 timeout은 retryable unknown으로 보낸다.
* Kakao, Naver, ODsay 결과의 광범위한 저장, caching, redistribution은 기본 허용으로 보지 않는다. 선택한 참조를 어디까지 저장할지는 provider별 정책 또는 법무 해석을 받은 뒤 정한다.

### 권한, `reward`, AI

* 인증 계정 키는 `(provider, provider_user_id)`다. Google은 `sub`, Kakao는 회원번호 또는 OIDC `sub`, Naver는 app-scoped profile `id`를 사용하며 email을 키로 쓰지 않는다. provider secret은 서버에만 둔다.
* 존속하는 `group_trip`에는 정확히 하나의 active owner가 있어야 한다. transfer, leave, 마지막 참여자 처리와 삭제 또는 보존은 trip row lock을 포함한 한 transaction으로 처리한다. A-04의 승계 또는 `leave` 제한 값은 정하지 않는다.
* invite token은 A-05의 TTL, 재사용, 최대 참여자 정책과 무관하게 예측 불가능해야 하며 hash만 저장한다. `expiry`, `revocation`, owner 발급 및 폐기, rate limiting, membership insert와의 atomic validation을 요구사항으로 둔다. 숫자 정책은 발명하지 않는다.
* `coupon`의 `(partner, user, issued_date)` 고유성은 중복 발급 방지의 한 축일 뿐 partner daily quota를 보장하지 않는다. server timezone을 정하고 partner row를 잠근 뒤 quota를 검사한다. `coupon_redeem`, 꿀 차감, balance cache, settlement marker는 한 transaction에 둔다. rollback된 transaction에는 보상 행을 추가하지 않는다. 이미 commit된 차감의 보정만 별도 business transaction이다. idempotency key, request hash, outcome, ledger uniqueness, reconciliation, settlement outbox는 아직 계약이 없다.
* `onboarding_survey`의 70/30과 최근 50건은 product tuning이며 최적성의 근거가 아니다. 모든 affinity의 정규화, 질문과 축 매핑, deterministic tie, unclassified event, `traveler_type` 표현을 먼저 정한다. 장소 추천에는 6축 feature, category mapping, formula, tie key, reason code와 evaluation이 필요하다.
* AI는 deterministic rank와 reason code를 바꾸거나 근거 없는 장소 사실을 추가하지 않는다. AI-04 결과는 여섯 축 중 하나 또는 `unknown`이다. `unknown`, timeout, model failure는 profile을 갱신하지 않는다. model/prompt version과 classification status를 저장하고 한국어 ground truth의 Macro-F1와 per-class F1을 확인하기 전 자동 갱신하지 않는다. 외부 payload에서 user/auth identity, exact coords 또는 `cell`, timestamp, dwell/trail, co-traveler, full history를 제외하고 raw prompt를 기록하지 않는다. `behavior_events.raw_text`의 목적, 보존 기간, 접근 권한은 미결정이다.

## 4. 역할별 확인표

### FE, Android

* `cell_id`는 API에서 16진 문자열로 받고 내부 경계에서만 `Long`으로 변환한다.
* Room 선저장, 재전송 queue, `cell_transition` 확인, `active`와 `dormant`와 `ended` 표시를 구현한다. background location, foreground-service, reboot 복원 요구를 확인한다.
* 공유 carrier와 `ClipData`를 처리하고 임시 콘텐츠를 소유 저장소로 복사한다. 자동 장소 확정이나 이미지 OCR은 하지 않는다.
* 사진은 앱에서 original과 thumbnail을 준비해 signed upload를 사용한다. private/group/public `visibility`에 맞는 authorized thumbnail read가 확정되기 전 public bucket을 가정하지 않는다.
* 지도는 idle 시점에만 조회하고 viewport source와 metric radius가 결정되기 전 임의의 경계 의미를 UI 계약으로 고정하지 않는다.

### BE, API

* API 경계에서 H3 validation과 `cell_id` 변환을 수행하고, 좌표 범위, antimeridian, H3 후보 의미를 계약에 반영한다.
* 외부 provider 오류를 body까지 해석하고 retryable unknown으로 구분한다. 3초는 provider SLA가 아니라 STOG tuning이다.
* `owner_id`와 active membership를 매 요청 검사한다. RLS만으로 권한을 보장하지 않는다. invite 검증과 membership insert는 원자적으로 처리한다.
* transaction 안에서 reward와 `honey_ledger`를 처리하고 idempotency와 settlement marker의 schema가 없는 동안 완료 보장을 주장하지 않는다.
* API가 DB의 sole writer가 되는지, AI timeout이 사용자 deadline보다 짧은지 확인한다. model/network 작업 중 DB transaction을 열어 두지 않는다.

### DB

* canonical YAML 수리 후 schema validation, migration ownership, unknown key와 null 검사를 CI에 넣는다.
* `cell_id`는 `BIGINT`, `coords`는 원본 `lat`, `lng`로 저장한다. `cells` 테이블과 PostGIS는 현재 범위에 추가하지 않는다.
* `owner_id`, `trip_members`, `visibility`의 actor authorization을 함께 검토한다. `itinerary_changes`는 append only로 남긴다.
* `coupon` unique constraint, partner quota row lock, ledger append-only privilege, idempotency record, settlement/outbox, balance reconciliation을 각각 schema와 transaction으로 명시한다.
* private media의 object access와 authorized read path, signed URL의 method, key, content type, 짧은 expiry, service-account signing IAM을 함께 검토한다.

### AI

* A-03 전에는 독립 FastAPI 실행 단위를 전제로 구현하지 않는다. 입력과 출력 계약만 먼저 고정한다.
* 추천은 학습 기반 모델이 아니라 결정된 feature와 규칙 기반 score로 시작한다. `unknown`과 tie를 숨기지 않는다.
* 생성형 설명은 rank, reason code, public place facts만 입력으로 받고 rank/order를 변경하지 않는다.
* 최소 payload, provider logging과 retention 검토, versioning, 한국어 평가, timeout fallback을 문서화한다.
* 독립 실행 단위는 Python 전용 의존성, 측정된 SLO 또는 cost 실패, 독립 release/failure domain 중 하나의 hard trigger와 typed I/O, sole DB writer, IAM, observability, local startup, contract test가 모두 확인될 때만 제안한다. 이는 기존 보장이 아니라 canonicalization pending requirement이다.

## 5. 미결정 처리와 주요 blocker

다음 항목은 답을 추측하지 않는다. A-02는 dataset 원본 형식과 loader 위치, A-03은 AI 실행 단위, A-04는 owner departure, A-05는 invite 정책이다. G-01은 STOBEE 표기, C-01은 formatter/linter, C-02는 branch protection이다. 이 문서는 어느 항목도 답하지 않는다. 또한 다음 blocker를 pre-code 또는 해당 기능의 시작 gate로 남긴다.

* YAML semantic null-map 수리와 schema validation
* P0/P1의 F-11, F-25 persistence/API 계약 및 F-24, F-07 request/response 의미
* viewport source와 포함 semantics, metric radius와 completeness
* photo `visibility`와 authorized read path
* coupon quota, ledger, idempotency, settlement
* `traveler_type` representation과 추천 feature 계약
* AI privacy, retention, evaluation, failure semantics
* IAM, secrets, Cloud SQL sizing/connection headroom, cost, CI와 deploy

판단이 필요하면 해당 YAML의 `open_items`에 질문을 추가하고 담당 역할과 공유한다. 결정 후 `decisions`로 옮기고 `CHANGELOG.yaml`에 남긴다. 급한 경우 되돌리기 쉬운 임시 선택만 하고 PR에 **요구사항 pending canonicalization**이라고 표시한다.

## 6. Pre-merge checklist

* [ ] 변경이 F-xx와 연결되고 영향받는 FE, BE, DB, AI가 PR에 표시됐다.
* [ ] 동작 변경이면 먼저 canonical YAML을 고쳤고, source가 user인 결정을 임의로 바꾸지 않았다.
* [ ] YAML schema, unknown key, unexpected `null`, semantic diff 검사가 통과했다.
* [ ] `glossary.yaml`의 en 식별자와 허용된 방향 용어만 사용했다.
* [ ] `cell_id`, 권한, transaction, idempotency, private media read를 경계에서 확인했다.
* [ ] 방문과 통과, 쿠폰, 성향 점수, 좌표와 셀 변환, `cell_transition`, 동선 속도 판정 테스트를 변경 범위에 맞게 실행했다.
* [ ] 외부 provider 오류, offline queue, timeout, `unknown`, rollback 흐름을 확인했다.
* [ ] 로컬 실행과 migration을 확인했고, secret은 APK나 로그에 없다.
* [ ] main 직접 push와 force push 없이 PR, 리뷰 승인 1건, 영향 범위 체크박스를 갖췄다.
* [ ] 미결정 항목을 임의로 닫지 않았고, 필요한 `open_items`와 `CHANGELOG.yaml` 기록이 있다.
