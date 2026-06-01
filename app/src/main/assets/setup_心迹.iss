; 心迹 - 安装程序脚本
; 使用方法：
; 1. 安装 Inno Setup (https://jrsoftware.org/isdl.php)
; 2. 把 heart_monitor.py 放在 script 文件夹
; 3. 右键这个 .iss 文件 → Compile
; 4. 生成 Setup_心迹.exe

#define MyAppName "心迹"
#define MyAppVersion "2.5"
#define MyAppPublisher "心迹 Team"
#define MyAppURL "https://github.com/a2006-dev/heart"
#define MyAppExeName "心迹.exe"

[Setup]
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
OutputDir=.
OutputBaseFilename=Setup_心迹_v{#MyAppVersion}
Compression=lzma
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}
SetupIconFile=heart_icon.ico
; 请求管理员权限（用于防火墙配置）
PrivilegesRequired=admin

[Languages]
Name: "chinese"; MessagesFile: "ChineseSimplified.isl"
Name: "english"; MessagesFile: "Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "快捷方式："
Name: "startup"; Description: "开机自动启动"; GroupDescription: "启动选项："

[Files]
; 主程序（用 PyInstaller 打包好的 exe）
Source: "heart_monitor.exe"; DestDir: "{app}"; Flags: ignoreversion
; 其他资源文件
Source: "scan_ble.py"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 心迹"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
; 安装完成后可选启动
Filename: "{app}\{#MyAppExeName}"; Description: "启动 心迹"; Flags: postinstall nowait skipifsilent

; 添加防火墙规则（管理员权限）
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""心迹 HeartRate"" dir=in action=allow protocol=tcp localport=9091 description=""心迹心率接收"""; Flags: runhidden; StatusMsg: "正在配置防火墙..."

[UninstallRun]
; 卸载时删除防火墙规则
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""心迹 HeartRate"""; Flags: runhidden

[Registry]
; 开机自启（如果用户勾选了）
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "心迹"; ValueData: "{app}\{#MyAppExeName}"; Tasks: startup

[Code]
// 安装前检查 Python 是否可用（如果用户选择用源码运行而非 exe）
function InitializeSetup: Boolean;
begin
  Result := True;
end;

// 安装完成后的欢迎提示
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    // 安装完成
  end;
end;
