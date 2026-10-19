#!/usr/bin/env bash
# Deploys one MotoFlow service to every server of an environment through SSM Run Command.
# Usage: ssm-deploy.sh <dev|production> <backend|frontend> <image@sha256:...>
#
# Servers are EC2 instances tagged App=motoflow and Env=<environment>; on each one,
# /opt/motoflow/bin/deploy-service (installed by deploy/server/setup.sh in MotoFlow_BE)
# pulls the image, restarts the service, waits until it is healthy and rolls back if not.
# The same file exists in MotoFlow_BE and MotoFlow_FE; keep the two copies identical.
set -euo pipefail

ENV_NAME="${1:?environment (dev|production)}"
SERVICE="${2:?service (backend|frontend)}"
IMAGE="${3:?image reference}"
REGION="${AWS_REGION:?AWS_REGION is not set}"

IDS=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:App,Values=motoflow" "Name=tag:Env,Values=$ENV_NAME" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].InstanceId' --output text)
if [ -z "$IDS" ]; then
  echo "::error::No running EC2 instance tagged App=motoflow, Env=$ENV_NAME in $REGION."
  exit 1
fi
echo "Deploying $SERVICE to $ENV_NAME ($IDS): $IMAGE"

# jq builds the JSON, so the image reference needs no shell quoting.
PARAMS=$(jq -nc --arg cmd "/opt/motoflow/bin/deploy-service $SERVICE $IMAGE" \
  '{commands: [$cmd], executionTimeout: ["900"]}')

# shellcheck disable=SC2086 # $IDS is a space-separated list on purpose
CMD_ID=$(aws ssm send-command --region "$REGION" \
  --instance-ids $IDS \
  --document-name AWS-RunShellScript \
  --comment "MotoFlow $SERVICE ${GITHUB_SHA:0:7} to $ENV_NAME" \
  --parameters "$PARAMS" \
  --query 'Command.CommandId' --output text)
echo "SSM command id: $CMD_ID"

# send-command only queues the command: wait for each server and report its own result.
FAILED=0
for ID in $IDS; do
  STATUS=Pending
  for _ in $(seq 1 180); do # up to 15 minutes
    STATUS=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CMD_ID" --instance-id "$ID" \
      --query Status --output text 2>/dev/null || echo Pending)
    case "$STATUS" in
      Pending|InProgress|Delayed) sleep 5 ;;
      *) break ;;
    esac
  done

  echo "::group::$ID: $STATUS"
  aws ssm get-command-invocation --region "$REGION" --command-id "$CMD_ID" --instance-id "$ID" \
    --query '[StandardOutputContent, StandardErrorContent]' --output text || true
  echo "::endgroup::"

  if [ "$STATUS" != Success ]; then
    echo "::error::Deployment on $ID finished with status $STATUS (see the log group above)."
    FAILED=1
  fi
done
exit "$FAILED"
