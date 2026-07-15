# Alloy 会话上下文

本文档记录本次会话里围绕 Alloy、Loki、Prometheus 做过的事情，方便后续继续处理时快速恢复上下文。

更新时间：2026-07-09

## 当前目标

这个项目需要一个定制 Alloy 镜像，用在 Docker Swarm 集群中，承担：

- Docker 容器日志采集，写入 Loki。
- 主机指标采集，remote write 到 Prometheus。
- Docker 容器指标采集，remote write 到 Prometheus。
- Java 应用指标采集，后续通过 Docker 容器标签自动发现 `/actuator/prometheus`。

当前镜像名：

```text
nexus.dev.ysb/alloy-ysb:latest
```

最近一次已推送 digest：

```text
sha256:197a1d09900365fb197158ad16ba76b0ec6a6f925162c9e36d73e88166cf9b63
```

## 相关环境

已知服务器和组件：

```text
Swarm 主节点: 10.10.0.63
Loki:        http://172.21.49.8:3100
Prometheus:  http://172.21.49.8:9090
Grafana:     http://172.21.49.8:3000
Swarm 网络:  release-overlay
APP_ENV:     release
namespace:   ysb
```

Nexus 镜像仓库：

```text
nexus.dev.ysb
用户: deploy-user
```

注意：密码不要写进仓库文档。需要登录时从会话历史或安全凭据处获取。

## 文件说明

Alloy 相关文件位于：

```text
docker/alloy/
```

当前包含：

```text
docker/alloy/Dockerfile
docker/alloy/config.alloy
docker/alloy/config-logs-only.alloy
docker/alloy/README.md
docker/alloy/SESSION_CONTEXT.md
```

之前曾经有过 `java-targets.yml`，但已经删除。原因是 Docker Swarm 的任务会自动调度到不同宿主机，手工维护每台宿主机上的 Java targets 不合适。

## 当前 Alloy 配置结构

`config.alloy` 里目前包含这些组件：

### 1. Prometheus remote_write

```alloy
prometheus.remote_write "default"
```

目标地址来自环境变量：

```text
PROMETHEUS_REMOTE_WRITE_URL
```

当前 create 命令里使用：

```text
http://172.21.49.8:9090/api/v1/write
```

外部标签：

```text
app_env   = APP_ENV
namespace = SERVICE_NAMESPACE
```

注意：Prometheus 必须开启 remote write receiver，否则 Alloy 写不进去。Prometheus v3 常见启动参数是：

```bash
--web.enable-remote-write-receiver
```

### 2. 主机指标

```alloy
prometheus.exporter.unix "host"
prometheus.scrape "host"
```

job 名：

```text
integrations/unix
```

依赖宿主机挂载：

```text
/                 -> /rootfs
/sys              -> /sys
```

### 3. Docker 容器指标

```alloy
prometheus.exporter.cadvisor "docker"
prometheus.scrape "cadvisor"
```

job 名：

```text
integrations/cadvisor
```

当前配置重点：

```text
docker_host               = unix:///var/run/docker.sock
docker_only               = true
disable_root_cgroup_stats = true
store_container_labels    = true
```

依赖宿主机挂载：

```text
/var/run
/sys
/var/lib/docker
/dev/disk
/
```

已知问题：用户查 Prometheus 时看到 cAdvisor 指标存在，但 `container_cpu_usage_seconds_total` 只有每台机器的根 cgroup：

```text
id="/"
```

没有看到容器级别的 `container` / `name` / `image` 标签。

判断：这通常说明 cAdvisor 权限或挂载不足。Swarm `docker service create` 本身不支持真正的 `--privileged`，所以 service 方式可能仍然无法拿到完整容器级指标。如果补齐挂载后仍然只有 `id="/"`，需要改成：

- 每台宿主机使用 `docker run --privileged` 跑 Alloy；或
- 在每台宿主机上用 systemd 安装 Alloy。

### 4. Docker 自动发现

```alloy
discovery.docker "containers"
```

使用 Docker socket：

```text
unix:///var/run/docker.sock
```

日志采集和 Java 指标自动发现都依赖这个组件。

### 5. Java 指标自动发现

```alloy
discovery.relabel "java_metrics"
prometheus.scrape "java"
```

job 名：

```text
integrations/java
```

不再使用 `java-targets.yml`。现在通过 Docker 容器标签发现。

业务服务需要带容器标签：

```text
prometheus.scrape=true
prometheus.port=8080
prometheus.path=/actuator/prometheus
```

其中：

- `prometheus.scrape=true` 表示需要被 Alloy 采集。
- `prometheus.port=8080` 表示应用容器内的指标端口。
- `prometheus.path=/actuator/prometheus` 可选，不配置时使用 `JAVA_METRICS_PATH`，默认 `/actuator/prometheus`。

Alloy 还会按网络过滤：

```text
DOCKER_METRICS_NETWORK=release-overlay
```

也就是说，只有处在 `release-overlay` 网络上的目标会被 Java scrape 采集。

Easy-CD 已经配合增加了 Docker 参数：

```text
container-labels=prometheus.scrape=true,prometheus.port=8080,prometheus.path=/actuator/prometheus
```

创建服务时会转成：

```bash
--container-label 'prometheus.scrape=true'
--container-label 'prometheus.port=8080'
--container-label 'prometheus.path=/actuator/prometheus'
```

更新服务时会转成：

```bash
--container-label-add 'prometheus.scrape=true'
--container-label-add 'prometheus.port=8080'
--container-label-add 'prometheus.path=/actuator/prometheus'
```

前提：Java 应用本身要启用 Spring Boot Actuator Prometheus，例如能访问：

```text
http://<service>:8080/actuator/prometheus
```

否则 Prometheus 中不会出现：

```text
jvm_memory_used_bytes
jvm_threads_live_threads
process_cpu_usage
tomcat_threads_current_threads
hikaricp_connections_active
jdbc_*
logback_*
spring_*
```

### 6. Loki 日志采集

```alloy
loki.source.docker "containers"
loki.process "docker"
loki.write "default"
```

目标地址来自环境变量：

```text
LOKI_PUSH_URL
```

当前使用：

```text
http://172.21.49.8:3100/loki/api/v1/push
```

日志标签：

```text
platform=docker
app_env=release
namespace=ysb
service_name=<Swarm service name>
container_name=<Docker container name>
image_name=<Docker image>
```

之前遇到过 Loki 拒收旧日志的问题，所以加了：

```alloy
stage.drop {
  older_than = "24h"
}
```

这样 Alloy 启动时不会把太旧的 Docker 历史日志推给 Loki。

## 镜像构建和推送

本机 Windows PowerShell 没有 Docker，但 WSL 已经可用。

已执行过：

```bash
wsl docker --version
```

结果：

```text
Docker version 29.4.1, build 055a478
```

登录 Nexus：

```bash
wsl sh -lc "printf '%s' '<PASSWORD>' | docker login nexus.dev.ysb -u deploy-user --password-stdin"
```

构建：

```bash
wsl sh -lc "cd /mnt/d/Project/Easy-CD && docker build -t alloy-ysb:latest -t nexus.dev.ysb/alloy-ysb:latest docker/alloy"
```

配置校验：

```bash
wsl docker run --rm \
  --env APP_ENV=release \
  --env SERVICE_NAMESPACE=ysb \
  --env LOKI_PUSH_URL=http://172.21.49.8:3100/loki/api/v1/push \
  --env PROMETHEUS_REMOTE_WRITE_URL=http://172.21.49.8:9090/api/v1/write \
  --env JAVA_METRICS_PATH=/actuator/prometheus \
  --env DOCKER_METRICS_NETWORK=release-overlay \
  nexus.dev.ysb/alloy-ysb:latest validate /etc/alloy/config.alloy
```

校验结果：通过。

推送：

```bash
wsl docker push nexus.dev.ysb/alloy-ysb:latest
```

推送结果：

```text
latest: digest: sha256:197a1d09900365fb197158ad16ba76b0ec6a6f925162c9e36d73e88166cf9b63 size: 856
```

注意：第一次 validate 时发现 `env()` 在当前 Alloy 版本中已 deprecated，并导致 `validate` 失败。已经把 `config.alloy` 里的环境变量读取全部改成：

```alloy
sys.env("NAME")
```

## 完整采集 create 命令

这个命令用于日志 + 主机指标 + 容器指标 + Java 指标自动发现：

```bash
docker service create \
  --name alloy-ysb \
  --mode global \
  --network release-overlay \
  --env APP_ENV=release \
  --env SERVICE_NAMESPACE=ysb \
  --env LOKI_PUSH_URL=http://172.21.49.8:3100/loki/api/v1/push \
  --env PROMETHEUS_REMOTE_WRITE_URL=http://172.21.49.8:9090/api/v1/write \
  --env JAVA_METRICS_PATH=/actuator/prometheus \
  --env DOCKER_METRICS_NETWORK=release-overlay \
  --mount type=bind,src=/,dst=/rootfs,readonly \
  --mount type=bind,src=/var/run,dst=/var/run \
  --mount type=bind,src=/sys,dst=/sys,readonly \
  --mount type=bind,src=/var/lib/docker,dst=/var/lib/docker,readonly \
  --mount type=bind,src=/dev/disk,dst=/dev/disk,readonly \
  --publish mode=host,target=12345,published=12345 \
  nexus.dev.ysb/alloy-ysb:latest
```

如果服务已存在，并且要改挂载参数，建议先删再建：

```bash
docker service rm alloy-ysb
```

## 只采集日志 create 命令

用户要求在 README 中额外增加“只收集日志，不收集指标”的 create 命令，要求是“加，不是改”。

当前 README 里已经新增了这一段：

```bash
docker service create \
  --name alloy-ysb \
  --mode global \
  --network release-overlay \
  --env APP_ENV=release \
  --env SERVICE_NAMESPACE=ysb \
  --env LOKI_PUSH_URL=http://172.21.49.8:3100/loki/api/v1/push \
  --env PROMETHEUS_REMOTE_WRITE_URL=http://127.0.0.1:9090/api/v1/write \
  --mount type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock \
  --publish mode=host,target=12345,published=12345 \
  nexus.dev.ysb/alloy-ysb:latest
```

重要说明：最开始的“只采日志”命令仍然带过 `PROMETHEUS_REMOTE_WRITE_URL`，原因是当时镜像只有完整 `config.alloy`，里面包含 Prometheus 组件，配置解析需要这个变量。后来已经补充了日志专用配置：

```text
docker/alloy/config-logs-only.alloy
```

README 中的只采日志命令已经改为显式启动：

```bash
run --server.http.listen-addr=0.0.0.0:12345 --storage.path=/var/lib/alloy/data /etc/alloy/config-logs-only.alloy
```

因此真正只加载 Docker 日志采集和 Loki 写入组件，不再需要 `PROMETHEUS_REMOTE_WRITE_URL`。

## 已知查询现象

用户曾在 Prometheus 中查到：

```text
jobs:
- integrations/cadvisor
- integrations/unix
- prometheus
```

说明：

- 主机指标有。
- cAdvisor job 有。
- Java job 当时没有，因为那时还没有 Java 自动发现配置，也没有业务服务容器标签。

用户还查到：

```text
container_* 指标总数: 1671
container_cpu_usage_seconds_total: 3
container_memory_usage_bytes: 3
```

但 `container_cpu_usage_seconds_total` 只有：

```text
id="/"
instance="e4c38ed3efd3"
instance="40cc7b14746c"
instance="82b861787673"
```

没有容器级标签。后续重建 Alloy service 后，需要再次确认这些指标是否改善。

## 后续建议验证步骤

### 1. 重建 Alloy service

在 Swarm 主节点执行：

```bash
docker service rm alloy-ysb
```

然后执行 README 中完整采集 create 命令，或者先执行只采日志 create 命令。

### 2. 查看 Alloy service 状态

```bash
docker service ps alloy-ysb --no-trunc
docker service logs alloy-ysb --tail 200
```

### 3. 验证 Loki 日志

在 Loki/Grafana 中查：

```logql
{app_env="release", namespace="ysb"}
```

或者按服务查：

```logql
{app_env="release", namespace="ysb", service_name="wc-pro"}
```

### 4. 验证容器指标

Prometheus 中查：

```promql
container_cpu_usage_seconds_total
```

重点看是否出现容器级标签，例如：

```text
container
name
image
container_label_com_docker_swarm_service_name
```

如果仍只有 `id="/"`，说明 Swarm service 权限仍不足，需考虑 privileged 方案。

### 5. 验证 Java 指标

先给业务服务加容器 label：

```bash
docker service update \
  --container-label-add prometheus.scrape=true \
  --container-label-add prometheus.port=8080 \
  --container-label-add prometheus.path=/actuator/prometheus \
  wc-pro
```

或者通过 Easy-CD Docker 参数配置：

```text
container-labels=prometheus.scrape=true,prometheus.port=8080,prometheus.path=/actuator/prometheus
```

然后在 Prometheus 中查：

```promql
up{job="integrations/java"}
jvm_memory_used_bytes
process_cpu_usage
tomcat_threads_current_threads
hikaricp_connections_active
```

如果没有数据，排查顺序：

1. 业务容器是否真的有 label。
2. 业务容器是否在 `release-overlay` 网络。
3. 容器内端口是否是 `8080`。
4. `/actuator/prometheus` 是否已经暴露。
5. Spring Boot 是否引入 `micrometer-registry-prometheus`。
6. Actuator exposure 是否包含 `prometheus`。

## 本会话里和 Alloy 相关的结论

- `java-targets.yml` 静态文件方案不适合 Swarm 自动调度，已废弃。
- Java 指标改为 Docker container label 自动发现。
- Easy-CD 已支持 `container-labels` 参数，方便给 Swarm task 容器打 label。
- Alloy 镜像已经构建、validate、推送。
- 当前最新推送镜像 digest 是 `sha256:197a1d09900365fb197158ad16ba76b0ec6a6f925162c9e36d73e88166cf9b63`。
- `config.alloy` 已使用 `sys.env()`，不再使用 deprecated 的 `env()`。
- Swarm service 方式可能无法完全替代 privileged cAdvisor，如果容器指标仍不完整，要切换部署方式。
