#!/usr/bin/env bash

echo ""
echo "[信息] 使用Jar命令运行Auth工程。"
echo ""

cd "$(dirname "$0")" || exit
cd ../ruoyi-auth/target || exit

JAVA_OPTS="-Xms512m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m"

java -Dfile.encoding=utf-8 "$JAVA_OPTS" -jar ruoyi-auth.jar
