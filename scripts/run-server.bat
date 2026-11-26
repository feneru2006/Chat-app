@echo off
cd /d "%~dp0.."
call mvn -q -pl common,server -am install -DskipTests && mvn -q -pl server exec:java -Dexec.args="server.properties"
