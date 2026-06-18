// @ts-check
import { themes as prismThemes } from 'prism-react-renderer';
import versionInfo from './src/versionInfo.mjs';
import remarkVersions from './src/remark/versions.mjs';

const { versions, build } = versionInfo();

// Token → live value, replaced in markdown/code at build time (see src/remark/versions.mjs).
const versionReplacements = {
  '@cimxmlVersion@': versions.cimxml,
  '@cimvocabcheckVersion@': versions.cimvocabcheck,
  '@cimnotebookVersion@': versions.cimnotebook,
};

// "Docs built from <sha> on <date>" — the commit these docs were generated from (docs track main).
const buildLine = build.shortSha
  ? `Docs built from <a class="footer__build-link" href="https://github.com/SOPTIM/OpenCGMES/commit/${build.fullSha}" target="_blank" rel="noopener noreferrer">${build.shortSha}</a> on ${build.date}.`
  : `Docs built on ${build.date}.`;

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'OpenCGMES',
  tagline:
    'Tools for CGMES / CIM (IEC 61970) RDF — CIMXML parsing, SPARQL/SHACL validation, and editor integrations',
  favicon: 'img/favicon.svg',

  url: 'https://opencgmes.soptim.de',
  baseUrl: '/',

  organizationName: 'SOPTIM',
  projectName: 'OpenCGMES',
  trailingSlash: false,

  // Resolved per build from git tags; available to MDX/React via useDocusaurusContext().siteConfig.
  customFields: {
    versions,
    build,
  },

  onBrokenLinks: 'warn',

  markdown: {
    mermaid: true,
    // Image placeholders (/img/cimnotebook/*.png) are added by maintainers later; don't fail the
    // build while they are missing. Broken doc links stay warnings too.
    hooks: {
      onBrokenMarkdownLinks: 'warn',
      onBrokenMarkdownImages: 'warn',
    },
  },

  themes: ['@docusaurus/theme-mermaid'],

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          path: 'content',
          routeBasePath: '/',
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/SOPTIM/OpenCGMES/edit/main/docs/',
          remarkPlugins: [[remarkVersions, { replacements: versionReplacements }]],
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      image: 'img/favicon.svg',
      colorMode: {
        defaultMode: 'light',
        respectPrefersColorScheme: true,
      },
      mermaid: {
        theme: { light: 'neutral', dark: 'dark' },
      },
      navbar: {
        title: 'OpenCGMES',
        logo: {
          alt: 'OpenCGMES',
          src: 'img/logo.svg',
          srcDark: 'img/logo-dark.svg',
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'overviewSidebar',
            position: 'left',
            label: 'Overview',
          },
          {
            type: 'docSidebar',
            sidebarId: 'cimxmlSidebar',
            position: 'left',
            label: 'CIMXML',
          },
          {
            type: 'docSidebar',
            sidebarId: 'cimvocabcheckSidebar',
            position: 'left',
            label: 'CIMVocabCheck',
          },
          {
            type: 'docSidebar',
            sidebarId: 'cimnotebookSidebar',
            position: 'left',
            label: 'CIMNotebook',
          },
          {
            type: 'docSidebar',
            sidebarId: 'developerSidebar',
            position: 'left',
            label: 'Developer Guide',
          },
          {
            type: 'docSidebar',
            sidebarId: 'referenceSidebar',
            position: 'left',
            label: 'Reference',
          },
          {
            href: 'https://github.com/SOPTIM/OpenCGMES',
            label: 'GitHub',
            position: 'right',
          },
          ...(build.shortSha
            ? [
                {
                  type: 'html',
                  position: 'right',
                  value: `<a class="navbar__item navbar__link ocg-build-badge" href="https://github.com/SOPTIM/OpenCGMES/commit/${build.fullSha}" target="_blank" rel="noopener noreferrer" title="Documentation built from ${build.branch || 'main'} @ ${build.shortSha} on ${build.date}">${build.shortSha}</a>`,
                },
              ]
            : []),
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Documentation',
            items: [
              { label: 'Introduction', to: '/' },
              { label: 'Getting Started', to: '/getting-started' },
              { label: 'CIMXML', to: '/cimxml/overview' },
              { label: 'CIMVocabCheck', to: '/cimvocabcheck/overview' },
              { label: 'CIMNotebook', to: '/cimnotebook/overview' },
            ],
          },
          {
            title: 'Project',
            items: [
              { label: 'GitHub', href: 'https://github.com/SOPTIM/OpenCGMES' },
              { label: 'Issues', href: 'https://github.com/SOPTIM/OpenCGMES/issues' },
              { label: 'Releases', href: 'https://github.com/SOPTIM/OpenCGMES/releases' },
              { label: 'Developer Guide', to: '/developer-guide/overview' },
            ],
          },
          {
            title: 'Reference',
            items: [
              { label: 'CGMES Background', to: '/reference/cgmes-background' },
              { label: 'FAQ', to: '/reference/faq' },
              { label: 'License', href: 'https://github.com/SOPTIM/OpenCGMES/blob/main/LICENSE' },
              { label: 'Legal Notice', href: 'https://www.soptim.de/en/legal-notice/' },
            ],
          },
        ],
        copyright: `Copyright © 2024-${new Date().getFullYear()} SOPTIM AG. Licensed under Apache 2.0.<br/>${buildLine}`,
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ['java', 'bash', 'json', 'turtle', 'sparql', 'kotlin'],
      },
      docs: {
        sidebar: {
          hideable: true,
          autoCollapseCategories: false,
        },
      },
    }),
};

export default config;
