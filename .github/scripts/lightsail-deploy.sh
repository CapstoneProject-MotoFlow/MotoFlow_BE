#!/usr/bin/env bash
# Deploys one MotoFlow service to every Lightsail instance of an environment.
# Usage: lightsail-deploy.sh <dev|production> <backend|frontend> <image@sha256:...>
#
# An instance is selected when it is running and tagged App=motoflow, Env=<environment>.
# The runner SSHs in with the short-lived key from GetInstanceAccessDetails (the instance
# must use the Lightsail default key pair). A custom key can be supplied as LIGHTSAIL_SSH_KEY.
# Docker on the instance logs into ECR with a password minted here, because a Lightsail
# instance has no IAM role. /opt/motoflow/bin/deploy-service then pulls and restarts.
set -euo pipefail

ENV_NAME="${1:?environment (dev|production)}"
SERVICE="${2:?service (backend|frontend)}"
IMAGE="${3:?image reference}"
REGION="${AWS_REGION:?AWS_REGION is not set}"

NAMES=$(aws lightsail get-instances --region "$REGION" --output json | jq -r --arg env "$ENV_NAME" '
  .instances[]
  | select(.state.name == "running")
  | select(any(.tags[]?; .key == "App" and .value == "motoflow"))
  | select(any(.tags[]?; .key == "Env" and .value == $env))
  | .name
')
if [ -z "$NAMES" ]; then
  echo "::error::No running Lightsail instance tagged App=motoflow, Env=$ENV_NAME in $REGION."
  exit 1
fi
echo "Deploying $SERVICE to Lightsail $ENV_NAME: $NAMES"

REGISTRY="${IMAGE%%/*}"
ECR_PASSWORD_B64=$(aws ecr get-login-password --region "$REGION" | base64 -w0)

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT
FAILED=0

for NAME in $NAMES; do
  echo "== $NAME"
  ACCESS=$(aws lightsail get-instance-access-details --region "$REGION" --instance-name "$NAME" --protocol ssh --output json)
  USER=$(jq -r '.accessDetails.username' <<<"$ACCESS")
  IP=$(jq -r '.accessDetails.ipAddress' <<<"$ACCESS")
  PRIV=$(jq -r '.accessDetails.privateKey // empty' <<<"$ACCESS")
  CERT=$(jq -r '.accessDetails.certKey // empty' <<<"$ACCESS")
  KEY="$WORKDIR/$NAME"
  if [ -n "$PRIV" ]; then
    printf '%s\n' "$PRIV" > "$KEY"
    printf '%s\n' "$CERT" > "$KEY-cert.pub"
  elif [ -n "${LIGHTSAIL_SSH_KEY:-}" ]; then
    printf '%s\n' "$LIGHTSAIL_SSH_KEY" > "$KEY"
  else
    echo "::error::$NAME uses a custom SSH key. Add the private key as the LIGHTSAIL_SSH_KEY secret, or recreate the instance with the Lightsail default key pair."
    FAILED=1
    continue
  fi
  chmod 600 "$KEY"
  if ! ssh -i "$KEY" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new \
      -o UserKnownHostsFile="$WORKDIR/known_hosts" -o ConnectTimeout=20 \
      "$USER@$IP" \
      "sudo env ECR_PASSWORD_B64=$(printf '%q' "$ECR_PASSWORD_B64") /opt/motoflow/bin/deploy-service $(printf '%q' "$SERVICE") $(printf '%q' "$IMAGE")"; then
    echo "::error::Deploy failed on $NAME ($IP)"
    FAILED=1
  fi
done

exit "$FAILED"
