@echo off
rem Compiles the project into target\classes and opens the case menu (bureaucracy.menu.MainMenu).
rem Needs JDK 25+: JAVA_HOME if set, else javac on the PATH, else a JDK downloaded by IntelliJ (%USERPROFILE%\.jdks).
setlocal
cd /d "%~dp0"

set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC for /f "delims=" %%j in ('where javac 2^>nul') do if not defined JAVAC set "JAVAC=%%j"
if not defined JAVAC for /d %%d in ("%USERPROFILE%\.jdks\*") do if exist "%%d\bin\javac.exe" set "JAVAC=%%d\bin\javac.exe"
if not defined JAVAC (
    echo No JDK found: set JAVA_HOME to a JDK 25+ or put javac on the PATH.
    exit /b 1
)
for %%j in ("%JAVAC%") do set "JAVA=%%~dpjjava.exe"

rem The menu starts each case by class name, so javac only compiles the cases listed here
rem (plus everything they use, found through -sourcepath)
"%JAVAC%" -d target\classes -sourcepath src ^
    src\bureaucracy\menu\MainMenu.java ^
    src\bureaucracy\simulation\SimulationMain.java ^
    src\bureaucracy\faulty1\Faulty1Demo.java ^
    src\bureaucracy\faulty2\DeadlockDemo.java ^
    src\bureaucracy\office\CoffeeBreakDemo.java ^
    src\bureaucracy\customer\CustomerDemo.java ^
    src\bureaucracy\harness\Harness.java
if errorlevel 1 exit /b 1

"%JAVA%" -cp target\classes bureaucracy.menu.MainMenu
