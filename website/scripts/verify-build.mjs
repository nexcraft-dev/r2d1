import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

const root = new URL("../dist/", import.meta.url);
const rootPath = root.pathname;
const release = JSON.parse(
  readFileSync(new URL("../src/data/release.json", import.meta.url), "utf8")
);

const requiredFiles = [
  "index.html",
  "docs/index.html",
  "docs/getting-started/index.html",
  "docs/concepts/index.html",
  "docs/configuration/index.html",
  "docs/document-stores/r2/index.html",
  "docs/document-stores/filesystem/index.html",
  "docs/index-stores/d1/index.html",
  "docs/index-stores/jdbc/index.html",
  "docs/querying/index.html",
  "docs/consistency/index.html",
  "docs/micronaut/index.html",
  "docs/spring/index.html",
  "404.html",
  "robots.txt"
];

const localizedLocales = ["ko", "zh", "ja"];
const localizedSlugs = [
  "getting-started",
  "concepts",
  "configuration",
  "document-stores/r2",
  "document-stores/filesystem",
  "index-stores/d1",
  "index-stores/jdbc",
  "querying",
  "consistency",
  "micronaut",
  "spring"
];

for (const locale of localizedLocales) {
  requiredFiles.push(`${locale}/index.html`, `${locale}/docs/index.html`, `${locale}/404/index.html`);
  for (const slug of localizedSlugs) {
    requiredFiles.push(`${locale}/docs/${slug}/index.html`);
  }
}

for (const relativePath of requiredFiles) {
  if (!existsSync(join(rootPath, relativePath))) {
    throw new Error(`Missing generated website file: ${relativePath}`);
  }
}

const home = readFileSync(join(rootPath, "index.html"), "utf8");
const docsOverview = readFileSync(join(rootPath, "docs/index.html"), "utf8");
const gettingStarted = readFileSync(
  join(rootPath, "docs/getting-started/index.html"),
  "utf8"
);
const micronaut = readFileSync(join(rootPath, "docs/micronaut/index.html"), "utf8");
const spring = readFileSync(join(rootPath, "docs/spring/index.html"), "utf8");
const robots = readFileSync(join(rootPath, "robots.txt"), "utf8");

for (const [locale, htmlLang] of [["ko", "ko"], ["zh", "zh-CN"], ["ja", "ja"]]) {
  const localizedHome = readFileSync(join(rootPath, locale, "index.html"), "utf8");
  const localizedGettingStarted = readFileSync(
    join(rootPath, locale, "docs", "getting-started", "index.html"),
    "utf8"
  );

  if (!localizedHome.includes(`<html lang="${htmlLang}"`)) {
    throw new Error(`Localized home page has the wrong lang attribute: ${locale}`);
  }

  if (!localizedHome.includes(`href="/${locale}/docs/getting-started/"`)) {
    throw new Error(`Localized home page is missing its localized Getting Started link: ${locale}`);
  }

  if (!localizedHome.includes('hreflang="en"') || !localizedHome.includes('hreflang="zh-CN"')) {
    throw new Error(`Localized home page is missing alternate language metadata: ${locale}`);
  }

  if (!localizedGettingStarted.includes('class="language-java"')) {
    throw new Error(`Localized Getting Started is missing Java syntax highlighting: ${locale}`);
  }
}

if (!home.includes('rel="canonical"') || !home.includes("https://r2d1.nexcraft.dev/")) {
  throw new Error("Home page is missing its canonical URL");
}

if (!gettingStarted.includes('data-language="java"')) {
  throw new Error("Java syntax highlighting was not generated");
}

if (!micronaut.includes('data-language="yaml"')) {
  throw new Error("YAML syntax highlighting was not generated");
}

if (!home.includes('href="/docs/getting-started/"') || !home.includes('href="/docs/"')) {
  throw new Error("Home page is missing required internal documentation links");
}

if (!home.includes(`Latest stable: ${release.latestStableVersion}`)) {
  throw new Error("Home page is missing the current stable release marker");
}

if (!home.includes("R2D1-SPRING-BOOT-STARTER") || !home.includes("dev.nexcraft:r2d1-spring-boot-starter")) {
  throw new Error("Home page is missing the published Spring integration");
}

if (
  !docsOverview.includes("R2D1-SPRING-BOOT-STARTER") ||
  !docsOverview.includes(`Spring Boot 4 starter ${release.latestStableVersion}`)
) {
  throw new Error("Documentation overview is missing the published Spring integration");
}

if (!gettingStarted.includes(`dev.nexcraft:r2d1:${release.latestStableVersion}`)) {
  throw new Error("Getting Started does not use the current stable release token");
}

if (
  !spring.includes(`Available in ${release.latestStableVersion}`) ||
  !spring.includes("published to Maven Central")
) {
  throw new Error("Spring documentation is missing its published-release status");
}

for (const requiredLabel of [
  "DOCUMENT STORE",
  "INDEX STORE",
  "R2D1-JDBC",
  "R2D1-FILESYSTEM",
  "R2D1-MICRONAUT"
]) {
  if (!home.includes(requiredLabel)) {
    throw new Error(`Home page is missing module or backend label: ${requiredLabel}`);
  }
}

for (const requiredLabel of ["DOCUMENT STORES", "INDEX STORES", "COMMON API"]) {
  if (!docsOverview.includes(requiredLabel)) {
    throw new Error(`Documentation overview is missing section: ${requiredLabel}`);
  }
}

for (const requiredHeading of ["1. Choose a document store", "2. Choose an index store", "7. Run an indexed query"]) {
  if (!gettingStarted.includes(requiredHeading)) {
    throw new Error(`Getting Started is missing setup or common API step: ${requiredHeading}`);
  }
}

if (!robots.includes("Sitemap: https://r2d1.nexcraft.dev/sitemap-index.xml")) {
  throw new Error("robots.txt does not point to the canonical sitemap");
}

const sitemapCandidates = ["sitemap-index.xml", "sitemap-0.xml"];
if (!sitemapCandidates.some((file) => existsSync(join(rootPath, file)))) {
  throw new Error("No generated sitemap was found");
}

const generatedHtmlFiles = requiredFiles.filter((file) => file.endsWith(".html"));
let internalLinkCount = 0;

for (const relativePath of generatedHtmlFiles) {
  const html = readFileSync(join(rootPath, relativePath), "utf8");

  if (!html.includes('rel="canonical"') || html.includes("undefined")) {
    throw new Error(`Invalid canonical metadata or title in generated file: ${relativePath}`);
  }

  if (html.includes("{{latestStableVersion}}") || html.includes("&lt;version&gt;")) {
    throw new Error(`Unresolved website dependency version token: ${relativePath}`);
  }

  for (const [, href] of html.matchAll(/<a\b[^>]*\bhref="(\/[^"#?]*)"/g)) {
    const target = href === "/"
      ? join(rootPath, "index.html")
      : href === "/404/"
        ? join(rootPath, "404.html")
      : href.endsWith("/")
        ? join(rootPath, href.slice(1), "index.html")
        : join(rootPath, href.slice(1));

    if (!existsSync(target)) {
      throw new Error(`Broken internal navigation target ${href} in ${relativePath}`);
    }

    internalLinkCount += 1;
  }
}

console.log(
  `Verified ${requiredFiles.length} generated website files, ${internalLinkCount} internal links, sitemap, metadata, and code highlighting.`
);
