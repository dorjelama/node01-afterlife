#!/bin/sh
# SearXNG, the chat's web search, installed for this user only: no sudo, no system packages.
# It listens on 127.0.0.1:8888 only, so nothing but this machine can use it, and answers the chat in JSON.
# Run once on the server: sh searxng-install.sh   (logs to ~/searxng-install.log)
set -e
cd "$HOME"
[ -d searxng ] || git clone --depth 1 https://github.com/searxng/searxng.git searxng
python3 -m venv searxng/venv
searxng/venv/bin/pip install -U pip setuptools wheel pyyaml msgspec typing_extensions
cd searxng
# Its own requirements first: the editable install below builds without isolation and needs them there.
venv/bin/pip install -r requirements.txt
venv/bin/pip install --use-pep517 --no-build-isolation -e .
cd "$HOME"

if [ ! -f searxng-settings.yml ]; then
  key=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
  cat > searxng-settings.yml <<EOF
use_default_settings: true
general:
  instance_name: "home search"
server:
  secret_key: "$key"
  bind_address: "127.0.0.1"
  port: 8888
  limiter: false
  image_proxy: false
search:
  safe_search: 1
  formats:
    - html
    - json
EOF
  chmod 600 searxng-settings.yml
fi

line="@reboot SEARXNG_SETTINGS_PATH=$HOME/searxng-settings.yml $HOME/searxng/venv/bin/python -m searx.webapp >> $HOME/searxng.log 2>&1"
(crontab -l 2>/dev/null | grep -v searx.webapp; echo "$line") | crontab -
SEARXNG_SETTINGS_PATH="$HOME/searxng-settings.yml" setsid nohup searxng/venv/bin/python -m searx.webapp >> searxng.log 2>&1 < /dev/null &
echo "INSTALLED"
