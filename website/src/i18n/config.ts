export const locales = ["en", "ko", "zh", "ja"] as const;

export type Locale = (typeof locales)[number];
export type LocalizedLocale = Exclude<Locale, "en">;

export const localeNames: Record<Locale, string> = {
  en: "EN",
  ko: "한국어",
  zh: "中文",
  ja: "日本語"
};

export const localeHreflangs: Record<Locale, string> = {
  en: "en",
  ko: "ko",
  zh: "zh-CN",
  ja: "ja"
};

const localeSet = new Set<string>(locales);

export function isLocale(value: string): value is Locale {
  return localeSet.has(value);
}

export function stripLocale(pathname: string): string {
  const segments = pathname.split("/");
  const firstSegment = segments[1];

  if (!isLocale(firstSegment)) {
    return pathname || "/";
  }

  const path = `/${segments.slice(2).join("/")}`;
  return path === "/" ? "/" : path;
}

export function localePath(pathname: string, locale: Locale): string {
  const basePath = stripLocale(pathname);

  if (locale === "en") {
    return basePath;
  }

  if (basePath === "/") {
    return `/${locale}/`;
  }

  const trailingSlash = basePath.endsWith("/") ? "/" : "";
  const pathWithoutSlashes = basePath.replace(/^\//, "").replace(/\/$/, "");
  return `/${locale}/${pathWithoutSlashes}${trailingSlash}`;
}
