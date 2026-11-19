using System.Net;
using System.Text;
using System.Text.Json;

namespace MeghrajAgent;

public sealed class MainForm : Form
{
    readonly TextBox root = new() { Dock = DockStyle.Fill, ReadOnly = true };
    readonly TextBox baseUrl = new() { Text = "https://openrouter.ai/api/v1", Dock = DockStyle.Fill };
    readonly TextBox model = new() { Text = "openai/gpt-4.1-mini", Dock = DockStyle.Fill };
    readonly TextBox key = new() { UseSystemPasswordChar = true, Dock = DockStyle.Fill };
    readonly TextBox task = new() { Multiline = true, Height = 90, Dock = DockStyle.Fill };
    readonly TextBox log = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Both, Dock = DockStyle.Fill };
    readonly Label pairing = new() { AutoSize = true };
    string? workspace;
    readonly AgentEngine engine = new();

    public MainForm()
    {
        Text = "Meghraj AI Desktop Agent";
        Width = 980; Height = 760; MinimumSize = new Size(760, 600);
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(12), ColumnCount = 2, RowCount = 8 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 140));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        Controls.Add(layout);
        AddRow(layout, "Workspace", root);
        var choose = new Button { Text = "Select project folder", AutoSize = true };
        choose.Click += (_, _) => { using var d = new FolderBrowserDialog(); if (d.ShowDialog() == DialogResult.OK) { workspace=d.SelectedPath; root.Text=workspace; engine.SetWorkspace(workspace); WriteLog("Workspace selected: "+workspace); } };
        AddRow(layout, "", choose);
        AddRow(layout, "API base URL", baseUrl);
        AddRow(layout, "Model ID", model);
        AddRow(layout, "API key", key);
        AddRow(layout, "Task", task);
        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        var run = new Button { Text = "Run task", AutoSize = true };
        var inspect = new Button { Text = "Index workspace", AutoSize = true };
        var server = new Button { Text = "Start Android connection", AutoSize = true };
        actions.Controls.AddRange([run, inspect, server, pairing]);
        AddRow(layout, "", actions);
        layout.RowStyles.Add(new RowStyle(SizeType.Percent,100));
        layout.Controls.Add(log,0,7); layout.SetColumnSpan(log,2);
        run.Click += async (_, _) => await RunTask();
        inspect.Click += async (_, _) => { if(workspace is null) return; try { WriteLog(await engine.InspectAsync()); } catch(Exception ex){WriteLog(ex.Message);} };
        server.Click += async (_, _) => { try { var p=await engine.StartServerAsync( baseUrl.Text, model.Text, key.Text, msg=>BeginInvoke(()=>WriteLog(msg))); pairing.Text="LAN server: http://"+p+"  Pair token: "+engine.PairToken; } catch(Exception ex){WriteLog("Server error: "+ex.Message);} };
    }

    static void AddRow(TableLayoutPanel p,string label,Control c) { int r=p.RowCount++; p.RowStyles.Add(new RowStyle(SizeType.AutoSize)); p.Controls.Add(new Label{Text=label,AutoSize=true,Anchor=AnchorStyles.Left,Padding=new Padding(0,8,0,0)},0,r); p.Controls.Add(c,1,r); }
    void WriteLog(string s) { if(InvokeRequired){BeginInvoke(()=>WriteLog(s));return;} log.AppendText(DateTime.Now.ToString("HH:mm:ss")+"  "+s+Environment.NewLine); }
    async Task RunTask() { if(workspace is null){WriteLog("Select a workspace first.");return;} if(string.IsNullOrWhiteSpace(key.Text)){WriteLog("Enter an API key.");return;} try { engine.Configure(baseUrl.Text,model.Text,key.Text); await engine.RunAsync(task.Text,WriteLog); } catch(Exception ex){WriteLog("FAILED: "+ex.Message);} }
}