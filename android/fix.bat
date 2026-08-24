@echo off
setlocal
set "FILE=F:\ibm hackathon\android\app\src\main\res\layout\activity_main.xml"
powershell -Command "(Get-Content -Path '%FILE%') -replace '& Act', '& Act' | Set-Content -Path '%FILE%'"