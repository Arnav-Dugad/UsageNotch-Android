using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;

namespace UsageNotch.Link;
public static class Program
{
    [STAThread] public static int Main(string[] args)
    {
        if (args.Contains("--self-test")) return SelfTest.Run().GetAwaiter().GetResult();
        var app = new Application(); app.Run(new LinkWindow()); return 0;
    }
}
public sealed class LinkWindow : Window
{
    private Identity? _identity; private Bridge? _bridge; private bool _closing;
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Foreground = Brushes.LightSteelBlue, Margin = new Thickness(0, 14, 0, 12) };
    private readonly ComboBox _address = new() { MinHeight = 36, Margin = new Thickness(0, 6, 0, 14) };
    private readonly Button _export; private readonly Button _start;
    public LinkWindow()
    {
        Title = "UsageNotch Link · Android pairing"; Width = 560; Height = 670; MinWidth = 440; MinHeight = 540; WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = new SolidColorBrush(Color.FromRgb(12, 22, 38)); Foreground = Brushes.WhiteSmoke; FontFamily = new FontFamily("Segoe UI"); FontSize = 14;
        var panel = new StackPanel { Margin = new Thickness(32) }; Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        panel.Children.Add(new TextBlock { Text = "USAGENOTCH  /  LINK", FontSize = 11, Foreground = Brushes.PaleTurquoise });
        panel.Children.Add(new TextBlock { Text = "Your usage. Within reach.", FontSize = 28, FontWeight = FontWeights.Light, Margin = new Thickness(0, 18, 0, 14) });
        panel.Children.Add(new TextBlock { Text = "Pair your Android phone with this PC. Keep UsageNotch and this window running for fresh readings. Your AI credentials never leave Windows.", TextWrapping = TextWrapping.Wrap, Foreground = Brushes.LightSteelBlue, LineHeight = 23 });
        panel.Children.Add(_status);
        _start = Button("Start secure link", Start); panel.Children.Add(_start);
        panel.Children.Add(new TextBlock { Text = "Wi-Fi or private VPN address", Margin = new Thickness(0, 18, 0, 0) });
        foreach (var address in Identity.Addresses()) _address.Items.Add(address);
        _address.SelectedIndex = 0; panel.Children.Add(_address);
        _export = Button("Export pairing file", Export); _export.IsEnabled = false; panel.Children.Add(_export);
        panel.Children.Add(new TextBlock { Text = "Transfer this file privately to your phone, then choose Import PC pairing in the Android app. Delete the transferred file after pairing: it grants read access to your usage.", TextWrapping = TextWrapping.Wrap, Foreground = Brushes.LightSteelBlue, Margin = new Thickness(0, 12, 0, 18), LineHeight = 21 });
        panel.Children.Add(Button("Revoke all paired phones", Revoke));
        panel.Children.Add(new TextBlock { Text = "Allow UsageNotch Link on Private networks if Windows Firewall asks. Port 43187 uses HTTPS with certificate pinning. No router port forwarding is needed. Closing this window stops the link.", TextWrapping = TextWrapping.Wrap, Foreground = Brushes.LightSteelBlue, Margin = new Thickness(0, 18, 0, 0), FontSize = 12, LineHeight = 19 });
        _status.Text = "Ready. Sharing is off until you start the link.";
        Closing += async (_, e) => { if (_closing) return; e.Cancel = true; _closing = true; if (_bridge != null) await _bridge.DisposeAsync(); _identity?.Dispose(); Close(); };
    }
    private static Button Button(string label, RoutedEventHandler handler) { var b = new Button { Content = label, Padding = new Thickness(16, 11, 16, 11), Margin = new Thickness(0, 4, 0, 4), HorizontalContentAlignment = HorizontalAlignment.Center }; b.Click += handler; return b; }
    private async void Start(object sender, RoutedEventArgs e)
    {
        _start.IsEnabled = false;
        try {
            _identity ??= new Identity(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UsageNotch", "AndroidLink"));
            _bridge = new Bridge(_identity, Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UsageNotch", "history.db"));
            await _bridge.Start(); _status.Text = "Secure link is running. Export a pairing file for the selected address."; _export.IsEnabled = _address.SelectedItem != null; _start.Content = "Link running · HTTPS";
        } catch { if (_bridge != null) await _bridge.DisposeAsync(); _bridge = null; _status.Text = "Could not start. Another copy may be using port 43187. Close it and try again."; _start.IsEnabled = true; }
    }
    private void Export(object sender, RoutedEventArgs e)
    {
        if (_identity == null || _address.SelectedItem is not string address) return;
        var dialog = new SaveFileDialog { FileName = "UsageNotch-PC.usagenotch", Filter = "UsageNotch pairing|*.usagenotch", DefaultExt = ".usagenotch" };
        if (dialog.ShowDialog(this) == true) { try { File.WriteAllText(dialog.FileName, _identity.Pairing(address)); _status.Text = "Pairing file exported. Import it on your phone while this link stays open."; } catch { _status.Text = "Could not save the file. Choose another location."; } }
    }
    private void Revoke(object sender, RoutedEventArgs e)
    {
        if (_identity == null) return;
        if (MessageBox.Show(this, "Disconnect every phone and invalidate previously exported pairing files?", "Revoke pairing", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
        try { _identity.Revoke(); _status.Text = "Old pairing keys are now invalid. Export a new file to pair again."; } catch { _status.Text = "Could not save the new key. Close the link and try again."; }
    }
}
