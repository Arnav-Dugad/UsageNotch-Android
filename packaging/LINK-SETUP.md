# UsageNotch Link 1.1.0

UsageNotch Link sends the usage recorded by UsageNotch for Windows to the UsageNotch Android app. Your AI sign-ins stay on this PC.

## Pair your phone

1. Keep UsageNotch for Windows (2.2.0 or newer) running with your AI accounts connected.
2. Run `UsageNotch.Link.exe`. If Windows SmartScreen warns about an unrecognized app, choose **More info → Run anyway**. Link isn't code-signed.
3. Click **Start secure link**. If Windows Firewall asks, allow **Private networks**.
4. Check the address shows your Wi-Fi (for example `192.168.1.8 · Wi-Fi`), not `vEthernet` or WSL.
5. Click **Copy pairing code**, send it to yourself privately, and on the phone choose **Paste pairing code**. Or click **Export pairing file** and open it on the phone with **Import PC pairing**.
6. Delete the code or file afterwards. It's an access key to your usage readings.

## Keep it running

- Tick **Start with Windows in the background** so your phone stays updated without opening Link.
- Closing the window keeps Link running in the notification area next to the clock. Right-click its icon and choose **Quit** to stop sharing.
- Link remembers whether the secure link was on and restarts it next time.

## Internet sync (optional)

Use this to get readings on your phone away from home, and to keep the latest reading available after this PC shuts down.

1. Open [GitHub → Settings → Fine-grained tokens → Generate new token](https://github.com/settings/personal-access-tokens/new). Under **Account permissions**, set **Gists** to **Read and write**. No repository access is needed.
2. Paste the token into Link and click **Turn on internet sync**.
3. Copy a **new** pairing code (or export a new file) and pair the phone again. The new code carries the encryption key.

Link encrypts each reading on this PC with AES-256-GCM and uploads it to a **secret gist in your GitHub account**. Only your pairing code or file holds the key, so GitHub stores ciphertext only. The token is saved on this PC, encrypted with Windows DPAPI. **Turn off internet sync** deletes the gist. **Revoke all paired phones** also changes the sync key.

## Troubleshooting

- *Could not start*: another program is using port 43187. Close other copies of Link and try again.
- Phone says *Couldn't reach your PC*: both devices need the same Wi-Fi or a private VPN such as Tailscale. Check the firewall prompt was allowed for Private networks.
- *GitHub rejected the token*: create a new token with Gists read and write, then turn internet sync off and on again.
