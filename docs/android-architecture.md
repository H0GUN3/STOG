# STOG Android 구조 초안

> 상태: draft  
> 이 문서는 현재 단일 `app` 모듈을 기준으로 Android 폴더와 개발
> 경계를 정리한다. 제품 기능과 용어의 기준은 `docs/*.yaml`이다.
> 실제 구현 전에 이 구조와 화면·API 계약을 함께 검토한다.

## 1. 구조 원칙

1. Android 앱은 현재 하나의 `app` 모듈로 유지한다. 기능이 많다는
   이유만으로 멀티 모듈을 만들지 않는다.
2. 폴더는 기술 종류보다 `space`, `record`, `plan`, `social`, `reward`,
   `profile` bounded context를 우선한다.
3. 화면, 상태, 입력 처리, 순수 판정 로직은 같은 기능 폴더에서 찾을 수
   있어야 한다.
4. 공통 폴더에는 여러 기능이 실제로 공유하는 값만 둔다.
5. `MainActivity`는 앱 진입과 navigation 연결만 담당하고 기능 로직을
   갖지 않는다.
6. Android의 로컬 저장과 서버 동기화를 분리한다. 네트워크가 없어도
   공유 원본과 위치 기록을 잃지 않는다.
7. 기능 간 직접 참조를 줄이고 공통 식별자는 glossary의 `en`을 사용한다.

## 2. 목표 폴더 구조

```text
app/src/main/java/com/stog/app/
├── MainActivity.kt
├── navigation/
│   ├── StogNavGraph.kt
│   └── StogDestination.kt
├── core/
│   ├── model/
│   │   ├── CellId.kt
│   │   ├── Coords.kt
│   │   └── Trip.kt
│   ├── database/
│   │   ├── StogDatabase.kt
│   │   ├── dao/
│   │   └── entity/
│   ├── network/
│   │   ├── StogApi.kt
│   │   ├── dto/
│   │   └── error/
│   ├── storage/
│   │   └── StogFileStore.kt
│   ├── location/
│   │   ├── LocationCollector.kt
│   │   └── CellTransitionDetector.kt
│   └── ui/
│       ├── components/
│       └── StogUiState.kt
├── feature/
│   ├── auth/
│   ├── space/
│   ├── record/
│   ├── plan/
│   │   ├── basket/
│   │   ├── itinerary/
│   │   ├── share_import/
│   │   └── travel_guide_ai/
│   ├── social/
│   ├── reward/
│   └── profile/
└── ui/
    └── theme/
```

현재 저장소에는 `MainActivity.kt`와 기본 theme만 있다. 위 구조는
구현을 시작할 때 적용할 목표 구조이며, 빈 패키지를 미리 만들지 않는다.

## 3. 기능 폴더 내부 규칙

기능이 실제로 시작되면 다음처럼 작은 수직 단위로 둔다.

```text
feature/plan/share_import/
├── ShareImportScreen.kt
├── ShareImportViewModel.kt
├── ShareImportUiState.kt
├── ShareImportReceiver.kt
├── ShareImportNormalizer.kt
└── ShareImportFileStore.kt
```

역할:

- `Screen`: 사용자에게 보이는 화면과 명확한 버튼 이름
- `ViewModel`: 화면 상태와 사용자 action 연결
- `UiState`: 저장됨, 확인 필요, 실패 등 가시적 상태
- `Receiver`: Android 공유 입력 수신
- `Normalizer`: 텍스트·URL·파일을 공통 입력으로 변환하는 순수 로직
- `FileStore`: 임시 `content://`를 앱 소유 저장소로 복사

provider 검색이나 AI 호출을 `Receiver`에 넣지 않는다. 수신부는 빠르게
원본을 저장하고, 후처리는 별도 작업으로 연결한다.

## 4. 화면·상태·데이터 흐름

```text
사용자 action
  -> ViewModel
  -> 순수 로직 또는 local data
  -> UiState 즉시 갱신
  -> 필요할 때 API 요청
  -> Room/API 결과 반영
  -> 화면에 상태와 다음 action 표시
```

공유 담기의 예:

```text
Android Intent
  -> ShareImportReceiver
  -> ShareImportNormalizer
  -> ShareImportFileStore
  -> Room 원본/link_block 저장
  -> "저장됨 · 장소 확인 중" 표시
  -> 후처리 worker 또는 다음 동기화
  -> 후보 확인 패널
```

화면이 API 응답을 직접 파싱하지 않는다. DTO는 `core/network/dto`에서
앱 모델로 변환한 뒤 화면에 전달한다.

## 5. 기능별 주 진입점

| bounded context | 사용자에게 보이는 주 기능 | Android 진입점 |
|---|---|---|
| `space` | Google 지도와 STOG cell | 지도 화면 |
| `record` | 여행 모드, 촬영 화면, 나의 여행 | 하단 중앙 촬영 진입, 보관함 |
| `plan` | 공유 담기, 일정 바구니, 카드 대시보드 | 계획 화면과 공유 수신 |
| `social` | 공개 동선과 좋아요 | 피드 |
| `reward` | 꿀 배지와 쿠폰 | 셀 상세와 장소 카드 |
| `profile` | 온보딩 설문과 성향 | 프로필 진입 |

`space`의 Android 지도 표시는 Google Maps SDK for Android를 사용한다.
STOG cell은 H3로 계산하고 Google 지도 위에 overlay로 표시한다. Android에는
제한된 Maps API key만 두며, Places API (New)와 Routes API key는 Spring
Boot backend에만 둔다. 장소 검색은 보호된 `POST /places/search`를 통해
backend에 요청한다. Kakao Native App Key는 소셜 로그인에만 사용하고 공유 담기
원본 carrier 인식은 키 없이 유지한다. Naver 로그인은 Android App Link domain과 backend callback 등록이
확인될 때까지 명시적으로 deferred 상태다. 로그인 화면은 준비 중 상태만 표시하고
Android는 ticket을 처리하는 intent filter를 등록하지 않는다.

지도 shell은 Google Map 위에 persistent draggable bottom sheet를 두고
closed·collapsed·half expanded·expanded 네 상태를 지원한다. 화면 하단에는
홈·여행·SNS·관광지 추천을 제공하는 notched bottom navigation과 중앙 육각형
AI Guide action을 독립된 고정 레이어로 둔다. 메뉴와 AI를 선택해도 이 레이어의
위치는 변하지 않으며 sheet의 하단 경계는 navigation 상단에서 끝난다.
root `Scaffold`의 `innerPadding`이 system navigation safe area를 소유하므로
map shell에서 `navigationBarsPadding()` 또는 동일한
`WindowInsets.navigationBars`를 다시 적용하지 않는다. 키보드 대응
`imePadding()`은 sheet 입력 영역에만 적용한다.

`travel_guide_ai`는 `plan`의 일정 검토와 `record`의 여행 중 다음 행동을
연결하는 기능으로 둔다. AI 실행 위치가 별도 서비스인지 여부는 A-03
결정 전까지 Android 폴더 구조와 분리해 둔다.

## 6. 개발 순서와 변경 경계

### 6.1 문서 우선

1. 화면이 아니라 DB부터 QA까지 연결된 기능 단위와 사용자 흐름을 정의한다.
2. `app`, `backend`, `docs`, migration, test 전체에서 관련 개념·caller와
   기존 API·model·DB relation을 검색한다.
3. 기존 관련 코드, DB, 재사용 항목, 신규 필요 영역, 신규 API, 신규 DB를
   포함한 Impact Analysis를 먼저 출력한다.
4. API request/response와 DB 변경을 계약으로 정리하고 필요한 경우
   canonical YAML의 `open_items` 또는 `decisions`를 갱신한다.
5. 기존 구현으로 해결할 수 없다는 근거와 책임 비중복 설명 없이 새
   Controller, Service, Repository, Entity, DTO, API, DB table,
   ViewModel, Android Repository를 만들지 않는다.
6. 그 뒤에만 최소한의 기능 폴더와 코드를 만든다.

### 6.2 기능 단위 작업

한 기능의 작업은 다음 순서로 진행한다.

```text
기능 ID 확인
  -> 기존 DB와 Backend Service/Repository 확인
  -> 기존 API/Controller와 Android Data/Repository 확인
  -> 사용자 흐름과 화면 상태 정의
  -> 순수 판정 로직 테스트
  -> 필요한 계층만 최소 수정
  -> Compose 화면
  -> DB까지 연결된 동작 확인
  -> 실제 표면 검증
```

기능 간 공통 코드가 필요해도 처음부터 `core`로 올리지 않는다. 두 번째
사용처가 생기고 의미가 동일할 때만 공통화한다.

화면 요구는 시스템 요구로 해석한다. 예를 들어 여행 카드에 참여자를
표시할 때는 현재 Trip API response, `trip_members` relation, 기존
participant 조회 Service, Android model·Repository와 ViewModel을 먼저
확인한다. UI에 참여자 이름을 직접 넣거나 fixture 성공을 실제 API
성공처럼 표시하지 않는다.

기능 완료 보고에는 Android UI, ViewModel, Android Repository/Data, HTTP
API/Controller, Backend Service, Backend Repository/JPA/SQL, DB, 관련
데이터·권한·소유권·cascade의 상태와 증거를 적는다. UI만 연결된 상태는
완료로 보고하지 않는다.

### 6.3 변경 소유

- `space`: 셀, 지도, viewport
- `record`: 여행 모드, 위치, 사진, 보관함
- `plan`: 공유 담기, 장소, 바구니, 일정, 여행 가이드 AI 진입
- `social`: 공개 범위, 피드, 좋아요
- `reward`: 꿀, 쿠폰, 제휴처
- `profile`: 설문, 성향, 추천 입력

다른 기능의 DB/API writer를 화면 코드에서 직접 호출하지 않는다. 공통
계약이 바뀌면 영향받는 FE, BE, DB, AI 역할을 PR에 표시한다.

## 7. 가시성 구현 확인표

- [ ] 공유 직후 원본 저장 완료가 화면에 보인다.
- [ ] 후처리 중인 항목 수가 `basket` 진입점에 보인다.
- [ ] 장소 후보가 없거나 여러 개인 이유가 보인다.
- [ ] `[이 장소가 맞아요]`, `[삭제]`, `[직접 입력]`의 결과가 즉시
      화면에 반영된다.
- [ ] `unresolved` 항목이 조용히 사라지지 않는다.
- [ ] AI가 일정에 적용하기 전 제안과 실제 적용을 구분한다.
- [ ] 네트워크 실패와 AI 실패 시 현재 데이터가 유지된다.
- [ ] 여행 모드가 `active`, `dormant`, `ended` 중 어떤 상태인지 보인다.
- [ ] 사용자가 다음에 누를 수 있는 행동이 각 상태에 하나 이상 있다.

## 8. 현재 구조에서 하지 않는 것

- 공유 수신 Activity에서 provider 검색과 AI 호출을 동기 실행하지 않는다.
- 기능마다 별도 Gradle 모듈을 만들지 않는다.
- 화면마다 독립적인 장소 모델을 만들지 않는다.
- `cells` 테이블, PostGIS, WebSocket, 학습 기반 추천 모델을 추가하지
  않는다.
- AI 결과로 사용자의 일정과 장소를 자동 확정하지 않는다.
