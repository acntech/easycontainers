#!/bin/sh

# Default values
sleep_time=2  # Default sleep time
exit_flag=0   # Exit flag not set by default
exit_code=10  # Default exit value
iterations=3  # Default number of iterations
message="Default message"  # Default message

# Parse command-line options
while getopts 'es:x:i:m:' flag; do
  case "${flag}" in
    e) exit_flag=1 ;;                              # Set exit flag
    s) sleep_time="${OPTARG}" ;;                   # Set sleep time
    x) exit_code="${OPTARG}" ;;                    # Set custom exit value
    i) iterations="${OPTARG}" ;;                   # Set custom number of iterations
    m) message="${OPTARG}" ;;                      # Set custom message
    *) echo "Unexpected option: ${flag}" ; exit 1 ;;
  esac
done

echo "Exit flag: ${exit_flag}"
echo "Sleep time: ${sleep_time}"
echo "Message: ${message}"

count=1

if [ "${exit_flag}" -eq 1 ]; then
  echo "Iterations: ${iterations}"
  echo "Exit code: ${exit_code}"
  # Run the exit version
  while [ $count -le "${iterations}" ]
  do
    echo "${count}: $(date) - ${message}"
    count=$((count+1))
    sleep "${sleep_time}"
  done
  echo "Exiting with code ${exit_code}"
  exit "${exit_code}"
else
  # Run the infinite version
  while true
  do
    echo "${count}: $(date) - ${message}"
    count=$((count+1))
    sleep "${sleep_time}"
  done
fi