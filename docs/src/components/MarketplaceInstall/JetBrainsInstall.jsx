import React from 'react';
import MarketplaceCard from './MarketplaceCard';

/**
 * Install card for the CIMNotebook JetBrains plugin. Styled identically to the
 * VS Code card; the action links to the JetBrains Marketplace listing (from
 * where the IDE's "Install to IDE" flow takes over).
 *
 * @param {number|string} pluginId numeric Marketplace plugin id (CIMNotebook = 32789)
 * @param {string} [slug]          Marketplace URL slug
 */
export default function JetBrainsInstall({pluginId = 32789, slug = 'cimnotebook'}) {
  const marketplaceUrl = `https://plugins.jetbrains.com/plugin/${pluginId}-${slug}`;
  return (
    <MarketplaceCard
      name="CIMNotebook"
      marketplace="SOPTIM AG · JetBrains Marketplace"
      badgeSrc={`https://img.shields.io/jetbrains/plugin/v/${pluginId}?label=JetBrains%20Marketplace&color=%234c8eda`}
      badgeAlt="CIMNotebook version on the JetBrains Marketplace"
      actions={[{label: 'Get from Marketplace', href: marketplaceUrl, primary: true, external: true}]}
    />
  );
}
