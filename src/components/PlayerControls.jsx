import React, { useEffect, useRef, useState } from 'react';
import { Play, Pause } from './icons';
import './PlayerControls.css';

const formatTime = seconds => {
  const value = Math.max(0, Math.floor(seconds || 0));
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`;
};
const Icon = ({ children }) => <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>;

export default function PlayerControls({ videoRef, stageRef, source, available, rate, onRateChange, onError, portrait = false, windowFill = false, onWindowFill }) {
  const [media, setMedia] = useState({ paused: true, time: 0, duration: 0, muted: false, volume: 1 });
  const [fullscreen, setFullscreen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [controlsHidden, setControlsHidden] = useState(false);
  const controlsRef = useRef(null);
  const menuRef = useRef(null);
  const menuButtonRef = useRef(null);
  const speedRef = useRef(null);
  const expanded = fullscreen || windowFill;

  useEffect(() => {
    if (!windowFill) return;
    const escape = event => { if (event.key === 'Escape') onWindowFill(false); };
    window.addEventListener('keydown', escape);
    return () => window.removeEventListener('keydown', escape);
  }, [windowFill, onWindowFill]);

  useEffect(() => {
    const stage = stageRef.current;
    let timer;
    let dragging = false;
    const reveal = () => {
      clearTimeout(timer);
      setControlsHidden(false);
      if (portrait && available && !media.paused && !menuOpen && !dragging) {
        timer = setTimeout(() => {
          if (!controlsRef.current?.querySelector(':focus-visible')) setControlsHidden(true);
        }, 2000);
      }
    };
    const startDrag = event => { if (event.target.closest('input[type="range"]')) { dragging = true; reveal(); } };
    const stopDrag = () => { if (dragging) { dragging = false; reveal(); } };
    stage?.addEventListener('pointermove', reveal);
    stage?.addEventListener('pointerdown', startDrag);
    stage?.addEventListener('focusin', reveal);
    stage?.addEventListener('focusout', reveal);
    window.addEventListener('keydown', reveal, true);
    window.addEventListener('keyup', reveal, true);
    window.addEventListener('pointerup', stopDrag);
    window.addEventListener('pointercancel', stopDrag);
    window.addEventListener('blur', stopDrag);
    reveal();
    return () => {
      clearTimeout(timer);
      stage?.removeEventListener('pointermove', reveal);
      stage?.removeEventListener('pointerdown', startDrag);
      stage?.removeEventListener('focusin', reveal);
      stage?.removeEventListener('focusout', reveal);
      window.removeEventListener('keydown', reveal, true);
      window.removeEventListener('keyup', reveal, true);
      window.removeEventListener('pointerup', stopDrag);
      window.removeEventListener('pointercancel', stopDrag);
      window.removeEventListener('blur', stopDrag);
    };
  }, [portrait, available, media.paused, menuOpen, source, stageRef]);

  useEffect(() => {
    setMenuOpen(false);
    const video = videoRef.current;
    if (!video) return;
    const sync = () => setMedia({ paused: video.paused, time: Number.isFinite(video.currentTime) ? video.currentTime : 0,
      duration: Number.isFinite(video.duration) && video.duration > 0 ? video.duration : 0, muted: video.muted, volume: video.volume });
    const events = ['play', 'pause', 'ended', 'timeupdate', 'loadedmetadata', 'durationchange', 'volumechange', 'emptied'];
    events.forEach(name => video.addEventListener(name, sync));
    sync();
    return () => events.forEach(name => video.removeEventListener(name, sync));
  }, [videoRef, source, available]);

  useEffect(() => {
    const sync = () => setFullscreen(document.fullscreenElement === stageRef.current);
    document.addEventListener('fullscreenchange', sync);
    sync();
    return () => document.removeEventListener('fullscreenchange', sync);
  }, [stageRef]);

  useEffect(() => {
    if (!menuOpen) return;
    speedRef.current?.focus();
    const outside = event => { if (!menuRef.current?.contains(event.target)) setMenuOpen(false); };
    const escape = event => {
      if (event.key !== 'Escape') return;
      event.preventDefault(); event.stopPropagation();
      setMenuOpen(false); menuButtonRef.current?.focus();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [menuOpen]);

  const togglePlay = async () => {
    const video = videoRef.current;
    if (!available || !video) return;
    try { if (video.paused) await video.play(); else video.pause(); }
    catch { onError?.('暂时无法播放，请重试本集'); }
  };
  const seek = event => {
    const video = videoRef.current;
    if (!available || !video || !media.duration) return;
    try { video.currentTime = Math.max(0, Math.min(media.duration, Number(event.target.value))); }
    catch { onError?.('暂时无法跳转，请稍后重试'); }
  };
  const changeVolume = event => {
    const video = videoRef.current;
    if (!video) return;
    video.volume = Number(event.target.value);
    video.muted = video.volume === 0;
  };
  const toggleFullscreen = async () => {
    try {
      if (portrait) { onWindowFill(!windowFill); return; }
      if (document.fullscreenElement === stageRef.current) await document.exitFullscreen();
      else await stageRef.current.requestFullscreen();
    } catch { onError?.('无法进入或退出全屏，请重试'); }
  };

  return (
    <div ref={controlsRef} className={`player-controls ${controlsHidden ? 'controls-hidden' : ''}`} role="group" aria-label="视频播放控制">
      <input className="player-controls-seek" type="range" aria-label="播放进度" aria-valuetext={`${formatTime(media.time)} / ${formatTime(media.duration)}`} min="0" max={media.duration || 0} step="0.1" value={Math.min(media.time, media.duration)} disabled={!available || !media.duration} onChange={seek} />
      <div className="player-controls-row">
        <button type="button" data-player-control aria-label={media.paused ? '播放' : '暂停'} title="播放/暂停（空格）" disabled={!available} onClick={togglePlay}>{media.paused ? <Play size={18} /> : <Pause size={18} />}</button>
        <span className="player-controls-time">{formatTime(media.time)} / {formatTime(media.duration)}</span>
        <div className="player-controls-volume">
          <button type="button" data-player-control aria-label={media.muted ? '取消静音' : '静音'} onClick={() => { const video = videoRef.current; if (video) video.muted = !video.muted; }}>
            <Icon><path d="M11 5 6 9H3v6h3l5 4Z" />{media.muted ? <path d="m16 9 5 6m0-6-5 6" /> : <path d="M15 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14" />}</Icon>
          </button>
          <input type="range" aria-label="音量" aria-valuetext={`${Math.round((media.muted ? 0 : media.volume) * 100)}%`} min="0" max="1" step="0.05" value={media.muted ? 0 : media.volume} onChange={changeVolume} />
        </div>
        <button type="button" data-player-control aria-label={expanded ? '退出全屏' : '全屏'} title={expanded ? '退出全屏' : portrait ? '填满当前窗口' : '全屏'} onClick={toggleFullscreen}>
          <Icon><path d={expanded ? 'M9 3v6H3m12-6v6h6M9 21v-6H3m12 6v-6h6' : 'M9 3H3v6m12-6h6v6M3 15v6h6m12-6v6h-6'} /></Icon>
        </button>
        <button type="button" data-player-control className="player-controls-rate" aria-label="增加播放速度" title={`播放速度 ${rate}×，点击增加 0.25 倍，4 倍后回到 1 倍`} onClick={() => onRateChange(rate >= 4 ? 1 : Math.min(4, rate + 0.25))}>{rate}×</button>
        <div className="player-controls-options" data-player-menu ref={menuRef}>
          <button type="button" ref={menuButtonRef} aria-label="更多播放选项" title={`播放速度 ${rate}×`} aria-haspopup="dialog" aria-expanded={menuOpen} onClick={() => setMenuOpen(open => !open)}>
            <Icon><circle cx="12" cy="5" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="12" cy="19" r="1" /></Icon>
          </button>
          {menuOpen && <div className="player-controls-menu" role="dialog" aria-label="播放选项">
            <label>播放速度<select ref={speedRef} aria-label="播放速度" value={rate} onChange={event => onRateChange(Number(event.target.value))}>
              {Array.from({ length: 16 }, (_, index) => (index + 1) / 4).map(value => <option key={value} value={value}>{value}×</option>)}
            </select></label>
            <span>空格：播放 / 暂停</span>
            <span>← 快退 5 秒 · → 快进 5 秒</span>
            <span>↑ 上一集 · ↓ 下一集</span>
            <span>S 减速 · D 加速，每次 0.25 倍</span>
          </div>}
        </div>
      </div>
    </div>
  );
}
