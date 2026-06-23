#!/usr/bin/env bash
set -euo pipefail

WORK_DIR="${WORK_DIR:-/opt/arkmem}"
JAR_DIR="${JAR_DIR:-${WORK_DIR}/jar}"
BACKUP_DIR="${BACKUP_DIR:-${WORK_DIR}/back}"
JVM_DIR="${JVM_DIR:-${WORK_DIR}/jvm}"
LOG_DIR="${LOG_DIR:-${WORK_DIR}/logs}"
RUN_DIR="${RUN_DIR:-${WORK_DIR}/run}"

CURRENT_JAR="${APP_JAR:-arkmem-0.1.0-SNAPSHOT.jar}"
SPRING_PROFILE="${SPRING_PROFILE:-release}"
SERVER_PORT="${SERVER_PORT:-19028}"
MAX_WAIT_TIME="${MAX_WAIT_TIME:-60}"
CHECK_INTERVAL="${CHECK_INTERVAL:-2}"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2G -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${JVM_DIR}}"

current_pids() {
  pgrep -f "java .*${WORK_DIR}/${CURRENT_JAR}" || true
}

install_new_jar() {
  mkdir -p "${WORK_DIR}" "${JAR_DIR}" "${BACKUP_DIR}" "${JVM_DIR}" "${LOG_DIR}" "${RUN_DIR}"

  if [ -f "${JAR_DIR}/${CURRENT_JAR}" ]; then
    if [ -f "${WORK_DIR}/${CURRENT_JAR}" ]; then
      local timestamp
      timestamp="$(date +%Y%m%d_%H%M%S)"
      cp "${WORK_DIR}/${CURRENT_JAR}" "${BACKUP_DIR}/${CURRENT_JAR}.${timestamp}"
      echo "Backed up current jar to ${BACKUP_DIR}/${CURRENT_JAR}.${timestamp}"
    fi

    mv "${JAR_DIR}/${CURRENT_JAR}" "${WORK_DIR}/${CURRENT_JAR}"
    echo "Installed new jar from ${JAR_DIR}/${CURRENT_JAR}"
    return
  fi

  if [ -f "${WORK_DIR}/${CURRENT_JAR}" ]; then
    echo "No new jar found, restarting current jar."
    return
  fi

  echo "No runnable jar found: ${WORK_DIR}/${CURRENT_JAR}" >&2
  exit 1
}

stop_current_process() {
  local pids
  pids="$(current_pids)"

  if [ -z "${pids}" ]; then
    echo "No running ArkMem process found."
    return
  fi

  echo "Stopping ArkMem process: ${pids}"
  for pid in ${pids}; do
    kill -TERM "${pid}" 2>/dev/null || true
  done

  local waited_sec=0
  while [ "${waited_sec}" -lt "${MAX_WAIT_TIME}" ]; do
    if [ -z "$(current_pids)" ]; then
      echo "ArkMem process stopped."
      return
    fi

    sleep "${CHECK_INTERVAL}"
    waited_sec=$((waited_sec + CHECK_INTERVAL))
  done

  pids="$(current_pids)"
  if [ -n "${pids}" ]; then
    echo "Force stopping ArkMem process after ${MAX_WAIT_TIME} seconds: ${pids}"
    for pid in ${pids}; do
      kill -KILL "${pid}" 2>/dev/null || true
    done
  fi
}

start_process() {
  echo "Starting ArkMem..."
  cd "${WORK_DIR}"

  nohup "${JAVA_BIN}" \
    -Dfile.encoding=UTF-8 \
    -Duser.dir="${WORK_DIR}" \
    -Dspring.profiles.active="${SPRING_PROFILE}" \
    -Dserver.port="${SERVER_PORT}" \
    ${JAVA_OPTS} \
    -jar "${WORK_DIR}/${CURRENT_JAR}" \
    > "${LOG_DIR}/java.log" 2>&1 &

  local pid=$!
  echo "${pid}" > "${RUN_DIR}/arkmem.pid"
  sleep 3

  if ps -p "${pid}" >/dev/null 2>&1; then
    echo "ArkMem started with PID ${pid}."
    return
  fi

  echo "ArkMem failed to start." >&2
  tail -n 80 "${LOG_DIR}/java.log" >&2 || true
  exit 1
}

install_new_jar
stop_current_process
start_process
