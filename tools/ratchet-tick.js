/* RATCHET TICK. The click from Yeetlist's pull to refresh, which they
   approved on 11 October 2026 ("i absolutely love how the ticking mechanism
   looks and feels and sounds") and want reused in other features and
   projects.

   THE SOUND: half a drum-machine closed hi-hat (six square waves at clashing
   pitches, 600Hz times 2, 3, 4.16, 5.43, 6.79 and 8.21) and half white noise,
   each decaying in 4 to 5ms, through a high-pass at 8.5kHz and a wide
   band-pass at 11.5kHz, at a gain of 0.25. Made in code, so there is no file.

   THE FEEL: one tick per 4px of travel, never closer than 35ms apart. At
   15ms a fast drag played 66 a second and blurred into a low buzz.

   A browser refuses sound a person did not start, so call ready() from a
   pointerup or touchend before the first tick. */
export function createRatchet({ notch = 4, gap = 35, gain = 0.25 } = {}) {
  const HAT = [2, 3, 4.16, 5.43, 6.79, 8.21];
  let audio = null;
  let buffer = null;
  let last = 0;
  let at = 0;

  function ready() {
    if (audio) { if (audio.state === 'suspended') audio.resume().catch(() => {}); return; }
    try {
      audio = new (window.AudioContext || window.webkitAudioContext)();
      const n = Math.round(audio.sampleRate * 0.03);
      buffer = audio.createBuffer(1, n, audio.sampleRate);
      const data = buffer.getChannelData(0);
      let peak = 0;
      for (let i = 0; i < n; i += 1) {
        const t = i / audio.sampleRate;
        let metal = 0;
        for (const r of HAT) metal += Math.sin(2 * Math.PI * 600 * r * t) >= 0 ? 1 : -1;
        const attack = Math.min(1, t / 0.0005);
        data[i] = attack * (0.5 * (metal / HAT.length) * Math.exp(-t / 0.004)
          + 0.5 * (Math.random() * 2 - 1) * Math.exp(-t / 0.005));
        peak = Math.max(peak, Math.abs(data[i]));
      }
      for (let i = 0; i < n; i += 1) data[i] /= peak || 1;
    } catch { audio = null; }
  }

  function tick() {
    if (!audio || audio.state !== 'running') return;
    const now = performance.now();
    if (now - last < gap) return;
    last = now;
    const src = audio.createBufferSource();
    src.buffer = buffer;
    const hp = audio.createBiquadFilter();
    hp.type = 'highpass';
    hp.frequency.value = 8500;
    const bp = audio.createBiquadFilter();
    bp.type = 'bandpass';
    bp.frequency.value = 11500;
    bp.Q.value = 0.5;
    const g = audio.createGain();
    g.gain.value = gain;
    src.connect(hp).connect(bp).connect(g).connect(audio.destination);
    src.start();
  }

  /* Call with the distance travelled; it ticks each time a notch is crossed. */
  function move(distance) {
    const n = Math.floor(distance / notch);
    if (n !== at) { at = n; tick(); }
  }
  function reset() { at = 0; }

  return { ready, tick, move, reset };
}
