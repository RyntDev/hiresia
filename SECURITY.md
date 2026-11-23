# Security Policy

## Supported Versions

| Version | Supported |
| :--- | :--- |
| `1.0.x` | Yes |
| `< 1.0` | No |

---

## Security & Privacy Architecture
- **Offline-First Playback:** Hiresia plays local audio files completely offline with zero user accounts, analytics trackers, or telemetry.
- **HTTPS-Only Network Policy:** `android:usesCleartextTraffic="false"` is enforced in `AndroidManifest.xml`. Network access is used solely when users request synced lyrics from [LRCLIB](https://lrclib.net/) over HTTPS (sending only track title, artist, and duration) or open external support links.
- **Least-Privilege Components:** Internal activities such as `SettingsActivity` are marked `android:exported="false"`.

---

## Reporting a Vulnerability

**Please do not open a public GitHub Issue for security vulnerabilities** (such as native C++ memory corruption/overflow bugs from malformed audio files, intent injection, or path traversal).

Instead, report vulnerabilities privately using one of these channels:
1. **GitHub Private Vulnerability Reporting** *(preferred if enabled under the repository's **Security → Advisories** tab)*.
2. **Private Report Form:** [Hiresia Private Report & Supporting Files Form](https://docs.google.com/forms/d/e/1FAIpQLSfe809fZb4Sg_37t5HjpQ0gW2WjYzNYuhyE4QPkKRHKc0DDBw/viewform) — choose your issue details and optionally attach sample files or logs privately (and leave a contact email if you want follow-up updates).

Please include:
- A clear description of the vulnerability and potential impact
- Steps to reproduce (or a minimal PoC/malformed file)
- Affected app version and Android version/device

We aim to acknowledge private reports promptly and coordinate a fix before public disclosure.

