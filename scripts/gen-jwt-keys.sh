#!/usr/bin/env bash
# Generates the RSA-2048 keypair the platform signs access tokens with,
# in the exact PEM shapes FileRsaKeyProvider expects:
#
#   - private: PKCS#8, label "PRIVATE KEY"     (openssl genpkey output)
#   - public:  X.509,  label "PUBLIC KEY"      (openssl pkey -pubout output)
#
# Spring's prod profile (application-prod.yml) loads these via
#   PLATFORM_JWT_PRIVATE_KEY_PATH
#   PLATFORM_JWT_PUBLIC_KEY_PATH
#   PLATFORM_JWT_KEY_ID
#
# We also emit a unique kid so JWKS consumers can distinguish keys when
# you rotate. Rotation today is "regenerate + restart"; a proper hot
# rotation (two-key window) lands when M1 grows a Phase 1.13.
#
# Usage:
#   scripts/gen-jwt-keys.sh                # default output dir: ./deployment/prod/secrets/jwt
#   scripts/gen-jwt-keys.sh /tmp/keys      # override output dir
#
# Refuses to overwrite existing keys — rotating a production key by
# accident is exactly the kind of "oops" we want to make hard.

set -euo pipefail

OUT_DIR="${1:-deployment/prod/secrets/jwt}"
PRIV_PATH="$OUT_DIR/jwt-private.pem"
PUB_PATH="$OUT_DIR/jwt-public.pem"
KID_PATH="$OUT_DIR/kid.txt"

if [[ -f "$PRIV_PATH" || -f "$PUB_PATH" ]]; then
  echo "Error: keys already exist at $OUT_DIR" >&2
  echo "Remove the directory explicitly if you really mean to rotate:" >&2
  echo "  rm -rf $OUT_DIR" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

echo "Generating RSA-2048 keypair in $OUT_DIR..."

# PKCS#8 PEM, 2048-bit RSA. Matches what jjwt + FileRsaKeyProvider parse.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$PRIV_PATH" 2>/dev/null
chmod 600 "$PRIV_PATH"

# X.509 SubjectPublicKeyInfo PEM (the "PUBLIC KEY" label, not the
# legacy PKCS#1 "RSA PUBLIC KEY").
openssl pkey -in "$PRIV_PATH" -pubout -out "$PUB_PATH"
chmod 644 "$PUB_PATH"

# Key id: short random suffix so JWKS clients can refresh on rotation.
# Format mirrors the dev-mode EphemeralRsaKeyProvider's "ephemeral-…"
# so logs/tooling read consistently.
if command -v uuidgen >/dev/null 2>&1; then
  KID="prod-$(uuidgen | tr 'A-Z' 'a-z' | cut -c1-8)"
else
  KID="prod-$(date +%s%N | sha256sum | head -c 8)"
fi
echo -n "$KID" > "$KID_PATH"
chmod 644 "$KID_PATH"

echo
echo "Generated:"
echo "  $PRIV_PATH       (mode 600)"
echo "  $PUB_PATH        (mode 644)"
echo "  $KID_PATH        (mode 644)"
echo
echo "Set these in the platform's environment:"
echo "  PLATFORM_JWT_PRIVATE_KEY_PATH=/run/secrets/jwt/jwt-private.pem"
echo "  PLATFORM_JWT_PUBLIC_KEY_PATH=/run/secrets/jwt/jwt-public.pem"
echo "  PLATFORM_JWT_KEY_ID=$KID"
echo
echo "If you commit *anything* under $OUT_DIR to git you've leaked a"
echo "signing key. Verify with: git status."
