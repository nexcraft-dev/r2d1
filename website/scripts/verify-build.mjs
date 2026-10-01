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
  "docs/quarkus/index.html",
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
  "spring",
  "quarkus"
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
  const localizedSpring = readFileSync(
    join(rootPath, locale, "docs", "spring", "index.html"),
    "utf8"
  );
  const localizedSpringHeading = {
    ko: "의존성",
    zh: "依赖",
    ja: "依存関係"
  }[locale];

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

  if (!localizedSpring.includes(`<h2>${localizedSpringHeading}</h2>`)) {
    throw new Error(`Localized Spring documentation is missing translated content: ${locale}`);
  }
}

for (const locale of ["en", ...localizedLocales]) {
  const prefix = locale === "en" ? "" : `${locale}/`;
  const quarkus = readFileSync(join(rootPath, prefix, "docs/quarkus/index.html"), "utf8");
  const localeHome = readFileSync(join(rootPath, prefix, "index.html"), "utf8");
  const localeOverview = readFileSync(join(rootPath, prefix, "docs/index.html"), "utf8");
  const heading = { en: "Enable and configure", ko: "활성화와 설정", zh: "启用与配置", ja: "有効化と設定" }[locale];
  const status = { en: "Included in R2D1 1.8.0", ko: "R2D1 1.8.0에 포함", zh: "包含于 R2D1 1.8.0", ja: "R2D1 1.8.0 に含まれます" }[locale];
  const dependencyHeading = { en: "Dependency requirements", ko: "의존성별 필요 여부", zh: "依赖项的适用范围", ja: "依存関係の必要範囲" }[locale];
  const backendHeading = { en: "Filesystem + JDBC + H2 dependencies", ko: "Filesystem + JDBC + H2 선택 의존성", zh: "Filesystem + JDBC + H2 可选依赖", ja: "Filesystem + JDBC + H2 の選択依存関係" }[locale];
  const href = `/${prefix}docs/quarkus/`;
  for (const html of [localeHome, localeOverview]) {
    if (!html.includes("R2D1-QUARKUS") || !html.includes(`href="${href}"`)) {
      throw new Error(`Missing Quarkus card or locale-specific navigation: ${locale}`);
    }
  }
  if (!quarkus.includes(`<h2>${heading}</h2>`) || !quarkus.includes(status)) {
    throw new Error(`Missing localized Quarkus content or 1.8.0 release inclusion: ${locale}`);
  }
  if (!quarkus.includes(`<h2>${dependencyHeading}</h2>`) || !quarkus.includes(`<h3>${backendHeading}</h3>`)) {
    throw new Error(`Missing required/optional dependency labels: ${locale}`);
  }
  if (quarkus.includes("Preview") || quarkus.includes("PREVIEW") || quarkus.includes("not yet published")) {
    throw new Error(`Quarkus is incorrectly labeled as a preview: ${locale}`);
  }
  for (const value of [
    `dev.nexcraft:r2d1-quarkus:${release.latestStableVersion}`,
    "r2d1-quarkus-deployment", "quarkus.r2d1.enabled=true",
    "quarkus.r2d1.jdbc.datasource=selected", "DocumentCodec", "3.39.5",
    `implementation(&quot;dev.nexcraft:r2d1-quarkus:${release.latestStableVersion}&quot;)`,
    `implementation(&quot;dev.nexcraft:r2d1-filesystem:${release.latestStableVersion}&quot;)`,
    "quarkus-jdbc-h2", "quarkus-agroal", "core", "r2d1-quarkus-deployment"
  ]) {
    if (!quarkus.includes(value)) throw new Error(`Missing Quarkus contract ${value}: ${locale}`);
  }
  const extensionDependency = quarkus.indexOf(`dev.nexcraft:r2d1-quarkus:${release.latestStableVersion}`);
  const optionalFilesystem = quarkus.indexOf(`dev.nexcraft:r2d1-filesystem:${release.latestStableVersion}`);
  if (extensionDependency === -1 || optionalFilesystem < extensionDependency) {
    throw new Error(`Quarkus extension dependency is not presented as the base dependency: ${locale}`);
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
