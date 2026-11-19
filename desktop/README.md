# Meghraj AI Desktop Agent

Windows companion MVP for local APK/project editing.

## Build
Install the .NET 8 SDK on Windows, then run:
```powershell
dotnet publish desktop/MeghrajAgent/MeghrajAgent.csproj -c Release -r win-x64 --self-contained true
```

The desktop app lets you select a workspace, configure an OpenAI-compatible API endpoint/model/key, inspect the selected folder, and run tool-calling coding tasks against local files. It supports listing, search, chunked reads, writes with per-task backups, rollback and Gradle/dotnet build commands.

The optional LAN listener uses port 8765 and requires the pairing token displayed in the desktop window. Only expose it on a trusted private network. Windows Firewall may ask for permission. Do not forward this port to the public internet.

This is an MVP: Anthropic/Gemini native adapters, Android-side pairing UI, diff approval UI, project archive import and signed APK delivery are follow-on work. The current desktop endpoint executes a task synchronously; keep the desktop app open during work.
