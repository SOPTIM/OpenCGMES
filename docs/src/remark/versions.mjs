// Remark plugin: replace version tokens with build-time resolved values.
//
// Tokens (safe in both prose and fenced code — no MDX-significant characters):
//   @cimxmlVersion@         → latest released cimxml version
//   @cimvocabcheckVersion@  → latest released cimvocabcheck version
//   @cimnotebookVersion@    → latest released cimnotebook version
//
// Pass the resolved map via options.replacements (token -> value). Replacement runs over text,
// inline code, and fenced code node values, so Maven/Gradle snippets stay plain ```xml/```gradle
// blocks (with copy button + highlighting) while still showing the live version.

import { visit } from 'unist-util-visit';

export default function remarkVersions(options = {}) {
  const entries = Object.entries(options.replacements || {});
  return (tree) => {
    if (entries.length === 0) return;
    visit(tree, ['text', 'code', 'inlineCode'], (node) => {
      if (typeof node.value !== 'string') return;
      let value = node.value;
      for (const [token, replacement] of entries) {
        if (value.includes(token)) value = value.split(token).join(replacement);
      }
      node.value = value;
    });
  };
}
