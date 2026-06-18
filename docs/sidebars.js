// @ts-check

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  overviewSidebar: [
    'intro',
    'getting-started',
  ],

  cimxmlSidebar: [
    'cimxml/overview',
    'cimxml/installation',
    'cimxml/quick-start',
    'cimxml/profiles-and-datatypes',
    'cimxml/difference-models',
    'cimxml/sparql-queries',
    'cimxml/architecture',
    'cimxml/library-usage',
    'cimxml/performance',
    'cimxml/compliance',
  ],

  cimvocabcheckSidebar: [
    'cimvocabcheck/overview',
    'cimvocabcheck/getting-started',
    'cimvocabcheck/configuration',
    'cimvocabcheck/validation-checks',
    'cimvocabcheck/architecture',
    'cimvocabcheck/library-and-tests',
    'cimvocabcheck/api',
    'cimvocabcheck/endpoints',
    'cimvocabcheck/explain-query',
    'cimvocabcheck/cli',
    'cimvocabcheck/language-server',
    'cimvocabcheck/limitations',
  ],

  cimnotebookSidebar: [
    'cimnotebook/overview',
    'cimnotebook/vscode',
    'cimnotebook/intellij',
    'cimnotebook/sparql-notebooks',
    'cimnotebook/troubleshooting',
  ],

  developerSidebar: [
    'developer-guide/overview',
    'developer-guide/building',
    'developer-guide/testing',
    'developer-guide/code-style',
    'developer-guide/ci-and-releases',
    'developer-guide/contributing',
  ],

  referenceSidebar: [
    'reference/cgmes-background',
    'reference/faq',
  ],
};

export default sidebars;
