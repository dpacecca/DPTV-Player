import fs from 'node:fs';
import path from 'node:path';

/**
 * Which DPTV-Server login each single-sign-on person uses, by the provider's subject id. Kept in the data
 * folder, each login sealed with the session key (the file alone gives nothing away).
 */
export function createLinkStore(dataDir, sessions) {
  const file = path.join(dataDir, 'sso-links.json');
  let links = {};
  try {
    links = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    links = {};
  }
  function save() {
    try {
      fs.mkdirSync(dataDir, { recursive: true });
      fs.writeFileSync(`${file}.tmp`, JSON.stringify(links), { mode: 0o600 });
      fs.renameSync(`${file}.tmp`, file);
    } catch (err) {
      console.warn(`[sso] could not save the linked logins (${err.code})`);
    }
  }
  return {
    get(sub) {
      const sealed = links[sub];
      if (!sealed) return null;
      const login = sessions.open(sealed);
      return login ? { server: login.server, username: login.username, password: login.password } : null;
    },
    set(sub, login) {
      // Sealed with a far expiry: a link lasts until it is replaced or removed.
      links[sub] = sessions.seal({ ...login, exp: Date.now() + 100 * 365 * 24 * 60 * 60 * 1000 });
      save();
    },
    remove(sub) {
      delete links[sub];
      save();
    },
  };
}
