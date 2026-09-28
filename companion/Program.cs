using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using Microsoft.Win32;

namespace UsageNotch.Link;
public static class Program
{
    [STAThread] public static int Main(string[] args)
    {
        if (args.Contains("--self-test")) return SelfTest.Run().GetAwaiter().GetResult();
        // One Link per Windows user: two copies would compete for port 43187 and upload twice.
        using var single = new Mutex(true, @"Local\UsageNotch.Link", out var first);
        if (!first)
        {
            if (!args.Contains("--background")) MessageBox.Show("UsageNotch Link is already running. Open it from the notification area next to the clock.", "UsageNotch Link");
            return 0;
        }
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        var window = new LinkWindow(background: args.Contains("--background"));
        app.Run(); GC.KeepAlive(window); return 0;
    }
}
/// <summary>Small preferences file beside the DPAPI identity. Contains no secrets.</summary>
public sealed class LinkSettings
{
    public bool ResumeLink { get; set; }
    public string? Address { get; set; }
    public bool TrayNoticeShown { get; set; }
    private static string File(string directory) => Path.Combine(directory, "link-settings.json");
    public static LinkSettings Load(string directory) { try { return JsonSerializer.Deserialize<LinkSettings>(System.IO.File.ReadAllText(File(directory))) ?? new(); } catch { return new(); } }
    public void Save(string directory) { try { Directory.CreateDirectory(directory); System.IO.File.WriteAllText(File(directory), JsonSerializer.Serialize(this)); } catch { } }
}
public sealed class LinkWindow : Window
{
    private static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UsageNotch");
    private static readonly string LinkDirectory = Path.Combine(Root, "AndroidLink");
    private static readonly string Database = Path.Combine(Root, "history.db");
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run", RunValue = "UsageNotch Link";
    private readonly LinkSettings _settings = LinkSettings.Load(LinkDirectory);
    private Identity? _identity; private Bridge? _bridge; private RelaySync? _sync; private readonly RelayPublisher _publisher = new();
    private readonly System.Windows.Forms.NotifyIcon _tray = new();
    private bool _quitting;
    private readonly TextBlock _status = Paragraph("", 0, 14, 12), _syncStatus = Paragraph("", 0, 8, 0);
    private readonly ComboBox _address = new() { MinHeight = 36, Margin = new Thickness(0, 6, 0, 10) };
    private readonly PasswordBox _token = new() { MinHeight = 34, Margin = new Thickness(0, 8, 0, 6), Padding = new Thickness(6, 4, 6, 4) };
    private readonly Button _start, _export, _copy, _syncOn, _syncOff;
    private readonly CheckBox _startup = new() { Content = "Start with Windows in the background", Foreground = Brushes.WhiteSmoke, Margin = new Thickness(0, 6, 0, 4) };
    private readonly StackPanel _syncSetup = new();

    public LinkWindow(bool background)
    {
        Title = "UsageNotch Link · Android pairing"; Width = 580; Height = 760; MinWidth = 460; MinHeight = 560; WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = new SolidColorBrush(Color.FromRgb(12, 22, 38)); Foreground = Brushes.WhiteSmoke; FontFamily = new FontFamily("Segoe UI"); FontSize = 14;
        var panel = new StackPanel { Margin = new Thickness(32) }; Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        panel.Children.Add(new TextBlock { Text = "USAGENOTCH  /  LINK", FontSize = 11, Foreground = Brushes.PaleTurquoise });
        panel.Children.Add(new TextBlock { Text = "Your usage. Within reach.", FontSize = 28, FontWeight = FontWeights.Light, Margin = new Thickness(0, 18, 0, 14) });
        panel.Children.Add(Paragraph("Pair your Android phone with this PC. Your AI credentials never leave Windows; the phone only receives usage percentages and reset times.", 0, 0, 0));
        panel.Children.Add(_status);
        _start = Button("Start secure link", ToggleLink); panel.Children.Add(_start);

        panel.Children.Add(Heading("Pair a phone"));
        panel.Children.Add(new TextBlock { Text = "Wi-Fi or private VPN address", Margin = new Thickness(0, 4, 0, 0) });
        foreach (var address in Identity.Addresses()) _address.Items.Add(address);
        _address.SelectedItem = _address.Items.Cast<LinkAddress>().FirstOrDefault(a => a.Address == _settings.Address) ?? (_address.Items.Count > 0 ? _address.Items[0] : null);
        _address.SelectionChanged += (_, _) => { _settings.Address = Address(); _settings.Save(LinkDirectory); Refresh(); };
        panel.Children.Add(_address);
        panel.Children.Add(Paragraph("Choose the address of the network your phone uses, usually Wi-Fi. Virtual adapters (WSL, Hyper-V) are listed last because phones cannot reach them.", 12, 0, 10));
        var pairRow = new Grid(); pairRow.ColumnDefinitions.Add(new ColumnDefinition()); pairRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(10) }); pairRow.ColumnDefinitions.Add(new ColumnDefinition());
        _export = Button("Export pairing file", Export); _copy = Button("Copy pairing code", CopyCode); Grid.SetColumn(_copy, 2);
        pairRow.Children.Add(_export); pairRow.Children.Add(_copy); panel.Children.Add(pairRow);
        panel.Children.Add(Paragraph("Send the file or code to your phone privately (for example by USB, or a message to yourself), then choose Import PC pairing or Paste pairing code in the Android app. Delete it afterwards: it grants read access to your usage.", 0, 10, 0));

        panel.Children.Add(Heading("Internet sync (optional)"));
        panel.Children.Add(Paragraph("Get readings on your phone away from home, and keep the latest one available after this PC shuts down. Each reading is encrypted on this PC with a key that only your pairing file holds, then stored in a secret gist in your own GitHub account. GitHub sees only ciphertext.", 0, 0, 0));
        var help = Paragraph("", 0, 8, 0);
        var link = new Hyperlink(new Run("Create a GitHub token")) { Foreground = Brushes.PaleTurquoise };
        link.Click += (_, _) => Open("https://github.com/settings/personal-access-tokens/new");
        help.Inlines.Add(link); help.Inlines.Add(new Run(" with Account permissions → Gists: Read and write, then paste it here. It is stored encrypted with Windows DPAPI."));
        _syncSetup.Children.Add(help); _syncSetup.Children.Add(_token);
        _syncOn = Button("Turn on internet sync", EnableSync); _syncSetup.Children.Add(_syncOn);
        panel.Children.Add(_syncSetup);
        _syncOff = Button("Turn off internet sync", DisableSync); panel.Children.Add(_syncOff);
        panel.Children.Add(_syncStatus);

        panel.Children.Add(Heading("Keep it running"));
        _startup.IsEnabled = IsInstalledExe(); _startup.IsChecked = StartsWithWindows();
        _startup.Click += (_, _) => SetStartup(_startup.IsChecked == true);
        panel.Children.Add(_startup);
        panel.Children.Add(Paragraph("Closing this window keeps Link running in the notification area next to the clock. Choose Quit there to stop sharing.", 0, 4, 0));
        panel.Children.Add(Button("Revoke all paired phones", Revoke));
        panel.Children.Add(Paragraph("Revoking invalidates every exported pairing file and code, including their internet sync key. Allow UsageNotch Link on Private networks if Windows Firewall asks. Port 43187 uses HTTPS with certificate pinning; no router port forwarding is needed.", 12, 18, 0));

        _tray.Icon = LoadIcon(); _tray.Text = "UsageNotch Link"; _tray.Visible = true;
        _tray.DoubleClick += (_, _) => ShowWindow();
        _tray.ContextMenuStrip = new System.Windows.Forms.ContextMenuStrip();
        _tray.ContextMenuStrip.Items.Add("Open UsageNotch Link", null, (_, _) => ShowWindow());
        _tray.ContextMenuStrip.Items.Add("Quit", null, async (_, _) => await Quit());
        Closing += (_, e) => {
            if (_quitting) return;
            e.Cancel = true; Hide();
            if (!_settings.TrayNoticeShown) { _settings.TrayNoticeShown = true; _settings.Save(LinkDirectory); _tray.ShowBalloonTip(4000, "UsageNotch Link is still running", "It keeps your phone updated. Right-click this icon and choose Quit to stop.", System.Windows.Forms.ToolTipIcon.Info); }
        };

        if (Identity.Exists(LinkDirectory)) try { _identity = new Identity(LinkDirectory); } catch { _status.Text = "Could not read this PC's saved link identity. Revoke and pair again if this continues."; }
        Refresh();
        if (!background) Show();
        Dispatcher.InvokeAsync(async () => {
            if (_settings.ResumeLink) await StartLink();
            StartSync();
        });
    }
    private static TextBlock Paragraph(string text, double size, double top, double bottom) => new() { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = Brushes.LightSteelBlue, FontSize = size > 0 ? size : 14, LineHeight = size > 0 ? size * 1.55 : 22, Margin = new Thickness(0, top, 0, bottom) };
    private static TextBlock Heading(string text) => new() { Text = text, FontSize = 17, Margin = new Thickness(0, 26, 0, 8) };
    private static Button Button(string label, RoutedEventHandler handler) { var b = new Button { Content = label, Padding = new Thickness(16, 11, 16, 11), Margin = new Thickness(0, 4, 0, 4), HorizontalContentAlignment = HorizontalAlignment.Center }; b.Click += handler; return b; }
    private static System.Drawing.Icon LoadIcon() { try { return System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? System.Drawing.SystemIcons.Application; } catch { return System.Drawing.SystemIcons.Application; } }
    private static void Open(string url) { try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { } }
    private void ShowWindow() { Show(); if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal; Activate(); }
    private Identity EnsureIdentity() => _identity ??= new Identity(LinkDirectory);
    private void Refresh()
    {
        var running = _bridge != null;
        _start.Content = running ? "Stop secure link" : "Start secure link";
        if (_status.Text.Length == 0) _status.Text = running ? "Secure link is running." : "Ready. Sharing is off until you start the link.";
        _export.IsEnabled = _copy.IsEnabled = _identity != null && _address.SelectedItem != null && (running || _identity.Sync != null);
        var sync = _identity?.Sync != null;
        _syncSetup.Visibility = sync ? Visibility.Collapsed : Visibility.Visible;
        _syncOff.Visibility = sync ? Visibility.Visible : Visibility.Collapsed;
        if (!sync && _syncStatus.Text.StartsWith("Internet sync is on")) _syncStatus.Text = "";
        _tray.Text = running ? "UsageNotch Link · sharing" : sync ? "UsageNotch Link · internet sync" : "UsageNotch Link · idle";
    }
    private async void ToggleLink(object sender, RoutedEventArgs e)
    {
        if (_bridge == null) { await StartLink(); return; }
        _start.IsEnabled = false;
        await _bridge.DisposeAsync(); _bridge = null; _settings.ResumeLink = false; _settings.Save(LinkDirectory);
        _status.Text = "Secure link stopped. Phones on this network can't reach this PC until you start it again."; _start.IsEnabled = true; Refresh();
    }
    private async Task StartLink()
    {
        if (_bridge != null) return;
        _start.IsEnabled = false;
        try {
            _bridge = new Bridge(EnsureIdentity(), Database); await _bridge.Start();
            _settings.ResumeLink = true; _settings.Save(LinkDirectory);
            _status.Text = "Secure link is running. Phones paired with this PC get fresh readings on the same Wi-Fi or private VPN.";
        } catch {
            if (_bridge != null) await _bridge.DisposeAsync(); _bridge = null;
            _status.Text = "Could not start. Another program may be using port 43187. Close it and try again.";
        }
        _start.IsEnabled = true; Refresh();
    }
    private void StartSync()
    {
        if (_identity?.Sync == null || _sync != null) return;
        _sync = new RelaySync(_identity, Database, _publisher, text => Dispatcher.InvokeAsync(() => _syncStatus.Text = text));
        _sync.Start();
    }
    private async Task StopSync() { if (_sync != null) { await _sync.DisposeAsync(); _sync = null; } }
    private async void EnableSync(object sender, RoutedEventArgs e)
    {
        var token = _token.Password; if (string.IsNullOrWhiteSpace(token)) { _syncStatus.Text = "Paste a GitHub token first."; return; }
        _syncOn.IsEnabled = false; _syncStatus.Text = "Creating your secret sync gist…";
        try {
            var identity = EnsureIdentity();
            string plain;
            try { plain = System.Text.Json.JsonSerializer.Serialize(SnapshotReader.Read(Database, RelaySync.UploadPoints), new JsonSerializerOptions(JsonSerializerDefaults.Web)); }
            catch { plain = RelaySync.EmptySnapshot(); }
            var state = await _publisher.Create(token, s => RelayCrypto.Seal(s.KeyBytes, plain));
            identity.SetSync(state); _token.Clear();
            _syncStatus.Text = "Internet sync is on. Export a new pairing file or code and import it on your phone to use it.";
            StartSync();
        }
        catch (RelayRejectedException ex) { _syncStatus.Text = ex.Message; }
        catch (Exception) { _syncStatus.Text = "Couldn't reach GitHub. Check your internet connection and try again."; }
        _syncOn.IsEnabled = true; Refresh();
    }
    private async void DisableSync(object sender, RoutedEventArgs e)
    {
        if (_identity?.Sync is not { } sync) return;
        if (MessageBox.Show(this, "Turn off internet sync and delete the encrypted gist from your GitHub account? Phones keep working on the same Wi-Fi.", "Internet sync", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
        _syncOff.IsEnabled = false;
        await StopSync();
        var deleted = true; try { await _publisher.Delete(sync); } catch { deleted = false; }
        _identity.SetSync(null);
        _syncStatus.Text = deleted ? "Internet sync is off and the gist was deleted." : "Internet sync is off. The gist could not be deleted automatically; you can delete it at gist.github.com.";
        _syncOff.IsEnabled = true; Refresh();
    }
    private string? Address() => (_address.SelectedItem as LinkAddress)?.Address;
    private void Export(object sender, RoutedEventArgs e)
    {
        if (_identity == null || Address() is not { } address) return;
        var dialog = new SaveFileDialog { FileName = "UsageNotch-PC.usagenotch", Filter = "UsageNotch pairing|*.usagenotch", DefaultExt = ".usagenotch" };
        if (dialog.ShowDialog(this) == true) { try { File.WriteAllText(dialog.FileName, _identity.Pairing(address)); _status.Text = "Pairing file exported. Import it on your phone."; } catch { _status.Text = "Could not save the file. Choose another location."; } }
    }
    private void CopyCode(object sender, RoutedEventArgs e)
    {
        if (_identity == null || Address() is not { } address) return;
        try { Clipboard.SetText(_identity.PairingCode(address)); _status.Text = "Pairing code copied. Send it to yourself privately, then choose Paste pairing code on your phone."; }
        catch { _status.Text = "Could not use the clipboard. Try again, or export a pairing file instead."; }
    }
    private async void Revoke(object sender, RoutedEventArgs e)
    {
        if (_identity == null) { _status.Text = "No phone has been paired with this PC yet."; return; }
        if (MessageBox.Show(this, "Disconnect every phone and invalidate previously exported pairing files and codes?", "Revoke pairing", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
        try {
            _identity.Revoke(); _status.Text = "Old pairing keys are now invalid. Export a new file or code to pair again.";
            if (_sync != null) await _sync.Upload(force: true); // Re-encrypt the latest reading with the new key.
        } catch { _status.Text = "Could not save the new key. Close the link and try again."; }
        Refresh();
    }
    private static bool IsInstalledExe() => Environment.ProcessPath is { } path && Path.GetFileName(path).Equals("UsageNotch.Link.exe", StringComparison.OrdinalIgnoreCase);
    private static bool StartsWithWindows() { try { using var key = Registry.CurrentUser.OpenSubKey(RunKey); return key?.GetValue(RunValue) is string; } catch { return false; } }
    private void SetStartup(bool on)
    {
        try {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (on) key.SetValue(RunValue, $"\"{Environment.ProcessPath}\" --background"); else key.DeleteValue(RunValue, false);
        } catch { _startup.IsChecked = StartsWithWindows(); _status.Text = "Windows didn't allow changing startup apps."; }
    }
    private async Task Quit()
    {
        if (_quitting) return; _quitting = true;
        await StopSync();
        if (_bridge != null) { await _bridge.DisposeAsync(); _bridge = null; }
        _tray.Visible = false; _tray.Dispose(); _publisher.Dispose(); _identity?.Dispose();
        Close(); Application.Current.Shutdown();
    }
}
