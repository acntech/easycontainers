#!/bin/bash

# Start sshd in the background with the -D flag to avoid daemonizing
/usr/sbin/sshd -D &

# Start lighttpd in the background
lighttpd -D -f /etc/lighttpd/lighttpd.conf &

# Environment variables with defaults if not set
LOG_TIME_SLEEP=${LOG_TIME_SLEEP:-2}
LOG_TIME_EXIT_FLAG=${LOG_TIME_EXIT_FLAG:-false}
LOG_TIME_EXIT_VALUE=${LOG_TIME_EXIT_VALUE:-0}

# Depending on the LOG_TIME_EXIT_FLAG, execute the log-time.sh script
# The `exec` command is used here to ensure that log-time.sh becomes the main process of the container
if [ "$LOG_TIME_EXIT_FLAG" = "true" ]; then
  exec /log-time.sh -e -s $LOG_TIME_SLEEP -x $LOG_TIME_EXIT_VALUE
else
  exec /log-time.sh -s $LOG_TIME_SLEEP
fi
