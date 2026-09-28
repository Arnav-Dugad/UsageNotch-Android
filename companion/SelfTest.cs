using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace UsageNotch.Link;
public static class SelfTest
{
    public static async Task<int> Run()
    {
        var directory = Path.Combine(Path.GetTempPath(), "UsageNotch-Link-Test-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(directory);
        var log = new List<string>();
        try {
            var path = Path.Combine(directory, "history.db"); var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            using (var db = new SqliteConnection("Data Source=" + path)) {
                db.Open(); using var cmd = db.CreateCommand();
                cmd.CommandText = "CREATE TABLE provider_state(provider TEXT PRIMARY KEY,account TEXT,status TEXT); CREATE TABLE observations(provider TEXT,account TEXT,window TEXT,at INTEGER,used REAL,reset INTEGER,period TEXT,PRIMARY KEY(provider,account,window,at)); INSERT INTO provider_state VALUES('claude','current','Ok'); INSERT INTO observations VALUES('claude','old-account','five_hour',1,.99,100,'old'),('claude','current','five_hour',$now,.25,$reset,'p1'),('claude','current','seven_day',$now,.40,$reset,'p2');";
                cmd.Parameters.AddWithValue("$now", now); cmd.Parameters.AddWithValue("$reset", now + 3600000); cmd.ExecuteNonQuery();
            }
            SqliteConnection.ClearAllPools();
            var before = SHA256.HashData(File.ReadAllBytes(path));
            var snapshot = SnapshotReader.Read(path);
            Check(snapshot.Providers.Count == 1 && snapshot.Providers[0].Windows.Count == 2 && snapshot.Providers[0].Windows[0].Used == .25, "current-account isolation, distinct windows, correct fractions", log);
            SqliteConnection.ClearAllPools();
            Check(before.SequenceEqual(SHA256.HashData(File.ReadAllBytes(path))), "database unchanged by snapshot reader", log);
            Check(!Identity.IsPrivate(IPAddress.Parse("8.8.8.8")) && Identity.IsPrivate(IPAddress.Parse("192.168.1.7")) && Identity.IsPrivate(IPAddress.Parse("100.100.1.7")), "private Wi-Fi and VPN address selection", log);
            Check(Identity.Rank("Wi-Fi Intel Wireless", System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211, true, IPAddress.Parse("192.168.1.8")) > Identity.Rank("vEthernet (WSL (Hyper-V firewall)) Hyper-V Virtual Ethernet Adapter", System.Net.NetworkInformation.NetworkInterfaceType.Ethernet, false, IPAddress.Parse("172.25.240.1"))
                && Identity.Rank("Wi-Fi", System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211, true, IPAddress.Parse("192.168.1.8")) > Identity.Rank("NordLynx", System.Net.NetworkInformation.NetworkInterfaceType.Unknown, false, IPAddress.Parse("10.5.0.2")), "Wi-Fi is offered before WSL/Hyper-V and tunnel adapters", log);
            using var identity = new Identity(Path.Combine(directory, "identity")); var token = identity.Token;
            File.WriteAllBytes(Path.Combine(AppContext.BaseDirectory, "test-certificate.cer"), identity.Certificate.RawData);
            using (var loaded = new Identity(Path.Combine(directory, "identity"))) Check(loaded.Token == token && loaded.Pin == identity.Pin, "DPAPI identity survives restart", log);
            var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0); listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
            await using var bridge = new Bridge(identity, path); await bridge.Start(port, loopbackOnly: true);
            using var handler = new HttpClientHandler { AllowAutoRedirect = false, ServerCertificateCustomValidationCallback = (_, cert, _, _) => cert != null && Convert.ToHexString(SHA256.HashData(cert.RawData)).Equals(identity.Pin, StringComparison.OrdinalIgnoreCase) };
            using var client = new HttpClient(handler) { BaseAddress = new Uri($"https://127.0.0.1:{port}") };
            Check((await client.GetAsync("/v1/snapshot")).StatusCode == HttpStatusCode.Unauthorized, "unauthenticated requests rejected", log);
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
            var response = await client.GetAsync("/v1/snapshot"); var json = await response.Content.ReadAsStringAsync();
            Check(response.IsSuccessStatusCode && JsonDocument.Parse(json).RootElement.GetProperty("schema").GetInt32() == 1, "pinned HTTPS authorized snapshot", log);
            Check(!json.Contains("current") && !json.Contains("old-account") && !json.Contains(token), "account identifiers and secrets absent from response", log);
            Check((await client.GetAsync("/v1/snapshot?token=" + token)).StatusCode == HttpStatusCode.NotFound, "query credentials and unexpected routes rejected", log);
            Check((await client.PostAsync("/v1/snapshot", null)).StatusCode == HttpStatusCode.NotFound, "read-only GET route", log);
            identity.Revoke(); Check((await client.GetAsync("/v1/snapshot")).StatusCode == HttpStatusCode.Unauthorized, "revoked token rejected immediately", log);
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", identity.Token);
            Check((await client.GetAsync("/v1/snapshot")).IsSuccessStatusCode, "new token works after revocation", log);
            await RelayChecks(directory, path, identity, log);
            log.Add("PASS: all companion checks");
            File.WriteAllLines(Path.Combine(AppContext.BaseDirectory, "verification.txt"), log); Console.WriteLine(string.Join(Environment.NewLine, log)); return 0;
        } catch (Exception e) { log.Add("FAIL: " + e); File.WriteAllLines(Path.Combine(AppContext.BaseDirectory, "verification.txt"), log); return 1; }
    }
    private static async Task RelayChecks(string directory, string database, Identity identity, List<string> log)
    {
        var key = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
        const string sample = "{\"schema\":1,\"generatedAt\":1790000000000,\"providers\":[{\"id\":\"claude\",\"name\":\"Claude\",\"status\":\"Ok\",\"windows\":[{\"id\":\"five_hour\",\"label\":\"5-hour window\",\"used\":0.42,\"at\":1790000000000,\"reset\":1790018000000,\"points\":[]}]}]}";
        var fixture = RelayCrypto.Seal(key, sample, Enumerable.Repeat((byte)9, 12).ToArray());
        File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "relay-fixture.json"), fixture); // Decrypted by the Android unit tests.
        Check(RelayCrypto.Open(key, fixture) == sample && !fixture.Contains("claude"), "relay envelope round-trips and hides plaintext", log);
        var wrong = (byte[])key.Clone(); wrong[0] ^= 1;
        Check(Throws(() => RelayCrypto.Open(wrong, fixture)), "relay envelope rejects a wrong key", log);
        Check(RelayCrypto.Seal(key, sample) != RelayCrypto.Seal(key, sample), "each upload uses a fresh nonce", log);

        using (var plain = JsonDocument.Parse(identity.Pairing("192.168.1.7"))) Check(!plain.RootElement.TryGetProperty("relay", out _), "pairing omits relay while internet sync is off", log);
        identity.SetSync(new SyncState("github_pat_test", "0123456789abcdef0123456789abcdef", "someone", SyncState.NewKey()));
        using (var withRelay = JsonDocument.Parse(identity.Pairing("192.168.1.7")))
        {
            var relay = withRelay.RootElement.GetProperty("relay");
            Check(relay.GetProperty("url").GetString() == "https://gist.githubusercontent.com/someone/0123456789abcdef0123456789abcdef/raw/usagenotch-sync.json" && identity.Sync!.KeyBytes.Length == 32, "pairing carries the gist address and 256-bit key", log);
            Check(!identity.Pairing("192.168.1.7").Contains("github_pat_test"), "GitHub token never leaves the PC", log);
        }
        var code = identity.PairingCode("192.168.1.7");
        var decoded = System.Text.Encoding.UTF8.GetString(Convert.FromBase64String(code[4..].Replace('-', '+').Replace('_', '/') + new string('=', (4 - (code.Length - 4) % 4) % 4)));
        Check(code.StartsWith("UN1.") && !code.Any(char.IsWhiteSpace) && decoded == identity.Pairing("192.168.1.7", indented: false), "pairing code is the compact pairing file", log);
        using (var reloaded = new Identity(Path.Combine(directory, "identity"))) Check(reloaded.Sync == identity.Sync, "internet sync settings survive restart under DPAPI", log);
        var oldKey = identity.Sync!.Key; identity.Revoke();
        Check(identity.Sync!.Key != oldKey && identity.Sync.GistId == "0123456789abcdef0123456789abcdef", "revoking rotates the internet sync key", log);

        var points = Enumerable.Range(0, 1000).Select(i => new SnapshotReader.Point(i, i / 1000.0, "p")).ToList();
        SnapshotReader.Thin(points, 192);
        Check(points.Count == 192 && points[0].At == 0 && points[^1].At == 999 && points.Zip(points.Skip(1)).All(p => p.First.At < p.Second.At), "uploads keep the day's shape in 192 ordered readings", log);
        Check(SnapshotReader.Label("claude", "month_cost") == "This month's API spend", "API spend windows are labelled", log);

        var github = new FakeGitHub();
        using var publisher = new RelayPublisher(github, new Uri("https://github.test/"));
        var state = await publisher.Create("ghp_" + new string('x', 36), s => RelayCrypto.Seal(s.KeyBytes, sample));
        var created = github.Requests[^1];
        Check(created.Method == "POST" && created.Path == "/gists" && created.Body.Contains("\"public\":false") && !created.Body.Contains("Claude") && created.Auth == "Bearer ghp_" + new string('x', 36), "creates a secret gist containing only ciphertext", log);
        Check(state.GistId == "0123456789abcdef0123456789abcdef" && state.Owner == "someone", "stores the gist address GitHub returned", log);
        identity.SetSync(state);
        await using (var sync = new RelaySync(identity, database, publisher, _ => { }))
        {
            Check(await sync.Upload(force: false) && github.Requests[^1].Method == "PATCH", "uploads the current snapshot", log);
            var uploaded = JsonDocument.Parse(github.Requests[^1].Body).RootElement.GetProperty("files").GetProperty(RelayPublisher.FileName).GetProperty("content").GetString()!;
            using var opened = JsonDocument.Parse(RelayCrypto.Open(state.KeyBytes, uploaded));
            Check(opened.RootElement.GetProperty("providers")[0].GetProperty("windows").GetArrayLength() == 2 && !uploaded.Contains("current"), "uploaded snapshot decrypts to the current account's readings", log);
            Check(!await sync.Upload(force: false), "unchanged readings are not uploaded again", log);
            github.Status = HttpStatusCode.Unauthorized;
            string? status = null;
            await using var rejected = new RelaySync(identity, database, publisher, s => status = s);
            Check(!await rejected.Upload(force: true) && status!.Contains("rejected the token"), "a revoked GitHub token is reported, not retried silently", log);
        }
        github.Status = HttpStatusCode.NotFound; await publisher.Delete(state);
        Check(github.Requests[^1].Method == "DELETE", "turning sync off deletes the gist (missing gist tolerated)", log);
        identity.SetSync(null);
    }
    private static bool Throws(Action action) { try { action(); return false; } catch { return true; } }
    private sealed class FakeGitHub : HttpMessageHandler
    {
        public sealed record Seen(string Method, string Path, string Body, string? Auth);
        public List<Seen> Requests { get; } = [];
        public HttpStatusCode Status { get; set; } = HttpStatusCode.OK;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Requests.Add(new(request.Method.Method, request.RequestUri!.AbsolutePath, request.Content == null ? "" : await request.Content.ReadAsStringAsync(ct), request.Headers.Authorization?.ToString()));
            var status = request.Method == HttpMethod.Post ? HttpStatusCode.Created : Status;
            return new HttpResponseMessage(status) { RequestMessage = request, Content = new StringContent("{\"id\":\"0123456789abcdef0123456789abcdef\",\"owner\":{\"login\":\"someone\"}}") };
        }
    }
    private static void Check(bool value, string label, List<string> log) { if (!value) throw new InvalidOperationException(label); log.Add("PASS: " + label); }
}
