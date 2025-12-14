#!/usr/bin/env bash

echo ""
echo "[信息] 打包Web工程，生成war/jar包文件。"
echo ""

cd "$(dirname "$0")" || exit
cd ..

mvn clean package -Dmaven.test.skip=true
