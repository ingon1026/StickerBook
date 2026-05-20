#!/usr/bin/env bash
# AD 가 의존하는 torchserve (ML 서버) 띄우는 스크립트.
#
# torchserve 가 8080(inference) / 8081(management) / 8082(metrics) 사용.
# 우리 FastAPI 는 8000 사용 — 포트 안 겹침.
#
# 사용 config: ../torchserve_config/config.properties
#   - AD 폴더는 건드리지 않음 (model_store 만 절대 경로로 참조)
#   - token authorization 비활성화 (AD 코드가 토큰 안 보냄)
set -euo pipefail

cd "$(dirname "$0")/.."

source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate animated_drawings

export PYTHONPATH=
export AMENT_PREFIX_PATH=

# 기존 인스턴스 종료 (포트 충돌 방지)
torchserve --stop 2>/dev/null || true
sleep 1

torchserve \
    --start \
    --ts-config torchserve_config/config.properties \
    --foreground
