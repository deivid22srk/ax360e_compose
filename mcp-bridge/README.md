# ax360e MCP Bridge

Render-hosted bridge service that lets an AI assistant (using the Model
Context Protocol) remotely control and inspect an ax360e Android emulator
instance running on a phone.

```
[ax360e Android app]  ⇄  [Render service (this)]  ⇄  [AI MCP client]
   (WebSocket out)         (MCP over HTTP+SSE)          (MCP SDK)
```

The Android app opens a WebSocket connection TO this service when the user
toggles **Settings → AI Remote Control** on. The AI then connects via MCP
(SSE transport) to call tools like `list_devices`, `get_logs`,
`open_game`, `send_key_event`, etc.

## Endpoints

| Method | Path              | Auth           | Purpose                              |
|--------|-------------------|----------------|--------------------------------------|
| GET    | `/health`         | public         | Health check (`{ok, devices, uptime}`) |
| GET    | `/`               | `X-API-Key`    | Admin dashboard HTML                 |
| GET    | `/sse`            | `X-API-Key`    | MCP server SSE stream                |
| POST   | `/messages`       | `X-API-Key`    | MCP client → server messages         |
| GET    | `/ws/device`      | `?apiKey=`     | WebSocket upgrade for Android devices |

Auth is a single shared secret stored in the `BRIDGE_API_KEY` env var.
Both MCP clients (header `X-API-Key`) and Android devices (query `?apiKey=`)
must present it.

## MCP tools exposed

| Tool                  | Description                                                  |
|-----------------------|--------------------------------------------------------------|
| `list_devices`        | List connected Android devices                               |
| `get_device_info`     | Get device hardware/OS info + emulator state                 |
| `get_logs`            | Fetch recent log lines (filter by level/substring, limit)    |
| `list_games`          | List games in the device's game directory                    |
| `open_game`           | Launch a game by URI                                         |
| `close_game`          | Force-close the running emulator                             |
| `get_emulator_status` | Get running/paused state, FPS, current game                  |
| `send_key_event`      | Simulate a button press / thumbstick movement                |
| `get_settings`        | Dump the current TOML config as JSON                         |
| `ping_device`         | Measure round-trip latency to the device                     |

## Local development

```bash
export BRIDGE_API_KEY="dev-secret-change-me-12345"
npm install
npm run dev          # tsx src/index.ts
```

Then visit `http://localhost:10000/health` — should return
`{"ok":true,"devices":0,"uptime":...}`.

## Deploy on Render

The repo includes a `Dockerfile` and a `render.yaml` blueprint.

1. Create a new Web Service on Render, point it at this repo + branch
   `feature/mcp-bridge-render`, set the root directory to `mcp-bridge`,
   and pick the Docker runtime.
2. Set the env var `BRIDGE_API_KEY` to a strong random secret.
3. Deploy. The health check is `GET /health`.
4. The public URL will look like
   `https://ax360e-mcp-bridge.onrender.com`.

In the Android app, the user pastes this URL into **Settings → AI Remote
Control → Bridge URL** and sets the same API key, then toggles the master
switch on. The phone then connects out to Render via WSS and the AI can
start calling tools immediately.

## Connecting an MCP client

The SSE transport is the standard MCP-over-HTTP pattern. With the
`@modelcontextprotocol/sdk` client:

```ts
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js";

const transport = new SSEClientTransport(
  new URL("https://ax360e-mcp-bridge.onrender.com/sse"),
  { requestInit: { headers: { "X-API-Key": BRIDGE_API_KEY } } }
);
const client = new Client({ name: "ai", version: "1.0" }, { capabilities: {} });
await client.connect(transport);

const tools = await client.listTools();
const result = await client.callTool({ name: "list_devices", arguments: {} });
console.log(result.content);
```
