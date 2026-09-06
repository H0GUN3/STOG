# STOG 개발 기록 및 환경

이 문서는 STOG Android 앱과 Spring Boot backend의 개발 환경, 실행 방법,
에뮬레이터 기준, 런타임 검증 규칙을 기록한다.
제품 기능과 기술 구조의 기준은 `docs/*.yaml`이며, 이 문서가 YAML을 대신하지 않는다.

## 1. 현재 기준 요약

| 항목 | 현재 기준 |
|---|---|
| OS | Windows 11 64-bit |
| CPU architecture | x86_64 / amd64 |
| Android Studio | Quail 3 \| 2026.1.3 |
| Android Studio Runtime | JetBrains Runtime (JBR) 25.0.2 |
| Windows `java` 명령 | Oracle Java 17.0.11 |
| 언어 | Kotlin |
| UI | Jetpack Compose |
| Build script | Kotlin DSL (`*.gradle.kts`) |
| Gradle Wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin plugin | 2.2.10 |
| Minimum SDK | API 24 / Android 7.0 |
| 현재 `compileSdk` | API 37 |
| 현재 `targetSdk` | API 37 |
| 에뮬레이터 기준 | Pixel 8 / Google Play / Android API 36 |
| 앱 패키지 | `com.stog.app` |
| 실행 Activity | `com.stog.app.MainActivity` |
| Backend | Java / Spring Boot |
| Spring Boot | 4.1.0 |
| 개발 DB | PostgreSQL 18.4 / `stog_canonical_dev` |
| 자동 테스트 DB | disposable PostgreSQL / `stog_backend_test` |
| Flyway migration 사용자 | `stog_migrator` |
| Runtime DB 사용자 | `stog_app` |
| DB 주소 | `localhost:5432` (isolated development database) |
| P0/P1 Cloud runtime | local backend + `prod,gcs-write` + Cloud SQL `stog_canonical` + GCS |
| 지도 SDK | Google Maps SDK for Android |
| 지도 인증 | 제한된 Google Maps Android API key + package/SHA-1 등록 |

### JBR와 Gradle 버전 구분

여기서 말하는 JDK 25는 **JBR 25.0.2**를 뜻한다. Gradle 버전은 **9.5.0**이다.
Android Studio와 Android Gradle은 Android Studio가 설정한 JBR을 기준으로 실행한다.
Windows PATH의 `java`가 Java 17을 가리키더라도 이를 임의로 바꾸지 않는다.

에뮬레이터 API 36과 프로젝트의 현재 `compileSdk`/`targetSdk` API 37은 서로 다른 기준이다.
SDK Platform 37은 빌드에 필요하고, Pixel 8 API 36 이미지는 런타임 검증 기준이다.
이 값들은 명시적인 요청 없이 바꾸지 않는다.

## 1.1 Backend 로컬 DB

로컬 canonical 개발 DB는 기존 local PostgreSQL 18.4 cluster 안의 격리된
`stog_canonical_dev`이며 Spring Boot backend만 접근한다. Android 클라이언트에서
PostgreSQL로 직접 연결하지 않는다.

Spring Boot는 runtime datasource와 Flyway migration datasource를 분리한다.
두 URL은 같은 isolated development database를 가리키며, legacy local `stog`나
Cloud SQL URL을 넣지 않는다.

```text
MIGRATOR_DB_URL=jdbc:postgresql://localhost:5432/stog_canonical_dev
MIGRATOR_DB_USERNAME=stog_migrator
MIGRATOR_DB_PASSWORD=<local password>
DB_URL=jdbc:postgresql://localhost:5432/stog_canonical_dev
DB_USERNAME=stog_app
DB_PASSWORD=<local password>
```

`application-local.yml`의 `spring.flyway`는 `MIGRATOR_DB_*`만 사용하고,
runtime datasource는 `DB_*`만 사용한다. 비밀번호는 저장소,
`secrets.properties`, APK에 기록하지 않는다.

### Automated backend test database

자동 backend test는 개발 DB를 재사용하지 않는다. test classpath의
`application.properties`가 direct IDE/JUnit launch에도 active profile을 `test`
하나로 고정하고 Gradle `test` task도 같은 값을 강제한다. runtime datasource와 Flyway는 모두
`127.0.0.1:5432/stog_backend_test`의 `stog_backend_test` identity만 사용한다.
ignored `backend/.env.test`에는 다음 password 변수만 둔다.

```text
TEST_DB_PASSWORD=<disposable test role password>
```

Spring EnvironmentPostProcessor guard는 datasource나 Flyway가 SQL connection을
시작하기 전에 active profile, runtime URL/identity, Flyway URL/identity의 exact
allowlist를 검사한다. `local`, `cloud`, `prod`, `gcs-write`, `cloud-write`,
`local-import`, `catalog-refresh`, URL alias·query parameter·다른 loopback host,
malformed URL은 모두 거부한다. 따라서 stale `SPRING_PROFILES_ACTIVE`나
`DB_URL`/`MIGRATOR_DB_URL`은 test target을 바꾸지 못하며 direct runner에서
profile override가 새면 enforce flag가 bootstrap 전에 거부한다.

`stog_backend_test` database와 role은 test-only 이름으로 임시 생성하고 suite가
끝나거나 중단되면 남은 session을 종료한 뒤 database와 role을 제거한다.
`stog_canonical_dev`, local `stog`, Cloud SQL `stog_canonical`, `stog_0`에는 test
migration, seed, truncate, clean 또는 application write를 실행하지 않는다.

```powershell
cmd.exe /d /c "gradlew.bat -p backend test"
```

### Isolated local PostgreSQL role and extension boundary

The canonical local target is the disposable `stog_canonical_dev` database in
the already-running PostgreSQL 18.4 cluster on `127.0.0.1:5432`. It is separate
from the legacy local `stog` database; do not move, stop, reconfigure, or modify
the cluster itself, and do not use `stog` as a substitute. C-drive persistence
is deferred as an environment optimization, not a requirement for this boundary.

A local PostgreSQL administrator must provision the database and roles before
local startup. The administrator authenticates without placing a password in a
command or log, and uses `\password` interactively for both login roles. Keep
those credentials only in the ignored `backend/.env` file. No Cloud SQL profile,
Cloud SQL database, GCS resource, or Cloud Run resource participates in this
setup.

The administrator creates only `stog_canonical_dev`; `stog_migrator` owns its
`public` schema and can run Flyway migrations, while `stog_app` receives DML
privileges only. Run the following as the administrator, without passwords:

```sql
CREATE ROLE stog_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
\password stog_migrator
CREATE ROLE stog_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
\password stog_app
CREATE DATABASE stog_canonical_dev;
\connect stog_canonical_dev
ALTER SCHEMA public OWNER TO stog_migrator;
REVOKE ALL ON DATABASE stog_canonical_dev FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE stog_canonical_dev TO stog_migrator, stog_app;
GRANT CREATE ON DATABASE stog_canonical_dev TO stog_migrator;
GRANT USAGE ON SCHEMA public TO stog_app;
ALTER DEFAULT PRIVILEGES FOR ROLE stog_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO stog_app;
ALTER DEFAULT PRIVILEGES FOR ROLE stog_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO stog_app;
```

Connect as `stog_migrator` to run `CREATE EXTENSION pg_trgm;`, then the
administrator immediately runs `REVOKE CREATE ON DATABASE stog_canonical_dev
FROM stog_migrator;`. Schema ownership remains with `stog_migrator` for Flyway.
Verify the completed boundary with real loopback `psql` probes: the administrator
runs `SHOW server_version` and `SHOW data_directory`; `stog_migrator` creates and
removes a disposable table; grant that table's `SELECT`, `INSERT`, `UPDATE`, and
`DELETE` privileges and its sequence privileges to `stog_app`; perform the four
DML operations as `stog_app` in one rolled-back transaction; then prove both
`CREATE TABLE forbidden_runtime_write(id int)` and `CREATE EXTENSION hstore` fail
as `stog_app`. Confirm `pg_trgm` with
`SELECT extversion FROM pg_extension WHERE extname = 'pg_trgm';`.

Back up only the isolated development database to an administrator-approved
local backup path, then list it before a restore proof:

`LOCAL_BACKUP_DIR`가 설정되지 않은 agent-owned canonical 정제에서는
`C:\Users\GUN\STOG-backups`를 기본 로컬 경로로 사용한다. 저장소나 Cloud
bucket에는 dump를 두지 않는다.

```sh
pg_dump -Fc -h 127.0.0.1 -p 5432 -U stog_migrator -d stog_canonical_dev \
  -f "$LOCAL_BACKUP_DIR/stog_canonical_dev-before-change.dump"
pg_restore --list "$LOCAL_BACKUP_DIR/stog_canonical_dev-before-change.dump"
```

For a restore proof, the administrator creates a disposable
`stog_canonical_restore_verify` database on port `5432`, runs
`pg_restore --exit-on-error` into it, verifies it opens, and drops it. With
role-specific local URLs and credentials in ignored `backend/.env`, start the
backend with `cmd.exe /d /c "gradlew.bat -p backend bootRun"`.

Canonical catalog cleaning is agent-owned, complete, and restricted to
`.omo/plans/stog-canonical-db-cleaning.md`. Its target is only local
`stog_canonical_dev`. P0/P1 tasks outside that plan must not normalize, merge,
delete, quarantine, or otherwise clean catalog rows. They preserve the cleaned
catalog until the named forward-migration gates; legacy local `stog` and Cloud
`stog_0` remain protected.

기본 Spring profile은 `local`이며 `backend/.env`를 읽는다.
P0/P1에서는 아래 canonical Cloud runtime처럼 로컬 backend에 `prod,gcs-write`를
명시한다. 후속 Cloud Run 배포가 승인되면 `.env` 파일 대신 Cloud Run 환경변수
또는 Secret Manager가 같은 변수 이름을 주입한다. `prod` profile로 로컬 개발 DB를
연결하거나 `local` profile로 Cloud SQL을 연결하지 않는다.

Legacy Cloud SQL `stog_0` 확인은 보호된 읽기 전용 `cloud` profile로만 수행한다.
Cloud SQL Auth Proxy가 `team-05-504502:asia-southeast1:stog-postgre-dev`를 로컬
`5433` 포트에 연결한 상태에서 다음 변수를 `backend/.env`로 주입한다.

```text
CLOUD_DB_URL=jdbc:postgresql://localhost:5433/stog_0
CLOUD_DB_USERNAME=Stobee_0
CLOUD_DB_PASSWORD=<cloud password>
```

평상시 인증 개발과 migration 검증에는 `cloud` profile을 활성화하지 않는다.
`cloud` profile은 legacy source 전용이므로 migration이나 데이터를 변경하지 않는다.

### P0/P1 canonical Cloud runtime boundary

P0/P1 통합 실행은 backend process를 개발 PC에서 로컬로 시작하되
`SPRING_PROFILES_ACTIVE=prod,gcs-write`를 사용한다. 이 runtime은 Cloud SQL
`stog_canonical`과 승인된 GCS bucket만 사용한다. `cloud` profile 또는 Cloud SQL
`stog_0`을 canonical runtime 대체 대상으로 사용하지 않는다. Cloud Run traffic,
최소 instance, secret, billing은 이 단계에서 변경하지 않는다.

Migration은 runtime 시작과 분리한다. 기능 필수 forward migration은 계획에 지정된
gate에서 migrator identity로만 적용한다. Todo 4에서는 V17 Preference traits 제약
교체와 V10 restore safety schema qualification을 새 forward migration으로 처리한다.
적용된 Flyway V1부터 V17까지 수정하지 않으며 Google Places fallback 결과를 새
canonical catalog로 일괄 적재하지 않는다. 현재 `places`, `place_details`,
`catalog_sources`, `license_snapshots`, `place_source_records`,
`place_source_images` 구조와 정제된 관광 catalog 및 local-first 검색을 P0/P1 동안
유지하고 더 넓은 저장 구조 재설계는 이후로 미룬다.

Cloud SQL에 forward migration을 적용하기 전에는 Flyway `info` 또는 동등한 읽기
전용 확인으로 schema와 migration history 충돌을 검토한다. runtime 시작 전에는
해당 gate의 migration 완료를 다시 읽기 전용으로 확인한다.

모든 DB 변경은 `backend/src/main/resources/db/migration`의 Flyway migration으로
관리한다. 로컬 PostgreSQL에서 먼저 migration과 `ddl-auto=validate`를 통과시킨
뒤 같은 애플리케이션 artifact의 동일 migration을 Cloud SQL에 적용한다.
기존 Cloud DB를 초기화하거나 적용된 migration 파일을 수정하지 않는다.

DB 기술 계약은 다음과 같다.

- ORM: Spring Data JPA / Hibernate
- migration: Flyway
- schema management: Flyway 우선
- Hibernate `ddl-auto`: `validate`
- driver: PostgreSQL JDBC Driver

## 1.2 Android 지도

STOG의 기본 지도 SDK는 Google Maps SDK for Android다.

- Android에는 package와 debug/release SHA-1로 제한한 Google Maps Android
  API key만 사용한다.
- Google Places와 Google Routes API key는 Backend 환경변수에만 둔다.
- Kakao Native App Key는 Kakao 로그인과 공유 입력 인식에만 사용한다.
- STOG cell은 H3 계산 결과를 Google 지도 위에 표시한다.
- 지도·장소 검색·동선 provider를 Android에서 직접 호출하지 않고,
  장소 검색과 동선 계산은 Spring Boot API를 통해 수행한다.

## 2. 문서 기준과 개발 기록

### 작업 시작 전 읽는 순서

1. `AGENTS.md`
2. `docs/glossary.yaml`
3. `docs/product.yaml`
4. `docs/architecture.yaml`
5. `docs/conventions.yaml`
6. `docs/pitfalls.yaml`
7. 이 문서 (`DEVELOPMENT.md`)

`AGENTS.md`와 YAML 문서의 지시가 이 문서와 충돌하면 YAML 문서가 우선한다.
개발환경이나 실행 명령의 변경은 이 문서에 기록하고, 제품 동작·스키마·API의 변경은 해당 YAML도 함께 갱신한다.

### 작업 시작 전 운영 gate

화면 작업을 바로 시작하지 않는다. `docs/conventions.yaml`의
`development_workflow`를 기준으로 다음 순서를 지킨다.

1. 작업을 화면이 아닌 DB부터 QA까지 연결된 기능 단위로 정의한다.
2. `app`, `backend`, `docs`, migration, test 전체에서 같은 의미의 구현과
   caller를 검색한다. 이름이 다르더라도 `TripService`, `TripController`,
   `TripRepository`, `TripDetail`, `Itinerary`, `Schedule`, `Stop`, `Place`,
   `Bookmark`, `Photo`, `User` 같은 관련 개념을 함께 확인한다.
3. 기존 관련 코드·DB·재사용 항목·신규 필요 영역·신규 API·신규 DB를 담은
   Impact Analysis를 먼저 출력한다.
4. 새 Controller, Service, Repository, Entity, DTO, API, DB table,
   ViewModel, Android Repository가 필요하면 기존 구현으로 부족한 이유,
   검색한 코드, 신규 필요성, 책임 비중복 근거를 기록한다.
5. UI 요구는 API response, DB relation, Service, Android model/Repository,
   ViewModel까지 추적한 뒤 마지막 표현 계층으로 구현한다. 값이나 네트워크
   성공을 UI에 하드코딩·fixture 처리하지 않는다.

### 기능 완료 보고

UI만 구현한 상태는 완료가 아니다. 최소한 아래 계층별 상태와 증거를 보고한다.

```text
기능:
Android UI:
Android ViewModel:
Android Repository/Data:
HTTP API/Controller:
Backend Service:
Backend Repository/JPA/SQL:
DB:
관련 데이터·권한·소유권·cascade:
QA:
```

필요한 계층이 연결되지 않았으면 완료 대신 `pending` 또는 `blocked`로
보고하고 마지막 연결 계층과 남은 작업을 적는다. 실제 기기 검증은 아래
Android runtime 절차를 따르며 host/backend 확인과 기기 실행 결과를
분리해 기록한다.

### 현재 저장소 중복 조사

이 규칙을 적용한 뒤 다음 기능 개발 전에 현재 저장소를 한 번 조사한다.
DB, Entity, Repository, Service, Controller, DTO, Android API,
Android Repository, ViewModel, Compose를 대상으로 같은 의미의 중복을
찾고 각 항목을 `KEEP`, `MERGE`, `DELETE`, `REFACTOR`, `NEW`로 분류한다.
`NEW`는 기존 구현으로 해결할 수 없다는 근거와
`new_component_gate` 설명이 있을 때만 허용한다.

### 기록 원칙

환경, 에뮬레이터, 빌드 실패와 해결 방법, 런타임 검증 결과, 새 개발 도구를 기록한다.
결정이 필요한 제품·아키텍처 사항은 임의로 닫지 않고 해당 YAML의 `open_items`에 남긴다.

기록 형식:

```md
### YYYY-MM-DD — 주제
- 관찰:
- 변경:
- 검증:
- 남은 사항:
```

### 현재 기록

#### 2026-08-19 — 지도 바텀 시트 anchor

- 변경: Compose BOM 2026.02.01이 관리하는 `androidx.compose.foundation:foundation`을
  직접 선언해 `AnchoredDraggable`로 지도 바텀 시트의 Hidden, Default, Expanded
  세 상태를 구현했다.
- 목적: 화면 높이 상수 대신 실제 검색바와 `Scaffold` content 영역을 측정해
  시트 anchor를 계산한다.
- 중복 여부: 기존 Compose BOM의 Foundation 모듈을 직접 사용한 것이며,
  새 외부 라이브러리나 별도 버전은 추가하지 않았다.
- 확인: `:app:testDebugUnitTest`, `:app:installDebug`, Pixel 8 API 36
  에뮬레이터의 드래그와 Back 전이를 실행한다.

#### 기준 환경

- Windows 11 64-bit, x86_64 / amd64
- Android Studio Quail 3 (2026.1.3), JBR 25.0.2
- Pixel 8, Google Play, Android API 36 에뮬레이터
- Gradle Wrapper 9.5.0

#### Known environment issue

이 Windows 환경에서는 Java/Gradle HTTPS 연결이 기본 IP 스택에서 timeout될 수 있다.
Gradle JVM 네트워크에는 IPv4를 우선 사용한다.

현재 `gradle.properties`의 다음 설정은 이 환경 문제를 해결하기 위한 것이므로
불필요해 보인다는 이유로 삭제하지 않는다.

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true
```

최초 Gradle Wrapper 다운로드 때는 PowerShell에서 다음을 사용한다.

```powershell
$env:JAVA_OPTS = "-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false"
.\gradlew.bat --version
Remove-Item Env:JAVA_OPTS
```

#### 2026-08-17 — 스플래시와 로그인 화면

- 관찰: `docs/ds_rfs/splash_screen_01.png`와
  `splash_screen_02.png`는 노란 단색 화면 뒤 아이보리 배경의 중앙
  STOG 로고 화면으로 이어진다.
- 변경: 기존 `ui/theme`에 레퍼런스 색상·타이포그래피를 반영하고,
  `feature/auth`에 `SplashScreen`과 `LoginScreen`을 추가했다. 로고는
  `docs/ds_rfs/stog_app_icon.png` 원본을 Android resource로 사용한다.
- Android launcher와 round launcher icon은 제공 PNG를 source로 삼아 generated
  adaptive icon resource로 연결한다. 외곽 흰 배경은 제거하고 로고만
  foreground로 분리하며, 배경 전체는 source의 STOG 메인 컬러로 채운다.
  임의로 다시 그린 icon은 사용하지 않는다.
- 검증: Pixel 8 API 36 에뮬레이터에서 스플래시 이후 로그인 화면,
  이메일·비밀번호 입력, 로그인 서버 안내 메시지를 확인했다.
- 남은 사항: 실제 이메일 로그인 API와 회원가입 API는 Spring Boot
  backend 구현 후 연결한다. Kakao SDK callback은 연결했지만 token
  검증과 자체 JWT 발급은 backend 경계에서 처리한다.

## 3. 개발환경 설정

### Android Studio

1. Android Studio에서 저장소 루트를 연다.
2. `Settings > Build, Execution, Deployment > Build Tools > Gradle`에서 Gradle JDK가 JBR 25.0.2인지 확인한다.
3. Gradle distribution은 프로젝트의 Wrapper를 사용한다.
4. SDK Manager에서 빌드에 필요한 Android SDK Platform 37을 설치한다.
5. 실행과 런타임 검증에는 아래의 Pixel 8 API 36 에뮬레이터를 사용한다.

Android Studio가 사용하는 JBR와 Windows PATH의 Java는 역할이 다르다.
개발환경 문제를 해결하기 위해 `local.properties`, 시스템 PATH, 로컬 SDK 경로를 임의로 수정하지 않는다.

### 에뮬레이터

현재 기준:

- Device: Pixel 8
- Image: Google Play
- API: Android 36

에뮬레이터 설정이나 시스템 이미지를 바꿀 필요가 생기면 기존 기준과의 차이,
영향받는 기능, 대체 검증 방법을 먼저 기록하고 사용자의 승인을 받는다.

### 환경변수

| 이름 | 용도 | 규칙 |
|---|---|---|
| `JAVA_HOME` | Java 실행 위치 | Android Studio가 선택한 JBR을 우선한다. 시스템 값을 임의로 바꾸지 않는다. |
| `JAVA_OPTS` | 최초 Wrapper 다운로드의 IPv4 우선 설정 | 위의 PowerShell 명령처럼 일시적으로 사용하고 필요하면 제거한다. |
| API key 또는 secret 환경변수 | 외부 서비스 인증 | 이름과 용도만 이 문서에 기록하고 값은 저장소·문서·로그에 기록하지 않는다. |

새 환경변수가 필요하면 다음을 기록한다.

- 변수 이름
- 사용하는 기능
- 값을 주입하는 로컬 또는 CI 위치
- 저장소에 값을 남기지 않는 방법
- 설정 후 확인 명령

로컬 키는 실행 위치에 따라 분리한다.

Android 전용 파일은 저장소 루트의 `secrets.properties`다.
이 파일에는 APK에 주입해야 하는 앱 공개 설정만 둔다.

- `KAKAO_NATIVE_APP_KEY`: Android 앱에 주입하는 공개 Native App Key로 Kakao 소셜 로그인에만 사용한다. Kakao는 공유 담기 원본 carrier 인식에는 남지만 이 경로는 키를 사용하지 않으며 Kakao 지도 또는 Local API에는 사용하지 않는다.
- `MAPS_API_KEY`: package와 debug/release SHA-1로 제한한 Android Google Maps SDK
  키. `app/build.gradle.kts`가 `GOOGLE_MAPS_API_KEY` manifest placeholder로 전달하고,
  `com.google.android.geo.API_KEY` metadata가 이를 받는다.
- `STOG_API_BASE_URL`: Android가 호출할 Spring Boot API 주소. APK 빌드 시
  반드시 `-PstogApiBaseUrl=<reachable backend URL>`로 지정하거나
  `secrets.properties`에 설정한다. 지정하지 않으면 APK를 만들지 않는다.
  실기기 APK의 값은 현재 살아 있는 public HTTPS backend 또는 Quick Tunnel
  hostname이어야 한다. `http://10.0.2.2:8080`, localhost, `127.0.0.1`,
  LAN 주소는 실기기 APK에서 거부된다.

백엔드 전용 파일은 `backend/.env`다. 이 파일은 `.gitignore`에 등록하며,
변수 이름만 담은 추적 가능한 템플릿은 `backend/.env.example`에 둔다.

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `CLOUD_DB_URL`
- `CLOUD_DB_USERNAME`
- `CLOUD_DB_PASSWORD`
- `JWT_SECRET`
- `GOOGLE_WEB_CLIENT_ID`
- `GOOGLE_PLACES_API_KEY`: Places API (New) 전용 backend key. Android에 주입하지 않는다.
- `GOOGLE_ROUTES_API_KEY`: Routes API 전용 backend key. Android에 주입하지 않는다.
- `NAVER_CLIENT_ID`: 백엔드가 소유하는 네이버 OAuth Client ID
- `NAVER_CLIENT_SECRET`: 백엔드 전용 네이버 OAuth Client Secret
- `KMA_SHORT_TERM_FORECAST_SERVICE_KEY`
- `SME_COMMERCIAL_AREA_SERVICE_KEY`
- `TOUR_PHOTO_SERVICE_KEY`
- `TOUR_API_SERVICE_KEY`

기존 `secrets.properties`에 남아 있는 백엔드 전용 키는 값을
`backend/.env`에 수동으로 복사한 뒤, 복사 확인 후 원본 파일에서 삭제한다.
실제 키 값은 문서, Git, 로그, APK에 기록하지 않는다.

`.env` 파일은 로컬 편의를 위한 평문 파일이며 암호화 저장소가 아니다.
운영 환경에서는 Cloud Run 환경변수 또는 Secret Manager를 사용한다.

승인된 공공 API의 endpoint와 operation은 `docs/architecture.yaml`의
`external_apis`를 기준으로 한다. Android는 공공 API를 직접 호출하지 않는다.

### Google Routes 외부 키 gate

`POST /trails/compute`의 backend client와 `GOOGLE_ROUTES_API_KEY` 환경변수
경계만 준비된 상태다. Google Cloud credential은 이 저장소에서 만들거나 바꾸지 않는다.
Routes 호출을 열기 전 프로젝트 소유자는 다음을 외부에서 확인해야 한다.

1. billing이 연결된 Google Cloud project에서 Google Routes API를 활성화한다.
2. Android Maps 키와 분리된 backend-only key에 Routes API 제한을 적용한다.
3. local은 `backend/.env`, 운영은 Cloud Run 환경변수 또는 Secret Manager에
   `GOOGLE_ROUTES_API_KEY`를 주입한다.
4. 제한된 backend 환경에서 walking과 transit 요청을 한 번씩 확인한다.

키가 비어 있으면 backend는 네트워크 호출 전에
`GOOGLE_ROUTES_NOT_CONFIGURED`를 반환한다. 외부 API의 인증, 권한, quota, 또는
가용성 실패는 `GOOGLE_ROUTES_FAILED`로 반환한다. 이 gate가 확인되기 전에는
Routes를 Android 기능 또는 배포 준비 완료로 표시하지 않는다.

Naver Android App Link 로그인도 deferred 상태다. verified App Link domain,
네이버 개발자센터 HTTPS callback, `STOG_APP_LINK_URI`를 함께 확인하기 전에는
Android 화면과 intent handler를 활성화하지 않는다.

## 4. Build 및 Run 명령

명령은 Windows PowerShell과 프로젝트 루트 기준이다.

### Wrapper 확인

```powershell
.\gradlew.bat --version
```

### Build

```powershell
# 실기기용 Debug APK 빌드: endpoint를 생략하면 Gradle이 실패한다.
$env:STOG_API_BASE_URL = "https://<reachable-backend-or-tunnel>"
.\gradlew.bat :app:assembleDebug -PstogApiTarget=phone -PstogApiBaseUrl=$env:STOG_API_BASE_URL

# JVM 단위 테스트
.\gradlew.bat :app:testDebugUnitTest -PstogApiTarget=phone -PstogApiBaseUrl=$env:STOG_API_BASE_URL

# Android runtime 검증 단계
.\gradlew.bat :app:connectedDebugAndroidTest -PstogApiTarget=phone -PstogApiBaseUrl=$env:STOG_API_BASE_URL
```

`app/build.gradle.kts`는 endpoint가 없을 때 자동 대체하지 않는다.
기본 target은 `phone`이며, 실기기 빌드는 반드시 현재 살아 있는 HTTPS
hostname을 받아야 한다. 따라서 오래된 Quick Tunnel 주소나
`10.0.2.2:8080`을 넣은 실기기 APK는 생성되지 않는다.
`127.0.0.1` loopback endpoint는 target과 무관하게, 특히
`127.0.0.1:8080`은 절대 허용되지 않는다. Quick Tunnel을
재시작해 hostname이 바뀌면 `secrets.properties`의 무시되는
`STOG_API_BASE_URL`을 새 주소로 바꾸고 반드시 다시 빌드한다.
에뮬레이터를 사용할 때만 다음처럼 대상과 endpoint를 함께 명시한다.

```powershell
.\gradlew.bat :app:assembleDebug -PstogApiTarget=emulator -PstogApiBaseUrl=http://10.0.2.2:8080
```

### Install 및 실행

이 절은 Android Studio 또는 PowerShell에서 Android runtime을 실행·검증하는
절차다. 실행 결과는 host build와 별도의 기기 검증 증거로 기록한다.

Android Studio에서 `app` 실행 구성을 선택하고 Pixel 8 API 36 emulator를
선택한 뒤 Run을 누르는 방법을 우선한다.

PowerShell에서 실행할 때:

```powershell
# 기기 연결 확인
adb devices

# 실기기 Debug APK 빌드·설치: 현재 HTTPS endpoint를 반드시 전달한다.
$env:STOG_API_BASE_URL = "https://<reachable-backend-or-tunnel>"
.\gradlew.bat :app:installDebug -PstogApiTarget=phone -PstogApiBaseUrl=$env:STOG_API_BASE_URL

# MainActivity 실행
adb shell am start -n com.stog.app/.MainActivity
```

`assembleDebug`가 성공해도 앱의 Android runtime 기능이 검증된 것은 아니다.
에뮬레이터 또는 실제 기기에서 실행한 결과를 별도로 기록한다.

## 5. Android runtime 검증

Android runtime과 기기 관찰은 host 검증과 별도의 증거다. `adb`, APK 설치,
`connectedDebugAndroidTest`, 기기 screenshot 결과는 실제 실행 출력과 함께
기록한다. Host unit test, lint, assemble, source inspection, backend HTTP/GCS
driver 결과는 기기 runtime 결과와 구분한다.

다음 기능은 owner 실행 목록에서 별도로 확인한다.

- 위치/GPS
- Camera
- 권한
- Navigation
- Notification
- Background task
- Foreground service
- Map
- 파일/사진 저장

기기 실행 목록에는 setup, action, expected result, failure recovery와 함께 다음을 남긴다.

- 사용한 기기 또는 emulator
- Android API level
- 실행한 사용자 흐름
- 권한 허용·거부 결과
- 백그라운드 전환과 재진입 결과
- 로그 또는 화면에서 확인한 결과
- 검증하지 못한 항목과 그 이유

Host-side 검증을 통과한 것은 기기 runtime 검증 성공을 뜻하지 않는다.
기기 전용 결과는 별도 상태로 명시한다.

## 6. Dependency Policy

새로운 dependency는 필요성이 명확할 때만 추가한다.

dependency 추가 전에:

1. Android/Jetpack 표준 API로 해결 가능한지 확인한다.
2. 프로젝트에 이미 존재하는 dependency로 해결 가능한지 확인한다.
3. 직접 구현이 과도하게 복잡한지 판단한다.

새 dependency를 추가하는 경우:

- 사용 목적을 명시한다.
- 어느 기능에서 사용하는지 명시한다.
- 기존 dependency와 역할이 중복되지 않는지 확인한다.
- 버전과 검증 명령을 이 문서의 개발 도구 목록에 기록한다.

단순 편의를 위해 dependency를 추가하지 않는다.

## 7. Do Not Modify

다음 파일/디렉터리는 명시적인 요청이 없는 한 수정하지 않는다.

- `local.properties`
- `.idea/`
- `.gradle/`
- `.kotlin/`
- `build/`
- `app/build/`
- `*.keystore`
- `*.jks`
- `secrets.properties`
- 환경변수 및 로컬 SDK 경로

## 8. Protected Configuration

다음 항목은 사용자의 명시적인 요청 없이 변경하지 않는다.

- Gradle Wrapper 버전
- Android Gradle Plugin (AGP) 버전
- Kotlin 버전
- `compileSdk` / `targetSdk` / `minSdk`
- `applicationId` / package name
- `signingConfig` 및 keystore 관련 설정
- `buildTypes` / `productFlavors`
- `AndroidManifest.xml`의 권한 추가·삭제
- Network Security Configuration
- Gradle JVM/JDK 설정
- 프로젝트 모듈 구조
- 기존 public API/DTO 계약
- 데이터베이스 schema 및 migration
- CI/CD 설정

설정 변경이 필요하다고 판단되는 경우:

1. 왜 필요한지 설명한다.
2. 영향을 받는 영역을 제시한다.
3. 기존 방식으로 해결할 수 있는지 먼저 검토한다.
4. 사용자의 명시적인 승인 후 변경한다.

문서만 추가하거나 갱신하는 경우에도 위 설정을 함께 정리한다는 이유로 값을 바꾸지 않는다.

## 9. 개발 도구 목록

새로운 개발 도구 또는 dependency를 도입할 때 버전, 목적, 설정 방법, 확인 방법,
기존 도구와의 중복 여부를 이 표에 기록한다.

| 도구 | 버전 | 목적 | 설정·확인 |
|---|---:|---|---|
| Android Studio | Quail 3 / 2026.1.3 | Android IDE | Gradle JDK가 JBR 25.0.2인지 확인 |
| JetBrains Runtime | 25.0.2 | Android Studio/Gradle JVM | Android Studio Gradle JDK 설정 확인 |
| Gradle Wrapper | 9.5.0 | 프로젝트 build | `.\gradlew.bat --version` |
| Android Gradle Plugin | 9.3.1 | Android build plugin | `gradle/libs.versions.toml` 확인 |
| Kotlin | 2.2.10 | 앱 언어 및 Compose plugin | `gradle/libs.versions.toml` 확인 |
| Jetpack Compose | BOM 2026.02.01 | UI | `app/build.gradle.kts` 및 version catalog 확인 |
| Kakao SDK for Android | Login 2.24.0 | Kakao Login | `secrets.properties`의 Native App Key, Kakao Developers package/hash 등록, `:app:assembleDebug` |
| Google Maps SDK for Android | 19.0.0 | 지도 표시 | package/SHA-1로 제한한 `MAPS_API_KEY`, `:app:processDebugMainManifest` |
| Android Emulator | Pixel 8 / Google Play / API 36 | runtime 검증 | Android Studio Device Manager에서 실행 |

도구 추가 기록 형식:

```md
### 도구 이름 — 버전
- 목적:
- 사용하는 기능:
- 설정:
- 확인:
- 기존 도구와 중복 여부:
- 제거 또는 교체 조건:
```

## 10. 완료 판단

작업 유형에 따라 다음 증거를 모두 확인한다.

| 작업 유형 | 최소 확인 |
|---|---|
| Kotlin/Compose 일반 변경 | 관련 build 또는 test |
| Android runtime 기능 변경 | agent: build/test/source inspection; owner: emulator 또는 실제 기기 실행 |
| 권한·위치·카메라·지도·저장 변경 | agent: host-side 확인; owner: 대상 사용자 흐름 + 권한/상태 전환 확인 |
| dependency 추가 | build/test + 사용 기능 실행 + 이 문서 기록 |
| 환경 설정 변경 | 변경 이유·영향·승인·복구 방법 기록 |

`Gradle build 성공`은 컴파일과 패키징 증거일 뿐이며 Android runtime 완료 증거가 아니다.

## 11. Android 폴더와 기능 개발 규칙

현재 앱은 단일 `app` 모듈로 유지한다. 기능이 많다는 이유만으로
멀티 모듈을 추가하지 않는다. 상세 목표 구조는
`docs/android-architecture.md`에 둔다.

소스 폴더는 기능 경계를 우선한다.

```text
com.stog.app/
├── navigation/
├── core/
│   ├── model/
│   ├── database/
│   ├── network/
│   ├── storage/
│   ├── location/
│   └── ui/
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
└── ui/theme/
```

기능 구현 순서:

1. `docs/user-flows.md`에서 사용자 이름과 상태를 확인한다.
2. API·Room 계약을 확인한다.
3. `Screen`, `ViewModel`, `UiState`를 기능 폴더에 둔다.
4. Android 입력 adapter와 순수 변환 로직을 분리한다.
5. 필요한 경우에만 `core`로 공통화한다.
6. Agent는 host-side 상태와 source를 확인하고, owner가 Android runtime 사용자 흐름을 확인할 실행 목록을 작성한다.

공유 담기 수신부는 원본 저장과 즉시 상태 표시만 담당한다. provider
검색과 여행 가이드 AI 호출을 수신 Activity에서 동기 실행하지 않는다.
