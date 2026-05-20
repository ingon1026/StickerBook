#!/usr/bin/env bash
# 서버 실행 스크립트.
#
# uvicorn 옵션:
#   --host 0.0.0.0  : 모든 NIC 노출 (LAN 의 다른 기기에서 접속 가능)
#                     127.0.0.1 이면 같은 PC 안에서만 접속됨
#   --port 8000     : listen 포트
#   --reload        : 코드 수정 시 자동 재시작 (개발 편의)
#
# PYTHONPATH/AMENT_PREFIX_PATH 비움: ROS2 환경 침범 차단.
set -euo pipefail
cd "$(dirname "$0")/.."

# conda env 활성화
source /home/ingon/miniconda3/etc/profile.d/conda.sh
conda activate animated_drawings

export PYTHONPATH=
export AMENT_PREFIX_PATH=

exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
