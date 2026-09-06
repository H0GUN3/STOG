#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-team-05-504502}"
REGION="${REGION:-asia-southeast1}"
SERVICE_NAME="${SERVICE_NAME:-stog-backend}"
SCHEDULER_NAME="${SCHEDULER_NAME:-stog-catalog-refresh}"
PREVIOUS_REVISION="${PREVIOUS_REVISION:-}"

if [[ "${1:-}" != "--apply" ]]; then
  cat <<EOF
STOG rollback plan (describe-only; no mutation)

1. Pause the catalog Scheduler:
   gcloud scheduler jobs pause $SCHEDULER_NAME --location=$REGION --project=$PROJECT_ID
2. Route all service traffic to the previously approved revision:
   gcloud run services update-traffic $SERVICE_NAME --region=$REGION --project=$PROJECT_ID \\
     --to-revisions=${PREVIOUS_REVISION:-<previous-revision>}=100
3. Verify the revision health, database connectivity, and signed-read checks.
4. Do not roll back Flyway or delete the canonical database.

Pass --apply only after a separate traffic-promotion/rollback approval and
set PREVIOUS_REVISION to an exact known-good revision.
EOF
  exit 0
fi

[[ "${ALLOW_GCP_WRITES:-}" == "I_UNDERSTAND_ROLLBACK_APPROVAL" ]] ||
  { printf 'ERROR: explicit rollback approval guard is required\n' >&2; exit 2; }
[[ -n "$PREVIOUS_REVISION" ]] ||
  { printf 'ERROR: PREVIOUS_REVISION is required\n' >&2; exit 2; }

gcloud scheduler jobs pause "$SCHEDULER_NAME" \
  --location="$REGION" --project="$PROJECT_ID" --quiet
gcloud run services update-traffic "$SERVICE_NAME" \
  --region="$REGION" --project="$PROJECT_ID" \
  --to-revisions="$PREVIOUS_REVISION=100" --quiet
