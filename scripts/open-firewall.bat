@echo off
REM Mo tuong lua Windows cho Zalo: cong 9000, 9001 (Server) va java.exe (cong P2P ngau nhien cua Client).
REM PHAI chay voi quyen Administrator: chuot phai file nay, chon "Run as administrator".
net session >nul 2>&1
if errorlevel 1 goto :noadmin
for /f "tokens=2 delims==" %%a in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /C:"java.home"') do set JH=%%a
set JH=%JH:~1%
echo Java: %JH%
netsh advfirewall firewall delete rule name="Zalo-Java" >nul 2>&1
netsh advfirewall firewall delete rule name="Zalo-Server-Ports" >nul 2>&1
netsh advfirewall firewall add rule name="Zalo-Java" dir=in action=allow program="%JH%\bin\java.exe" enable=yes profile=any
netsh advfirewall firewall add rule name="Zalo-Server-Ports" dir=in action=allow protocol=TCP localport=9000,9001 profile=any
echo.
echo Xong. De go bo: netsh advfirewall firewall delete rule name="Zalo-Java"
pause
exit /b 0
:noadmin
echo Hay dong cua so nay, chuot phai file open-firewall.bat va chon "Run as administrator".
pause
exit /b 1
