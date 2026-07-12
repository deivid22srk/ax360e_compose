/**
 * ax360e MCP Bridge — Render-hosted service.
 *
 * Architecture:
 *
 *   [ax360e Android app]  ⇄  [this Render service]  ⇄  [AI MCP client]
 *      (WebSocket out)         (MCP over HTTP+SSE)        (MCP SDK)
 *
 * The Android app opens a WebSocket connection TO this service (the device
 * initiates so we don't need NAT traversal). The service maintains a
 * registry of connected devices keyed by deviceId. When the AI calls an
 * MCP tool, the service forwards the call to the right device over its
 * WebSocket and waits for the response.
 *
 * Endpoints:
 *   GET  /health                  — health check (public)
 *   GET  /                        — admin dashboard HTML (api key required)
 *   GET  /sse                     — MCP server SSE stream (api key required)
 *   POST /messages?sessionId=...  — MCP client → server messages (api key)
 *   GET  /ws/device?apiKey=...    — WebSocket upgrade for Android devices
 *
 * Auth: a single shared secret stored in BRIDGE_API_KEY. Both MCP clients
 * (header `X-API-Key`) and Android devices (query `?apiKey=`) must present
 * it. If BRIDGE_API_KEY is unset, the service refuses to start.
 */
import express from "express";
import http from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";
import type { JSONRPCMessage } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const PORT = parseInt(process.env.PORT ?? "10000", 10);
const API_KEY = process.env.BRIDGE_API_KEY;
const MAX_LOG_LINES_PER_REQUEST = 5000;
const COMMAND_TIMEOUT_MS = 30_000;

if (!API_KEY || API_KEY.length < 8) {
  console.error(
    "FATAL: BRIDGE_API_KEY environment variable must be set to a non-empty secret of at least 8 characters."
  );
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Device registry
// ---------------------------------------------------------------------------

interface PendingCommand {
  commandId: string;
  resolve: (payload: unknown) => void;
  reject: (err: Error) => void;
  timer: NodeJS.Timeout;
}

interface ConnectedDevice {
  deviceId: string;
  deviceName: string;
  appVersion: string;
  connectedAt: number;
  lastSeen: number;
  ws: WebSocket;
  currentGame: string | null;
  pending: Map<string, PendingCommand>;
  /** Bounded ring buffer of recent log lines pushed by the device. */
  logBuffer: string[];
}

const MAX_BUFFERED_LOG_LINES = 2000;

const devices = new Map<string, ConnectedDevice>();

function listDevices() {
  return Array.from(devices.values()).map((d) => ({
    deviceId: d.deviceId,
    deviceName: d.deviceName,
    appVersion: d.appVersion,
    connectedAt: d.connectedAt,
    lastSeen: d.lastSeen,
    currentGame: d.currentGame,
    pendingCommands: d.pending.size,
  }));
}

function sendToDevice(
  deviceId: string,
  command: string,
  args: Record<string, unknown>
): Promise<unknown> {
  const device = devices.get(deviceId);
  if (!device) {
    return Promise.reject(
      new Error(`Device '${deviceId}' is not connected. Use list_devices first.`)
    );
  }
  if (device.ws.readyState !== WebSocket.OPEN) {
    return Promise.reject(
      new Error(`Device '${deviceId}' WebSocket is not open (state=${device.ws.readyState}).`)
    );
  }

  const commandId =
    Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      const pending = device.pending.get(commandId);
      if (pending) {
        device.pending.delete(commandId);
        pending.reject(new Error(`Command '${command}' timed out after ${COMMAND_TIMEOUT_MS}ms`));
      }
    }, COMMAND_TIMEOUT_MS);

    device.pending.set(commandId, { commandId, resolve, reject, timer });

    device.ws.send(
      JSON.stringify({ type: "command", commandId, command, args }),
      (err) => {
        if (err) {
          const pending = device.pending.get(commandId);
          if (pending) {
            clearTimeout(pending.timer);
            device.pending.delete(commandId);
            pending.reject(new Error(`Failed to send command: ${err.message}`));
          }
        }
      }
    );
  });
}

// ---------------------------------------------------------------------------
// MCP server
// ---------------------------------------------------------------------------

function createMcpServer(): Server {
  const server = new Server(
    { name: "ax360e-mcp-bridge", version: "1.0.0" },
    { capabilities: { tools: {} } }
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: [
      {
        name: "list_devices",
        description:
          "List all ax360e Android devices currently connected to the bridge. " +
          "Each device entry includes deviceId, deviceName, appVersion, connectedAt (ISO ms), " +
          "lastSeen, currentGame (or null), and pendingCommands count. " +
          "Call this first to discover which device to target with subsequent tools.",
        inputSchema: { type: "object", properties: {} },
      },
      {
        name: "get_device_info",
        description:
          "Get detailed info about a specific device: Android version, SoC, total/RAM, " +
          "storage_root, current emulator status (running/paused), FPS, current game.",
        inputSchema: {
          type: "object",
          properties: { deviceId: { type: "string" } },
          required: ["deviceId"],
        },
      },
      {
        name: "get_logs",
        description:
          "Fetch recent emulator logs from a device. Returns an array of log lines. " +
          "Optionally filter by level (error/warn/info/debug) and limit the number of lines " +
          "returned. The device keeps a ring buffer of the last ~2000 lines.",
        inputSchema: {
          type: "object",
          properties: {
            deviceId: { type: "string" },
            level: {
              type: "string",
              enum: ["all", "error", "warn", "info", "debug"],
              default: "all",
            },
            limit: { type: "integer", minimum: 1, maximum: 5000, default: 500 },
            filter: {
              type: "string",
              description: "Optional substring filter applied to each line.",
            },
          },
          required: ["deviceId"],
        },
      },
      {
        name: "list_games",
        description:
          "List all games available on the device's configured game directory. " +
          "Returns an array of {uri, name}. The uri can be passed to open_game.",
        inputSchema: {
          type: "object",
          properties: { deviceId: { type: "string" } },
          required: ["deviceId"],
        },
      },
      {
        name: "open_game",
        description:
          "Launch a game on the device by its URI (returned by list_games). " +
          "This is the same as tapping the game in the app's main screen. " +
          "The emulator activity starts in a separate process and the bridge " +
          "stays connected for log streaming.",
        inputSchema: {
          type: "object",
          properties: { deviceId: { type: "string" }, uri: { type: "string" } },
          required: ["deviceId", "uri"],
        },
      },
      {
        name: "close_game",
        description:
          "Force-close the currently running emulator activity on the device. " +
          "Useful when a game hangs and the user can't reach the back button.",
        inputSchema: {
          type: "object",
          properties: { deviceId: { type: "string" } },
          required: ["deviceId"],
        },
      },
      {
        name: "get_emulator_status",
        description:
          "Get the current emulator status: is_running, is_paused, current_game, fps, " +
          "session_start_time (ms since epoch), session_game_name.",
        inputSchema: {
          type: "object",
          properties: { deviceId: { type: "string" } },
          required: ["deviceId"],
        },
      },
      {
        name: "send_key_event",
        description:
          "Send a virtual key event to the running emulator (e.g. simulate a button press). " +
          "key codes match the VirtualControl.KEY_CODE_* constants in the app. " +
          "pressed=true for key-down, false for key-up. value is the analog magnitude " +
          "for thumbsticks (0-32767), 0 for digital buttons.",
        inputSchema: {
          type: "object",
          properties: {
            deviceId: { type: "string" },
            keyCode: { type: "integer", description: "VirtualControl.KEY_CODE_*" },
            pressed: { type: "boolean" },
            value: { type: "integer", default: 0 },
          },
          required: ["deviceId", "keyCode", "pressed"],
        },
      },
      {
        name: "get_settings",
        description:
          "Get the current emulator settings (TOML config) as a JSON object. " +
          "Includes CPU, GPU, APU, Storage sections. Pass scope='game' for per-game " +
          "config (requires a game to be running) or scope='global' for the global config.",
        inputSchema: {
          type: "object",
          properties: {
            deviceId: { type: "string" },
            scope: {
              type: "string",
              enum: ["global", "game"],
              default: "global",
            },
          },
          required: ["deviceId"],
        },
      },
      {
        name: "ping_device",
        description:
          "Send a lightweight ping to the device and measure round-trip latency in ms. " +
          "Useful to verify the device is still responsive before issuing heavier commands.",
        inputSchema: {
          type: "object",
          properties: { deviceId: { type: "string" } },
          required: ["deviceId"],
        },
      },
    ],
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;
    try {
      switch (name) {
        case "list_devices":
          return { content: [{ type: "text", text: JSON.stringify(listDevices(), null, 2) }] };

        case "get_device_info": {
          const deviceId = z.string().parse(args?.deviceId);
          const info = await sendToDevice(deviceId, "get_device_info", {});
          return { content: [{ type: "text", text: JSON.stringify(info, null, 2) }] };
        }

        case "get_logs": {
          const deviceId = z.string().parse(args?.deviceId);
          const level = (args?.level as string) ?? "all";
          const limit = Math.min(
            Math.max((args?.limit as number) ?? 500, 1),
            MAX_LOG_LINES_PER_REQUEST
          );
          const filter = args?.filter as string | undefined;
          const result = await sendToDevice(deviceId, "get_logs", { level, limit, filter });
          return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
        }

        case "list_games": {
          const deviceId = z.string().parse(args?.deviceId);
          const games = await sendToDevice(deviceId, "list_games", {});
          return { content: [{ type: "text", text: JSON.stringify(games, null, 2) }] };
        }

        case "open_game": {
          const deviceId = z.string().parse(args?.deviceId);
          const uri = z.string().parse(args?.uri);
          const result = await sendToDevice(deviceId, "open_game", { uri });
          return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
        }

        case "close_game": {
          const deviceId = z.string().parse(args?.deviceId);
          const result = await sendToDevice(deviceId, "close_game", {});
          return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
        }

        case "get_emulator_status": {
          const deviceId = z.string().parse(args?.deviceId);
          const status = await sendToDevice(deviceId, "get_emulator_status", {});
          return { content: [{ type: "text", text: JSON.stringify(status, null, 2) }] };
        }

        case "send_key_event": {
          const deviceId = z.string().parse(args?.deviceId);
          const keyCode = z.number().int().parse(args?.keyCode);
          const pressed = z.boolean().parse(args?.pressed);
          const value = (args?.value as number) ?? 0;
          const result = await sendToDevice(deviceId, "send_key_event", {
            keyCode,
            pressed,
            value,
          });
          return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
        }

        case "get_settings": {
          const deviceId = z.string().parse(args?.deviceId);
          const scope = (args?.scope as string) ?? "global";
          const result = await sendToDevice(deviceId, "get_settings", { scope });
          return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
        }

        case "ping_device": {
          const deviceId = z.string().parse(args?.deviceId);
          const t0 = Date.now();
          await sendToDevice(deviceId, "ping", {});
          const latency = Date.now() - t0;
          return {
            content: [{ type: "text", text: JSON.stringify({ deviceId, latencyMs: latency }) }],
          };
        }

        default:
          throw new Error(`Unknown tool: ${name}`);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      return {
        content: [{ type: "text", text: `Error: ${message}` }],
        isError: true,
      };
    }
  });

  return server;
}

// ---------------------------------------------------------------------------
// HTTP + WebSocket server
// ---------------------------------------------------------------------------

const app = express();
app.use(express.json({ limit: "1mb" }));

function checkApiKey(req: express.Request): boolean {
  const header = req.header("X-API-Key");
  const query = req.query["apiKey"];
  const queryStr = typeof query === "string" ? query : undefined;
  const candidate = header ?? queryStr;
  return candidate === API_KEY;
}

app.get("/health", (_req, res) => {
  res.json({ ok: true, devices: devices.size, uptime: process.uptime() });
});

app.get("/", (req, res) => {
  if (!checkApiKey(req)) {
    res.status(401).send("Unauthorized");
    return;
  }
  const html = renderDashboard();
  res.type("html").send(html);
});

// ---------------------------------------------------------------------------
// Inline JSON-RPC-over-HTTP transport.
//
// Implements the MCP Transport interface for a single request/response
// cycle. The server connects to it, we inject the client's JSON-RPC
// message via handleMessage(), the server processes it and calls send()
// with the response, which we write directly to the HTTP response.
// ---------------------------------------------------------------------------
class InlineHttpServerTransport implements Transport {
  private res: express.Response;
  private settled = false;
  private responsePromise: Promise<void>;
  private resolveResponse!: () => void;

  onclose?: () => void;
  onerror?: (e: Error) => void;
  onmessage?: (msg: JSONRPCMessage) => void;

  constructor(_req: express.Request, res: express.Response) {
    this.res = res;
    this.responsePromise = new Promise((resolve) => {
      this.resolveResponse = resolve;
    });
  }

  async start(): Promise<void> {
    // No-op — the HTTP request is already in flight.
  }

  async send(message: JSONRPCMessage): Promise<void> {
    if (this.settled) return;
    this.settled = true;
    const msg = message as Record<string, unknown>;
    // Only send responses (with id/result/error) back over HTTP.
    // Notifications (no id) have no response to send.
    if ("id" in msg || "result" in msg || "error" in msg) {
      this.res.status(200).json(message);
    } else {
      // It's a notification — respond with 202 Accepted.
      if (!this.res.headersSent) {
        this.res.status(202).end();
      }
    }
    this.resolveResponse();
  }

  async close(): Promise<void> {
    if (!this.settled) {
      this.settled = true;
      if (!this.res.headersSent) {
        this.res.status(204).end();
      }
      this.resolveResponse();
    }
    this.onclose?.();
  }

  /** Inject a JSON-RPC message and wait for the server to respond via send(). */
  async handleMessage(message: JSONRPCMessage): Promise<void> {
    this.onmessage?.(message);
    // Wait until send() or close() is called by the server.
    await this.responsePromise;
  }
}

// ---------------------------------------------------------------------------
// MCP over SSE transport
// ---------------------------------------------------------------------------
const transports = new Map<string, SSEServerTransport>();

app.get("/sse", async (req, res) => {
  if (!checkApiKey(req)) {
    res.status(401).send("Unauthorized");
    return;
  }
  const transport = new SSEServerTransport("/messages", res);
  const server = createMcpServer();
  transports.set(transport.sessionId, transport);

  res.on("close", () => {
    transports.delete(transport.sessionId);
    server.close().catch(() => {});
  });

  await server.connect(transport);
});

// ---------------------------------------------------------------------------
// Simple JSON-RPC over HTTP endpoint (no SSE required).
//
// Some reverse proxies (Render's free tier included) close idle SSE
// connections before the client can POST back, which breaks the standard
// MCP SSE transport. This endpoint accepts a single JSON-RPC request per
// HTTP POST and returns the response in the same HTTP response — no
// streaming, no sessions, no timeouts.
//
// The request body is a standard JSON-RPC 2.0 message:
//   { "jsonrpc": "2.0", "id": 1, "method": "tools/call",
//     "params": { "name": "list_devices", "arguments": {} } }
//
// The response is a standard JSON-RPC 2.0 response:
//   { "jsonrpc": "2.0", "id": 1, "result": { "content": [...] } }
//
// Notifications (no `id`) get a 202 with empty body.
// ---------------------------------------------------------------------------
app.post("/rpc", async (req, res) => {
  if (!checkApiKey(req)) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  const rpcReq = req.body;
  if (!rpcReq || typeof rpcReq !== "object" || typeof rpcReq.method !== "string") {
    res.status(400).json({
      jsonrpc: "2.0",
      error: { code: -32600, message: "Invalid Request" },
      id: null,
    });
    return;
  }

  // Create a fresh MCP server for each request (cheap — no state between calls).
  const server = createMcpServer();
  const transport = new InlineHttpServerTransport(req, res);
  try {
    await server.connect(transport);
    await transport.handleMessage(rpcReq);
  } catch (e) {
    if (!res.headersSent) {
      res.status(500).json({
        jsonrpc: "2.0",
        error: { code: -32603, message: e instanceof Error ? e.message : "Internal error" },
        id: rpcReq.id ?? null,
      });
    }
  } finally {
    await server.close().catch(() => {});
  }
});

app.post("/messages", async (req, res) => {
  if (!checkApiKey(req)) {
    res.status(401).send("Unauthorized");
    return;
  }
  const sessionId = req.query["sessionId"] as string;
  const transport = transports.get(sessionId);
  if (!transport) {
    res.status(404).send("Session not found");
    return;
  }
  await transport.handlePostMessage(req, res);
});

// WebSocket endpoint for Android devices
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url ?? "", `http://${req.headers.host}`);
  if (url.pathname !== "/ws/device") {
    socket.destroy();
    return;
  }
  const apiKey = url.searchParams.get("apiKey");
  if (apiKey !== API_KEY) {
    socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit("connection", ws, req);
  });
});

wss.on("connection", (ws) => {
  let registeredDeviceId: string | null = null;
  let registeredDevice: ConnectedDevice | null = null;

  const registrationTimer = setTimeout(() => {
    if (!registeredDeviceId) {
      ws.close(4001, "Registration timeout");
    }
  }, 10_000);

  ws.on("message", (raw) => {
    let msg: unknown;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      ws.send(JSON.stringify({ type: "error", error: "Invalid JSON" }));
      return;
    }

    const m = msg as Record<string, unknown>;
    const type = m["type"] as string;

    if (type === "register" && !registeredDeviceId) {
      const deviceId = (m["deviceId"] as string) || "";
      const deviceName = (m["deviceName"] as string) || "unknown";
      const appVersion = (m["appVersion"] as string) || "unknown";
      const currentGame = (m["currentGame"] as string | null) ?? null;

      if (!deviceId) {
        ws.send(JSON.stringify({ type: "error", error: "Missing deviceId" }));
        ws.close(4002, "Missing deviceId");
        return;
      }

      // Replace any existing connection with the same deviceId
      const existing = devices.get(deviceId);
      if (existing) {
        try {
          existing.ws.close(4003, "Replaced by newer connection");
        } catch {}
        devices.delete(deviceId);
      }

      registeredDeviceId = deviceId;
      registeredDevice = {
        deviceId,
        deviceName,
        appVersion,
        connectedAt: Date.now(),
        lastSeen: Date.now(),
        ws,
        currentGame,
        pending: new Map(),
        logBuffer: existing?.logBuffer ?? [],
      };
      devices.set(deviceId, registeredDevice);
      clearTimeout(registrationTimer);
      ws.send(JSON.stringify({ type: "registered", deviceId }));
      console.log(`[device] connected: ${deviceName} (${deviceId})`);
      return;
    }

    if (!registeredDevice || !registeredDeviceId) {
      ws.send(JSON.stringify({ type: "error", error: "Not registered" }));
      return;
    }

    registeredDevice.lastSeen = Date.now();

    if (type === "response") {
      const commandId = m["commandId"] as string;
      const pending = registeredDevice.pending.get(commandId);
      if (pending) {
        clearTimeout(pending.timer);
        registeredDevice.pending.delete(commandId);
        if (m["error"]) {
          pending.reject(new Error(String(m["error"])));
        } else {
          pending.resolve(m["result"] ?? {});
        }
      }
      return;
    }

    if (type === "log") {
      const line = m["line"] as string;
      if (typeof line === "string") {
        registeredDevice.logBuffer.push(line);
        if (registeredDevice.logBuffer.length > MAX_BUFFERED_LOG_LINES) {
          registeredDevice.logBuffer.shift();
        }
      }
      return;
    }

    if (type === "status_update") {
      const game = m["currentGame"] as string | null;
      registeredDevice.currentGame = game;
      return;
    }

    if (type === "ping_reply") {
      // No-op — the sendToDevice promise was already resolved by the
      // "response" message that accompanied this ping reply.
      return;
    }

    // Unknown message type — ignore silently for forward compatibility.
  });

  ws.on("close", () => {
    clearTimeout(registrationTimer);
    if (registeredDeviceId && registeredDevice) {
      // Reject any pending commands
      for (const pending of registeredDevice.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(new Error("Device disconnected"));
      }
      registeredDevice.pending.clear();
      if (devices.get(registeredDeviceId) === registeredDevice) {
        devices.delete(registeredDeviceId);
      }
      console.log(`[device] disconnected: ${registeredDevice.deviceName} (${registeredDeviceId})`);
    }
  });

  ws.on("error", (err) => {
    console.error("[device] ws error:", err.message);
  });
});

// Idle sweep: every 60s, drop devices whose WebSocket has died but hasn't
// emitted a close event yet. Also fails any commands whose timeout fired.
setInterval(() => {
  for (const [id, device] of devices) {
    if (device.ws.readyState === WebSocket.CLOSED || device.ws.readyState === WebSocket.CLOSING) {
      for (const pending of device.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(new Error("Device WebSocket closed"));
      }
      device.pending.clear();
      devices.delete(id);
    }
  }
}, 60_000).unref();

function renderDashboard(): string {
  const deviceRows = Array.from(devices.values())
    .map(
      (d) => `
      <tr>
        <td>${escapeHtml(d.deviceId)}</td>
        <td>${escapeHtml(d.deviceName)}</td>
        <td>${escapeHtml(d.appVersion)}</td>
        <td>${new Date(d.connectedAt).toISOString()}</td>
        <td>${d.currentGame ? escapeHtml(d.currentGame) : "<em>idle</em>"}</td>
        <td>${d.pending.size}</td>
        <td>${d.logBuffer.length}</td>
      </tr>`
    )
    .join("");

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>ax360e MCP Bridge</title>
  <style>
    body { font: 14px/1.5 -apple-system, system-ui, sans-serif; max-width: 920px; margin: 32px auto; padding: 0 16px; color: #222; }
    h1 { font-size: 20px; }
    table { width: 100%; border-collapse: collapse; margin-top: 16px; }
    th, td { padding: 8px 10px; border-bottom: 1px solid #eee; text-align: left; font-size: 13px; }
    th { background: #f5f5f5; }
    code { background: #f5f5f5; padding: 2px 5px; border-radius: 3px; }
    .meta { color: #666; margin-top: 24px; font-size: 12px; }
  </style>
</head>
<body>
  <h1>ax360e MCP Bridge</h1>
  <p>Connected devices: <strong>${devices.size}</strong></p>
  <table>
    <thead>
      <tr>
        <th>Device ID</th><th>Name</th><th>App version</th><th>Connected</th>
        <th>Current game</th><th>Pending cmds</th><th>Log buffer</th>
      </tr>
    </thead>
    <tbody>
      ${deviceRows || '<tr><td colspan="7"><em>No devices connected.</em></td></tr>'}
    </tbody>
  </table>
  <div class="meta">
    <p>MCP endpoint: <code>GET /sse</code> + <code>POST /messages?sessionId=...</code></p>
    <p>Device WebSocket: <code>GET /ws/device?apiKey=...</code></p>
    <p>Health: <code>GET /health</code></p>
  </div>
</body>
</html>`;
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

server.listen(PORT, () => {
  console.log(`ax360e MCP Bridge listening on :${PORT}`);
  console.log(`  MCP SSE:  http://localhost:${PORT}/sse`);
  console.log(`  Devices:  ws://localhost:${PORT}/ws/device?apiKey=...`);
  console.log(`  Health:   http://localhost:${PORT}/health`);
});
