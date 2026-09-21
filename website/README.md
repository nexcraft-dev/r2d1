# R2D1 Website

The R2D1 website is a static Astro site. It is intentionally separate from the Java/Gradle build.

## Release metadata

`src/data/release.json` is the single website version source. Keep `latestStableVersion` on the most
recent independently verified Maven Central release, and use `nextReleaseVersion` for integrations
that are documented before publication. Stable dependency examples are replaced with the stable
value during the Astro build.

## Local development

```shell
cd website
npm ci
npm run dev
```

Run the same checks used by the website workflow:

```shell
npm run check
npm run build
npm run verify
```

The production build is written to `dist/`. No Cloudflare credentials are required for local
development or static generation.

## Localized routes

English is the default route set. The same home, overview, getting-started, backend, common API,
operations, integration, Spring Boot integration, and 404 pages are generated under `/ko/` (Korean), `/zh/` (Simplified
Chinese), and `/ja/` (Japanese). The header language switcher keeps the current page when a
translation exists. Each generated page includes a locale-specific canonical URL and `hreflang`
alternates.

## Cloudflare Pages deployment

The GitHub Actions workflow deploys the generated `dist/` directory to Cloudflare Pages only after
a successful build on `main`. Configure these values in the repository or its environment:

- `CLOUDFLARE_API_TOKEN` secret with permission to deploy the Pages project.
- `CLOUDFLARE_ACCOUNT_ID` secret for the Cloudflare account.
- `CLOUDFLARE_PAGES_PROJECT` repository variable containing the Pages project name, normally
  `r2d1`.

The custom domain `r2d1.nexcraft.dev` and its DNS mapping remain Cloudflare account configuration;
the workflow does not change DNS or deploy from a developer workstation.
