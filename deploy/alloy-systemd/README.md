# Alloy 宿主机 systemd 部署教程

本文档用于把 Alloy 直接安装到 Docker Swarm 每台宿主机上，通过 systemd 运行。

这种方式比 `docker service create` 更适合采集完整 Docker 容器指标，因为它不受 Swarm service 无法使用真正 `--privileged` 的限制。

## 目标

每台宿主机上的 Alloy 负责采集本机数据：

- Docker 容器日志 -> Loki
- 主机指标 -> Prometheus remote_write
- Docker 容器指标/cAdvisor -> Prometheus remote_write
- Java 容器指标 -> Prometheus remote_write

Java 指标通过 Docker 容器 label 自动发现，不需要手工维护目标列表。

## 当前环境

按当前集群信息，下面教程默认使用：

```text
APP_ENV=release
SERVICE_NAMESPACE=ysb
Loki: http://172.21.49.8:3100
Prometheus remote_write: http://172.21.49.8:9090/api/v1/write
Docker overlay network: release-overlay
Alloy HTTP 端口: 12345
```

如环境变化，修改 `/etc/alloy/alloy.env` 即可。

## 前置条件

每台宿主机需要满足：

```bash
docker version
systemctl --version
```

并且 Prometheus 必须开启 remote write receiver，否则 Alloy 指标写不进去。

Prometheus 启动参数需要包含：

```bash
--web.enable-remote-write-receiver
```

## 第一步：安装 Alloy

在每台 Docker Swarm 节点执行。

如果系统可以访问 Grafana 官方源，推荐按官方包安装。

Debian/Ubuntu 示例：

```bash
mkdir -p /etc/apt/keyrings
wget -q -O - https://apt.grafana.com/gpg.key | gpg --dearmor > /etc/apt/keyrings/grafana.gpg
echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" > /etc/apt/sources.list.d/grafana.list
apt-get update
apt-get install -y alloy
```

CentOS/RHEL/Rocky/Alibaba Cloud Linux 示例：

```bash
cat >/etc/yum.repos.d/grafana.repo <<'EOF'
[grafana]
name=grafana
baseurl=https://rpm.grafana.com
repo_gpgcheck=1
enabled=1
gpgcheck=1
gpgkey=https://rpm.grafana.com/gpg.key
sslverify=1
sslcacert=/etc/pki/tls/certs/ca-bundle.crt
EOF

yum install -y alloy
```

安装完成后确认：

```bash
alloy --version
```

如果服务器不能访问公网，可以先在能访问公网的机器下载 Alloy rpm/deb 包，再上传到每台节点安装。

## 第二步：创建配置目录

```bash
mkdir -p /etc/alloy /var/lib/alloy/data
```

## 第三步：写环境变量

```bash
cat >/etc/alloy/alloy.env <<'EOF'
APP_ENV=release
SERVICE_NAMESPACE=ysb
LOKI_PUSH_URL=http://172.21.49.8:3100/loki/api/v1/push
PROMETHEUS_REMOTE_WRITE_URL=http://172.21.49.8:9090/api/v1/write
JAVA_METRICS_PATH=/actuator/prometheus
DOCKER_METRICS_NETWORK=release-overlay
EOF
```

## 第四步：写 Alloy 配置

```bash
cat >/etc/alloy/config.alloy <<'EOF'
logging {
  level  = "info"
  format = "logfmt"
}

prometheus.remote_write "default" {
  endpoint {
    url = sys.env("PROMETHEUS_REMOTE_WRITE_URL")
  }

  external_labels = {
    app_env   = sys.env("APP_ENV"),
    namespace = sys.env("SERVICE_NAMESPACE"),
  }
}

prometheus.exporter.unix "host" {
  rootfs_path = "/"
  procfs_path = "/proc"
  sysfs_path  = "/sys"
}

prometheus.scrape "host" {
  job_name        = "integrations/unix"
  targets         = prometheus.exporter.unix.host.targets
  scrape_interval = "15s"
  forward_to      = [prometheus.remote_write.default.receiver]
}

prometheus.exporter.cadvisor "docker" {
  docker_host               = "unix:///var/run/docker.sock"
  storage_duration          = "5m"
  docker_only               = true
  disable_root_cgroup_stats = true
  store_container_labels    = true
}

prometheus.scrape "cadvisor" {
  job_name        = "integrations/cadvisor"
  targets         = prometheus.exporter.cadvisor.docker.targets
  scrape_interval = "15s"
  forward_to      = [prometheus.remote_write.default.receiver]
}

discovery.docker "containers" {
  host = "unix:///var/run/docker.sock"
}

discovery.relabel "java_metrics" {
  targets = discovery.docker.containers.targets

  rule {
    source_labels = ["__meta_docker_container_label_prometheus_scrape"]
    regex         = "true"
    action        = "keep"
  }

  rule {
    source_labels = ["__meta_docker_network_name"]
    regex         = sys.env("DOCKER_METRICS_NETWORK")
    action        = "keep"
  }

  rule {
    source_labels = ["__meta_docker_network_ip", "__meta_docker_container_label_prometheus_port"]
    separator     = ":"
    regex         = "(.+):(.+)"
    replacement   = "$1:$2"
    target_label  = "__address__"
  }

  rule {
    source_labels = ["__meta_docker_container_label_prometheus_path"]
    regex         = "(.+)"
    target_label  = "__metrics_path__"
  }

  rule {
    source_labels = ["__meta_docker_container_label_com_docker_swarm_service_name"]
    target_label  = "service_name"
  }

  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container_name"
  }

  rule {
    source_labels = ["__meta_docker_container_image"]
    target_label  = "image_name"
  }
}

prometheus.scrape "java" {
  job_name        = "integrations/java"
  targets         = discovery.relabel.java_metrics.output
  metrics_path    = sys.env("JAVA_METRICS_PATH")
  scrape_interval = "15s"
  forward_to      = [prometheus.remote_write.default.receiver]
}

discovery.relabel "docker_logs" {
  targets = []

  rule {
    source_labels = ["__meta_docker_container_label_com_docker_swarm_service_name"]
    target_label  = "service_name"
  }

  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container_name"
  }

  rule {
    source_labels = ["__meta_docker_container_image"]
    target_label  = "image_name"
  }
}

loki.process "docker" {
  stage.drop {
    older_than          = "24h"
    drop_counter_reason = "too_old"
  }

  forward_to = [loki.write.default.receiver]
}

loki.source.docker "containers" {
  host          = "unix:///var/run/docker.sock"
  targets       = discovery.docker.containers.targets
  relabel_rules = discovery.relabel.docker_logs.rules
  labels        = {
    platform  = "docker",
    app_env   = sys.env("APP_ENV"),
    namespace = sys.env("SERVICE_NAMESPACE"),
  }
  forward_to    = [loki.process.docker.receiver]
}

loki.write "default" {
  endpoint {
    url = sys.env("LOKI_PUSH_URL")
  }
}
EOF
```

## 第五步：校验配置

```bash
set -a
. /etc/alloy/alloy.env
set +a

alloy validate /etc/alloy/config.alloy
```

没有输出错误即表示配置语法通过。

## 第六步：创建 systemd 服务

```bash
cat >/etc/systemd/system/alloy.service <<'EOF'
[Unit]
Description=Grafana Alloy
After=network-online.target docker.service
Wants=network-online.target

[Service]
User=root
EnvironmentFile=/etc/alloy/alloy.env
ExecStart=/usr/bin/alloy run --server.http.listen-addr=0.0.0.0:12345 --storage.path=/var/lib/alloy/data /etc/alloy/config.alloy
Restart=always
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF
```

## 第七步：启动 Alloy

```bash
systemctl daemon-reload
systemctl enable --now alloy
systemctl status alloy
```

查看日志：

```bash
journalctl -u alloy -f
```

查看 Alloy HTTP 页面：

```bash
curl http://127.0.0.1:12345/-/ready
```

## 第八步：给 Java 服务加自动发现标签

业务服务必须先暴露 Prometheus 指标，例如：

```text
/actuator/prometheus
```

Spring Boot 常见依赖：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

常见配置：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    prometheus:
      enabled: true
```

给 Swarm 服务加容器标签：

```bash
docker service update \
  --container-label-add prometheus.scrape=true \
  --container-label-add prometheus.port=8080 \
  --container-label-add prometheus.path=/actuator/prometheus \
  wc-pro
```

如果通过 Easy-CD 配置 Docker 运行参数，写：

```text
container-labels=prometheus.scrape=true,prometheus.port=8080,prometheus.path=/actuator/prometheus
```

## 第九步：验证 Loki 日志

在 Loki 或 Grafana Explore 查询：

```logql
{app_env="release", namespace="ysb"}
```

按服务查：

```logql
{app_env="release", namespace="ysb", service_name="wc-pro"}
```

## 第十步：验证 Prometheus 指标

主机指标：

```promql
up{job="integrations/unix"}
node_cpu_seconds_total
node_memory_MemAvailable_bytes
```

Docker 容器指标：

```promql
up{job="integrations/cadvisor"}
container_cpu_usage_seconds_total
container_memory_usage_bytes
```

重点确认是否有容器级标签：

```promql
count by (container, name, image) (container_cpu_usage_seconds_total)
```

Java 指标：

```promql
up{job="integrations/java"}
jvm_memory_used_bytes
jvm_threads_live_threads
process_cpu_usage
tomcat_threads_current_threads
hikaricp_connections_active
```

## 常见问题

### 1. Alloy 没权限访问 Docker socket

报错可能类似：

```text
permission denied while trying to connect to the Docker daemon socket
```

本文档里的 systemd 服务使用 `User=root`，正常不会有这个问题。

如果不用 root，需要把运行用户加入 docker 组。

### 2. 容器日志没有进入 Loki

检查：

```bash
journalctl -u alloy -n 200
curl http://172.21.49.8:3100/ready
```

确认 service label 是否正常：

```bash
docker ps --format '{{.Names}} {{.Image}}'
```

### 3. Java 指标没有数据

排查顺序：

1. 服务是否暴露 `/actuator/prometheus`。
2. 服务是否有容器 label。
3. 服务是否在 `release-overlay` 网络。
4. `prometheus.port` 是否是容器内端口，不是宿主机 published port。
5. Alloy 是否能访问容器 IP 和端口。

查看容器 label：

```bash
docker inspect <container_id> --format '{{json .Config.Labels}}'
```

查看容器网络 IP：

```bash
docker inspect <container_id> --format '{{json .NetworkSettings.Networks}}'
```

在宿主机上测试：

```bash
curl http://<container_overlay_ip>:8080/actuator/prometheus
```

### 4. Prometheus 没有 remote_write 数据

确认 Prometheus 开启：

```bash
--web.enable-remote-write-receiver
```

测试 remote write 接口是否存在：

```bash
curl -i http://172.21.49.8:9090/api/v1/write
```

返回 405、400 这类响应不一定是坏事，至少说明接口存在；如果是 404，通常表示 remote write receiver 没开。

## 更新配置

修改配置后执行：

```bash
alloy validate /etc/alloy/config.alloy
systemctl restart alloy
journalctl -u alloy -f
```

## 停止和卸载

停止：

```bash
systemctl stop alloy
systemctl disable alloy
```

卸载 systemd 服务：

```bash
rm -f /etc/systemd/system/alloy.service
systemctl daemon-reload
```

保留配置：

```text
/etc/alloy/config.alloy
/etc/alloy/alloy.env
/var/lib/alloy/data
```

如需完全删除：

```bash
rm -rf /etc/alloy /var/lib/alloy
```

