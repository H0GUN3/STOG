# STOG canonical GCP deployment packet

Task 22 is a reviewable, describe-first packet. It does not create or modify
GCP resources. `plan.sh` reads current inventory and prints the exact resource
diff. `rollback.sh` prints the rollback commands unless an explicit
approval guard is supplied.

## Proposed resource boundary

| Resource | Proposed value | Scope |
| --- | --- | --- |
| Project | `team-05-504502` | Existing project |
| Region | `asia-southeast1` | Existing runnable Cloud SQL instance |
| Source instance | `stog-postgre-dev` | Read-only source boundary |
| Source database | `stog_0` | Never receive DDL/DML |
| Canonical database | `stog_canonical` | New database in the existing instance |
| Artifact Registry | `stog` | Docker images only |
| Cloud Run service | `stog-backend` | One Spring Boot API |
| Cloud Run Job | `stog-catalog-refresh` | Manual/Scheduler-triggered catalog and event refresh |
| Scheduler | `stog-catalog-refresh` | Created paused |
| Production media bucket | `stog-media` | Private Cloud Run media bucket |
| Development media bucket | `stog-media-dev` | Separate private bucket for local development |
| Runtime identity | `stog-backend-runtime` | DML on canonical schema and approved media |
| Refresh identity | `stog-catalog-refresh` | Catalog refresh only |

Production uses the private `stog-media` bucket. Local development uses the
separate private `stog-media-dev` bucket. The development signer is not used by
the Cloud runtime; Cloud runtime receives a separate identity without the
development delete role. No Android credential is added for Cloud resources.

## Secret boundary

Secret Manager stores the JWT secret, database passwords, provider credentials,
API keys, and catalog URL. Cloud Run receives Secret Manager references through
environment bindings; no secret value belongs in this repository, Docker image,
Cloud Build substitution, or Android APK.

The canonical runtime receives `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
`GCS_BUCKET`, `GCS_SIGNED_URL_TTL`, Google/Naver provider configuration, and the
approved external catalog configuration. The migrator receives its own
database user and password. The refresh Job uses the `local-import`,
`catalog-refresh`, and `event-refresh` profiles with
`STOG_CATALOG_REFRESH_RUN_ON_STARTUP=true`; it receives the
`TOUR_API_SERVICE_KEY` Secret Manager binding and has no scheduler inside the
application. Event detail pages and notifications are not part of the phase-one
refresh.

Cloud Run connects to Cloud SQL through the mounted instance connector and the
PostgreSQL socket-factory dependency in `backend/build.gradle.kts`; it does not
use a public database address or an Android credential.

## Approval packet for Task 23

Approval must name every item below before `plan.sh` output is turned into
write commands:

1. Source backup of `stog-postgre-dev` and its verification checksum.
2. Creation of only database `stog_canonical`; never `stog_0`.
3. Separate `stog_migrator` and `stog_app` users with migration/runtime
   boundaries.
4. `pg_trgm` on the canonical database only.
5. Verify existing `stog-media` remains private with uniform bucket-level
   access and public access prevention; no new bucket is created.
6. Secret names listed in `stog-gcp.env.example`, with values supplied only
   through Secret Manager.
7. Artifact Registry `stog`, the two service accounts, Cloud Run service with
   zero traffic, paused catalog Job/Scheduler, and exact IAM bindings.
8. A no-traffic window for manifest import and reconciliation.

Estimated incremental baseline is usage-based: the existing Cloud SQL instance
remains the dominant database charge; Cloud Run service scales to zero, the
Job runs only when invoked, Artifact Registry and the reused GCS bucket charge
for stored bytes, and one paused Scheduler job is a small fixed monthly charge.
Confirm the current pricing before approval rather than hard-coding a cost
promise.

## Local container proof

```text
docker build --file backend/Dockerfile --tag stog-backend:task22 .
docker run --rm --network host \
  -e SPRING_PROFILES_ACTIVE=local \
  -e DB_URL=jdbc:postgresql://127.0.0.1:5432/stog_canonical_dev \
  -e DB_USERNAME=stog_app -e DB_PASSWORD=<local-secret> \
  -e JWT_SECRET=<local-secret> \
  stog-backend:task22
```

The health probe is `GET /feed`. The local container must use only
`stog_canonical_dev`; it must never receive the `stog_0` URL. When the
storage-write profile is explicitly approved, local development uses
`GCS_BUCKET=stog-media-dev` and Cloud Run uses `GCS_BUCKET=stog-media`.

## Build and release commands

`cloudbuild.yaml` builds and pushes an image to the proposed Artifact Registry
only when a separately approved Cloud Build invocation runs it. It does not
deploy Cloud Run or change traffic. The first deployment must use
`--no-traffic`; traffic promotion and rollback are separate approvals.

The source database remains available read-only throughout. Rollback changes
traffic and pauses the Scheduler; it never rolls back Flyway, deletes source
data, or deletes shared development media.
