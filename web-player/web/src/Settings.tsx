import { FormEvent, useState } from 'react';
import { api, RecorderConfig } from './api';
import { browserCodecs } from './codecs';
import { BUFFER_PRESETS, BufferMode, loadBufferMode, saveBufferMode } from './playback';

/** Settings: the DPTV-Recorder that makes recordings for this login, and the account. */
export function Settings({ recorder, onRecorder, user, sso, version, onClose, onSignOut, onChangeLogin }: {
  recorder: RecorderConfig | null;
  onRecorder: (c: RecorderConfig) => void;
  user: string;
  sso: boolean;
  version: string;
  onClose: () => void;
  onSignOut: () => void;
  onChangeLogin: () => void;
}) {
  const [url, setUrl] = useState(recorder?.source === 'user' ? recorder.url : '');
  const [apiKey, setApiKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const c = await api.dvrSetConfig(url, apiKey);
      onRecorder(c);
      setApiKey('');
      setMessage({ ok: true, text: `Connected. ${c.recordings} recording${c.recordings === 1 ? '' : 's'} on this recorder.` });
    } catch (err) {
      setMessage({ ok: false, text: (err as Error).message });
    } finally {
      setBusy(false);
    }
  }

  async function clear() {
    setBusy(true);
    try {
      onRecorder(await api.dvrClearConfig());
      setUrl('');
      setMessage({ ok: true, text: 'Removed.' });
    } catch (err) {
      setMessage({ ok: false, text: (err as Error).message });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-back" onClick={onClose}>
      <div className="modal settings" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <div className="modal-title">Settings</div>
          <button className="btn ghost" onClick={onClose} aria-label="Close">✕</button>
        </div>

        <section>
          <h3>Recording server</h3>
          <p className="muted">
            DPTV-Recorder records for you, whether this page is open or not - the same server DPTV-Player on
            your TV uses. Make an API key in DPTV-Recorder → Settings → Account → API keys.
          </p>
          {recorder?.source === 'server' && (
            <p className="note">Set for everyone on this DPTV Web: <span className="mono">{recorder.url}</span>. Enter your own below to use a different one.</p>
          )}
          {recorder?.source === 'user' && <p className="note">Connected to <span className="mono">{recorder.url}</span>.</p>}
          <form className="form" onSubmit={save}>
            <label>Address<input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="http://192.168.1.10:3000" autoComplete="off" /></label>
            <label>API key<input value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder={recorder?.source === 'user' ? 'Enter again to change' : 'dptv_…'} type="password" autoComplete="off" /></label>
            <div className="row-actions">
              <button className="btn primary" disabled={busy || !url || !apiKey}>{busy ? 'Checking…' : 'Save'}</button>
              {recorder?.source === 'user' && <button type="button" className="btn ghost danger" disabled={busy} onClick={clear}>Remove</button>}
            </div>
          </form>
          {message && <p className={message.ok ? 'ok' : 'error'}>{message.text}</p>}
        </section>

        <section>
          <h3>Playback in this browser</h3>
          <Buffer />
          <Codecs />
        </section>

        <section>
          <h3>Account</h3>
          <p className="muted">Signed in as <b>{user}</b>.</p>
          <div className="row-actions">
            {sso && <button className="btn ghost" onClick={onChangeLogin}>Change DPTV-Server login</button>}
            <button className="btn ghost danger" onClick={onSignOut}>Sign out</button>
          </div>
        </section>
        <p className="muted version">DPTV Web {version}</p>
      </div>
    </div>
  );
}

/**
 * Live TV's buffer, for this browser only: a phone on 4G/5G wants more in hand than a PC on cable. A change
 * applies to the channel playing at once (it re-tunes).
 */
function Buffer() {
  const [mode, setMode] = useState<BufferMode>(loadBufferMode);
  const choose = (m: BufferMode) => { setMode(m); saveBufferMode(m); };
  return (
    <div className="buffer-setting">
      <div className="setting-label">Live TV buffer</div>
      <div className="choice-list" role="radiogroup" aria-label="Live TV buffer">
        {BUFFER_PRESETS.map((p) => (
          <label key={p.mode} className={`choice${mode === p.mode ? ' on' : ''}`}>
            <input type="radio" name="buffer" checked={mode === p.mode} onChange={() => choose(p.mode)} />
            <span className="choice-text">
              <span className="choice-title">{p.label}</span>
              <span className="muted">{p.hint}</span>
            </span>
          </label>
        ))}
      </div>
      <p className="muted">Applies to this browser only, so a phone and a PC can each keep their own. More buffer means fewer stalls on an unsteady connection, a few more seconds behind live, and a slower start.</p>
    </div>
  );
}

/** Which formats this browser decodes, and how to get HEVC / 4K and Dolby audio when it can't. */
function Codecs() {
  const c = browserCodecs();
  const row = (name: string, ok: boolean) => (
    <li key={name}><span className={ok ? 'ok' : 'error'}>{ok ? '✓' : '✕'}</span> {name}</li>
  );
  return (
    <>
      <ul className="codecs">
        {row('H.264 (most HD channels)', c.h264)}
        {row('HEVC / H.265 (most 4K)', c.hevc)}
        {row('10-bit HEVC (4K HDR)', c.hevc10)}
        {row('AC-3 audio (Dolby Digital)', c.ac3)}
        {row('E-AC-3 audio (Dolby Digital Plus)', c.eac3)}
      </ul>
      {(!c.hevc || !c.hevc10) && (
        <p className="muted">
          HEVC needs hardware decoding: Chrome or Edge on a PC whose graphics can decode HEVC (most since 2016,
          with up-to-date drivers; on Windows also install Microsoft's free <i>HEVC Video Extensions</i> for Edge),
          or Safari on a Mac. Check chrome://gpu → "Video Acceleration" lists HEVC.
        </p>
      )}
      {(!c.ac3 || !c.eac3) && (
        <p className="muted">Dolby audio (AC-3 / E-AC-3) depends on the browser: Edge on Windows and Safari usually have it, Chrome and Firefox usually do not. The list above is what this browser reports.</p>
      )}
    </>
  );
}
