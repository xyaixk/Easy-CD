# Alloy YSB 镜像

## 构建镜像

```bash
docker build -t alloy-ysb:latest docker/alloy
```

## 创建 Swarm 服务

```bash
mkdir -p /opt/alloy
test -f /opt/alloy/java-targets.yml || printf '[]\n' > /opt/alloy/java-targets.yml

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

如果已经存在 `alloy-ysb` 服务，并且需要修改挂载参数，建议删除后重新创建：

```bash
docker service rm alloy-ysb
```

## 只采集日志的 Swarm 服务

如果暂时只需要采集容器日志到 Loki，不采集主机指标、容器指标和 Java 指标，可以先用下面这个命令创建服务。

这个命令会显式启动 `/etc/alloy/config-logs-only.alloy`，只加载 Docker 日志采集和 Loki 写入组件。

```bash
docker service create \
  --name alloy-ysb \
  --mode global \
  --network release-overlay \
  --env APP_ENV=release \
  --env SERVICE_NAMESPACE=ysb \
  --env LOKI_PUSH_URL=http://172.21.49.8:3100/loki/api/v1/push \
  --mount type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock \
  --publish mode=host,target=12345,published=12345 \
  nexus.dev.ysb/alloy-ysb:latest \
  run --server.http.listen-addr=0.0.0.0:12345 --storage.path=/var/lib/alloy/data /etc/alloy/config-logs-only.alloy
```

## Java 指标自动发现

Java 指标通过 Docker 容器标签自动发现，不需要手动维护每台宿主机上的目标列表。

业务服务需要先暴露 Spring Boot Actuator Prometheus 指标接口，例如：

```text
/actuator/prometheus
```

然后给需要采集的 Swarm 服务增加容器标签：

```bash
docker service update \
  --container-label-add prometheus.scrape=true \
  --container-label-add prometheus.port=8080 \
  --container-label-add prometheus.path=/actuator/prometheus \
  wc-pro
```

标签含义：

- `prometheus.scrape=true`：标记这个容器需要被 Alloy 采集。
- `prometheus.port=8080`：标记应用容器内的指标端口。
- `prometheus.path=/actuator/prometheus`：标记指标路径，可选。不配置时使用 `JAVA_METRICS_PATH`，默认是 `/actuator/prometheus`。

## Easy-CD 服务参数写法

在 Easy-CD 的 Docker 运行参数里可以这样写：

```text
container-labels=prometheus.scrape=true,prometheus.port=8080,prometheus.path=/actuator/prometheus
```

Easy-CD 创建 Swarm 服务时会转换为：

```bash
--container-label 'prometheus.scrape=true'
--container-label 'prometheus.port=8080'
--container-label 'prometheus.path=/actuator/prometheus'
```

Easy-CD 更新 Swarm 服务时会转换为：

```bash
--container-label-add 'prometheus.scrape=true'
--container-label-add 'prometheus.port=8080'
--container-label-add 'prometheus.path=/actuator/prometheus'
```

## 新增容器时指定指标采集标签

如果你手动使用 `docker service create` 新增一个 Java 服务，并希望 Alloy 自动采集它的 `/actuator/prometheus` 指标，需要在创建服务时加 `--container-label`。

示例：

```bash
docker service create \
  --name wc-pro \
  --network release-overlay \
  --replicas 1 \
  --container-label prometheus.scrape=true \
  --container-label prometheus.port=8080 \
  --container-label prometheus.path=/actuator/prometheus \
  nexus.dev.ysb/wc-pro:latest
```

标签说明：

- `prometheus.scrape=true`：告诉 Alloy 这个容器需要采集指标。
- `prometheus.port=8080`：容器内部暴露指标的端口。这里写容器内端口，不是宿主机映射端口。
- `prometheus.path=/actuator/prometheus`：指标路径。这个标签可选，不写时使用 Alloy 的 `JAVA_METRICS_PATH`，默认 `/actuator/prometheus`。

如果服务已经创建好了，后续再补标签，用 `docker service update`：

```bash
docker service update \
  --container-label-add prometheus.scrape=true \
  --container-label-add prometheus.port=8080 \
  --container-label-add prometheus.path=/actuator/prometheus \
  wc-pro
```

如果通过 Easy-CD 新增服务，不需要手写 `--container-label`，在 Docker 运行参数里写这一行即可：

```text
container-labels=prometheus.scrape=true,prometheus.port=8080,prometheus.path=/actuator/prometheus
```

Easy-CD 创建服务时会自动转换成 `docker service create --container-label ...`。

## 容器指标说明

Alloy 使用 `prometheus.exporter.cadvisor` 采集 Docker 容器指标，并 remote write 到 Prometheus。

Swarm `docker service create` 不支持真正的 `--privileged`。当前方案通过补齐宿主机挂载尽量满足 cAdvisor：

- `/var/run`
- `/sys`
- `/var/lib/docker`
- `/dev/disk`
- `/`

如果 Prometheus 中仍然只能看到 `id="/"` 这种根 cgroup 指标，看不到具体容器的 `container`、`name`、`image` 等标签，说明 Swarm service 权限仍然不够。这个情况下需要改成每台宿主机用 `docker run --privileged` 跑 Alloy，或者在宿主机上用 systemd 安装 Alloy。
