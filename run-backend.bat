@echo off
echo Loading environment variables from .env...
if exist .env (
    for /f "usebackq tokens=1* delims==" %%i in (".env") do (
        set %%i=%%j
    )
) else (
    echo .env file not found!
)
echo Starting backend...
call mvnw.cmd spring-boot:run
