#!/usr/bin/env bash
# Owner-run, one-time setup for a durable APK signer.
# No root operations, no Lotus Control/Bridge changes, no secrets in GitHub source.
set -euo pipefail
umask 077

REPO="latatuev/kids-tv"
ALIAS="lotus-executor-stable"
DIR="${HOME}/.local/share/lotus-executor-signing"
KEYSTORE="${DIR}/lotus-executor-stable.p12"
PASSWORD_FILE="${DIR}/signing-password.txt"
CERT_FILE="${DIR}/certificate-sha256.txt"
BACKUP="${HOME}/lotus-executor-signing-backup.enc"
fail() { printf 'ОШИБКА: %s\n' "$*" >&2; exit 1; }

for binary in keytool gh openssl base64 tar; do
  command -v "$binary" >/dev/null 2>&1 || fail "Не найден $binary. Установку пакетов этот скрипт не выполняет."
done
gh auth status -h github.com >/dev/null 2>&1 ||
  fail "Сначала необходимо подключить GitHub CLI: gh auth login"
if [[ ! -f "$KEYSTORE" || ! -f "$PASSWORD_FILE" ]]; then
  [[ ! -e "$KEYSTORE" && ! -e "$PASSWORD_FILE" && ! -e "$CERT_FILE" ]] ||
    fail "Неполный набор старого ключа. Нельзя создавать новый вместо него!"
  if gh secret list -R "$REPO" | grep -q '^LOTUS_EXECUTOR_KEYSTORE_B64[[:space:]]'; then
    fail "В GitHub уже зарегистрирован ключ, но локальная копия потеряна. НЕ генерируем новый ключ."
  fi
  mkdir -p "$DIR"
  chmod 700 "$DIR"
  openssl rand -hex 32 > "$PASSWORD_FILE"
  chmod 600 "$PASSWORD_FILE"
  export LOTUS_KEYSTORE_PW
  LOTUS_KEYSTORE_PW="$(cat "$PASSWORD_FILE")"
  keytool -genkeypair -noprompt -storetype PKCS12 \
    -keystore "$KEYSTORE" \
    -storepass:env LOTUS_KEYSTORE_PW \
    -keypass:env LOTUS_KEYSTORE_PW \
    -alias "$ALIAS" -keyalg RSA -keysize 3072 \
    -validity 10950 -dname "CN=Lotus Executor,O=Owner Signing" >/dev/null
  chmod 600 "$KEYSTORE"
  unset LOTUS_KEYSTORE_PW
fi
export LOTUS_KEYSTORE_PW
LOTUS_KEYSTORE_PW="$(cat "$PASSWORD_FILE")"
cert="$(keytool -list -v -storetype PKCS12 -keystore "$KEYSTORE" \
  -storepass:env LOTUS_KEYSTORE_PW -alias "$ALIAS" 2>/dev/null \
  | sed -n 's/^[[:space:]]*SHA256: //p' | head -n1 | tr -d ':[:space:]' | tr 'A-F' 'a-f')"
[[ "$cert" =~ ^[0-9a-f]{64}$ ]] || fail "Не удалось подтвердить отпечаток сертификата"
if [[ -f "$CERT_FILE" ]]; then
  [[ "$(cat "$CERT_FILE")" == "$cert" ]] || fail "Ключ изменён. Прерываем!"
else
  printf '%s\n' "$cert" > "$CERT_FILE"
fi

# gh encrypts repo secrets before upload. Values are not committed to the public repo.
secrets_env="$(mktemp)"
trap 'rm -f "$secrets_env"' EXIT
printf 'LOTUS_EXECUTOR_KEYSTORE_B64=%s\n' "$(base64 -w0 "$KEYSTORE")" > "$secrets_env"
printf 'LOTUS_EXECUTOR_SIGNING_PASSWORD=%s\n' "$LOTUS_KEYSTORE_PW" >> "$secrets_env"
printf 'LOTUS_EXECUTOR_SIGNING_CERT_SHA256=%s\n' "$cert" >> "$secrets_env"
gh secret set -R "$REPO" -f "$secrets_env" >/dev/null
unset LOTUS_KEYSTORE_PW

printf '\n✅ Один и тот же сертификат зарегистрирован для будущих GitHub Actions сборок.\n'
printf 'SHA-256 сертификата (это публичное значение): %s\n' "$cert"
printf 'Локальный ключ хранится: %s\n' "$KEYSTORE"

# Off-host recoverable encrypted backup, deliberately NOT uploaded automatically.
if [[ ! -f "$BACKUP" ]]; then
  export LOTUS_RECOVERY_PW
  LOTUS_RECOVERY_PW="$(openssl rand -hex 32)"
  tar -C "$DIR" -cf - "lotus-executor-stable.p12" "signing-password.txt" "certificate-sha256.txt" |
    openssl enc -aes-256-cbc -pbkdf2 -iter 250000 -salt -pass env:LOTUS_RECOVERY_PW -out "$BACKUP"
  chmod 600 "$BACKUP"
  printf '\nСОХРАНИТЕ резервный пароль ВНЕ ЭТОГО КОМПЬЮТЕРА (он показывается один раз):\n%s\n' "$LOTUS_RECOVERY_PW"
  unset LOTUS_RECOVERY_PW
fi
printf '\nРезервная копия: %s\n' "$BACKUP"
printf 'Перенесите зашифрованную копию на Google Drive и сохраните резервный пароль отдельно.\n'
printf 'Пока нет внешней резервной копии, НЕ устанавливайте первую stable APK.\n'
printf 'Исходники/другие APK не изменены. Установка APK не выполнялась.\n'
