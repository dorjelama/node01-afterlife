#!/bin/sh
# Every 15 seconds for a minute: send the CPU's temperature to NODE Companion, and keep the AI from cooking it.
# At STOP_C it stops Ollama, the server's heat; under START_C it starts it again, only if this script stopped it.
# Run by root's cron every minute, since only root may stop Ollama:
#   * * * * * /home/dorje/node-temp.sh
# The phone's address and key are the collector's, in /home/dorje/.config/node-collect.

STOP_C=95
START_C=70
SENSOR=/sys/class/thermal/thermal_zone0/temp    # x86_pkg_temp, the CPU package
CONFIG=/home/dorje/.config/node-collect
MARK=/run/node-temp-stopped-ollama

for i in 1 2 3 4; do
    c=$(( $(cat "$SENSOR") / 1000 ))
    if [ "$c" -ge "$STOP_C" ] && [ ! -e "$MARK" ]; then
        systemctl stop ollama && touch "$MARK" && logger -t node-temp "stopped ollama at ${c}C"
    elif [ "$c" -lt "$START_C" ] && [ -e "$MARK" ]; then
        systemctl start ollama && rm -f "$MARK" && logger -t node-temp "started ollama at ${c}C"
    fi
    curl -s -m 5 -o /dev/null -d "temp=$c" "$(cat "$CONFIG")server"
    [ "$i" -lt 4 ] && sleep 15
done
