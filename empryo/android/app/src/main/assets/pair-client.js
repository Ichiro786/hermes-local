// Empryo Local pairing helper. Runs inside the rootfs as `BUN_BE_BUN=1 empryo /opt/empryo-local/pair-client.js`.
// It is a "window" on Empryo's phone-service control socket (the same protocol the TUI's /remote uses):
// reads ~/.empryo/mobile/service.json (endpoint + token), says hello with ui:true, then relays NDJSON both ways:
// stdin lines from the app are sent as-is (start / pairing-view / confirm / info / forget), every service line
// is printed to stdout. Stays a window while connected, so pair requests ("confirm") reach the app.
const fs = require("fs");
const net = require("net");
const rec = JSON.parse(fs.readFileSync(`${process.env.HOME}/.empryo/mobile/service.json`, "utf8"));
const sock = net.connect(rec.endpoint);
const send = (o) => sock.write(JSON.stringify(o) + "\n");
sock.setEncoding("utf8");
sock.on("connect", () => {
  send({ op: "hello", token: rec.token, ui: true, clientVersion: rec.version, release: rec.release, link: 4 });
  console.log(JSON.stringify({ event: "helper-connected", endpoint: rec.endpoint, servicePid: rec.pid, version: rec.version }));
});
let buf = "";
sock.on("data", (d) => {
  buf += d;
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i);
    buf = buf.slice(i + 1);
    if (line.trim()) console.log(line);
  }
});
sock.on("error", (e) => { console.log(JSON.stringify({ event: "helper-error", error: String(e && e.message || e) })); process.exit(2); });
sock.on("close", () => { console.log(JSON.stringify({ event: "helper-closed" })); process.exit(0); });
let inBuf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (d) => {
  inBuf += d;
  let i;
  while ((i = inBuf.indexOf("\n")) >= 0) {
    const line = inBuf.slice(0, i).trim();
    inBuf = inBuf.slice(i + 1);
    if (line) sock.write(line + "\n");
  }
});
process.stdin.on("end", () => sock.end());
