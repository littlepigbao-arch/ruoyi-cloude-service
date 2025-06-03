#!/bin/sh

# 使用说明，用来提示输入参数
usage() {
	echo "Usage: sh 执行脚本.sh [port|base|modules|stop|rm]"
	exit 1
}

# 开启所需端口
port(){
    # 检测端口是否被占用。
    for port in 80 8080 8848 9848 9849 6379 3306 9100 9200 9201 9202 9203 9300; do
      echo "检查端口 $port:"
      lsof -Pi :$port -sTCP:LISTEN -t >/dev/null && echo "已被占用" || echo "未被占用"
    done

	firewall-cmd --add-port=80/tcp --permanent
	firewall-cmd --add-port=8080/tcp --permanent
	firewall-cmd --add-port=8848/tcp --permanent
	firewall-cmd --add-port=9848/tcp --permanent
	firewall-cmd --add-port=9849/tcp --permanent
	firewall-cmd --add-port=6379/tcp --permanent
	firewall-cmd --add-port=3306/tcp --permanent
	firewall-cmd --add-port=9100/tcp --permanent
	firewall-cmd --add-port=9200/tcp --permanent
	firewall-cmd --add-port=9201/tcp --permanent
	firewall-cmd --add-port=9202/tcp --permanent
	firewall-cmd --add-port=9203/tcp --permanent
	firewall-cmd --add-port=9300/tcp --permanent
	service firewalld restart
}

# 启动基础环境（必须）
base(){
	docker-compose up -d ruoyi-mysql ruoyi-redis ruoyi-nacos
}

# 启动程序模块（必须）
modules(){
	docker-compose up -d ruoyi-nginx ruoyi-gateway ruoyi-auth ruoyi-modules-system
}

# 关闭所有环境/模块
stop(){
	docker-compose stop
}

# 删除所有环境/模块
rm(){
	docker-compose rm
    # 查询已启动的容器使用的镜像文件
    docker ps -a --filter "name=ruoyi" --format "{{.Names}}\t{{.Image}}"
    # 删除容器使用的镜像
    docker rmi -f $(docker ps -a --filter "name=ruoyi" --format "{{.Image}}")

}

# 根据输入参数，选择执行对应方法，不输入则执行使用说明
case "$1" in
"port")
	port
;;
"base")
	base
;;
"modules")
	modules
;;
"stop")
	stop
;;
"rm")
	rm
;;
*)
	usage
;;
esac
