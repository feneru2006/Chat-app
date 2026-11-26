@echo off
cd /d "%~dp0.."
call mvn -q -pl common,client -am install -DskipTests && mvn -q -pl client javafx:run
