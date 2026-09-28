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
            log.Add("PASS: all companion checks");
            File.WriteAllLines(Path.Combine(AppContext.BaseDirectory, "verification.txt"), log); Console.WriteLine(string.Join(Environment.NewLine, log)); return 0;
        } catch (Exception e) { log.Add("FAIL: " + e); File.WriteAllLines(Path.Combine(AppContext.BaseDirectory, "verification.txt"), log); return 1; }
    }
    private static void Check(bool value, string label, List<string> log) { if (!value) throw new InvalidOperationException(label); log.Add("PASS: " + label); }
}
