import { defineConfig } from "astro/config";
import sitemap from "@astrojs/sitemap";
import release from "./src/data/release.json";

const releaseTokenPlugin = {
  name: "r2d1-release-token",
  enforce: "pre",
  transform(source, id) {
    const pathname = id.split("?", 1)[0];
    if (!pathname.endsWith(".md")) {
      return undefined;
    }
    return source.replaceAll("{{latestStableVersion}}", release.latestStableVersion);
  }
};

export default defineConfig({
  site: "https://r2d1.nexcraft.dev",
  output: "static",
  vite: {
    plugins: [releaseTokenPlugin]
  },
  integrations: [
    sitemap({
      filter: (page) => !page.includes("/404/")
    })
  ]
});
