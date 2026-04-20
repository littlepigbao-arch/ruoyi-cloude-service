pipeline {
    agent any

    // 定义环境变量
    environment {
        // 你的 Git 仓库地址
        GIT_URL = 'ssh://git@192.168.10.10:2222/root/ruoyi-cloud.git' 
        // 你的 Docker 镜像仓库地址 (可以是阿里云、Harbor 或本地 IP)
        REGISTRY_URL = '192.168.10.10:5000'
        // 项目名称前缀
        APP_PREFIX = 'ruoyi'
    }

    tools {
        // 确保在 Jenkins "全局工具配置" 中配置了名为 'jdk17' 和 'maven3' 的工具
        jdk 'jdk17'
        maven 'maven3'
    }

    stages {
        // --- 阶段 1: 拉取代码 ---
        stage('Checkout') {
            steps {
                echo '🚀 正在拉取 RuoYi-Cloud 代码...'
                // 使用 SSH 凭据拉取，ID 需与你在 Jenkins 凭据管理中设置的一致
                git branch: 'master', url: "${GIT_URL}", credentialsId: 'git-ssh-cred'
            }
        }

        // --- 阶段 2: Maven 编译打包 ---
        stage('Maven Build') {
            steps {
                echo '🔨 正在编译所有模块...'
                // 跳过测试，清理并打包
                sh 'mvn clean package -DskipTests -Dmaven.test.skip=true'
            }
        }

        // --- 阶段 3: 构建 Docker 镜像 ---
        stage('Build Docker Images') {
            parallel {
                // 并行构建网关服务
                stage('Build Gateway') {
                    steps {
                        script {
                            buildDockerImage('ruoyi-gateway', 'ruoyi-gateway/target/ruoyi-gateway.jar')
                        }
                    }
                }
                // 并行构建认证服务
                stage('Build Auth') {
                    steps {
                        script {
                            buildDockerImage('ruoyi-auth', 'ruoyi-auth/target/ruoyi-auth.jar')
                        }
                    }
                }
                // 并行构建系统服务
                stage('Build System') {
                    steps {
                        script {
                            buildDockerImage('ruoyi-system', 'ruoyi-modules/ruoyi-system/target/ruoyi-system.jar')
                        }
                    }
                }
                // 如果有文件、定时任务等其他模块，可以继续添加 stage...
            }
        }

        // --- 阶段 4: 部署容器 ---
        stage('Deploy Containers') {
            steps {
                echo '🚢 正在部署容器...'
                script {
                    // 部署网关
                    deployContainer('ruoyi-gateway', 8080, 8080)
                    // 部署认证
                    deployContainer('ruoyi-auth', 9200, 9200)
                    // 部署系统
                    deployContainer('ruoyi-system', 9201, 9201)
                }
            }
        }
    }

    post {
        success {
            echo '🎉 RuoYi-Cloud 全部服务构建部署成功！'
        }
        failure {
            echo '❌ 构建失败，请检查日志。'
        }
    }
}

// --- 自定义函数：构建镜像 ---
def buildDockerImage(String imageName, String jarPath) {
    echo "正在构建镜像: ${imageName}..."
    // 检查 Jar 包是否存在
    if (fileExists(jarPath)) {
        // 使用 Dockerfile 构建
        // 注意：这里假设你有一个通用的 Dockerfile，或者我们在脚本里动态生成
        // 为了简化，我们使用 docker build 的 --build-arg 或者直接在 Dockerfile 中指定
        // 这里推荐使用通用的 Dockerfile 放在根目录
        sh """
            docker build -t ${REGISTRY_URL}/${APP_PREFIX}/${imageName}:latest \
                --build-arg JAR_FILE=${jarPath} \
                -f Dockerfile.microservice .
        """
    } else {
        error "找不到 Jar 包: ${jarPath}"
    }
}

// --- 自定义函数：部署容器 ---
def deployContainer(String imageName, int hostPort, int containerPort) {
    echo "正在部署: ${imageName} 到端口 ${hostPort}..."
    sh """
        docker stop ${imageName} || true
        docker rm ${imageName} || true
        docker run -d --name ${imageName} \
            -p ${hostPort}:${containerPort} \
            -e SPRING_PROFILES_ACTIVE=dev \
            --restart=always \
            ${REGISTRY_URL}/${APP_PREFIX}/${imageName}:latest
    """
}