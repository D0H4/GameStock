import { readFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

// Keep the comparison's initial conditions identical; never connect to an application database.
const baseline = JSON.parse(await readFile(new URL("../public/simulation/before.json", import.meta.url), "utf8"));
const child = spawn("mvn", ["-q", "-Dtest=MarketReplayExportTest",
  "-Dmarket.replay.output=../public/simulation/after.json", "-Dmarket.replay.version=after",
  `-Dmarket.replay.code=${baseline.code}`, `-Dmarket.replay.seed=${baseline.seed}`,
  `-Dmarket.replay.minutes=${baseline.durationMs / 60000}`, "test"], {
  cwd: fileURLToPath(new URL("../backend", import.meta.url)), stdio: "inherit",
});
child.on("error", error => { console.error(error.message); process.exitCode = 1; });
child.on("exit", code => {
  process.exitCode = code ?? 1;
  if (code === 0) console.log("Replay updated. Open /simulation.html on the Vite server, or refresh the page.");
});
