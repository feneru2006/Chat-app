@echo off
REM Tao chung chi TLS tu ky cho Kenh 1, TU DONG lay IP cua may hien tai. Chay lai bao nhieu lan cung duoc.
REM Chay duoc tu bat ky thu muc nao; file tao ra nam o thu muc goc du an.
setlocal
cd /d "%~dp0.."
set IPS=
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0get-ips.ps1"') do set IPS=%%i
set SAN=dns:localhost,ip:127.0.0.1
if not "%IPS%"=="" set SAN=%SAN%,%IPS%
if not "%IP%"=="" set SAN=%SAN%,ip:%IP%
echo SAN: %SAN%
del /q server.jks server.crt client-trust.jks 2>nul
keytool -genkeypair -alias zalo -keyalg RSA -keysize 2048 -validity 365 -dname "CN=zalo-server" -ext "san=%SAN%" -keystore server.jks -storepass changeit -keypass changeit
keytool -exportcert -alias zalo -keystore server.jks -storepass changeit -file server.crt
keytool -importcert -noprompt -alias zalo -file server.crt -keystore client-trust.jks -storepass changeit
echo.
echo OK: server.jks (cho Server) + client-trust.jks (copy cho moi may Client)
