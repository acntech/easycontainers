#!/bin/sh

# Default values
sleep_time=2      # Default sleep time
exit_flag=0       # Exit flag not set by default
exit_value=10     # Default exit value

# Parse command-line options
while getopts 'es:x:' flag; do
  case "${flag}" in
    e) exit_flag=1 ;;                              # Set exit flag
    s) sleep_time="${OPTARG}" ;;                   # Set sleep time
    x) exit_value="${OPTARG}" ;;                   # Set custom exit value
    *) echo "Unexpected option: ${flag}" ; exit 1 ;;
  esac
done

count=1

if [ "${exit_flag}" -eq 1 ]; then
  # Run the exit version
  while [ $count -le 3 ]
  do
    echo "${count}: $(date)"
    count=$((count+1))
    sleep "${sleep_time}"
  done
  exit "${exit_value}"
else
  # Run the infinite version
  while true
  do
    echo "${count}: $(date)"
    count=$((count+1))
    sleep "${sleep_time}"
  done
fi
