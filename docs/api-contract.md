# STOG API 계약 초안

> 상태: draft  
> REST API의 앱·서버 경계를 설명한다. 실제 endpoint와 schema를
> 확정할 때 `docs/architecture.yaml`과 함께 갱신한다.

## 1. 공통 규칙

### 인증

- 보호된 API는 STOG 자체 JWT를 받는다.
- 서버는 token의 `user_id`와 active membership를 매 요청 확인한다.
- provider access token과 provider secret을 다른 API 응답에 포함하지
  않는다.

### 응답

성공 응답은 기능별 DTO를 반환한다. 오류는 다음 공통 형태를 사용한다.

```json
{
  "error": {
    "code": "PLACE_REVIEW_REQUIRED",
    "message": "확인이 필요한 장소가 있습니다.",
    "retryable": false,
    "request_id": "server-generated-id"
  }
}
```

`message`는 사용자에게 보여줄 수 있지만, 내부 로그·provider payload·
secret을 포함하지 않는다. `request_id`는 서버 로그와 대응한다.

### `cell_id`

| 경계 | 형식 |
|---|---|
| PostgreSQL | `BIGINT` |
| Spring 내부 | `Long` |
| JSON | 소문자 16진 문자열 |

컨트롤러 경계에서만 H3 변환과 유효성 검사를 수행한다. 숫자 JSON으로
보내지 않는다.

### 외부 API 오류

Spring Boot가 provider 응답의 HTTP status와 body 수준 오류를 모두
해석한다. Android는 provider별 응답 형식을 알지 않는다.

| 상황 | HTTP | code | retryable |
|---|---:|---|---|
| 인증 실패 | 401/403 | `UPSTREAM_AUTH_FAILED` | false |
| provider timeout | 504 | `UPSTREAM_TIMEOUT` | true |
| quota 초과 | 429 | `UPSTREAM_QUOTA_EXCEEDED` | later |
| 후보 없음 | 200 | `NO_PLACE_CANDIDATE` | false |
| AI 실행 실패 | 503 | `AI_UNAVAILABLE` | true |
| 입력 오류 | 400 | `INVALID_REQUEST` | false |
| 권한 없음 | 403 | `FORBIDDEN` | false |
| 동시 변경 충돌 | 409 | `VERSION_CONFLICT` | true |

## 2. 인증 API

| method | path | 목적 |
|---|---|---|
| POST | `/auth/signup` | local 계정 생성 |
| POST | `/auth/login` | local 로그인 |
| POST | `/auth/social` | Google/Kakao/Naver token 검증 후 로그인 |
| POST | `/auth/refresh` | JWT 갱신 |
| POST | `/auth/logout` | refresh token 폐기 |
| GET | `/me` | 현재 사용자 조회 |
| GET | `/auth/naver/start` | 네이버 authorization code 흐름 시작 |
| GET | `/auth/naver/callback` | code와 state 검증 후 1회용 login ticket 발급 |

회원가입과 `auth_accounts` 생성은 하나의 transaction으로 처리한다.
소셜 로그인에서 provider email을 계정 key로 사용하지 않고 검증된
provider 식별자를 사용한다.

네이버 callback은 `STOG_APP_LINK_URI`가 설정되면 ticket만 포함한 HTTPS
App Link로 Android에 돌아가고, 아직 도메인이 없으면 ticket JSON을 반환한다.
어느 경우에도 STOG JWT를 URL에 넣지 않는다. Android App Link 로그인 화면과
intent 처리는 verified domain과 네이버 HTTPS callback 등록을 확인할 때까지
deferred 상태이므로, 이 backend contract를 Android 출시 기능으로 표시하지 않는다.

## 3. 셀·여행·사진 API

| method | path | 주요 입력 | 주요 결과 |
|---|---|---|---|
| GET | `/cells` | viewport 좌표 | 콘텐츠가 있는 셀과 배지 |
| GET | `/cells/{cellId}` | 16진 `cellId` | 셀 상세 |
| GET | `/cells/{cellId}/photos` | 셀 ID | 권한에 맞는 사진 |
| POST | `/trips` | 제목·활동·기간 | `trip` |
| PATCH | `/trips/{id}/mode` | `active/dormant/ended` | 새 `trip_mode` |
| POST | `/trips/{id}/visits` | 셀·좌표·idempotency key | 방문/통과 결과 |
| GET | `/trips/me` | 없음 | 사용자 여행 목록 |
| POST | `/photos/upload-url` | 파일 metadata | original/thumb signed URL |
| POST | `/photos` | 업로드 완료 metadata | 사진 row |
| GET | `/photos/{id}` | 사진 ID | owner-authorized signed GET URLs |
| GET | `/photos/mine` | 없음 | 인증 사용자의 전체 사진과 signed thumbnail URL |
| PATCH | `/photos/{id}/visibility` | 공개 범위 | 변경된 공개 범위 |
| GET | `/profile/summary` | 없음 | 닉네임·프로필 사진·여행/CELL/꿀 통계 |
| POST | `/profile/avatar/upload-url` | JPEG metadata | 프로필 사진 signed PUT URL |
| POST | `/profile/avatar/finalize` | 업로드 metadata | 프로필 사진 연결 |

사진 API는 서버가 bytes를 중계하지 않는다. signed URL 업로드가 성공한
뒤 metadata API를 호출한다.

`GET /trips/me`는 인증된 사용자가 소유하거나 active membership으로 참여 중인 여행을 생성일 역순으로 반환한다. `planned_start_date`와 `planned_end_date`는 Android 월별 캘린더와 여행 모드 자동 활성화가 함께 사용하는 canonical 날짜 범위다.

```json
[
  {
    "id": 8,
    "title": "전주 여행",
    "activity_type": "tour",
    "mode": "dormant",
    "visibility": "private"
  }
]
```

`POST /photos/upload-url` accepts only image metadata. The server generates
both object keys; clients never provide a bucket path.

```json
{
  "content_type": "image/jpeg",
  "size_bytes": 5242880
}
```

The response contains original and thumbnail object keys, a V4 signed PUT URL
for each, the signed `content_type`, and `expires_at`. The endpoint is
available only with the explicit `gcs-write` profile.

```json
{
  "original_object_key": "photos/42/uuid.jpg",
  "original_upload_url": "https://storage.googleapis.com/...",
  "thumbnail_object_key": "photos/42/uuid-thumb.jpg",
  "thumbnail_upload_url": "https://storage.googleapis.com/...",
  "content_type": "image/jpeg",
  "expires_at": "2026-08-19T12:15:00Z"
}
```

`POST /photos` is called after both PUT uploads. It accepts
`trip_id`, `source`, optional coordinates and capture time, both server-issued
object keys, and an optional caption. The server verifies that the trip is
owned by the authenticated user, that both keys belong to that user, and
calculates nullable H3-10 `cell_id` from valid coordinates. New rows default
to `private`.

`GET /photos/{id}` and `PATCH /photos/{id}/visibility` are owner-scoped until
the `trip_members` authorization contract is implemented. The read response
contains short-lived signed GET URLs for the original and thumbnail; public
URLs are never persisted.

`GET /photos/mine` is owner-scoped and returns both public and private photos
for the profile's 3-column grid. Visibility remains enforced when another
viewer reads a photo.

### 3.1 발견 피드와 저장

| method | path | 목적 |
|---|---|---|
| GET | `/feed` | 공개 발견 사진 피드 |
| GET | `/feed/saved` | 인증 사용자가 저장한 공개 발견 사진 |
| POST | `/photos/{id}/save` | 발견 사진 저장 |
| DELETE | `/photos/{id}/save` | 발견 사진 저장 취소 |

저장은 `saved_photos`에 사용자와 사진을 함께 기록한다. 저장 대상은
현재 `feed` 공개 eligibility를 만족해야 하며, 같은 사용자의 반복 저장과
취소는 멱등적이다. 피드 항목은 `saved_by_viewer`를 함께 반환한다.
`/feed/saved`는 저장 시각 역순 cursor를 사용한다.

## 4. 계획 API

| method | path | 목적 |
|---|---|---|
| POST | `/places/search` | 서버를 통한 장소 후보 검색 |
| GET | `/trips/{id}/members` | active 참여자와 그룹장 조회 |
| POST | `/trips/{id}/invite-links` | 그룹장 초대 코드 생성 |
| POST | `/trip-invites/{token}/join` | 인증 사용자의 초대 코드 참여 |
| DELETE | `/trips/{id}/members/{userId}` | 그룹장이 참여자를 내보냄 |
| DELETE | `/trips/{id}/members/me` | 참여자가 여행에서 나감 |
| POST | `/basket-items` | 검색·직접 입력 항목을 바구니에 추가 |
| POST | `/basket-items/link` | 확인한 공유 담기 원본을 선택한 여행 바구니에 추가 |
| GET | `/trips/{id}/basket` | 바구니 조회, source/status filter |
| POST | `/basket-items/{id}/resolve` | 좌표와 장소 후보 확인 |
| GET | `/trips/{id}/itinerary` | 날짜·순서가 지정된 일정 항목 조회 |
| PUT | `/trips/{id}/itinerary` | 날짜·순서 저장 |
| GET | `/trips/{id}/itinerary/changes` | 변경 이력 조회 |
| POST | `/itinerary/validate` | 이동·체류 기반 일정 검토 |

모든 참여자는 바구니와 일정 변경을 할 수 있고, 변경 결과는
`itinerary_changes`에 append-only로 기록한다. `unresolved` 항목은
동선 검토 입력에서 제외하고 응답에 제외 이유를 포함한다.

`GET /trips/{id}/members`는 active member에게만 `viewer_id`, `owner_id`,
그리고 `user_id`, `nickname`, `joined_at`으로 구성된 `members` 배열을
반환한다. Android는 `viewer_id == owner_id`일 때만 초대 코드 생성과 다른
참여자 삭제 action을 표시한다. 초대는 기존 token path를 코드로 입력하는
흐름만 지원하며 Android deep link나 별도 캘린더 저장소를 만들지 않는다.
월별 캘린더는 `GET /trips/me`의 날짜 범위를 순수하게 투영하고 선택한 날짜를
`planned_start_date` 기준의 1-based `day_number`로 변환한다.

The first canonical planning write path is owner-scoped trip creation followed
by Google candidate confirmation:

```http
POST /trips
Authorization: Bearer <stog-jwt>
Content-Type: application/json
```

```json
{
  "title": "전주 여행",
  "activity_type": "tour",
  "planned_start_date": "2026-09-01",
  "planned_end_date": "2026-09-03"
}
```

```http
POST /basket-items
Authorization: Bearer <stog-jwt>
Content-Type: application/json
```

```json
{
  "trip_id": 1,
  "provider": "google",
  "external_id": "ChIJexample",
  "name": "전주 카페",
  "category": "cafe",
  "latitude": 35.815,
  "longitude": 127.15
}
```

The server resolves the Google Place Details record by `external_id` and uses
only that provider data when it creates or reuses the canonical `places` row by
`(source, external_id)`. The client-supplied display fields remain accepted for
backward compatibility but are not canonical input. The server calculates H3
resolution 10 from provider coordinates and creates a resolved `basket_items`
row only when the authenticated user owns the trip. The response serializes
`cell_id` as lowercase hexadecimal.

`GET /trips/{id}/basket` is owner-scoped and returns both searched places and
confirmed `share_import` link items in one list:

```json
[
  {
    "id": 42,
    "item_type": "place",
    "title": "전주 카페",
    "category": "cafe",
    "source": "google",
    "status": "resolved",
    "added_at": "2026-08-19T12:00:00Z"
  }
]
```

`POST /basket-items/link` is authenticated and owner-scoped. It stores the
confirmed sharing source without running place search during the share flow.

```json
{
  "trip_id": 1,
  "source": "naver",
  "original_url": "https://map.naver.com/example",
  "title": "공유로 담은 장소",
  "category": null
}
```

`GET /trips/{id}/itinerary` and `PUT /trips/{id}/itinerary` use the same
ordered item shape. The PUT replaces the selected trip's current arrangement
in one transaction; every `basket_item_id` must belong to that trip.

```json
{
  "items": [
    {
      "basket_item_id": 42,
      "day_number": 1,
      "order_index": 0,
      "planned_arrival": null,
      "planned_duration_min": null
    }
  ]
}
```

## 5. Google Places API (New) and Routes API

`/places/search`, `/places/{placeId}`, and `/places/photo` are public read-only
Spring Boot endpoints so guests can explore places. Android never calls a
Google provider directly, and no provider API key is returned to the client.
Trip creation and every basket or itinerary write remain authenticated.
Spring uses Places API (New) with an explicit field mask.

```http
POST /places/search
Content-Type: application/json
```

```json
{
  "query": "전주 한옥마을",
  "latitude": 35.815,
  "longitude": 127.15,
  "radius_meters": 3000,
  "max_result_count": 10
}
```

The normalized response contains only fields needed by STOG:
`provider`, `external_id`, `name`, `formatted_address`, `latitude`,
`longitude`, `types`, `regular_opening_hours`, `national_phone_number`,
`website_uri`, `google_maps_uri`, photo resource names, `rating`,
`user_rating_count`, `business_status`, `open_now`, and `next_close_time`.
평점·리뷰 수·현재 영업 정보는 장소 상세 표시에서만 사용하고 검색 순위,
추천, Cell 판정에는 사용하지 않는다. A provider result is a candidate; it
becomes a canonical `place` only after the user confirms it.

Android presents candidates through the shared STOG place-card structure:
name, one normalized category, formatted address, and the bookmark-shaped
`담기` action. Provider IDs, field names, duplicated types, and other raw
payload details are not rendered directly. The result list and corresponding
map positions update together as soon as the response succeeds.

`GET /places/{placeId}` uses Place Details (New) and the same field-mask rule.
Google and any preserved legacy provider IDs are stored as separate external
references; one provider ID never overwrites another.

`POST /places/nearby` is a public read-only endpoint. Saving a candidate still
requires an authenticated trip/basket flow.

```http
POST /places/nearby
Content-Type: application/json
```

Nearby search accepts a center coordinate, radius, and place types covering
tourist attractions, culture, food, lodging, and shopping. The backend merges
eligible canonical candidates with Google Nearby Search candidates, keeps only
one candidate for the same provider identity or nearby normalized place, and
orders candidates with photo resources before candidates without photos. Both
search endpoints return the same normalized candidate shape, including
`photo_names` and `provenance`.

### 5.1 Nearby public events (F-17 phase 1)

`POST /events/nearby` is a public read-only endpoint. It reads synchronized
TourAPI festival data and never exposes `TOUR_API_SERVICE_KEY` to Android.

```http
POST /events/nearby
Content-Type: application/json
```

```json
{
  "center": {"latitude": 35.815, "longitude": 127.15},
  "radius_meters": 10000,
  "from_date": "2026-08-26",
  "to_date": "2026-11-24",
  "max_result_count": 10
}
```

The normalized response contains `provider`, `external_id`, `title`,
`venue_name`, `formatted_address`, `latitude`, `longitude`, `starts_on`,
`ends_on`, `detail_uri`, `image_uri`, and `provenance`. Results require valid
coordinates, overlap the requested date range, and are ordered by distance.
`radius_meters`, `from_date`, `to_date`, and `max_result_count` are optional;
omitted values use the canonical nearby-event tuning defaults.
Event details, notifications, and event Cell content remain outside phase 1.

Routes API calls remain backend-only. The request carries ordered origin,
destination, optional intermediate coordinates, and the approved walking or
transit mode. The response is normalized to duration, distance, and encoded
polyline data for itinerary validation; raw provider payloads are not exposed.

## 6. 공유 담기 API 초안

공유 수신은 Android에서 원본을 local 저장한 뒤 서버 동기화한다.
공유 수신 Activity가 provider 검색을 기다리지 않는다.

### 5.1 원본 저장

```http
POST /share-imports
Authorization: Bearer <stog-jwt>
Idempotency-Key: <client-generated-key>
Content-Type: application/json
```

```json
{
  "trip_id": 12,
  "source": "instagram",
  "original_url": "https://example.invalid/post",
  "raw_text": "원본에서 받은 텍스트",
  "raw_html": null,
  "attachments": [
    {
      "local_reference": "room://share/123",
      "mime_type": "image/jpeg"
    }
  ]
}
```

응답은 provider 검색 결과가 아니라 저장 상태만 빠르게 반환한다.

```json
{
  "share_import_id": 123,
  "status": "saved",
  "basket_item_id": 456,
  "message": "저장됨 · 장소 확인 중"
}
```

### 5.2 확인 대상 조회

```http
GET /trips/{tripId}/share-imports/review
```

응답에는 source별 원본, 장소 언급, 후보, 현재 상태와 사용 가능한
action을 포함한다. 후보가 없거나 여러 개여도 성공 응답으로 처리한다.

### 5.3 사용자 결정

```http
POST /share-imports/{id}/mentions/{mentionId}/decision
Idempotency-Key: <client-generated-key>
```

```json
{
  "action": "confirm",
  "candidate_id": 88,
  "manual_place": null
}
```

가능한 action:

- `confirm`: 후보를 선택하고 장소별 바구니 항목 생성
- `reject`: 후보를 버리고 원본은 보존
- `edit`: 사용자가 장소명·주소·좌표를 입력
- `defer`: 나중에 다시 확인

`confirm`과 `edit`는 사용자가 action을 확정한 뒤에만
`basket_item`과 `place`를 만들 수 있다. 하나의 Instagram 원본에서
여러 mention을 확정하면 장소별 바구니 항목을 각각 만든다.

### 5.4 상태

```text
saved
  -> processing
  -> needs_review
  -> completed

processing -> failed -> processing
needs_review -> completed
needs_review -> unresolved
```

실패해도 원본 `share_import`와 link block은 삭제하지 않는다.

## 7. 여행 가이드 AI API 초안

사용자에게 보이는 기능 이름은 **여행 가이드 AI**이며, 코드와 API
경로에서는 `travel_guide_ai`를 사용한다.

여행 가이드 AI는 기존 F-09 P1 흐름의 확장 작업안이다. 별도 기능 ID와
실행 서비스 분리는 canonical 결정 전까지 확정하지 않는다.

### 6.1 제안 요청

```http
POST /trips/{tripId}/travel-guide-ai/suggestions
```

```json
{
  "intent": "find_next_place",
  "question": "지금 쉬었다 갈 곳 있어?",
  "context": {
    "include_current_location": true,
    "include_current_time": true,
    "include_remaining_itinerary": true,
    "include_profile": true
  }
}
```

서버는 허용된 범위의 장소·일정·최신 외부 자료만 AI에 전달한다. 원본
공유 파일, token, 필요 이상의 정확한 위치, 전체 행동 이력은 보내지
않는다.

응답은 사실과 제안을 구분한다.

```json
{
  "suggestion_id": "sg_123",
  "status": "ready",
  "facts": [
    {
      "label": "운영 상태",
      "value": "현재 확인 필요",
      "checked_at": "2026-08-13T10:00:00Z"
    }
  ],
  "actions": [
    {
      "type": "add_to_itinerary",
      "place_candidate_id": 88,
      "label": "일정에 추가"
    },
    {
      "type": "replace_next",
      "label": "다음 장소와 교체"
    }
  ],
  "uncertainties": ["운영 시간은 최신 확인이 필요합니다."]
}
```

### 6.2 제안 적용

```http
POST /trips/{tripId}/travel-guide-ai/suggestions/{suggestionId}/apply
```

사용자 action을 다시 검증한 뒤 일반 일정 transaction으로 반영한다.
AI 응답 자체는 DB 일정 writer가 아니다. 적용 결과는
`itinerary_changes`에 기록한다.

## 8. 가시성과 비동기 규칙

| 요청 | 즉시 응답 | 후속 표시 |
|---|---|---|
| 공유 원본 저장 | `saved`, `basket_item_id` | 장소 확인 중 |
| 후보 검색 | `processing` 또는 review 가능 상태 | 확인 패널 |
| 후보 없음 | `needs_review` | 직접 입력 |
| AI 실행 중 | loading state | 제안 카드 또는 실패 |
| AI timeout | 현재 일정 유지 | 다시 요청·수동 편집 |
| 오프라인 | local pending | 연결 후 재동기화 |

Android 화면은 pending 항목 수와 다음 action을 표시한다. 긴 provider
검색과 AI 실행을 공유 수신 응답에 포함하지 않는다.

## 9. 미결정 계약

- `share-imports` API를 실제 별도 resource로 만들지
- 원본 첨부를 서버에 보관할지 Android local에만 둘지
- provider 후보의 서버 저장 TTL과 재사용 범위
- `travel-guide-ai` path와 AI 응답 version
- 현재 위치를 AI에 전달하는 최소 정밀도
- AI 제안 보관 기간과 사용자 삭제 범위

