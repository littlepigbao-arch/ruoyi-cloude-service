#!/usr/bin/env bash

echo ""
echo "[信息] 清理工程target生成路径。"
echo ""

cd "$(dirname "$0")" || exit
cd ..

mvn clean
