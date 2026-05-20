# ThatsApp

<p align="center">
  <img src="Client/src/main/resources/img/icon.png" alt="ThatsApp Icon" width="112" />
</p>

**ThatsApp** is a multi-transport group chat project with a JavaFX desktop client, a JavaFX desktop relay server, and a PHP/CodeIgniter web backend. The repository contains everything needed to run ThatsApp either as a local desktop chat system or through the database-backed web backend.

**Personal Note:** I started ThatsApp as a side project while studying for my Master's degree, and it has grown iteratively over time. The original idea was simple: explore how lightweight communication can be set up without depending on a single central chat platform, while still keeping the setup approachable enough for real people to run, test, and understand. I hope you enjoy exploring it.

<details>
<summary><strong>Project Status And Security Scope</strong></summary>


ThatsApp is a proof-of-concept and portfolio project that demonstrates the architecture of a quickly bootstrapped communication system. Its core flow works across both the desktop and `Webserver` modes, including chat sessions and file transfer.

When configured with strong communication keys and a properly secured web backend setup, the communication content is designed to be impractical to intercept.

The application is still a prototype and not production-hardened. Some availability and hardening gaps remain, especially around denial-of-service protection. Examples include missing rate limits or IP blocklists for repeated access attempts, limited connection throttling before authentication, and storage pressure from large or abandoned file transfers.

At a high level, the project consists of:

<table>
  <tr><th>Area</th><th>Path</th><th>Role</th></tr>
  <tr><td><b>Shared protocol</b></td><td><code>Common/</code></td><td>Shared records, status codes, and payload rules.</td></tr>
  <tr><td><b>Desktop client</b></td><td><code>Client/</code></td><td>JavaFX chat client for desktop and <code>Webserver</code> mode sessions.</td></tr>
  <tr><td><b>Desktop server</b></td><td><code>Server/</code></td><td>JavaFX relay server for local or LAN sessions.</td></tr>
  <tr><td><b>Web backend</b></td><td><code>Webserver/</code></td><td>CodeIgniter web backend for persistent <code>Webserver</code> mode sessions.</td></tr>
</table>

For web backend details, see [Webserver/README.md](Webserver/README.md).


</details>

---

<details>
<summary><strong>Features</strong></summary>


- Two connection modes: direct Java desktop server or HTTP/SSE/TUS web backend
- Desktop client and desktop server are designed to run on Windows, Linux, and macOS through JavaFX
- Session handshake protected by an `access key` in both modes
- Optional AES-GCM encryption based on the `room secret` for chat messages, file names, and uploaded file content
- Typing indicators, join/leave events, and server/system messages
- File transfer in both modes
- Auto reconnect window for web sessions with `Last-Event-ID` resume support
- Encrypted client settings storage protected by a user password
- Configurable desktop server rules for user count, file size, and message length
- Database-backed `Webserver` rules for session limits, ping behavior, file quotas, and public stats visibility

### Desktop client

- Password-gated startup flow with first-run setup and reusable encrypted settings file
- Connection configuration for both `Desktopserver` and `Webserver` modes
- Optional auto-connect on launch
- Chat UI with own/other/system message layouts
- Live typing indicators in the conversation header
- Drag-and-drop and file-picker based uploads
- Download folder selection and optional automatic file downloads
- Password change dialog for the local settings store
- Custom chat background image stored inside the encrypted settings file

### Desktop server

- Local JavaFX server UI with start/stop/shutdown controls
- Shutdown control that sends an immediate close command to connected desktop clients; receiving clients exit and discard the current in-memory chat state
- Live external and internal IP display
- Optional `access key` for authenticated handshakes
- Runtime limits for connected users, maximum file size, and message character count
- Server-side broadcast messages sent from the server UI
- Connected-user counter and ID overview
- Temporary on-disk buffering for in-flight file transfers

### Web backend

- Session creation and join flow over HTTP
- Database-backed event queue streamed via Server-Sent Events
- Message, typing, heartbeat, disconnect, file download, and TUS upload endpoints
- Session cleanup for unresponsive clients using missed-ping counters
- Public statistics dashboard with live refresh
- Automatic cleanup of orphaned uploads and exhausted file permissions


</details>

---

<details>
<summary><strong>Quick Start</strong></summary>


<b>Notes: </b>Both connection modes use the same two security fields: the `access key` controls who may join when set, while the optional `room secret` provides separate key material for client-side encryption of names, chat, and file payloads. The `room secret` never leaves the client in either mode.

### Option 1: Desktop-only mode

1. Start the Java desktop server with `./mvnw -pl Server javafx:run`.
2. In the server UI, choose a port, optional `access key`, and server rules.
3. Start one or more desktop clients with `./mvnw -pl Client javafx:run`.
4. In the client, select `Desktopserver`, enter host, port, display name, `access key`, and an optional `room secret`.
5. Connect and use chat, typing indicators, and protocol-based file transfers.

`Desktopserver`-specific: if no `room secret` is set, a non-empty `access key` also enables encrypted chat and file payloads for that desktop session.

### Option 2: `Webserver` mode

1. Set up and deploy the web backend as described in [Webserver/README.md](Webserver/README.md).
2. Start the desktop client with `./mvnw -pl Client javafx:run`.
3. In the client, select `Webserver`, enter the deployment base URL, then set session name, `access key`, and optional `room secret`.
4. Connect. The client will perform the handshake, open the SSE stream, and switch uploads/downloads to the web backend.

`Webserver`-specific: when auto-create is enabled for a new session, the first client sets the `access key` and encryption mode.


</details>

---

<details>
<summary><strong>Requirements</strong></summary>


- JDK 21
- Maven 3.9 or the included Maven Wrapper
- JavaFX-compatible desktop environment for the client and desktop server
- PHP 8.5+
- Composer 2.9
- A MySQL/MariaDB-compatible database for the web backend


</details>

---

<details>
<summary><strong>Build, Run, And Test</strong></summary>


### Java modules

Run all Java tests from the repository root:

```bash
./mvnw test
```

Run the desktop client:

```bash
./mvnw -pl Client javafx:run
```

Run the desktop server:

```bash
./mvnw -pl Server javafx:run
```

### Webserver

For web backend dependency installation, tests, and coverage, see [Webserver/README.md](Webserver/README.md).

### Full test suite

Run both Java and PHP tests through the repository helper:

```bash
./test-all.sh
```

On Windows, use `mvnw.cmd` instead of `./mvnw` when needed.

### Test coverage

The repository includes coverage for the behavior that matters most to the product:

- `Common` tests validate status/payload helpers and rule defaults.
- `Client` tests cover login/config validation, encrypted settings storage, symmetric encryption, desktop/web transports, reconnect behavior, and both file transfer implementations.
- `Server` tests cover port/settings validation, settings persistence, client handshake behavior, message broadcasting, and file transfer edge cases.
- Web backend tests cover strict validation helpers, session logic, message payload validation, storage helpers, and basic framework health checks.


</details>

---

<details>
<summary><strong>Connection Modes</strong></summary>


<table>
  <tr><th>Mode</th><th>Transport</th><th>Chat delivery</th><th>File delivery</th><th>Typical use</th></tr>
  <tr><td><code>Desktopserver</code></td><td>Java sockets with serialized protocol messages</td><td>Direct server relay using <code>Message</code> records and shared status codes</td><td>Chunked <code>FilePacket</code> relay buffered on the desktop server</td><td>LAN usage, or fully self-contained desktop-only deployment</td></tr>
  <tr><td><code>Webserver</code></td><td>HTTP + SSE + TUS</td><td>Messages stored in MySQL-compatible tables and streamed with SSE</td><td>TUS upload to the web backend plus authenticated one-time downloads</td><td>Persistent sessions, browser-friendly hosting, and centralized web backend operation</td></tr>
</table>

</details>

---

<details>
<summary><strong>Repository Structure</strong></summary>


<table>
  <tr><th>Area</th><th>Path(s)</th><th>Description</th></tr>
  <tr><td><b>Root build</b></td><td><code>pom.xml</code>, <code>mvnw</code>, <code>mvnw.cmd</code>, <code>test-all.sh</code></td><td>Maven reactor for <code>Common</code>, <code>Client</code>, and <code>Server</code>, plus a convenience script that also runs the <code>Webserver</code> PHPUnit suite.</td></tr>
  <tr><td><b>Common module</b></td><td><code>Common/src/main/java/thatsapp/common/</code></td><td>Shared message records, file packet record, status codes, rule exchange object, payload helpers, and shared logging.</td></tr>
  <tr><td><b>Client bootstrap</b></td><td><code>Client/src/main/java/thatsapp/client/Main.java</code>, <code>Launcher.java</code></td><td>JavaFX application entrypoints and global MaterialFX theme setup.</td></tr>
  <tr><td><b>Client UI</b></td><td><code>Client/src/main/java/thatsapp/client/ui/</code>, <code>Client/src/main/resources/thatsapp/client/</code></td><td>Main chat window, login/setup flow, configuration dialogs, file handling UI, password/background dialogs, and per-message FXML templates.</td></tr>
  <tr><td><b>Client communication</b></td><td><code>Client/src/main/java/thatsapp/client/communication/</code></td><td>Facade, encryption helper, desktop transport, web transport, and file transfer implementations.</td></tr>
  <tr><td><b>Client persistence</b></td><td><code>Client/src/main/java/thatsapp/client/data/</code></td><td>In-memory settings holder and encrypted settings serialization.</td></tr>
  <tr><td><b>Server bootstrap/UI</b></td><td><code>Server/src/main/java/thatsapp/server/</code>, <code>Server/src/main/resources/thatsapp/server/</code></td><td>JavaFX server application, main window, settings dialog, and persisted server rule handling.</td></tr>
  <tr><td><b>Server communication</b></td><td><code>Server/src/main/java/thatsapp/server/communication/</code></td><td>Socket accept loop, client handlers, handshake validation, broadcast logic, and buffered file session management.</td></tr>
  <tr><td><b>Web API</b></td><td><code>Webserver/app/Controllers/Api/</code>, <code>Webserver/app/Models/Api/</code></td><td>HTTP API for sessions, events, messages, typing, uploads, and downloads.</td></tr>
  <tr><td><b>Web support/config</b></td><td><code>Webserver/app/Support/</code>, <code>Webserver/app/Config/</code>, <code>Webserver/app/Database/database.sql</code></td><td>Shared backend helpers, validation rules, routing, and the canonical database schema.</td></tr>
  <tr><td><b>Web stats page</b></td><td><code>Webserver/app/Controllers/Stats.php</code>, <code>Webserver/app/Views/stats.php</code>, <code>Webserver/public/style.css</code></td><td>Public live statistics dashboard for sessions, users, storage usage, and configured limits.</td></tr>
  <tr><td><b>Tests</b></td><td><code>Common/src/test/</code>, <code>Client/src/test/</code>, <code>Server/src/test/</code>, <code>Webserver/tests/</code></td><td>Unit and integration-style tests covering payload rules, encryption, transports, file transfer, settings validation, and backend helpers.</td></tr>
</table>

### Main technologies by area

<table>
  <tr><th>Area</th><th>Technologies</th></tr>
  <tr><td><b>Java desktop</b></td><td>JavaFX 21.0, MaterialFX 11.17, Ikonli 12.4</td></tr>
  <tr><td><b>Web backend</b></td><td>CodeIgniter 4.7, tus-php 2.4</td></tr>
  <tr><td><b>Tests and coverage</b></td><td>JUnit 6.0, PHPUnit 13.1, JaCoCo 0.8, Faker 1.24, vfsStream 1.6</td></tr>
</table>


</details>

---

<details>
<summary><strong>Security And Data Flow</strong></summary>


### `access key` and `room secret`

The `access key` controls admission to a session. In `Desktopserver` mode it is used for the HMAC challenge-response handshake; in `Webserver` mode it is sent during `handshake` and `connect`, stored as a password hash when a session is created, and verified on later joins. If it is left empty, the session has no `access key` protection.

The `room secret` controls optional client-side content encryption and is never sent to the `Desktopserver` or web backend. When present, clients derive an AES-GCM key from the `room secret` and the session nonce, then encrypt chat messages, user names, file names, and uploaded file content before sending them through either transport. Chat payloads use the `E2:` prefix when encrypted and `P2:` when plain.

In `Webserver` mode, when a `room secret` is set, the client hashes the visible session name with it before contacting the web backend. The backend sees and stores that hash as the session name, not the original room label; it also stores the `access key` hash, the session nonce, and whether the session expects encrypted chat payloads.

In `Desktopserver` mode, if no `room secret` is set, a non-empty `access key` also enables encrypted chat and file payloads for that desktop session. Setting a `room secret` keeps admission and encryption secrets separate.

- The client stores settings in an AES-GCM encrypted file at `%LOCALAPPDATA%\ThatsApp\client_settings.dat` by default. The desktop server stores its own settings in `%LOCALAPPDATA%\ThatsApp\server_settings.dat`.
- Both Java transports apply object/input validation or request validation to reject malformed payloads. The web backend additionally validates JSON bodies and session tokens on every API call.
- On Apache deployments, `Webserver/public/.htaccess` rejects oversized `POST` bodies above `16 KB` for the small JSON session endpoints before CodeIgniter runs. TUS upload routes are intentionally excluded from that cap.


</details>

---

<details>
<summary><strong>Key Technical Details</strong></summary>


### Shared protocol

- `StatusCodes` defines handshake, message, typing, join/leave, and file-transfer constants.
- `ServerRules` is exchanged during connect so clients immediately know the active message limit, file size limit, and whether file transfer is currently enabled.
- `ChatMessagePayload` centralizes the plain/encrypted payload format and character counting logic so both transports enforce the same rules.

### Desktop transport

- `DesktopserverTransport` handles the handshake, registration, message relay, typing updates, and raw file packets over a socket connection.
- `ClientHandler` validates incoming message payloads against the current rule set before broadcasting them.
- `FileTransferManager` buffers file chunks to disk and can replay already-received chunks to recipients that begin downloading slightly later.

### Web transport

- `WebserverTransport` performs the handshake and connect flow over HTTP, then keeps a Server-Sent Events connection open for real-time updates.
- The client remembers the last processed event ID and can resume a dropped SSE stream in the reconnect window.
- `HttpFileTransferService` uses TUS uploads and authenticated file downloads instead of desktop file packets.

### Persistent state

- Client settings include both connection modes, `access key` values, `room secret` values, auto-connect, file download preferences, and optional chat background data.
- Desktop server settings persist the last port and configured limits between runs.
- Web backend session, client, message, and file state is persisted in SQL tables.


</details>

---

<details>
<summary><strong>Additional Documentation</strong></summary>


- [Webserver/README.md](Webserver/README.md): web backend setup, API reference, schema, storage paths, and deployment notes
- [Webserver/app/Database/database.sql](Webserver/app/Database/database.sql): canonical database schema
- [img/database.png](img/database.png): visual database overview

</details>
