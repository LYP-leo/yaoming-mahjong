# Third-party components

The portable simulator carries these unmodified Maven Central artifacts. SHA-256
checksums are pinned in `build_simulator.py` and recorded in `simulator/manifest.json`.
Their license/notice metadata remains inside each original JAR.

| Component | Version | License | Upstream |
|---|---|---|---|
| Jackson databind | 2.18.3 | Apache-2.0 | https://github.com/FasterXML/jackson-databind |
| Jackson core | 2.18.3 | Apache-2.0 | https://github.com/FasterXML/jackson-core |
| Jackson annotations | 2.18.3 | Apache-2.0 | https://github.com/FasterXML/jackson-annotations |
| SLF4J API | 2.0.17 | MIT | https://www.slf4j.org/license.html |

Python dependencies are installed separately, not bundled: PyTorch (BSD-style
license and third-party notices), NumPy (BSD-3-Clause), pytest (MIT).
See the installed packages' upstream license files for their complete terms.

No external mahjong model, Japanese-mahjong rule implementation, user screenshots,
browser assets, production rooms, credentials, or private game logs are included.
The Java rule sources are an exact snapshot of this project's own engine at build time.
`training/java/.../YmReplay.java` is a deliberately separate no-op recorder for
headless training and must not replace the production replay implementation.
