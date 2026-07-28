#!/usr/bin/env bash
# Generates throwaway TLS material for the TLS-PROXY-01 rig (docs/tls-proxy-recipe.md).
#
# Nothing here is installed into any system or JDK trust store. The test client is pointed at a
# purpose-built truststore instead, so certificate validation stays ON for every case — disabling
# validation would make case 3 pass without proving anything. That is also why this script does not
# use `mkcert -install`: it would modify the operator's machine.
#
# Output (all under $1):
#   test-ca.crt / .key       CA the client trusts — imported into truststore.p12
#   proxy.crt / .key         leaf for localhost, signed by test-ca      (good path)
#   rogue-ca.crt / .key      a second CA the client never trusts
#   rogue-proxy.crt / .key   leaf for localhost, signed by rogue-ca     (wrong-CA case)
#   mismatch.crt / .key      leaf for other.example, signed by test-ca  (hostname case)
#   truststore.p12           PKCS12 containing ONLY test-ca.crt
#
# Usage: scripts/tls-proxy-certs.sh <output-dir>   (keep the output OUT of the repo)
set -euo pipefail

OUT="${1:?usage: tls-proxy-certs.sh <output-dir>}"
STOREPASS="${TLS_RIG_STOREPASS:-changeit}"
mkdir -p "$OUT"
cd "$OUT"

cat > openssl-leaf.cnf <<'EOF'
[req]
distinguished_name = dn
[dn]
[localhost_ext]
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, IP:127.0.0.1
[mismatch_ext]
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:other.example
EOF

for ca in test-ca rogue-ca; do
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
    -keyout "$ca.key" -out "$ca.crt" \
    -subj "/CN=RemoteBLE $ca (throwaway)" >/dev/null 2>&1
done

# $1 = name, $2 = signing CA, $3 = extension section, $4 = CN
issue() {
  openssl req -newkey rsa:2048 -nodes -keyout "$1.key" -out "$1.csr" \
    -subj "/CN=$4" >/dev/null 2>&1
  openssl x509 -req -in "$1.csr" -CA "$2.crt" -CAkey "$2.key" -CAcreateserial \
    -out "$1.crt" -days 30 -extfile openssl-leaf.cnf -extensions "$3" >/dev/null 2>&1
  rm -f "$1.csr"
}

issue proxy       test-ca  localhost_ext  localhost
issue rogue-proxy rogue-ca localhost_ext  localhost
issue mismatch    test-ca  mismatch_ext   other.example

rm -f truststore.p12
keytool -importcert -noprompt -alias remoteble-test-ca \
  -file test-ca.crt -keystore truststore.p12 -storetype PKCS12 \
  -storepass "$STOREPASS" >/dev/null 2>&1

chmod 600 ./*.key truststore.p12
echo "TLS material written to $OUT (truststore password: $STOREPASS)"

# Verify the SANs actually landed. Note: macOS ships LibreSSL, whose `x509 -ext` is not the
# OpenSSL 1.1.1+ flag — reading the text dump works on both.
for leaf in proxy rogue-proxy mismatch; do
  printf '  %-12s %s\n' "$leaf" \
    "$(openssl x509 -in "$leaf.crt" -noout -text | grep -A1 'Subject Alternative Name' | tail -1 | sed 's/^ *//')"
done
