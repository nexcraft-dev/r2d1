import type { Locale } from "./config";

interface UiCopy {
  htmlLang: string;
  skip: string;
  bannerTitle: string;
  bannerSubtitle: string;
  home: string;
  docs: string;
  docsTitle: string;
  github: string;
  getStarted: string;
  languageLabel: string;
  docsMobileOpen: string;
  docsMobileClose: string;
  docsEyebrow: string;
  footerHome: string;
  footerDocs: string;
  footerLicense: string;
  footerTagline: string;
  docsNav: {
    title: string;
    groups: Array<{
      label: string;
      links: Array<{ label: string; href: string }>;
    }>;
  };
}

export const ui = {
  en: {
    htmlLang: "en",
    skip: "Skip to content",
    bannerTitle: "R2D1 / JAVA MODULES",
    bannerSubtitle: "One collection API across document and index backends.",
    home: "HOME",
    docs: "DOCS",
    docsTitle: "Docs",
    github: "GITHUB",
    getStarted: "GET STARTED",
    languageLabel: "Language",
    docsMobileOpen: "OPEN DOCUMENTATION NAVIGATION",
    docsMobileClose: "CLOSE DOCUMENTATION NAVIGATION",
    docsEyebrow: "R2D1 / DOCS",
    footerHome: "HOME",
    footerDocs: "DOCUMENTATION",
    footerLicense: "Apache License 2.0",
    footerTagline: "Static documentation for R2D1.",
    docsNav: {
      title: "DOCUMENTATION",
      groups: [
        {
          label: "START HERE",
          links: [
            { label: "Overview", href: "/docs/" },
            { label: "Getting Started", href: "/docs/getting-started/" }
          ]
        },
        {
          label: "DOCUMENT STORES",
          links: [
            { label: "R2 DocumentStore", href: "/docs/document-stores/r2/" },
            { label: "Filesystem DocumentStore", href: "/docs/document-stores/filesystem/" }
          ]
        },
        {
          label: "INDEX STORES",
          links: [
            { label: "D1 IndexStore", href: "/docs/index-stores/d1/" },
            { label: "JDBC IndexStore", href: "/docs/index-stores/jdbc/" }
          ]
        },
        {
          label: "COMMON API",
          links: [
            { label: "Core Concepts", href: "/docs/concepts/" },
            { label: "Configuration", href: "/docs/configuration/" },
            { label: "Querying", href: "/docs/querying/" }
          ]
        },
        {
          label: "OPERATIONS",
          links: [{ label: "Consistency and Recovery", href: "/docs/consistency/" }]
        },
        {
          label: "INTEGRATIONS",
          links: [
            { label: "Micronaut", href: "/docs/micronaut/" },
            { label: "Spring Boot", href: "/docs/spring/" }
          ]
        }
      ]
    }
  },
  ko: {
    htmlLang: "ko",
    skip: "본문으로 건너뛰기",
    bannerTitle: "R2D1 / 자바 모듈",
    bannerSubtitle: "하나의 컬렉션 API로 문서 및 인덱스 백엔드를 연결합니다.",
    home: "홈",
    docs: "문서",
    docsTitle: "문서",
    github: "깃허브",
    getStarted: "시작하기",
    languageLabel: "언어",
    docsMobileOpen: "문서 탐색 열기",
    docsMobileClose: "문서 탐색 닫기",
    docsEyebrow: "R2D1 / 문서",
    footerHome: "홈",
    footerDocs: "문서",
    footerLicense: "Apache License 2.0",
    footerTagline: "R2D1 정적 문서입니다.",
    docsNav: {
      title: "문서 탐색",
      groups: [
        {
          label: "처음 시작",
          links: [
            { label: "개요", href: "/docs/" },
            { label: "시작하기", href: "/docs/getting-started/" }
          ]
        },
        {
          label: "문서 저장소",
          links: [
            { label: "R2 DocumentStore", href: "/docs/document-stores/r2/" },
            { label: "Filesystem DocumentStore", href: "/docs/document-stores/filesystem/" }
          ]
        },
        {
          label: "인덱스 저장소",
          links: [
            { label: "D1 IndexStore", href: "/docs/index-stores/d1/" },
            { label: "JDBC IndexStore", href: "/docs/index-stores/jdbc/" }
          ]
        },
        {
          label: "공통 API",
          links: [
            { label: "핵심 개념", href: "/docs/concepts/" },
            { label: "구성", href: "/docs/configuration/" },
            { label: "쿼리", href: "/docs/querying/" }
          ]
        },
        {
          label: "운영",
          links: [{ label: "일관성과 복구", href: "/docs/consistency/" }]
        },
        {
          label: "통합",
          links: [
            { label: "Micronaut", href: "/docs/micronaut/" },
            { label: "Spring Boot", href: "/docs/spring/" }
          ]
        }
      ]
    }
  },
  zh: {
    htmlLang: "zh-CN",
    skip: "跳转到正文",
    bannerTitle: "R2D1 / Java 模块",
    bannerSubtitle: "用一个集合 API 连接文档与索引后端。",
    home: "首页",
    docs: "文档",
    docsTitle: "文档",
    github: "GitHub",
    getStarted: "开始使用",
    languageLabel: "语言",
    docsMobileOpen: "打开文档导航",
    docsMobileClose: "关闭文档导航",
    docsEyebrow: "R2D1 / 文档",
    footerHome: "首页",
    footerDocs: "文档",
    footerLicense: "Apache License 2.0",
    footerTagline: "R2D1 静态文档。",
    docsNav: {
      title: "文档导航",
      groups: [
        {
          label: "从这里开始",
          links: [
            { label: "概览", href: "/docs/" },
            { label: "开始使用", href: "/docs/getting-started/" }
          ]
        },
        {
          label: "文档存储",
          links: [
            { label: "R2 DocumentStore", href: "/docs/document-stores/r2/" },
            { label: "Filesystem DocumentStore", href: "/docs/document-stores/filesystem/" }
          ]
        },
        {
          label: "索引存储",
          links: [
            { label: "D1 IndexStore", href: "/docs/index-stores/d1/" },
            { label: "JDBC IndexStore", href: "/docs/index-stores/jdbc/" }
          ]
        },
        {
          label: "通用 API",
          links: [
            { label: "核心概念", href: "/docs/concepts/" },
            { label: "配置", href: "/docs/configuration/" },
            { label: "查询", href: "/docs/querying/" }
          ]
        },
        {
          label: "运行与恢复",
          links: [{ label: "一致性与恢复", href: "/docs/consistency/" }]
        },
        {
          label: "集成",
          links: [
            { label: "Micronaut", href: "/docs/micronaut/" },
            { label: "Spring Boot", href: "/docs/spring/" }
          ]
        }
      ]
    }
  },
  ja: {
    htmlLang: "ja",
    skip: "本文へ移動",
    bannerTitle: "R2D1 / Java モジュール",
    bannerSubtitle: "1つのコレクション API でドキュメントとインデックスのバックエンドを接続します。",
    home: "ホーム",
    docs: "ドキュメント",
    docsTitle: "ドキュメント",
    github: "GitHub",
    getStarted: "はじめる",
    languageLabel: "言語",
    docsMobileOpen: "ドキュメントナビゲーションを開く",
    docsMobileClose: "ドキュメントナビゲーションを閉じる",
    docsEyebrow: "R2D1 / ドキュメント",
    footerHome: "ホーム",
    footerDocs: "ドキュメント",
    footerLicense: "Apache License 2.0",
    footerTagline: "R2D1 静的ドキュメント。",
    docsNav: {
      title: "ドキュメントナビゲーション",
      groups: [
        {
          label: "まずはここから",
          links: [
            { label: "概要", href: "/docs/" },
            { label: "はじめに", href: "/docs/getting-started/" }
          ]
        },
        {
          label: "ドキュメントストア",
          links: [
            { label: "R2 DocumentStore", href: "/docs/document-stores/r2/" },
            { label: "Filesystem DocumentStore", href: "/docs/document-stores/filesystem/" }
          ]
        },
        {
          label: "インデックスストア",
          links: [
            { label: "D1 IndexStore", href: "/docs/index-stores/d1/" },
            { label: "JDBC IndexStore", href: "/docs/index-stores/jdbc/" }
          ]
        },
        {
          label: "共通 API",
          links: [
            { label: "基本概念", href: "/docs/concepts/" },
            { label: "設定", href: "/docs/configuration/" },
            { label: "クエリ", href: "/docs/querying/" }
          ]
        },
        {
          label: "運用",
          links: [{ label: "整合性と復旧", href: "/docs/consistency/" }]
        },
        {
          label: "統合",
          links: [
            { label: "Micronaut", href: "/docs/micronaut/" },
            { label: "Spring Boot", href: "/docs/spring/" }
          ]
        }
      ]
    }
  }
} satisfies Record<Locale, UiCopy>;
