FROM grafana/alloy:latest

COPY config.alloy /etc/alloy/config.alloy
COPY config-logs-only.alloy /etc/alloy/config-logs-only.alloy

ENV APP_ENV=release
ENV SERVICE_NAMESPACE=ysb
ENV LOKI_PUSH_URL=http://172.21.49.8:3100/loki/api/v1/push
ENV PROMETHEUS_REMOTE_WRITE_URL=http://172.21.49.8:9090/api/v1/write
ENV JAVA_METRICS_PATH=/actuator/prometheus
ENV DOCKER_METRICS_NETWORK=release-overlay

EXPOSE 12345

CMD ["run", "--server.http.listen-addr=0.0.0.0:12345", "--storage.path=/var/lib/alloy/data", "/etc/alloy/config.alloy"]
