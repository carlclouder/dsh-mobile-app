' 隐藏启动安卓模拟器（无窗口；日志写 C:\TEMP\emulator-dsh_test.log）
Set shell = CreateObject("WScript.Shell")
shell.Run "cmd /c """"D:\dshm\.toolchain\android-sdk\emulator\emulator.exe"""" -avd dsh_test -no-audio -no-boot-anim -no-snapshot -no-window > ""C:\TEMP\emulator-dsh_test.log"" 2>&1", 0, False
