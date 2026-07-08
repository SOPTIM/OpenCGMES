import React from 'react';
import styles from './styles.module.css';

/**
 * Presentational install card shared by the VS Code and JetBrains widgets so
 * both marketplaces render with an identical look: name, marketplace line,
 * a version badge, and one or more call-to-action links.
 *
 * @param {string} name         product name shown as the card title
 * @param {string} marketplace  publisher / marketplace line
 * @param {string} [badgeSrc]   URL of a version badge image
 * @param {string} [badgeAlt]   alt text for the badge
 * @param {Array<{label: string, href: string, primary?: boolean, external?: boolean}>} actions
 */
export default function MarketplaceCard({name, marketplace, badgeSrc, badgeAlt, actions = []}) {
  return (
    <div className={styles.card}>
      <div className={styles.body}>
        <span className={styles.title}>{name}</span>
        <span className={styles.publisher}>{marketplace}</span>
        {badgeSrc && <img className={styles.badge} src={badgeSrc} alt={badgeAlt} />}
      </div>
      <div className={styles.actions}>
        {actions.map((action) => (
          <a
            key={action.href + action.label}
            className={action.primary ? styles.primary : styles.secondary}
            href={action.href}
            {...(action.external ? {target: '_blank', rel: 'noopener noreferrer'} : {})}
          >
            {action.label}
          </a>
        ))}
      </div>
    </div>
  );
}
