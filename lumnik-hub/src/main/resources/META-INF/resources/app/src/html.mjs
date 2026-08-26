// The one local module every component imports — houses the htm+preact+hooks vendor bridge,
// so components never reach into vendor/ directly (one seam, easy to re-pin later).
import htm from '../vendor/htm.mjs';
import { h, render, Fragment } from '../vendor/preact.mjs';
import { useState, useEffect, useRef } from '../vendor/hooks.mjs';

export const html = htm.bind(h);
export { render, Fragment, useState, useEffect, useRef };
