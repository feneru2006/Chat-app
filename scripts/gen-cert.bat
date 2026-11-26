@echo off
REM Tao chung chi tu ky cho Kenh 1 (Windows). Chay 1 lan o thu muc goc du an.
REM Neu Client ket noi bang IP LAN, dat: set IP=192.168.1.10 truoc khi chay.
if "%IP%"=="" set IP=127.0.0.1
keytool -genkeypair -alias zalo -keyalg RSA -keysize 2048 -validity 365 -dname "CN=zalo-server" -ext "san=dns:localhost,ip:127.0.0.1,ip:%IP%" -keystore server.jks -storepass changeit -keypass changeit
keytool -exportcert -alias zalo -keystore server.jks -storepass changeit -file server.crt
keytool -importcert -noprompt -alias zalo -file server.crt -keystore client-trust.jks -storepass changeit
echo OK: server.jks (cho Server) + client-trust.jks (cho moi Client)
