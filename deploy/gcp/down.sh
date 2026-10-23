#!/usr/bin/env bash
# Delete the demo VM and its stop schedule. The budget, if created, is left in place.
set -euo pipefail

: "${PROJECT:?set PROJECT to your GCP project id}"
REGION="${REGION:-europe-west1}"
ZONE="${ZONE:-${REGION}-b}"
NAME="${NAME:-aeronfire}"

gcloud compute instances delete "$NAME" --project "$PROJECT" --zone "$ZONE" --quiet
gcloud compute resource-policies delete "${NAME}-nightly-stop" --project "$PROJECT" --region "$REGION" --quiet
