#!/bin/bash

# Start sshd in the background with the -D flag to avoid daemonizing
/usr/sbin/sshd -D &

# Start lighttpd in the background
lighttpd -D -f /etc/lighttpd/lighttpd.conf &

# Environment variables with defaults if not set
LOG_TIME_SLEEP=${LOG_TIME_SLEEP:-2}
LOG_TIME_EXIT_FLAG=${LOG_TIME_EXIT_FLAG:-false}
LOG_TIME_EXIT_CODE=${LOG_TIME_EXIT_CODE:-10}
LOG_TIME_ITERATIONS=${LOG_TIME_ITERATIONS:-5}
LOG_TIME_MESSAGE=${LOG_TIME_MESSAGE:-"Default message"}

# Depending on the LOG_TIME_EXIT_FLAG, execute the log-time.sh script
# The `exec` command is used here to ensure that log-time.sh becomes the main process of the container
if [ "$LOG_TIME_EXIT_FLAG" = "true" ]; then
  # shellcheck disable=SC2086
  exec /log-time.sh -e -s "$LOG_TIME_SLEEP" -x "$LOG_TIME_EXIT_CODE" -i "$LOG_TIME_ITERATIONS" -m "$LOG_TIME_MESSAGE"
else
  exec /log-time.sh -s "$LOG_TIME_SLEEP" -m "$LOG_TIME_MESSAGE"
fi
