const esbuild = require('esbuild');

const prod = process.argv.includes('--production');

esbuild.build({
    entryPoints: ['src/extension.ts'],
    bundle: true,
    outfile: 'out/extension.js',
    external: ['vscode'],       // provided by VS Code at runtime — never bundle
    format: 'cjs',
    platform: 'node',
    target: 'node20',
    sourcemap: !prod,
    minify: prod,
}).catch(() => process.exit(1));
