#!/usr/bin/env bash
# Installs or updates the Snowball Client website on this server.
# Run as root from the uploaded folder:  sudo bash deploy/install.sh
# Safe to run again after changing the site: it re-copies files and reloads Caddy.
set -euo pipefail

DOMAIN="snowball.krakensmp.xyz"
WEB_ROOT="/var/www/snowball"
SITE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

say() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || fail "Run this with sudo (as root)."
[ -f "$SITE_DIR/index.html" ] || fail "index.html not found next to deploy/. Upload the whole website folder."
[ -f "$SITE_DIR/downloads/SnowballClient-1.0.0-setup.exe" ] || echo "WARNING: downloads/*.exe missing; the download buttons will 404."

# ---- 1. Never take over ports another web server already uses ----
if command -v ss >/dev/null 2>&1; then
	busy="$(ss -ltnpH '( sport = :80 or sport = :443 )' 2>/dev/null | grep -v caddy || true)"
	if [ -n "$busy" ]; then
		echo "$busy"
		fail "Something other than Caddy is already using port 80/443 (shown above). Nothing was changed. Send this output to get a config for your existing web server."
	fi
fi

# ---- 2. Install Caddy (official package repository) ----
if ! command -v caddy >/dev/null 2>&1; then
	say "Installing Caddy"
	if command -v apt-get >/dev/null 2>&1; then
		apt-get update -y
		apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg
		curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
		curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
		apt-get update -y
		apt-get install -y caddy
	elif command -v dnf >/dev/null 2>&1; then
		dnf install -y 'dnf-command(copr)'
		dnf copr enable -y @caddy/caddy
		dnf install -y caddy
	else
		fail "Unsupported Linux distribution (needs apt or dnf). Install Caddy manually from caddyserver.com, then run this script again."
	fi
fi

# ---- 3. Copy the site ----
say "Copying site to $WEB_ROOT"
mkdir -p "$WEB_ROOT"
if command -v rsync >/dev/null 2>&1; then
	rsync -a --delete --exclude deploy --exclude serve.mjs "$SITE_DIR/" "$WEB_ROOT/"
else
	find "$WEB_ROOT" -mindepth 1 -delete
	cp -a "$SITE_DIR/." "$WEB_ROOT/"
	rm -rf "$WEB_ROOT/deploy" "$WEB_ROOT/serve.mjs"
fi
chown -R root:root "$WEB_ROOT"
find "$WEB_ROOT" -type d -exec chmod 755 {} +
find "$WEB_ROOT" -type f -exec chmod 644 {} +

# ---- 4. Caddy site config (kept separate from any existing sites) ----
say "Configuring Caddy for $DOMAIN"
mkdir -p /etc/caddy/sites
install -m 644 "$SITE_DIR/deploy/Caddyfile" /etc/caddy/sites/snowball.caddy
MAIN=/etc/caddy/Caddyfile
if [ ! -f "$MAIN" ] || grep -qE '^\s*:80\s*\{' "$MAIN"; then
	# Missing or the package's default placeholder site: replace it.
	[ -f "$MAIN" ] && cp "$MAIN" "$MAIN.bak.$(date +%s)"
	printf 'import sites/*.caddy\n' > "$MAIN"
elif ! grep -qF 'import sites/*.caddy' "$MAIN"; then
	cp "$MAIN" "$MAIN.bak.$(date +%s)"
	printf '\nimport sites/*.caddy\n' >> "$MAIN"
fi
caddy validate --config "$MAIN" --adapter caddyfile

# ---- 5. Firewall: open web ports only if a firewall is active ----
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
	say "Opening ports 80 and 443 in ufw"
	ufw allow 80/tcp
	ufw allow 443/tcp
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
	say "Opening ports 80 and 443 in firewalld"
	firewall-cmd --permanent --add-service=http
	firewall-cmd --permanent --add-service=https
	firewall-cmd --reload
fi

# ---- 6. Start ----
systemctl enable caddy >/dev/null
systemctl reload caddy 2>/dev/null || systemctl restart caddy
sleep 2
systemctl is-active --quiet caddy || { journalctl -u caddy -n 30 --no-pager; fail "Caddy did not start (log above)."; }

say "Checking DNS"
server_ip="$(curl -4 -s --max-time 5 https://api.ipify.org || true)"
dns_ip="$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)"
echo "This server: ${server_ip:-unknown}   $DOMAIN resolves to: ${dns_ip:-nothing yet}"
if [ -n "$server_ip" ] && [ "$server_ip" != "$dns_ip" ]; then
	echo "DNS does not point here yet. Caddy will get the HTTPS certificate automatically once it does."
fi

say "Done. Visit https://$DOMAIN"
