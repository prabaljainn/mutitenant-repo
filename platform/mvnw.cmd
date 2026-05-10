@REM ----------------------------------------------------------------------------
@REM Maven Wrapper script (Windows)
@REM ----------------------------------------------------------------------------
@echo off
setlocal

set BASE_DIR=%~dp0
set WRAPPER_JAR=%BASE_DIR%.mvn\wrapper\maven-wrapper.jar

if not exist "%WRAPPER_JAR%" (
    echo Error: Maven Wrapper jar not found at %WRAPPER_JAR% 1>&2
    exit /b 1
)

if defined JAVA_HOME (
    set JAVA_EXEC=%JAVA_HOME%\bin\java.exe
) else (
    set JAVA_EXEC=java
)

"%JAVA_EXEC%" %MAVEN_OPTS% ^
    -classpath "%WRAPPER_JAR%" ^
    "-Dmaven.multiModuleProjectDirectory=%BASE_DIR%" ^
    org.apache.maven.wrapper.MavenWrapperMain %*
