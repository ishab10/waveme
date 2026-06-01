const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const Database = require('better-sqlite3');
const path = require('path');

// Initialize database
const db = new Database('sync_data.db');
db.exec(`
  CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    content TEXT,
    sender TEXT,
    timestamp INTEGER,
    room TEXT,
    received_at INTEGER
  )
`);

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"]
  }
});

app.use(cors());
app.use(express.json());

// For uptime calculation
const startTime = Date.now();

// Logger middleware
app.use((req, res, next) => {
  console.log(`${new Date().toISOString()} - ${req.method} ${req.url}`);
  next();
});

// Serve dashboard at GET /
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'dashboard.html'));
});

// POST /sync endpoint
app.post('/sync', (req, res) => {
  const { id, content, sender, timestamp, room } = req.body;

  if (!id || !content || !sender || !timestamp || !room) {
    return res.status(400).json({ error: "Missing required fields" });
  }

  try {
    const insert = db.prepare(`
      INSERT OR REPLACE INTO messages (id, content, sender, timestamp, room, received_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `);

    insert.run(id, content, sender, timestamp, room, Date.now());

    const message = { id, content, sender, timestamp, room };

    // Emit to all connected Socket.io clients
    io.emit('new_msg', message);

    console.log(`Synced message: ${id} from ${sender}`);
    res.json({ status: "ok" });
  } catch (err) {
    console.error("Database error:", err.message);
    res.status(500).json({ error: "Internal server error" });
  }
});

// GET /messages endpoint - returns all stored messages
app.get('/messages', (req, res) => {
  try {
    const messages = db.prepare('SELECT * FROM messages ORDER BY timestamp DESC').all();
    res.json(messages);
  } catch (err) {
    console.error("Database error:", err.message);
    res.status(500).json({ error: "Internal server error" });
  }
});

// GET /status endpoint - health check and metrics
app.get('/status', (req, res) => {
  try {
    const count = db.prepare('SELECT COUNT(*) as total FROM messages').get().total;
    const uptimeSeconds = Math.floor((Date.now() - startTime) / 1000);

    res.json({
      status: "online",
      message_count: count,
      uptime: `${uptimeSeconds}s`
    });
  } catch (err) {
    res.status(500).json({ status: "error", error: err.message });
  }
});

const PORT = 4000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Sync Node running on http://localhost:${PORT}`);
  console.log(`Database initialized. Socket.io ready.`);
});
