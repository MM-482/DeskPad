@echo off
title DeskPad Secure Python Receiver
if "%~2"=="" (
  echo Usage: start_deskpad_pc.bat certificate.pem private-key.pem
  echo See SECURE_PYTHON_RECEIVER.md for certificate provisioning and pairing.
  exit /b 2
)
python "%~dp0deskpad_server.py" --cert "%~1" --key "%~2"
