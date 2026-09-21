import { defineConfig } from "astro/config";
import sitemap from "@astrojs/sitemap";

export default defineConfig({
  site: "https://r2d1.nexcraft.dev",
  output: "static",
  integrations: [
    sitemap({
      filter: (page) => !page.includes("/404/")
    })
  ]
});
