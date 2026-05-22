using System.Diagnostics;
using System.Net.Http;
using Microsoft.Web.WebView2.WinForms;

ApplicationConfiguration.Initialize();
Application.Run(new GoHomeWindow());

sealed class GoHomeWindow : Form
{
    private const string ControlAddress = "127.0.0.1:47779";
    private readonly WebView2 _webView = new() { Dock = DockStyle.Fill };
    private Process? _clientCore;

    public GoHomeWindow()
    {
        Text = "Go Home";
        MinimumSize = new Size(960, 640);
        Width = 1220;
        Height = 820;
        Controls.Add(_webView);
        Shown += async (_, _) => await InitializeAsync();
        FormClosed += (_, _) => StopClientCore();
    }

    private async Task InitializeAsync()
    {
        try
        {
            StartClientCore();
            await WaitForClientCoreAsync();
            await _webView.EnsureCoreWebView2Async();
            _webView.CoreWebView2.Settings.AreDevToolsEnabled = false;
            _webView.CoreWebView2.Settings.IsStatusBarEnabled = false;
            _webView.Source = new Uri($"http://{ControlAddress}/");
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "Go Home startup failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Close();
        }
    }

    private void StartClientCore()
    {
        var appDir = AppContext.BaseDirectory;
        var corePath = Path.Combine(appDir, "go-home-client-core.exe");
        var uiDir = Path.Combine(appDir, "ui");
        if (!File.Exists(corePath))
        {
            throw new FileNotFoundException("go-home-client-core.exe is missing from the Windows client package.", corePath);
        }
        if (!Directory.Exists(uiDir))
        {
            throw new DirectoryNotFoundException("The client UI directory is missing from the Windows client package.");
        }

        _clientCore = Process.Start(new ProcessStartInfo
        {
            FileName = corePath,
            Arguments = $"-control-addr {ControlAddress} -ui-dir \"{uiDir}\"",
            WorkingDirectory = appDir,
            CreateNoWindow = true,
            UseShellExecute = false
        });
        if (_clientCore is null)
        {
            throw new InvalidOperationException("Unable to start the Go Home client core.");
        }
    }

    private static async Task WaitForClientCoreAsync()
    {
        using var client = new HttpClient { Timeout = TimeSpan.FromMilliseconds(600) };
        for (var attempt = 0; attempt < 30; attempt++)
        {
            try
            {
                using var response = await client.GetAsync($"http://{ControlAddress}/api/status");
                if (response.IsSuccessStatusCode)
                {
                    return;
                }
            }
            catch (HttpRequestException)
            {
            }
            catch (TaskCanceledException)
            {
            }
            await Task.Delay(200);
        }
        throw new TimeoutException("The local Go Home client core did not become ready.");
    }

    private void StopClientCore()
    {
        if (_clientCore is null || _clientCore.HasExited)
        {
            return;
        }
        _clientCore.Kill(entireProcessTree: true);
        _clientCore.Dispose();
    }
}
