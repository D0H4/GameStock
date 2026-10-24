import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    target: "es2022",
    sourcemap: false,
    rollupOptions: { input: { main: "index.html", simulation: "simulation.html" } },
  },
  server: {
    port: 5180,
    strictPort: true,
  },
});
