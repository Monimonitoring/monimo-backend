# 2026-10-28 에이전트 · 덤프 비용 (overhead_gc.sh)

해석은 `../../../90-overhead-gc.md`. `summary.md` 는 `overhead_gc_analyze.py` 출력 그대로다.

| 파일 (회차 폴더 `r<반복>-<off · on · dump>`) | 무엇 |
|---|---|
| `manifest.env` | 조건 · 커밋 · order 이미지 · 측정 구간(ms) |
| `gc-*.log.gz` | order GC · safepoint 로그 (원래 `gclog/`, gzip) |
| `k6-summary.json` | k6 요약. 요청별 `k6.csv.gz` 는 회차당 약 1.4MB 라 안 넣었다 |
| `cgroup-before.txt` · `cgroup-after.txt` | 서비스 · CPU 누적 µs · 메모리 바이트 |
| `dumps.tsv` | dump 회차만. 요청 · 응답 시각 · HTTP · 크기 |

버린 회차 : 첫 `r2-on` · `r2-dump` (맥북 잠자기, 10:16:48 KST). r2 ~ r5 는 다시 돌린 값이다.
