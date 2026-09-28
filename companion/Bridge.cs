using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Data.Sqlite;

namespace UsageNotch.Link;

public sealed record LinkAddress(string Address, string Adapter, int Rank)
{
    public override string ToString() => $"{Address}  ·  {Adapter}";
}
/// <summary>Optional internet sync: a secret gist in the owner's GitHub account holding AES-GCM ciphertext.</summary>
public sealed record SyncState(string GitHubToken, string GistId, string Owner, string Key)
{
    public string RawUrl => $"https://gist.githubusercontent.com/{Owner}/{GistId}/raw/{RelayPublisher.FileName}";
    public byte[] KeyBytes => Convert.FromBase64String(Key.Replace('-', '+').Replace('_', '/') + new string('=', (4 - Key.Length % 4) % 4));
    public static string NewKey() => Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
public sealed class Identity : IDisposable
{
    private readonly string _file;
    public string Token { get; private set; }
    public SyncState? Sync { get; private set; }
    public X509Certificate2 Certificate { get; }
    private sealed record Stored(string Token, string Pfx, SyncState? Sync = null);
    public static bool Exists(string directory) => File.Exists(Path.Combine(directory, "identity.dpapi"));
    public Identity(string directory)
    {
        Directory.CreateDirectory(directory); _file = Path.Combine(directory, "identity.dpapi");
        if (File.Exists(_file))
        {
            var data = JsonSerializer.Deserialize<Stored>(ProtectedData.Unprotect(File.ReadAllBytes(_file), null, DataProtectionScope.CurrentUser))!;
            Token = data.Token; Sync = data.Sync; Certificate = new X509Certificate2(Convert.FromBase64String(data.Pfx), (string?)null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
        }
        else
        {
            Token = NewToken(); using var key = RSA.Create(3072);
            var request = new CertificateRequest("CN=UsageNotch Link", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
            var usages = new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }; request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(usages, false));
            using var created = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddYears(5));
            // Schannel requires a persisted user key handle; ephemeral-only keys fail server handshakes on Windows.
            Certificate = new X509Certificate2(created.Export(X509ContentType.Pfx), (string?)null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable); Save();
        }
    }
    private static string NewToken() => Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    public string Pin => Convert.ToHexString(SHA256.HashData(Certificate.RawData)).ToLowerInvariant();
    /// <summary>Invalidates every exported pairing: a new LAN key, and a new internet sync key so old files cannot decrypt new uploads.</summary>
    public void Revoke() { Token = NewToken(); if (Sync != null) Sync = Sync with { Key = SyncState.NewKey() }; Save(); }
    public void SetSync(SyncState? sync) { Sync = sync; Save(); }
    private void Save()
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(new Stored(Token, Convert.ToBase64String(Certificate.Export(X509ContentType.Pfx)), Sync));
        var encrypted = ProtectedData.Protect(bytes, null, DataProtectionScope.CurrentUser); var temporary = _file + ".tmp";
        File.WriteAllBytes(temporary, encrypted); File.Move(temporary, _file, true); CryptographicOperations.ZeroMemory(bytes);
    }
    public string Pairing(string address, int port = 43187, bool indented = true) => JsonSerializer.Serialize(new {
        schema = 1, name = Environment.MachineName, endpoint = $"https://{address}:{port}", token = Token, certificateSha256 = Pin,
        relay = Sync == null ? null : new { url = Sync.RawUrl, key = Sync.Key }
    }, new JsonSerializerOptions { WriteIndented = indented, DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull });
    /// <summary>The pairing file as one copyable line, for sending to yourself instead of transferring a file.</summary>
    public string PairingCode(string address, int port = 43187) => "UN1." + Convert.ToBase64String(Encoding.UTF8.GetBytes(Pairing(address, port, indented: false))).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    public void Dispose() => Certificate.Dispose();
    /// <summary>Private IPv4 addresses a phone could reach, best first: the Wi-Fi/Ethernet adapter with a router, then private VPNs, then virtual adapters (WSL, Hyper-V, VMs) that phones normally cannot reach.</summary>
    public static IReadOnlyList<LinkAddress> Addresses() => NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
        .SelectMany(n => { var ip = n.GetIPProperties(); var gateway = ip.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
            return ip.UnicastAddresses.Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && IsPrivate(a.Address)).Select(a => new LinkAddress(a.Address.ToString(), n.Name, Rank(n.Name + " " + n.Description, n.NetworkInterfaceType, gateway, a.Address))); })
        .GroupBy(a => a.Address).Select(g => g.OrderByDescending(a => a.Rank).First()).OrderByDescending(a => a.Rank).ThenBy(a => a.Address, StringComparer.Ordinal).ToList();
    public static int Rank(string name, NetworkInterfaceType type, bool gateway, IPAddress address)
    {
        var text = name.ToLowerInvariant();
        if (new[] { "vethernet", "hyper-v", "wsl", "virtualbox", "vmware", "docker", "vbox", "loopback" }.Any(text.Contains)) return 0;
        var score = gateway ? 100 : 10;
        if (type is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet) score += 20;
        if (address.GetAddressBytes()[0] == 100 || text.Contains("tailscale") || text.Contains("zerotier")) score += 30; // Private mesh VPNs reach phones anywhere.
        return score;
    }
    public static bool IsPrivate(IPAddress ip) { var b = ip.GetAddressBytes(); return b.Length == 4 && (b[0] == 10 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 100 && b[1] is >= 64 and <= 127)); }
}
public sealed class Bridge : IAsyncDisposable
{
    private WebApplication? _app;
    private readonly Identity _identity;
    private readonly string _database;
    public Bridge(Identity identity, string database) { _identity = identity; _database = database; }
    public async Task Start(int port = 43187, bool loopbackOnly = false)
    {
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = [] });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options => {
            options.AddServerHeader = false; options.Limits.MaxRequestBodySize = 0;
            options.Limits.MaxRequestHeadersTotalSize = 8192; options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(5);
            options.Limits.MaxConcurrentConnections = 16; options.Limits.KeepAliveTimeout = TimeSpan.FromSeconds(5);
            options.Listen(loopbackOnly ? IPAddress.Loopback : IPAddress.Any, port, listen => listen.UseHttps(_identity.Certificate));
        });
        _app = builder.Build();
        var limiter = new System.Threading.RateLimiting.FixedWindowRateLimiter(new() { PermitLimit = 30, Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true });
        _app.Lifetime.ApplicationStopped.Register(limiter.Dispose);
        _app.Run(async context => {
            context.Response.Headers.CacheControl = "no-store";
            context.Response.Headers["X-Content-Type-Options"] = "nosniff";
            if (context.Request.Path != "/v1/snapshot" || context.Request.Method != "GET" || context.Request.QueryString.HasValue) { context.Response.StatusCode = 404; return; }
            using var lease = limiter.AttemptAcquire(); if (!lease.IsAcquired) { context.Response.StatusCode = 429; return; }
            var header = context.Request.Headers.Authorization.ToString(); var expected = "Bearer " + _identity.Token;
            if (!CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(header), Encoding.UTF8.GetBytes(expected))) { context.Response.StatusCode = 401; return; }
            try { await context.Response.WriteAsJsonAsync(SnapshotReader.Read(_database), context.RequestAborted); }
            catch (Exception e) when (e is SqliteException or IOException or UnauthorizedAccessException) { context.Response.StatusCode = 503; }
        });
        await _app.StartAsync();
    }
    public async ValueTask DisposeAsync() { if (_app != null) { await _app.StopAsync(); await _app.DisposeAsync(); _app = null; } }
}
public static class SnapshotReader
{
    public sealed record Point(long At, double Used, string Period);
    public sealed record Window(string Id, string Label, double Used, long At, long? Reset, List<Point> Points);
    public sealed record Provider(string Id, string Name, string Status, List<Window> Windows);
    public sealed record Snapshot(int Schema, long GeneratedAt, List<Provider> Providers);
    public static Snapshot Read(string path, int maxPoints = 512)
    {
        if (!File.Exists(path)) throw new IOException("No history yet");
        using var db = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Mode = SqliteOpenMode.ReadOnly, DefaultTimeout = 3 }.ToString()); db.Open();
        using var transaction = db.BeginTransaction(deferred: true);
        var providers = new List<Provider>(); var states = new List<(string Id, string Account, string Status)>();
        using (var cmd = db.CreateCommand()) { cmd.Transaction = transaction; cmd.CommandText = "SELECT provider,account,status FROM provider_state ORDER BY CASE provider WHEN 'claude' THEN 0 WHEN 'codex' THEN 1 ELSE 2 END,provider LIMIT 16";
            using var r = cmd.ExecuteReader(); while (r.Read()) states.Add((r.GetString(0), r.GetString(1), r.GetString(2))); }
        foreach (var state in states)
        {
            var windows = new List<Window>();
            using (var cmd = db.CreateCommand())
            {
                cmd.Transaction = transaction;
                // Only the current account, and only windows in its latest observation batch.
                cmd.CommandText = "SELECT window,used,at,reset FROM observations WHERE provider=$p AND account=$a AND at=(SELECT MAX(at) FROM observations WHERE provider=$p AND account=$a) ORDER BY CASE WHEN window='five_hour' OR window LIKE '%-primary' THEN 0 WHEN window='seven_day' OR window LIKE '%-secondary' THEN 1 ELSE 2 END,window LIMIT 32";
                cmd.Parameters.AddWithValue("$p", state.Id); cmd.Parameters.AddWithValue("$a", state.Account);
                using var r = cmd.ExecuteReader(); while (r.Read()) { var used = r.GetDouble(1); if (double.IsFinite(used) && used >= 0 && used <= 1000) windows.Add(new Window(r.GetString(0), Label(state.Id, r.GetString(0)), used, r.GetInt64(2), r.IsDBNull(3) ? null : r.GetInt64(3), [])); }
            }
            foreach (var w in windows)
            {
                using var cmd = db.CreateCommand(); cmd.Transaction = transaction;
                cmd.CommandText = "SELECT at,used,period FROM observations WHERE provider=$p AND account=$a AND window=$w AND at >= $since ORDER BY at DESC LIMIT 512";
                cmd.Parameters.AddWithValue("$p", state.Id); cmd.Parameters.AddWithValue("$a", state.Account); cmd.Parameters.AddWithValue("$w", w.Id); cmd.Parameters.AddWithValue("$since", w.At - 86400000);
                using var r = cmd.ExecuteReader(); while (r.Read()) { var value = r.GetDouble(1); if (double.IsFinite(value) && value >= 0 && value <= 1000) w.Points.Add(new Point(r.GetInt64(0), value, r.GetString(2))); } w.Points.Reverse();
                Thin(w.Points, maxPoints);
            }
            providers.Add(new Provider(state.Id, state.Id switch { "claude" => "Claude", "codex" => "Codex", "gemini" => "Gemini", "cursor" => "Cursor", "openai-api" or "openai_api" => "OpenAI API", "anthropic-api" or "anthropic_api" => "Claude API", _ => state.Id }, state.Status, windows));
        }
        transaction.Commit(); return new Snapshot(1, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), providers);
    }
    /// <summary>Keeps the 24-hour shape within a smaller upload: evenly spaced readings plus the latest one.</summary>
    public static void Thin(List<Point> points, int max)
    {
        if (max < 2 || points.Count <= max) return;
        var kept = Enumerable.Range(0, max - 1).Select(i => points[(int)((long)i * (points.Count - 1) / (max - 1))]).Append(points[^1]).Distinct().ToList();
        points.Clear(); points.AddRange(kept);
    }
    public static string Label(string provider, string window) => window switch {
        "five_hour" => "5-hour window", "seven_day" => "Weekly window", "seven_day_opus" => "Opus · weekly", "seven_day_sonnet" => "Sonnet · weekly", "extra_usage" => "Usage credits", "month_cost" => "This month's API spend", "on_demand" => "On-demand usage",
        "codex-primary" when provider == "codex" => "Primary window",
        "codex-secondary" when provider == "codex" => "Secondary window",
        _ when provider == "codex" && window.EndsWith("-primary") => window[..^8] + " · primary window",
        _ when provider == "codex" && window.EndsWith("-secondary") => window[..^10] + " · secondary window",
        _ => window.Replace('_', ' ').Replace('-', ' ')
    };
}
