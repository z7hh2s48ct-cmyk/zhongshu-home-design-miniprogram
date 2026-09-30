'use strict';
const RESOLUTIONS = [
  { value: '2K', label: '2K', detail: '清晰出图' },
  { value: '4K', label: '4K', detail: '高清细节' }
];
const ORIENTATION_BASE = [
  { value: 'LANDSCAPE', label: '横屏', ratio: '16:9' },
  { value: 'PORTRAIT', label: '竖屏', ratio: '9:16' }
];
const PIXELS = {
  '2K': { LANDSCAPE: '2048 × 1152', PORTRAIT: '1152 × 2048' },
  '4K': { LANDSCAPE: '3840 × 2160', PORTRAIT: '2160 × 3840' }
};

function defaults(stage) {
  return { resolution: '4K', orientation: 'LANDSCAPE' };
}

function selection(stage, value) {
  const fallback = defaults(stage);
  const requestedResolution = value && value.resolution;
  const requestedOrientation = value && value.orientation;
  const resolution = requestedResolution === '2K' || requestedResolution === '4K' ? requestedResolution : fallback.resolution;
  const orientation = requestedOrientation === 'LANDSCAPE' || requestedOrientation === 'PORTRAIT' ? requestedOrientation : fallback.orientation;
  return { resolution: resolution, orientation: orientation, outputPixels: PIXELS[resolution][orientation] };
}

function orientations(resolution) {
  const valid = resolution === '4K' ? '4K' : '2K';
  return ORIENTATION_BASE.map(function (item) {
    return { value: item.value, label: item.label, ratio: item.ratio, pixels: PIXELS[valid][item.value] };
  });
}

module.exports = { RESOLUTIONS, defaults, selection, orientations };
