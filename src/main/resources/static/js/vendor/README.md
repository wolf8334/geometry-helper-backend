# Vendored browser dependencies

These files are served locally so the PWA can render Markdown and math while offline.

| File | Package | Version | License |
| --- | --- | --- | --- |
| `marked.min.js` | [marked](https://github.com/markedjs/marked) | 15.0.7 | MIT |
| `purify.min.js` | [DOMPurify](https://github.com/cure53/DOMPurify) | 3.2.4 | Apache-2.0 / MPL-2.0 |
| `katex.min.js` + `../../css/vendor/katex.min.css` | [KaTeX](https://github.com/KaTeX/KaTeX) | 0.19.1 | MIT |

`marked` converts Markdown to HTML, DOMPurify sanitizes that HTML before it is inserted into the result area, and KaTeX renders the LaTeX placeholders after sanitization. KaTeX fonts live in `css/vendor/fonts/` and are cached by the service worker for offline use. See `katex.LICENSE.txt` for the KaTeX license.
