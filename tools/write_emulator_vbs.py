"""写模拟器隐藏启动脚本（VBS，窗口标志 0 = 隐藏；日志重定向到文件）。

背景：此前用 `cmd /c emulator.exe ...` 启动，WMI 新建控制台窗口，
模拟器的 netsimd/androidboot 日志直接打到该窗口，用户看到持续弹窗（2026-10-10 用户投诉）。
改用 wscript 执行本 VBS：无窗口、日志落盘可查。

VBS 用 GBK（ANSI/CP936）写入，Windows 脚本宿主按系统 ANSI 代码页解析。
"""
path = r"D:\AI任务\dsh-mobile-app\tools\start-emulator-hidden.vbs"
emu = r"D:\dshm\.toolchain\android-sdk\emulator\emulator.exe"
log = r"C:\TEMP\emulator-dsh_test.log"

content = (
    "' 隐藏启动安卓模拟器（无窗口；日志写 C:\\TEMP\\emulator-dsh_test.log）\r\n"
    'Set shell = CreateObject("WScript.Shell")\r\n'
    f'shell.Run "cmd /c """"{emu}"""" -avd dsh_test -no-audio -no-boot-anim -no-snapshot -no-window > ""{log}"" 2>&1", 0, False\r\n'
)

with open(path, "w", encoding="gbk", newline="") as handle:
    handle.write(content)

check = open(path, encoding="gbk").read()
print("written:", path)
print("隐藏标志在位:", ", 0, False" in check, "| 日志重定向在位:", "emulator-dsh_test.log" in check)
