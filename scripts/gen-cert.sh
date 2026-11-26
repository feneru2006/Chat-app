#!/usr/bin/env bash
# Tạo chứng chỉ tự ký cho Kênh 1. Chạy 1 lần, rồi copy client-trust.jks cho mọi máy Client.
# Nếu Client kết nối bằng IP LAN (không phải localhost), thêm IP vào SAN: ví dụ IP=192.168.1.10 ./gen-cert.sh
set -e
IP=${IP:-127.0.0.1}
keytool -genkeypair -alias zalo -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=zalo-server" -ext "san=dns:localhost,ip:127.0.0.1,ip:${IP}" \
  -keystore server.jks -storepass changeit -keypass changeit
keytool -exportcert -alias zalo -keystore server.jks -storepass changeit -file server.crt
keytool -importcert -noprompt -alias zalo -file server.crt -keystore client-trust.jks -storepass changeit
echo "OK: server.jks (cho Server) + client-trust.jks (cho Client)"
