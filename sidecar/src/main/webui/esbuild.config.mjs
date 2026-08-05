import { build, context } from "esbuild";
import { cpSync, readFileSync } from "fs";
import { createRequire } from "module";

const require = createRequire(import.meta.url);

const rawPlugin = {
  name: "raw-loader",
  setup(b) {
    b.onResolve({ filter: /\?raw$/ }, (args) => ({
      path: require.resolve(args.path.replace(/\?raw$/, ""), {
        paths: [args.resolveDir],
      }),
      namespace: "raw-loader",
    }));
    b.onLoad({ filter: /.*/, namespace: "raw-loader" }, (args) => ({
      contents: `export default ${JSON.stringify(readFileSync(args.path, "utf-8"))}`,
      loader: "js",
    }));
  },
};

const isWatch = process.argv.includes("--watch");

const options = {
  entryPoints: ["src/app.ts"],
  bundle: true,
  outfile: "dist/app.js",
  format: "esm",
  target: "es2020",
  minify: !isWatch,
  sourcemap: isWatch,
  plugins: [rawPlugin],
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}

cpSync("public", "dist", { recursive: true });
