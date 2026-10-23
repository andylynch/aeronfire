#!/usr/bin/env bash
# Cheap, cost-controlled demo VM on GCP: Spot VM, stops itself every night, optional budget alert.
#
#   PROJECT=my-project ./deploy/gcp/up.sh
#   ssh in:   gcloud compute ssh aeronfire --project "$PROJECT" --zone "$ZONE"
#   tear down: ./deploy/gcp/down.sh
#
# Defaults avoid europe-west2 (London). europe-west1 (Belgium) is the cheapest EU tier;
# europe-west4 (Netherlands) is the fallback.
set -euo pipefail

: "${PROJECT:?set PROJECT to your GCP project id}"
REGION="${REGION:-europe-west1}"
ZONE="${ZONE:-${REGION}-b}"
MACHINE="${MACHINE:-e2-standard-4}"
NAME="${NAME:-aeronfire}"
REPO="${REPO:-https://github.com/andylynch/aeronfire.git}"
STOP_CRON="${STOP_CRON:-0 22 * * *}"          # stop nightly at 22:00 London time
BILLING_ACCOUNT="${BILLING_ACCOUNT:-}"        # set to create a budget alert
BUDGET_AMOUNT="${BUDGET_AMOUNT:-20}"          # in the billing account's currency

gcloud config set project "$PROJECT" >/dev/null
gcloud services enable compute.googleapis.com

if ! gcloud compute resource-policies describe "${NAME}-nightly-stop" --region "$REGION" >/dev/null 2>&1; then
  gcloud compute resource-policies create instance-schedule "${NAME}-nightly-stop" \
    --region "$REGION" \
    --vm-stop-schedule "$STOP_CRON" \
    --timezone "Europe/London" \
    --description "Stop the aeronfire demo VM every night"
fi

gcloud compute instances create "$NAME" \
  --zone "$ZONE" \
  --machine-type "$MACHINE" \
  --provisioning-model SPOT \
  --instance-termination-action STOP \
  --image-family ubuntu-2404-lts-amd64 \
  --image-project ubuntu-os-cloud \
  --boot-disk-size 30GB \
  --resource-policies "${NAME}-nightly-stop" \
  --metadata startup-script="#!/bin/bash
set -e
if ! command -v docker >/dev/null; then
  apt-get update && apt-get install -y docker.io docker-compose-v2 git
fi
cd /opt
[ -d aeronfire ] || git clone ${REPO}
cd aeronfire && git pull --ff-only
docker compose up -d --build
"

if [[ -n "$BILLING_ACCOUNT" ]]; then
  gcloud services enable billingbudgets.googleapis.com
  gcloud billing budgets create \
    --billing-account "$BILLING_ACCOUNT" \
    --display-name "${NAME}-budget" \
    --budget-amount "$BUDGET_AMOUNT" \
    --filter-projects "projects/${PROJECT}" \
    --threshold-rule percent=0.5 \
    --threshold-rule percent=0.9 \
    --threshold-rule percent=1.0
fi

echo "VM ${NAME} starting in ${ZONE}. The cluster comes up via the startup script (a few minutes on first boot)."
echo "Load test: gcloud compute ssh ${NAME} --zone ${ZONE} -- 'cd /opt/aeronfire && sudo docker compose run --rm client'"
