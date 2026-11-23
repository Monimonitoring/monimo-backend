#!/usr/bin/env bash
# Notifier 컨테이너 안에서 돈다. JVM(PID 1)의 커널 쪽 숫자를 0.2초마다 한 줄씩 적는다.
# 열: epoch초  스레드수  RSS(kB)  최고RSS(kB)  CPU틱(user+sys, 죽은 스레드 포함)  자발적전환합  비자발적전환합
#  - 스레드 수 · RSS · 최고 RSS = /proc/1/status (Threads · VmRSS · VmHWM)
#  - CPU 틱 = /proc/1/stat 14 · 15번째 칸. 스레드 그룹 전체 누적이라 끝난 스레드 몫도 들어 있다
#  - 문맥 교환 = /proc/1/task/*/status 를 더한 값. 살아 있는 스레드만 세므로 끝난 스레드 몫은 빠진다
#    (레인 · IO · HttpClient 스레드는 쉬어도 60초 살아 있어서 한 회차 안에서는 거의 빠지지 않는다)
OUT=$1
touch /tmp/sampling   # 이 파일을 지우면 멈춘다
while [ -e /tmp/sampling ]; do
  now=$(date +%s.%N)
  st=$(awk '/^Threads:/{t=$2} /^VmRSS:/{r=$2} /^VmHWM:/{h=$2} END{print t, r, h}' /proc/1/status)
  cpu=$(awk '{print $14 + $15}' /proc/1/stat)
  cs=$(cat /proc/1/task/*/status 2>/dev/null | awk '/^voluntary_ctxt_switches/{v+=$2} /^nonvoluntary_ctxt_switches/{n+=$2} END{print v, n}')
  echo "$now $st $cpu $cs" >> "$OUT"
  sleep 0.2
done
