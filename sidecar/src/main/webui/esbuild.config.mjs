import { build, context } from "esbuild";
import { cpSync } from "fs";

const isWatch = process.argv.includes("--watch");

const options = {
  entryPoints: ["src/app.ts"],
  bundle: true,
  outfile: "dist/app.js",
  format: "esm",
  target: "es2020",
  minify: !isWatch,
  sourcemap: isWatch,
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}

cpSync("public", "dist", { recursive: true });
