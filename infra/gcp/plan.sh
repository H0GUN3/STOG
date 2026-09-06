#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-team-05-504502}"
REGION="${REGION:-asia-southeast1}"
SQL_INSTANCE="${SQL_INSTANCE:-stog-postgre-dev}"
SOURCE_DATABASE="${SOURCE_DATABASE:-stog_0}"
CANONICAL_DATABASE="${CANONICAL_DATABASE:-stog_canonical}"
ARTIFACT_REPOSITORY="${ARTIFACT_REPOSITORY:-stog}"
IMAGE_NAME="${IMAGE_NAME:-stog-backend}"
SERVICE_NAME="${SERVICE_NAME:-stog-backend}"
JOB_NAME="${JOB_NAME:-stog-catalog-refresh}"
SCHEDULER_NAME="${SCHEDULER_NAME:-stog-catalog-refresh}"
MEDIA_BUCKET="${MEDIA_BUCKET:-stog-media}"
DEV_BUCKET="${DEV_BUCKET:-stog-media-dev}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

command -v gcloud >/dev/null || die "gcloud is required"
[[ "$CANONICAL_DATABASE" != "$SOURCE_DATABASE" ]] ||
  die "canonical database must not equal SOURCE_DATABASE"
[[ "$CANONICAL_DATABASE" != "stog_0" ]] ||
  die "canonical database stog_0 is forbidden"
[[ "$MEDIA_BUCKET" != "$DEV_BUCKET" ]] ||
  die "MEDIA_BUCKET and DEV_BUCKET must be different buckets"

describe() {
  local label="$1"
  shift
  printf '\n[%s]\n' "$label"
  if "$@"; then
    :
  else
    printf 'absent or unavailable (no mutation performed)\n'
  fi
}

printf 'STOG GCP deployment plan (describe-only)\n'
printf 'project=%s region=%s source_instance=%s source_database=%s\n' \
  "$PROJECT_ID" "$REGION" "$SQL_INSTANCE" "$SOURCE_DATABASE"
printf 'canonical_database=%s media_bucket=%s dev_bucket=%s\n' \
  "$CANONICAL_DATABASE" "$MEDIA_BUCKET" "$DEV_BUCKET"

describe "project" \
  gcloud --quiet projects describe "$PROJECT_ID" --format='value(projectId)'
describe "source Cloud SQL instance" \
  gcloud --quiet sql instances describe "$SQL_INSTANCE" \
    --project="$PROJECT_ID" --format='value(name,region,databaseVersion,state)'
describe "source database" \
  gcloud --quiet sql databases describe "$SOURCE_DATABASE" \
    --instance="$SQL_INSTANCE" --project="$PROJECT_ID" --format='value(name)'
describe "proposed canonical database" \
  gcloud --quiet sql databases describe "$CANONICAL_DATABASE" \
    --instance="$SQL_INSTANCE" --project="$PROJECT_ID" --format='value(name)'
describe "production media bucket" \
  gcloud --quiet storage buckets describe "gs://$MEDIA_BUCKET" \
    --format='value(name,location,iamConfiguration.uniformBucketLevelAccess.enabled,iamConfiguration.publicAccessPrevention)'
describe "development media bucket" \
  gcloud --quiet storage buckets describe "gs://$DEV_BUCKET" \
    --format='value(name,location,iamConfiguration.uniformBucketLevelAccess.enabled,iamConfiguration.publicAccessPrevention)'
describe "artifact repository" \
  gcloud --quiet artifacts repositories describe "$ARTIFACT_REPOSITORY" \
    --location="$REGION" --project="$PROJECT_ID" --format='value(name,format)'
describe "Cloud Run service" \
  gcloud --quiet run services describe "$SERVICE_NAME" \
    --region="$REGION" --project="$PROJECT_ID" --format='value(metadata.name,status.latestReadyRevisionName)'
describe "Cloud Run Job" \
  gcloud --quiet run jobs describe "$JOB_NAME" \
    --region="$REGION" --project="$PROJECT_ID" --format='value(metadata.name)'
describe "Cloud Scheduler job" \
  gcloud --quiet scheduler jobs describe "$SCHEDULER_NAME" \
    --location="$REGION" --project="$PROJECT_ID" --format='value(name,state)'

cat <<EOF

PROPOSED WRITES (not executed by this script)
  gcloud sql databases create $CANONICAL_DATABASE --instance=$SQL_INSTANCE --project=$PROJECT_ID
  gcloud sql users create stog_migrator --instance=$SQL_INSTANCE --project=$PROJECT_ID
  gcloud sql users create stog_app --instance=$SQL_INSTANCE --project=$PROJECT_ID
  gcloud storage buckets create gs://$DEV_BUCKET --location=$REGION \
    --uniform-bucket-level-access --public-access-prevention
  gcloud artifacts repositories create $ARTIFACT_REPOSITORY --repository-format=docker --location=$REGION
  gcloud iam service-accounts create stog-backend-runtime --project=$PROJECT_ID
  gcloud iam service-accounts create stog-catalog-refresh --project=$PROJECT_ID
  gcloud secrets create <secret-name> --replication-policy=automatic
  gcloud run deploy $SERVICE_NAME --image=<approved-image> --no-traffic --region=$REGION
  gcloud run jobs deploy $JOB_NAME --image=<approved-image> --region=$REGION
  gcloud scheduler jobs create http $SCHEDULER_NAME --location=$REGION --paused

SOURCE SAFETY
  No DDL or DML is permitted against $SOURCE_DATABASE.
  No bucket, secret, IAM binding, Cloud Run service, Job, or Scheduler write
  is performed by this describe-only script.
EOF
