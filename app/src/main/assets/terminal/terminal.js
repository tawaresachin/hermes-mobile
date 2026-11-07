(function () {
  'use strict';

  var Bridge = window.HermesBridge;
  // UMD wrappers differ per package: xterm exports the class directly,
  // the addons export a namespace object.
  var TerminalCtor = window.Terminal && window.Terminal.Terminal
    ? window.Terminal.Terminal : window.Terminal;
  var FitCtor = window.FitAddon && window.FitAddon.FitAddon
    ? window.FitAddon.FitAddon : window.FitAddon;
  var SearchCtor = window.SearchAddon && window.SearchAddon.SearchAddon
    ? window.SearchAddon.SearchAddon : window.SearchAddon;

  var term = new TerminalCtor({
    fontFamily: 'monospace',
    fontSize: 13,
    lineHeight: 1.15,
    scrollback: 10000,
    cursorBlink: true,
    convertEol: false,
    screenReaderMode: false,
    // The search addon highlights matches with decorations, which xterm 6
    // still gates behind the proposed API.
    allowProposedApi: true,
    minimumContrastRatio: 4.5,
    theme: {
      background: '#14161a',
      foreground: '#d7dae0',
      cursor: '#d7dae0',
      cursorAccent: '#14161a',
      selectionBackground: '#3a4252',
      black: '#1c1f26',
      red: '#e06c75',
      green: '#98c379',
      yellow: '#e5c07b',
      blue: '#61afef',
      magenta: '#c678dd',
      cyan: '#56b6c2',
      white: '#d7dae0',
      brightBlack: '#5c6370',
      brightRed: '#ef7b85',
      brightGreen: '#a9d383',
      brightYellow: '#f0cf8b',
      brightBlue: '#7cc0f7',
      brightMagenta: '#d68ce4',
      brightCyan: '#6cc5d1',
      brightWhite: '#ffffff'
    }
  });

  var fit = new FitCtor();
  var search = new SearchCtor();
  term.loadAddon(fit);
  term.loadAddon(search);

  var root = document.getElementById('terminal');
  term.open(root);

  var textarea = term.textarea;
  if (textarea) {
    textarea.setAttribute('aria-label', 'Terminal input');
    textarea.setAttribute('autocorrect', 'off');
    textarea.setAttribute('autocapitalize', 'off');
    textarea.setAttribute('autocomplete', 'off');
    textarea.spellcheck = false;
  }

  // ── Fit: keyboard, rotation, split-screen. The PTY follows on resize.
  // Kotlin calls fit() from onSizeChanged as well, because a WebView that
  // reports its size late never fires the observer and xterm would keep
  // whatever row count it measured first (the "one line" bug).
  var lastCols = 0;
  var lastRows = 0;
  var fitting = false;
  function refit() {
    if (fitting || !root.clientWidth || !root.clientHeight) return false;
    fitting = true;
    var ok = false;
    try {
      fit.fit();
      ok = term.cols > 0 && term.rows > 0;
    } catch (_) {
      // Layout not measurable yet; a retry below picks it up.
    }
    fitting = false;
    if (term.cols !== lastCols || term.rows !== lastRows) {
      lastCols = term.cols;
      lastRows = term.rows;
      Bridge.resized(term.cols, term.rows);
    }
    return ok;
  }
  new ResizeObserver(function () { refit(); }).observe(root);
  window.addEventListener('orientationchange', function () {
    setTimeout(refit, 100);
  });
  // A shell can start before the WebView has its final size; refit until
  // the reported geometry stops changing.
  var retries = 0;
  (function retryFit() {
    if (refit() || retries++ > 20) return;
    setTimeout(retryFit, 200);
  })();

  // ── Input: every keystroke goes straight to the PTY. ──
  term.onData(function (data) { Bridge.input(data); });

  // xterm ignores insertText events (dictation / IME commit paths); feed
  // those through its input queue so nothing is lost.
  (function installInputPatch() {
    if (!textarea) return;
    var handledKey;
    var composing = false;
    var imeKey = false;
    var timer;
    term.onKey(function (e) { handledKey = e.key; });
    textarea.addEventListener('keydown', function (e) { imeKey = e.keyCode === 229; });
    textarea.addEventListener('keyup', function () { handledKey = undefined; imeKey = false; });
    textarea.addEventListener('compositionstart', function () {
      clearTimeout(timer);
      composing = true;
    });
    textarea.addEventListener('compositionend', function () {
      timer = setTimeout(function () { composing = false; }, 0);
    });
    textarea.addEventListener('input', function (e) {
      if (e.data && e.inputType === 'insertText' && !e.isComposing &&
          !composing && !imeKey && e.data !== handledKey) {
        term.input(e.data, true);
      }
      handledKey = undefined;
    });
  })();

  (function installTouch() {
    var start;
    var selecting = false;
    var moved = false;
    var lastY = 0;
    var remainder = 0;
    var timer;
    function position(touch) {
      var screen = root.querySelector('.xterm-screen').getBoundingClientRect();
      return {
        x: touch.clientX,
        y: touch.clientY,
        col: Math.max(0, Math.min(term.cols - 1,
          Math.floor((touch.clientX - screen.left) / (screen.width / term.cols)))),
        row: term.buffer.active.viewportY + Math.max(0, Math.min(term.rows - 1,
          Math.floor((touch.clientY - screen.top) / (screen.height / term.rows))))
      };
    }
    function begin(event) {
      if (event.touches.length !== 1) {
        clearTimeout(timer);
        start = undefined;
        return;
      }
      event.stopImmediatePropagation();
      start = position(event.touches[0]);
      lastY = start.y;
      remainder = 0;
      moved = false;
      selecting = false;
      timer = setTimeout(function () {
        if (!start) return;
        var line = term.buffer.active.getLine(start.row);
        var left = start.col;
        var right = left + 1;
        function isWord(col) {
          return /[\p{L}\p{N}_./~-]/u.test(
            (line && line.getCell(col) && line.getCell(col).getChars()) || '');
        }
        if (line && line.getCell(left) && line.getCell(left).getWidth() === 0 && left > 0) left--;
        if (isWord(left)) {
          while (left > 0 && (isWord(left - 1) ||
            (line && line.getCell(left - 1) && line.getCell(left - 1).getWidth() === 0))) left--;
          while (right < term.cols && (isWord(right) ||
            (line && line.getCell(right) && line.getCell(right).getWidth() === 0))) right++;
        }
        term.select(left, start.row, right - left);
        start.col = left;
        selecting = true;
      }, 450);
    }
    function move(event) {
      if (!start || event.touches.length !== 1) return;
      event.stopImmediatePropagation();
      event.preventDefault();
      var current = position(event.touches[0]);
      if (selecting) {
        var first = start.row * term.cols + start.col;
        var last = current.row * term.cols + current.col;
        var lo = Math.min(first, last);
        term.select(lo % term.cols, Math.floor(lo / term.cols), Math.abs(last - first) + 1);
      } else if (Math.abs(current.y - start.y) + Math.abs(current.x - start.x) > 8) {
        moved = true;
        clearTimeout(timer);
        var screen = root.querySelector('.xterm-screen').getBoundingClientRect();
        remainder += lastY - current.y;
        var lines = Math.trunc(remainder / (screen.height / term.rows));
        if (lines) {
          term.scrollLines(lines);
          remainder -= lines * screen.height / term.rows;
        }
        lastY = current.y;
      }
    }
    function end(event) {
      if (!start) return;
      clearTimeout(timer);
      event.stopImmediatePropagation();
      if (selecting || moved) event.preventDefault();
      else {
        term.clearSelection();
        term.focus();
      }
      start = undefined;
    }
    root.addEventListener('touchstart', begin, { capture: true, passive: false });
    root.addEventListener('touchmove', move, { capture: true, passive: false });
    root.addEventListener('touchend', end, { capture: true, passive: false });
    root.addEventListener('touchcancel', end, { capture: true, passive: false });
  })();

  var DECORATIONS = {
    matchBackground: '#623315',
    matchBorder: '#e8a33d',
    matchOverviewRuler: '#e8a33d',
    activeMatchBackground: '#e8a33d',
    activeMatchBorder: '#ffffff',
    activeMatchColorOverviewRuler: '#ffffff'
  };
  search.onDidChangeResults(function (e) {
    Bridge.findResult(e.resultIndex, e.resultCount);
  });

  function bytesFromBase64(b64) {
    var bin = atob(b64);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  window.HermesTerminal = {
    writeBase64: function (b64) { term.write(bytesFromBase64(b64)); },
    writeText: function (text) { term.write(text); },
    clear: function () { term.reset(); },
    focus: function () { term.focus(); },
    fit: refit,
    find: function (query, incremental, backwards) {
      if (!query) { search.clearDecorations(); return; }
      var options = { incremental: !!incremental, decorations: DECORATIONS };
      if (backwards) search.findPrevious(query, options);
      else search.findNext(query, options);
    },
    clearFind: function () { search.clearDecorations(); },
    copySelection: function () {
      var text = term.getSelection();
      if (text) Bridge.copy(text);
    },
    scrollToBottom: function () { term.scrollToBottom(); }
  };

  // Ready is unconditional: queued PTY output must not depend on the
  // WebView having reported a usable size yet.
  Bridge.ready();
  refit();
})();
