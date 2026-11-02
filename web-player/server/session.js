import crypto from 'node:crypto';

/**
 * The signed-in login, sealed (AES-256-GCM) into an HttpOnly cookie: the browser holds it but cannot read
 * it, and the server keeps no session table, so a restart signs nobody out.
 */
export function createSessions(key, { secure = false, maxAgeDays = 30 } = {}) {
  const COOKIE = 'dptv_session';
  const maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000;

  function seal(obj) {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    const body = Buffer.concat([cipher.update(JSON.stringify(obj), 'utf8'), cipher.final()]);
    return Buffer.concat([iv, cipher.getAuthTag(), body]).toString('base64url');
  }

  function open(token) {
    try {
      const raw = Buffer.from(token, 'base64url');
      const decipher = crypto.createDecipheriv('aes-256-gcm', key, raw.subarray(0, 12));
      decipher.setAuthTag(raw.subarray(12, 28));
      const obj = JSON.parse(Buffer.concat([decipher.update(raw.subarray(28)), decipher.final()]).toString('utf8'));
      if (!obj || typeof obj.exp !== 'number' || obj.exp < Date.now()) return null;
      return obj;
    } catch {
      return null;
    }
  }

  return {
    seal,
    open,
    start(res, login) {
      res.cookie(COOKIE, seal({ ...login, exp: Date.now() + maxAgeMs }), {
        httpOnly: true, sameSite: 'lax', secure, maxAge: maxAgeMs, path: '/',
      });
    },
    end(res) {
      res.clearCookie(COOKIE, { path: '/' });
    },
    /** Express middleware: req.login, or 401. */
    required(req, res, next) {
      const login = req.cookies?.[COOKIE] ? open(req.cookies[COOKIE]) : null;
      if (!login) return res.status(401).json({ error: 'Not signed in' });
      req.login = login;
      next();
    },
  };
}
