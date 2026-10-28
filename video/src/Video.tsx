import React from 'react';
import {AbsoluteFill, Audio, Img, Sequence, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import '@fontsource/roboto-flex';
import timeline from './timeline.json';
import barMeta from './barMeta.json';
import {C, FONT, Icon, ease} from './ui';

type Word = {w: string; start: number; end: number};
type Scene = {id: string; title: string; text: string; audio: string | null; lead: number; frames: number; words: Word[]};
/** What RenderVideoTest recorded alongside the bar's frames. */
type Meta = {
  frames: number; w: number; h: number;
  inset: number[];
  fieldStates: {t: string; d: boolean}[];
  field: number[];
  fingers: {at: number; hold: number; path: [number, number, number][]}[];
  sent: {at: number; text: string}[];
};
const scenes = timeline as Scene[];
const metas = barMeta as unknown as Record<string, Meta>;

export const FPS = 30;
export const totalFrames = scenes.reduce((a, s) => a + s.frames, 0);

// the phone screen inside the video: the bar's frames are rendered at exactly this size
const SX = 70, SY = 520, SW = 940, SH = 1330;

/** Frame (within the scene) when the narration reaches the first word starting with [w]. */
const at = (s: Scene, w: string, after = 0) => {
  const i = s.words.findIndex((x, k) => k >= after && x.w.toLowerCase().replace(/[^a-z']/g, '').startsWith(w.toLowerCase()));
  return Math.round((s.lead + (i >= 0 ? s.words[i].start : 0)) * FPS);
};

const Caption: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const t = f / FPS - s.lead;
  return (
    // a longer line steps down a size, so it stays above the phone (at most five lines)
    <div style={{position: 'absolute', left: 80, right: 80, top: 250, font: `400 ${s.text.length > 180 ? 38 : 46}px/1.32 ${FONT}`, color: C.text}}>
      {s.words.map((w, i) => <span key={i} style={{opacity: ease(t, w.start - 0.05, w.start + 0.12, 0.28, 1)}}>{w.w} </span>)}
    </div>
  );
};

const Title: React.FC<{s: Scene; f: number}> = ({s, f}) => (
  <div style={{position: 'absolute', left: 80, right: 80, top: 120, font: `700 76px ${FONT}`, color: C.text, letterSpacing: -1,
    opacity: ease(f, 0, 10), transform: `translateY(${(1 - ease(f, 0, 14)) * 24}px)`}}>{s.title}</div>
);

const Caret: React.FC<{f: number}> = ({f}) => <span style={{display: 'inline-block', width: 3, height: 44, background: C.pill, verticalAlign: 'middle', marginLeft: 2, opacity: Math.floor(f / 15) % 2 ? 0.2 : 1}} />;

/** The chat app being typed into: its text box sits on top of the keyboard, wherever that is. */
const Chat: React.FC<{m: Meta | undefined; f: number}> = ({m, f}) => {
  const i = Math.min(f, (m?.frames ?? 1) - 1);
  const inset = m ? m.inset[i] : 0;
  const field = m ? m.fieldStates[m.field[i]] : {t: '', d: false};
  const sent = (m?.sent ?? []).filter((x) => x.at <= f);
  const fieldBottom = inset + 22;
  return (
    <>
      <div style={{position: 'absolute', left: 0, right: 0, top: 0, height: 140, background: '#E8ECF7', display: 'flex', alignItems: 'center', padding: '0 40px', gap: 24}}>
        <div style={{width: 76, height: 76, borderRadius: 38, background: '#B9C7E6', display: 'flex', alignItems: 'center', justifyContent: 'center', font: `600 34px ${FONT}`, color: '#24324F'}}>J</div>
        <div style={{font: `500 40px ${FONT}`, color: C.text}}>Jamie</div>
      </div>
      <div style={{position: 'absolute', left: 36, right: 36, bottom: fieldBottom + 128, display: 'flex', flexDirection: 'column', gap: 20}}>
        <div style={{alignSelf: 'flex-start', maxWidth: '75%', background: C.bubbleThem, borderRadius: 40, padding: '22px 32px', font: `400 38px/1.3 ${FONT}`, color: C.text}}>Hike tomorrow? When should we meet?</div>
        {sent.map((x, k) => {
          const pop = spring({frame: f - x.at, fps: FPS, config: {damping: 16}});
          return <div key={k} style={{alignSelf: 'flex-end', maxWidth: '78%', background: C.bubbleMe, borderRadius: 40, padding: '22px 32px', font: `400 38px/1.3 ${FONT}`, color: '#fff', transform: `scale(${0.85 + 0.15 * pop})`, opacity: pop, transformOrigin: 'bottom right'}}>{x.text}</div>;
        })}
      </div>
      <div style={{position: 'absolute', left: 30, right: 30, bottom: fieldBottom, minHeight: 100, borderRadius: 50, background: '#E9EDF7', display: 'flex', alignItems: 'center', padding: '18px 36px', gap: 20}}>
        <div style={{flex: 1, font: `400 38px/1.3 ${FONT}`, color: C.text}}>
          {field.t ? <span style={field.d ? {textDecoration: 'underline', textDecorationColor: '#0005', textUnderlineOffset: 6} : {}}>{field.t}</span> : <span style={{color: C.muted}}>Message</span>}
          {field.t ? <Caret f={f} /> : null}
        </div>
        <div style={{width: 80, height: 80, borderRadius: 40, background: C.bubbleMe, display: 'flex', alignItems: 'center', justifyContent: 'center'}}><Icon name="send" size={40} color="#fff" /></div>
      </div>
    </>
  );
};

/** The real bar, filmed by RenderVideoTest: one transparent frame per video frame, covering the screen. */
const BarFrames: React.FC<{id: string; m: Meta | undefined; f: number; rise?: number}> = ({id, m, f, rise = 0}) => {
  if (!m) return null;
  const i = Math.min(f, m.frames - 1);
  return <Img src={staticFile(`bar/${id}/${String(i).padStart(4, '0')}.png`)} style={{position: 'absolute', left: 0, top: rise, width: SW, height: SH}} />;
};

/** Fingertips where the script touched the bar: a press with a ripple, following any drag. */
const Fingers: React.FC<{m: Meta | undefined; f: number}> = ({m, f}) => (
  <>
    {(m?.fingers ?? []).map((g, k) => {
      const t = f - g.at;
      if (t < -10 || t > g.hold + 22) return null;
      // where along its path the finger is now
      let x = g.path[0][1], y = g.path[0][2];
      for (let j = 1; j < g.path.length; j++) {
        const [f0, x0, y0] = g.path[j - 1], [f1, x1, y1] = g.path[j];
        if (f >= f1) { x = x1; y = y1; } else if (f > f0) { const u = (f - f0) / (f1 - f0); x = x0 + (x1 - x0) * u; y = y0 + (y1 - y0) * u; break; }
      }
      const appear = ease(t, -10, 0), leave = 1 - ease(t, g.hold + 8, g.hold + 22);
      const ripple = Math.max(0, Math.min(1, t / 14));
      const press = spring({frame: t, fps: FPS, config: {damping: 14}});
      return (
        <div key={k} style={{position: 'absolute', left: x, top: y, pointerEvents: 'none'}}>
          <div style={{position: 'absolute', left: -70 * ripple, top: -70 * ripple, width: 140 * ripple, height: 140 * ripple, borderRadius: '50%', border: `4px solid ${C.pill}`, opacity: (1 - ripple) * 0.6 * (t >= 0 ? 1 : 0)}} />
          <div style={{position: 'absolute', left: -38, top: -38, width: 76, height: 76, borderRadius: '50%', background: '#ffffff', boxShadow: '0 6px 24px #0003', opacity: 0.92 * appear * leave, transform: `scale(${1.15 - 0.15 * press})`, border: '3px solid #0000001a'}} />
        </div>
      );
    })}
  </>
);

const Screen: React.FC<{children: React.ReactNode; f: number; enter?: boolean}> = ({children, f, enter}) => {
  const {fps} = useVideoConfig();
  const s = enter ? spring({frame: f - 4, fps, config: {damping: 18}}) : 1;
  return (
    <div style={{position: 'absolute', left: SX, top: SY, width: SW, height: SH, borderRadius: 64, overflow: 'hidden', background: C.screen,
      boxShadow: '0 30px 80px #1b2a4a2e, 0 0 0 10px #ffffff', transform: `translateY(${(1 - s) * 140}px)`, opacity: s}}>
      {children}
    </div>
  );
};

/** Most scenes: the chat, the real bar on it, and the fingertips. */
const Phone: React.FC<{s: Scene; f: number; enter?: boolean; rise?: number; children?: React.ReactNode}> = ({s, f, enter, rise, children}) => {
  const m = metas[s.id];
  return (
    <Screen f={f} enter={enter}>
      <Chat m={m} f={f} />
      <BarFrames id={s.id} m={m} f={f} rise={rise} />
      <Fingers m={m} f={f} />
      {children}
    </Screen>
  );
};

const Intro: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const {fps} = useVideoConfig();
  // the bar lifts in from the bottom as the narration says "slim bar"
  const rise = spring({frame: f - at(s, 'slim'), fps, config: {damping: 16}});
  return <Phone s={s} f={f} enter rise={(1 - rise) * 300} />;
};

const Outro: React.FC<{s: Scene; f: number}> = ({s, f}) => {
  const how = at(s, 'how');
  const hl = ease(f, how, how + 10);
  const logo = ease(f, at(s, 'happy') - 6, at(s, 'happy') + 10);
  return (
    <Screen f={f}>
      <div style={{position: 'absolute', inset: 0, background: '#F2F3FA', padding: '70px 40px'}}>
        <div style={{font: `400 64px ${FONT}`, color: C.text, marginBottom: 50, opacity: 1 - logo}}>Voicebar</div>
        {[['Bar and Gboard', 'Layout, your keys, floating'], ['Voice typing', 'Speech, listening, only my voice'], ['Cleanup', 'Model, sending, the last look'], ['How to use it', 'Watch this video again'], ['Try it', 'A place to practice']].map(([t, sub], i) => (
          <div key={t} style={{background: i === 3 ? `rgba(216,226,255,${0.4 + 0.6 * hl})` : '#fff', borderRadius: i === 0 ? '44px 44px 10px 10px' : i === 4 ? '10px 10px 44px 44px' : 10, marginBottom: 6, padding: '34px 40px', opacity: 1 - logo,
            boxShadow: i === 3 ? `0 0 0 ${6 * hl}px ${C.pill}55` : 'none', transform: i === 3 ? `scale(${1 + 0.03 * hl})` : 'none'}}>
            <div style={{font: `500 40px ${FONT}`, color: C.text}}>{t}</div>
            <div style={{font: `400 32px ${FONT}`, color: C.muted, marginTop: 6}}>{sub}</div>
          </div>
        ))}
      </div>
      <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 50, opacity: logo, background: C.screen}}>
        <div style={{width: 200, height: 200, borderRadius: 100, background: C.pill, display: 'flex', alignItems: 'center', justifyContent: 'center', transform: `scale(${0.8 + 0.2 * logo})`}}><Icon name="mic" size={110} color="#fff" /></div>
        <div style={{font: `700 96px ${FONT}`, color: C.text, letterSpacing: -2}}>Voicebar</div>
      </div>
      <div style={{position: 'absolute', inset: 0, opacity: logo}}><BarFrames id={s.id} m={metas[s.id]} f={f} /></div>
    </Screen>
  );
};

const Views: Record<string, React.FC<{s: Scene; f: number}>> = {intro: Intro, outro: Outro};

const SceneView: React.FC<{s: Scene}> = ({s}) => {
  const f = useCurrentFrame();
  const V = Views[s.id];
  const fadeOut = 1 - ease(f, s.frames - 8, s.frames);
  return (
    <AbsoluteFill style={{opacity: s.id === 'outro' ? 1 : fadeOut}}>
      <Title s={s} f={f} />
      <Caption s={s} f={f} />
      {V ? <V s={s} f={f} /> : <Phone s={s} f={f} />}
      {s.audio ? <Sequence from={Math.round(s.lead * FPS)}><Audio src={staticFile(s.audio)} /></Sequence> : null}
    </AbsoluteFill>
  );
};

export const Welcome: React.FC = () => {
  let from = 0;
  return (
    <AbsoluteFill style={{background: `linear-gradient(180deg, ${C.page}, #E4E8F6)`, fontFamily: FONT}}>
      {scenes.map((s) => {
        const seq = <Sequence key={s.id} from={from} durationInFrames={s.frames}><SceneView s={s} /></Sequence>;
        from += s.frames;
        return seq;
      })}
    </AbsoluteFill>
  );
};

