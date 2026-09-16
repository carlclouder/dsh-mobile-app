"""用任务计划 XML 创建 DSH-AuthProxy（wscript 隐藏启动代理）——绕开 schtasks 命令行引号问题。

schtasks 的 /TR 参数经 pwsh 传参会丢引号（实测 Invalid syntax: Mandatory option 'sc' is missing），
改用 /XML 导入：XML 内 Command 字段可直接写完整命令行，无转义冲突。
XML 必须以 Unicode（UTF-16LE + BOM）保存，schtasks 才认。
"""
import os

xml = """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>DSH 自动登录代理（隐藏窗口）：手机 App 用裸地址无 token 访问 dsh WebUI 时自动注入会话 Cookie</Description>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>true</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>7</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>wscript.exe</Command>
      <Arguments>"C:\\Users\\Carl\\.dsh\\start-auth-proxy.vbs"</Arguments>
    </Exec>
  </Actions>
</Task>
"""

path = r"C:\Users\Carl\.dsh\dsh-auth-proxy-task.xml"
with open(path, "w", encoding="utf-16", newline="") as f:
    f.write(xml)

# 回读校验（UTF-16 可解码 + 关键字段在位）
with open(path, encoding="utf-16") as f:
    check = f.read()
print("written:", path)
print("verify vbs arg:", "start-auth-proxy.vbs" in check, "| hidden:", "<Hidden>true</Hidden>" in check)
print("first bytes:", open(path, "rb").read(2))
