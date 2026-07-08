import React from 'react';
import MarketplaceCard from './MarketplaceCard';

const EXTENSION_ID = 'soptim-ag.cimnotebook';

/**
 * Install card for the CIMNotebook VS Code extension. The primary action uses a
 * `vscode:` deep link that opens the extension page inside a local VS Code.
 */
export default function VSCodeInstall() {
  return (
    <MarketplaceCard
      name="CIMNotebook"
      marketplace="SOPTIM AG · Visual Studio Marketplace"
      badgeSrc="https://vsmarketplacebadges.dev/version-short/soptim-ag.cimnotebook.svg"
      badgeAlt="CIMNotebook version on the Visual Studio Marketplace"
      actions={[
        {label: 'Install in VS Code', href: `vscode:extension/${EXTENSION_ID}`, primary: true},
        {
          label: 'View on Marketplace',
          href: `https://marketplace.visualstudio.com/items?itemName=${EXTENSION_ID}`,
          external: true,
        },
      ]}
    />
  );
}
