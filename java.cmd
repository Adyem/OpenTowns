@echo off
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
set "JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
set "PATH=%JAVA_HOME%\bin;%PATH%"
"%JAVA_HOME%\bin\java.exe" %*
